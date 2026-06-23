import SwiftUI
import AVFoundation

struct ContentView: View {
    var body: some View {
        TabView {
            StatusView()
                .tabItem { Label("Statut", systemImage: "dot.radiowaves.left.and.right") }
            SettingsView()
                .tabItem { Label("Réglages", systemImage: "gearshape") }
            InfoView()
                .tabItem { Label("Infos", systemImage: "info.circle") }
        }
    }
}

struct StatusView: View {
    @EnvironmentObject var controller: CameraController
    @EnvironmentObject var settings: AppSettings

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    CameraPreview(layer: controller.camera.previewLayer)
                        .frame(height: 220)
                        .cornerRadius(12)
                        .overlay(alignment: .topTrailing) {
                            if controller.recording {
                                Text("⏺ REC").bold().foregroundColor(.white)
                                    .padding(6).background(Color.red).cornerRadius(6).padding(8)
                            }
                        }

                    GroupBox("Service") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(controller.running ? "● En fonctionnement" : "○ Arrêté")
                            Text(statusText)
                            Text("En attente de transfert : \(controller.pendingCount)")
                            if let err = controller.pairingError {
                                Text("Appairage refusé : \(err)").foregroundColor(.red)
                            }
                            if let event = controller.lastEvent {
                                Text(event).font(.footnote).foregroundColor(.secondary)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }

                    HStack {
                        Button("Démarrer") { controller.start() }
                            .buttonStyle(.borderedProminent)
                        Button("Arrêter") { controller.stop() }
                            .buttonStyle(.bordered)
                    }
                }.padding()
            }
            .navigationTitle("SoundCam")
        }
    }

    private var statusText: String {
        if controller.connected { return "Connecté à : \(controller.serverName ?? "PC")" }
        if settings.pairingToken == nil { return "Non appairé — saisissez le code PIN dans Réglages" }
        return "Connexion au PC…"
    }
}

struct SettingsView: View {
    @EnvironmentObject var controller: CameraController
    @EnvironmentObject var settings: AppSettings
    @State private var pin = ""
    @State private var host = ""

    var body: some View {
        NavigationView {
            Form {
                Section("Appairage") {
                    SecureField("Code PIN affiché par le PC", text: $pin)
                        .keyboardType(.numberPad)
                    Button("Appairer") {
                        settings.pairingToken = pin
                        pin = ""
                        controller.connectIfPossible()
                    }.disabled(pin.count != 6)
                    if settings.pairingToken != nil {
                        Button("Oublier l'appairage", role: .destructive) {
                            settings.pairingToken = nil
                        }
                    }
                }

                Section("Identité") {
                    TextField("Nom de la caméra", text: $settings.deviceName)
                }

                Section("Serveur (optionnel — sinon découverte auto)") {
                    TextField("Adresse IP du PC", text: Binding(
                        get: { settings.serverHost ?? "" },
                        set: { settings.serverHost = $0.isEmpty ? nil : $0 }))
                        .keyboardType(.numbersAndPunctuation)
                    Stepper("Port : \(settings.serverPort)", value: $settings.serverPort, in: 1024...65535)
                }

                Section("Détection sonore — seuil : \(Int(settings.threshold * 100)) %") {
                    Slider(value: $settings.threshold, in: 0.05...0.95)
                }

                Section("Qualité vidéo") {
                    Picker("Qualité", selection: $settings.videoQuality) {
                        Text("480p").tag("SD_480P")
                        Text("720p").tag("HD_720P")
                        Text("1080p").tag("FHD_1080P")
                    }.pickerStyle(.segmented)
                }
            }
            .navigationTitle("Réglages")
        }
    }
}

struct InfoView: View {
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Périmètre iOS (version allégée)").font(.title3).bold()
                    Text("En raison des restrictions d'Apple, l'application iOS fonctionne avec un périmètre réduit par rapport à Android :")
                    bullet("La capture vidéo n'est possible que lorsque l'application est au premier plan, écran allumé.")
                    bullet("L'écoute du micro peut continuer en arrière-plan (mode audio). Un son détecté en arrière-plan est signalé au PC, mais ne peut pas démarrer la caméra automatiquement.")
                    bullet("Le déclenchement manuel depuis le PC enregistre uniquement si l'application est au premier plan.")
                    bullet("Gardez l'application ouverte et l'appareil branché pour une surveillance prolongée.")
                    Divider()
                    Text("Transparence").font(.headline)
                    Text("Une session d'enregistrement est signalée visuellement (indicateur REC) et la capture peut être déclenchée à distance. Utilisez cette application uniquement dans un cadre légal et avec le consentement des personnes concernées.")
                }.padding()
            }
            .navigationTitle("Infos")
        }
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("•")
            Text(text)
        }
    }
}

/// Aperçu caméra (premier plan) — encapsule l'AVCaptureVideoPreviewLayer.
struct CameraPreview: UIViewRepresentable {
    let layer: AVCaptureVideoPreviewLayer

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.backgroundColor = .black
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        view.previewLayer = layer
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}

    final class PreviewUIView: UIView {
        var previewLayer: AVCaptureVideoPreviewLayer?
        override func layoutSubviews() {
            super.layoutSubviews()
            previewLayer?.frame = bounds
        }
    }
}
