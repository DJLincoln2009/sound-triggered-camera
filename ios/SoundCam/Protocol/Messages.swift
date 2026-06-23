import Foundation

/// Construction et analyse des messages du canal de commandes (protocole v1).
///
/// Miroir Swift de `protocol/PROTOCOL.md`, cohérent avec les implémentations Android et PC.
enum Messages {
    static let protocolVersion = 1

    enum MsgType {
        static let hello = "HELLO"
        static let helloAck = "HELLO_ACK"
        static let startRecording = "START_RECORDING"
        static let stopRecording = "STOP_RECORDING"
        static let setSettings = "SET_SETTINGS"
        static let soundTriggered = "SOUND_TRIGGERED"
        static let status = "STATUS"
        static let ack = "ACK"
    }

    static func nowISO() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: Date())
    }

    static func encode(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }

    static func decode(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return obj
    }

    static func hello(token: String, device: DeviceInfo) -> String {
        encode([
            "type": MsgType.hello,
            "protocol": protocolVersion,
            "token": token,
            "device": [
                "id": device.id,
                "name": device.name,
                "platform": "ios",
                "model": device.model,
                "manufacturer": "Apple",
                "app_version": device.appVersion,
            ],
        ])
    }

    static func status(state: String, recording: Bool, battery: Float?, recordingId: String?) -> String {
        encode([
            "type": MsgType.status,
            "state": state,
            "recording": recording,
            "battery_level": battery as Any? ?? NSNull(),
            "recording_id": recordingId as Any? ?? NSNull(),
            "timestamp": nowISO(),
        ])
    }

    static func soundTriggered(recordingId: String, confidence: Float, label: String?) -> String {
        encode([
            "type": MsgType.soundTriggered,
            "timestamp": nowISO(),
            "confidence": confidence,
            "sound_label": label as Any? ?? NSNull(),
            "recording_id": recordingId,
        ])
    }

    static func ack(requestId: String, result: String, reason: String?, recordingId: String?) -> String {
        encode([
            "type": MsgType.ack,
            "request_id": requestId,
            "result": result,
            "reason": reason as Any? ?? NSNull(),
            "recording_id": recordingId as Any? ?? NSNull(),
        ])
    }
}

/// Identité de l'appareil annoncée dans le HELLO.
struct DeviceInfo {
    let id: String
    let name: String
    let model: String
    let appVersion: String
}

/// Réglages distants reçus via SET_SETTINGS (sous-ensemble pertinent pour iOS).
struct RemoteSettings {
    var threshold: Float?
    var videoQuality: String?

    init(from o: [String: Any]) {
        threshold = (o["threshold"] as? NSNumber)?.floatValue
        videoQuality = o["video_quality"] as? String
    }
}
