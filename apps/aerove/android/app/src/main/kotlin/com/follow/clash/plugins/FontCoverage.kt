package com.follow.clash.plugins

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object FontCoverage {
    const val CHINESE_SAMPLE = "的一是不了人在有我他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与高意进把法此实回二理美点月明其种声全工己话儿者向情部正名定机力化题问数设网连"
    val requiredCodePoints = (0x20..0x7e).toList() + CHINESE_SAMPLE.map(Char::code)

    fun supports(buffer: ByteBuffer, faceIndex: Int = 0): Boolean = runCatching {
        val data = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        fun u16(at: Int) = data.getShort(at).toInt() and 0xffff
        fun u32(at: Int): Int {
            val value = data.getInt(at)
            require(value >= 0)
            return value
        }
        val face = if (data.getInt(0) == 0x74746366) {
            require(faceIndex >= 0 && faceIndex < u32(8))
            u32(12 + faceIndex * 4)
        } else {
            require(faceIndex == 0)
            0
        }
        val tables = u16(face + 4)
        val cmapRecord = (0 until tables).map { face + 12 + it * 16 }
            .firstOrNull { data.getInt(it) == 0x636d6170 } ?: return false
        val cmap = u32(cmapRecord + 8)
        val cmapEnd = cmap.toLong() + u32(cmapRecord + 12)
        require(cmapEnd <= data.limit())
        val subtables = (0 until u16(cmap + 2)).mapNotNull { index ->
            val record = cmap + 4 + index * 8
            val platform = u16(record)
            val encoding = u16(record + 2)
            if (platform != 0 && !(platform == 3 && encoding in listOf(1, 10))) return@mapNotNull null
            val table = cmap + u32(record + 4)
            require(table >= cmap && table + 2 <= cmapEnd)
            val format = u16(table)
            if (format != 4 && format != 12) return@mapNotNull null
            val length = if (format == 4) u16(table + 2) else u32(table + 4)
            require(table.toLong() + length <= cmapEnd)
            if (format == 4) {
                val segments = u16(table + 6)
                require(segments > 0 && segments % 2 == 0 && 16L + segments * 4L <= length)
            } else {
                require(16L + u32(table + 12) * 12L <= length)
            }
            table to format
        }
        fun glyph(table: Int, format: Int, code: Int): Int {
            if (format == 12) {
                var low = 0
                var high = u32(table + 12) - 1
                while (low <= high) {
                    val mid = (low + high).ushr(1)
                    val group = table + 16 + mid * 12
                    val start = u32(group)
                    val end = u32(group + 4)
                    when {
                        code < start -> high = mid - 1
                        code > end -> low = mid + 1
                        else -> return u32(group + 8) + code - start
                    }
                }
                return 0
            }
            val count = u16(table + 6) / 2
            val ends = table + 14
            val starts = ends + count * 2 + 2
            val deltas = starts + count * 2
            val offsets = deltas + count * 2
            for (index in 0 until count) {
                if (code > u16(ends + index * 2)) continue
                if (code < u16(starts + index * 2)) return 0
                val delta = u16(deltas + index * 2)
                val offset = u16(offsets + index * 2)
                if (offset == 0) return (code + delta) and 0xffff
                val address = offsets + index * 2 + offset + (code - u16(starts + index * 2)) * 2
                require(address + 2 <= table + u16(table + 2))
                val value = u16(address)
                return if (value == 0) 0 else (value + delta) and 0xffff
            }
            return 0
        }
        requiredCodePoints.all { code -> subtables.any { (table, format) -> glyph(table, format, code) != 0 } }
    }.getOrDefault(false)
}
