package dev.aerove.lite.data

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object ProfileArchive {
    private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 256
    private val legacyCharset = Charset.forName("CP437")

    suspend fun extract(input: InputStream, archive: File, destination: File): String {
        check(archive.parentFile!!.isDirectory || archive.parentFile!!.mkdirs())
        check(destination.mkdirs()) { "ZIP staging directory already exists or cannot be created" }
        copyResource(input, archive, MAX_ARCHIVE_BYTES)
        val metadata = metadata(archive)
        val seen = mutableSetOf<String>()
        metadata.forEach { entry ->
            val path = entry.name.removeSuffix("/")
            require(path.isNotEmpty() && path.length <= 1024 && path.split('/').size <= 32 &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                path.none { it.isISOControl() || it == ':' || it == '\\' }) { "Unsafe ZIP entry path" }
            privateResource(destination, path)
            require(seen.add(path.lowercase(Locale.ROOT))) { "Duplicate ZIP entry" }
        }
        val files = metadata.filterNot { it.name.endsWith('/') }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        seen.forEach { path ->
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in files) { "ZIP file/directory collision" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        require(metadata.count { it.name == "config.yaml" } == 1) { "ZIP must contain config.yaml at its root" }
        require(metadata.sumOf { it.size } <= MAX_EXPANDED_BYTES) { "ZIP expanded size limit exceeded" }
        ZipFile(archive, legacyCharset).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size == metadata.size) { "ZIP central directory does not match entries" }
            entries.zip(metadata).forEach { (entry, meta) ->
                currentCoroutineContext().ensureActive()
                require(entry.name == meta.name && entry.size == meta.size && entry.crc == meta.crc) { "ZIP entry metadata mismatch" }
                val file = privateResource(destination, entry.name.removeSuffix("/"))
                if (entry.isDirectory) {
                    check(file.isDirectory || file.mkdirs())
                } else {
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    val limit = if (entry.name == "config.yaml") MAX_PROFILE_BYTES.toLong() else MAX_ENTRY_BYTES
                    require(meta.size <= limit) { "ZIP entry exceeds $limit bytes" }
                    zip.getInputStream(entry).use { stream ->
                        val checked = CheckedInputStream(stream, CRC32())
                        val digest = copyResource(checked, file, limit)
                        require(digest.bytes == meta.size && checked.checksum.value == meta.crc) { "ZIP entry size/CRC mismatch" }
                    }
                }
            }
        }
        return File(destination, "config.yaml").inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
    }

    // ZipEntry does not expose Unix file types; read bounded central metadata before opening any entry.
    private fun metadata(archive: File): List<Entry> = RandomAccessFile(archive, "r").use { file ->
        require(file.length() >= 22) { "Truncated ZIP" }
        val tail = ByteArray(minOf(file.length(), 65557L).toInt())
        file.seek(file.length() - tail.size)
        file.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull {
            u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
        } ?: throw IllegalArgumentException("ZIP end record is missing")
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
            u16(tail, end + 8) == u16(tail, end + 10)) { "Multi-disk ZIP is not supported" }
        val count = u16(tail, end + 10)
        val size = u32(tail, end + 12)
        val start = u32(tail, end + 16)
        val endOffset = file.length() - tail.size + end
        require(count in 1..MAX_ENTRIES && start + size == endOffset) { "ZIP64, excessive entries or invalid central directory" }
        file.seek(start)
        val result = ArrayList<Entry>(count)
        repeat(count) {
            val header = ByteArray(46)
            require(file.filePointer + header.size <= endOffset) { "Truncated ZIP directory" }
            file.readFully(header)
            require(u32(header, 0) == 0x02014b50L) { "Invalid ZIP central entry" }
            val flags = u16(header, 8)
            val method = u16(header, 10)
            val nameSize = u16(header, 28)
            val extraSize = u16(header, 30)
            val commentSize = u16(header, 32)
            val attributes = (u32(header, 38) ushr 16).toInt() and 0xf000
            require(attributes in setOf(0, 0x4000, 0x8000)) { "ZIP symlink or special file is forbidden" }
            require(flags and 0x41 == 0 && method in setOf(0, 8) && u16(header, 34) == 0) { "Encrypted or unsupported ZIP entry" }
            require(nameSize in 1..1024 && file.filePointer + nameSize + extraSize + commentSize <= endOffset) { "Invalid ZIP entry name/extra data" }
            val compressed = u32(header, 20)
            val expanded = u32(header, 24)
            require(compressed <= MAX_ARCHIVE_BYTES && expanded <= MAX_ENTRY_BYTES && u32(header, 42) < start) { "ZIP entry size or offset is invalid" }
            val nameBytes = ByteArray(nameSize)
            file.readFully(nameBytes)
            val name = if (flags and 0x800 != 0) nameBytes.strictUtf8() else String(nameBytes, legacyCharset)
            require((attributes != 0x4000 || name.endsWith('/')) && (!name.endsWith('/') || expanded == 0L)) { "Invalid ZIP directory" }
            file.seek(file.filePointer + extraSize + commentSize)
            result += Entry(name, expanded, u32(header, 16))
        }
        require(file.filePointer == endOffset) { "Unexpected ZIP central data" }
        result
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private data class Entry(val name: String, val size: Long, val crc: Long)
}
