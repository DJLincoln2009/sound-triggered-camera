import Foundation
import Combine
import UIKit

/// Orchestrateur de l'app caméra iOS (périmètre allégé, §7.2).
///
/// Coordonne découverte, canal de commandes, détection sonore, capture et transfert.
/// Particularités iOS :
///  - la **capture vidéo** n'a lieu qu'au premier plan (restriction système) ;
///  - l'**écoute sonore** peut continuer en arrière-plan (mode `audio`), mais un
///    déclenchement en arrière-plan ne peut pas démarrer la caméra : il est signalé au PC.
final class CameraController: NSObject, ObservableObject, CommandClient.Delegate {

    @Published var connected = false
    @Published var serverName: String?
    @Published var recording = false
    @Published var pendingCount = 0
    @Published var lastEvent: String?
    @Published var pairingError: String?
    @Published var running = false

    let settings: AppSettings
    let camera = CameraRecorder()
    private let detector = SoundDetector()
    private let discovery = Discovery()
    private let pending = PendingUploads()
    private let uploader = VideoUploader()
    private var client: CommandClient?
    private var cancellables = Set<AnyCancellable>()

    private var isForeground = true
    private var activeRecordingId: String?
    private var activeOrigin = "manual"
    private var heartbeat: Timer?

    init(settings: AppSettings) {
        self.settings = settings
        super.init()
        camera.quality = settings.videoQuality
        detector.threshold = settings.threshold
        UIDevice.current.isBatteryMonitoringEnabled = true
    }

    // MARK: - Cycle de vie

    func start() {
        running = true
        camera.configure { [weak self] ok in
            guard let self = self else { return }
            if ok { self.camera.startSession() }
        }
        startDetector()
        startDiscovery()
        connectIfPossible()
        startHeartbeat()
        pendingCount = pending.list().count
    }

    func stop() {
        running = false
        detector.stop()
        discovery.stop()
        camera.stopSession()
        client?.close()
        heartbeat?.invalidate()
        connected = false
    }

    func setForeground(_ foreground: Bool) {
        isForeground = foreground
        if foreground {
            camera.startSession()
            flushUploads()
        }
    }

    // MARK: - Découverte / connexion

    private func startDiscovery() {
        discovery.start { [weak self] server in
            guard let self = self else { return }
            if self.settings.serverHost != server.host {
                self.settings.serverHost = server.host
                self.settings.serverPort = server.port
                self.settings.serverTLS = server.tls
            }
            self.lastEvent = "PC détecté : \(server.name)"
            self.connectIfPossible()
        }
    }

    func connectIfPossible() {
        guard let host = settings.serverHost, let token = settings.pairingToken else { return }
        if client == nil {
            let c = CommandClient(device: settings.deviceInfo())
            c.delegate = self
            client = c
        }
        client?.connect(host: host, port: settings.serverPort, tls: settings.serverTLS, credential: token)
        uploader.configure(host: host, port: settings.serverPort, tls: settings.serverTLS, token: token)
        flushUploads()
    }

    // MARK: - Détection sonore

    private func startDetector() {
        detector.threshold = settings.threshold
        detector.start { [weak self] confidence in
            self?.onSoundTrigger(confidence: confidence)
        }
    }

    private func onSoundTrigger(confidence: Float) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if self.isForeground {
                self.startRecording(origin: "sound", confidence: confidence, ackRequestId: nil)
            } else {
                // Capture impossible en arrière-plan : on notifie le PC du déclenchement.
                let rid = self.newRecordingId()
                self.client?.sendSoundTriggered(recordingId: rid, confidence: confidence, label: nil)
                self.lastEvent = "Son détecté en arrière-plan (capture indisponible)"
            }
        }
    }

    // MARK: - Contrôleur d'enregistrement

    private func startRecording(origin: String, confidence: Float, ackRequestId: String?) {
        guard isForeground else {
            ackRequestId.map { client?.sendAck(requestId: $0, result: "error",
                                               reason: "iOS : capture possible au premier plan uniquement", recordingId: nil) }
            return
        }
        if camera.isRecording {
            ackRequestId.map { client?.sendAck(requestId: $0, result: "ok",
                                               reason: "déjà en cours", recordingId: activeRecordingId) }
            return
        }
        let recordingId = newRecordingId()
        activeRecordingId = recordingId
        activeOrigin = origin
        let url = pending.videoURL(for: recordingId)
        try? FileManager.default.removeItem(at: url)
        camera.startRecording(to: url) { [weak self] fileURL, duration, w, h, error in
            self?.onFinalize(fileURL: fileURL, duration: duration, w: w, h: h, error: error)
        }
        recording = true
        lastEvent = "Enregistrement (\(origin))"
        ackRequestId.map { client?.sendAck(requestId: $0, result: "ok", reason: nil, recordingId: recordingId) }
        if origin == "sound" {
            client?.sendSoundTriggered(recordingId: recordingId, confidence: confidence, label: nil)
        }
        sendStatus()
    }

    private func stopRecording(ackRequestId: String?) {
        if camera.isRecording {
            camera.stopRecording()
            ackRequestId.map { client?.sendAck(requestId: $0, result: "ok", reason: nil, recordingId: activeRecordingId) }
        } else {
            ackRequestId.map { client?.sendAck(requestId: $0, result: "ok", reason: "aucun enregistrement", recordingId: nil) }
        }
    }

    private func onFinalize(fileURL: URL, duration: Double, w: Int, h: Int, error: Bool) {
        let recordingId = activeRecordingId ?? fileURL.deletingPathExtension().lastPathComponent
        if !error, FileManager.default.fileExists(atPath: fileURL.path) {
            let meta: [String: Any] = [
                "recording_id": recordingId,
                "device_id": settings.deviceId,
                "device_name": settings.deviceName,
                "origin": activeOrigin,
                "sound_label": NSNull(),
                "started_at": Messages.nowISO(),
                "duration_s": duration,
                "width": w,
                "height": h,
                "mime": "video/quicktime",
            ]
            pending.saveMeta(recordingId: recordingId, metadata: meta)
        }
        activeRecordingId = nil
        recording = false
        pendingCount = pending.list().count
        sendStatus()
        flushUploads()
    }

    // MARK: - Transfert

    private func flushUploads() {
        guard uploader.isConfigured else { return }
        let items = pending.list()
        guard let item = items.first else { return }
        uploader.upload(fileURL: item.video, metadata: item.metadata) { [weak self] ok in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if ok {
                    self.pending.remove(recordingId: item.recordingId)
                    self.pendingCount = self.pending.list().count
                    self.flushUploads() // suivant
                }
            }
        }
    }

    // MARK: - STATUS heartbeat

    private func startHeartbeat() {
        heartbeat?.invalidate()
        heartbeat = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.sendStatus()
        }
    }

    private func sendStatus() {
        let state = camera.isRecording ? "recording" : (detector.isRunning ? "listening" : "idle")
        client?.sendStatus(state: state, recording: camera.isRecording,
                           battery: batteryLevel(), recordingId: activeRecordingId)
    }

    private func batteryLevel() -> Float? {
        let level = UIDevice.current.batteryLevel
        return level >= 0 ? level : nil
    }

    private func newRecordingId() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return "rec_" + formatter.string(from: Date())
    }

    // MARK: - CommandClient.Delegate

    func onConnected(serverName: String?) {
        DispatchQueue.main.async {
            self.connected = true
            self.serverName = serverName
            self.pairingError = nil
            self.flushUploads()
        }
    }

    func onPaired(token: String) {
        DispatchQueue.main.async { self.settings.pairingToken = token }
    }

    func onPairingRejected(reason: String?) {
        DispatchQueue.main.async {
            self.connected = false
            self.pairingError = reason ?? "appairage refusé"
        }
    }

    func onStartRecording(requestId: String, maxDurationS: Double) {
        DispatchQueue.main.async {
            self.startRecording(origin: "manual", confidence: 0, ackRequestId: requestId)
            if maxDurationS > 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + maxDurationS) { [weak self] in
                    self?.stopRecording(ackRequestId: nil)
                }
            }
        }
    }

    func onStopRecording(requestId: String) {
        DispatchQueue.main.async { self.stopRecording(ackRequestId: requestId) }
    }

    func onSetSettings(requestId: String, settings: RemoteSettings) {
        DispatchQueue.main.async {
            self.settings.applyRemote(settings)
            self.camera.quality = self.settings.videoQuality
            self.detector.threshold = self.settings.threshold
            self.client?.sendAck(requestId: requestId, result: "ok", reason: nil, recordingId: self.activeRecordingId)
        }
    }

    func onDisconnected() {
        DispatchQueue.main.async { self.connected = false }
    }
}
