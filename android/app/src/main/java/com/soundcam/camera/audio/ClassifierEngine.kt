package com.soundcam.camera.audio

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier

/**
 * Enveloppe du classifieur audio YAMNet (TensorFlow Lite), chargé depuis
 * `assets/yamnet.tflite`. Renvoie `null` à la création si le modèle est absent, ce qui
 * permet au [SoundDetector] de retomber sur la détection par amplitude (EF-04, Should).
 *
 * Pour activer ce mode : déposez `yamnet.tflite` dans `app/src/main/assets/` (voir
 * `app/src/main/assets/README.md`).
 */
class ClassifierEngine private constructor(
    private val classifier: AudioClassifier,
) {
    data class Cat(val label: String, val score: Float)

    private val tensor: TensorAudio = classifier.createInputTensorAudio()
    private var record: AudioRecord? = null
    val intervalMs: Long = 500L

    fun start() {
        record = classifier.createAudioRecord().also { it.startRecording() }
    }

    fun classifyOnce(): List<Cat>? {
        val rec = record ?: return null
        tensor.load(rec)
        val output = classifier.classify(tensor)
        if (output.isEmpty()) return emptyList()
        return output[0].categories.map { Cat(it.label, it.score) }
    }

    fun stopRecording() {
        runCatching { record?.stop() }
        record?.release()
        record = null
    }

    fun close() {
        stopRecording()
        runCatching { classifier.close() }
    }

    companion object {
        private const val TAG = "ClassifierEngine"
        private const val MODEL_ASSET = "yamnet.tflite"

        fun tryCreate(context: Context): ClassifierEngine? = runCatching {
            val classifier = AudioClassifier.createFromFile(context, MODEL_ASSET)
            ClassifierEngine(classifier)
        }.getOrElse {
            Log.w(TAG, "Modèle YAMNet indisponible ($MODEL_ASSET) — repli sur amplitude: ${it.message}")
            null
        }
    }
}
