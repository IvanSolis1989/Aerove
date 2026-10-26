package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
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
private fun buildFullAuroraMesh(seed: Long, colors: List<Long>, dark: Boolean, checkCancellation: () -> Unit): FullAuroraMesh {
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
    return FullAuroraMesh(IntArray(65 * 97).also { output ->
        for (row in 0..96) {
            checkCancellation()
            for (column in 0..64) {
                val index = row * 65 + column
                output[index] = run {
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
                }
            }
        }
    })
}

private data class AuroraMeshKey(val seed: Long, val colors: List<Long>, val dark: Boolean)
private val auroraMeshes = object : LinkedHashMap<AuroraMeshKey, FullAuroraMesh>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AuroraMeshKey, FullAuroraMesh>?) = size > 4
}

private fun cachedAuroraMesh(seed: Long, colors: List<Long>, dark: Boolean): FullAuroraMesh? = synchronized(auroraMeshes) { auroraMeshes[AuroraMeshKey(seed, colors, dark)] }

internal fun fullAuroraMesh(seed: Long, colors: List<Long>, dark: Boolean, checkCancellation: () -> Unit = {}): FullAuroraMesh {
    cachedAuroraMesh(seed, colors, dark)?.let { return it }
    val built = buildFullAuroraMesh(seed, colors, dark, checkCancellation)
    return synchronized(auroraMeshes) { auroraMeshes.getOrPut(AuroraMeshKey(seed, colors.toList(), dark)) { built } }
}

internal data class FullAuroraPose(val angle: Double, val x: Double, val y: Double, val side: Double)

private const val MotionMask = 0xffffffffL
private const val MotionUnit = 4294967296.0
private const val TurnUnitMicros = 1_600_000L
private const val WarpRadians = 0.2
private const val WarpKnotMicros = 9_000_000L
private const val SaltWarp = 3L
private const val SaltHeading = 11L
private const val SaltBlock = 12L
private const val SaltWalk = 13L
private const val Headings = 64
private const val HalfHeadings = 32
private const val KnotMicros = 250_000L
private const val BlockSteps = 1920
private const val BlockMicros = BlockSteps * KnotMicros
private const val WanderStep = 0.035
private const val AimReach = 0.85
private const val WanderLimit = 0.97
private const val Candidates = 43
private const val LegMin = 0.4
private const val LegCap = 1.3
private const val TurnMax = 28
private const val TurnSoft = 17
private const val Steer = 1
private const val Arrive = 1.5
private const val WanderWindow = 120
private const val WindCap = 21
private const val AxisBand = 6
private const val BandFree = 0.42
private const val WeightLeg = 0.585
private const val WeightTurn = 0.0104
private const val WeightSharp = 0.42
private const val WeightOffset = 2.8
private const val WeightWind = 0.1
private const val WeightCap = 0.0856
private const val WeightBand = 18.75
private const val WeightNoise = 0.82
private const val HomeFrom = BlockSteps - 80
private const val HomeMin = 0.5
private const val HomeTurn = 8
private const val HomeWait = 70
private const val HomeSteer = 3
private const val HomeBehind = 16
private const val Emergency = 30
private const val MotionCapacity = 2048

// cos(2 pi k / 64) for k = 0..16 as exact literals, so the heading table needs no trigonometry.
private val HeadingQuadrant = doubleArrayOf(
    1.0, 0.9951847266721969, 0.9807852804032304, 0.9569403357322088, 0.9238795325112867, 0.881921264348355,
    0.8314696123025452, 0.773010453362737, 0.7071067811865476, 0.6343932841636455, 0.5555702330196022,
    0.47139673682599764, 0.3826834323650898, 0.2902846772544624, 0.19509032201612828, 0.0980171403295606, 0.0,
)
private val HeadingX = DoubleArray(Headings) { k ->
    val j = k % 16
    when (k / 16) { 0 -> HeadingQuadrant[j]; 1 -> -HeadingQuadrant[16 - j]; 2 -> -HeadingQuadrant[j]; else -> HeadingQuadrant[16 - j] }
}
private val HeadingY = DoubleArray(Headings) { k ->
    val j = k % 16
    when (k / 16) { 0 -> HeadingQuadrant[16 - j]; 1 -> HeadingQuadrant[j]; 2 -> -HeadingQuadrant[16 - j]; else -> -HeadingQuadrant[j] }
}

private fun splineWeight(i: Int, f: Double) = when (i) {
    0 -> (1 - f) * (1 - f) * (1 - f) / 6
    1 -> (3 * f * f * f - 6 * f * f + 4) / 6
    2 -> (-3 * f * f * f + 3 * f * f + 3 * f + 1) / 6
    else -> f * f * f / 6
}

private fun motionHash(key: Long, index: Long, salt: Long): Long {
    var x = (key xor ((index and MotionMask) * 0x9e3779b1L) xor (salt * 0x85ebca77L)) and MotionMask
    x = x xor (x ushr 16)
    x = (x * 0x7feb352dL) and MotionMask
    x = x xor (x ushr 15)
    x = (x * 0x846ca68bL) and MotionMask
    return x xor (x ushr 16)
}

private fun wrapHeading(d: Int) = Math.floorMod(d + HalfHeadings, Headings) - HalfHeadings

private fun nearestHeading(dx: Double, dy: Double): Int {
    var best = dx * HeadingX[0] + dy * HeadingY[0]
    var k = 0
    for (j in 1 until Headings) {
        val v = dx * HeadingX[j] + dy * HeadingY[j]
        if (v > best) {
            best = v
            k = j
        }
    }
    return k
}

// Operation for operation with apps/aerove/lib/common/aurora_motion.dart; both editions check one set of golden poses bit for bit.
// The wander lives in 8-minute blocks simulated from (seed, block) alone into arrays allocated once; one instance serves one drawing thread.
internal class FullAuroraMotion(seed: Long) {
    private val key = ((seed xor 0x9e3779b9L) and MotionMask).let { if (it == 0L) 0x6d2b79f5L else it }
    private val phase: Double
    private val turn: Long
    private val xs = DoubleArray(MotionCapacity)
    private val ys = DoubleArray(MotionCapacity)
    private val ring = IntArray(WanderWindow)
    private val axes = IntArray(WanderWindow)
    private val bands = IntArray(HalfHeadings)
    private var cached = -1L
    private var extra = 0
    private var blockKey = 0L
    private var counter = 0L
    private var aimX = 0.0
    private var aimY = 0.0

    init {
        val rng = FullSeed(seed xor 0x9e3779b9L)
        phase = rng.next() * 2 * PI
        repeat(6) { rng.next() }
        turn = (800L + floor(rng.next() * 600 + 0.5).toLong()) * TurnUnitMicros
    }

    fun at(elapsedMicros: Long, width: Double, height: Double): FullAuroraPose {
        val angle = angle(elapsedMicros)
        val block = elapsedMicros / BlockMicros
        val tau = elapsedMicros % BlockMicros
        if (block != cached) simulate(block)
        val u = tau.toDouble() / BlockMicros
        val g = u * u * u * (u * (u * 6 - 15) + 10)
        val s = tau.toDouble() / KnotMicros + extra * g
        val k = floor(s).toInt()
        val f = s - k
        var x = 0.0
        var y = 0.0
        for (i in 0..3) {
            val w = splineWeight(i, f)
            x += w * xs[k + i]
            y += w * ys[k + i]
        }
        val reach = min(width, height) * 0.34
        return FullAuroraPose(angle, reach * x, reach * y, (sqrt(width * width + height * height) + 2 * reach) * 1.01)
    }

    private fun angle(t: Long): Double {
        var angle = 2 * PI * (27 * t % turn) / turn + phase
        val k = t / WarpKnotMicros
        val f = (t % WarpKnotMicros).toDouble() / WarpKnotMicros
        for (i in 0..3) angle += splineWeight(i, f) * WarpRadians * (2 * hash(k + i - 1, SaltWarp) - 1)
        return angle
    }

    private fun hash(index: Long, salt: Long) = motionHash(key, index, salt) / MotionUnit

    private fun heading(block: Long) = floor(hash(block, SaltHeading) * Headings).toInt()

    private fun draw() = motionHash(blockKey, counter++, SaltWalk) / MotionUnit

    private fun simulate(block: Long) {
        ring.fill(0)
        axes.fill(-1)
        bands.fill(0)
        blockKey = motionHash(key, block, SaltBlock)
        counter = 0
        val h0 = heading(block)
        val h1 = heading(block + 1)
        xs[0] = 0.0
        ys[0] = 0.0
        xs[1] = 0.0
        ys[1] = 0.0
        var x = 0.0
        var y = 0.0
        var h = h0
        var wind = 0
        var aim = false
        aimX = 0.0
        aimY = 0.0
        var homing = false
        var i = 0
        var total = -1
        var arrivalX = 0.0
        var arrivalY = 0.0
        while (total < 0) {
            i++
            var dh = 0
            var steer = i >= 2
            if (steer && !homing && i >= HomeFrom) {
                val d2 = x * x + y * y
                if (i >= HomeFrom + HomeWait) homing = true
                else if (d2 >= HomeMin * HomeMin && abs(wrapHeading(nearestHeading(-x, -y) - h)) <= HomeTurn) homing = true
                if (homing) {
                    aim = true
                    aimX = 0.0
                    aimY = 0.0
                }
            }
            if (steer && !homing) {
                if (aim) {
                    val ex = aimX - x
                    val ey = aimY - y
                    if (ex * ex + ey * ey < (Arrive * WanderStep) * (Arrive * WanderStep) || ex * HeadingX[h] + ey * HeadingY[h] <= 0) aim = false
                }
                if (!aim) {
                    choose(x, y, h, wind)
                    aim = true
                }
            }
            if (steer && homing && x * x + y * y < HomeMin * HomeMin && abs(wrapHeading(nearestHeading(-x, -y) - h)) > HomeBehind) steer = false
            if (steer) {
                val ex = aimX - x
                val ey = aimY - y
                for (n in 0 until if (homing) HomeSteer else Steer) {
                    val left = Math.floorMod(h + 1, Headings)
                    val right = Math.floorMod(h - 1, Headings)
                    val cur = ex * HeadingX[h] + ey * HeadingY[h]
                    val toLeft = ex * HeadingX[left] + ey * HeadingY[left]
                    val toRight = ex * HeadingX[right] + ey * HeadingY[right]
                    if (toLeft > cur && toLeft >= toRight) {
                        h = left
                        dh += 1
                    } else if (toRight > cur) {
                        h = right
                        dh -= 1
                    } else break
                }
            }
            val slot = i % WanderWindow
            wind += dh - ring[slot]
            ring[slot] = dh
            if (axes[slot] >= 0) bands[axes[slot]] -= 1
            axes[slot] = h % HalfHeadings
            bands[h % HalfHeadings] += 1
            x = x + WanderStep * HeadingX[h]
            y = y + WanderStep * HeadingY[h]
            val r2 = x * x + y * y
            if (r2 > WanderLimit * WanderLimit) {
                val scale = WanderLimit / sqrt(r2)
                x = x * scale
                y = y * scale
            }
            xs[i + 1] = x
            ys[i + 1] = y
            if (homing) {
                val d2 = x * x + y * y
                if (d2 > (2 * WanderStep) * (2 * WanderStep) &&
                    (abs(wrapHeading(nearestHeading(-x, -y) - h)) <= 1 || i >= HomeFrom + HomeWait + Emergency)) {
                    val d = sqrt(d2)
                    arrivalX = -x / d
                    arrivalY = -y / d
                    val m = max(2, ceil(d / WanderStep).toInt())
                    val tx = -WanderStep * arrivalX
                    val ty = -WanderStep * arrivalY
                    for (step in 1 until m) {
                        val frac = step.toDouble() / (m - 1)
                        xs[i + 1 + step] = x + frac * (tx - x)
                        ys[i + 1 + step] = y + frac * (ty - y)
                    }
                    xs[i + 1 + m] = 0.0
                    ys[i + 1 + m] = 0.0
                    total = i + m
                }
            }
        }
        // Turning the whole block slowly about the centre makes its arrival heading the next block's hashed start heading.
        val ux1 = HeadingX[h1]
        val uy1 = HeadingY[h1]
        val c = arrivalX * ux1 + arrivalY * uy1
        val sn = arrivalX * uy1 - arrivalY * ux1
        var lo = (1 - c) / 2
        var hi = (1 + c) / 2
        if (lo < 0) lo = 0.0
        if (hi < 0) hi = 0.0
        var qEnd = sqrt(lo) / (1 + sqrt(hi))
        if (sn < 0) qEnd = -qEnd
        val span = total - 2
        for (s in 2 until total - 1) {
            val q = qEnd * (s - 1) / span
            val cq = (1 - q * q) / (1 + q * q)
            val sq = 2 * q / (1 + q * q)
            val c4 = cq * cq - sq * sq
            val s4 = 2 * cq * sq
            val px = xs[s + 1]
            val py = ys[s + 1]
            xs[s + 1] = c4 * px - s4 * py
            ys[s + 1] = s4 * px + c4 * py
        }
        xs[0] = -WanderStep * HeadingX[h0]
        ys[0] = -WanderStep * HeadingY[h0]
        xs[total] = -WanderStep * ux1
        ys[total] = -WanderStep * uy1
        xs[total + 1] = 0.0
        ys[total + 1] = 0.0
        xs[total + 2] = WanderStep * ux1
        ys[total + 2] = WanderStep * uy1
        extra = total - BlockSteps
        cached = block
    }

    private fun choose(x: Double, y: Double, h: Int, wind: Int) {
        var found = false
        var scoreBest = 0.0
        var bx = 0.0
        var by = 0.0
        for (n in 0 until Candidates) {
            val u1 = draw()
            val u2 = draw()
            val u3 = draw()
            val k = floor(u2 * Headings).toInt()
            val rad = AimReach * sqrt(u1)
            val cx = rad * HeadingX[k]
            val cy = rad * HeadingY[k]
            val dx = cx - x
            val dy = cy - y
            val d2 = dx * dx + dy * dy
            if (d2 < LegMin * LegMin) continue
            val d = sqrt(d2)
            val kk = nearestHeading(dx, dy)
            val turn = wrapHeading(kk - h)
            if (abs(turn) > TurnMax) continue
            var leg = d
            if (leg > LegCap) leg = LegCap
            val a = kk % HalfHeadings
            var count = 0
            for (j in -AxisBand..AxisBand) count += bands[Math.floorMod(a + j, HalfHeadings)]
            val share = (count + leg / WanderStep) / (WanderWindow + leg / WanderStep)
            var sharp = abs(turn) - TurnSoft
            if (sharp < 0) sharp = 0
            val spin = abs(wind + turn)
            var over = spin - WindCap
            if (over < 0) over = 0
            var free = share - BandFree
            if (free < 0) free = 0.0
            val score = WeightLeg * leg - WeightTurn * abs(turn) - WeightSharp * sharp - WeightOffset * abs(x * dy - y * dx) / d -
                WeightWind * spin - WeightCap * over - WeightBand * free + WeightNoise * u3
            if (!found || score > scoreBest) {
                found = true
                scoreBest = score
                bx = cx
                by = cy
            }
        }
        aimX = if (found) bx else 0.0
        aimY = if (found) by else 0.0
    }
}

private fun android.graphics.Canvas.drawAuroraMesh(mesh: FullAuroraMesh, paint: android.graphics.Paint) =
    drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, FullAuroraMesh.positions.size, FullAuroraMesh.positions, 0, null, 0, mesh.colors, 0, FullAuroraMesh.indices, 0, FullAuroraMesh.indices.size, paint)

@Composable
internal fun Modifier.fullAuroraWallpaper(time: LongState, origin: LongState, dark: Boolean, colors: List<Long> = FullAuroraColors, seed: Long = FullAuroraSeed): Modifier {
    val mesh = remember(dark, colors, seed) { fullAuroraMesh(seed, colors, dark) }
    val motion = remember(seed) { FullAuroraMotion(seed) }
    return drawWithCache {
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = false }
        val side = motion.at(0L, size.width.toDouble(), size.height.toDouble()).side.toFloat()
        val recorded = obtainGraphicsLayer()
        recorded.alpha = 1f
        recorded.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Auto
        recorded.pivotOffset = androidx.compose.ui.geometry.Offset(side / 2, side / 2)
        recorded.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(ceil(side).toInt(), ceil(side).toInt())) {
            drawContext.canvas.nativeCanvas.apply {
                val checkpoint = save()
                scale(side, side)
                drawAuroraMesh(mesh, paint)
                restoreToCount(checkpoint)
            }
        }
        onDrawBehind {
            val pose = motion.at((time.longValue - origin.longValue).coerceAtLeast(0L), size.width.toDouble(), size.height.toDouble())
            recorded.translationX = ((size.width - side) / 2 + pose.x).toFloat()
            recorded.translationY = ((size.height - side) / 2 + pose.y).toFloat()
            recorded.rotationZ = (pose.angle * 180 / PI).toFloat()
            clipRect { drawLayer(recorded) }
        }
    }
}

/** The unrotated mesh stretched over the whole box, as Standard's SeededAuroraWallpaper paints without motion. */
@Composable
internal fun Modifier.staticAuroraWallpaper(colors: List<Long>, seed: Long, dark: Boolean): Modifier {
    val mesh = remember(dark, colors, seed) { fullAuroraMesh(seed, colors, dark) }
    return drawWithCache {
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = false }
        onDrawBehind {
            val canvas = drawContext.canvas.nativeCanvas
            val checkpoint = canvas.save()
            canvas.scale(size.width, size.height)
            canvas.drawAuroraMesh(mesh, paint)
            canvas.restoreToCount(checkpoint)
        }
    }
}
