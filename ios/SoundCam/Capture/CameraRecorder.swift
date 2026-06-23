import AVFoundation
import UIKit

/// Capture vidéo via AVFoundation (EF-01). Sur iOS, la capture caméra n'est possible
/// qu'au **premier plan** (restriction système) — voir l'écran d'information (§7.2).
final class CameraRecorder: NSObject, AVCaptureFileOutputRecordingDelegate {

    private let session = AVCaptureSession()
    private let movieOutput = AVCaptureMovieFileOutput()
    private let sessionQueue = DispatchQueue(label: "soundcam.camera.session")
    private var finalizeHandler: ((URL, Double, Int, Int, Bool) -> Void)?
    private var startedAt = Date()

    var quality: String = "HD_720P"
    private(set) var isConfigured = false
    var isRecording: Bool { movieOutput.isRecording }

    /// Couche d'aperçu pour l'UI (premier plan).
    let previewLayer = AVCaptureVideoPreviewLayer()

    func configure(completion: @escaping (Bool) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = self.preset()

            guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let videoInput = try? AVCaptureDeviceInput(device: camera),
                  self.session.canAddInput(videoInput) else {
                self.session.commitConfiguration()
                DispatchQueue.main.async { completion(false) }
                return
            }
            self.session.addInput(videoInput)

            if let mic = AVCaptureDevice.default(for: .audio),
               let audioInput = try? AVCaptureDeviceInput(device: mic),
               self.session.canAddInput(audioInput) {
                self.session.addInput(audioInput)
            }

            if self.session.canAddOutput(self.movieOutput) {
                self.session.addOutput(self.movieOutput)
            }
            self.session.commitConfiguration()
            self.previewLayer.session = self.session
            self.previewLayer.videoGravity = .resizeAspectFill
            self.isConfigured = true
            DispatchQueue.main.async { completion(true) }
        }
    }

    func startSession() {
        sessionQueue.async { if !self.session.isRunning { self.session.startRunning() } }
    }

    func stopSession() {
        sessionQueue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    func startRecording(to url: URL, onFinalize: @escaping (URL, Double, Int, Int, Bool) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self, self.isConfigured, !self.movieOutput.isRecording else {
                DispatchQueue.main.async { onFinalize(url, 0, 0, 0, true) }
                return
            }
            self.finalizeHandler = onFinalize
            self.startedAt = Date()
            self.movieOutput.startRecording(to: url, recordingDelegate: self)
        }
    }

    func stopRecording() {
        sessionQueue.async { if self.movieOutput.isRecording { self.movieOutput.stopRecording() } }
    }

    private func preset() -> AVCaptureSession.Preset {
        switch quality {
        case "SD_480P": return .vga640x480
        case "FHD_1080P": return .hd1920x1080
        default: return .hd1280x720
        }
    }

    private func resolution() -> (Int, Int) {
        switch quality {
        case "SD_480P": return (640, 480)
        case "FHD_1080P": return (1920, 1080)
        default: return (1280, 720)
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        let (w, h) = resolution()
        let duration = Date().timeIntervalSince(startedAt)
        let handler = finalizeHandler
        finalizeHandler = nil
        DispatchQueue.main.async {
            handler?(outputFileURL, duration, w, h, error != nil)
        }
    }
}
