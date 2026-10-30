package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.NativeCore.RuntimeMode
import java.io.File
import org.json.JSONObject

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
    val runtimeMode: RuntimeMode,
    val physicalNetwork: MeowPhysicalNetwork,
    val resourceReadRoot: File?,
    val cacheRoot: File?,
) {
    internal fun withResources(yaml: String, readRoot: File, revision: String?, generation: String?): MeowRuntimeConfig =
        MeowRuntimeConfig(yaml, privateControl, tun, userInboundYaml, hostPolicyYaml, initiallyParked,
            profileId, revision, generation, runtimeMode, physicalNetwork, readRoot, cacheRoot)

    fun planJson(): String = JSONObject().put("schemaVersion", 1).put("runtimeMode", runtimeMode.wireValue)
        .put("initiallyParked", initiallyParked).put("engineYaml", engineYaml)
        .put("privateControl", JSONObject().put("address", "127.0.0.1:${privateControl.port}").put("secret", privateControl.secret))
        .put("inbounds", JSONObject().put("configYaml", userInboundYaml))
        .put("tun", tun?.json() ?: JSONObject.NULL)
        .put("physicalNetwork", physicalNetwork.json()).put("hostPolicyYaml", hostPolicyYaml)
        .put("profileId", profileId ?: JSONObject.NULL).put("revision", revision ?: JSONObject.NULL)
        .put("resourceGeneration", resourceGeneration ?: JSONObject.NULL)
        .put("resourceReadRoot", resourceReadRoot?.path ?: JSONObject.NULL)
        .put("cacheRoot", checkNotNull(cacheRoot) { "A trusted private session cacheRoot is required" }.path).toString()

    override fun toString() = "MeowRuntimeConfig(parked=$initiallyParked, tun=${tun != null}, credentials=<redacted>)"
}

class MeowTunPlan internal constructor(val descriptor: Int?, val rawYaml: String, val mtu: Int,
    val ipv6: Boolean, val dnsHijack: List<String>) {
    internal fun json(): JSONObject = JSONObject().put("mtu", mtu).put("ipv6", ipv6)
        .put("inet4Address", "198.18.0.1/30")
        .put("inet6Address", if (ipv6) "fdfe:dcba:9876::1/126" else JSONObject.NULL)
        .put("dnsHijack", org.json.JSONArray(dnsHijack)).put("autoRoute", false)
    override fun toString() = "MeowTunPlan(descriptorPresent=${descriptor != null})"
}

class MeowPhysicalNetwork(val handle: String? = null, val dns: SystemDnsSnapshot = SystemDnsSnapshot()) {
    init { require(handle == null || (handle.toULongOrNull()?.let { it > 0uL && it.toString() == handle } == true)) { "Invalid physical network handle" }; dns.config() }
    internal fun json() = JSONObject().put("handle", handle ?: JSONObject.NULL).put("dns", JSONObject(dns.config()))
}

/** Reuses the existing safe resource/control composition; only the engine dialect is adapted here. */
class MeowRuntimeConfigComposer(privateDirectory: File, private val defaultUserAgent: String = "AEROVE-Lite", cacheDirectory: File? = null) {
    private val shared = RuntimeConfigComposer(privateDirectory, defaultUserAgent)
    private val cacheRoot = cacheDirectory?.canonicalFile?.also { cache ->
        val owner = File(privateDirectory.canonicalFile, "meow-cache").canonicalFile
        val privateRoot = privateDirectory.canonicalFile.toPath()
        require(owner.toPath().startsWith(privateRoot) && cache.isDirectory && cache.toPath().startsWith(owner.toPath()) && cache != owner) { "Cache root must be an existing private session directory" }
    }

    fun compose(prepared: PreparedProfile, settings: LiteSettings, tunFd: Int, port: Int, secret: String,
        runtimeMode: RuntimeMode = if (settings.network.vpnEnabled) RuntimeMode.Tun else RuntimeMode.Proxy,
        systemDns: SystemDnsSnapshot = SystemDnsSnapshot(),
        tunOptions: TunRuntimeOptions = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6),
        initiallyParked: Boolean = false, physicalNetworkHandle: String? = null,
        verifiedNativeFeatures: Set<String> = emptySet()): MeowRuntimeConfig {
        val input = input(prepared.yaml)
        val composed = shared.compose(prepared.copy(yaml = ConfigYaml.dump(input.first)), settings, tunFd, port,
            secret, runtimeMode, systemDns, tunOptions, initiallyParked)
        return finish(composed, input.second, tunFd, runtimeMode, tunOptions, initiallyParked,
            prepared.profileId, prepared.revision, prepared.generation, MeowPhysicalNetwork(physicalNetworkHandle, systemDns), verifiedNativeFeatures.toSet(), prepared.resourceDirectory?.canonicalFile, settings)
    }

    fun compose(source: String, settings: LiteSettings, tunFd: Int, port: Int, secret: String,
        runtimeMode: RuntimeMode = if (settings.network.vpnEnabled) RuntimeMode.Tun else RuntimeMode.Proxy,
        systemDns: SystemDnsSnapshot = SystemDnsSnapshot(),
        tunOptions: TunRuntimeOptions = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6),
        initiallyParked: Boolean = false, physicalNetworkHandle: String? = null,
        verifiedNativeFeatures: Set<String> = emptySet()): MeowRuntimeConfig {
        val input = input(source)
        return finish(shared.compose(ConfigYaml.dump(input.first), settings, tunFd, port, secret,
            runtimeMode, systemDns, tunOptions, initiallyParked), input.second, tunFd, runtimeMode,
            tunOptions, initiallyParked, null, null, null, MeowPhysicalNetwork(physicalNetworkHandle, systemDns), verifiedNativeFeatures.toSet(), null, settings)
    }

    fun composeFlutter(prepared: PreparedProfile, lease: FlutterRuntimeResources, settings: LiteSettings, tunFd: Int,
        port: Int, secret: String, runtimeMode: RuntimeMode, systemDns: SystemDnsSnapshot,
        tunOptions: TunRuntimeOptions, initiallyParked: Boolean, physicalNetworkHandle: String?,
        verifiedNativeFeatures: Set<String>): MeowRuntimeConfig {
        val input = input(prepared.yaml)
        val composed = shared.composeFlutter(prepared, lease, settings, tunFd,
            port, secret, runtimeMode, systemDns, tunOptions, initiallyParked, ConfigYaml.dump(input.first))
        return finish(composed, input.second, tunFd, runtimeMode, tunOptions, initiallyParked,
            prepared.profileId, prepared.revision, prepared.generation, MeowPhysicalNetwork(physicalNetworkHandle, systemDns),
            verifiedNativeFeatures.toSet(), lease.verifyReadLease(prepared), settings)
    }

    private fun invalid(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))

    private fun input(source: String): Pair<MutableMap<String, Any?>, List<Any?>> = MeowSourceConfig.input(source)

    private fun finish(composed: String, named: List<Any?>, fd: Int, mode: RuntimeMode, actual: TunRuntimeOptions,
        parked: Boolean, profile: String?, revision: String?, generation: String?, physicalNetwork: MeowPhysicalNetwork,
        verifiedNativeFeatures: Set<String>, resourceReadRoot: File?, settings: LiteSettings): MeowRuntimeConfig {
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
        val policies = dev.aerove.lite.core.MeowHostPolicyTarget.from(settings, defaultUserAgent)
        require(host["cors-allow-origins"] == emptyList<String>()) { "Private controller CORS must be disabled" }
        host["only-statistics-proxy"] = policies.onlyProxy
        host["user-controller"] = linkedMapOf("enabled" to policies.controller.enabled, "secret" to policies.controller.secret)
        @Suppress("UNCHECKED_CAST")
        val dns = root["dns"] as? MutableMap<String, Any?>
        if (dns?.containsKey("listen") == true) user["dns-listen"] = dns.remove("listen")
        val tun = if (mode == RuntimeMode.Proxy) null else MeowTunPlan(if (parked) null else fd,
            ConfigYaml.dump(linkedMapOf<String, Any?>("enable" to true, "auto-route" to false,
                "inet4-address" to "198.18.0.1/30", "mtu" to actual.mtu,
                "dns-hijack" to if (dns?.get("enable") == true && actual.dnsHijacking) listOf("any:53") else emptyList<String>())
                .apply { if (actual.ipv6) put("inet6-address", "fdfe:dcba:9876::1/126") }), actual.mtu, actual.ipv6,
            if (dns?.get("enable") == true && actual.dnsHijacking) listOf("any:53") else emptyList())
        root["tun"] = linkedMapOf("enable" to false, "auto-route" to false)
        @Suppress("UNCHECKED_CAST")
        val geo = (root["geodata"] as? MutableMap<String, Any?>) ?: linkedMapOf()
        mapOf("mmdb" to "mmdb-path", "asn-mmdb" to "asn-path", "geosite" to "geosite-path").forEach { (old, new) ->
            root.remove(old)?.let { geo[new] = it }
        }
        if (geo.isNotEmpty()) { geo["auto-update"] = false; root["geodata"] = geo }
        // Both user inbounds and TUN stay disabled in parser YAML, including parked startup.
        // The bridge must consume these host plans explicitly; retaining them is not activation evidence.
        val engine = MeowCompatibilityValidator.normalize(root, verifiedNativeFeatures)
        return MeowRuntimeConfig(ConfigYaml.dump(engine), control, tun, ConfigYaml.dump(user), ConfigYaml.dump(host),
            parked, profile, revision, generation, mode, physicalNetwork, resourceReadRoot, cacheRoot)
    }
}
