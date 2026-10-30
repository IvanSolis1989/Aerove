package dev.aerove.lite.flutter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowServiceSetupTest {
    private fun setup(url: String, skip: Any = false) = JSONObject().put("test-url", url)
        .put("skip-cert-verify", skip).put("selected-map", JSONObject().put("Pick", "Node")).toString()

    @Test fun emptyTestUrlRetainsServiceDefaultAndExplicitUrlReplacesIt() {
        assertEquals("https://old.test/check", MeowServiceSetup(setup(""), "https://old.test/check").testUrl)
        assertEquals("https://new.test/check", MeowServiceSetup(setup("https://new.test/check"), "https://old.test/check").testUrl)
        assertFalse(MeowServiceSetup(setup(""), MEOW_DEFAULT_TEST_URL).skipCertVerify)
        assertTrue(MeowServiceSetup(setup("", true), MEOW_DEFAULT_TEST_URL).skipCertVerify)
    }

    @Test fun groupDefaultProjectionPreservesExplicitUrlsAndOriginalCompiledSource() {
        val groups = listOf(mapOf("name" to "A", "type" to "url-test"),
            mapOf("name" to "B", "type" to "fallback", "url" to ""),
            mapOf("name" to "C", "type" to "load-balance", "url" to "https://explicit.test/check"),
            mapOf("name" to "D", "type" to "select"))
        val original = mapOf("proxy-groups" to groups)
        assertTrue(meowMissingGroupTestUrl(original))
        val projected = meowProjectGroupTestUrl(original, "https://default.test/check")
        val rows = projected.getValue("proxy-groups") as List<*>
        assertEquals("https://default.test/check", (rows[0] as Map<*, *>)["url"])
        assertEquals("https://default.test/check", (rows[1] as Map<*, *>)["url"])
        assertEquals("https://explicit.test/check", (rows[2] as Map<*, *>)["url"])
        assertFalse((rows[3] as Map<*, *>).containsKey("url"))
        assertFalse(groups[0].containsKey("url")); assertEquals("", groups[1]["url"])
        assertFalse(meowMissingGroupTestUrl(projected))
    }

    @Test fun malformedSetupCannotBeReportedAsAppliedPolicy() {
        assertThrows(Exception::class.java) { MeowServiceSetup(setup("ftp://invalid.test/"), MEOW_DEFAULT_TEST_URL) }
        assertThrows(Exception::class.java) { MeowServiceSetup(setup("", "false"), MEOW_DEFAULT_TEST_URL) }
        assertThrows(Exception::class.java) { MeowServiceSetup(JSONObject(setup("")).put("selected-map", JSONObject().put("Pick", true)).toString(), MEOW_DEFAULT_TEST_URL) }
    }

    @Test fun vpnIpv6ProjectionDoesNotOverwriteCoreIpv6MtuOrDns() {
        val core = dev.aerove.lite.LiteSettings(ipv6 = false,
            network = dev.aerove.lite.networksettings.NetworkSettings(mtu = 1500, dnsHijacking = true))
        val vpn = meowFlutterBuilderSettings(core, JSONObject().put("enable", true).put("ipv6", true).toString())
        assertFalse(core.ipv6); assertTrue(vpn.ipv6)
        assertEquals(core.network, vpn.network)
        assertEquals(1500, vpn.network.mtu); assertTrue(vpn.network.dnsHijacking)
        val reverse = meowFlutterBuilderSettings(core.copy(ipv6 = true), JSONObject().put("enable", true).put("ipv6", false).toString())
        assertFalse(reverse.ipv6)
        val directory = java.nio.file.Files.createTempDirectory("meow-dual-ipv6").toFile()
        try {
            val composer = dev.aerove.lite.data.MeowRuntimeConfigComposer(directory)
            val config = composer.compose("dns: {enable: true}", core, 42, 49152,
                "0123456789abcdef0123456789abcdef", tunOptions = dev.aerove.lite.data.TunRuntimeOptions(vpn.network.mtu, vpn.network.dnsHijacking, vpn.ipv6))
            assertEquals(false, dev.aerove.lite.data.ConfigYaml.parse(config.engineYaml)["ipv6"])
            assertTrue(config.tun!!.ipv6)
            assertEquals(1500, config.tun!!.mtu)
        } finally { directory.deleteRecursively() }
    }

    @Test fun telemetryIdentityIgnoresOnlySetupMetadataAndRetiresOnEveryRuntimeProofField() {
        val a = RuntimeAppliedIdentity("revision", 2, "hash", RuntimeAppliedProof(3, 4, "5"), "old-record")
        assertTrue(meowSameRuntimeIdentity(a, a.copy(expectedSetupRecordJson = "new-record")))
        listOf(a.copy(revision = "new"), a.copy(intentEpoch = 3), a.copy(compiledSha256 = "other"),
            a.copy(proof = a.proof.copy(serviceOwner = 4)), a.copy(proof = a.proof.copy(sessionGeneration = 5)),
            a.copy(proof = a.proof.copy(coreEpoch = "6"))).forEach { assertFalse(meowSameRuntimeIdentity(a, it)) }
    }
}
