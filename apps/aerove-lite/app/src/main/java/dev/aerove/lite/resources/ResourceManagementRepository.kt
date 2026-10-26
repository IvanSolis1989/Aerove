package dev.aerove.lite.resources

import dev.aerove.lite.data.PreparedProfile
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.NativeRulePayloadInspector
import dev.aerove.lite.data.MAX_RULE_INPUT_BYTES
import dev.aerove.lite.data.MAX_RULE_PREVIEW_BYTES
import dev.aerove.lite.data.MAX_RULE_TEXT_BYTES
import dev.aerove.lite.data.countRuleLines
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.editorSizeFailure
import dev.aerove.lite.data.readEditorRuleText
import dev.aerove.lite.data.readRuleYaml
import dev.aerove.lite.data.strictUtf8
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ManagedResource(
    val key: String, val kind: String, val sha256: String, val bytes: Long,
    val updatedAt: Long, val url: String?, val localPath: String?, val sideLoaded: Boolean,
    val behavior: String, val format: String,
    val subscriptionInfo: ResourceSubscriptionInfo? = null,
    val count: Int? = null,
) {
    val replaceable: Boolean get() = kind !in setOf("proxy-provider", "private-file")
    val name: String get() = key.removePrefix("$.rule-providers.").removePrefix("$.proxy-providers.").removePrefix("$.")
}

data class ResourceManagementSnapshot(val prepared: PreparedProfile, val entries: List<ManagedResource>)

/** Saved immutable manifests are authoritative; the lifecycle owner applies returned generations. */
class ResourceManagementRepository(private val profiles: ProfileRepository,
    private val offlineRules: NativeRulePayloadInspector? = null) {
    suspend fun snapshot(profileId: String): ResourceManagementSnapshot = describe(profiles.prepare(profileId))
    suspend fun refresh(profileId: String): ResourceManagementSnapshot = describe(profiles.refreshResources(profileId))
    suspend fun refresh(profileId: String, key: String): ResourceManagementSnapshot = describe(profiles.refreshResource(profileId, key))
    suspend fun sideload(profileId: String, key: String, input: InputStream): ResourceManagementSnapshot =
        describe(profiles.sideLoadResource(profileId, key, input))

    suspend fun preview(profileId: String, key: String): String {
        val prepared = profiles.prepare(profileId)
        val lease = profiles.retain(prepared)
        try {
            return withContext(Dispatchers.IO) {
                val directory = checkNotNull(prepared.resourceDirectory)
                val manifest = JSONObject(File(directory, "manifest.json").readText()).getJSONArray("records")
                val record = (0 until manifest.length()).map { manifest.getJSONObject(it) }.single { it.getString("key") == key }
                require(record.getString("kind") !in setOf("mmdb", "geosite", "private-file")) {
                    "Binary resource cannot be opened as text"
                }
                val target = File(directory, record.getString("file")).canonicalFile
                require(target.toPath().startsWith(directory.canonicalFile.toPath()))
                currentCoroutineContext().ensureActive()
                if (record.optString("format") == "mrs") {
                    checkNotNull(offlineRules) { "Offline ruleset inspector is not configured" }
                        .dump(target.path, record.getString("behavior"), "mrs")
                        .also { currentCoroutineContext().ensureActive() }
                } else target.readEditorRuleText(MAX_RULE_TEXT_BYTES) ?: editorSizeFailure(MAX_RULE_PREVIEW_BYTES)
            }
        } finally { profiles.release(lease) }
    }

    private suspend fun describe(prepared: PreparedProfile): ResourceManagementSnapshot {
        val lease = profiles.retain(prepared)
        try {
            val checks = profiles.resourceChecks(prepared)
            return withContext(Dispatchers.IO) {
                val directory = prepared.resourceDirectory ?: return@withContext ResourceManagementSnapshot(prepared, emptyList())
                val manifest = JSONObject(File(directory, "manifest.json").readText())
                val records = manifest.getJSONArray("records")
                val entries = (0 until records.length()).map { i ->
                    val row = records.getJSONObject(i)
                    val path = if (row.isNull("localPath")) null else row.getString("localPath")
                    val kind = row.getString("kind")
                    val format = row.optString("format")
                    val key = row.getString("key")
                    val receipt = checks.receipts[key]?.takeIf { !row.optBoolean("localOverride", false) &&
                        it.sourceSha256 == row.getString("sourceSha256") && it.sha256 == row.getString("sha256") }
                    val subscriptionInfo = if (receipt != null) ResourceSubscriptionInfo.parse(receipt.subscriptionUserInfo)
                        else if (row.isNull("subscriptionUserInfo")) null else ResourceSubscriptionInfo.parse(row.getString("subscriptionUserInfo"))
                    val count = if (kind in setOf("ruleset", "source-proxy") && format in setOf("yaml", "text")) {
                        val file = File(directory, row.getString("file")).canonicalFile
                        require(file.toPath().startsWith(directory.canonicalFile.toPath()))
                        if (kind == "ruleset") {
                            if (format == "text") file.countRuleLines(MAX_RULE_INPUT_BYTES)
                            else (file.readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"] as List<*>).size
                        } else {
                            val content = file.inputStream().use { it.readBounded(8 * 1024 * 1024).strictUtf8() }
                            if (format == "text") content.lineSequence().map(String::trim)
                                .count { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith("//") }
                            else (ConfigYaml.parse(content, 8 * 1024 * 1024)["proxies"] as List<*>).size
                        }
                    } else null
                    ManagedResource(row.getString("key"), row.getString("kind"), row.getString("sha256"), row.getLong("bytes"),
                        maxOf(row.optLong("updatedAt", manifest.getLong("createdAt")), checks.checkedAt[key] ?: 0L, receipt?.checkedAt ?: 0L),
                        if (row.isNull("url")) null else row.getString("url"), path,
                        path?.startsWith(".overrides/") == true, row.optString("behavior"), row.optString("format"),
                        subscriptionInfo, count)
                }
                ResourceManagementSnapshot(prepared, entries)
            }
        } finally { profiles.release(lease) }
    }
}
