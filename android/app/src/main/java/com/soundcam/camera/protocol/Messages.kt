package com.soundcam.camera.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Construction et analyse des messages du canal de commandes (protocole v1).
 *
 * Miroir Kotlin de `protocol/PROTOCOL.md` et de `receiver/protocol.py`. Toute évolution
 * doit rester cohérente entre les trois implémentations (Android, iOS, PC).
 */
object Messages {

    const val PROTOCOL_VERSION = 1

    // Types de messages (EF-22).
    const val HELLO = "HELLO"
    const val HELLO_ACK = "HELLO_ACK"
    const val START_RECORDING = "START_RECORDING"
    const val STOP_RECORDING = "STOP_RECORDING"
    const val SET_SETTINGS = "SET_SETTINGS"
    const val SOUND_TRIGGERED = "SOUND_TRIGGERED"
    const val STATUS = "STATUS"
    const val ACK = "ACK"

    // Diffusion en direct WebRTC (signaling relayé par le PC).
    const val LIVE_REQUEST = "LIVE_REQUEST"
    const val LIVE_OFFER = "LIVE_OFFER"
    const val LIVE_ANSWER = "LIVE_ANSWER"
    const val LIVE_ICE = "LIVE_ICE"
    const val LIVE_STOP = "LIVE_STOP"

    private val iso: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun nowIso(): String = iso.format(Date())

    fun type(raw: String): String? = runCatching { JSONObject(raw).optString("type") }.getOrNull()

    fun parse(raw: String): JSONObject = JSONObject(raw)

    fun hello(token: String, device: DeviceInfo): String = JSONObject().apply {
        put("type", HELLO)
        put("protocol", PROTOCOL_VERSION)
        put("token", token)
        put("device", JSONObject().apply {
            put("id", device.id)
            put("name", device.name)
            put("platform", "android")
            put("model", device.model)
            put("manufacturer", device.manufacturer)
            put("app_version", device.appVersion)
        })
    }.toString()

    fun status(state: String, recording: Boolean, batteryLevel: Float?, recordingId: String?): String =
        JSONObject().apply {
            put("type", STATUS)
            put("state", state)
            put("recording", recording)
            put("battery_level", batteryLevel ?: JSONObject.NULL)
            put("recording_id", recordingId ?: JSONObject.NULL)
            put("timestamp", nowIso())
        }.toString()

    fun soundTriggered(recordingId: String, confidence: Float, label: String?): String =
        JSONObject().apply {
            put("type", SOUND_TRIGGERED)
            put("timestamp", nowIso())
            put("confidence", confidence.toDouble())
            put("sound_label", label ?: JSONObject.NULL)
            put("recording_id", recordingId)
        }.toString()

    fun ack(requestId: String, result: String, reason: String?, recordingId: String?): String =
        JSONObject().apply {
            put("type", ACK)
            put("request_id", requestId)
            put("result", result)
            put("reason", reason ?: JSONObject.NULL)
            put("recording_id", recordingId ?: JSONObject.NULL)
        }.toString()

    // --- Diffusion en direct (caméra -> PC -> navigateur) --------------------

    fun liveOffer(sessionId: String, sdp: String): String = JSONObject().apply {
        put("type", LIVE_OFFER)
        put("session_id", sessionId)
        put("sdp", sdp)
    }.toString()

    fun liveIce(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int): String =
        JSONObject().apply {
            put("type", LIVE_ICE)
            put("session_id", sessionId)
            put("candidate", JSONObject().apply {
                put("candidate", candidate)
                put("sdpMid", sdpMid ?: JSONObject.NULL)
                put("sdpMLineIndex", sdpMLineIndex)
            })
        }.toString()

    fun liveStop(sessionId: String): String = JSONObject().apply {
        put("type", LIVE_STOP)
        put("session_id", sessionId)
    }.toString()
}

/** Identité de l'appareil caméra annoncée dans le HELLO. */
data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String,
    val manufacturer: String,
    val appVersion: String,
)

/** Réglages distants reçus via SET_SETTINGS (EF-10/EF-18). */
data class RemoteSettings(
    val threshold: Float? = null,
    val videoQuality: String? = null,
    val useClassifier: Boolean? = null,
    val targetLabels: List<String>? = null,
    val transferMode: String? = null,
    val activeHoursEnabled: Boolean? = null,
    val activeHoursStart: String? = null,
    val activeHoursEnd: String? = null,
) {
    companion object {
        fun fromJson(o: JSONObject): RemoteSettings {
            val ah = o.optJSONObject("active_hours")
            val labels = o.optJSONArray("target_labels")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            return RemoteSettings(
                threshold = if (o.has("threshold")) o.getDouble("threshold").toFloat() else null,
                videoQuality = o.optStringOrNull("video_quality"),
                useClassifier = if (o.has("use_classifier")) o.getBoolean("use_classifier") else null,
                targetLabels = labels,
                transferMode = o.optStringOrNull("transfer_mode"),
                activeHoursEnabled = ah?.let { if (it.has("enabled")) it.getBoolean("enabled") else null },
                activeHoursStart = ah?.optStringOrNull("start"),
                activeHoursEnd = ah?.optStringOrNull("end"),
            )
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
