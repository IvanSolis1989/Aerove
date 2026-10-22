package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import java.io.File
import java.util.Locale

class RuntimeConfigComposer(privateDirectory: File) {
    private val resources = File(privateDirectory, "resources").canonicalFile

    fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String): String {
        require(tunFd >= 0) { "Invalid TUN descriptor" }
        val endpoint = ControlEndpoint(port, secret)
        validateSettings(settings)
        val root = ConfigYaml.parse(source)
        listOf("listeners", "inbound-providers").forEach {
            if (root[it] != null) throw ConfigValidationException(listOf(ConfigIssue("$.$it", "Additional inbounds are not supported in Lite")))
        }
        listOf("external-ui", "external-ui-url", "external-controller-ipc", "external-controller-unix", "external-controller-pipe", "http_port").forEach(root::remove)
        // Port(0) still creates an ephemeral listener in clash-rs; absent shortcuts create none.
        listOf("port", "socks-port", "mixed-port", "redir-port", "tproxy-port").forEach(root::remove)
        root["external-controller"] = "127.0.0.1:${endpoint.port}"
        root["secret"] = endpoint.secret
        root["allow-lan"] = false
        root["bind-address"] = "127.0.0.1"
        root["authentication"] = emptyList<String>()
        root["cors-allow-origins"] = emptyList<String>()
        root["mode"] = settings.mode.name.lowercase(Locale.ROOT)
        root["ipv6"] = settings.ipv6
        root["tun"] = linkedMapOf<String, Any?>(
            "enable" to true,
            "device-id" to "fd://$tunFd",
            "gateway" to "198.18.0.1/30",
            "mtu" to 1500,
            "dns-hijack" to true,
        ).apply { if (settings.ipv6) put("gateway-v6", "fdfe:dcba:9876::1/126") }
        @Suppress("UNCHECKED_CAST")
        val dns = (root["dns"] as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()
        dns["enable"] = true
        dns["ipv6"] = settings.ipv6
        dns["nameserver"] = settings.dnsServers.toList()
        dns.remove("listen")
        root["dns"] = dns
        listOf("mmdb", "asn-mmdb", "geosite").forEach { key ->
            (root[key] as? String)?.let { root[key] = privateResource(resources, it).path }
        }
        listOf("proxy-providers", "rule-providers").forEach { key ->
            (root[key] as? Map<*, *>)?.forEach { (name, raw) ->
                if (raw is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val provider = raw as MutableMap<String, Any?>
                    val path = provider["path"] as? String
                    provider["path"] = privateResource(resources, path ?: "providers/${java.util.UUID.nameUUIDFromBytes("$key:$name".toByteArray())}.cache").path
                }
            }
        }
        (root["proxies"] as? List<*>)?.forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val proxy = raw as? MutableMap<String, Any?> ?: return@forEach
            listOf("tls-cert", "tls-key", "ca").forEach { key ->
                (proxy[key] as? String)?.let { proxy[key] = privateResource(resources, it).path }
            }
        }
        return ConfigYaml.dump(root)
    }
}
