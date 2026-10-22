package dev.aerove.lite.presentation

import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SystemFontCatalog {
    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        File("/system/etc/fonts.xml").inputStream().use(::readFamilies)
    }

    internal fun readFamilies(input: InputStream): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
        }
        val families = factory.newDocumentBuilder().parse(input).getElementsByTagName("family")
        return (0 until families.length).mapNotNull { index ->
            families.item(index).attributes?.getNamedItem("name")?.nodeValue?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith('.') }
        }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
