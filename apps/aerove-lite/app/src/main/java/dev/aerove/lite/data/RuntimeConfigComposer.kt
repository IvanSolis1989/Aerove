package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.NativeCore.RuntimeMode
import dev.aerove.lite.networksettings.planNetwork
import java.io.File
import java.util.Locale
import java.net.InetAddress
import dev.aerove.lite.requests.RequestPreferences
import org.json.JSONObject

data class TunRuntimeOptions(val mtu: Int, val dnsHijacking: Boolean, val ipv6: Boolean)

data class SystemDnsSnapshot(
    val defaultInterface: String? = null,
    val interfaces: Map<String, List<String>> = emptyMap(),
) {
    fun config(): Map<String, Any?> {
        require(interfaces.size <= 64 && (defaultInterface == null || defaultInterface in interfaces)) { "Invalid system DNS interfaces" }
        val copied = interfaces.mapValues { (name, addresses) ->
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && addresses.size <= 16) { "Invalid system DNS interface" }
            addresses.map { address ->
                val parts = address.split('%')
                val literal = parts.first()
                require(parts.size <= 2 && (parts.size == 1 || (':' in literal && (parts[1] == name || (parts[1].all { it in '0'..'9' } && parts[1].toULongOrNull()?.let { it in 1uL..UInt.MAX_VALUE.toULong() } == true)))) &&
                    literal.isNotEmpty() && literal.all { it in "0123456789abcdefABCDEF:." } &&
                    (':' in literal || (literal.split('.').size == 4 && literal.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }))) {
                    "System DNS must contain IP literals"
                }
                // Parse only the literal: scoped Android addresses need not name a host JVM interface.
                val parsed = InetAddress.getByName(literal)
                require(!parsed.isAnyLocalAddress && !parsed.isMulticastAddress) { "Invalid system DNS address" }
                address
            }
        }
        return linkedMapOf("defaultInterface" to defaultInterface, "interfaces" to copied)
    }

    fun json(): String = JSONObject(config()).toString()
}

fun effectiveProfileDnsEnabled(source: String): Boolean = (ConfigYaml.parse(source)["dns"] as? Map<*, *>)?.get("enable") == true
fun effectiveProfileDnsEnabled(prepared: PreparedProfile): Boolean = effectiveProfileDnsEnabled(prepared.yaml)

class RuntimeConfigComposer(privateDirectory: File, private val defaultUserAgent: String) {
    constructor(privateDirectory: File) : this(privateDirectory, "AEROVE-Lite")
    private val resources = File(privateDirectory, "resources").canonicalFile

    fun compose(prepared: PreparedProfile, settings: LiteSettings, tunFd: Int, port: Int, secret: String,
        runtimeMode: RuntimeMode = if (settings.network.vpnEnabled) RuntimeMode.Tun else RuntimeMode.Proxy,
        systemDnsSnapshot: SystemDnsSnapshot = SystemDnsSnapshot(),
        tunOptions: TunRuntimeOptions = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6),
        initiallyParked: Boolean = false): String {
        val bundle = prepared.resourceDirectory
        if (bundle != null) {
            val expected = File(resources, "bundles/${prepared.profileId}/${prepared.generation}").canonicalFile
            require(bundle.canonicalFile == expected && expected.isDirectory) { "Prepared profile has a foreign resource bundle" }
        }
        return compose(prepared.yaml, settings, tunFd, port, secret, bundle, runtimeMode, systemDnsSnapshot, tunOptions, initiallyParked)
    }

    internal fun composeFlutter(prepared: PreparedProfile, lease: FlutterRuntimeResources, settings: LiteSettings,
        tunFd: Int, port: Int, secret: String, runtimeMode: RuntimeMode, systemDnsSnapshot: SystemDnsSnapshot,
        tunOptions: TunRuntimeOptions, initiallyParked: Boolean, normalizedYaml: String = prepared.yaml): String = compose(normalizedYaml, settings, tunFd, port,
            secret, lease.verifyReadLease(prepared), runtimeMode, systemDnsSnapshot, tunOptions, initiallyParked)

    fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String,
        runtimeMode: RuntimeMode = if (settings.network.vpnEnabled) RuntimeMode.Tun else RuntimeMode.Proxy,
        systemDnsSnapshot: SystemDnsSnapshot = SystemDnsSnapshot(),
        tunOptions: TunRuntimeOptions = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6),
        initiallyParked: Boolean = false): String =
        compose(source, settings, tunFd, port, secret, null, runtimeMode, systemDnsSnapshot, tunOptions, initiallyParked)

    private fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String, bundle: File?, runtimeMode: RuntimeMode, systemDnsSnapshot: SystemDnsSnapshot, tunOptions: TunRuntimeOptions, initiallyParked: Boolean): String {
        require(if (runtimeMode == RuntimeMode.Tun && !initiallyParked) tunFd >= 0 else tunFd == -1) { "Invalid descriptor for $runtimeMode" }
        require(runtimeMode != RuntimeMode.Tun || tunOptions.mtu in 1280..65535) { "Invalid actual tunnel MTU" }
        val endpoint = ControlEndpoint(port, secret)
        validateSettings(settings)
        require(RequestPreferences.validUserAgent(defaultUserAgent)) { "Invalid default User-Agent" }
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
        root["find-process-mode"] = settings.findProcessMode.wireName
        root["ipv6"] = settings.ipv6
        root["unified-delay"] = settings.unifiedDelay
        root["tcp-concurrent"] = settings.tcpConcurrent
        root["global-ua"] = settings.requests.globalUserAgent ?: defaultUserAgent
        root["check-certificate"] = settings.requests.checkCertificate
        root["aerove-system-dns"] = systemDnsSnapshot.config()
        root["tun"] = if (runtimeMode == RuntimeMode.Proxy) linkedMapOf<String, Any?>("enable" to false) else linkedMapOf<String, Any?>(
            "enable" to true,
            "gateway" to "198.18.0.1/30",
            "mtu" to tunOptions.mtu,
            "dns-hijack" to (effectiveProfileDnsEnabled(source) && tunOptions.dnsHijacking),
        ).apply {
            if (!initiallyParked) put("device-id", "fd://$tunFd")
            if (tunOptions.ipv6) put("gateway-v6", "fdfe:dcba:9876::1/126")
        }
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
