import Foundation

/// Transfert des enregistrements vers le PC via HTTP multipart (EF-09, EF-23).
/// Idempotent côté serveur via `recording_id`. Réessaie tant que le PC n'a pas confirmé.
final class VideoUploader: NSObject, URLSessionDelegate {
    private var baseURL = ""
    private var token = ""
    private lazy var session: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }()

    func configure(host: String, port: Int, tls: Bool, token: String) {
        let scheme = tls ? "https" : "http"
        baseURL = "\(scheme)://\(host):\(port)"
        self.token = token
    }

    var isConfigured: Bool { !baseURL.isEmpty && !token.isEmpty }

    /// Téléverse un fichier vidéo + ses métadonnées. Appelle `completion(true)` si 2xx.
    func upload(fileURL: URL, metadata: [String: Any], completion: @escaping (Bool) -> Void) {
        guard isConfigured, let url = URL(string: "\(baseURL)/upload"),
              let fileData = try? Data(contentsOf: fileURL),
              let metaData = try? JSONSerialization.data(withJSONObject: metadata) else {
            completion(false); return
        }

        let boundary = "----soundcam\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        var body = Data()
        func appendPart(headers: String, payload: Data) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append(headers.data(using: .utf8)!)
            body.append("\r\n\r\n".data(using: .utf8)!)
            body.append(payload)
            body.append("\r\n".data(using: .utf8)!)
        }
        appendPart(headers: "Content-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json",
                   payload: metaData)
        appendPart(headers: "Content-Disposition: form-data; name=\"file\"; filename=\"\(fileURL.lastPathComponent)\"\r\nContent-Type: video/quicktime",
                   payload: fileData)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        session.dataTask(with: request) { _, response, _ in
            let ok = (response as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } ?? false
            completion(ok)
        }.resume()
    }

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
