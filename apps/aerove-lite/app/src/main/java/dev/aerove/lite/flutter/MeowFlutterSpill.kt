package dev.aerove.lite.flutter

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class MeowConfigProtocolException(val code: String, message: String, val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null) : IOException(message, cause)

data class MeowSpillBudget(val maxFileBytes: Int = 16 * 1024 * 1024,
    val maxDirectoryBytes: Long = 64L * 1024 * 1024, val maxFiles: Int = 64) {
    init { require(maxFileBytes > 0 && maxDirectoryBytes >= maxFileBytes && maxFiles > 0) }
}

class MeowFlutterSpill(homeDir: File, private val budget: MeowSpillBudget = MeowSpillBudget()) : AutoCloseable {
    internal val home = checkedHome(homeDir)
    private val directory = File(home, "responses")
    private val monitor = monitors.computeIfAbsent(directory.path) { Any() }
    private val owned = mutableSetOf<File>()
    private var closed = false

    internal suspend fun write(text: String, extension: String): String {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > budget.maxFileBytes) failure("Response exceeds the file byte budget")
        require(extension in setOf("json", "yaml"))
        return synchronized(monitor) {
            checkOpen()
            prepareDirectory()
            owned.removeAll { !Files.exists(it.toPath(), NOFOLLOW_LINKS) }
            val entries = directory.listFiles() ?: failure("Cannot inspect response directory")
            var total = 0L
            entries.forEach {
                checkedDirectFile(it)
                total = Math.addExact(total, Files.size(it.toPath()))
            }
            if (entries.size >= budget.maxFiles || total + bytes.size > budget.maxDirectoryBytes)
                failure("Response directory budget exhausted")
            val identity = UUID.randomUUID().toString()
            val temporary = File(directory, ".$identity.tmp")
            val target = File(directory, "$identity.$extension")
            try {
                FileChannel.open(temporary.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                    restrictPermissions(temporary)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        context.ensureActive()
                        val limit = buffer.limit()
                        buffer.limit(minOf(limit, buffer.position() + 64 * 1024))
                        channel.write(buffer)
                        buffer.limit(limit)
                    }
                    channel.force(true)
                }
                context.ensureActive()
                checkedDirectory()
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
                checkedDirectFile(target)
                context.ensureActive()
                owned.add(target)
                target.path
            } catch (failure: Throwable) {
                listOf(temporary, target).forEach { file ->
                    try { Files.deleteIfExists(file.toPath()) }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }

    fun discard(path: String): Unit = synchronized(monitor) {
        val file = File(path)
        if (!owned.contains(file)) return@synchronized
        checkedDirectory()
        checkedDirectFile(file, allowMissing = true)
        Files.deleteIfExists(file.toPath())
        owned.remove(file)
        Unit
    }

    override fun close(): Unit = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        var error: Throwable? = null
        owned.toList().forEach { file ->
            try { discard(file.path) }
            catch (failure: Throwable) { if (error == null) error = failure else error!!.addSuppressed(failure) }
        }
        error?.let { throw it }
    }

    private fun prepareDirectory() {
        checkedHome(home)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
            Files.createDirectory(directory.toPath())
            restrictPermissions(directory, directory = true)
        }
        checkedDirectory()
    }

    private fun checkedDirectory() {
        checkedHome(home)
        if (directory.absoluteFile != directory.canonicalFile || Files.isSymbolicLink(directory.toPath()) ||
            !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || directory.toPath() != directory.toPath().toRealPath())
            failure("Unsafe response directory")
    }

    private fun checkedDirectFile(file: File, allowMissing: Boolean = false) {
        if (file.parentFile != directory || file.absoluteFile != file.canonicalFile || Files.isSymbolicLink(file.toPath()) ||
            (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !(allowMissing && !Files.exists(file.toPath(), NOFOLLOW_LINKS))))
            failure("Unsafe response file")
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS) && file.toPath() != file.toPath().toRealPath()) failure("Unsafe response file")
    }

    private fun checkOpen() { if (closed) throw MeowConfigProtocolException("closed", "Configuration protocol is closed") }
    private fun failure(message: String): Nothing = throw MeowConfigProtocolException("spill_error", message)

    companion object {
        private val monitors = ConcurrentHashMap<String, Any>()
        internal fun checkedHome(file: File): File {
            val absolute = file.absoluteFile
            if (absolute != file.canonicalFile || !Files.isDirectory(absolute.toPath(), NOFOLLOW_LINKS) ||
                absolute.toPath() != absolute.toPath().toRealPath())
                throw MeowConfigProtocolException("path_error", "Home must be an existing canonical private directory")
            var component: File? = absolute
            while (component != null) {
                if (Files.isSymbolicLink(component.toPath()))
                    throw MeowConfigProtocolException("path_error", "Symbolic links are not accepted")
                component = component.parentFile
            }
            return absolute
        }

        private fun restrictPermissions(file: File, directory: Boolean = false) {
            if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        }
    }
}
