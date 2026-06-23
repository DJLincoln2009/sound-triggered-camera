import Foundation

/// Client WebSocket persistant du canal de commandes (EF-21), basé sur
/// `URLSessionWebSocketTask`. Reconnexion automatique avec backoff exponentiel.
///
/// En mode TLS, le PC présente un certificat auto-signé local (ENF-07) ; on l'accepte
/// après appairage explicite (modèle TOFU, voir `URLSessionDelegate`).
final class CommandClient: NSObject, URLSessionDelegate {

    protocol Delegate: AnyObject {
        func onConnected(serverName: String?)
        func onPaired(token: String)
        func onPairingRejected(reason: String?)
        func onStartRecording(requestId: String, maxDurationS: Double)
        func onStopRecording(requestId: String)
        func onSetSettings(requestId: String, settings: RemoteSettings)
        func onDisconnected()
    }

    weak var delegate: Delegate?
    private let device: DeviceInfo
    private var task: URLSessionWebSocketTask?
    private lazy var session: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }()

    private var credential = ""
    private var urlString = ""
    private var running = false
    private var backoff: TimeInterval = 1.0
    private let maxBackoff: TimeInterval = 30.0

    private(set) var isConnected = false

    init(device: DeviceInfo) {
        self.device = device
    }

    func connect(host: String, port: Int, tls: Bool, credential: String) {
        let scheme = tls ? "wss" : "ws"
        urlString = "\(scheme)://\(host):\(port)/ws"
        self.credential = credential
        running = true
        openSocket()
    }

    private func openSocket() {
        guard running, let url = URL(string: urlString) else { return }
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        send(Messages.hello(token: credential, device: device))
        receiveLoop()
    }

    private func receiveLoop() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure:
                self.handleDisconnect()
            case .success(let message):
                if case let .string(text) = message {
                    self.handle(text)
                }
                self.receiveLoop()
            }
        }
    }

    private func handle(_ text: String) {
        guard let o = Messages.decode(text), let type = o["type"] as? String else { return }
        switch type {
        case Messages.MsgType.helloAck:
            let accepted = (o["accepted"] as? Bool) ?? false
            if accepted {
                isConnected = true
                backoff = 1.0
                delegate?.onConnected(serverName: o["server_name"] as? String)
                if let token = o["token"] as? String, !token.isEmpty {
                    credential = token
                    delegate?.onPaired(token: token)
                }
            } else {
                running = false
                delegate?.onPairingRejected(reason: o["reason"] as? String)
            }
        case Messages.MsgType.startRecording:
            delegate?.onStartRecording(
                requestId: o["request_id"] as? String ?? "",
                maxDurationS: (o["max_duration_s"] as? NSNumber)?.doubleValue ?? 0)
        case Messages.MsgType.stopRecording:
            delegate?.onStopRecording(requestId: o["request_id"] as? String ?? "")
        case Messages.MsgType.setSettings:
            delegate?.onSetSettings(
                requestId: o["request_id"] as? String ?? "",
                settings: RemoteSettings(from: o))
        default:
            break
        }
    }

    private func handleDisconnect() {
        isConnected = false
        task = nil
        delegate?.onDisconnected()
        guard running else { return }
        let delay = backoff
        backoff = min(backoff * 2, maxBackoff)
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.openSocket()
        }
    }

    func send(_ text: String) {
        task?.send(.string(text)) { _ in }
    }

    func sendStatus(state: String, recording: Bool, battery: Float?, recordingId: String?) {
        send(Messages.status(state: state, recording: recording, battery: battery, recordingId: recordingId))
    }

    func sendAck(requestId: String, result: String, reason: String?, recordingId: String?) {
        send(Messages.ack(requestId: requestId, result: result, reason: reason, recordingId: recordingId))
    }

    func sendSoundTriggered(recordingId: String, confidence: Float, label: String?) {
        send(Messages.soundTriggered(recordingId: recordingId, confidence: confidence, label: label))
    }

    func close() {
        running = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    // TOFU : accepte le certificat auto-signé du PC sur le réseau local (ENF-07).
    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}
