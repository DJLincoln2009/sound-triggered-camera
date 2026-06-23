package com.soundcam.camera.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Écoute continue du micro et détection de déclenchement sonore (EF-03, EF-04).
 *
 * Deux modes :
 *  - **Amplitude** (par défaut) : déclenche si l'énergie RMS normalisée dépasse le seuil.
 *  - **Classifieur** (optionnel) : utilise un modèle YAMNet TensorFlow Lite embarqué
 *    (`assets/yamnet.tflite`) pour ne réagir qu'aux catégories de sons ciblées. Si le
 *    modèle est absent, repli automatique sur le mode amplitude.
 *
 * Le détecteur ne décide pas de l'enregistrement : il notifie [onTrigger] et le service
 * applique les règles (plages horaires, concurrence). Il libère le micro via [stop] le
 * temps qu'une capture CameraX (qui enregistre son propre audio) ait lieu.
 */
class SoundDetector(
    private val context: Context,
    private val onTrigger: (confidence: Float, label: String?) -> Unit,
) {

    @Volatile var threshold: Float = 0.35f
    @Volatile var useClassifier: Boolean = false
    @Volatile var targetLabels: List<String> = listOf("Speech", "Dog", "Glass")

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    val isRunning: Boolean get() = running

    private fun loop() {
        val classifier = if (useClassifier) ClassifierEngine.tryCreate(context) else null
        try {
            if (classifier != null) classifierLoop(classifier) else amplitudeLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "Boucle de détection interrompue: ${t.message}")
        } finally {
            classifier?.close()
        }
    }

    // --- Mode amplitude -------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun amplitudeLoop() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf,
        )
        val buffer = ShortArray(minBuf / 2)
        var lastTrigger = 0L
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buffer[i].toDouble()
                    sum += s * s
                }
                val rms = sqrt(sum / n)
                val level = (rms / Short.MAX_VALUE).toFloat() // 0..1
                val now = System.currentTimeMillis()
                if (level >= threshold && now - lastTrigger > TRIGGER_COOLDOWN_MS) {
                    lastTrigger = now
                    onTrigger(level.coerceIn(0f, 1f), null)
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    // --- Mode classifieur (YAMNet) -------------------------------------------
    private fun classifierLoop(engine: ClassifierEngine) {
        var lastTrigger = 0L
        engine.start()
        while (running) {
            val result = engine.classifyOnce() ?: continue
            val now = System.currentTimeMillis()
            val match = result.firstOrNull { cat ->
                cat.score >= threshold && targetLabels.any { it.equals(cat.label, ignoreCase = true) }
            }
            if (match != null && now - lastTrigger > TRIGGER_COOLDOWN_MS) {
                lastTrigger = now
                onTrigger(match.score, match.label)
            }
            Thread.sleep(engine.intervalMs)
        }
        engine.stopRecording()
    }

    companion object {
        private const val TAG = "SoundDetector"
        private const val TRIGGER_COOLDOWN_MS = 3000L
    }
}
