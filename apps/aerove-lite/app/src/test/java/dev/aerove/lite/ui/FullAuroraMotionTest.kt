package dev.aerove.lite.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullAuroraMotionTest {
    private data class Golden(val seed: Long, val width: Double, val height: Double, val seconds: Long,
                              val angle: Double, val x: Double, val y: Double, val side: Double)

    // The same rows are pinned in apps/aerove/test/widgets/aurora_motion_test.dart and FullAppearanceParityTest.
    private val goldens = listOf(
        Golden(832838199L, 390.0, 844.0, 0, 4.708915131711234, 0.0, 0.0, 1206.9000092093268),
        Golden(832838199L, 390.0, 844.0, 2, 4.87782879206507, 32.73327297002594, -17.480551140786897, 1206.9000092093268),
        Golden(832838199L, 390.0, 844.0, 5, 5.109019275106863, 83.11485803179671, -41.26636130120369, 1206.9000092093268),
        Golden(832838199L, 390.0, 844.0, 3600, 6.3297701677309295, -115.64232059337, -33.179728688427986, 1206.9000092093268),
        Golden(832838199L, 412.0, 915.0, 0, 4.708915131711234, 0.0, 0.0, 1296.4748346940519),
        Golden(832838199L, 412.0, 915.0, 2, 4.87782879206507, 34.579765291412016, -18.466633512831287, 1296.4748346940519),
        Golden(832838199L, 412.0, 915.0, 5, 5.109019275106863, 87.80338848487243, -43.59420732332287, 1296.4748346940519),
        Golden(832838199L, 412.0, 915.0, 3600, 6.3297701677309295, -122.16573354991907, -35.05140569136495, 1296.4748346940519),
        Golden(3919277682L, 390.0, 844.0, 0, 2.3951164410395274, 0.0, 0.0, 1206.9000092093268),
        Golden(3919277682L, 390.0, 844.0, 2, 2.559900709932794, 36.52912307394951, -0.22472780647865187, 1206.9000092093268),
        Golden(3919277682L, 390.0, 844.0, 5, 2.8153214385981, 90.97911740293785, -0.1934214407862733, 1206.9000092093268),
        Golden(3919277682L, 390.0, 844.0, 3600, 3.0972836184169243, -37.797860558927034, -53.48944907633882, 1206.9000092093268),
        Golden(3919277682L, 412.0, 915.0, 0, 2.3951164410395274, 0.0, 0.0, 1296.4748346940519),
        Golden(3919277682L, 412.0, 915.0, 2, 2.559900709932794, 38.58974027299282, -0.23740475966462707, 1296.4748346940519),
        Golden(3919277682L, 412.0, 915.0, 5, 2.8153214385981, 96.11127274361638, -0.2043323938562682, 1296.4748346940519),
        Golden(3919277682L, 412.0, 915.0, 3600, 3.0972836184169243, -39.930047564815226, -56.506802613978444, 1296.4748346940519),
    )

    private val standardSeeds = listOf(832838199L, 2379623510L, 624302977L, 2671861087L, 3374210312L,
        1374901759L, 3079535385L, 3413751917L, 3209466732L, 3919277682L)
    private val extremeSeeds = listOf(731L, 3399L, 4722L, 2030L, 2162L, 4763L, 0L, -1L, 0x9e3779b9L, 1L shl 40)
    // Standard's ten seeds, four fixed ones and the slowest (230, 562, 259) and fastest (15, 731, 490) base turns.
    private val wanderSeeds = standardSeeds + listOf(0L, 1L, 42L, 271828L, 15L, 731L, 490L, 230L, 562L, 259L)
    private val slowestTurn = 2 * PI * 27 / (1400 * 1.6) - 1.5 * 0.2 / 9
    private val fastestTurn = 2 * PI * 27 / (800 * 1.6) + 1.5 * 0.2 / 9
    // Control points step at most 0.035 r plus the block's slow turn about the centre; the block warp adds at most 53 of 1920 steps.
    private val speedLimit = (0.035 + 4 * 0.97 / (1854 - 2)) / 0.25 * (1 + 1.875 * 53 / 1920)

    private fun wrap(value: Double): Double {
        var a = value % (2 * PI)
        if (a > PI) a -= 2 * PI
        if (a <= -PI) a += 2 * PI
        return a
    }

    private fun assertBits(label: String, expected: Double, actual: Double) =
        assertEquals("$label expected $expected, got $actual", expected.toRawBits(), actual.toRawBits())

    private fun legacyAngle(seed: Long, micros: Long): Double {
        val rng = FullSeed(seed xor 0x9e3779b9L)
        val phases = DoubleArray(7) { rng.next() * 2 * PI }
        val periods = listOf(800 to 600, 480 to 240, 780 to 360).map { (base, range) -> (base + (rng.next() * range).roundToInt()).toLong() * 1_000_000 / 5 }
        val time = micros * 9 / 4
        fun phase(i: Int) = 2 * PI * (time % periods[i]) / periods[i] + phases[i]
        return phase(0) + 0.08 * sin(phase(1)) + 0.05 * sin(phase(2))
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    @Test
    fun sharedGoldenPosesMatchStandardBitForBit() {
        for (golden in goldens) {
            val pose = FullAuroraMotion(golden.seed).at(golden.seconds * 1_000_000, golden.width, golden.height)
            val label = "${golden.seed} ${golden.width}x${golden.height} t=${golden.seconds}s"
            assertBits("$label angle", golden.angle, pose.angle)
            assertBits("$label x", golden.x, pose.x)
            assertBits("$label y", golden.y, pose.y)
            assertBits("$label side", golden.side, pose.side)
        }
    }

    @Test
    fun parityCasesMatchTheReferenceBitForBitWhetherFreshOrReused() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/aurora-motion/parity-cases.txt")).bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(336, lines.size)
        val reused = HashMap<Long, FullAuroraMotion>()
        for (line in lines) {
            val p = line.split(' ')
            val seed = p[0].toLong()
            val t = p[1].toLong()
            val width = p[2].toDouble()
            val height = p[3].toDouble()
            for ((mode, motion) in listOf("fresh" to FullAuroraMotion(seed), "reused" to reused.getOrPut(seed) { FullAuroraMotion(seed) })) {
                val pose = motion.at(t, width, height)
                val label = "$mode $seed t=$t ${width}x$height"
                assertBits("$label angle", p[4].toDouble(), pose.angle)
                assertBits("$label x", p[5].toDouble(), pose.x)
                assertBits("$label y", p[6].toDouble(), pose.y)
                assertBits("$label side", p[7].toDouble(), pose.side)
            }
        }
    }

    @Test
    fun everySeedTurnsOneAndAHalfTimesItsCurrentBaseRateWithoutStopping() {
        val seeds = (0L until 160L) + standardSeeds + extremeSeeds + (1..30).map { it * 143_165_577L }
        for (seed in seeds) {
            val motion = FullAuroraMotion(seed)
            var current = 0.0
            var legacy = 0.0
            var previous = motion.at(0, 390.0, 844.0).angle
            var previousLegacy = legacyAngle(seed, 0)
            for (second in 1..3600L) {
                val angle = motion.at(second * 1_000_000, 390.0, 844.0).angle
                val step = wrap(angle - previous)
                assertTrue("seed $seed must keep turning forward at ${second}s: $step", step >= slowestTurn * 0.999 && step <= fastestTurn * 1.001)
                current += step
                previous = angle
                val old = legacyAngle(seed, second * 1_000_000)
                legacy += wrap(old - previousLegacy)
                previousLegacy = old
            }
            assertTrue("seed $seed one hour: $current vs 1.5 x $legacy", abs(current - 1.5 * legacy) <= 0.4 + 1.5 * 0.26)
            assertTrue("seed $seed must turn faster than today", current / legacy >= 1.49)
        }
    }

    @Test
    fun framesAndScreenCornersStayWithinTheStatedStepBounds() {
        assertEquals(0.15606, speedLimit, 1e-5)
        var fastest = 0.0
        for ((width, height) in listOf(390.0 to 844.0, 412.0 to 915.0, 800.0 to 1280.0)) {
            val reach = min(width, height) * 0.34
            val halfDiagonal = hypot(width, height) / 2
            val cornerLimit = speedLimit * 0.1 * reach + fastestTurn * 0.1 * (halfDiagonal + 0.97 * reach)
            if (width == 390.0) assertEquals(11.914, cornerLimit, 1e-3)
            if (width == 412.0) assertEquals(12.762, cornerLimit, 1e-3)
            if (width < 800) assertTrue("a 10 fps corner step must not exceed round 2's 12.8 dp", cornerLimit <= 12.8)
            for (seed in wanderSeeds + extremeSeeds) {
                val motion = FullAuroraMotion(seed)
                for (start in 0L until 7_200_000_000L step 1_700_003L) {
                    val a = motion.at(start, width, height)
                    for ((step, label) in listOf(16_000L to "16 ms", 66_667L to "15 fps", 100_000L to "10 fps")) {
                        val b = motion.at(start + step, width, height)
                        val seconds = step / 1e6
                        val travel = hypot(b.x - a.x, b.y - a.y)
                        val turn = wrap(b.angle - a.angle)
                        assertTrue("$seed $label drift $travel", travel <= speedLimit * seconds * reach * (1 + 1e-9))
                        assertTrue("$seed $label turn $turn", turn >= slowestTurn * seconds * 0.999 && turn <= fastestTurn * seconds * 1.001)
                        fastest = maxOf(fastest, travel / seconds / reach)
                        if (step != 100_000L) continue
                        val c = cos(turn) - 1
                        val s = sin(turn)
                        for ((cx, cy) in listOf(width / 2 to height / 2, -width / 2 to height / 2, width / 2 to -height / 2, -width / 2 to -height / 2)) {
                            val qx = cx - a.x
                            val qy = cy - a.y
                            val moved = hypot(b.x - a.x + c * qx - s * qy, b.y - a.y + s * qx + c * qy)
                            assertTrue("$seed ${width}x$height corner moved $moved in one 10 fps frame", moved <= cornerLimit)
                        }
                    }
                }
            }
        }
        assertTrue("The bound must stay tight to real motion: $fastest of $speedLimit", fastest > 0.85 * speedLimit)
    }

    @Test
    fun blocksJoinWithoutAJumpAndEveryBlockFitsItsBuffers() {
        for (seed in wanderSeeds + extremeSeeds) {
            val motion = FullAuroraMotion(seed)
            for (block in 1L..40L) {
                val joint = block * 480_000_000L
                val earlier = motion.at(joint - 1_000, 390.0, 844.0)
                val before = motion.at(joint - 1, 390.0, 844.0)
                val at = motion.at(joint, 390.0, 844.0)
                val after = motion.at(joint + 1_000, 390.0, 844.0)
                assertTrue("$seed block $block jumps", hypot(at.x - before.x, at.y - before.y) <= 1e-6 * 132.6)
                val inbound = hypot(at.x - earlier.x, at.y - earlier.y)
                val outbound = hypot(after.x - at.x, after.y - at.y)
                assertTrue("$seed block $block speed $inbound -> $outbound", abs(inbound - outbound) <= 1e-6 * 132.6)
            }
        }
    }

    @Test
    fun squareCoversEveryViewportCornerAndDriftStaysInsideTheReach() {
        val viewports = listOf(390.0 to 844.0, 412.0 to 915.0, 360.0 to 800.0, 844.0 to 390.0, 720.0 to 720.0, 673.0 to 841.0,
            800.0 to 1280.0, 1280.0 to 800.0, 1920.0 to 1080.0, 320.0 to 1400.0, 1.0 to 1.0, 2400.0 to 1.0)
        for (seed in standardSeeds + extremeSeeds) {
            val motion = FullAuroraMotion(seed)
            val times = (0L..7_200_000_000L step 37_000_000L) + (0L..40L).map { it * 250_000L } +
                (1L..8L).map { it * 480_000_000L - 1 } + listOf(1_000_000_000_000L, 8_640_000_000_000_000L)
            for (t in times) for ((width, height) in viewports) {
                val pose = motion.at(t, width, height)
                val reach = min(width, height) * 0.34
                assertTrue("$seed t=$t drift", hypot(pose.x, pose.y) <= 0.97 * reach * (1 + 1e-9))
                val c = cos(pose.angle)
                val s = sin(pose.angle)
                for ((px, py) in listOf(0.0 to 0.0, width to 0.0, 0.0 to height, width to height)) {
                    val dx = px - width / 2 - pose.x
                    val dy = py - height / 2 - pose.y
                    val u = dx * c + dy * s
                    val v = -dx * s + dy * c
                    assertTrue("$seed ${width}x$height t=$t corner", maxOf(abs(u), abs(v)) < pose.side / 2 * 0.995)
                }
            }
        }
    }

    @Test
    fun reducedMotionRestPoseIsTheCentreAndPosesNeverDependOnHistory() {
        for (seed in (0L until 2000L) + standardSeeds + extremeSeeds + listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val rest = FullAuroraMotion(seed).at(0, 390.0, 844.0)
            assertEquals("seed $seed rest x", 0L, rest.x.toRawBits())
            assertEquals("seed $seed rest y", 0L, rest.y.toRawBits())
        }
        val shared = FullAuroraMotion(832838199L)
        val times = listOf(5_000_000L, 0L, 3_600_000_000L, 480_000_001L, 16_000L, 999_999_999_999L, 479_999_999L, 2_000_000L, 960_000_000L)
        for (t in times) {
            val fresh = FullAuroraMotion(832838199L).at(t, 390.0, 844.0)
            assertEquals("pose at $t", fresh, shared.at(t, 390.0, 844.0))
        }
    }

    @Test
    fun pixelsScaleTranslationAndSideButNotAngle() {
        val motion = FullAuroraMotion(3919277682L)
        for (density in listOf(1.0, 2.625, 3.5)) for (t in listOf(0L, 2_000_000L, 5_000_000L, 3_600_000_000L)) {
            val dp = motion.at(t, 390.0, 844.0)
            val px = motion.at(t, 390.0 * density, 844.0 * density)
            assertEquals(dp.angle, px.angle, 0.0)
            assertEquals(dp.x * density, px.x, 1e-12 * dp.side * density)
            assertEquals(dp.y * density, px.y, 1e-12 * dp.side * density)
            assertEquals(dp.side * density, px.side, 1e-12 * dp.side * density)
        }
    }

    @Test
    fun wanderKeepsItsHeadingAndTravelsEveryWayWithoutOrbiting() {
        val samples = 72_000
        val window = 300
        for (seed in wanderSeeds) for ((width, height) in listOf(390.0 to 844.0, 412.0 to 915.0)) {
            val motion = FullAuroraMotion(seed)
            val xs = DoubleArray(samples)
            val ys = DoubleArray(samples)
            val ux = DoubleArray(samples)
            val uy = DoubleArray(samples)
            val sectors = IntArray(8)
            val reach = min(width, height) * 0.34
            for (i in 0 until samples) {
                val t = i * 100_000L
                val pose = motion.at(t, width, height)
                xs[i] = pose.x
                ys[i] = pose.y
                val before = if (t == 0L) pose else motion.at(t - 1_000, width, height)
                val after = motion.at(t + 1_000, width, height)
                val speed = hypot(after.x - before.x, after.y - before.y)
                ux[i] = (after.x - before.x) / speed
                uy[i] = (after.y - before.y) / speed
                sectors[floor((atan2(uy[i], ux[i]) + PI) / (2 * PI) * 8).toInt().mod(8)]++
            }
            val label = "$seed ${width}x$height"
            val headings = DoubleArray(samples - 1) { atan2(ys[it + 1] - ys[it], xs[it + 1] - xs[it]) }
            val lengths = DoubleArray(samples - 1) { hypot(ys[it + 1] - ys[it], xs[it + 1] - xs[it]) }
            val turning = DoubleArray(samples)
            for (i in 2 until samples) turning[i] = turning[i - 1] + wrap(headings[i - 1] - headings[i - 2])
            val starts = (0 until samples - window step 10).toList()
            val withSpin = starts.count { turning[it + window] - turning[it] > PI }.toDouble() / starts.size
            val againstSpin = starts.count { turning[it + window] - turning[it] < -PI }.toDouble() / starts.size
            assertTrue("$label turns with the spin in $withSpin of 30 s windows", withSpin <= 0.15)
            assertTrue("$label circles against the spin in $againstSpin of 30 s windows", againstSpin <= 0.15)
            for (count in sectors) assertTrue("$label heading sectors ${sectors.toList()}", count >= samples / 10)
            val net = DoubleArray((samples - 50 + 9) / 10) { hypot(xs[it * 10 + 50] - xs[it * 10], ys[it * 10 + 50] - ys[it * 10]) * 390 / min(width, height) }
            assertTrue("$label five second travel ${median(net)}", median(net) >= 80)
            val persistence = (samples - 20).let { n -> (0 until n).sumOf { ux[it] * ux[it + 20] + uy[it] * uy[it + 20] } / n }
            assertTrue("$label heading correlation over 2 s $persistence", persistence >= 0.5)
            val reversals = (0 until samples - 50).count { ux[it] * ux[it + 50] + uy[it] * uy[it + 50] < cos(3 * PI / 4) }.toDouble() / (samples - 50)
            assertTrue("$label reverses within 5 s in $reversals", reversals <= 0.15)
            val turns = DoubleArray(samples - 40) {
                val a = atan2(ys[it + 20] - ys[it], xs[it + 20] - xs[it])
                val b = atan2(ys[it + 40] - ys[it + 20], xs[it + 40] - xs[it + 20])
                abs(wrap(b - a))
            }
            assertTrue("$label median turn between 2 s legs ${median(turns) * 180 / PI}", median(turns) * 180 / PI <= 60)
            val octants = Array(8) { DoubleArray(samples) }
            for (i in 0 until samples - 1) {
                val o = floor((headings[i] + PI) / (2 * PI) * 8).toInt().mod(8)
                for (k in 0 until 8) octants[k][i + 1] = octants[k][i] + if (k == o) lengths[i] else 0.0
            }
            val fewest = (0 until samples - window step 10).minOf { s -> (0 until 8).count { octants[it][s + window] - octants[it][s] > 0 } }
            assertTrue("$label visits only $fewest heading octants in some 30 s window", fewest >= 4)
            assertTrue("$label leaves the reach", (0 until samples).all { hypot(xs[it], ys[it]) <= 0.97 * reach * (1 + 1e-9) })
        }
    }
}
