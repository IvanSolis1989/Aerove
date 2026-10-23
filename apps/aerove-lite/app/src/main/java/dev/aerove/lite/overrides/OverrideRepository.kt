package dev.aerove.lite.overrides

import dev.aerove.lite.data.PrivateStorage
import dev.aerove.lite.data.SubscriptionDownloader
import dev.aerove.lite.dnssettings.DnsPreferencesJson
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** One atomic settings record; profile bindings are remapped by the backup owner on restore. */
class OverrideRepository(privateDirectory: File) {
    private val storage = PrivateStorage(File(privateDirectory, "settings"))

    suspend fun snapshot(): OverrideSnapshot = storage.transaction { read() }

    suspend fun putScript(script: ScriptResource) = mutate { state ->
        validateScript(script)
        require(state.scripts.none { it.id != script.id && it.name == script.name }) { "Duplicate script name" }
        state.copy(scripts = replace(state.scripts, script) { it.id })
    }

    suspend fun replaceScriptIfCurrent(expected: ScriptResource, updated: ScriptResource): Boolean = storage.transaction {
        val state = read()
        if (state.scripts.find { it.id == expected.id } != expected) return@transaction false
        validateScript(updated)
        currentCoroutineContext().ensureActive()
        storage.write(FILE_NAME, encode(state.copy(scripts = replace(state.scripts, updated) { it.id })))
        true
    }

    suspend fun deleteScript(id: String) = mutate { state -> state.copy(
        scripts = state.scripts.filterNot { it.id == id },
        profiles = state.profiles.map { if (it.scriptId == id) it.copy(scriptId = null) else it },
    ) }

    suspend fun replaceContent(id: String, expected: String, replacement: String) = mutate { state ->
        val script = state.scripts.find { it.id == id } ?: throw ScriptExecutionException("missing_script")
        if (script.content != expected) throw ScriptExecutionException("edit_conflict")
        val updated = script.copy(content = replacement, updatedAt = System.currentTimeMillis())
        validateScript(updated)
        state.copy(scripts = replace(state.scripts, updated) { it.id })
    }

    suspend fun moveScript(id: String, destination: Int) = mutate { state ->
        val list = state.scripts.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        require(index >= 0 && destination in list.indices)
        list.add(destination, list.removeAt(index))
        state.copy(scripts = list)
    }

    suspend fun setProfile(value: ProfileOverride) = mutate { state ->
        require(value.scriptId == null || state.scripts.any { it.id == value.scriptId }) { "Missing script" }
        require(value.disabledGlobalRuleIds.all { id -> state.globalRules.any { it.id == id } }) { "Missing global rule" }
        state.copy(profiles = replace(state.profiles, value) { it.profileId })
    }

    suspend fun setGlobalRules(values: List<OverrideRule>) = mutate { state -> state.copy(
        globalRules = values,
        profiles = state.profiles.map { it.copy(disabledGlobalRuleIds = it.disabledGlobalRuleIds.filter { id -> values.any { it.id == id } }) },
    ) }

    private suspend fun mutate(change: (OverrideSnapshot) -> OverrideSnapshot) = storage.transaction {
        val next = change(read())
        val text = encode(next)
        decode(text)
        currentCoroutineContext().ensureActive()
        storage.write(FILE_NAME, text)
    }

    private fun read() = if (storage.file(FILE_NAME).isFile) decode(storage.read(FILE_NAME, MAX_LIBRARY_BYTES)) else OverrideSnapshot()

    companion object {
        const val FILE_NAME = "overrides.json"
        const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024
        const val MAX_LIBRARY_BYTES = 16 * 1024 * 1024

        private fun validateScript(script: ScriptResource) {
            require(script.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid script id" }
            require(script.name.isNotBlank() && script.name.length <= 200) { "Invalid script name" }
            require(script.byteSize <= MAX_SCRIPT_BYTES) { "Script exceeds size limit" }
            script.url?.let { require(SubscriptionDownloader.parseUrl(it).scheme in setOf("https", "http")) }
        }

        fun encode(state: OverrideSnapshot): String = JSONObject().put("version", 1)
            .put("scripts", JSONArray(state.scripts.map { JSONObject().put("id", it.id).put("name", it.name)
                .put("url", it.url ?: JSONObject.NULL).put("content", it.content).put("updatedAt", it.updatedAt) }))
            .put("profiles", JSONArray(state.profiles.map { JSONObject().put("profileId", it.profileId)
                .put("mode", it.mode.name).put("scriptId", it.scriptId ?: JSONObject.NULL)
                .put("addedRules", JSONArray(it.addedRules)).put("disabledGlobalRuleIds", JSONArray(it.disabledGlobalRuleIds))
                .put("matchTarget", it.matchTarget ?: JSONObject.NULL)
                .put("dnsPreferences", it.dnsPreferences?.let(DnsPreferencesJson::encode) ?: JSONObject.NULL) }))
            .put("globalRules", JSONArray(state.globalRules.map { JSONObject().put("id", it.id).put("value", it.value) })).toString()

        fun decode(text: String): OverrideSnapshot {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_LIBRARY_BYTES) { "Script library exceeds size limit" }
            val root = JSONObject(text)
            require(root.getInt("version") == 1) { "Unknown override format" }
            fun JSONArray.strings() = List(length()) { getString(it) }
            val scripts = root.getJSONArray("scripts").let { array -> List(array.length()) { index ->
                val value = array.getJSONObject(index)
                ScriptResource(value.getString("id"), value.getString("name"), value.nullable("url"), value.getString("content"), value.getLong("updatedAt")).also(::validateScript)
            } }
            val profiles = root.getJSONArray("profiles").let { array -> List(array.length()) { index ->
                val value = array.getJSONObject(index)
                ProfileOverride(value.getString("profileId"), OverrideMode.valueOf(value.getString("mode")), value.nullable("scriptId"),
                    value.getJSONArray("addedRules").strings(), value.getJSONArray("disabledGlobalRuleIds").strings(), value.nullable("matchTarget"),
                    if (!value.has("dnsPreferences") || value.isNull("dnsPreferences")) null else DnsPreferencesJson.decode(value.get("dnsPreferences")))
            } }
            val rules = root.getJSONArray("globalRules").let { array -> List(array.length()) { index ->
                val value = array.getJSONObject(index)
                OverrideRule(value.getString("id"), value.getString("value"))
            } }
            require(scripts.map { it.id }.distinct().size == scripts.size && scripts.map { it.name }.distinct().size == scripts.size)
            require(profiles.map { it.profileId }.distinct().size == profiles.size && rules.map { it.id }.distinct().size == rules.size)
            require(rules.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && validRule(it.value) })
            require(profiles.all { it.profileId.isNotBlank() && it.addedRules.size <= 100000 && it.disabledGlobalRuleIds.distinct().size == it.disabledGlobalRuleIds.size })
            require(profiles.all { it.addedRules.all(::validRule) && (it.matchTarget == null || (it.matchTarget.length <= 1000 && !it.matchTarget.contains('\n'))) })
            require(profiles.all { p -> (p.scriptId == null || scripts.any { it.id == p.scriptId }) && p.disabledGlobalRuleIds.all { id -> rules.any { it.id == id } } })
            return OverrideSnapshot(scripts, profiles, rules)
        }

        fun remapProfiles(state: OverrideSnapshot, ids: Map<String, String>): OverrideSnapshot = state.copy(
            profiles = state.profiles.mapNotNull { profile -> ids[profile.profileId]?.let { profile.copy(profileId = it, dnsPreferences = profile.dnsPreferences?.snapshot()) } },
        )

        /** Compatible restore retains all live entries. Imported identities never collide with live references. */
        fun mergeRestore(current: OverrideSnapshot, incoming: OverrideSnapshot, profileIds: Map<String, String>,
            replace: Boolean, profilesOnly: Boolean, liveProfileIds: Set<String>): OverrideSnapshot {
            val explicitIncoming = incoming.copy(profiles = incoming.profiles + profileIds.keys
                .filter { id -> incoming.profiles.none { it.profileId == id } }.map(::ProfileOverride))
            var mapped = remapProfiles(explicitIncoming, profileIds)
            if (replace && !profilesOnly) return mapped
            if (profilesOnly) mapped = mapped.copy(
                profiles = mapped.profiles.map { p -> p.copy(
                    addedRules = p.addedRules + mapped.globalRules.filterNot { it.id in p.disabledGlobalRuleIds }.map { it.value },
                    disabledGlobalRuleIds = emptyList(),
                ) },
                globalRules = emptyList(),
                scripts = mapped.scripts.filter { script -> mapped.profiles.any { it.scriptId == script.id } },
            )
            val usedScripts = current.scripts.map { it.id }.toMutableSet()
            val usedRules = current.globalRules.map { it.id }.toMutableSet()
            fun fresh(id: String, used: MutableSet<String>): String {
                if (used.add(id)) return id
                return java.util.UUID.randomUUID().toString().also { used.add(it) }
            }
            val scriptIds = mapped.scripts.associate { it.id to fresh(it.id, usedScripts) }
            val ruleIds = mapped.globalRules.associate { it.id to fresh(it.id, usedRules) }
            val names = current.scripts.map { it.name }.toMutableSet()
            val scripts = mapped.scripts.map { script ->
                var name = script.name
                var suffix = 2
                while (!names.add(name)) name = script.name.take(180) + " (${suffix++})"
                script.copy(id = scriptIds.getValue(script.id), name = name)
            }
            val importedRules = mapped.globalRules.map { it.copy(id = ruleIds.getValue(it.id)) }
            val importedProfiles = mapped.profiles.map { p -> p.copy(
                scriptId = p.scriptId?.let(scriptIds::getValue),
                disabledGlobalRuleIds = p.disabledGlobalRuleIds.map(ruleIds::getValue) + current.globalRules.map { it.id },
            ) }
            // Imported globals must not alter unrelated live profiles (including implicit defaults).
            val existing = (current.profiles.filter { it.profileId in liveProfileIds } + liveProfileIds.filter { id -> current.profiles.none { it.profileId == id } }.map(::ProfileOverride))
            val liveProfiles = existing.filterNot { p -> importedProfiles.any { it.profileId == p.profileId } }
                .map { it.copy(disabledGlobalRuleIds = it.disabledGlobalRuleIds + importedRules.map { rule -> rule.id }) }
            return OverrideSnapshot(current.scripts + scripts, liveProfiles + importedProfiles, current.globalRules + importedRules)
        }

        private fun JSONObject.nullable(key: String) = if (isNull(key)) null else getString(key)
        private fun validRule(value: String) = value.isNotBlank() && value.length <= 65536 && !value.contains('\n') && !value.contains('\r')
        private fun <T> replace(list: List<T>, value: T, key: (T) -> String): List<T> =
            if (list.any { key(it) == key(value) }) list.map { if (key(it) == key(value)) value else it } else list + value
    }
}
