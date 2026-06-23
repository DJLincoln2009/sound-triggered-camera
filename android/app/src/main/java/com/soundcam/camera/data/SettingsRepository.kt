package com.soundcam.camera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.soundcam.camera.protocol.RemoteSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "soundcam")

/** Réglages persistés de l'app caméra (réglages distants + appairage + identité). */
data class AppConfig(
    val deviceId: String,
    val deviceName: String,
    val threshold: Float,
    val videoQuality: String,
    val useClassifier: Boolean,
    val targetLabels: List<String>,
    val activeHoursEnabled: Boolean,
    val activeHoursStart: String,
    val activeHoursEnd: String,
    val pairingToken: String?,   // token fort émis par le PC après appairage (ENF-06)
    val serverHost: String?,     // découvert par NSD ou saisi
    val serverPort: Int,
    val serverTls: Boolean,
    val serviceEnabled: Boolean,
    val iosInfoSeen: Boolean,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THRESHOLD = floatPreferencesKey("threshold")
        val QUALITY = stringPreferencesKey("video_quality")
        val USE_CLASSIFIER = booleanPreferencesKey("use_classifier")
        val TARGET_LABELS = stringPreferencesKey("target_labels")
        val AH_ENABLED = booleanPreferencesKey("ah_enabled")
        val AH_START = stringPreferencesKey("ah_start")
        val AH_END = stringPreferencesKey("ah_end")
        val PAIRING_TOKEN = stringPreferencesKey("pairing_token")
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = floatPreferencesKey("server_port")
        val SERVER_TLS = booleanPreferencesKey("server_tls")
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
    }

    val config: Flow<AppConfig> = context.dataStore.data.map { p -> toConfig(p) }

    private fun toConfig(p: Preferences): AppConfig = AppConfig(
        deviceId = p[Keys.DEVICE_ID] ?: "",
        deviceName = p[Keys.DEVICE_NAME] ?: (android.os.Build.MODEL ?: "Caméra"),
        threshold = p[Keys.THRESHOLD] ?: 0.35f,
        videoQuality = p[Keys.QUALITY] ?: "HD_720P",
        useClassifier = p[Keys.USE_CLASSIFIER] ?: false,
        targetLabels = (p[Keys.TARGET_LABELS] ?: "Speech,Dog,Glass")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() },
        activeHoursEnabled = p[Keys.AH_ENABLED] ?: false,
        activeHoursStart = p[Keys.AH_START] ?: "00:00",
        activeHoursEnd = p[Keys.AH_END] ?: "23:59",
        pairingToken = p[Keys.PAIRING_TOKEN],
        serverHost = p[Keys.SERVER_HOST],
        serverPort = (p[Keys.SERVER_PORT] ?: 8766f).toInt(),
        serverTls = p[Keys.SERVER_TLS] ?: false,
        serviceEnabled = p[Keys.SERVICE_ENABLED] ?: false,
        iosInfoSeen = true,
    )

    /** Assure la présence d'un identifiant d'appareil stable. */
    suspend fun ensureDeviceId() {
        context.dataStore.edit { p ->
            if (p[Keys.DEVICE_ID].isNullOrEmpty()) {
                p[Keys.DEVICE_ID] = UUID.randomUUID().toString().take(8)
            }
        }
    }

    suspend fun applyRemoteSettings(s: RemoteSettings) {
        context.dataStore.edit { p ->
            s.threshold?.let { p[Keys.THRESHOLD] = it }
            s.videoQuality?.let { p[Keys.QUALITY] = it }
            s.useClassifier?.let { p[Keys.USE_CLASSIFIER] = it }
            s.targetLabels?.let { p[Keys.TARGET_LABELS] = it.joinToString(",") }
            s.activeHoursEnabled?.let { p[Keys.AH_ENABLED] = it }
            s.activeHoursStart?.let { p[Keys.AH_START] = it }
            s.activeHoursEnd?.let { p[Keys.AH_END] = it }
        }
    }

    suspend fun setPairingToken(token: String?) =
        context.dataStore.edit { p ->
            if (token == null) p.remove(Keys.PAIRING_TOKEN) else p[Keys.PAIRING_TOKEN] = token
        }

    suspend fun setServer(host: String?, port: Int, tls: Boolean) =
        context.dataStore.edit { p ->
            if (host == null) p.remove(Keys.SERVER_HOST) else p[Keys.SERVER_HOST] = host
            p[Keys.SERVER_PORT] = port.toFloat()
            p[Keys.SERVER_TLS] = tls
        }

    suspend fun setServiceEnabled(enabled: Boolean) =
        context.dataStore.edit { p -> p[Keys.SERVICE_ENABLED] = enabled }

    suspend fun setDeviceName(name: String) =
        context.dataStore.edit { p -> p[Keys.DEVICE_NAME] = name }

    suspend fun setThreshold(value: Float) =
        context.dataStore.edit { p -> p[Keys.THRESHOLD] = value }
}
