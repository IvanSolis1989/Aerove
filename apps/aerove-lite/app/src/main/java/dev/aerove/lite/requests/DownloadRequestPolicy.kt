package dev.aerove.lite.requests

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** A private download client: never changes the injected/shared client's TLS state or pool. */
internal fun OkHttpClient.forDownload(preferences: RequestPreferences): OkHttpClient {
    if (preferences.checkCertificate) return this
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    return newBuilder().connectionPool(ConnectionPool()).sslSocketFactory(context.socketFactory, trust)
        .hostnameVerifier { _, _ -> true }.build()
}
