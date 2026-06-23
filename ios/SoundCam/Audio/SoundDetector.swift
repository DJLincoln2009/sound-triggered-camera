import AVFoundation

/// Écoute continue du micro et détection par seuil d'amplitude (EF-03).
///
/// Utilise `AVAudioEngine` avec un tap sur le bus d'entrée. La session audio est
/// configurée avec le mode arrière-plan `audio` (périmètre allégé §7.2) : la détection
/// peut continuer en arrière-plan, mais la **capture vidéo** reste réservée au premier plan.
final class SoundDetector {
    var threshold: Float = 0.35
    private let engine = AVAudioEngine()
    private var lastTrigger = Date.distantPast
    private let cooldown: TimeInterval = 3.0
    private var onTrigger: ((Float) -> Void)?
    private(set) var isRunning = false

    func start(onTrigger: @escaping (Float) -> Void) {
        guard !isRunning else { return }
        self.onTrigger = onTrigger
        configureSession()

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            self?.process(buffer)
        }
        do {
            try engine.start()
            isRunning = true
        } catch {
            isRunning = false
        }
    }

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth])
        try? session.setActive(true)
    }

    private func process(_ buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let frames = Int(buffer.frameLength)
        if frames == 0 { return }
        var sum: Float = 0
        for i in 0..<frames {
            let sample = channel[i]
            sum += sample * sample
        }
        let rms = (sum / Float(frames)).squareRoot() // déjà normalisé 0..1 (échantillons float)
        let now = Date()
        if rms >= threshold && now.timeIntervalSince(lastTrigger) > cooldown {
            lastTrigger = now
            onTrigger?(min(max(rms, 0), 1))
        }
    }

    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
    }
}
