package com.soundcam.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.soundcam.camera.R
import com.soundcam.camera.audio.SoundDetector
import com.soundcam.camera.capture.VideoRecorder
import com.soundcam.camera.data.PendingUploads
import com.soundcam.camera.data.SettingsRepository
import com.soundcam.camera.net.CommandClient
import com.soundcam.camera.net.Discovery
import com.soundcam.camera.net.VideoUploader
import com.soundcam.camera.protocol.DeviceInfo
import com.soundcam.camera.protocol.Messages
import com.soundcam.camera.protocol.RemoteSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Foreground service — cœur de l'app caméra (EF-02). Héberge :
 *  - la détection sonore (EF-03/EF-04),
 *  - la capture vidéo CameraX (EF-01),
 *  - le client WebSocket du canal de commandes (EF-05/EF-06/EF-21),
 *  - le transfert différé des enregistrements (EF-08/EF-09),
 *  - la découverte du PC (EF-12).
 *
 * Une **notification persistante** reste affichée pendant tout le fonctionnement, exigée
 * à la fois techniquement (foreground service) et pour la transparence légale (§9.2).
 */
class CameraService : LifecycleService(), CommandClient.Callback {

    private lateinit var settings: SettingsRepository
    private lateinit var pending: PendingUploads
    private lateinit var uploader: VideoUploader
    private lateinit var detector: SoundDetector
    private lateinit var recorder: VideoRecorder
    private lateinit var discovery: Discovery
    private var client: CommandClient? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cfgDeviceId = ""
    @Volatile private var cfgDeviceName = "Caméra"
    @Volatile private var activeRecordingId: String? = null
    @Volatile private var activeOrigin: String = "manual"
    @Volatile private var activeLabel: String? = null
    @Volatile private var activeConfidence: Float = 0f
    @Volatile private var cameraReady = false
    @Volatile private var activeHoursEnabled = false
    @Volatile private var activeHoursStart = "00:00"
    @Volatile private var activeHoursEnd = "23:59"

    private val stopMaxDurationRunnable = Runnable { stopRecording(null) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        pending = PendingUploads(applicationContext)
        uploader = VideoUploader(pending)
        recorder = VideoRecorder(applicationContext)
        detector = SoundDetector(applicationContext) { conf, label -> onSoundTrigger(conf, label) }
        discovery = Discovery(applicationContext)

        createNotificationChannel()
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(recording = false),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        ServiceState.update { it.copy(running = true) }

        observeConfig()
        recorder.bind(this) { ok ->
            cameraReady = ok
            if (ok) detector.start()
        }
        discovery.start { server -> onServerDiscovered(server) }
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    // --- Configuration --------------------------------------------------------

    private fun observeConfig() {
        lifecycleScope.launch {
            settings.ensureDeviceId()
            settings.config.collect { cfg ->
                cfgDeviceId = cfg.deviceId
                cfgDeviceName = cfg.deviceName
                detector.threshold = cfg.threshold
                detector.useClassifier = cfg.useClassifier
                detector.targetLabels = cfg.targetLabels
                recorder.quality = cfg.videoQuality
                activeHoursEnabled = cfg.activeHoursEnabled
                activeHoursStart = cfg.activeHoursStart
                activeHoursEnd = cfg.activeHoursEnd

                if (client == null && cfg.deviceId.isNotEmpty()) {
                    client = CommandClient(deviceInfo(), this@CameraService)
                }
                val host = cfg.serverHost
                val token = cfg.pairingToken
                if (host != null && token != null) {
                    client?.connect(host, cfg.serverPort, cfg.serverTls, token)
                    uploader.configure(host, cfg.serverPort, cfg.serverTls, token)
                    flushUploads()
                }
            }
        }
    }

    private fun deviceInfo() = DeviceInfo(
        id = cfgDeviceId,
        name = cfgDeviceName,
        model = android.os.Build.MODEL ?: "?",
        manufacturer = android.os.Build.MANUFACTURER ?: "?",
        appVersion = "1.0.0",
    )

    private fun onServerDiscovered(server: Discovery.Server) {
        lifecycleScope.launch {
            val cfg = settings.config.first()
            if (cfg.serverHost != server.host || cfg.serverTls != server.tls) {
                settings.setServer(server.host, server.httpPort, server.tls)
            }
            ServiceState.update { it.copy(lastEvent = "PC détecté : ${server.name}") }
        }
    }

    // --- CommandClient.Callback ----------------------------------------------

    override fun onConnected(serverName: String?) {
        ServiceState.update { it.copy(connected = true, serverName = serverName, pairingError = null) }
        flushUploads()
    }

    override fun onPaired(token: String) {
        lifecycleScope.launch { settings.setPairingToken(token) }
    }

    override fun onPairingRejected(reason: String?) {
        ServiceState.update { it.copy(connected = false, pairingError = reason ?: "appairage refusé") }
    }

    override fun onStartRecording(requestId: String, maxDurationS: Double) {
        main.post { startRecording("manual", null, 0f, maxDurationS, requestId) }
    }

    override fun onStopRecording(requestId: String) {
        main.post { stopRecording(requestId) }
    }

    override fun onSetSettings(requestId: String, settings: RemoteSettings) {
        lifecycleScope.launch {
            this@CameraService.settings.applyRemoteSettings(settings)
            client?.sendAck(requestId, "ok", null, activeRecordingId)
        }
    }

    override fun onDisconnected() {
        ServiceState.update { it.copy(connected = false) }
    }

    // --- Déclenchement sonore (EF-03) ----------------------------------------

    private fun onSoundTrigger(confidence: Float, label: String?) {
        if (!withinActiveHours()) return
        main.post { startRecording("sound", label, confidence, 0.0, null) }
    }

    // --- Contrôleur d'enregistrement (EF-07, idempotent) ----------------------

    private fun startRecording(
        origin: String,
        label: String?,
        confidence: Float,
        maxDurationS: Double,
        ackRequestId: String?,
    ) {
        if (!cameraReady) {
            ackRequestId?.let { client?.sendAck(it, "error", "caméra indisponible", null) }
            return
        }
        // EF-07 : un enregistrement est déjà en cours -> prolonge sans ouvrir un 2e flux.
        if (recorder.isRecording) {
            scheduleMaxDuration(maxDurationS)
            ackRequestId?.let { client?.sendAck(it, "ok", "déjà en cours (prolongé)", activeRecordingId) }
            return
        }

        val recordingId = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        activeRecordingId = recordingId
        activeOrigin = origin
        activeLabel = label
        activeConfidence = confidence

        // Libère le micro du détecteur avant que CameraX n'enregistre son propre audio.
        detector.stop()
        val file = pending.videoFileFor(recordingId)
        recorder.start(file) { f, durMs, w, h, error ->
            onRecordingFinalized(f, durMs, w, h, error)
        }

        updateNotification(recording = true)
        ServiceState.update { it.copy(recording = true, lastEvent = "Enregistrement ($origin)") }
        ackRequestId?.let { client?.sendAck(it, "ok", null, recordingId) }
        if (origin == "sound") client?.sendSoundTriggered(recordingId, confidence, label)
        sendStatus()
        scheduleMaxDuration(maxDurationS)
    }

    private fun stopRecording(ackRequestId: String?) {
        main.removeCallbacks(stopMaxDurationRunnable)
        if (recorder.isRecording) {
            recorder.stop() // déclenche onRecordingFinalized
            ackRequestId?.let { client?.sendAck(it, "ok", null, activeRecordingId) }
        } else {
            ackRequestId?.let { client?.sendAck(it, "ok", "aucun enregistrement en cours", null) }
        }
    }

    private fun scheduleMaxDuration(maxDurationS: Double) {
        main.removeCallbacks(stopMaxDurationRunnable)
        if (maxDurationS > 0) {
            main.postDelayed(stopMaxDurationRunnable, (maxDurationS * 1000).toLong())
        }
    }

    private fun onRecordingFinalized(file: File, durMs: Long, w: Int, h: Int, error: Boolean) {
        val recordingId = activeRecordingId ?: file.nameWithoutExtension
        if (!error && file.exists() && file.length() > 0) {
            val meta = JSONObject().apply {
                put("recording_id", recordingId)
                put("device_id", cfgDeviceId)
                put("device_name", cfgDeviceName)
                put("origin", activeOrigin)
                put("sound_label", activeLabel ?: JSONObject.NULL)
                put("started_at", Messages.nowIso())
                put("duration_s", durMs / 1000.0)
                put("width", w)
                put("height", h)
                put("mime", "video/mp4")
            }
            pending.saveMeta(recordingId, meta)
        }
        activeRecordingId = null
        updateNotification(recording = false)
        ServiceState.update { it.copy(recording = false, pendingUploads = pending.list().size) }
        // Reprend l'écoute sonore puis tente le transfert.
        if (cameraReady) detector.start()
        sendStatus()
        flushUploads()
    }

    // --- Transfert (EF-08/EF-09) ---------------------------------------------

    private fun flushUploads() {
        Thread {
            val sent = uploader.flushPending()
            val remaining = pending.list().size
            ServiceState.update { it.copy(pendingUploads = remaining) }
            if (sent > 0) sendStatus()
        }.start()
    }

    // --- STATUS heartbeat (EF-13, ENF-10) ------------------------------------

    private val heartbeat = object : Runnable {
        override fun run() {
            sendStatus()
            main.postDelayed(this, 2000)
        }
    }

    private fun startHeartbeat() = main.postDelayed(heartbeat, 2000)

    private fun sendStatus() {
        val recording = recorder.isRecording
        val state = when {
            recording -> "recording"
            detector.isRunning -> "listening"
            else -> "idle"
        }
        client?.sendStatus(state, recording, batteryLevel(), activeRecordingId)
    }

    private fun batteryLevel(): Float? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct / 100f else null
    }

    // --- Plages horaires actives ---------------------------------------------

    private fun withinActiveHours(): Boolean {
        if (!activeHoursEnabled) return true
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = toMinutes(activeHoursStart)
        val end = toMinutes(activeHoursEnd)
        return if (start <= end) cur in start..end else cur >= start || cur <= end
    }

    private fun toMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    // --- Notification ---------------------------------------------------------

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_MIN,
        ).apply { description = getString(R.string.notif_channel_desc) }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(recording: Boolean): Notification {
        val text = if (recording) getString(R.string.notif_text_recording)
        else getString(R.string.notif_text_listening)
        // Pas de contentIntent : la notification n'ouvre pas l'app au clic.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(false)
            .setColor(if (recording) 0xFFEF4444.toInt() else 0xFF3B82F6.toInt())
            .build()
    }

    private fun updateNotification(recording: Boolean) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(recording))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        detector.stop()
        recorder.unbind()
        discovery.stop()
        client?.close()
        ServiceState.update { CameraServiceState(running = false) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "soundcam_service"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.soundcam.camera.STOP"

        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CameraService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
