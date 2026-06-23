package com.soundcam.camera.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/**
 * Contrôleur de capture vidéo basé sur CameraX (EF-01). Fonctionne sans aperçu, piloté
 * par le foreground service, ce qui permet la capture en arrière-plan (EF-02).
 *
 * La qualité est configurable (480p–1080p, EF-10) et l'audio est enregistré avec la vidéo.
 */
class VideoRecorder(private val context: Context) {

    fun interface Finalized {
        fun onFinalized(file: File, durationMs: Long, width: Int, height: Int, error: Boolean)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val mainExecutor: Executor get() = ContextCompat.getMainExecutor(context)

    @Volatile var quality: String = "HD_720P"
    val isRecording: Boolean get() = recording != null

    /** Lie la caméra au cycle de vie du service. À appeler sur le thread principal. */
    fun bind(lifecycleOwner: LifecycleOwner, onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector())
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                )
                videoCapture = capture
                onReady(true)
            } catch (t: Throwable) {
                Log.e(TAG, "Échec liaison caméra: ${t.message}")
                onReady(false)
            }
        }, mainExecutor)
    }

    private fun qualitySelector(): QualitySelector {
        val q = when (quality) {
            "SD_480P" -> Quality.SD
            "FHD_1080P" -> Quality.FHD
            else -> Quality.HD
        }
        return QualitySelector.from(q, androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
    }

    @SuppressLint("MissingPermission")
    fun start(outputFile: File, onFinalized: Finalized) {
        val capture = videoCapture ?: run {
            onFinalized.onFinalized(outputFile, 0, 0, 0, true)
            return
        }
        if (recording != null) return // EF-07 : pas de double flux

        val options = FileOutputOptions.Builder(outputFile).build()
        var pending = capture.output.prepareRecording(context, options)
        if (hasAudio()) {
            pending = pending.withAudioEnabled()
        }
        val startedAt = System.currentTimeMillis()
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                val (w, h) = resolutionForQuality()
                val dur = System.currentTimeMillis() - startedAt
                recording = null
                onFinalized.onFinalized(outputFile, dur, w, h, event.hasError())
            }
        }
    }

    fun stop() {
        recording?.stop()
        recording = null
    }

    fun unbind() {
        stop()
        cameraProvider?.unbindAll()
    }

    private fun hasAudio(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun resolutionForQuality(): Pair<Int, Int> = when (quality) {
        "SD_480P" -> 720 to 480
        "FHD_1080P" -> 1920 to 1080
        else -> 1280 to 720
    }

    companion object {
        private const val TAG = "VideoRecorder"
    }
}
