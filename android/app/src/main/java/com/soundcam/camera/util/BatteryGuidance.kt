package com.soundcam.camera.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Guidage batterie par constructeur (EF-11). Plusieurs fabricants (Xiaomi, Huawei, Oppo,
 * Vivo, OnePlus…) superposent un gestionnaire de batterie agressif qui peut tuer le
 * foreground service. On détecte la marque et on oriente l'utilisateur vers le réglage
 * pertinent ; référence : https://dontkillmyapp.com.
 */
object BatteryGuidance {

    data class Guidance(val manufacturer: String, val message: String, val dontKillMyAppUrl: String)

    private val AGGRESSIVE = setOf(
        "xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme",
        "vivo", "oneplus", "samsung", "meizu", "asus",
    )

    /** Renvoie un guidage si la marque est connue pour des restrictions agressives. */
    fun forCurrentDevice(): Guidance? {
        val brand = (Build.MANUFACTURER ?: "").lowercase()
        if (AGGRESSIVE.none { brand.contains(it) }) return null
        val name = Build.MANUFACTURER ?: "votre appareil"
        return Guidance(
            manufacturer = name,
            message = "Votre appareil ($name) applique une gestion de batterie qui peut " +
                "interrompre la surveillance en arrière-plan. Désactivez les optimisations " +
                "de batterie pour SoundCam et autorisez le démarrage automatique.",
            dontKillMyAppUrl = "https://dontkillmyapp.com/${brand.takeWhile { it.isLetter() }}",
        )
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Intent vers l'écran système des optimisations de batterie. */
    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun dontKillMyAppIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
}
