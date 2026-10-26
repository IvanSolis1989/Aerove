package dev.aerove.lite.data

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingEndEvent
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceEndEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.parser.Parser
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader

/** Native `MAX_INPUT_BYTES`: official MRS compressed input and text or YAML rule input. */
internal const val MAX_RULE_INPUT_BYTES = 64L * 1024 * 1024
/** Native `MAX_DUMP_TEXT_BYTES`: preview, export and materialized rule text. */
internal const val MAX_RULE_TEXT_BYTES = 64L * 1024 * 1024
/** Native `MAX_ENTRIES`: list items in one YAML rule payload. */
internal const val MAX_RULE_PAYLOAD_ENTRIES = 250_000
/** Native `MAX_ENTRY_BYTES`: one trimmed rule entry. */
internal const val MAX_RULE_ENTRY_BYTES = 2048
internal const val MAX_RULE_YAML_VALUES = MAX_RULE_PAYLOAD_ENTRIES + 2
/** SnakeYAML holds a token as int code points and a scalar as one builder; both, and the text between events, stay under this. */
internal const val MAX_RULE_YAML_TOKEN_CHARS = 1024 * 1024
/** Read-only rule previews open up to the Native text budget, a device-protection limit the user kept. */
internal const val MAX_RULE_PREVIEW_BYTES = MAX_RULE_TEXT_BYTES.toInt()

internal fun File.ruleTextReader(limit: Long): Reader {
    if (length() > limit) throw IOException("Input exceeds $limit bytes")
    val input = BoundedRuleInput(FileInputStream(this), limit)
    try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = PushbackReader(InputStreamReader(input, decoder), 1)
        val first = reader.read()
        if (first >= 0 && first != 0xFEFF) reader.unread(first)
        return reader
    } catch (failure: Throwable) {
        input.close()
        throw failure
    }
}

internal class RuleLineScan(val count: Int, private val overlong: Int) {
    fun requireEntryBound(path: String): Int {
        if (overlong >= 0) throw ConfigValidationException(listOf(ConfigIssue("$path[$overlong]", "Rule entry exceeds $MAX_RULE_ENTRY_BYTES UTF-8 bytes")))
        return count
    }
}

/** The `lineSequence().map(String::trim)` rule lines, each handed over once; nothing past one entry bound is kept. */
internal fun File.forEachRuleLine(limit: Long, action: (Int, String) -> Unit): RuleLineScan =
    RuleLineScanner(MAX_RULE_ENTRY_BYTES, action).let { scanner -> ruleTextReader(limit).use(scanner::scan) }

internal fun File.countRuleLines(limit: Long): Int = RuleLineScanner(-1, null).let { scanner -> ruleTextReader(limit).use(scanner::scan).count }

private class RuleLineScanner(private val maxBytes: Int, private val action: ((Int, String) -> Unit)?) {
    private enum class State { Lead, Slash, Entry, Skip, Overlong }
    private var state = State.Lead
    private val entry = StringBuilder()
    private val gap = StringBuilder()
    private var bytes = 0
    private var gapBytes = 0
    private var afterCr = false
    private var index = 0
    private var overlong = -1

    fun scan(reader: Reader): RuleLineScan {
        val buffer = CharArray(16 * 1024)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (position in 0 until count) accept(buffer[position])
        }
        endLine()
        return RuleLineScan(index, overlong)
    }

    private fun accept(c: Char) {
        if (afterCr) { afterCr = false; if (c == '\n') return }
        if (c == '\n' || c == '\r') { afterCr = c == '\r'; endLine(); return }
        when (state) {
            State.Lead -> if (!c.isWhitespace()) when (c) {
                '#' -> state = State.Skip
                '/' -> state = State.Slash
                else -> { state = State.Entry; append(c) }
            }
            State.Slash -> if (c == '/') state = State.Skip else { state = State.Entry; append('/'); append(c) }
            State.Entry -> append(c)
            State.Skip, State.Overlong -> Unit
        }
    }

    private fun append(c: Char) {
        if (action == null) return
        val size = when { c.code < 0x80 -> 1; c.code < 0x800 || c.isSurrogate() -> 2; else -> 3 }
        if (c.isWhitespace()) {
            gapBytes += size
            if (bytes + gapBytes <= maxBytes) gap.append(c)
            return
        }
        bytes += gapBytes + size
        gapBytes = 0
        if (bytes > maxBytes) { state = State.Overlong; return }
        entry.append(gap).append(c)
        gap.setLength(0)
    }

    private fun endLine() {
        when (state) {
            State.Slash -> action?.invoke(index++, "/") ?: index++
            State.Entry -> action?.invoke(index++, entry.toString()) ?: index++
            State.Overlong -> { if (overlong < 0) overlong = index; index++ }
            State.Lead, State.Skip -> Unit
        }
        state = State.Lead
        entry.setLength(0)
        gap.setLength(0)
        bytes = 0
        gapBytes = 0
    }
}

internal fun File.readRuleYaml(limit: Long): MutableMap<String, Any?> {
    admitRuleYaml(limit)
    return ruleTextReader(limit).use { ConfigYaml.parseRule(BoundedYamlStream(it), limit.toInt(), MAX_RULE_YAML_VALUES) }
}

/** Event pass before any node exists: the bound, encoding and size errors are decided here; anything else by the full loader. */
private fun File.admitRuleYaml(limit: Long) = ruleTextReader(limit).use { decoded ->
    val source = DrainingReader(decoded)
    val stream = BoundedYamlStream(source)
    val options = ConfigYaml.options(limit.toInt())
    val budget = YamlValueBudget(MAX_RULE_YAML_VALUES, options, stopAtEarlierError = true)
    val parser = ParserImpl(stream, options)
    val rejected = try {
        while (!budget.admitted) {
            val event = parser.event ?: break
            stream.eventSeen()
            budget.observe(event)
        }
        null
    } catch (bound: ConfigValidationException) {
        bound
    } catch (syntax: RuntimeException) {
        source.failure?.let { throw it }
        null
    }
    source.drain()
    rejected?.let { throw it }
}

private class DrainingReader(private val input: Reader) : Reader() {
    var failure: IOException? = null
        private set
    override fun read(buffer: CharArray, offset: Int, length: Int): Int = try {
        input.read(buffer, offset, length)
    } catch (error: IOException) {
        failure = error
        throw error
    }
    fun drain() {
        failure?.let { throw it }
        val buffer = CharArray(16 * 1024)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }
    override fun close() = input.close()
}

/**
 * Marks without the code point window, so composed nodes never pin the document. SnakeYAML 2.4 consumes a comment's `#`
 * alone and nowhere inside a scalar, so a comment restarts the count like an event does; a scalar never spans either.
 */
internal class BoundedYamlStream(reader: Reader) : StreamReader(reader) {
    private var consumed = 0L
    fun eventSeen() { consumed = 0 }
    override fun getMark(): Mark = super.getMark().let { Mark(it.name, it.index, it.line, it.column, NO_CODE_POINTS, 0) }
    override fun peek(index: Int): Int = if (index < MAX_RULE_YAML_TOKEN_CHARS) super.peek(index) else tooLong()
    override fun prefix(length: Int): String = if (length <= MAX_RULE_YAML_TOKEN_CHARS) super.prefix(length) else tooLong()
    override fun forward(length: Int) {
        if (length == 1 && super.peek() == '#'.code) consumed = 0 else consume(length)
        super.forward(length)
    }
    override fun prefixForward(length: Int): String { consume(length); return super.prefixForward(length) }
    private fun consume(length: Int) { consumed += length; if (consumed > MAX_RULE_YAML_TOKEN_CHARS) tooLong() }
    private fun tooLong(): Nothing = throw ConfigValidationException(listOf(ConfigIssue("$",
        "YAML scalar, comment or gap exceeds $MAX_RULE_YAML_TOKEN_CHARS characters at line ${line + 1}")))
    private companion object { val NO_CODE_POINTS = IntArray(0) }
}

internal fun YamlValueBudget.guard(parser: Parser, stream: BoundedYamlStream): Parser = object : Parser {
    override fun checkEvent(choice: Event.ID): Boolean = parser.checkEvent(choice)
    override fun peekEvent(): Event? = parser.peekEvent()
    override fun getEvent(): Event? = parser.event.also { stream.eventSeen(); if (it != null) observe(it) }
}

/** Expanded values in document order, as ConfigYaml's copy counts them; a merge key counts what it names. */
internal class YamlValueBudget(private val maxValues: Int, options: LoaderOptions, private val stopAtEarlierError: Boolean) {
    var admitted = false
        private set
    private val maxCollectionAliases = options.maxAliasesForCollections
    private val maxDepth = options.nestingDepthLimit
    private var values = 0L
    private var collectionAliases = 0
    private var documents = 0
    private val frames = ArrayList<Frame>()
    private val anchors = HashMap<String, Anchored>()

    private class Anchored(val collection: Boolean) { var size = 1L; var open = collection }
    private class Frame(val path: String, val mapping: Boolean, val start: Long, val anchor: Anchored?) {
        var index = 0
        var key: String? = null
    }

    fun observe(event: Event) {
        if (admitted) return
        when (event) {
            is DocumentStartEvent -> if (++documents > 1) earlierError()
            is ScalarEvent -> {
                event.anchor?.let { anchors[it] = Anchored(false) }
                node(1, counted = !atKey(), key = event.value)
            }
            is AliasEvent -> alias(event.anchor)
            is SequenceStartEvent -> start(event.anchor, mapping = false)
            is MappingStartEvent -> start(event.anchor, mapping = true)
            is SequenceEndEvent, is MappingEndEvent -> end()
            else -> Unit
        }
    }

    private fun atKey(): Boolean = frames.lastOrNull()?.let { it.mapping && it.key == null } == true

    private fun path(): String = frames.lastOrNull()?.let { top ->
        if (!top.mapping) "${top.path}[${top.index}]" else top.key?.let { "${top.path}.$it" } ?: top.path
    } ?: "$"

    private fun count(amount: Long) {
        values += amount
        if (values > maxValues) throw ConfigValidationException(listOf(ConfigIssue(path(), "Expanded configuration exceeds $maxValues values")))
    }

    private fun node(amount: Long, counted: Boolean, key: String?) {
        if (counted) count(amount)
        completed(key)
    }

    private fun completed(key: String?) {
        val top = frames.lastOrNull() ?: return
        if (!top.mapping) top.index++ else top.key = if (top.key == null) key ?: "?" else null
    }

    private fun alias(name: String) {
        val target = anchors[name]
        if (target == null) earlierError()
        else if (target.collection && ++collectionAliases > maxCollectionAliases) earlierError()
        if (admitted) return
        node(if (target == null || target.open) 1 else target.size, counted = !atKey(), key = "*$name")
    }

    private fun start(anchor: String?, mapping: Boolean) {
        if (frames.size > maxDepth) earlierError()
        if (admitted) return
        val path = path()
        count(1)
        val anchored = anchor?.let { Anchored(true).also { state -> anchors[it] = state } }
        frames += Frame(path, mapping, values - 1, anchored)
    }

    private fun end() {
        val frame = frames.removeAt(frames.lastIndex)
        frame.anchor?.let { it.size = values - frame.start; it.open = false }
        completed(null)
    }

    private fun earlierError() { if (stopAtEarlierError) admitted = true }
}

internal fun File.readRuleText(limit: Long): String = readBoundedFile(limit).strictUtf8()

/** UTF-8 bytes the editor would receive: the file without a leading BOM. */
internal fun File.editorTextBytes(): Long {
    val size = length()
    if (size < 3) return size
    val head = ByteArray(3)
    val read = FileInputStream(this).use { input -> var filled = 0; while (filled < 3) { val n = input.read(head, filled, 3 - filled); if (n < 0) break; filled += n }; filled }
    val bom = read == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
    return if (bom) size - 3 else size
}

/** Text a rule preview can open, or null when it would refuse it; a larger file is still decoded to the end for its errors. */
internal fun File.readEditorRuleText(limit: Long): String? {
    if (length() > limit) throw IOException("Input exceeds $limit bytes")
    if (editorTextBytes() <= MAX_RULE_PREVIEW_BYTES) return readRuleText(limit)
    ruleTextReader(limit).use { reader -> val buffer = CharArray(16 * 1024); while (reader.read(buffer) >= 0) Unit }
    return null
}

internal fun File.readBoundedFile(limit: Long): ByteArray = FileInputStream(this).use { input ->
    val expected = input.channel.size()
    if (expected > limit) throw IOException("Input exceeds $limit bytes")
    var bytes = ByteArray(expected.toInt())
    var size = 0
    while (true) {
        if (size == bytes.size) {
            val next = input.read()
            if (next < 0) break
            if (size + 1L > limit) throw IOException("Input exceeds $limit bytes")
            bytes = bytes.copyOf(minOf(limit, maxOf(bytes.size * 2L, 8192L)).toInt())
            bytes[size++] = next.toByte()
            continue
        }
        val count = input.read(bytes, size, bytes.size - size)
        if (count < 0) break
        size += count
    }
    if (size == bytes.size) bytes else bytes.copyOf(size)
}

private class BoundedRuleInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) charge(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) charge(it.toLong()) }
    override fun skip(count: Long): Long = super.skip(count).also(::charge)
    private fun charge(bytes: Long) {
        count += bytes
        if (count > limit) throw IOException("Input exceeds $limit bytes")
    }
}
