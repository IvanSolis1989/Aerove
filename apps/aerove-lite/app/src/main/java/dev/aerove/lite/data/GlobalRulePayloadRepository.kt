package dev.aerove.lite.data

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.aerove.lite.profilebuilder.BuilderEntry
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.BuilderResourceSnapshot
import dev.aerove.lite.profilebuilder.BuilderResourceKind
import dev.aerove.lite.profilebuilder.BuilderRemoteRefreshTicket
import dev.aerove.lite.resources.ManagedRuleResource
import dev.aerove.lite.resources.LibraryRuleSource

/** Data-only global provider edits share the existing definition CAS and domain restore journal. */
class GlobalRulePayloadRepository(
    privateDirectory: File,
    private val definitions: BuilderResourceLibrary,
    private val manager: ResourceBundleManager,
    private val inspector: NativeRulePayloadInspector,
    private val profiles: ProfileRepository,
    private val downloader: ResourceDownloader = ResourceDownloader(),
) {
    private val root = privateDirectory.canonicalFile

    suspend fun prepareLibrary(): BuilderResourceSnapshot = profiles.migrateGlobalResourceIdentity(definitions)

    suspend fun importUrl(expected: BuilderResourceSnapshot, name: String, url: String): BuilderResourceSnapshot {
        SubscriptionDownloader.parseUrl(url)
        val label = if (name.isNotBlank()) name else {
            val file = java.net.URI(url).path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Provider" }
            val base = file.take(180)
            generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base ($it)" }
                .first { candidate -> expected.resources.ruleProviders.none { it.name == candidate } }
        }
        return stage(expected, null, label, url, null)
    }

    suspend fun importFile(expected: BuilderResourceSnapshot, name: String, input: InputStream): BuilderResourceSnapshot =
        stage(expected, null, name, null, input)

    suspend fun saveText(expected: BuilderResourceSnapshot, previousName: String?, name: String, text: String): BuilderResourceSnapshot {
        if (previousName != null) require(entry(expected, previousName).fields["type"] == "file") { "Remote rule payload is read-only" }
        return stage(expected, previousName, name, null, text.byteInputStream())
    }

    suspend fun sync(expected: BuilderResourceSnapshot, name: String): BuilderResourceSnapshot {
        val previous = entry(expected, name)
        val url = previous.fields["url"] as? String ?: error("Rule resource has no remote URL")
        val ticket = definitions.beginRemoteRefresh(expected, name, BuilderResourceKind.RuleProvider)
        return stage(expected, name, previous.name, url, null, ticket)
    }

    suspend fun options(expected: BuilderResourceSnapshot, previousName: String, name: String, url: String?): BuilderResourceSnapshot {
        val old = entry(expected, previousName)
        val previousUrl = old.fields["url"] as? String
        require(previousUrl != null || url.isNullOrEmpty()) { "Local rule payload cannot acquire a remote URL" }
        if (previousUrl != null && previousUrl != url) return stage(expected, previousName, name, checkNotNull(url), null)
        return commit(expected, previousName, old.copy(name = name), null, null, null)
    }

    suspend fun delete(expected: BuilderResourceSnapshot, name: String): BuilderResourceSnapshot =
        commit(expected, name, null, null, null, null)

    suspend fun preview(expected: BuilderResourceSnapshot, name: String): GlobalRulePayloadPreview = payload(expected, name) { metadata, file ->
        val editable = metadata.localId != null && metadata.format != "mrs"
        val text = if (metadata.format == "mrs") inspector.dump(file.path, metadata.behavior, metadata.format)
            else file.inputStream().use { it.readBounded(MAX_TEXT.toInt()).strictUtf8() }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT) { "Rule preview exceeds text limit" }
        GlobalRulePayloadPreview(text, editable)
    }

    suspend fun info(expected: BuilderResourceSnapshot, name: String): GlobalRulePayloadInfo = payload(expected, name) { value, _ ->
        GlobalRulePayloadInfo(value.behavior, value.format, value.count, value.bytes, value.sha256, value.updatedAt, value.localId == null)
    }

    private suspend fun <T> payload(expected: BuilderResourceSnapshot, name: String, action: suspend (ManagedRuleResource, File) -> T): T =
        privateDomainTransaction(root) {
            require(definitions.snapshot().encoded == expected.encoded) { "Resources changed; reload before reading" }
            val fields = entry(expected, name).fields
            manager.withGlobalRule(name, fields["url"] as? String ?: "", fields["behavior"] as String, fields["format"] as String,
                if (fields["type"] == "file") globalRuleLocalId(fields["path"] as? String) else null, action)
        }

    private suspend fun stage(expected: BuilderResourceSnapshot, previousName: String?, name: String, url: String?, input: InputStream?,
        refreshTicket: BuilderRemoteRefreshTicket? = null): BuilderResourceSnapshot =
        withContext(Dispatchers.IO) {
            requireName(expected, previousName, name)
            url?.let(SubscriptionDownloader::parseUrl)
            val directory = File(root, "runtime/global-rule-staging/${UUID.randomUUID()}").apply { check(mkdirs()) }
            try {
                val source = File(directory, "origin.bin")
                val digest = if (url != null) downloader.download(url, source, MAX_BINARY)
                    else checkNotNull(input).use { copyLocalRuleResource(it, source, MAX_BINARY) }
                currentCoroutineContext().ensureActive()
                val inspection = inspector.inspect(source.path)
                require(inspection.format == "mrs" || digest.bytes <= MAX_TEXT) { "Rule payload exceeds text limit" }
                require(url == null || inspection.count > 0) { "Remote rule payload is empty" }
                val token = UUID.randomUUID().toString()
                val fields = if (refreshTicket == null) linkedMapOf<String, Any?>() else entry(expected, checkNotNull(previousName)).fields.toMutableMap()
                fields["type"] = if (url == null) "file" else "http"
                fields["path"] = "$GLOBAL_RULE_PATH_PREFIX$token"
                fields["behavior"] = inspection.behavior
                fields["format"] = inspection.format
                if (url != null) fields["url"] = url
                commit(expected, previousName, BuilderEntry(name, fields, previousName?.let { entry(expected, it).id } ?: UUID.randomUUID().toString()), source, digest, inspection, refreshTicket)
            } finally { deletePrivateTree(root, directory) }
        }

    private suspend fun commit(expected: BuilderResourceSnapshot, previousName: String?, replacement: BuilderEntry?, source: File?,
        digest: ResourceDigest?, inspection: RulePayloadInspection?, refreshTicket: BuilderRemoteRefreshTicket? = null): BuilderResourceSnapshot = privateDomainTransaction(root) {
        val current = definitions.snapshot()
        val baseline = if (refreshTicket == null) {
            require(current.encoded == expected.encoded) { "Resources changed; reload before saving" }
            expected
        } else {
            definitions.requireRemoteRefreshCurrent(refreshTicket)
            val id = checkNotNull(previousName)
            require(entry(current, id) == entry(expected, id)) { "Rule resource changed during download" }
            current
        }
        replacement?.let { requireName(baseline, previousName, it.name) }
        val rows = baseline.resources.ruleProviders.filterNot { it.id == previousName } + listOfNotNull(replacement)
        manager.withLibraryRestoreTransaction { cache ->
            val fields = replacement?.fields
            val localId = if (fields?.get("type") == "file") globalRuleLocalId(fields["path"] as? String) else null
            val metadata = if (source != null) ManagedRuleResource(checkNotNull(replacement).name, fields?.get("url") as? String ?: "",
                checkNotNull(inspection).behavior, inspection.format, checkNotNull(digest).bytes, digest.sha256, 0, inspection.count, localId = localId) else null
            val paths = cache.journalPaths(listOfNotNull(metadata)).map { root.toPath().relativize(it.toPath()).joinToString("/") }
            atomicDomainRestore(root, paths) {
                if (source != null) manager.storeGlobalRule(checkNotNull(replacement).name, metadata!!.url, localId, source, checkNotNull(digest), checkNotNull(inspection))
                currentCoroutineContext().ensureActive()
                profiles.commitGlobalResourceMutationInTransaction(definitions, baseline, baseline.resources.copy(ruleProviders = rows),
                    if (previousName != null && replacement == null) mapOf(BuilderResourceKind.RuleProvider to setOf(previousName)) else emptyMap())
            }
        }
    }

    private fun entry(expected: BuilderResourceSnapshot, id: String) = expected.resources.ruleProviders.single { it.id == id }
    private fun requireName(expected: BuilderResourceSnapshot, previousName: String?, name: String) {
        require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl)) { "Invalid rule resource name" }
        require(expected.resources.ruleProviders.none { it.name == name && it.id != previousName }) { "Duplicate rule resource name" }
        if (previousName != null) entry(expected, previousName)
    }
    companion object { private const val MAX_TEXT = 8L * 1024 * 1024; private const val MAX_BINARY = 32L * 1024 * 1024 }
}

/** Pure file parser callbacks; production binds the two no-handle NativeCore APIs. */
interface NativeRulePayloadInspector {
    fun inspect(path: String): RulePayloadInspection
    fun dump(path: String, behavior: String, format: String): String
}

class NativeJsonRulePayloadInspector(
    private val inspectJson: (String) -> String,
    private val dumpText: (String, String, String) -> String,
) : NativeRulePayloadInspector {
    override fun inspect(path: String): RulePayloadInspection = RulePayloadInspection.decode(inspectJson(path))
    override fun dump(path: String, behavior: String, format: String): String = dumpText(path, behavior, format)
}

data class RulePayloadInspection(val format: String, val behavior: String, val count: Int) {
    init {
        require(format in setOf("text", "yaml", "mrs") && behavior in setOf("domain", "ipcidr", "classical") && count >= 0) {
            "Invalid offline ruleset inspection"
        }
    }
    companion object {
        fun decode(text: String): RulePayloadInspection {
            require(text.length <= 1024) { "Offline ruleset receipt exceeds its limit" }
            val row = JSONObject(text)
            require(row.keys().asSequence().toSet() == setOf("format", "behavior", "count")) { "Invalid offline ruleset receipt" }
            val format = row.get("format"); val behavior = row.get("behavior"); val count = row.get("count")
            require(format is String && behavior is String && count is Number && count !is Float && count !is Double) {
                "Invalid offline ruleset receipt types"
            }
            val exact = count.toString().toLongOrNull()
            require(exact != null && exact in 0..Int.MAX_VALUE.toLong()) { "Invalid offline ruleset count" }
            return RulePayloadInspection(format, behavior, exact.toInt())
        }
    }
}

data class GlobalRulePayloadPreview(val text: String, val editable: Boolean)
data class GlobalRulePayloadInfo(val behavior: String, val format: String, val count: Int?, val bytes: Long,
    val sha256: String, val updatedAt: Long, val remote: Boolean)

internal const val GLOBAL_RULE_PATH_PREFIX = ".library/"
internal fun globalRuleLocalId(path: String?): String? = path?.takeIf { it.startsWith(GLOBAL_RULE_PATH_PREFIX) }?.let {
    val id = it.removePrefix(GLOBAL_RULE_PATH_PREFIX)
    require(java.util.UUID.fromString(id).toString() == id) { "Invalid global rule payload reference" }
    id
}

/** Native source paths remain owned originals even while a live binding overrides them. */
internal fun localGlobalRuleSources(source: String): List<LibraryRuleSource> {
    return (ConfigYaml.parse(source)["rule-providers"] as? Map<*, *>).orEmpty().mapNotNull { (name, raw) ->
        if (name !is String) return@mapNotNull null
        val fields = raw as? Map<*, *> ?: return@mapNotNull null
        if (fields["type"] != "file") return@mapNotNull null
        val id = globalRuleLocalId(fields["path"] as? String) ?: return@mapNotNull null
        LibraryRuleSource(name, "", fields["behavior"] as? String ?: "domain", fields["format"] as? String ?: "yaml", localId = id)
    }
}
