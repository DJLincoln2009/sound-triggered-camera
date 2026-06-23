import Foundation

/// File d'attente locale des enregistrements à transférer (EF-08). Chaque vidéo possède
/// un fichier `.meta.json` ; l'entrée subsiste tant que le PC n'a pas confirmé la réception.
final class PendingUploads {
    let directory: URL

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        directory = docs.appendingPathComponent("pending", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    struct Item {
        let video: URL
        let metadata: [String: Any]
        var recordingId: String { metadata["recording_id"] as? String ?? "" }
    }

    func videoURL(for recordingId: String) -> URL {
        directory.appendingPathComponent("\(recordingId).mov")
    }

    func saveMeta(recordingId: String, metadata: [String: Any]) {
        let url = directory.appendingPathComponent("\(recordingId).meta.json")
        if let data = try? JSONSerialization.data(withJSONObject: metadata) {
            try? data.write(to: url)
        }
    }

    func list() -> [Item] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { $0.lastPathComponent.hasSuffix(".meta.json") }
            .compactMap { metaURL -> Item? in
                guard let data = try? Data(contentsOf: metaURL),
                      let meta = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let rid = meta["recording_id"] as? String else { return nil }
                let video = videoURL(for: rid)
                guard FileManager.default.fileExists(atPath: video.path) else { return nil }
                return Item(video: video, metadata: meta)
            }
            .sorted { lhs, rhs in
                ((try? lhs.video.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast)
                    < ((try? rhs.video.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast)
            }
    }

    func remove(recordingId: String) {
        try? FileManager.default.removeItem(at: videoURL(for: recordingId))
        try? FileManager.default.removeItem(at: directory.appendingPathComponent("\(recordingId).meta.json"))
    }
}
