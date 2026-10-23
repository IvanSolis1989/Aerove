package dev.aerove.lite.presentation

import android.graphics.fonts.SystemFonts
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object SystemFontCatalog {
    private val mutex = Mutex()
    private var cacheKey: String? = null
    private var cached = emptyList<String>()
    private val configurations = listOf(
        "/system/etc/fonts.xml", "/system/etc/font_fallback.xml",
        "/product/etc/fonts_customization.xml", "/system_ext/etc/fonts_customization.xml",
        "/vendor/etc/fonts.xml", "/data/fonts/config/config.xml",
    )

    fun resolve(selected: String?, eligible: List<String>): String? = selected?.takeIf { it in eligible }

    suspend fun load(configurationKey: String = ""): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val available = SystemFonts.getAvailableFonts().take(1024)
                .mapNotNull { font -> font.file?.let { InstalledFace(it.canonicalFile, font.ttcIndex) } }
                .distinct().sortedWith(compareBy({ it.file.path }, { it.index }))
            val configs = configurations.map(::File).filter { it.isFile && it.length() in 1..MAX_XML_BYTES.toLong() }
            val key = configurationKey + configs.joinToString { "${it.path}:${it.lastModified()}:${it.length()}" } +
                available.joinToString { "${it.file.path}:${it.index}:${it.file.lastModified()}:${it.file.length()}" }
            if (key == cacheKey) return@withLock cached
            val candidates = configs.flatMap { file ->
                currentCoroutineContext().ensureActive()
                val root = file.parentFile?.parentFile ?: return@flatMap emptyList()
                val directory = File(root, "fonts")
                file.inputStream().use(::readCandidates).map { DeclaredCandidate(it, directory) }
            }.take(1024).associateBy { it.candidate.name.lowercase(Locale.ROOT) }.values
            val accepted = ArrayList<String>()
            val coverage = HashMap<InstalledFace, Boolean>()
            for (declared in candidates) {
                currentCoroutineContext().ensureActive()
                val candidate = declared.candidate
                val paint = Paint().apply { typeface = Typeface.create(candidate.name, Typeface.NORMAL) }
                val shaped = TextRunShaper.shapeTextRun("A", 0, 1, 0, 1, 0f, 0f, false, paint)
                if (shaped.glyphCount() == 0) continue
                val primary = shaped.getFont(0)
                val face = primary.file?.let { InstalledFace(it.canonicalFile, primary.ttcIndex) } ?: continue
                if (face !in available) continue
                if (!matchesDeclaredPrimary(candidate, declared.directory, face.file, face.index)) continue
                val supports = coverage.getOrPut(face) { covers(face.file, face.index) }
                if (supports) accepted += candidate.name
            }
            currentCoroutineContext().ensureActive()
            accepted.distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
                .also { cached = it; cacheKey = key }
        }
    }

    internal fun covers(file: File, index: Int): Boolean = runCatching {
        require(file.length() in 12..64L * 1024 * 1024)
        file.inputStream().channel.use { channel ->
            FontCoverage.supports(channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size()), index)
        }
    }.getOrDefault(false)

    internal data class Candidate(val name: String, val file: String, val index: Int)
    private data class DeclaredCandidate(val candidate: Candidate, val directory: File)
    private data class InstalledFace(val file: File, val index: Int)

    internal fun matchesDeclaredPrimary(candidate: Candidate, directory: File, shapedFile: File, shapedIndex: Int): Boolean =
        runCatching {
            val declared = File(candidate.file).let { if (it.isAbsolute) it else File(directory, candidate.file) }
            candidate.index == shapedIndex && declared.canonicalFile == shapedFile.canonicalFile
        }.getOrDefault(false)

    internal fun readFamilies(input: InputStream): List<String> = document(input).let { document ->
        val families = document.getElementsByTagName("family")
        (0 until families.length).mapNotNull { i -> validName(families.item(i).attributes?.getNamedItem("name")?.nodeValue) }
            .distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    internal fun readCandidates(input: InputStream): List<Candidate> {
        val families = document(input).getElementsByTagName("family")
        return (0 until families.length.coerceAtMost(1024)).mapNotNull { i ->
            val family = families.item(i)
            val name = validName(family.attributes?.getNamedItem("name")?.nodeValue) ?: return@mapNotNull null
            val fonts = (0 until family.childNodes.length).map { family.childNodes.item(it) }
                .filter { it.nodeName == "font" }
            val primary = fonts.minByOrNull { font ->
                val weight = font.attributes?.getNamedItem("weight")?.nodeValue?.toIntOrNull() ?: 400
                val italic = font.attributes?.getNamedItem("style")?.nodeValue == "italic"
                kotlin.math.abs(weight - 400) / 100 + if (italic) 2 else 0
            } ?: return@mapNotNull null
            val file = primary.childNodes.let { children ->
                (0 until children.length).map { children.item(it) }.filter { it.nodeType == org.w3c.dom.Node.TEXT_NODE }
                    .joinToString("") { it.nodeValue }.trim()
            }
            val index = primary.attributes?.getNamedItem("index")?.nodeValue?.toIntOrNull() ?: 0
            if (file.isEmpty() || file.contains("..") || index !in 0..255) null else Candidate(name, file, index)
        }
    }

    private fun validName(name: String?) = name?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith('.') && it.length <= 256 }

    private fun document(input: InputStream): org.w3c.dom.Document {
        val bytes = input.readNBytes(MAX_XML_BYTES + 1)
        require(bytes.size <= MAX_XML_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        return DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            .newDocumentBuilder().parse(bytes.inputStream())
    }

    private const val MAX_XML_BYTES = 2 * 1024 * 1024
}

internal object FontCoverage {
    const val CHINESE_SAMPLE = "的一是不了人在有我他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与高意进把法此实回二理美点月明其种声全工己话儿者向情部正名定机力化题问数设网连"
    val requiredCodePoints = (0x20..0x7e).toList() + CHINESE_SAMPLE.map(Char::code)

    fun supports(buffer: ByteBuffer, faceIndex: Int = 0): Boolean = runCatching {
        val data = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        fun u16(at: Int) = data.getShort(at).toInt() and 0xffff
        fun u32(at: Int): Int {
            val value = data.getInt(at)
            require(value >= 0)
            return value
        }
        val face = if (data.getInt(0) == 0x74746366) {
            require(faceIndex >= 0 && faceIndex < u32(8))
            u32(12 + faceIndex * 4)
        } else {
            require(faceIndex == 0)
            0
        }
        val tables = u16(face + 4)
        val cmapRecord = (0 until tables).map { face + 12 + it * 16 }
            .firstOrNull { data.getInt(it) == 0x636d6170 } ?: return false
        val cmap = u32(cmapRecord + 8)
        val cmapEnd = cmap.toLong() + u32(cmapRecord + 12)
        require(cmapEnd <= data.limit())
        val subtables = (0 until u16(cmap + 2)).mapNotNull { index ->
            val record = cmap + 4 + index * 8
            val platform = u16(record)
            val encoding = u16(record + 2)
            if (platform != 0 && !(platform == 3 && encoding in listOf(1, 10))) return@mapNotNull null
            val table = cmap + u32(record + 4)
            require(table >= cmap && table + 2 <= cmapEnd)
            val format = u16(table)
            if (format != 4 && format != 12) return@mapNotNull null
            val length = if (format == 4) u16(table + 2) else u32(table + 4)
            require(table.toLong() + length <= cmapEnd)
            if (format == 4) {
                val segments = u16(table + 6)
                require(segments > 0 && segments % 2 == 0 && 16L + segments * 4L <= length)
            } else {
                require(16L + u32(table + 12) * 12L <= length)
            }
            table to format
        }
        fun glyph(table: Int, format: Int, code: Int): Int {
            if (format == 12) {
                var low = 0
                var high = u32(table + 12) - 1
                while (low <= high) {
                    val mid = (low + high).ushr(1)
                    val group = table + 16 + mid * 12
                    val start = u32(group)
                    val end = u32(group + 4)
                    when {
                        code < start -> high = mid - 1
                        code > end -> low = mid + 1
                        else -> return u32(group + 8) + code - start
                    }
                }
                return 0
            }
            val count = u16(table + 6) / 2
            val ends = table + 14
            val starts = ends + count * 2 + 2
            val deltas = starts + count * 2
            val offsets = deltas + count * 2
            for (index in 0 until count) {
                if (code > u16(ends + index * 2)) continue
                if (code < u16(starts + index * 2)) return 0
                val delta = u16(deltas + index * 2)
                val offset = u16(offsets + index * 2)
                if (offset == 0) return (code + delta) and 0xffff
                val address = offsets + index * 2 + offset + (code - u16(starts + index * 2)) * 2
                require(address + 2 <= table + u16(table + 2))
                val value = u16(address)
                return if (value == 0) 0 else (value + delta) and 0xffff
            }
            return 0
        }
        requiredCodePoints.all { code -> subtables.any { (table, format) -> glyph(table, format, code) != 0 } }
    }.getOrDefault(false)
}
