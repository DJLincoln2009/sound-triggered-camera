import Foundation
import Network

/// Découverte du serveur PC via Bonjour/mDNS (EF-12) : service `_soundcam._tcp`.
/// Utilise le framework Network (NWBrowser). Évite la saisie manuelle d'IP.
final class Discovery {
    struct Server {
        let host: String
        let port: Int
        let tls: Bool
        let name: String
    }

    private var browser: NWBrowser?
    private var connections: [NWConnection] = []

    func start(onFound: @escaping (Server) -> Void) {
        stop()
        let params = NWParameters()
        params.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_soundcam._tcp", domain: nil), using: params)
        self.browser = browser

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            for result in results {
                self?.resolve(result, onFound: onFound)
            }
        }
        browser.start(queue: .main)
    }

    private func resolve(_ result: NWBrowser.Result, onFound: @escaping (Server) -> Void) {
        var name = "PC"
        var httpPort = 8766
        var tls = false
        if case let .bonjour(txt) = result.metadata {
            if let n = txt["name"] { name = n }
            if let p = txt["http"], let v = Int(p) { httpPort = v }
            tls = txt["tls"] == "1"
        }

        let connection = NWConnection(to: result.endpoint, using: .tcp)
        connections.append(connection)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let connection = connection else { return }
            if case .ready = state, let remote = connection.currentPath?.remoteEndpoint,
               case let .hostPort(host, _) = remote {
                let hostString = Self.hostString(host)
                onFound(Server(host: hostString, port: httpPort, tls: tls, name: name))
                connection.cancel()
                self?.connections.removeAll { $0 === connection }
            }
        }
        connection.start(queue: .main)
    }

    private static func hostString(_ host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let addr): return "\(addr)".components(separatedBy: "%").first ?? "\(addr)"
        case .ipv6(let addr): return "\(addr)".components(separatedBy: "%").first ?? "\(addr)"
        case .name(let name, _): return name
        @unknown default: return "\(host)"
        }
    }

    func stop() {
        browser?.cancel()
        browser = nil
        connections.forEach { $0.cancel() }
        connections.removeAll()
    }
}
