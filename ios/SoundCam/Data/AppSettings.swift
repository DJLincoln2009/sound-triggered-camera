import Foundation
import Combine
import UIKit

/// Réglages persistés de l'app caméra iOS (UserDefaults). Inclut l'appairage et l'identité.
final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var deviceName: String {
        didSet { defaults.set(deviceName, forKey: Keys.deviceName) }
    }
    @Published var pairingToken: String? {
        didSet { defaults.set(pairingToken, forKey: Keys.pairingToken) }
    }
    @Published var serverHost: String? {
        didSet { defaults.set(serverHost, forKey: Keys.serverHost) }
    }
    @Published var serverPort: Int {
        didSet { defaults.set(serverPort, forKey: Keys.serverPort) }
    }
    @Published var serverTLS: Bool {
        didSet { defaults.set(serverTLS, forKey: Keys.serverTLS) }
    }
    @Published var threshold: Float {
        didSet { defaults.set(threshold, forKey: Keys.threshold) }
    }
    @Published var videoQuality: String {
        didSet { defaults.set(videoQuality, forKey: Keys.videoQuality) }
    }

    let deviceId: String

    init() {
        deviceName = defaults.string(forKey: Keys.deviceName) ?? (UIDevice.current.name)
        pairingToken = defaults.string(forKey: Keys.pairingToken)
        serverHost = defaults.string(forKey: Keys.serverHost)
        serverPort = defaults.object(forKey: Keys.serverPort) as? Int ?? 8766
        serverTLS = defaults.bool(forKey: Keys.serverTLS)
        threshold = defaults.object(forKey: Keys.threshold) as? Float ?? 0.35
        videoQuality = defaults.string(forKey: Keys.videoQuality) ?? "HD_720P"

        if let existing = defaults.string(forKey: Keys.deviceId) {
            deviceId = existing
        } else {
            let generated = UUID().uuidString.prefix(8).lowercased()
            deviceId = String(generated)
            defaults.set(deviceId, forKey: Keys.deviceId)
        }
    }

    func applyRemote(_ s: RemoteSettings) {
        if let t = s.threshold { threshold = t }
        if let q = s.videoQuality { videoQuality = q }
    }

    func deviceInfo() -> DeviceInfo {
        DeviceInfo(
            id: deviceId,
            name: deviceName,
            model: UIDevice.current.model,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        )
    }

    private enum Keys {
        static let deviceId = "device_id"
        static let deviceName = "device_name"
        static let pairingToken = "pairing_token"
        static let serverHost = "server_host"
        static let serverPort = "server_port"
        static let serverTLS = "server_tls"
        static let threshold = "threshold"
        static let videoQuality = "video_quality"
    }
}
