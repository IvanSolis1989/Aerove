package dev.aerove.lite.resources

import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.NativeRulePayloadInspector
import dev.aerove.lite.data.RulePayloadInspection
import dev.aerove.lite.data.copyLocalRuleResource
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.PrivateStorage
import dev.aerove.lite.data.ResourceDigest
import dev.aerove.lite.data.ResourceDownloader
import dev.aerove.lite.data.copyResource
import dev.aerove.lite.data.deletePrivateTree
import dev.aerove.lite.data.privateResource
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.sha256
import dev.aerove.lite.data.strictUtf8
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ManagedRuleResource(val name: String, val url: String, val behavior: String, val format: String,
    val bytes: Long, val sha256: String, val updatedAt: Long, val count: Int?, val kind: String = "ruleset", val localId: String? = null,
    val runtimeFormat: String? = null, val runtimeSha256: String? = null, val runtimeBytes: Long? = null)
data class LibraryRuleSource(val name: String, val url: String, val behavior: String, val format: String, val kind: String = "ruleset", val localId: String? = null)
data class CachedLibraryRule(val resource: ManagedRuleResource, val file: File)
interface LibraryCacheRestore {
    fun journalPaths(resources: List<ManagedRuleResource>): List<File>
    suspend fun importResource(resource: ManagedRuleResource, input: InputStream): ManagedRuleResource
}

/** Shared validated library payloads are copied into each profile's independently leased generation. */
internal class ResourceManagementRuleCache(directory: File, private val validator: CompatibilityValidator,
    private val native: NativeResourceValidator, private val downloader: ResourceDownloader, private val clock: () -> Long,
    private val inspector: NativeRulePayloadInspector? = null) {
    private val storage = PrivateStorage(directory)
    private val state = states.computeIfAbsent(storage.root.path) { CacheState() }
    private val mutex = state.mutex

    suspend fun sync(name: String, url: String, behavior: String, format: String, input: InputStream? = null,
        expected: ManagedRuleResource? = null, assetKind: String = "ruleset"): ManagedRuleResource =
        sync(name, url, behavior, format, input, expected, assetKind, isCurrent = { true })

    suspend fun sync(name: String, url: String, behavior: String, format: String, input: InputStream? = null,
        expected: ManagedRuleResource? = null, assetKind: String = "ruleset", isCurrent: () -> Boolean): ManagedRuleResource = withContext(Dispatchers.IO) {
        val key = identity(name, url, behavior, format, assetKind)
        val baseline = locked {
            if (!isCurrent()) throw CancellationException("Resource source changed")
            state.restoreEpoch to indexDigest(key)
        }
        val staging = File(storage.root, ".staging/${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val file = File(staging, "payload.bin")
            val digest = if (input == null) downloader.download(url, file, limit(format, assetKind))
                else input.use { copyResource(it, file, limit(format, assetKind)) }
            currentCoroutineContext().ensureActive()
            locked {
                if (!isCurrent()) throw CancellationException("Resource source changed")
                check(state.restoreEpoch == baseline.first && indexDigest(key) == baseline.second) { "Resource source changed during download" }
                currentCoroutineContext().ensureActive()
                file.inputStream().use { publish(name, url, behavior, format, it, expected, assetKind, prune = true, sourceDigest = digest, commitGuard = isCurrent) }
            }
        } finally { withContext(NonCancellable) { deletePrivateTree(storage.root, staging) } }
    }

    suspend fun storePrepared(name: String, url: String, localId: String?, source: File, digest: ResourceDigest,
        inspected: RulePayloadInspection): ManagedRuleResource = withContext(Dispatchers.IO) { locked {
        source.inputStream().use { publish(name, url, inspected.behavior, inspected.format, it, null, "ruleset", true,
            localId, inspected, digest, true) }
    } }

    suspend fun <T> withPayload(name: String, url: String, behavior: String, format: String, localId: String?,
        action: suspend (ManagedRuleResource, File) -> T): T = withContext(Dispatchers.IO) { locked {
        val saved = checkNotNull(read(identity(name, url, behavior, format, "ruleset", localId))) { "Global rule payload is missing" }
        action(saved.first.copy(name = name), saved.second)
    } }

    suspend fun pruneLocal(retainedIds: Set<String>) = withContext(Dispatchers.IO) { locked {
        val unused = storage.root.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }
            .mapNotNull { index -> read(index.nameWithoutExtension)?.first?.takeIf { it.localId != null && it.localId !in retainedIds }?.let { index } }
        for (index in unused) {
            currentCoroutineContext().ensureActive()
            java.nio.file.Files.deleteIfExists(index.toPath())
            deletePrivateTree(storage.root, File(storage.root, index.nameWithoutExtension))
        }
    } }

    private suspend fun publish(name: String, url: String, behavior: String, format: String, input: InputStream?,
        expected: ManagedRuleResource?, assetKind: String, prune: Boolean, localId: String? = null,
        inspected: RulePayloadInspection? = null, sourceDigest: ResourceDigest? = null, normalized: Boolean = false,
        commitGuard: () -> Boolean = { true }): ManagedRuleResource {
        val identity = identity(name, url, behavior, format, assetKind, localId)
        val directory = File(storage.root, "$identity/${UUID.randomUUID()}").apply { check(mkdirs()) }
        var committed = false
        return try {
            val file = File(directory, "payload.bin")
            val digest = if (input == null) downloader.download(url, file, limit(format, assetKind))
                else input.use { if (localId != null) copyLocalRuleResource(it, file, limit(format, assetKind)) else copyResource(it, file, limit(format, assetKind)) }
            if (expected != null) require(digest.bytes == expected.bytes && digest.sha256 == expected.sha256 && expected.updatedAt >= 0) {
                "Restored rule resource digest does not match its manifest"
            }
            if (sourceDigest != null) require(digest.bytes == sourceDigest.bytes && digest.sha256 == sourceDigest.sha256) { "Inspected rule source changed" }
            var runtimeFile = file
            val runtimeFormat: String?
            val count: Int?
            if (normalized) {
                val parser = checkNotNull(inspector) { "Offline rule payload parser is required" }
                val actual = inspected ?: parser.inspect(file.path)
                require(actual.format == format && actual.behavior == behavior) { "Rule payload inspection changed" }
                require(localId != null || actual.count > 0) { "Remote rule payload is empty" }
                count = actual.count
                runtimeFormat = if (format == "mrs") "mrs" else "text"
                if (format != "mrs") {
                    val canonical = parser.dump(file.path, behavior, format)
                    require(canonical.toByteArray(Charsets.UTF_8).size <= limit("text")) { "Materialized rule payload exceeds limit" }
                    runtimeFile = File(directory, "runtime.txt")
                    PrivateStorage(directory).write("runtime.txt", canonical)
                    validate(runtimeFile, behavior, "text")
                }
            } else {
                runtimeFormat = null
                count = if (assetKind == "ruleset") validate(file, behavior, format) else null
                native.validate(assetKind, file.path, behavior, format)?.let { error(it) }
            }
            if (expected != null) require(expected.count == count) { "Restored resource count does not match its payload" }
            currentCoroutineContext().ensureActive()
            val value = ManagedRuleResource(name, url, behavior, format, digest.bytes, digest.sha256, expected?.updatedAt ?: clock(), count, assetKind,
                localId, runtimeFormat, if (normalized) sha256(runtimeFile) else null, if (normalized) runtimeFile.length() else null)
            if (expected?.runtimeSha256 != null) require(value.runtimeSha256 == expected.runtimeSha256 && value.runtimeBytes == expected.runtimeBytes) { "Restored materialized rule payload changed" }
            val row = encode(value).put("schema", 1).put("file", storage.root.toPath().relativize(file.toPath()).joinToString("/"))
                .put("runtimeFile", if (normalized) storage.root.toPath().relativize(runtimeFile.toPath()).joinToString("/") else JSONObject.NULL)
            PrivateStorage(directory).write("manifest.json", row.toString())
            currentCoroutineContext().ensureActive()
            val previous = runCatching { read(identity) }.getOrNull()?.second?.parentFile
            if (!commitGuard()) throw CancellationException("Resource source changed")
            currentCoroutineContext().ensureActive()
            storage.write("$identity.json", row.toString())
            committed = true
            if (prune) File(storage.root, identity).listFiles().orEmpty().filter { it != directory && it != previous }.forEach {
                deletePrivateTree(storage.root, it)
            }
            checkNotNull(read(identity)).first.also { require(it == value) { "Rule resource readback failed" } }
        } finally {
            if (!committed) withContext(NonCancellable) { deletePrivateTree(storage.root, directory) }
        }
    }

    suspend fun <T> withRestoreTransaction(invalidateInFlight: Boolean = false, action: suspend (LibraryCacheRestore) -> T): T = withContext(Dispatchers.IO) {
        locked {
            if (invalidateInFlight) state.restoreEpoch++
            val owner = checkNotNull(currentCoroutineContext()[CacheLock])
            action(object : LibraryCacheRestore {
                override fun journalPaths(resources: List<ManagedRuleResource>): List<File> = resources.map { value ->
                    storage.file("${identity(value.name, value.url, value.behavior, value.format, value.kind, value.localId)}.json")
                }.distinct()
                override suspend fun importResource(resource: ManagedRuleResource, input: InputStream): ManagedRuleResource {
                    check(currentCoroutineContext()[CacheLock] === owner && owner.active) { "Library restore transaction is closed" }
                    return publish(resource.name, resource.url, resource.behavior, resource.format, input, resource, resource.kind, prune = false,
                        localId = resource.localId, normalized = resource.runtimeFormat != null)
                }
            })
        }
    }

    private suspend fun <T> locked(action: suspend () -> T): T {
        if (currentCoroutineContext()[CacheLock]?.let { it.path == storage.root.path && it.active } == true) return action()
        return mutex.withLock {
            val owner = CacheLock(storage.root.path)
            try { withContext(owner) { action() } } finally { owner.active = false }
        }
    }

    private fun indexDigest(key: String): String? = storage.file("$key.json").takeIf { it.isFile }?.let(::sha256)

    suspend fun <T> withSnapshot(sources: List<LibraryRuleSource>, action: suspend (List<CachedLibraryRule>) -> T): T =
        withContext(Dispatchers.IO) { locked {
            val values = sources.distinctBy { identity(it.name, it.url, it.behavior, it.format, it.kind, it.localId) }.mapNotNull { source ->
                val saved = read(identity(source.name, source.url, source.behavior, source.format, source.kind, source.localId))
                require(source.localId == null || saved != null) { "Local rule source original is missing" }
                saved?.let { (entry, file) ->
                    CachedLibraryRule(entry.copy(name = source.name), file)
                }
            }
            action(values)
        } }

    suspend fun snapshot(name: String, url: String, behavior: String, format: String, assetKind: String = "ruleset", localId: String? = null): ManagedRuleResource? = withContext(Dispatchers.IO) {
        locked { read(identity(name, url, behavior, format, assetKind, localId))?.first?.copy(name = name) }
    }

    suspend fun copyInto(name: String, url: String, behavior: String, format: String, destination: File, maxBytes: Long, assetKind: String = "ruleset", localId: String? = null): ResourceDigest? =
        withContext(Dispatchers.IO) { locked {
            val (entry, file) = read(identity(name, url, behavior, format, assetKind, localId)) ?: return@locked null
            val row = JSONObject(storage.read("${identity(name, url, behavior, format, assetKind, localId)}.json", 16384))
            val runtime = if (entry.runtimeFormat == null) file else privateResource(storage.root, row.getString("runtimeFile"))
            runtime.inputStream().use { if (localId != null) copyLocalRuleResource(it, destination, maxBytes) else copyResource(it, destination, maxBytes) }
                .also { require(it.sha256 == (entry.runtimeSha256 ?: entry.sha256)) }.copy(sourceUpdatedAt = entry.updatedAt)
        } }

    private fun validate(file: File, behavior: String, format: String): Int? {
        if (format == "mrs") return null
        val text = file.inputStream().use { it.readBounded(limit(format).toInt()).strictUtf8() }
        val lines = if (format == "text") text.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith("//") }.toList()
        else {
            val root = ConfigYaml.parse(text, limit(format).toInt())
            require(root.keys == setOf("payload")) { "Rule resource YAML must contain only payload" }
            val rows = root["payload"] as? List<*> ?: error("Rule resource payload is missing")
            require(rows.all { it is String }) { "Rule resource payload must contain strings" }
            rows.filterIsInstance<String>()
        }
        validator.validateRulePayload(lines, "$.rule-providers.library.payload")
        return lines.size
    }

    private fun read(identity: String): Pair<ManagedRuleResource, File>? {
        if (!storage.file("$identity.json").isFile) return null
        val row = JSONObject(storage.read("$identity.json", 16384))
        require(row.getInt("schema") == 1) { "Unsupported rule resource cache schema" }
        val file = privateResource(storage.root, row.getString("file"))
        require(file.parentFile.parentFile.name == identity && file.isFile && file.length() == row.getLong("bytes") &&
            sha256(file) == row.getString("sha256")) { "Rule resource cache changed or is missing" }
        val resource = ManagedRuleResource(row.getString("name"), row.getString("url"), row.getString("behavior"), row.getString("format"),
            row.getLong("bytes"), row.getString("sha256"), row.getLong("updatedAt"),
            if (row.isNull("count")) null else row.getInt("count"), row.optString("kind", "ruleset"), if (row.isNull("localId")) null else row.getString("localId"),
            if (row.isNull("runtimeFormat")) null else row.getString("runtimeFormat"),
            if (row.isNull("runtimeSha256")) null else row.getString("runtimeSha256"),
            if (row.isNull("runtimeBytes")) null else row.getLong("runtimeBytes"))
        require(this.identity(resource.name, resource.url, resource.behavior, resource.format, resource.kind, resource.localId) == identity) {
            "Resource cache source identity changed"
        }
        if (resource.runtimeFormat != null) {
            require(resource.runtimeFormat in setOf("text", "mrs") && resource.runtimeSha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Invalid materialized rule metadata" }
            val runtime = privateResource(storage.root, row.getString("runtimeFile"))
            require(runtime.parentFile == file.parentFile && runtime.isFile && runtime.length() == resource.runtimeBytes && sha256(runtime) == resource.runtimeSha256) { "Materialized rule cache changed" }
        }
        return resource to file
    }

    private fun encode(value: ManagedRuleResource): JSONObject = JSONObject().put("name", value.name).put("url", value.url)
        .put("behavior", value.behavior).put("format", value.format).put("bytes", value.bytes).put("sha256", value.sha256)
        .put("updatedAt", value.updatedAt).put("count", value.count ?: JSONObject.NULL).put("kind", value.kind)
        .put("localId", value.localId ?: JSONObject.NULL).put("runtimeFormat", value.runtimeFormat ?: JSONObject.NULL)
        .put("runtimeSha256", value.runtimeSha256 ?: JSONObject.NULL).put("runtimeBytes", value.runtimeBytes ?: JSONObject.NULL)

    private fun identity(name: String, url: String, behavior: String, format: String, assetKind: String, localId: String? = null): String {
        require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl)) { "Invalid rule resource name" }
        if (localId == null) dev.aerove.lite.data.SubscriptionDownloader.parseUrl(url)
        else require(url.isEmpty() && UUID.fromString(localId).toString() == localId && assetKind == "ruleset") { "Invalid local rule payload identity" }
        require(assetKind in setOf("ruleset", "mmdb", "geosite")) { "Unsupported resource kind" }
        if (assetKind == "ruleset") require(behavior in setOf("domain", "ipcidr", "classical") && format in setOf("yaml", "text", "mrs")) { "Unsupported rule resource format or behavior" }
        else require(behavior.isEmpty() && format.isEmpty()) { "Unexpected geo resource format" }
        return sha256(JSONArray(if (localId == null) listOf(assetKind, url, behavior, format) else listOf(assetKind, "local", localId, behavior, format)).toString())
    }
    private fun limit(format: String, assetKind: String = "ruleset") = if (assetKind != "ruleset") 64L * 1024 * 1024 else if (format == "mrs") 32L * 1024 * 1024 else 8L * 1024 * 1024
    private class CacheLock(val path: String) : AbstractCoroutineContextElement(Key) {
        @Volatile var active = true
        companion object Key : CoroutineContext.Key<CacheLock>
    }
    private class CacheState { val mutex = Mutex(); var restoreEpoch = 0L }
    companion object { private val states = ConcurrentHashMap<String, CacheState>() }
}
