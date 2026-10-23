package dev.aerove.lite.overrides

import dev.aerove.lite.dnssettings.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OverrideDnsPersistenceTest {
    private fun dns(server: String = "1.1.1.1") = DnsPreferences()
        .withValue(DnsField.Nameserver, DnsValue.Entries(listOf(server)))
        .copy(hosts = mapOf("private.example" to listOf("192.168.1.2")))

    @Test fun optional_dns_round_trips_real_store_missing_and_null_stay_inherited() = runBlocking {
        val directory = Files.createTempDirectory("profile-dns").toFile()
        val repository = OverrideRepository(directory)
        val expected = ProfileOverride("custom", dnsPreferences = dns())
        repository.setProfile(expected)
        assertEquals(expected, OverrideRepository(directory).snapshot().profiles.single())
        val document = JSONObject(OverrideRepository.encode(repository.snapshot()))
        document.getJSONArray("profiles").getJSONObject(0).remove("dnsPreferences")
        assertNull(OverrideRepository.decode(document.toString()).profiles.single().dnsPreferences)
        document.getJSONArray("profiles").getJSONObject(0).put("dnsPreferences", JSONObject.NULL)
        assertNull(OverrideRepository.decode(document.toString()).profiles.single().dnsPreferences)
        repository.setProfile(expected.copy(dnsPreferences = null))
        assertNull(OverrideRepository(directory).snapshot().profiles.single().dnsPreferences)
    }

    @Test fun strict_dns_codec_rejects_bad_types_and_fields_and_invalid_write_preserves_saved_record() = runBlocking {
        val directory = Files.createTempDirectory("profile-dns-strict").toFile()
        val repository = OverrideRepository(directory)
        repository.setProfile(ProfileOverride("p", dnsPreferences = dns()))
        val saved = repository.snapshot()
        for (bad in listOf<Any>("not a DNS object", JSONObject().put("unknown", true),
            JSONObject().put("values", JSONObject().put("ipv6", "true")),
            JSONObject().put("overridden", org.json.JSONArray(listOf("ipv6", "ipv6"))))) {
            val document = JSONObject(OverrideRepository.encode(saved))
            document.getJSONArray("profiles").getJSONObject(0).put("dnsPreferences", bad)
            try { OverrideRepository.decode(document.toString()); fail("Invalid DNS must fail before use") }
            catch (_: DnsPreferencesException) { }
        }
        try {
            repository.setProfile(saved.profiles.single().copy(dnsPreferences = DnsPreferences(values = mapOf(DnsField.Ipv6 to DnsValue.Text("true")))))
            fail("Invalid typed DNS must not commit")
        } catch (_: DnsPreferencesException) { }
        assertEquals(saved, OverrideRepository(directory).snapshot())
    }

    @Test fun backup_remap_compatible_profiles_only_and_replace_keep_the_correct_profile_dns() {
        val localDns = dns("9.9.9.9")
        val incomingDns = dns("8.8.8.8")
        val live = OverrideSnapshot(profiles = listOf(ProfileOverride("live", dnsPreferences = localDns)))
        val backup = OverrideSnapshot(profiles = listOf(ProfileOverride("original", dnsPreferences = incomingDns)))
        val remapped = OverrideRepository.remapProfiles(backup, mapOf("original" to "restored"))
        assertEquals(incomingDns, remapped.profiles.single().dnsPreferences)
        for (profilesOnly in listOf(false, true)) {
            val merged = OverrideRepository.mergeRestore(live, backup, mapOf("original" to "restored"), false, profilesOnly, setOf("live", "restored"))
            assertEquals(localDns, merged.profiles.single { it.profileId == "live" }.dnsPreferences)
            assertEquals(incomingDns, merged.profiles.single { it.profileId == "restored" }.dnsPreferences)
            assertEquals(merged, OverrideRepository.decode(OverrideRepository.encode(merged)))
        }
        val replaced = OverrideRepository.mergeRestore(live, backup, mapOf("original" to "restored"), true, false, setOf("restored"))
        assertEquals(listOf("restored"), replaced.profiles.map { it.profileId })
        assertEquals(incomingDns, replaced.profiles.single().dnsPreferences)
    }

    @Test fun dns_changes_invalidate_both_mode_fingerprints_without_script_execution() = runBlocking {
        val repository = OverrideRepository(Files.createTempDirectory("profile-dns-policy").toFile())
        val pipeline = OverridePipeline(repository) { _, _ -> error("Fingerprint must not evaluate scripts") }
        for (mode in OverrideMode.entries) {
            repository.setProfile(ProfileOverride("p", mode = mode))
            val inherited = pipeline.fingerprint("p", "unchanged source")
            repository.setProfile(ProfileOverride("p", mode = mode, dnsPreferences = dns()))
            val explicit = pipeline.fingerprint("p", "unchanged source")
            assertNotEquals(inherited, explicit)
            repository.setProfile(ProfileOverride("p", mode = mode, dnsPreferences = dns("8.8.4.4")))
            assertNotEquals(explicit, pipeline.fingerprint("p", "unchanged source"))
        }
    }
}
