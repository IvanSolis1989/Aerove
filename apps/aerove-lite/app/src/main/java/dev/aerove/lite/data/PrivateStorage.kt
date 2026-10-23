package dev.aerove.lite.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MAX_PROFILE_BYTES = 4 * 1024 * 1024

/** Explicit restore outcome; unrelated suppressed exceptions never imply rollback success. */
class RestoreRollbackStatus internal constructor(val verified: Boolean, reason: Throwable? = null) :
    Exception(if (verified) "Restore rollback verified" else "Restore rollback is unverified", reason)

fun rollbackVerified(failure: Throwable): Boolean {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val pending = java.util.ArrayDeque<Throwable>().apply { add(failure) }
    var verified = false
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        if (current is RestoreRollbackStatus) {
            if (!current.verified) return false
            verified = true
        }
        current.cause?.let(pending::addLast)
        current.suppressed.filterIsInstance<RestoreRollbackStatus>().forEach(pending::addLast)
    }
    return verified
}

fun invalidateRestoreRollback(failure: Throwable, reason: Throwable) {
    failure.addSuppressed(RestoreRollbackStatus(false, reason))
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (result.size().toLong() + count > limit) throw IOException("Input exceeds $limit bytes")
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

internal fun ByteArray.strictUtf8(): String = strictUtf8PreservingBom().removePrefix("\uFEFF")

internal fun ByteArray.strictUtf8PreservingBom(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this)).toString()

private fun InputStream.restoreText(limit: Int): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(readBounded(limit))).toString()

internal fun deletePrivateTree(root: File, directory: File) {
    val boundary = root.canonicalFile.toPath()
    val target = directory.canonicalFile.toPath()
    require(target.startsWith(boundary) && target != boundary) { "Cleanup target escapes private directory" }
    if (Files.exists(target)) Files.walk(target).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

internal class PrivateStorage(directory: File) {
    val root: File = directory.canonicalFile
    private val mutex = locks.computeIfAbsent(root.path) { Mutex() }

    init {
        require(root.isDirectory || root.mkdirs()) { "Cannot create private storage" }
    }

    suspend fun <T> transaction(action: suspend () -> T): T = privateDomainTransaction(domain) { withContext(Dispatchers.IO) {
        mutex.withLock {
            FileChannel.open(
                File(root, ".lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel -> channel.lock().use { action() } }
        }
    } }

    private val domain get() = if (root.name in setOf("profiles", "settings")) root.parentFile!! else root

    fun read(name: String, limit: Int = MAX_PROFILE_BYTES): String =
        file(name).inputStream().use { it.readBounded(limit).strictUtf8() }

    fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && name != "." && name != "..")
        return File(root, name)
    }

    fun write(name: String, content: String) {
        val target = file(name)
        val temporary = file(".tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}

private class StorageDomain(val paths: Set<String>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StorageDomain>
}

private val domainLocks = ConcurrentHashMap<String, Mutex>()

internal suspend fun <T> privateDomainTransaction(directory: File, action: suspend () -> T): T {
    val root = directory.canonicalFile
    val held = currentCoroutineContext()[StorageDomain]?.paths.orEmpty()
    if (root.path in held) return action()
    return withContext(Dispatchers.IO) {
        domainLocks.computeIfAbsent(root.path) { Mutex() }.withLock {
            check(root.isDirectory || root.mkdirs()) { "Cannot create data domain" }
            FileChannel.open(File(root, ".domain.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    recoverDomainRestore(root)
                    withContext(StorageDomain(held + root.path)) { action() }
                }
            }
        }
    }
}

internal suspend fun <T> atomicDomainRestore(directory: File, action: suspend () -> T): T =
    atomicDomainRestore(directory, emptyList(), action)

internal suspend fun <T> atomicDomainRestore(directory: File, extraPaths: List<String>, action: suspend () -> T): T = privateDomainTransaction(directory) {
    val root = directory.canonicalFile
    val pending = File(root, ".restore-journal")
    check(!pending.exists()) { "A restore is already pending" }
    require(extraPaths.distinct().size == extraPaths.size && extraPaths.size <= 12000 && extraPaths.all(::isCacheIndex)) { "Invalid restore cache index paths" }
    val paths = restoreFiles + extraPaths.sorted()
    val candidate = File(root, ".restore-${UUID.randomUUID()}").apply { check(mkdir()) }
    try {
        val journal = PrivateStorage(candidate)
        val records = paths.mapIndexed { index, path ->
            val original = restoreTarget(root, path)
            if (original.exists()) {
                require(original.isFile && !Files.isSymbolicLink(original.toPath())) { "Unsafe restore target" }
                val limit = restoreLimit(path)
                journal.write("$index.json", original.inputStream().use { it.restoreText(limit) })
                org.json.JSONObject().put("path", path).put("present", true)
            } else org.json.JSONObject().put("path", path).put("present", false)
        }
        journal.write("state.json", org.json.JSONObject().put("schema", 2)
            .put("files", org.json.JSONArray(records)).toString())
        Files.move(candidate.toPath(), pending.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try {
            val value = action()
            withContext(NonCancellable) {
                // The journal rename is the commit point; until then every opener rolls both stores back.
                val committed = File(root, ".restore-committed-${UUID.randomUUID()}")
                Files.move(pending.toPath(), committed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                runCatching { deletePrivateTree(root, committed) }
            }
            value
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    check(pending.exists()) { "Restore journal is missing before rollback" }
                    recoverDomainRestore(root)
                    failure.addSuppressed(RestoreRollbackStatus(true))
                } catch (rollback: Throwable) { invalidateRestoreRollback(failure, rollback) }
            }
            throw failure
        }
    } finally { if (candidate.exists()) deletePrivateTree(root, candidate) }
}

private val legacyRestoreFiles = listOf("settings/settings.json", "profiles/index.json", "backups/webdav.json")
private val restoreFiles = legacyRestoreFiles + listOf("settings/overrides.json", "settings/builder-resources.yaml")

private fun restoreLimit(path: String): Int = when (path) {
    "settings/settings.json" -> 128 * 1024
    "profiles/index.json" -> MAX_PROFILE_INDEX_BYTES
    "settings/overrides.json", "settings/builder-resources.yaml" -> 16 * 1024 * 1024
    else -> if (isCacheIndex(path)) 16384 else 1024 * 1024
}

private fun isCacheIndex(path: String): Boolean = path.matches(Regex("runtime/resources/library/[0-9a-f]{64}\\.json"))

private fun restoreTarget(root: File, path: String): File {
    require(path in restoreFiles || isCacheIndex(path)) { "Unexpected restore journal path" }
    val target = File(root, path)
    require(target.canonicalFile.toPath().startsWith(root.toPath()) && !Files.isSymbolicLink(target.toPath())) { "Unsafe restore target" }
    return target
}

private fun recoverDomainRestore(root: File) {
    val journal = File(root, ".restore-journal")
    if (!journal.exists()) return
    require(journal.isDirectory && !Files.isSymbolicLink(journal.toPath())) { "Unsafe restore journal" }
    val text = File(journal, "state.json").inputStream().use { it.readBounded(4 * 1024 * 1024).strictUtf8() }
    val records: List<Pair<String, Boolean>> = if (text.trimStart().startsWith("[")) {
        val state = org.json.JSONArray(text)
        require(state.length() == legacyRestoreFiles.size) { "Invalid legacy restore journal" }
        legacyRestoreFiles.mapIndexed { index, path -> path to state.getBoolean(index) }
    } else {
        val state = org.json.JSONObject(text)
        require(state.getInt("schema") == 2) { "Unknown restore journal schema" }
        val files = state.getJSONArray("files")
        require(files.length() in restoreFiles.size..(restoreFiles.size + 12000)) { "Incomplete restore journal" }
        List(files.length()) { index -> files.getJSONObject(index).let { it.getString("path") to it.getBoolean("present") } }
            .also { rows ->
                val paths = rows.map(Pair<String, Boolean>::first)
                require(paths.take(restoreFiles.size) == restoreFiles && paths.distinct().size == paths.size &&
                    paths.drop(restoreFiles.size).all(::isCacheIndex)) { "Invalid restore journal paths" }
            }
    }
    // Validate every payload before writing any destination, so corrupt journals cannot partly roll back.
    val originals = records.mapIndexed { index, (path, present) ->
        restoreTarget(root, path)
        if (present) {
            val payload = File(journal, "$index.json")
            require(payload.isFile && !Files.isSymbolicLink(payload.toPath())) { "Unsafe restore journal payload" }
            payload.inputStream().use { it.restoreText(restoreLimit(path)) }
        } else null
    }
    records.forEachIndexed { index, (path, _) ->
        val target = restoreTarget(root, path)
        val content = originals[index]
        if (content != null) PrivateStorage(target.parentFile!!).write(target.name, content)
        else Files.deleteIfExists(target.toPath())
    }
    // Resume is allowed only after every registered domain file matches its journal snapshot.
    records.forEachIndexed { index, (path, _) ->
        val target = restoreTarget(root, path)
        val expected = originals[index]
        if (expected == null) check(!target.exists()) { "Rollback removal could not be verified: $path" }
        else check(target.isFile && target.inputStream().use { it.restoreText(restoreLimit(path)) } == expected) {
            "Rollback content could not be verified: $path"
        }
    }
    deletePrivateTree(root, journal)
}
