package dev.aerove.lite.scan

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

internal class QrDecoder {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun luminance(bytes: ByteArray, width: Int, height: Int): List<String> =
        decode(PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false))

    fun pixels(pixels: IntArray, width: Int, height: Int): List<String> =
        decode(RGBLuminanceSource(width, height, pixels))

    private fun decode(source: LuminanceSource): List<String> {
        for (candidate in listOf(source, source.invert())) {
            try {
                val values = QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(candidate)), hints)
                    .map { it.text }.distinct()
                if (values.isNotEmpty()) return values
            } catch (_: ReaderException) {
                // A frame without a decodable QR is normal camera input.
            }
        }
        return emptyList()
    }
}
