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

internal fun ByteArray.strictUtf8(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this)).toString().removePrefix("\uFEFF")

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

internal suspend fun <T> atomicDomainRestore(directory: File, action: suspend () -> T): T = privateDomainTransaction(directory) {
    val root = directory.canonicalFile
    val pending = File(root, ".restore-journal")
    check(!pending.exists()) { "A restore is already pending" }
    val candidate = File(root, ".restore-${UUID.randomUUID()}").apply { check(mkdir()) }
    try {
        val journal = PrivateStorage(candidate)
        val present = restoreFiles.mapIndexed { index, path ->
            val original = File(root, path)
            if (original.exists()) {
                require(original.isFile && !Files.isSymbolicLink(original.toPath())) { "Unsafe restore target" }
                val limit = if (index == 0) 128 * 1024 else 1024 * 1024
                journal.write("$index.json", original.inputStream().use { it.readBounded(limit).strictUtf8() })
                true
            } else false
        }
        journal.write("state.json", org.json.JSONArray(present).toString())
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
                try { recoverDomainRestore(root) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            }
            throw failure
        }
    } finally { if (candidate.exists()) deletePrivateTree(root, candidate) }
}

private val restoreFiles = listOf("settings/settings.json", "profiles/index.json", "backups/webdav.json")

private fun recoverDomainRestore(root: File) {
    val journal = File(root, ".restore-journal")
    if (!journal.exists()) return
    require(journal.isDirectory && !Files.isSymbolicLink(journal.toPath())) { "Unsafe restore journal" }
    val state = org.json.JSONArray(File(journal, "state.json").inputStream().use { it.readBounded(1024).strictUtf8() })
    require(state.length() == restoreFiles.size) { "Invalid restore journal" }
    restoreFiles.forEachIndexed { index, path ->
        val target = File(root, path)
        require(target.canonicalFile.toPath().startsWith(root.toPath()) && !Files.isSymbolicLink(target.toPath())) { "Unsafe restore target" }
        if (state.getBoolean(index)) {
            val limit = if (index == 0) 128 * 1024 else 1024 * 1024
            PrivateStorage(target.parentFile!!).write(target.name,
                File(journal, "$index.json").inputStream().use { it.readBounded(limit).strictUtf8() })
        } else Files.deleteIfExists(target.toPath())
    }
    deletePrivateTree(root, journal)
}
