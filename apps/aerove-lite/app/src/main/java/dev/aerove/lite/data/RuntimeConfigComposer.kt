package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.networksettings.planNetwork
import java.io.File
import java.util.Locale

class RuntimeConfigComposer(privateDirectory: File) {
    private val resources = File(privateDirectory, "resources").canonicalFile

    fun compose(prepared: PreparedProfile, settings: LiteSettings, tunFd: Int, port: Int, secret: String): String {
        val bundle = prepared.resourceDirectory
        if (bundle != null) {
            val expected = File(resources, "bundles/${prepared.profileId}/${prepared.generation}").canonicalFile
            require(bundle.canonicalFile == expected && expected.isDirectory) { "Prepared profile has a foreign resource bundle" }
        }
        return compose(prepared.yaml, settings, tunFd, port, secret, bundle)
    }

    fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String): String =
        compose(source, settings, tunFd, port, secret, null)

    private fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String, bundle: File?): String {
        require(tunFd >= 0) { "Invalid TUN descriptor" }
        val endpoint = ControlEndpoint(port, secret)
        validateSettings(settings)
        val network = planNetwork(settings.network, endpoint.port, settings.ipv6)
        val root = ConfigYaml.parse(source)
        listOf("listeners", "inbound-providers").forEach {
            if (root[it] != null) throw ConfigValidationException(listOf(ConfigIssue("$.$it", "Additional inbounds are not supported in Lite")))
        }
        listOf("external-ui", "external-ui-url", "external-controller-ipc", "external-controller-unix", "external-controller-pipe", "http_port").forEach(root::remove)
        // Port(0) still creates an ephemeral listener in clash-rs; absent shortcuts create none.
        listOf("port", "socks-port", "mixed-port", "redir-port", "tproxy-port").forEach(root::remove)
        root["external-controller"] = "127.0.0.1:${endpoint.port}"
        root["secret"] = endpoint.secret
        root.putAll(network.userConfigFields())
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
        if (settings.overrideDns || (dns["nameserver"] as? List<*>).isNullOrEmpty()) {
            dns["nameserver"] = settings.dnsServers.toList()
        }
        dns.remove("listen")
        root["dns"] = dns
        fun resourcePath(value: String): String {
            if (bundle == null) return privateResource(resources, value).path
            val file = File(value).canonicalFile
            require(file.toPath().startsWith(bundle.canonicalFile.toPath()) && file.isFile) { "Resource is outside the prepared bundle" }
            return file.path
        }
        listOf("mmdb", "asn-mmdb", "geosite").forEach { key ->
            (root[key] as? String)?.let { root[key] = resourcePath(it) }
            if (bundle != null) root.remove("$key-download-url")
        }
        listOf("proxy-providers", "rule-providers").forEach { key ->
            (root[key] as? Map<*, *>)?.forEach { (name, raw) ->
                if (raw is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val provider = raw as MutableMap<String, Any?>
                    val path = provider["path"] as? String
                    if (provider["type"] != "inline") {
                        provider["path"] = resourcePath(path ?: "providers/${java.util.UUID.nameUUIDFromBytes("$key:$name".toByteArray())}.cache")
                    }
                }
            }
        }
        (root["proxies"] as? List<*>)?.forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val proxy = raw as? MutableMap<String, Any?> ?: return@forEach
            listOf("tls-cert", "tls-key", "ca").forEach { key ->
                (proxy[key] as? String)?.let { proxy[key] = resourcePath(it) }
            }
        }
        return ConfigYaml.dump(root)
    }
}
