package dev.aerove.lite.integration

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.vpn.VpnSnapshot
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private data class AssetProxyAuthenticator(private val port: Int, private val username: String,
    private val password: String) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val address = route?.proxy?.address() as? InetSocketAddress ?: return null
        if (route.proxy.type() != Proxy.Type.HTTP || address.hostString != "127.0.0.1" || address.port != port ||
            response.request.header("Proxy-Authorization") != null) return null
        return response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(username, password, Charsets.UTF_8)).build()
    }
    override fun toString(): String = "AssetProxyAuthenticator(<redacted>)"
}

/** A fixed per-call route is part of OkHttp Address equality; dynamic selectors can reuse an obsolete proxy. */
internal fun runtimeAssetHttpClient(client: OkHttpClient, snapshot: VpnSnapshot, url: HttpUrl): OkHttpClient {
    if (url.host == "127.0.0.1" || snapshot.phase != VpnPhase.Running || !snapshot.listening || snapshot.suspended)
        return client.newBuilder().proxy(Proxy.NO_PROXY).proxyAuthenticator(Authenticator.NONE).build()
    val actual = checkNotNull(snapshot.appliedNetwork ?: snapshot.initialNetwork) { "The running listener has no actual network snapshot" }
    val auth = actual.authentication
    return client.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", actual.ports.mixed)))
        .proxyAuthenticator(if (auth.enabled) AssetProxyAuthenticator(actual.ports.mixed, auth.username, auth.password) else Authenticator.NONE)
        .build()
}
