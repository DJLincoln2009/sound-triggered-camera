package com.soundcam.camera.net

import android.util.Log
import com.soundcam.camera.protocol.DeviceInfo
import com.soundcam.camera.protocol.Messages
import com.soundcam.camera.protocol.RemoteSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client WebSocket persistant du canal de commandes (EF-21).
 *
 * Le téléphone est client ; le PC serveur. Reconnexion automatique avec backoff
 * exponentiel (spéc. §7.1). À la connexion, envoie un HELLO d'appairage (ENF-06) ;
 * sur HELLO_ACK accepté, mémorise le token fort via [callback].
 */
class CommandClient(
    private val device: DeviceInfo,
    private val callback: Callback,
) {

    interface Callback {
        fun onConnected(serverName: String?)
        fun onPaired(token: String)
        fun onPairingRejected(reason: String?)
        fun onStartRecording(requestId: String, maxDurationS: Double)
        fun onStopRecording(requestId: String)
        fun onSetSettings(requestId: String, settings: RemoteSettings)
        fun onLiveRequest(sessionId: String)
        fun onLiveAnswer(sessionId: String, sdp: String)
        fun onLiveIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onLiveStop(sessionId: String)
        fun onDisconnected()
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var credential: String = ""
    @Volatile private var url: String = ""
    @Volatile private var tls: Boolean = false
    private val running = AtomicBoolean(false)
    private var backoffMs = INITIAL_BACKOFF_MS
    private val client: OkHttpClient by lazy {
        TlsTrust.client(
            tls,
            OkHttpClient.Builder()
                .pingInterval(5, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS),
        )
    }

    val isConnected: Boolean get() = webSocket != null

    /** Démarre (ou met à jour) la connexion vers le serveur découvert/configuré. */
    fun connect(host: String, port: Int, tls: Boolean, credential: String) {
        this.url = (if (tls) "wss" else "ws") + "://$host:$port/ws"
        this.tls = tls
        this.credential = credential
        if (running.compareAndSet(false, true)) {
            openSocket()
        } else {
            // Reconfiguration : ferme la socket courante pour rouvrir avec la nouvelle URL.
            webSocket?.cancel()
        }
    }

    private fun openSocket() {
        if (!running.get() || url.isEmpty()) return
        Log.i(TAG, "Connexion à $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            ws.send(Messages.hello(credential, device))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handle(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onSocketDown()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket échec: ${t.message}")
            onSocketDown()
        }
    }

    private fun handle(text: String) {
        val o: JSONObject = runCatching { Messages.parse(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            Messages.HELLO_ACK -> {
                if (o.optBoolean("accepted")) {
                    backoffMs = INITIAL_BACKOFF_MS
                    callback.onConnected(o.optString("server_name").ifEmpty { null })
                    val token = o.optString("token", "")
                    if (token.isNotEmpty()) {
                        credential = token
                        callback.onPaired(token)
                    }
                } else {
                    callback.onPairingRejected(o.optString("reason").ifEmpty { null })
                    running.set(false) // appairage refusé : ne pas boucler indéfiniment
                }
            }
            Messages.START_RECORDING ->
                callback.onStartRecording(o.optString("request_id"), o.optDouble("max_duration_s", 0.0))
            Messages.STOP_RECORDING ->
                callback.onStopRecording(o.optString("request_id"))
            Messages.SET_SETTINGS ->
                callback.onSetSettings(o.optString("request_id"), RemoteSettings.fromJson(o))
            Messages.LIVE_REQUEST ->
                callback.onLiveRequest(o.optString("session_id"))
            Messages.LIVE_ANSWER ->
                callback.onLiveAnswer(o.optString("session_id"), o.optString("sdp"))
            Messages.LIVE_ICE -> {
                val c = o.optJSONObject("candidate")
                if (c != null) callback.onLiveIce(
                    o.optString("session_id"),
                    c.optString("candidate"),
                    if (c.isNull("sdpMid")) null else c.optString("sdpMid"),
                    c.optInt("sdpMLineIndex", 0),
                )
            }
            Messages.LIVE_STOP ->
                callback.onLiveStop(o.optString("session_id"))
        }
    }

    private fun onSocketDown() {
        webSocket = null
        callback.onDisconnected()
        if (running.get()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        Thread {
            Thread.sleep(delay)
            if (running.get()) openSocket()
        }.start()
    }

    fun sendStatus(state: String, recording: Boolean, battery: Float?, recordingId: String?) {
        webSocket?.send(Messages.status(state, recording, battery, recordingId))
    }

    fun sendAck(requestId: String, result: String, reason: String?, recordingId: String?) {
        webSocket?.send(Messages.ack(requestId, result, reason, recordingId))
    }

    fun sendSoundTriggered(recordingId: String, confidence: Float, label: String?) {
        webSocket?.send(Messages.soundTriggered(recordingId, confidence, label))
    }

    fun sendLiveOffer(sessionId: String, sdp: String) {
        webSocket?.send(Messages.liveOffer(sessionId, sdp))
    }

    fun sendLiveIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        webSocket?.send(Messages.liveIce(sessionId, candidate, sdpMid, sdpMLineIndex))
    }

    fun sendLiveStop(sessionId: String) {
        webSocket?.send(Messages.liveStop(sessionId))
    }

    fun close() {
        running.set(false)
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    companion object {
        private const val TAG = "CommandClient"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
