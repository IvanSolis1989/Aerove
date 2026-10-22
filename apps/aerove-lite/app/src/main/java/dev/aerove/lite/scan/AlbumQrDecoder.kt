package dev.aerove.lite.scan

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import java.io.IOException

internal class AlbumQrDecoder(private val resolver: ContentResolver) {
    fun decode(uri: Uri): List<String> {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val data = input.readNBytes(MAX_BYTES + 1)
            if (data.size > MAX_BYTES) throw IOException("Image is too large")
            data
        } ?: throw IOException("Image is unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image")
        var sample = 1
        fun sampled(dimension: Int) = (dimension.toLong() + sample - 1) / sample
        while (sampled(bounds.outWidth) * sampled(bounds.outHeight) > MAX_PIXELS ||
            maxOf(sampled(bounds.outWidth), sampled(bounds.outHeight)) > MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Invalid image")
        return try {
            if (bitmap.width.toLong() * bitmap.height > MAX_PIXELS || maxOf(bitmap.width, bitmap.height) > MAX_EDGE) {
                throw IOException("Decoded image is too large")
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QrDecoder().pixels(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_PIXELS = 4L * 1024 * 1024
        const val MAX_EDGE = 2048
    }
}
