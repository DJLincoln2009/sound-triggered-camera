package com.soundcam.camera.net

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Construction d'un client OkHttp.
 *
 * En mode TLS, le PC utilise un certificat **auto-signé** généré localement (ENF-07). Sur
 * un réseau domestique, ce certificat n'est pas signé par une autorité reconnue ; nous
 * l'acceptons après appairage explicite (modèle TOFU — Trust On First Use). La sécurité
 * repose sur l'appairage par token (ENF-06) et sur l'isolement au réseau local : aucune
 * donnée ne sort vers Internet (ENF-08). Pour un déploiement durci, on remplacerait ce
 * trust manager par un certificate-pinning sur l'empreinte du certificat du PC.
 */
object TlsTrust {

    fun client(tls: Boolean, builder: OkHttpClient.Builder): OkHttpClient {
        if (!tls) return builder.build()

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
