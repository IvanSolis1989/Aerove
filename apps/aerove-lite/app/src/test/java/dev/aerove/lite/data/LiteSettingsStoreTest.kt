package dev.aerove.lite.data

import dev.aerove.lite.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteSettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun allProductSettingsRoundTripThroughDisk() = runBlocking<Unit> {
        val settings = LiteSettings(
            material = MaterialTier.Clear,
            aurora = true,
            colorMode = ColorMode.Dark,
            themeColor = 0xff124578,
            mode = ProxyMode.Global,
            ipv6 = true,
            overrideDns = true,
            dnsServers = listOf("https://1.1.1.1/dns-query", "tls://8.8.8.8:853"),
            appFilterMode = AppFilterMode.Exclude,
            packages = setOf("com.example.one", "com.example.two"),
            autoStart = true,
            openLogs = true,
        )
        LiteSettingsStore(temporary.root).save(settings, vpnAuthorized = true)
        assertEquals(settings, LiteSettingsStore(temporary.root).read())
    }

    @Test fun missingFileUsesDefaults() = runBlocking<Unit> {
        assertEquals(LiteSettings(), LiteSettingsStore(temporary.root).read())
    }

    @Test fun oldSettingsWithoutOverrideFlagFollowProfileDns() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(overrideDns = true))
        val file = File(temporary.root, "settings/settings.json")
        val json = org.json.JSONObject(file.readText()).apply { remove("overrideDns") }
        file.writeText(json.toString())
        assertFalse(store.read().overrideDns)
    }

    @Test fun bootAutostartRequiresVpnAuthorizationAndFailureDoesNotWrite() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(LiteSettings(autoStart = true)) }
        }
        assertFalse(store.read().autoStart)
    }

    @Test fun oldSettingsWithoutLogFlagKeepLoggingDisabled() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(openLogs = true))
        val file = File(temporary.root, "settings/settings.json")
        val json = org.json.JSONObject(file.readText()).apply { remove("openLogs") }
        file.writeText(json.toString())
        assertFalse(store.read().openLogs)
    }

    @Test fun invalidDnsAndEmptyAllowListPreservePriorSettings() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(material = MaterialTier.Frosted))
        listOf(
            LiteSettings(dnsServers = listOf("system")),
            LiteSettings(dnsServers = listOf("dhcp://wlan0")),
            LiteSettings(dnsServers = emptyList()),
            LiteSettings(appFilterMode = AppFilterMode.Include),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(invalid) } }
        }
        assertEquals(MaterialTier.Frosted, store.read().material)
    }

    @Test fun corruptSettingsAreReportedInsteadOfSilentlyReset() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        val file = File(temporary.root, "settings/settings.json")
        file.writeText("{broken")
        assertThrows(org.json.JSONException::class.java) { runBlocking { store.read() } }
        assertEquals("{broken", file.readText())
    }
}
