package dev.aerove.lite.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import dev.aerove.lite.optics.AeroveGlassQuality
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.optics.AeroveSuperellipseShape
import dev.aerove.lite.optics.OpticsColor
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Effects = BackdropEffectScope.() -> Unit

class LiteBackdropPaddingTest {
    private data class Pane(val name: String, val pad: Float, val effects: (BackdropViewport) -> Effects)

    // Kyant's BackdropEffectScopeImpl is internal, so a proxy carries its state: density 1 and an unspecified size until a draw.
    private class EffectScope {
        var density = 1f
        var size = Size.Unspecified
        var padding = 0f
        var renderEffect: RenderEffect? = null
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BackdropEffectScope::class.java), InvocationHandler { _, method, args ->
            val name = method.name
            when {
                method.declaringClass == Any::class.java -> method.invoke(this, *args.orEmpty())
                name == "getDensity" -> density
                name == "getFontScale" -> 1f
                name.startsWith("getSize") -> Size::class.java.getMethod("unbox-impl").invoke(size)
                name == "getLayoutDirection" -> LayoutDirection.Ltr
                name == "getPadding" -> padding
                name == "setPadding" -> { padding = args!![0] as Float; null }
                name == "getRenderEffect" -> renderEffect
                name == "setRenderEffect" -> { renderEffect = args!![0] as RenderEffect?; null }
                else -> Density::class.java.getMethod(name, *method.parameterTypes).invoke(Density(density), *args.orEmpty())
            }
        }) as BackdropEffectScope
    }

    // Replays Kyant 2.0.1 DrawBackdropNode on the Compose snapshot runtime: effects run with the reset scope on attach and when a
    // recomposition updates the element, and inside the draw that first sees a new size or density, which then reads the stored
    // padding back. Each frame starts like AndroidComposeView.dispatchDraw and ends with the apply notification that schedules redraws.
    private class KyantNode(private val effects: Effects, private val drawDensity: Float) {
        private val scope = EffectScope()
        private val padding = mutableFloatStateOf(0f)
        private val observer = SnapshotStateObserver { it() }.apply { start() }
        private val redraw: (KyantNode) -> Unit = { redraws++ }
        var redraws = 0
            private set
        val stored get() = padding.floatValue

        private fun updateEffects() {
            scope.padding = 0f
            scope.renderEffect = null
            effects(scope.proxy)
            padding.floatValue = scope.padding
        }

        fun attach() {
            scope.density = 1f
            scope.size = Size.Unspecified
            updateEffects()
        }

        fun recompose() = updateEffects()

        fun draw(size: Size) {
            Snapshot.notifyObjectsInitialized()
            observer.observeReads(this, redraw) {
                if (scope.density != drawDensity || scope.size != size) {
                    scope.density = drawDensity
                    scope.size = size
                    updateEffects()
                }
                padding.floatValue
            }
            Snapshot.sendApplyNotifications()
        }

        fun stop() = observer.stop()
    }

    private fun node(effects: Effects, density: Float, frames: KyantNode.() -> Unit) = KyantNode(effects, density).apply {
        try { frames() } finally { stop() }
    }

    private val noViewport: BackdropViewport = { _, _, _ -> null }
    private val densities = listOf(1f, 2.625f, 3.5f)
    private val styles = listOf(AeroveGlassStyle.regular(true).forQuality(AeroveGlassQuality.Standard), AeroveGlassStyle.regular(false),
        AeroveGlassStyle.menu(OpticsColor(0.2f, 0.3f, 0.4f, 0.5f)), AeroveGlassStyle.lens(false))

    private fun panes(density: Float, style: AeroveGlassStyle): List<Pane> {
        val composition = Density(density)
        val geometry = PaneGeometry(AeroveSuperellipseShape(24.dp))
        return listOf(
            Pane("frost $density $style", composition.frostBackdropPadding(style)) { frostBackdropEffects(composition, style, it) },
            Pane("clear $density $style", composition.glassBackdropPadding(style)) {
                glassBackdropEffects(composition, style, geometry, "", null, null, null, it)
            },
        )
    }

    private fun eachPane(check: (Pane, Float) -> Unit) {
        for (density in densities) for (style in styles) for (pane in panes(density, style)) check(pane, density)
    }

    @Test fun firstDrawKeepsTheAttachPaddingForSizedAndEmptyPanes() = eachPane { pane, density ->
        for (size in listOf(Size(320f, 120f), Size.Zero, Size(320f, 0f))) {
            val replay = node(pane.effects(noViewport), density) { attach(); draw(size); draw(size) }
            assertEquals("${pane.name} $size", 0, replay.redraws)
            assertEquals("${pane.name} $size", pane.pad, replay.stored, 0f)
        }
    }

    @Test fun resizingRecomposingAndReattachingNeverRewriteThePadding() = eachPane { pane, density ->
        val replay = node(pane.effects(noViewport), density) {
            attach(); draw(Size.Zero); draw(Size(320f, 120f)); recompose(); draw(Size(320f, 120f))
            draw(Size(320f, 0f)); attach(); draw(Size(200f, 80f)); recompose(); draw(Size(200f, 80f))
        }
        assertEquals(pane.name, 0, replay.redraws)
        assertEquals(pane.name, pane.pad, replay.stored, 0f)
    }

    @Test fun onlyMeasuredNonEmptyPassesClampTheViewportWithTheReportedPadding() = eachPane { pane, density ->
        val tiles = mutableListOf<Triple<Int, Int, Float>>()
        node(pane.effects { width, height, pad -> tiles += Triple(width, height, pad); null }, density) {
            attach(); draw(Size.Zero); draw(Size(320f, 120f)); draw(Size(320f, 120f)); recompose(); draw(Size(320f, 0f))
        }
        val tile = Triple(320 + 2 * pane.pad.toInt(), 120 + 2 * pane.pad.toInt(), pane.pad)
        assertEquals(pane.name, listOf(tile, tile), tiles)
    }

    @Test fun replayDetectsEveryPaddingThatChangesBetweenAttachAndDraw() {
        val style = AeroveGlassStyle.regular(true)
        val sizedOnly: Effects = { if (size.minDimension > 0f) padding = 20f }
        val emptyIsZero: Effects = { if (!size.isSpecified || size.minDimension > 0f) padding = 20f }
        val scopeDensity: Effects = { padding = frostBackdropPadding(style) }
        assertEquals(1, node(sizedOnly, 2.625f) { attach(); draw(Size(320f, 120f)) }.redraws)
        assertEquals(1, node(emptyIsZero, 2.625f) { attach(); draw(Size.Zero) }.redraws)
        assertEquals(1, node(scopeDensity, 2.625f) { attach(); draw(Size(320f, 120f)) }.redraws)
    }

    @Test fun reportedPaddingCoversTheBlurRadiusOfEverySizedPass() {
        val bases = listOf(AeroveGlassStyle(), AeroveGlassStyle.regular(true), AeroveGlassStyle.regular(false), AeroveGlassStyle.pill(true),
            AeroveGlassStyle.lens(false), AeroveGlassStyle.lensContent(), AeroveGlassStyle.menu(OpticsColor(0.2f, 0.3f, 0.4f, 0.5f)),
            AeroveGlassStyle(blur = 40f))
        for (scale in listOf(0.75f, 1f, 2.625f, 3.5f, 4f)) for (style in bases + bases.map { it.forQuality(AeroveGlassQuality.Standard) }) {
            with(Density(scale)) {
                assertTrue("glass $scale $style", glassBackdropPadding(style) >= backdropBlurRadius(style))
                assertTrue("frost $scale $style", frostBackdropPadding(style) >= backdropBlurRadius(style))
            }
        }
    }

    @Test fun productionEffectsRejectAnActualBlurRadiusLargerThanTheirPadding() {
        val composition = Density(1f)
        val style = AeroveGlassStyle.regular(false)
        val effects = listOf(frostBackdropEffects(composition, style, noViewport),
            glassBackdropEffects(composition, style, PaneGeometry(AeroveSuperellipseShape(24.dp)), "", null, null, null, noViewport))
        for (effect in effects) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                node(effect, 100f) { attach(); draw(Size(320f, 120f)) }
            }
            assertTrue(failure.message.orEmpty().contains("exceeds its padding"))
        }
    }

    // The composable call sites and the Kyant blur call cannot run on the JVM; the source pins them to the replayed builders.
    @Test fun frostAndClearPanesTakeTheirEffectsFromTheReplayedBuilders() {
        val lines = File("src/main/java/dev/aerove/lite/ui/LiteOptics.kt").readText().lines().map(String::trim)
        for (call in listOf("effects = frostBackdropEffects(LocalDensity.current, style, viewport.tracking { coordinates }),",
            "effects = glassBackdropEffects(LocalDensity.current, style, geometry, sources.glass, glow, rim, explicitGlow, viewport.tracking { coordinates }),")) {
            assertEquals("composable passes the composition density to the replayed builder: $call", 1, lines.count { it == call })
        }
        assertEquals("only plainBackdropEffects reports padding", listOf("padding = pad"),
            lines.filter { Regex("""\bpadding\s*=(?!=)""").containsMatchIn(it) })
        assertEquals("only plainBackdropEffects blurs, with the radius the coverage test checks", listOf("if (sigma > 0f) blur(sigma)"),
            lines.filter { Regex("""\bblur\(""").containsMatchIn(it) })
        assertEquals("blur radius comes from backdropBlurRadius", 1, lines.count { it == "val sigma = backdropBlurRadius(style)" })
    }
}
