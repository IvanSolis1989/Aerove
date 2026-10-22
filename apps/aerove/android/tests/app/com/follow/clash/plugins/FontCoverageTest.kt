package com.follow.clash.plugins

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCoverageTest {
    @Test fun missingPrimaryLatinGlyphCannotBorrowABilingualFallback() {
        val primary = format12(FontCoverage.requiredCodePoints.filter { it != 'A'.code })
        val fallback = format12(FontCoverage.requiredCodePoints)
        assertFalse(FontCoverage.supports(primary))
        assertTrue(FontCoverage.supports(fallback))
        assertFalse(sameFontFace(File("system/fonts/Primary.ttf") to 0,
            File("system/fonts/BilingualFallback.ttf") to 0))
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
