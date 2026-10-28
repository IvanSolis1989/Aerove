package dev.aerove.lite.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot

internal data class CountryFlagInk(val bitmap: Bitmap)

internal object CountryFlagInkCache {
    private val entries = LinkedHashMap<Triple<String, Int, String>, CountryFlagInk?>()
    private var cachedBytes = 0

    suspend fun load(flag: String, side: Int, fontConfiguration: String): CountryFlagInk? = withContext(Dispatchers.Default) {
        synchronized(entries) {
            val key = Triple(flag, side, fontConfiguration)
            if (entries.containsKey(key)) {
                val value = entries.remove(key)
                entries[key] = value
                return@synchronized value
            }
            val first = measure(flag, side.toFloat())
            val result = first?.let {
                val diagonal = hypot(it.width.toFloat(), it.height.toFloat()).coerceAtLeast(1f)
                val textSize = side * side * 0.76f / diagonal
                it.recycle()
                measure(flag, textSize)?.let { ink -> CountryFlagInk(ink) }
            }
            entries[key] = result
            cachedBytes += result?.bitmap?.allocationByteCount ?: 0
            while (entries.size > 16 || cachedBytes > 2 * 1024 * 1024) {
                cachedBytes -= entries.remove(entries.keys.first())?.bitmap?.allocationByteCount ?: 0
            }
            result
        }
    }

    private fun measure(flag: String, textSize: Float): Bitmap? {
        if (!textSize.isFinite() || textSize <= 0f) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize }
        val padding = ceil(textSize * 2).toInt()
        val width = ceil(paint.measureText(flag)).toInt() + padding * 2
        val height = ceil(paint.fontMetrics.bottom - paint.fontMetrics.top).toInt() + padding * 2
        if (width !in 1..1536 || height !in 1..1536) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        try {
            val baseline = padding - paint.fontMetrics.top
            Canvas(bitmap).drawText(flag, padding.toFloat(), baseline, paint)
            val row = IntArray(width)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in row.indices) if (row[x] ushr 24 != 0) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            if (left <= 0 || top <= 0 || right < left || bottom < top || right >= width - 1 || bottom >= height - 1) return null
            return Bitmap.createBitmap(bitmap, left, top, right + 1 - left, bottom + 1 - top)
        } finally {
            bitmap.recycle()
        }
    }
}
