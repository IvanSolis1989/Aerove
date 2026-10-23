package dev.aerove.lite.images

import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class IconSvgDocument(val text: String, val embeddedImages: List<ByteArray>) {
    companion object {
        fun parse(bytes: ByteArray, checkpoint: () -> Unit = {}): IconSvgDocument {
            if (bytes.size > IconHttpSource.MAX_ICON_BYTES) throw IOException("SVG body exceeds limit")
            val charset = when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                .replace(Regex("^<\\?xml\\s[^?]*\\?>", RegexOption.IGNORE_CASE), "")
            if (Regex("<!DOCTYPE|<!ENTITY|@import|@font-face", RegexOption.IGNORE_CASE).containsMatchIn(text))
                throw IOException("External SVG declarations are forbidden")
            var elements = 0
            var nesting = 0
            var textLength = 0L
            var attributeLength = 0L
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw IOException("External SVG entity forbidden") }
            reader.contentHandler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    checkpoint()
                    if (++elements > 2048 || ++nesting > 32) throw IOException("SVG structure exceeds limit")
                    for (index in 0 until attributes.length) {
                        val value = attributes.getValue(index)
                        if (!value.startsWith("data:image/", true)) attributeLength += value.length
                        if (attributeLength > 2L * 1024 * 1024) throw IOException("SVG attribute budget exceeded")
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    checkpoint(); textLength += length
                    if (textLength > 65536) throw IOException("SVG text budget exceeded")
                }
                override fun endElement(uri: String?, localName: String?, qName: String?) { nesting-- }
            }
            reader.parse(InputSource(StringReader(text)))
            checkpoint()
            val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
                .newDocumentBuilder().apply { setEntityResolver { _, _ -> throw IOException("External SVG entity forbidden") } }
            val document = builder.parse(text.byteInputStream())
            val root = document.documentElement
            if (root.localName != "svg" || root.namespaceURI !in listOf(null, "", "http://www.w3.org/2000/svg"))
                throw IOException("Not an SVG document")
            val nodes = ArrayList<Element>()
            val images = ArrayList<ByteArray>()
            val ids = HashMap<String, Element>()
            val references = HashMap<Element, MutableList<String>>()
            var imageBytes = 0L
            fun referencesIn(node: Element, value: String) {
                if (value.contains('\\')) throw IOException("Escaped SVG resource reference refused")
                Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE).findAll(value).forEach { match ->
                    val reference = match.groupValues[1].trim().trim('\'', '"')
                    if (!reference.startsWith('#')) throw IOException("External SVG resource forbidden")
                    references.getOrPut(node) { ArrayList() }.add(reference.drop(1))
                }
            }
            fun visit(node: Node, depth: Int) {
                checkpoint()
                if (depth > 32) throw IOException("SVG nesting exceeds limit")
                if (node.nodeType == Node.PROCESSING_INSTRUCTION_NODE) throw IOException("SVG processing instructions forbidden")
                if (node is Element) {
                    if (nodes.size >= 2048) throw IOException("SVG element count exceeds limit")
                    nodes += node
                    if ((node.localName ?: node.nodeName) in setOf("script", "foreignObject", "animate", "animateMotion", "animateTransform", "set", "filter", "font"))
                        throw IOException("Unsupported active SVG element")
                    val id = node.getAttribute("id")
                    if (id.isNotEmpty() && ids.put(id, node) != null) throw IOException("Duplicate SVG id")
                    for (i in 0 until node.attributes.length) {
                        val attribute = node.attributes.item(i)
                        val name = attribute.localName ?: attribute.nodeName
                        val value = attribute.nodeValue
                        if (name.startsWith("on", true) || attribute.nodeName == "xml:base") throw IOException("Active SVG attribute forbidden")
                        if (name == "d" && value.length > 262144) throw IOException("SVG path exceeds limit")
                        if (name == "href") {
                            when {
                                value.startsWith('#') -> references.getOrPut(node) { ArrayList() }.add(value.drop(1))
                                node.localName == "image" && Regex("^data:image/(png|jpeg|gif|webp);base64,", RegexOption.IGNORE_CASE).containsMatchIn(value.take(32)) -> {
                                    val decoded = Base64.getMimeDecoder().decode(value.substringAfter(','))
                                    imageBytes += decoded.size
                                    if (imageBytes > 8L shl 20 || images.size >= 64) throw IOException("Embedded SVG images exceed limit")
                                    images += decoded
                                }
                                else -> throw IOException("External SVG href forbidden")
                            }
                        } else if (name != "xmlns" && attribute.prefix != "xmlns") referencesIn(node, value)
                    }
                    if (node.localName == "style") referencesIn(node, node.textContent)
                }
                for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i), depth + 1)
            }
            visit(document, 0)
            val visiting = HashSet<Element>()
            val costs = HashMap<Element, Int>()
            fun cost(node: Element): Int {
                checkpoint()
                costs[node]?.let { return it }
                if (!visiting.add(node)) throw IOException("Cyclic SVG reference")
                var total = 1L
                for (i in 0 until node.childNodes.length) (node.childNodes.item(i) as? Element)?.let { total += cost(it) }
                for (id in references[node].orEmpty()) total += cost(ids[id] ?: throw IOException("Missing SVG reference"))
                visiting.remove(node)
                if (total > 10000) throw IOException("Expanded SVG exceeds limit")
                return total.toInt().also { costs[node] = it }
            }
            cost(root)
            return IconSvgDocument(text, images)
        }
    }
}
