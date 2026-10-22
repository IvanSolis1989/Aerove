package dev.aerove.lite.scan

import kotlin.math.max
import kotlin.math.min

internal data class ScanRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val bottom get() = top + height
    val centerY get() = top + height / 2
}

internal data class ScanLayout(val window: ScanRect, val hint: ScanRect, val torch: ScanRect) {
    companion object {
        fun calculate(width: Float, height: Float, hintHeight: Float): ScanLayout {
            fun window(top: Float, bottom: Float, maxWidth: Float): ScanRect {
                val side = max(0f, minOf(400f, min(width, height) * 0.67f, bottom - top, maxWidth))
                val center = max(top + side / 2, min(height / 2, bottom - side / 2))
                return ScanRect((width - side) / 2, center - side / 2, side, side)
            }
            val torchTop = height - 48 - 64
            val below = window(56f, torchTop - 16 - 24 - hintHeight, width)
            val beside = window(16f, height - 16 - 24 - hintHeight, width - 2 * (24 + 64 + 16))
            if (beside.width > below.width) {
                val torch = ScanRect(width - 24 - 64, max(56f, beside.centerY - 32), 64f, 64f)
                val hintInset = if (torch.bottom <= beside.bottom + 24) 32f else 104f
                return ScanLayout(beside, ScanRect(hintInset, beside.bottom + 24, max(0f, width - 2 * hintInset), hintHeight), torch)
            }
            return ScanLayout(below, ScanRect(32f, below.bottom + 24, max(0f, width - 64), hintHeight), ScanRect((width - 64) / 2, torchTop, 64f, 64f))
        }
    }
}
