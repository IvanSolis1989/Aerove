package dev.aerove.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import androidx.compose.ui.platform.LocalContext
import dev.aerove.lite.optics.AeroveGlassCapture
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.optics.AeroveOpticsPrograms
import dev.aerove.lite.optics.configureAeroveGlass
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier

data class RenderCase(val tier: MaterialTier, val aurora: Boolean, val dark: Boolean, val checked: Boolean, val enabled: Boolean) {
    val settings get() = LiteSettings(material = tier, aurora = aurora, colorMode = if (dark) ColorMode.Dark else ColorMode.Light)
    override fun toString() = "${tier.name}_${if (aurora) "aurora" else "ordinary"}_${if (dark) "dark" else "light"}_${if (checked) "on" else "off"}_${if (enabled) "enabled" else "disabled"}"
}

internal enum class FixtureStage { Source, Production, NoLens, InkMask, OutlineMask, FlatThumb }
internal data class FixtureState(
    val stage: FixtureStage = FixtureStage.Source,
    val revision: Int = 0,
    val auroraPhase: Float = 0f,
    val parentSelected: Boolean = false,
    val frontHue: Boolean = false,
    val switchMarkers: Boolean = false,
    val thumbMarker: Boolean = false,
    val regularPane: Boolean = false,
    val pillPane: Boolean = false,
    val inkAlpha: Float = 1f,
)

@Composable
internal fun OpticalFixture(case: RenderCase, state: FixtureState, probe: RenderProbe) {
    LiteTheme(case.settings) {
        val visuals = LocalLiteVisuals.current
        val ink = visuals.ink.copy(alpha = state.inkAlpha)
        val source = rememberLayerBackdrop()
        val phase = remember { mutableStateOf(state.auroraPhase) }
        val view = LocalView.current
        val density = LocalDensity.current
        SideEffect {
            phase.value = state.auroraPhase
            probe.view = view
            probe.density = density.density
            probe.fontScale = density.fontScale
            probe.surface = visuals.surface.toArgb()
            probe.selectedSurface = lerp(visuals.surface, visuals.primary, 0.13f).toArgb()
            probe.switchTrack = (if (case.checked) visuals.primary else lerp(visuals.surface, visuals.ink, 0.12f)).toArgb()
            probe.switchThumb = (if (case.checked) Color.White else visuals.secondaryInk).toArgb()
            probe.ink = ink.toArgb()
        }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { probe.bounds["root"] = it.boundsInWindow() }) {
            val paneWidth = minOf(320.dp, maxWidth - 48.dp)
            Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                LiteWallpaper(phase, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val base = Color(if (state.revision == 0) 0xFF172432 else 0xFFBDC3CF)
                    drawRect(base, Offset.Zero, Size(size.width, 310.dp.toPx()))
                    val white = Color(0xFFF7F8FC)
                    if (case.tier == MaterialTier.Clear) {
                        val center = 24.dp.toPx() + paneWidth.toPx() / 2
                        for (y in listOf(40.dp, 197.dp)) drawLine(white,
                            Offset(0f, y.toPx() - center), Offset(size.width, y.toPx() + size.width - center), strokeWidth = 3.dp.toPx())
                    } else for (y in listOf(42.dp, 198.dp)) drawRect(white, Offset(0f, y.toPx()), Size(size.width, 1.dp.toPx()))
                    if (state.switchMarkers) {
                        val y = if (state.thumbMarker) 261.2.dp else 258.5.dp
                        drawRect(white, Offset(0f, y.toPx()), Size(size.width, 1.dp.toPx()))
                    }
                    if (state.pillPane) drawRect(white, Offset(0f, 120.dp.toPx()), Size(size.width, 1.dp.toPx()))
                    if (state.stage == FixtureStage.InkMask) drawRect(Color.Black, Offset(0f, 310.dp.toPx()), Size(size.width, 44.dp.toPx()))
                }
            }
            CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source) {
                Box(Modifier.offset(24.dp, 32.dp).size(paneWidth, 176.dp).onGloballyPositioned { probe.bounds["pane"] = it.boundsInWindow() }) {
                    when (state.stage) {
                        FixtureStage.Production -> LitePane(Modifier.fillMaxSize(),
                            glassStyle = when {
                                state.pillPane -> AeroveGlassStyle.pill(case.dark)
                                case.tier == MaterialTier.Clear && !state.regularPane -> AeroveGlassStyle.lensContent()
                                else -> null
                            }) {}
                        FixtureStage.NoLens -> NoLensControl(case, Modifier.fillMaxSize(),
                            blurOnly = if (state.pillPane) 15.6f else null, glassTint = if (state.pillPane) Color.Transparent else null)
                        FixtureStage.OutlineMask -> CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(material = MaterialTier.Solid)) {
                            LitePane(Modifier.fillMaxSize()) {}
                        }
                        else -> Unit
                    }
                }
                Box(Modifier.offset(24.dp, 244.dp).size(60.dp, 48.dp).onGloballyPositioned { probe.bounds["switch"] = it.boundsInWindow() }) {
                    if (state.stage == FixtureStage.Production) LiteSwitch(case.checked, "rendering-switch", {}, case.enabled)
                    if (state.stage == FixtureStage.NoLens) NoLensSwitch(case)
                    if (state.stage == FixtureStage.FlatThumb) NoLensSwitch(case, flatTrack = false)
                }
                val inkModifier = Modifier.offset(24.dp, 312.dp).onGloballyPositioned { probe.bounds["ink"] = it.boundsInWindow() }
                if (state.stage == FixtureStage.InkMask) {
                    CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(aurora = false, material = MaterialTier.Solid)) {
                        LiteText("MMMMMM", inkModifier, style = LiteType.Headline, color = Color.White)
                    }
                } else if (state.stage != FixtureStage.Source) LiteText("MMMMMM", inkModifier, style = LiteType.Headline, color = ink)
                if (state.stage != FixtureStage.Source) {
                    val iconModifier = Modifier.offset(220.dp, 314.dp).onGloballyPositioned { probe.bounds["inkIcon"] = it.boundsInWindow() }
                    if (state.stage == FixtureStage.InkMask) {
                        CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(aurora = false, material = MaterialTier.Solid)) {
                            LiteIcon(FullGlyphs.logs, iconModifier, color = Color.White)
                        }
                    } else LiteIcon(FullGlyphs.logs, iconModifier, color = ink)
                }
                if (state.stage != FixtureStage.Source && state.stage != FixtureStage.InkMask) LitePane(
                    Modifier.offset(24.dp, 366.dp).size(paneWidth, 128.dp).onGloballyPositioned { probe.bounds["parent"] = it.boundsInWindow() },
                    selected = state.parentSelected,
                    clipContent = false,
                ) {
                    CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(material = MaterialTier.Clear)) {
                        LitePane(
                            Modifier.offset((-20).dp, (-20).dp).size(140.dp, 72.dp)
                                .onGloballyPositioned { probe.bounds["cornerProbe"] = it.boundsInWindow() },
                            glassStyle = AeroveGlassStyle(blur = 0f, refraction = 0f, bevel = 0f, rim = 0f),
                        ) {}
                    }
                    LitePane(
                        Modifier.align(Alignment.Center).size(140.dp, 72.dp).onGloballyPositioned { probe.bounds["child"] = it.boundsInWindow() },
                        corner = LiteCorner.Control,
                    ) {
                        LiteText(
                            "MMMM", Modifier.align(Alignment.Center).onGloballyPositioned { probe.bounds["front"] = it.boundsInWindow() },
                            style = LiteType.Headline, color = if (state.frontHue) Color.Cyan else Color.Magenta,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoLensControl(
    case: RenderCase,
    modifier: Modifier,
    corner: Dp = LiteCorner.Card,
    selected: Boolean = false,
    solidColor: Color? = null,
    glassStyle: AeroveGlassStyle? = null,
    glassTint: Color? = null,
    blurOnly: Float? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = rememberLiteShape(corner)
    val bufferShape = rememberLiteShape(LiteCorner.None)
    val source = LocalLiteBackdrop.current
    val exported = rememberLayerBackdrop()
    val combined = rememberCombinedBackdrop(source, exported)
    val visuals = LocalLiteVisuals.current
    val surface = if (selected) lerp(visuals.surface, visuals.primary, 0.13f) else visuals.surface
    val alpha = when (case.tier) {
        MaterialTier.Solid -> 1f
        MaterialTier.Frosted -> if (case.dark) 0.06f else 0.24f
        MaterialTier.Clear -> 0f
    }
    val assets = LocalContext.current.assets
    val referenceSource = remember(assets) { AeroveOpticsPrograms.load(assets).glassSource
        .replace("uniform shader hazeContent;", "").replace("hazeContent.eval(", "content.eval(") }
    val fill = glassTint ?: if (case.tier == MaterialTier.Frosted && case.dark) visuals.ink.copy(alpha = alpha) else surface.copy(alpha = alpha)
    Box(modifier, propagateMinConstraints = true) {
        Box(Modifier.matchParentSize().layerBackdrop(exported).clip(shape).drawPlainBackdrop(
            backdrop = source,
            shape = { bufferShape },
            effects = {
                if (case.tier != MaterialTier.Solid && blurOnly != null) {
                    padding = kotlin.math.ceil(3 * blurOnly * density)
                    blur(blurOnly * density)
                } else if (case.tier == MaterialTier.Frosted) { padding = kotlin.math.ceil(3 * 2.5.dp.toPx()); blur(2.5.dp.toPx()) }
                else if (case.tier == MaterialTier.Clear && glassStyle != null && size.minDimension > 0f) {
                    val margin = kotlin.math.ceil(20.dp.toPx())
                    padding = margin
                    val geometry = shape.geometry(size, layoutDirection, this)
                    val capture = AeroveGlassCapture(size.width.toInt() + 2 * margin, size.height.toInt() + 2 * margin, density, margin)
                    runtimeShaderEffect("NoRefractionReference", referenceSource, "content") {
                        configureAeroveGlass(geometry, glassStyle.copy(refraction = 0f, tint = fill.opticsColor()), capture)
                    }
                }
            },
            onDrawBackdrop = { drawBackdrop -> if (case.tier != MaterialTier.Solid) drawBackdrop() },
            onDrawSurface = {
                if (case.tier == MaterialTier.Solid) drawRect(solidColor ?: surface)
                else if (case.tier != MaterialTier.Clear || glassStyle == null) drawRect(fill)
            },
        ))
        Box(Modifier.clip(shape), propagateMinConstraints = true) {
            CompositionLocalProvider(LocalLiteBackdrop provides combined) { content() }
        }
    }
}

@Composable
private fun NoLensSwitch(case: RenderCase, flatTrack: Boolean = true) {
    Box(Modifier.size(60.dp, 48.dp).alpha(if (case.enabled || case.tier != MaterialTier.Solid) 1f else 0.48f), contentAlignment = Alignment.Center) {
        val visuals = LocalLiteVisuals.current
        val inactive = if (case.tier == MaterialTier.Frosted) 0.13f else 0.06f
        val trackTint = (if (case.checked) visuals.primary else visuals.ink).copy(alpha = if (!case.enabled) 0.03f else if (case.checked) 0.18f else inactive)
        val thumbTint = visuals.surface.copy(alpha = if (case.enabled) 0.08f else 0.03f)
        val finish = AeroveGlassStyle.regular(case.dark).copy(blur = 0f)
        val children: @Composable BoxScope.() -> Unit = {
            Box(Modifier.offset(if (case.checked) 24.dp else 4.dp, 4.dp)) {
                NoLensControl(case, Modifier.size(24.dp), LiteCorner.Full, case.checked,
                    solidColor = if (case.checked) Color.White else visuals.secondaryInk, glassStyle = finish, glassTint = thumbTint) {
                    if (!flatTrack && case.tier != MaterialTier.Solid) LiteIcon(if (case.checked) FullGlyphs.check else FullGlyphs.close,
                        Modifier.align(Alignment.Center), visuals.ink.copy(alpha = if (case.enabled) 1f else 0.38f), size = 14.dp)
                }
            }
        }
        if (flatTrack) NoLensControl(case, Modifier.size(52.dp, 32.dp), LiteCorner.Full, case.checked,
            solidColor = if (case.checked) visuals.primary else lerp(visuals.surface, visuals.ink, 0.12f), glassStyle = finish, glassTint = trackTint, content = children)
        else LitePane(Modifier.size(52.dp, 32.dp), corner = LiteCorner.Full, selected = case.checked,
            solidColor = if (case.checked) visuals.primary else lerp(visuals.surface, visuals.ink, 0.12f), glassStyle = finish, glassTint = trackTint, content = children)
    }
}
