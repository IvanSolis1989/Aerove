package dev.aerove.lite.presentation

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemFontCatalogTest {
    @Test fun namedSystemFamiliesAreTrimmedDeduplicatedAndSorted() {
        val xml = """<familyset><family name=" serif "/><family><font>fallback.ttf</font></family>
            <family name="Sans-Serif"/><family name="sans-serif"/><family name=".hidden"/>
            <family name=""/><family name="monospace"/></familyset>"""
        assertEquals(listOf("monospace", "Sans-Serif", "serif"), SystemFontCatalog.readFamilies(ByteArrayInputStream(xml.toByteArray())))
    }
    @Test fun emptyCatalogDoesNotInventAvailableFonts() {
        assertEquals(emptyList<String>(), SystemFontCatalog.readFamilies(ByteArrayInputStream("<familyset/>".toByteArray())))
    }
    @Test(expected = org.xml.sax.SAXException::class) fun malformedCatalogPropagatesForRetryUi() {
        SystemFontCatalog.readFamilies(ByteArrayInputStream("<familyset>".toByteArray()))
    }
}
