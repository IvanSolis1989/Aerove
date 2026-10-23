package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.NativeCore.RuntimeMode
import java.io.File

/** Candidate only. None of these plans starts listeners or transfers ownership of the caller FD. */
class MeowRuntimeConfig internal constructor(
    val engineYaml: String,
    val privateControl: ControlEndpoint,
    val tun: MeowTunPlan?,
    val userInboundYaml: String,
    val hostPolicyYaml: String,
    val initiallyParked: Boolean,
    val profileId: String?,
    val revision: String?,
    val resourceGeneration: String?,
) {
    override fun toString() = "MeowRuntimeConfig(parked=$initiallyParked, tun=${tun != null}, credentials=<redacted>)"
}

class MeowTunPlan internal constructor(val descriptor: Int?, val rawYaml: String) {
    override fun toString() = "MeowTunPlan(descriptorPresent=${descriptor != null})"
}

/** Reuses the existing safe resource/control composition; only the engine dialect is adapted here. */
class MeowRuntimeConfigComposer(privateDirectory: File, defaultUserAgent: String = "AEROVE-Lite") {
    private val shared = RuntimeConfigComposer(privateDirectory, defaultUserAgent)

    fun compose(prepared: PreparedProfile, settings: LiteSettings, tunFd: Int, port: Int, secret: String,
        runtimeMode: RuntimeMode = if (settings.network.vpnEnabled) RuntimeMode.Tun else RuntimeMode.Proxy,
        systemDns: SystemDnsSnapshot = SystemDnsSnapshot(),
        tunOptions: TunRuntimeOptions = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6),
        initiallyParked: Boolean = false): MeowRuntimeConfig {
        val input = input(prepared.yaml)
        val composed = shared.compose(prepared.copy(yaml = ConfigYaml.dump(input.first)), settings, tunFd, port,
            secret, runtimeMode, systemDns, tunOptions, initiallyParked)
        return finish(composed, input.second, tunFd, runtimeMode, tunOptions, initiallyParked,
            prepared.profileId, prepared.revision, prepared.generation)
    }

    fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String,
        runtimeMode: RuntimeMode = if (settings.network.vpnEnabled) RuntimeMode.Tun else RuntimeMode.Proxy,
        systemDns: SystemDnsSnapshot = SystemDnsSnapshot(),
        tunOptions: TunRuntimeOptions = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6),
        initiallyParked: Boolean = false): MeowRuntimeConfig {
        val input = input(source)
        return finish(shared.compose(ConfigYaml.dump(input.first), settings, tunFd, port, secret,
            runtimeMode, systemDns, tunOptions, initiallyParked), input.second, tunFd, runtimeMode,
            tunOptions, initiallyParked, null, null, null)
    }

    private fun invalid(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))

    private fun input(source: String): Pair<MutableMap<String, Any?>, List<Any?>> {
        val root = ConfigYaml.parse(source)
        if (root.containsKey("inbound-providers")) invalid("$.inbound-providers", "No Meow v0.26.1 schema; bridge support is required")
        listOf("firewall", "udp", "udp-timeout").forEach { if (root.containsKey(it)) invalid("$.$it", "Meow warns and ignores this root field") }
        val tun = root["tun"]
        if (tun != null && tun !is Map<*, *>) invalid("$.tun", "Expected an object")
        (tun as? Map<*, *>)?.forEach { (key, _) ->
            if (key !in setOf("enable", "device", "mtu", "inet4-address", "inet6-address", "auto-route", "dns-hijack"))
                invalid("$.tun.$key", "Not consumed by the Android typed TUN plan; warn-only fields cannot be ignored")
        }
        // Source TUN device/address/route choices cannot silently supersede the actual Android Builder.
        (tun as? Map<*, *>)?.forEach { (key, value) ->
            val valid = when (key) {
                "enable" -> value is Boolean
                "device" -> value is String && value.isNotBlank() && !value.startsWith("fd://")
                "mtu" -> value is Number && value.toLong() in 1280..65535 && value.toDouble() == value.toLong().toDouble()
                "auto-route" -> value is Boolean || value in setOf("global", "fake-ip")
                "dns-hijack" -> value is List<*> && value.all { it is String && it == "any:53" }
                "inet4-address", "inet6-address" -> value is String && value.isNotBlank()
                else -> false
            }
            if (!valid) invalid("$.tun.$key", "Invalid Meow RawTun value")
            if (key in setOf("device", "inet4-address", "inet6-address", "auto-route"))
                invalid("$.tun.$key", "Explicit source TUN policy requires Android Builder reconciliation before activation")
        }
        val raw = root.remove("listeners") ?: emptyList<Any?>()
        if (raw !is List<*>) invalid("$.listeners", "Expected a list")
        val names = mutableSetOf<String>()
        raw.forEachIndexed { index, item ->
            val path = "$.listeners[$index]"
            val row = item as? Map<*, *> ?: invalid(path, "Expected an object")
            val allowed = setOf("name", "type", "port", "listen", "tproxy-sni", "firewall", "udp-timeout", "max-connections", "cipher", "password", "udp", "simple-obfs")
            row.keys.forEach { if (it !in allowed) invalid("$path.$it", "Unknown or warn-only listener field") }
            val name = row["name"] as? String ?: invalid("$path.name", "Expected a name")
            if (name.isBlank() || !names.add(name)) invalid("$path.name", "Empty or duplicate listener name")
            val type = row["type"] as? String ?: invalid("$path.type", "Expected a protocol")
            if (type !in setOf("mixed", "http", "socks5", "tproxy", "shadowsocks", "ss")) invalid("$path.type", "Unknown Meow listener protocol")
            if (row["port"] !is Int || (row["port"] as Int) !in 1..65535) invalid("$path.port", "Explicit non-ephemeral port required by host listener ownership")
            if (row.containsKey("listen") && row["listen"] !is String) invalid("$path.listen", "Expected a bind address")
            if (type == "tproxy" && row["firewall"] != false) invalid("$path.firewall", "Android owns routing; native firewall must be false")
            if (type in setOf("ss", "shadowsocks") && listOf("cipher", "password").any { (row[it] as? String).isNullOrEmpty() })
                invalid(path, "Shadowsocks listener requires cipher and password")
        }
        (root["geodata"] as? Map<*, *>)?.keys?.forEach {
            if (it in setOf("geodata-mode", "geodata-loader", "geoip-matcher")) invalid("$.geodata.$it", "Meow warns and ignores this field")
        }
        @Suppress("UNCHECKED_CAST")
        (root["geodata"] as? MutableMap<String, Any?>)?.let { geo ->
            mapOf("mmdb-path" to "mmdb", "asn-path" to "asn-mmdb", "geosite-path" to "geosite").forEach { (key, old) ->
                if (geo.containsKey(key)) {
                    val path = geo.remove(key) as? String ?: invalid("$.geodata.$key", "Expected a resource path")
                    if (root.containsKey(old)) invalid("$.geodata.$key", "Duplicate resource path")
                    root[old] = path
                }
            }
        }
        return root to raw
    }

    private fun finish(composed: String, named: List<Any?>, fd: Int, mode: RuntimeMode, actual: TunRuntimeOptions,
        parked: Boolean, profile: String?, revision: String?, generation: String?): MeowRuntimeConfig {
        val root = ConfigYaml.parse(composed)
        val endpoint = (root.remove("external-controller") as String).substringAfterLast(':').toInt()
        val control = ControlEndpoint(endpoint, root.remove("secret") as String)
        val user = linkedMapOf<String, Any?>()
        listOf("mixed-port", "port", "socks-port", "redir-port", "tproxy-port", "allow-lan", "bind-address", "authentication").forEach {
            if (root.containsKey(it)) user[it] = root.remove(it)
        }
        if (named.isNotEmpty()) user["listeners"] = named
        named.forEachIndexed { i, raw ->
            if ((raw as Map<*, *>)["port"] == endpoint) invalid("$.listeners[$i].port", "Conflicts with private control port")
        }
        val host = linkedMapOf<String, Any?>()
        listOf("aerove-system-dns", "global-ua", "check-certificate", "find-process-mode", "unified-delay", "tcp-concurrent", "cors-allow-origins").forEach { host[it] = root.remove(it) }
        @Suppress("UNCHECKED_CAST")
        val dns = root["dns"] as? MutableMap<String, Any?>
        if (dns?.containsKey("listen") == true) user["dns-listen"] = dns.remove("listen")
        val tun = if (mode == RuntimeMode.Proxy) null else MeowTunPlan(if (parked) null else fd,
            ConfigYaml.dump(linkedMapOf<String, Any?>("enable" to true, "auto-route" to false,
                "inet4-address" to "198.18.0.1/30", "mtu" to actual.mtu,
                "dns-hijack" to if (dns?.get("enable") == true && actual.dnsHijacking) listOf("any:53") else emptyList<String>())
                .apply { if (actual.ipv6) put("inet6-address", "fdfe:dcba:9876::1/126") }))
        root["tun"] = linkedMapOf("enable" to false, "auto-route" to false)
        @Suppress("UNCHECKED_CAST")
        val geo = (root["geodata"] as? MutableMap<String, Any?>) ?: linkedMapOf()
        mapOf("mmdb" to "mmdb-path", "asn-mmdb" to "asn-path", "geosite" to "geosite-path").forEach { (old, new) ->
            root.remove(old)?.let { geo[new] = it }
        }
        if (geo.isNotEmpty()) { geo["auto-update"] = false; root["geodata"] = geo }
        // Both user inbounds and TUN stay disabled in parser YAML, including parked startup.
        // The bridge must consume these host plans explicitly; retaining them is not activation evidence.
        return MeowRuntimeConfig(ConfigYaml.dump(root), control, tun, ConfigYaml.dump(user), ConfigYaml.dump(host),
            parked, profile, revision, generation)
    }
}
