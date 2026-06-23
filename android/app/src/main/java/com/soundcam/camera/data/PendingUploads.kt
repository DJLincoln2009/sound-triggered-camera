package com.soundcam.camera.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * File d'attente locale des enregistrements à transférer (EF-08, ENF-05).
 *
 * Chaque enregistrement est stocké dans le répertoire privé de l'app avec un fichier
 * « .meta.json » associé. Tant que l'upload n'a pas réussi, l'entrée reste présente et
 * sera retransférée automatiquement à la reconnexion.
 */
class PendingUploads(context: Context) {

    val dir: File = File(context.filesDir, "pending").apply { mkdirs() }

    data class Item(val video: File, val meta: JSONObject) {
        val recordingId: String get() = meta.optString("recording_id")
    }

    fun videoFileFor(recordingId: String, ext: String = "mp4"): File =
        File(dir, "$recordingId.$ext")

    /** Enregistre les métadonnées associées à un fichier vidéo finalisé. */
    fun saveMeta(recordingId: String, meta: JSONObject) {
        File(dir, "$recordingId.meta.json").writeText(meta.toString())
    }

    fun list(): List<Item> =
        dir.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { metaFile ->
                runCatching {
                    val meta = JSONObject(metaFile.readText())
                    val rid = meta.optString("recording_id")
                    val ext = if (meta.optString("mime").contains("webm")) "webm" else "mp4"
                    val video = File(dir, "$rid.$ext")
                    if (video.exists()) Item(video, meta) else null
                }.getOrNull()
            }
            ?.sortedBy { it.video.lastModified() }
            ?: emptyList()

    fun remove(recordingId: String) {
        File(dir, "$recordingId.meta.json").delete()
        File(dir, "$recordingId.mp4").delete()
        File(dir, "$recordingId.webm").delete()
    }
}
