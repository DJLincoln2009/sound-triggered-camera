package com.soundcam.camera.net

import android.util.Log
import com.soundcam.camera.data.PendingUploads
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Transfert différé des enregistrements vers le PC via HTTP multipart (EF-09, EF-23).
 *
 * Réessaie en boucle (backoff côté appelant) tant que la réponse n'est pas 200, ce qui
 * garantit la reprise après coupure réseau (EF-08, ENF-05). Idempotent côté serveur via
 * `recording_id`.
 */
class VideoUploader(private val pending: PendingUploads) {

    @Volatile private var baseUrl: String = ""
    @Volatile private var token: String = ""
    @Volatile private var tls: Boolean = false

    private val client: OkHttpClient by lazy {
        TlsTrust.client(
            tls,
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS),
        )
    }

    fun configure(host: String, httpPort: Int, tls: Boolean, token: String) {
        this.baseUrl = (if (tls) "https" else "http") + "://$host:$httpPort"
        this.tls = tls
        this.token = token
    }

    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    /** Tente de transférer toutes les vidéos en attente. Retourne le nombre transféré. */
    fun flushPending(): Int {
        if (!isConfigured) return 0
        var sent = 0
        for (item in pending.list()) {
            if (uploadOne(item)) {
                pending.remove(item.recordingId)
                sent++
            } else {
                break // réessaiera plus tard ; préserve l'ordre
            }
        }
        return sent
    }

    private fun uploadOne(item: PendingUploads.Item): Boolean {
        val mime = item.meta.optString("mime", "video/mp4")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, item.meta.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart("file", item.video.name, item.video.asRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/upload")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                Log.i(TAG, "Upload ${item.recordingId}: HTTP ${resp.code}")
                resp.isSuccessful
            }
        }.getOrElse {
            Log.w(TAG, "Upload échec ${item.recordingId}: ${it.message}")
            false
        }
    }

    companion object {
        private const val TAG = "VideoUploader"
    }
}
