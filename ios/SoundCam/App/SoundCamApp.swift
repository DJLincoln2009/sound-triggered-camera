import SwiftUI

@main
struct SoundCamApp: App {
    @StateObject private var settings = AppSettings()
    @StateObject private var controller: CameraController
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let settings = AppSettings()
        _settings = StateObject(wrappedValue: settings)
        _controller = StateObject(wrappedValue: CameraController(settings: settings))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(settings)
                .environmentObject(controller)
                .onChange(of: scenePhase) { phase in
                    controller.setForeground(phase == .active)
                }
        }
    }
}
