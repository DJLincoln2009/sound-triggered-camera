package com.soundcam.camera.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Découverte du serveur PC via NSD/mDNS (EF-12) : type de service `_soundcam._tcp`.
 *
 * Évite à l'utilisateur de saisir une adresse IP : dès que le PC publie le service sur le
 * réseau local, on résout son hôte/port et ses attributs TXT (port HTTP, TLS).
 */
class Discovery(context: Context) {

    data class Server(val host: String, val wsPort: Int, val httpPort: Int, val tls: Boolean, val name: String)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start(onFound: (Server) -> Unit) {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Échec démarrage découverte: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                resolve(service, onFound)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "Service perdu: ${service.serviceName}")
            }
        }
        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(service: NsdServiceInfo, onFound: (Server) -> Unit) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Échec résolution: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val attrs = info.attributes
                fun txt(key: String): String? = attrs[key]?.let { String(it) }
                val httpPort = txt("http")?.toIntOrNull() ?: info.port
                val wsPort = txt("ws")?.toIntOrNull() ?: info.port
                val tls = txt("tls") == "1"
                val name = txt("name") ?: info.serviceName
                val host = info.host?.hostAddress ?: return
                onFound(Server(host, wsPort, httpPort, tls, name))
            }
        })
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    companion object {
        private const val TAG = "Discovery"
        const val SERVICE_TYPE = "_soundcam._tcp."
    }
}
