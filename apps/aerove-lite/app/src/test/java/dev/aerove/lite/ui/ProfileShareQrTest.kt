package dev.aerove.lite.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.common.BitMatrix
import com.google.zxing.ResultMetadataType
import org.junit.Assert.*
import org.junit.Test

class ProfileShareQrTest {
    private fun read(code: BitMatrix): com.google.zxing.Result {
        val scale = 4
        val size = code.width * scale
        val pixels = IntArray(size * size) { i -> if (code[i % size / scale, i / size / scale]) 0xff000000.toInt() else -1 }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
    }

    @Test fun actualZxingEncoderAndReaderPreserveTheExactSubscriptionUrl() {
        listOf("https://example.test/subscription?token=a%2Fb%3D&x=1#part",
            "https://例子.test/订阅?名称=中文&emoji=🧭",
            "https://example.test/subscription?token=" + "aB39".repeat(300)).forEachIndexed { index, url ->
            val symbol = encodeProfileShareQr(url)
            assertNotNull("QR fixture $index must fit without rewriting its payload", symbol)
            assertEquals(url, read(requireNotNull(symbol)).text)
        }
    }

    @Test fun symbolHasFourWhiteQuietModulesAndMediumErrorCorrection() {
        val code = requireNotNull(encodeProfileShareQr("https://example.test/subscription"))
        assertTrue(code.width in 29..185); assertEquals(code.width, code.height)
        for (edge in 0 until 4) for (i in 0 until code.width) {
            assertFalse(code[edge, i]); assertFalse(code[i, edge])
            assertFalse(code[code.width - 1 - edge, i]); assertFalse(code[i, code.height - 1 - edge])
        }
        assertEquals("M", read(code).resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test fun oversizedAndBlankInputsProduceNoFakeOrTruncatedSymbol() {
        assertNull(encodeProfileShareQr("")); assertNull(encodeProfileShareQr("https://example.test/?token=" + "x".repeat(10000)))
    }
}
