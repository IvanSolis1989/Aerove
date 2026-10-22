package dev.aerove.lite.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QrDecoderTest {
    private val link = "https://example.com/profile.yaml?token=offline"
    private fun qr(text: String, edge: Int = 300): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, edge, edge, mapOf(EncodeHintType.MARGIN to 4))
        return IntArray(edge * edge) { if (matrix[it % edge, it / edge]) 0xff000000.toInt() else 0xffffffff.toInt() }
    }

    @Test fun decodesOfflinePixelsAndCameraLuma() {
        val pixels = qr(link)
        val decoder = QrDecoder()
        assertEquals(listOf(link), decoder.pixels(pixels, 300, 300))
        assertEquals(listOf(link), decoder.luminance(ByteArray(pixels.size) { pixels[it].toByte() }, 300, 300))
    }

    @Test fun decodesInvertedAndRotatedCodes() {
        val pixels = qr(link)
        val rotated = IntArray(pixels.size) { i -> pixels[(299 - i % 300) * 300 + i / 300] }
        assertEquals(listOf(link), QrDecoder().pixels(rotated, 300, 300))
        assertEquals(listOf(link), QrDecoder().pixels(pixels.map { it xor 0x00ffffff }.toIntArray(), 300, 300))
    }

    @Test fun findsValidProfileAmongMultipleCodes() {
        val left = qr("not a profile")
        val right = qr(link)
        val pixels = IntArray(600 * 300) { i -> if (i % 600 < 300) left[i / 600 * 300 + i % 600] else right[i / 600 * 300 + i % 600 - 300] }
        assertEquals(link, ProfileQr.first(QrDecoder().pixels(pixels, 600, 300)))
    }

    @Test fun blankImageContainsNoCode() {
        assertTrue(QrDecoder().pixels(IntArray(300 * 300) { -1 }, 300, 300).isEmpty())
    }
}
