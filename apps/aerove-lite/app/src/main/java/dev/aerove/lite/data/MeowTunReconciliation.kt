package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.networksettings.validateRouteCidr
import dev.aerove.lite.networksettings.validTunAddress
import java.util.Collections

/** A per-preparation value, not start authority or a platform owner. */
class MeowTunReconciliation private constructor(private val sourceTun: Map<String, Any?>, val settings: LiteSettings) {
    internal fun verify(source: String) {
        val policy = sourcePolicy(source)
        if (policy != sourceTun) fail("$.tun", "Source TUN changed after Builder reconciliation")
        val expected = reconcile(source, settings).settings
        if (expected != settings) fail("$.tun", "Source TUN no longer matches the typed Builder settings")
    }

    companion object {
        fun reconcile(source: String, base: LiteSettings): MeowTunReconciliation {
            val root = ConfigYaml.parse(source)
            val policy = sourcePolicy(source)
            val network = base.network
            val auto = policy["auto-route"]
            val explicitRoutes = policy["route-address"] as? List<*>
            val fakeRoutes = if (auto == "fake-ip") {
                val dns = root["dns"] as? Map<*, *>
                if (dns?.get("enable") != true || dns["enhanced-mode"] != "fake-ip") fail("$.tun.auto-route", "fake-ip routing requires the actual enabled fake-ip DNS configuration")
                listOf(dns["fake-ip-range"] as? String ?: "198.18.0.1/16").filter { base.ipv6 || ':' !in it }.also {
                    if (it.isEmpty()) fail("$.tun.auto-route", "fake-ip routing has no actual address range")
                }
            } else null
            val routes = explicitRoutes?.map { it as String } ?: fakeRoutes ?: if (auto == false) emptyList() else network.routeAddress
            val autoDefault = when { explicitRoutes != null || fakeRoutes != null || auto == false -> false; auto == true || auto == "global" -> true; else -> network.routeAuto }
            val hijack = (policy["dns-hijack"] as? List<*>)?.map { it as String }
            val dnsEnabled = (root["dns"] as? Map<*, *>)?.get("enable") == true
            if (base.network.vpnEnabled && hijack?.isNotEmpty() == true && !dnsEnabled) fail("$.tun.dns-hijack", "Source DNS interception requires the actual DNS resolver to be enabled")
            if (base.network.vpnEnabled && !base.ipv6 && policy.containsKey("inet6-address")) fail("$.tun.inet6-address", "Explicit IPv6 TUN address conflicts with disabled Android IPv6")
            val next = base.copy(network = network.copy(
                mtu = (policy["mtu"] as? Number)?.toInt() ?: network.mtu,
                routeAuto = autoDefault, routeDefaultsPerFamily = if (explicitRoutes != null || fakeRoutes != null || auto == false) false else network.routeDefaultsPerFamily, routeAddress = routes,
                routeExcludeAddress = network.routeExcludeAddress + ((policy["route-exclude-address"] as? List<*>)?.map { it as String } ?: emptyList()).filterNot { it in network.routeExcludeAddress },
                tunInet4Address = policy["inet4-address"] as? String ?: network.tunInet4Address,
                tunInet6Address = policy["inet6-address"] as? String ?: network.tunInet6Address,
                tunDnsHijack = hijack ?: network.tunDnsHijack,
                dnsHijacking = hijack?.isNotEmpty() ?: network.dnsHijacking,
            ))
            validateSettings(next)
            return MeowTunReconciliation(policy, next)
        }

        internal fun requireActual(source: String, actual: LiteSettings, options: TunRuntimeOptions, actualReservedRanges: List<String>? = null) {
            val proof = reconcile(source, actual)
            val builder = actual.copy(network = options.builderNetwork ?: actual.network.copy(mtu=options.mtu, dnsHijacking=options.dnsHijacking,
                tunInet4Address=options.inet4Address, tunInet6Address=options.inet6Address, tunDnsHijack=options.dnsHijackTargets), ipv6=options.ipv6)
            val reserved = reservedRanges(source, builder.ipv6)
            if (dev.aerove.lite.networksettings.effectiveVpnNetworkOptions(proof.settings.network, builder.ipv6, reserved) !=
                dev.aerove.lite.networksettings.effectiveVpnNetworkOptions(builder.network, builder.ipv6, actualReservedRanges ?: reserved)) fail("$.tun", "Source TUN policy differs from the actual Android Builder plan")
            val network = builder.network
            if (options.mtu != network.mtu || options.ipv6 != builder.ipv6 || options.dnsHijacking != network.dnsHijacking ||
                options.inet4Address != network.tunInet4Address || options.inet6Address != network.tunInet6Address || options.dnsHijackTargets != network.tunDnsHijack)
                fail("$.tun", "Core TUN plan differs from the actual Android Builder plan")
        }

        internal fun reservedRanges(source: String, ipv6: Boolean): List<String> {
            val dns = ConfigYaml.parse(source)["dns"] as? Map<*, *> ?: return emptyList()
            if (dns["enable"] != true) return emptyList()
            val fake = if (dns["enhanced-mode"] == "fake-ip") listOf(dns["fake-ip-range"] as? String ?: "198.18.0.1/16").filter { ipv6 || ':' !in it } else emptyList()
            return (fake + if (ipv6) listOf("fdfe:dcba:9876::2/128") else emptyList()).distinct()
        }

        internal fun sourcePolicy(source: String): Map<String, Any?> {
            val raw = ConfigYaml.parse(source)["tun"] ?: return emptyMap()
            if (raw !is Map<*, *>) fail("$.tun", "Expected an object")
            val allowed = setOf("enable", "device", "mtu", "inet4-address", "inet6-address", "auto-route", "dns-hijack", "route-address", "route-exclude-address", "stack", "congestion-controller")
            raw.forEach { (key, value) ->
                if (key !in allowed) fail("$.tun.$key", "Not consumed by the Android typed TUN plan")
                val valid = when(key) {
                    "enable" -> value is Boolean
                    "device" -> value == ""
                    "stack" -> value == "lwip"
                    "congestion-controller" -> value == "builtin"
                    "mtu" -> value is Number && value.toLong() in 1280..65535 && value.toDouble() == value.toLong().toDouble()
                    "auto-route" -> value is Boolean || value in setOf("global", "fake-ip")
                    "inet4-address" -> value is String && validTunAddress(value, false)
                    "inet6-address" -> value is String && validTunAddress(value, true)
                    "dns-hijack" -> value is List<*> && value.size <= 16 && value.all { it is String && validHijack(it) }
                    "route-address", "route-exclude-address" -> value is List<*> && value.all { it is String && validateRouteCidr(it) }
                    else -> false
                }
                if (!valid) fail("$.tun.$key", when(key) {"device" -> "Android owns the TUN descriptor; an explicit device cannot be reconciled"; "stack" -> "The fixed Meow core uses lwIP"; "congestion-controller" -> "The fixed lwIP core uses builtin congestion control"; else -> "Invalid typed TUN value"})
            }
            return Collections.unmodifiableMap(raw.entries.associate { (key, value) -> key as String to if (value is List<*>) Collections.unmodifiableList(value.toList()) else value })
        }

        private fun validHijack(value: String): Boolean {
            if (value == "any:53") return true
            val host = if (value.startsWith('[') && value.endsWith("]:53")) value.substring(1,value.length-4) else if (value.count { it == ':' } == 1 && value.endsWith(":53")) value.dropLast(3) else return false
            return validTunAddress("$host/${if (':' in host) 128 else 32}", ':' in host)
        }
        private fun fail(field: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(field, reason)))
    }
}
