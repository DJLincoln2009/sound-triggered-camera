package com.soundcam.camera.live

import android.content.Context
import android.util.Log
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Émetteur de diffusion en direct (WebRTC). La caméra est le pair **émetteur** ; le PC
 * relaie le signaling (offre/réponse/ICE) vers le navigateur récepteur du dashboard.
 *
 * Conforme au 100 % local : aucun serveur ICE externe (STUN/TURN) — seuls les candidats
 * du réseau local sont utilisés, le navigateur et la caméra étant sur le même LAN.
 *
 * Limite : la diffusion en direct utilise son propre capteur caméra (Camera2). Sur la
 * plupart des appareils la caméra ne peut pas être ouverte simultanément par
 * l'enregistrement (CameraX) et par le live ; le live est donc destiné à la supervision.
 */
class LiveStreamer(
    private val context: Context,
    private val signaling: Signaling,
) {

    interface Signaling {
        fun sendOffer(sessionId: String, sdp: String)
        fun sendIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun sendStop(sessionId: String)
    }

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    @Volatile private var sessionId: String? = null

    val isStreaming: Boolean get() = sessionId != null

    @Synchronized
    fun start(sessionId: String) {
        stop() // une seule session live à la fois
        ensureFactory()
        val f = factory ?: return
        this.sessionId = sessionId

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = f.createPeerConnection(rtcConfig, PeerObserver(sessionId))
        if (pc == null) {
            Log.w(TAG, "Création PeerConnection impossible")
            stop()
            return
        }
        peer = pc

        if (!startCapture(f)) {
            Log.w(TAG, "Capture caméra indisponible (occupée par l'enregistrement ?)")
            signaling.sendStop(sessionId)
            stop()
            return
        }
        pc.addTrack(videoTrack as MediaStreamTrack, listOf(STREAM_ID))

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                signaling.sendOffer(sessionId, desc.description)
            }
        }, MediaConstraints())
    }

    @Synchronized
    fun onAnswer(sessionId: String, sdp: String) {
        if (sessionId != this.sessionId) return
        peer?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    @Synchronized
    fun onRemoteIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        if (sessionId != this.sessionId) return
        peer?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    @Synchronized
    fun stop() {
        sessionId = null
        try { capturer?.stopCapture() } catch (_: InterruptedException) {}
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        surfaceHelper?.dispose(); surfaceHelper = null
        peer?.dispose(); peer = null
    }

    @Synchronized
    fun release() {
        stop()
        factory?.dispose(); factory = null
        eglBase?.release(); eglBase = null
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        val egl = EglBase.create()
        eglBase = egl
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun startCapture(f: PeerConnectionFactory): Boolean {
        val egl = eglBase ?: return false
        val cam = createCameraCapturer() ?: return false
        return try {
            val helper = SurfaceTextureHelper.create("LiveCaptureThread", egl.eglBaseContext)
            val source = f.createVideoSource(false)
            cam.initialize(helper, context, source.capturerObserver)
            cam.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            surfaceHelper = helper
            videoSource = source
            capturer = cam
            videoTrack = f.createVideoTrack("video0", source).apply { setEnabled(true) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startCapture a échoué: ${t.message}")
            cam.dispose()
            false
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        // Préfère la caméra arrière, repli sur la première disponible.
        names.firstOrNull { enumerator.isBackFacing(it) }?.let {
            return enumerator.createCapturer(it, null)
        }
        return names.firstOrNull()?.let { enumerator.createCapturer(it, null) }
    }

    private inner class PeerObserver(private val sid: String) : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            signaling.sendIce(sid, c.sdp, c.sdpMid, c.sdpMLineIndex)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED ||
                newState == PeerConnection.PeerConnectionState.CLOSED
            ) {
                synchronized(this@LiveStreamer) { if (sid == sessionId) stop() }
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    /** Adaptateur [SdpObserver] : seules les méthodes utiles sont surchargées. */
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.w(TAG, "createSdp: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "setSdp: $error") }
    }

    companion object {
        private const val TAG = "LiveStreamer"
        private const val STREAM_ID = "soundcam"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
    }
}
