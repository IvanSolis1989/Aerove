package dev.aerove.lite.presentation

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    @Test fun catalogUsesTheNormalPrimaryFaceAndNeverFallbackFamilies() {
        val xml = """<familyset><family name="Primary"><font weight="700">bold.ttf</font>
            <font weight="400" index="2">regular.ttc<axis tag="wght" stylevalue="400"/></font></family>
            <family><font>fallback.ttf</font></family><family name="Empty"/></familyset>"""
        assertEquals(listOf(SystemFontCatalog.Candidate("Primary", "regular.ttc", 2)),
            SystemFontCatalog.readCandidates(xml.byteInputStream()))
    }
    @Test fun unavailableSelectionFallsBackWithoutRewritingStoredName() {
        val stored = "Removed font"
        assertNull(SystemFontCatalog.resolve(stored, emptyList()))
        assertEquals(stored, SystemFontCatalog.resolve(stored, listOf(stored)))
    }
    @Test(expected = IllegalArgumentException::class) fun externalEntityCatalogIsRejected() {
        SystemFontCatalog.readFamilies("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><familyset/>".byteInputStream())
    }
    @Test fun primaryCmapMustCoverEveryEnglishAndChineseSampleWithoutFallback() {
        assertTrue(FontCoverage.supports(format12(FontCoverage.requiredCodePoints)))
        assertFalse(FontCoverage.supports(format12((0x20..0x7e).toList())))
        assertFalse(FontCoverage.supports(format12(FontCoverage.requiredCodePoints.filter { it != '中'.code })))
        assertFalse(FontCoverage.supports(ByteBuffer.wrap(byteArrayOf(0, 1, 2))))
    }
    @Test fun bmpCmapAndCollectionFaceIndexAreCheckedIndividually() {
        val codes = FontCoverage.requiredCodePoints.distinct().sorted() + 0xffff
        val count = codes.size
        val table = ByteBuffer.allocate(16 + count * 8).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(4); putShort(capacity().toShort()); putShort(0); putShort((count * 2).toShort())
            putShort(0); putShort(0); putShort(0)
            codes.forEach { putShort(it.toShort()) }; putShort(0)
            codes.forEach { putShort(it.toShort()) }
            codes.forEach { putShort((1 - it).toShort()) }
            repeat(count) { putShort(0) }
        }
        assertTrue(FontCoverage.supports(wrapCmap(table.array(), 1)))
        val latin = format12((0x20..0x7e).toList()).array()
        val bilingual = format12(FontCoverage.requiredCodePoints).array()
        val second = 20 + latin.size
        val collection = ByteBuffer.allocate(second + bilingual.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x74746366); putInt(0x00010000); putInt(2); putInt(20); putInt(second)
            put(latin); put(bilingual)
            putInt(40, 48); putInt(second + 20, second + 28)
        }
        assertFalse(FontCoverage.supports(collection, 0))
        assertTrue(FontCoverage.supports(collection, 1))
        assertFalse(FontCoverage.supports(collection, 2))
    }
    @Test fun missingAsciiPrimaryCannotBorrowBilingualFallback() {
        val directory = java.nio.file.Files.createTempDirectory("aerove-font-primary-").toFile()
        val primary = File(directory, "primary.ttf")
        val fallback = File(directory, "fallback.ttf")
        try {
            primary.writeBytes(format12(FontCoverage.requiredCodePoints.filter { it != 'A'.code }).array())
            fallback.writeBytes(format12(FontCoverage.requiredCodePoints).array())
            assertFalse(SystemFontCatalog.covers(primary, 0))
            assertTrue(SystemFontCatalog.covers(fallback, 0))
            val requested = SystemFontCatalog.Candidate("Primary without A", primary.name, 0)
            assertFalse(SystemFontCatalog.matchesDeclaredPrimary(requested, directory, fallback, 0))
        } finally {
            primary.delete(); fallback.delete(); directory.delete()
        }
    }
    @Test fun declaredPrimaryRequiresExactCanonicalFileAndCollectionIndex() {
        val directory = java.nio.file.Files.createTempDirectory("aerove-font-index-").toFile()
        val primary = File(directory, "regular.ttc")
        try {
            val latin = format12((0x20..0x7e).toList()).array()
            val bilingual = format12(FontCoverage.requiredCodePoints).array()
            val second = 20 + latin.size
            val collection = ByteBuffer.allocate(second + bilingual.size).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0x74746366); putInt(0x00010000); putInt(2); putInt(20); putInt(second)
                put(latin); put(bilingual); putInt(40, 48); putInt(second + 20, second + 28)
            }
            primary.writeBytes(collection.array())
            assertFalse(SystemFontCatalog.covers(primary, 0))
            assertTrue(SystemFontCatalog.covers(primary, 1))
            val requested = SystemFontCatalog.Candidate("Primary", primary.name, 1)
            assertTrue(SystemFontCatalog.matchesDeclaredPrimary(requested, directory, File(directory, "./regular.ttc"), 1))
            assertFalse(SystemFontCatalog.matchesDeclaredPrimary(requested, directory, primary, 2))
            assertFalse(SystemFontCatalog.matchesDeclaredPrimary(requested, directory, File(directory, "replacement.ttc"), 1))
            assertFalse(SystemFontCatalog.matchesDeclaredPrimary(requested, directory, primary, 0))
        } finally {
            primary.delete(); directory.delete()
        }
    }

    private fun wrapCmap(table: ByteArray, encoding: Int): ByteBuffer =
        ByteBuffer.allocate(40 + table.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x00010000); putShort(1); putShort(0); putShort(0); putShort(0)
            putInt(0x636d6170); putInt(0); putInt(28); putInt(12 + table.size)
            putShort(0); putShort(1); putShort(3); putShort(encoding.toShort()); putInt(12)
            put(table); rewind()
        }

    private fun format12(codes: List<Int>): ByteBuffer {
        val unique = codes.distinct().sorted()
        val length = 16 + unique.size * 12
        return ByteBuffer.allocate(40 + length).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x00010000); putShort(1); putShort(0); putShort(0); putShort(0)
            putInt(0x636d6170); putInt(0); putInt(28); putInt(12 + length)
            putShort(0); putShort(1); putShort(3); putShort(10); putInt(12)
            putShort(12); putShort(0); putInt(length); putInt(0); putInt(unique.size)
            unique.forEachIndexed { index, code -> putInt(code); putInt(code); putInt(index + 1) }
            rewind()
        }
    }
}
