package dev.aerove.lite.overrides

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.ConfigIssue
import dev.aerove.lite.data.ConfigValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

data class OverrideTransform(val yaml: String, val fingerprint: String)

fun interface ScriptEngine {
    suspend fun evaluate(script: String, configJson: String): String
}

class OverridePipeline(private val repository: OverrideRepository, private val engine: ScriptEngine) {
    suspend fun transform(profileId: String, sourceYaml: String): String = resolve(profileId, sourceYaml).yaml

    @Suppress("UNUSED_PARAMETER")
    suspend fun fingerprint(profileId: String, sourceYaml: String): String = withContext(Dispatchers.Default) {
        overridePolicyFingerprint(repository.snapshot(), profileId)
    }

    suspend fun resolve(profileId: String, sourceYaml: String): OverrideTransform = withContext(Dispatchers.Default) {
        val snapshot = repository.snapshot()
        val profile = snapshot.profiles.find { it.profileId == profileId } ?: ProfileOverride(profileId)
        val fingerprint = overridePolicyFingerprint(snapshot, profileId)
        fun result(yaml: String) = OverrideTransform(yaml, fingerprint)
        val source = ConfigYaml.parse(sourceYaml)
        if (profile.mode == OverrideMode.Script) {
            val script = profile.scriptId?.let { id -> snapshot.scripts.find { it.id == id } }
            if (script == null || script.content.isEmpty()) return@withContext result(sourceYaml)
            val output = engine.evaluate(script.content, JSONObject(source).toString())
            currentCoroutineContext().ensureActive()
            return@withContext result(ConfigYaml.dump(ConfigYaml.parse(output)))
        }
        val rules = (source["rules"] as? List<*>)?.mapIndexed { index, value -> value as? String
            ?: throw ConfigValidationException(listOf(ConfigIssue("$.rules[$index]", "Expected a rule string"))) } ?: emptyList()
        // Standard rules DAO places the profile's ordered rows before enabled global rows.
        val added = profile.addedRules + snapshot.globalRules.filterNot { it.id in profile.disabledGlobalRuleIds }.map { it.value }
        if (added.isEmpty()) return@withContext result(sourceYaml)
        val target = profile.matchTarget?.trim()?.takeIf { it.isNotEmpty() } ?: rules.asReversed()
            .firstNotNullOfOrNull { line -> line.split(',').takeIf { it.first().trim().uppercase() in setOf("MATCH", "FINAL") }?.getOrNull(1)?.trim() }
        source["rules"] = added.map { rule ->
            val action = rule.substringBefore(',').trim().uppercase()
            val start = if (action in OverrideRuleDraft.parameterActions)
                rule.indexOf(',', rule.indexOf(',') + 1) + 1 else rule.lastIndexOf(',') + 1
            val end = if (action in OverrideRuleDraft.parameterActions)
                rule.indexOf(',', start).takeIf { it >= 0 } ?: rule.length else rule.length
            require(start > 0 && start < end) { "Invalid rule target" }
            if (target != null && rule.substring(start, end).trim().equals("MATCH", true))
                rule.substring(0, start) + target + rule.substring(end) else rule
        } + rules
        currentCoroutineContext().ensureActive()
        result(ConfigYaml.dump(source))
    }

}

/** Shared pure policy identity for workspace invalidation; never reads stores or executes scripts. */
fun overridePolicyFingerprint(snapshot: OverrideSnapshot, profileId: String): String {
        val profile = snapshot.profiles.find { it.profileId == profileId } ?: ProfileOverride(profileId)
        val relevant = if (profile.mode == OverrideMode.Script) OverrideSnapshot(
            scripts = snapshot.scripts.filter { it.id == profile.scriptId }.map { it.copy(name = "script", url = null, updatedAt = 0) },
            profiles = listOf(profile.copy(addedRules = emptyList(), disabledGlobalRuleIds = emptyList(), matchTarget = null)),
        ) else OverrideSnapshot(profiles = listOf(profile.copy(scriptId = null)),
            globalRules = snapshot.globalRules.filterNot { it.id in profile.disabledGlobalRuleIds })
        return MessageDigest.getInstance("SHA-256").digest(OverrideRepository.encode(relevant).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
