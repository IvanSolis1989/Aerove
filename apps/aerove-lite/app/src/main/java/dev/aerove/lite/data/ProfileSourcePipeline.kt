package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.dnssettings.DnsPreferences
import dev.aerove.lite.dnssettings.transformDnsPreferences
import dev.aerove.lite.overrides.OverridePipeline
import dev.aerove.lite.overrides.OverrideTransform
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.resources.GeoResourceKind
import org.json.JSONArray

/** Explicit profile metadata determines custom DNS inheritance; a missing subscription URL does not. */
data class ProfileDnsSelection(val preferences: DnsPreferences? = null, val customProfile: Boolean = false)

/** Reads real stores; source revisions remain untouched by runtime-only transforms. */
class ProfileSourcePipeline(
    private val builders: BuilderResourceLibrary,
    private val overrides: OverridePipeline,
    private val readSettings: suspend () -> LiteSettings,
    private val profileDns: suspend (String) -> ProfileDnsSelection = { ProfileDnsSelection() },
) : ProfileSourcePolicy {
    override suspend fun forData(profileId: String, logicalOriginalSource: String): ProfileSourceTransform {
        val source = builders.snapshot().resources.resolve(logicalOriginalSource)
        val settings = readSettings()
        val urls = geoUrls(settings)
        return ProfileSourceTransform(source, sha256(JSONArray(listOf("data-v1", sha256(source), urls)).toString()),
            isCurrent = { builders.snapshot().resources.resolve(logicalOriginalSource) == source && geoUrls(readSettings()) == urls }, geoUrls = urls)
    }

    override suspend fun forStart(profileId: String, logicalOriginalSource: String): ProfileSourceTransform {
        val bound = builders.snapshot().resources.resolve(logicalOriginalSource)
        val selected = profileDns(profileId)
        val extended = if (selected.customProfile) OverrideTransform(bound, overrides.fingerprint(profileId, bound))
            else overrides.resolve(profileId, bound)
        val settings = readSettings()
        val source = transformDnsPreferences(ConfigYaml.parse(extended.yaml), settings.dnsPreferences,
            selected.preferences, selected.customProfile)
        val compiled = ConfigYaml.dump(source)
        val urls = geoUrls(settings)
        val fingerprint = sha256(JSONArray(listOf("runtime-v1", extended.fingerprint, sha256(compiled), urls)).toString())
        return ProfileSourceTransform(compiled, fingerprint, isCurrent = {
            val current = readSettings()
            val selection = profileDns(profileId)
            selection.customProfile == selected.customProfile &&
                builders.snapshot().resources.resolve(logicalOriginalSource) == bound &&
                overrides.fingerprint(profileId, bound) == extended.fingerprint && geoUrls(current) == urls &&
                ConfigYaml.dump(transformDnsPreferences(ConfigYaml.parse(extended.yaml), current.dnsPreferences,
                    selection.preferences, selection.customProfile)) == compiled
        }, geoUrls = urls)
    }

    private fun geoUrls(settings: LiteSettings): Map<String, String> = settings.geoResources.validate().let { value ->
        GeoResourceKind.entries.associate { it.sourceKey to value.urls.getValue(it) }
    }
}
