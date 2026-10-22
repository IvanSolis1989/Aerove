package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal val FullAuroraColors = listOf(0xff65bdd2L, 0xffc589caL, 0xff75cca8L)
internal const val FullAuroraSeed = 271828L

internal class FullSeed(seed: Long) {
    private var state = (seed and 0xffffffffL).let { if (it == 0L) 0x6d2b79f5L else it }
    fun next(): Double {
        state = (state xor (state shl 13)) and 0xffffffffL
        state = (state xor (state shr 17)) and 0xffffffffL
        state = (state xor (state shl 5)) and 0xffffffffL
        return state / 4294967296.0
    }
}

private data class MeshColor(val r: Double, val g: Double, val b: Double) {
    constructor(argb: Int) : this((argb ushr 16 and 255) / 255.0, (argb ushr 8 and 255) / 255.0, (argb and 255) / 255.0)
    fun argb(): Int = (0xff shl 24) or ((r * 255).roundToInt().coerceIn(0, 255) shl 16) or
        ((g * 255).roundToInt().coerceIn(0, 255) shl 8) or (b * 255).roundToInt().coerceIn(0, 255)
    fun mix(other: MeshColor, t: Double) = MeshColor(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)
    fun luminance(): Double {
        fun linear(v: Double) = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }
    fun hsv(): Triple<Double, Double, Double> {
        val high = max(r, max(g, b)); val low = min(r, min(g, b)); val delta = high - low
        val hue = if (delta == 0.0) 0.0 else ((when (high) { r -> (g - b) / delta; g -> (b - r) / delta + 2; else -> (r - g) / delta + 4 }) * 60).mod(360.0)
        return Triple(hue, if (high == 0.0) 0.0 else delta / high, high)
    }
    fun hsl(): Triple<Double, Double, Double> {
        val (hue, _, high) = hsv(); val low = min(r, min(g, b)); val light = (high + low) / 2
        return Triple(hue, if (high == low) 0.0 else (high - low) / (1 - abs(2 * light - 1)), light)
    }
}

private fun hueColor(hue: Double, chroma: Double, match: Double): MeshColor {
    val secondary = chroma * (1 - abs((hue / 60).mod(2.0) - 1))
    val (r, g, b) = when {
        hue < 60 -> Triple(chroma, secondary, 0.0)
        hue < 120 -> Triple(secondary, chroma, 0.0)
        hue < 180 -> Triple(0.0, chroma, secondary)
        hue < 240 -> Triple(0.0, secondary, chroma)
        hue < 300 -> Triple(secondary, 0.0, chroma)
        else -> Triple(chroma, 0.0, secondary)
    }
    return MeshColor(MeshColor(r + match, g + match, b + match).argb())
}

private fun hsv(hue: Double, saturation: Double, value: Double) = hueColor(hue.mod(360.0), saturation * value, value * (1 - saturation))
private fun hsl(hue: Double, saturation: Double, light: Double): MeshColor {
    val chroma = (1 - abs(2 * light - 1)) * saturation
    return hueColor(hue, chroma, light - chroma / 2)
}

private val LightAuroraBase = MeshColor(0xfff7f8fa.toInt())

private fun readable(tint: MeshColor): MeshColor {
    if (tint.luminance() >= 0.74) return tint
    var low = 0.0; var high = 1.0
    repeat(12) { val middle = (low + high) / 2; if (LightAuroraBase.mix(tint, middle).luminance() >= 0.74) low = middle else high = middle }
    return LightAuroraBase.mix(tint, low)
}

private fun limitChroma(color: MeshColor): MeshColor {
    if (fullHctChroma(color.argb()) <= 30) return color
    val neutral = MeshColor(fullNeutralTone(color.argb()))
    var low = 0.0; var high = 1.0
    repeat(12) { val middle = (low + high) / 2; if (fullHctChroma(neutral.mix(color, middle).argb()) <= 30) low = middle else high = middle }
    return readable(neutral.mix(color, low))
}

private fun lightTone(color: MeshColor, luminance: Double): MeshColor {
    val (hue, saturation) = color.hsl()
    var low = 0.0; var high = 1.0
    repeat(14) { val middle = (low + high) / 2; if (hsl(hue, saturation, middle).luminance() < luminance) low = middle else high = middle }
    return limitChroma(hsl(hue, saturation, (low + high) / 2))
}

internal data class FullAuroraMesh(val colors: IntArray) {
    companion object {
        val positions = FloatArray(65 * 97 * 2).apply {
            for (row in 0..96) for (column in 0..64) {
                val index = (row * 65 + column) * 2
                this[index] = column / 64f; this[index + 1] = row / 96f
            }
        }
        val indices = ShortArray(64 * 96 * 6).apply {
            var index = 0
            for (row in 0 until 96) for (column in 0 until 64) {
                val a = row * 65 + column
                for (vertex in intArrayOf(a, a + 1, a + 65, a + 1, a + 66, a + 65)) this[index++] = vertex.toShort()
            }
        }
    }
}

// apps/aerove/lib/common/aurora.dart; colors are built once, never per animation frame.
private fun buildFullAuroraMesh(seed: Long, colors: List<Long>, dark: Boolean): FullAuroraMesh {
    val rng = FullSeed(seed)
    val angle = rng.next() * PI * 2; val cosine = cos(angle); val sine = sin(angle)
    val phaseX = rng.next() * PI * 2; val phaseY = rng.next() * PI * 2
    val frequency = 3.2 + rng.next() * 2; val amplitude = 0.24 + rng.next() * 0.16
    val centerX = 0.46 + rng.next() * 0.08; val centerY = 0.46 + rng.next() * 0.08
    val palette = (colors.ifEmpty { FullAuroraColors }).take(3).map { value ->
        val color = MeshColor(value.toInt()); val (h, s, v) = color.hsv()
        if (s > 0.60) hsv(h, 0.60, v) else color
    }
    val source = palette.map { it.hsv() }
    val sorted = source.filter { it.second > 0 }.map { it.first }.sorted()
    var start = sorted.firstOrNull() ?: 0.0; var largest = -1.0
    for (i in sorted.indices) {
        val next = (i + 1) % sorted.size
        val gap = sorted[next] + (if (next == 0) 360 else 0) - sorted[i]
        if (gap > largest) { largest = gap; start = sorted[next] }
    }
    val hues = source.map { if (it.second == 0.0) start else start + (it.first - start).mod(360.0) }
    val sourceIndices = intArrayOf(0, if (palette.size > 1) 1 else 0, if (palette.size > 2) 2 else 0, palette.lastIndex)
    val darkCorners = sourceIndices.mapIndexed { i, sourceIndex -> Triple(hues[sourceIndex], source[sourceIndex].second, source[sourceIndex].third * doubleArrayOf(0.32, 0.46, 0.50, 0.38)[i]) }
    val tints = if (dark) emptyList() else palette.map { limitChroma(readable(LightAuroraBase.mix(it, 0.72))) }
    val corners = if (dark) emptyList() else listOf(lightTone(tints[0], 0.74), lightTone(tints.getOrElse(1) { tints[0] }, if (tints.size == 1) 0.88 else 0.76), lightTone(tints.getOrElse(2) { tints[0] }, 0.78), lightTone(tints.last(), 0.84))
    val minimumSaturation = if (dark) 0.0 else min(0.08, corners.minOf { it.hsv().second } * 0.8)
    val order = intArrayOf(0, 1, 2, 3)
    for (i in 3 downTo 1) { val other = floor(rng.next() * (i + 1)).toInt(); val old = order[i]; order[i] = order[other]; order[other] = old }
    fun smooth(value: Double): Double { val t = (value / 0.58 + 0.5).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
    return FullAuroraMesh(IntArray(65 * 97) { index ->
        val dx = index % 65 / 64.0 - centerX; val dy = index / 65 / 96.0 - centerY
        var x = dx * cosine - dy * sine; var y = dx * sine + dy * cosine
        val falloff = (1 - (dx * dx + dy * dy) * 4).coerceIn(0.0, 1.0); val warp = amplitude * falloff * falloff
        x += sin(y * frequency + phaseX) * warp; y += sin(x * frequency * 1.25 + phaseY) * warp
        val u = smooth(x); val v = smooth(y)
        fun mix(a: Double, b: Double, c: Double, d: Double) = (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v
        if (dark) {
            val a = darkCorners[order[0]]; val b = darkCorners[order[1]]; val c = darkCorners[order[2]]; val d = darkCorners[order[3]]
            hsv(mix(a.first, b.first, c.first, d.first), mix(a.second, b.second, c.second, d.second), mix(a.third, b.third, c.third, d.third)).argb()
        } else {
            var color = corners[order[0]].mix(corners[order[1]], u).mix(corners[order[2]].mix(corners[order[3]], u), v)
            var components = color.hsv()
            if (components.second < minimumSaturation) color = hsv(components.first, minimumSaturation, components.third)
            components = color.hsv()
            readable(hsv(components.first, (components.second * 1.08).coerceIn(0.0, 1.0), components.third)).argb()
        }
    })
}

private data class AuroraMeshKey(val seed: Long, val colors: List<Long>, val dark: Boolean)
private val auroraMeshes = object : LinkedHashMap<AuroraMeshKey, FullAuroraMesh>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AuroraMeshKey, FullAuroraMesh>?) = size > 4
}

private fun cachedAuroraMesh(seed: Long, colors: List<Long>, dark: Boolean): FullAuroraMesh? = synchronized(auroraMeshes) { auroraMeshes[AuroraMeshKey(seed, colors, dark)] }

internal fun fullAuroraMesh(seed: Long, colors: List<Long>, dark: Boolean): FullAuroraMesh {
    cachedAuroraMesh(seed, colors, dark)?.let { return it }
    val built = buildFullAuroraMesh(seed, colors, dark)
    return synchronized(auroraMeshes) { auroraMeshes.getOrPut(AuroraMeshKey(seed, colors.toList(), dark)) { built } }
}

internal data class FullAuroraPose(val angle: Double, val x: Double, val y: Double, val side: Double)

internal class FullAuroraMotion(seed: Long) {
    private val rng = FullSeed(seed xor 0x9e3779b9L)
    private val phases = DoubleArray(7) { rng.next() * 2 * PI }
    private val periods = arrayOf(800 to 600, 480 to 240, 780 to 360, 250 to 180, 610 to 360, 340 to 170, 750 to 360).map { (base, range) -> (base + (rng.next() * range).roundToInt()).toLong() * 1_000_000 / 5 }
    fun at(elapsedMicros: Long, width: Double, height: Double): FullAuroraPose {
        val time = elapsedMicros * 9 / 4
        fun phase(i: Int) = 2 * PI * (time % periods[i]) / periods[i] + phases[i]
        val reach = min(width, height) * 0.12
        return FullAuroraPose(phase(0) + 0.08 * sin(phase(1)) + 0.05 * sin(phase(2)), reach * (0.7 * sin(phase(3)) + 0.3 * sin(phase(4))), reach * (0.7 * sin(phase(5)) + 0.3 * sin(phase(6))), sqrt((width + 2 * reach) * (width + 2 * reach) + (height + 2 * reach) * (height + 2 * reach)) * 1.01)
    }
}

@Composable
internal fun Modifier.fullAuroraWallpaper(time: State<Float>, dark: Boolean, colors: List<Long> = FullAuroraColors, seed: Long = FullAuroraSeed): Modifier {
    val mesh = remember(dark, colors, seed) { fullAuroraMesh(seed, colors, dark) }
    val motion = remember(seed) { FullAuroraMotion(seed) }
    return drawWithCache {
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = false }
        onDrawBehind {
            val pose = motion.at((time.value.toDouble() * 90_000_000).toLong(), size.width.toDouble(), size.height.toDouble())
            val canvas = drawContext.canvas.nativeCanvas
            val checkpoint = canvas.save()
            canvas.clipRect(0f, 0f, size.width, size.height)
            canvas.translate((size.width / 2 + pose.x).toFloat(), (size.height / 2 + pose.y).toFloat())
            canvas.rotate((pose.angle * 180 / PI).toFloat())
            canvas.scale(pose.side.toFloat(), pose.side.toFloat())
            canvas.translate(-0.5f, -0.5f)
            canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, FullAuroraMesh.positions.size, FullAuroraMesh.positions, 0, null, 0, mesh.colors, 0, FullAuroraMesh.indices, 0, FullAuroraMesh.indices.size, paint)
            canvas.restoreToCount(checkpoint)
        }
    }
}
