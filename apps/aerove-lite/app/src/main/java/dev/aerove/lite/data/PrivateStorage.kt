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
import kotlinx.coroutines.Dispatchers
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

    suspend fun <T> transaction(action: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            FileChannel.open(
                File(root, ".lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel -> channel.lock().use { action() } }
        }
    }

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
