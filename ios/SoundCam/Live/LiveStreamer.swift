import AVFoundation
import CoreMedia
import Foundation
import WebRTC

/// Émetteur de diffusion en direct (WebRTC) pour iOS. La caméra est le pair **émetteur** ;
/// le PC relaie le signaling vers le navigateur récepteur du dashboard.
///
/// Conforme au 100 % local : aucun serveur ICE externe (STUN/TURN).
///
/// Limites iOS (§7.2) : la capture n'est possible **qu'au premier plan**, et la caméra ne
/// peut pas être partagée simultanément entre l'enregistrement et le live.
final class LiveStreamer: NSObject {

    protocol Signaling: AnyObject {
        func sendOffer(sessionId: String, sdp: String)
        func sendIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int32)
        func sendStop(sessionId: String)
    }

    private weak var signaling: Signaling?
    private let factory: RTCPeerConnectionFactory
    private var peer: RTCPeerConnection?
    private var capturer: RTCCameraVideoCapturer?
    private var videoSource: RTCVideoSource?
    private var sessionId: String?

    init(signaling: Signaling) {
        self.signaling = signaling
        RTCInitializeSSL()
        let encoder = RTCDefaultVideoEncoderFactory()
        let decoder = RTCDefaultVideoDecoderFactory()
        self.factory = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
        super.init()
    }

    func start(sessionId: String) {
        stop()
        self.sessionId = sessionId

        let config = RTCConfiguration()
        config.iceServers = []                 // 100 % local : aucun STUN/TURN
        config.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            signaling?.sendStop(sessionId: sessionId)
            stop()
            return
        }
        peer = pc

        guard let track = makeVideoTrack() else {
            signaling?.sendStop(sessionId: sessionId)
            stop()
            return
        }
        pc.add(track, streamIds: ["soundcam"])

        pc.offer(for: constraints) { [weak self] sdp, _ in
            guard let self = self, let sdp = sdp else { return }
            pc.setLocalDescription(sdp) { _ in }
            self.signaling?.sendOffer(sessionId: sessionId, sdp: sdp.sdp)
        }
    }

    func onAnswer(sessionId: String, sdp: String) {
        guard sessionId == self.sessionId else { return }
        peer?.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { _ in }
    }

    func onRemoteIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int32) {
        guard sessionId == self.sessionId else { return }
        peer?.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid))
    }

    func stop() {
        sessionId = nil
        capturer?.stopCapture()
        capturer = nil
        videoSource = nil
        peer?.close()
        peer = nil
    }

    private func makeVideoTrack() -> RTCVideoTrack? {
        let source = factory.videoSource()
        let capturer = RTCCameraVideoCapturer(delegate: source)
        // Préfère la caméra arrière, repli sur n'importe quelle caméra disponible.
        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let device = devices.first(where: { $0.position == .back }) ?? devices.first else {
            return nil
        }
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let format = bestFormat(formats) ?? formats.last
        guard let chosen = format else { return nil }
        let fps = chosen.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 30
        capturer.startCapture(with: device, format: chosen, fps: Int(min(fps, 30)))
        self.capturer = capturer
        self.videoSource = source
        return factory.videoTrack(with: source, trackId: "video0")
    }

    /// Choisit le format le plus proche de 720p pour limiter débit et batterie.
    private func bestFormat(_ formats: [AVCaptureDevice.Format]) -> AVCaptureDevice.Format? {
        formats.min { a, b in
            let da = a.formatDescription
            let db = b.formatDescription
            let dimA = CMVideoFormatDescriptionGetDimensions(da)
            let dimB = CMVideoFormatDescriptionGetDimensions(db)
            return abs(Int(dimA.width) - 1280) < abs(Int(dimB.width) - 1280)
        }
    }
}

extension LiveStreamer: RTCPeerConnectionDelegate {
    func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard let sid = sessionId else { return }
        signaling?.sendIce(sessionId: sid, candidate: candidate.sdp,
                           sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex)
    }

    func peerConnection(_ pc: RTCPeerConnection, didChange state: RTCPeerConnectionState) {
        if state == .failed || state == .closed { stop() }
    }

    // Méthodes requises non utilisées.
    func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
