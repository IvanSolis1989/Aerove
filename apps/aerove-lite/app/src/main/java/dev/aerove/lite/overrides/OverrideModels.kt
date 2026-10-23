package dev.aerove.lite.overrides

import dev.aerove.lite.dnssettings.DnsPreferences

enum class OverrideMode { Standard, Script }

data class ScriptResource(
    val id: String,
    val name: String,
    val url: String? = null,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val byteSize: Int get() = content.toByteArray(Charsets.UTF_8).size
}

data class OverrideRule(val id: String, val value: String)

data class ProfileOverride(
    val profileId: String,
    val mode: OverrideMode = OverrideMode.Standard,
    val scriptId: String? = null,
    val addedRules: List<String> = emptyList(),
    val disabledGlobalRuleIds: List<String> = emptyList(),
    val matchTarget: String? = null,
    val dnsPreferences: DnsPreferences? = null,
)

data class OverrideSnapshot(
    val scripts: List<ScriptResource> = emptyList(),
    val profiles: List<ProfileOverride> = emptyList(),
    val globalRules: List<OverrideRule> = emptyList(),
)

class ScriptExecutionException(val code: String) : java.io.IOException("Script: $code")
