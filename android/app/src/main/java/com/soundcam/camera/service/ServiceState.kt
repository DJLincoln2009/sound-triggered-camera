package com.soundcam.camera.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * État partagé du service, observable par l'UI (Compose). Évite un binder complet :
 * le service publie son état ici, l'écran d'accueil l'affiche en temps réel.
 */
data class CameraServiceState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val serverName: String? = null,
    val recording: Boolean = false,
    val pendingUploads: Int = 0,
    val lastEvent: String? = null,
    val pairingError: String? = null,
)

object ServiceState {
    private val _state = MutableStateFlow(CameraServiceState())
    val state: StateFlow<CameraServiceState> = _state.asStateFlow()

    fun update(transform: (CameraServiceState) -> CameraServiceState) {
        _state.value = transform(_state.value)
    }
}
