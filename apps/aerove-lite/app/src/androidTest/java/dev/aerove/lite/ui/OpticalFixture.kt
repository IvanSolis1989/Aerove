package dev.aerove.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.style.TextOverflow
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import org.json.JSONArray
import org.json.JSONObject

data class RenderCase(val tier: MaterialTier, val aurora: Boolean, val dark: Boolean, val checked: Boolean, val enabled: Boolean) {
    val settings get() = LiteSettings(material = tier, aurora = aurora, colorMode = if (dark) ColorMode.Dark else ColorMode.Light)
    override fun toString() = "${tier.name}_${if (aurora) "aurora" else "ordinary"}_${if (dark) "dark" else "light"}_${if (checked) "on" else "off"}_${if (enabled) "enabled" else "disabled"}"
}

internal val OpticalPaneShape = dev.aerove.lite.optics.AeroveSuperellipseShape(LiteCorner.Card)

internal enum class FixtureStage { Source, Production, NoLens, InkMask, OutlineMask, FlatThumb }
internal enum class LensControl { None, DispersionOff, DispersionOffRedEdge, DispersionOffBlueEdge }
private const val LensControlEdgeWidthDp = 0.67f
private fun dispersionOffStyle() = AeroveGlassStyle.lensContent().copy(dispersion = 0f)
private fun LensControl.edgeColor(): Color? = when (this) {
    LensControl.DispersionOffRedEdge -> Color(180f / 255f, 0f, 0f, 1f)
    LensControl.DispersionOffBlueEdge -> Color(0f, 0f, 180f / 255f, 1f)
    else -> null
}
internal enum class SolidDemandPose { DefaultNoGlass, Mounted, VisibleExit, Unmounted, Remounted }

internal class SolidDemandEvidence {
    var ancestor: com.kyant.backdrop.Backdrop? = null
    var nested: com.kyant.backdrop.Backdrop? = null
    var ink: com.kyant.backdrop.Backdrop? = null
    val observed = JSONObject()
}

internal enum class SdkChromaticStencil(val redBlueSpanPx: Float, val edgeChannel: Int? = null) {
    Zero(0f), Subpixel(0.5f), OnePixel(1f), TwoPixels(2f), Reverse(-1f), ReverseSubpixel(-0.5f),
    UniformRed(0f, 0), UniformBlue(0f, 2),
}

internal data class FixtureState(
    val stage: FixtureStage = FixtureStage.Source,
    val revision: Int = 0,
    val auroraTime: Long = 0L,
    val parentSelected: Boolean = false,
    val frontHue: Boolean = false,
    val switchMarkers: Boolean = false,
    val thumbMarker: Boolean = false,
    val regularPane: Boolean = false,
    val pillPane: Boolean = false,
    val inkAlpha: Float = 1f,
    val lensControl: LensControl = LensControl.None,
    val plainInkControl: Boolean = false,
    val switchForegroundControl: Boolean = false,
    val sdkInkReference: SdkInkReferenceCapture? = null,
    val fixtureGeometry: JSONObject? = null,
    val inkContrastSource: Boolean = false,
    val inkContrastDrawReceipt: JSONObject? = null,
    val sceneDrawReceipt: JSONObject? = null,
    val sdkChromaticStencil: SdkChromaticStencil? = null,
    val sdkChromaticDrawReceipt: JSONObject? = null,
    val solidDemandPose: SolidDemandPose? = null,
    val solidDemandEvidence: SolidDemandEvidence? = null,
)

internal fun FixtureState.opticalConfiguration(): JSONObject {
    val color = lensControl.edgeColor()
    val style = dispersionOffStyle()
    return JSONObject().put("stage", stage.name).put("revision", revision).put("auroraTime", auroraTime)
        .put("parentSelected", parentSelected).put("frontHue", frontHue).put("switchMarkers", switchMarkers)
        .put("thumbMarker", thumbMarker).put("regularPane", regularPane).put("pillPane", pillPane)
        .put("inkAlpha", inkAlpha).put("lensControl", lensControl.name).put("plainInkControl", plainInkControl)
        .put("diagnosticOnly", lensControl != LensControl.None || plainInkControl || inkContrastSource)
        .put("fixtureGeometry", if (stage == FixtureStage.Source || stage == FixtureStage.InkMask) JSONObject.NULL else fixtureGeometry?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
        .put("sceneDrawReceipt", sceneDrawReceipt?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
        .put("sdkChromaticStencil", sdkChromaticStencil?.name ?: JSONObject.NULL)
        .put("sdkChromaticDrawReceipt", sdkChromaticDrawReceipt?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
        .put("chromaticCalibrationOnly", sdkChromaticStencil != null)
        .put("solidDemandPose", solidDemandPose?.name ?: JSONObject.NULL)
        .put("solidDemandObserved", solidDemandEvidence?.let { JSONObject(it.observed.toString()) } ?: JSONObject.NULL)
        .put("inkContrastSource", inkContrastSource)
        .put("samplingSourceMode", if (inkContrastSource) "wallpaper+independent-test-contrast" else "original-wallpaper-and-bench-markers")
        .put("pureAuroraLocalMotionEvidence", false)
        .put("inkContrastDefinition", if (!inkContrastSource) JSONObject.NULL else JSONObject()
            .put("bandDp", JSONArray(listOf(0, 310, "full-width", 354))).put("zeroRgb", "ff0000").put("halfRgb", "0000ff")
            .put("alpha", 0.60).put("blendMode", "SrcOver").put("zeroTime", 0L).put("halfTime", 45_000_000L)
            .put("drawOrder", "After real LiteWallpaper and existing markers, before original InkMask black band")
            .put("drawReceipt", inkContrastDrawReceipt?.let { JSONObject(it.toString()) } ?: JSONObject.NULL))
        .put("sdkInkReference", sdkInkReference != null)
        .put("sdkInkReferencePaint", if (sdkInkReference == null) JSONObject.NULL else "Independent direct Compose Text/SDK layer and raw vector; original paint alpha already included in RGBA")
        .put("switchForegroundControl", switchForegroundControl)
        .put("switchForegroundReference", if (!switchForegroundControl) JSONObject.NULL else JSONObject()
            .put("stages", "NoLens; non-Solid only; existing FlatThumb foreground unchanged")
            .put("glyph", "Original check/close selected by RenderCase.checked")
            .put("paint", "Original visuals.ink and shared LiteIcon/liteInk intent; enabled alpha1, disabled alpha0.38")
            .put("sharedForegroundPrimitive", true).put("independentGlyphOracle", false)
            .put("requestedSizeDp", 14).put("alignment", "Center in the original 24dp control thumb")
            .put("actualGlyphBounds", "See switch-control-thumb/track window Rects; original glyph constraints retained")
            .put("positionStages", "NoLens and FlatThumb; foreground rendering remains independently selected").put("checkedPosition", "Independent measured track maxWidth minus 28dp, rounded once in pixels"))
        .put("plainInkPaint", "Original ink color/alpha/font/style/position; only liteInk disabled, no black background or white replacement")
        .put("lensParameters", if (lensControl == LensControl.None) JSONObject.NULL else JSONObject()
            .put("base", "AeroveGlassStyle.lensContent").put("dispersion", style.dispersion).put("refraction", style.refraction)
            .put("bevel", style.bevel).put("blur", style.blur).put("rimBlur", style.rimBlur).put("rim", style.rim)
            .put("haze", style.haze).put("hazeBlur", style.hazeBlur).put("contrast", style.contrast)
            .put("rimWidth", style.rimWidth).put("rimTail", style.rimTail).put("rimFocus", style.rimFocus)
            .put("rimAdded", style.rimAdded).put("endShade", style.endShade).put("hairline", style.hairline)
            .put("hairlineSide", style.hairlineSide ?: JSONObject.NULL)
            .put("tintRgba", JSONArray(listOf(style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)))
            .put("saturation", style.saturation).put("brightness", style.brightness))
        .put("additiveEdge", if (color == null) JSONObject.NULL else JSONObject()
            .put("innerWidthDp", LensControlEdgeWidthDp).put("centerStrokeWidthDp", 2 * LensControlEdgeWidthDp)
            .put("sourceRgba8", JSONArray(listOf(android.graphics.Color.red(color.toArgb()), android.graphics.Color.green(color.toArgb()),
                android.graphics.Color.blue(color.toArgb()), android.graphics.Color.alpha(color.toArgb()))))
            .put("blendMode", "Plus").put("drawAlpha", 1).put("clip", "Original OpticalPaneShape path; centered stroke clipped to its interior")
            .put("rgba8Equivalence", "Not assumed: verify actual backend compositing with the original captured pixels"))
}

@Composable
internal fun OpticalFixture(case: RenderCase, state: FixtureState, probe: RenderProbe) {
    require(state.sdkChromaticStencil == null || (state.stage == FixtureStage.Production && state.lensControl == LensControl.None))
    require(state.sdkInkReference == null || (state.plainInkControl && state.stage == FixtureStage.Production))
    require(!state.plainInkControl || state.stage != FixtureStage.InkMask) { "Plain ink must retain the original backdrop and paint" }
    require(state.lensControl == LensControl.None || (case.tier == MaterialTier.Clear && state.stage == FixtureStage.Production && !state.regularPane && !state.pillPane))
    LiteTheme(case.settings) {
        val visuals = LocalLiteVisuals.current
        val ink = visuals.ink.copy(alpha = state.inkAlpha)
        val source = rememberLayerBackdrop()
        val auroraTime = remember { mutableLongStateOf(state.auroraTime) }
        val view = LocalView.current
        val density = LocalDensity.current
        SideEffect {
            auroraTime.longValue = state.auroraTime
            probe.view = view
            probe.density = density.density
            probe.fontScale = density.fontScale
            probe.surface = visuals.surface.toArgb()
            probe.selectedSurface = lerp(visuals.surface, visuals.primary, 0.13f).toArgb()
            probe.switchTrack = (if (case.checked) visuals.primary else lerp(visuals.surface, visuals.ink, 0.12f)).toArgb()
            probe.switchThumb = (if (case.checked) Color.White else visuals.secondaryInk).toArgb()
            probe.ink = ink.toArgb()
        }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
            probe.bounds["root"] = it.boundsInWindow()
            probe.consumerReceipt?.rootLayout(view.rootView, it.boundsInWindow(clipBounds = false))
        }.drawWithContent {
            drawContent()
            state.sceneDrawReceipt?.let { receipt ->
                receipt.put("drawPasses", receipt.optLong("drawPasses", 0L) + 1L).put("stage", state.stage.name)
                    .put("auroraTime", state.auroraTime).put("inkContrastSource", state.inkContrastSource)
                    .put("parentSelected", state.parentSelected).put("frontHue", state.frontHue)
                    .put("plainInkControl", state.plainInkControl).put("sdkInkReference", state.sdkInkReference != null)
                    .put("inkAlpha", state.inkAlpha).put("method", "OpticalFixture root drawWithContent after drawContent")
            }
            probe.consumerReceipt?.rootDraw(state, view.rootView)
        }) {
            val paneWidth = minOf(320.dp, maxWidth - 48.dp)
            val sourceModifier = if (probe.consumerReceipt == null) Modifier else Modifier.onGloballyPositioned {
                probe.consumerReceipt?.sourceLayout(it.boundsInWindow(clipBounds = false))
            }
            Box(Modifier.fillMaxSize().then(sourceModifier).layerBackdrop(source)) {
                LiteWallpaper(auroraTime, Modifier.fillMaxSize())
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
                    if (state.inkContrastSource) {
                        val time = auroraTime.longValue
                        check(time == 0L || time == 45_000_000L) { "Controlled ink input has exactly two fixed poses" }
                        val signal = if (time == 0L) Color.Red else Color.Blue
                        drawRect(signal.copy(alpha = 0.60f), Offset(0f, 310.dp.toPx()), Size(size.width, 44.dp.toPx()), blendMode = BlendMode.SrcOver)
                        state.inkContrastDrawReceipt?.put("drawReadTime", time)?.put("signalRgb", if (time == 0L) "ff0000" else "0000ff")
                            ?.put("bandPx", JSONArray(listOf(0f, 310.dp.toPx(), size.width, 354.dp.toPx())))
                            ?.put("alpha", 0.60)?.put("blendMode", "SrcOver")?.put("canvasDrawExecuted", true)
                    }
                    if (state.stage == FixtureStage.InkMask) drawRect(Color.Black, Offset(0f, 310.dp.toPx()), Size(size.width, 44.dp.toPx()))
                    probe.consumerReceipt?.sourceDraw(state, source, size.width, size.height, this.density)
                }
            }
            CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source) {
                Box(Modifier.offset(24.dp, 32.dp).size(paneWidth, 176.dp).onGloballyPositioned { probe.bounds["pane"] = it.boundsInWindow() }) {
                    if (state.sdkChromaticStencil != null) IndependentSdkChromaticStencil(state)
                    else when (state.stage) {
                        FixtureStage.Production -> LitePane(Modifier.fillMaxSize(), corner = OpticalPaneShape.corner,
                            glassStyle = when {
                                state.lensControl != LensControl.None -> dispersionOffStyle()
                                state.pillPane -> AeroveGlassStyle.pill(case.dark)
                                case.tier == MaterialTier.Clear && !state.regularPane -> AeroveGlassStyle.lensContent()
                                else -> null
                            }) {}
                        FixtureStage.NoLens -> NoLensControl(case, Modifier.fillMaxSize(),
                            blurOnly = if (state.pillPane) 15.6f else null, glassTint = if (state.pillPane) Color.Transparent else null)
                        FixtureStage.OutlineMask -> CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(material = MaterialTier.Solid)) {
                            LitePane(Modifier.fillMaxSize(), corner = OpticalPaneShape.corner) {}
                        }
                        else -> Unit
                    }
                    if (state.lensControl == LensControl.DispersionOffRedEdge || state.lensControl == LensControl.DispersionOffBlueEdge) {
                        Canvas(Modifier.fillMaxSize()) {
                            val outline = OpticalPaneShape.createOutline(size, layoutDirection, this) as Outline.Generic
                            val color = requireNotNull(state.lensControl.edgeColor())
                            clipPath(outline.path) {
                                drawPath(outline.path, color, style = Stroke(2 * LensControlEdgeWidthDp.dp.toPx()), blendMode = BlendMode.Plus)
                            }
                        }
                    }
                }
                Box(Modifier.offset(24.dp, 244.dp).size(60.dp, 48.dp).onGloballyPositioned { probe.bounds["switch"] = it.boundsInWindow() }) {
                    if (state.stage == FixtureStage.Production) LiteSwitch(case.checked, "rendering-switch", {}, case.enabled)
                    if (state.stage == FixtureStage.NoLens) NoLensSwitch(case, foregroundControl = state.switchForegroundControl, measuredPosition = state.switchForegroundControl, probe = if (state.switchForegroundControl) probe else null)
                    if (state.stage == FixtureStage.FlatThumb) NoLensSwitch(case, flatTrack = false, measuredPosition = state.switchForegroundControl, probe = if (state.switchForegroundControl) probe else null)
                }
                CompositionLocalProvider(LocalLiteVisuals provides if (state.plainInkControl) visuals.copy(aurora = false, material = MaterialTier.Solid) else visuals) {
                val inkModifier = Modifier.offset(24.dp, 312.dp).onGloballyPositioned { probe.bounds["ink"] = it.boundsInWindow() }
                if (state.stage == FixtureStage.InkMask) {
                    CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(aurora = false, material = MaterialTier.Solid)) {
                        LiteText("MMMMMM", inkModifier, style = LiteType.Headline, color = Color.White)
                    }
                } else if (state.stage != FixtureStage.Source) {
                    val reference = state.sdkInkReference
                    if (reference != null) SdkInkReferenceGlyph("ink", reference, inkModifier, ink)
                    else LiteText("MMMMMM", inkModifier, style = LiteType.Headline, color = ink)
                }
                if (state.stage != FixtureStage.Source) {
                    val iconModifier = Modifier.offset(220.dp, 314.dp).onGloballyPositioned { probe.bounds["inkIcon"] = it.boundsInWindow() }
                    if (state.stage == FixtureStage.InkMask) {
                        CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(aurora = false, material = MaterialTier.Solid)) {
                            LiteIcon(FullGlyphs.logs, iconModifier, color = Color.White)
                        }
                    } else {
                        val reference = state.sdkInkReference
                        if (reference != null) SdkInkReferenceGlyph("inkIcon", reference, iconModifier, ink)
                        else LiteIcon(FullGlyphs.logs, iconModifier, color = ink)
                    }
                }
                }
                if (state.solidDemandPose != null && state.stage == FixtureStage.Production) {
                    SolidDemandFixture(state, probe, paneWidth)
                } else if (state.stage != FixtureStage.Source && state.stage != FixtureStage.InkMask) LitePane(
                    Modifier.offset(24.dp, 366.dp).size(paneWidth, 128.dp).onGloballyPositioned { probe.bounds["parent"] = it.boundsInWindow(clipBounds = false) },
                    selected = state.parentSelected,
                    clipContent = false,
                    exportForGlassDescendants = true,
                ) {
                    FixtureLooseContent("parent-content", state.fixtureGeometry) {
                    CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(material = MaterialTier.Clear)) {
                        LitePane(
                            Modifier.offset((-20).dp, (-20).dp).size(140.dp, 72.dp)
                                .onGloballyPositioned { probe.bounds["cornerProbe"] = it.boundsInWindow(clipBounds = false) },
                            glassStyle = AeroveGlassStyle(blur = 0f, refraction = 0f, bevel = 0f, rim = 0f),
                        ) {}
                    }
                    LitePane(
                        Modifier.align(Alignment.Center).size(140.dp, 72.dp).onGloballyPositioned { probe.bounds["child"] = it.boundsInWindow(clipBounds = false) },
                        corner = LiteCorner.Control,
                    ) {
                        FixtureLooseContent("child-content", state.fixtureGeometry) {
                        LiteText(
                            "MMMM", Modifier.align(Alignment.Center).onGloballyPositioned { probe.bounds["front"] = it.boundsInWindow(clipBounds = false) },
                            style = LiteType.Headline, color = if (state.frontHue) Color.Cyan else Color.Magenta,
                            onTextLayout = { result ->
                                state.fixtureGeometry?.put("front-text-layout", JSONObject().put("text", result.layoutInput.text.text)
                                    .put("widthPx", result.size.width).put("heightPx", result.size.height)
                                    .put("minWidthPx", result.layoutInput.constraints.minWidth).put("minHeightPx", result.layoutInput.constraints.minHeight)
                                    .put("maxWidthPx", result.layoutInput.constraints.maxWidth).put("maxHeightPx", result.layoutInput.constraints.maxHeight)
                                    .put("overflow", result.didOverflowWidth || result.didOverflowHeight).put("lineCount", result.lineCount))
                            },
                        )
                        }
                    }
                    }
                }
            }
        }
    }
}



/** Independent SDK paint/geometry only: no lens, material, RuntimeShader or production dispersion path. */
@Composable
private fun IndependentSdkChromaticStencil(state: FixtureState) {
    val pose = requireNotNull(state.sdkChromaticStencil)
    Canvas(Modifier.fillMaxSize()) {
        val outline = OpticalPaneShape.createOutline(size, layoutDirection, this) as Outline.Generic
        clipPath(outline.path) {
            drawRect(Color(0xFF172432))
            val colors = listOf(Color(224f / 255f, 0f, 0f), Color(0f, 212f / 255f, 0f), Color(0f, 0f, 202f / 255f))
            for ((bottom, y) in listOf(false to 8.dp.toPx(), true to 165.dp.toPx())) {
                val shifts = listOf(pose.redBlueSpanPx / 2 * if (bottom) 1 else -1, 0f,
                    pose.redBlueSpanPx / 2 * if (bottom) -1 else 1)
                for (channel in 0..2) drawLine(colors[channel],
                    Offset(0f, y - size.width / 2 + shifts[channel]),
                    Offset(size.width, y + size.width / 2 + shifts[channel]),
                    strokeWidth = 3.dp.toPx(), blendMode = BlendMode.Plus)
            }
            pose.edgeChannel?.let { channel ->
                val edge = if (channel == 0) Color(180f / 255f, 0f, 0f) else Color(0f, 0f, 180f / 255f)
                drawPath(outline.path, edge, style = Stroke(2 * LensControlEdgeWidthDp.dp.toPx()), blendMode = BlendMode.Plus)
            }
        }
        state.sdkChromaticDrawReceipt?.put("drawExecuted", true)?.put("renderer", "Independent Compose SDK Canvas")
            ?.put("pose", pose.name)?.put("redBlueSpanPx", pose.redBlueSpanPx)?.put("density", density)
            ?.put("lineYDp", JSONArray(listOf(8, 165)))?.put("slope", 1)?.put("strokeWidthDp", 3)
            ?.put("baseRgb8", JSONArray(listOf(23, 36, 50)))?.put("channelPaint8", JSONArray(listOf(224, 212, 202)))
            ?.put("zeroFullCoverageRgb8Intent", JSONArray(listOf(247, 248, 252)))?.put("uniformEdgeChannelPaint8", 180)
            ?.put("blendMode", "Plus")?.put("clip", "Original OpticalPaneShape")
            ?.put("topShiftSigns", "R=-span/2; G=0; B=span/2; bottom reversed")
            ?.put("innerOutlineWidthDp", if (pose.edgeChannel == null) 0 else LensControlEdgeWidthDp)
            ?.put("panePx", JSONArray(listOf(size.width, size.height)))?.put("productionLensInvoked", false)
    }
}

/** A bounded lifecycle control scene; the original optical scene above remains unchanged. */
@Composable
private fun SolidDemandFixture(state: FixtureState, probe: RenderProbe, paneWidth: Dp) {
    val pose = requireNotNull(state.solidDemandPose)
    val evidence = requireNotNull(state.solidDemandEvidence)
    val ancestor = LocalLiteBackdrop.current
    val ink = LocalLiteInkBackdrop.current
    val visuals = LocalLiteVisuals.current
    val mounted = pose != SolidDemandPose.DefaultNoGlass && pose != SolidDemandPose.Unmounted
    val parentModifier = Modifier.offset(24.dp, 366.dp).size(paneWidth, 128.dp)
        .onGloballyPositioned { probe.bounds["parent"] = it.boundsInWindow(clipBounds = false) }
    val content: @Composable BoxScope.() -> Unit = {
        val nested = LocalLiteBackdrop.current
        val nestedInk = LocalLiteInkBackdrop.current
        val logicalActive = LocalLitePageActive.current
        SideEffect {
            evidence.ancestor = ancestor
            evidence.nested = nested
            evidence.ink = nestedInk
        }
        FixtureLooseContent("parent-content", state.fixtureGeometry) {
            val corner = Modifier.offset((-20).dp, (-20).dp).size(140.dp, 72.dp)
                .onGloballyPositioned { probe.bounds["cornerProbe"] = it.boundsInWindow(clipBounds = false) }
                .drawWithContent {
                    drawContent()
                    val draw = JSONObject().put("pose", pose.name).put("glassMounted", mounted)
                        .put("logicalPageActive", logicalActive).put("sourceIsAncestor", nested === ancestor)
                        .put("ancestorIdentity", System.identityHashCode(ancestor)).put("nestedIdentity", System.identityHashCode(nested))
                        .put("inkIdentity", System.identityHashCode(nestedInk)).put("inkIsAncestor", nestedInk === ink)
                        .put("parentSelected", state.parentSelected)
                    if (!evidence.observed.has("firstDraw")) evidence.observed.put("firstDraw", JSONObject(draw.toString()))
                    evidence.observed.put("lastDraw", draw).put("draws", evidence.observed.optInt("draws") + 1)
                        .put("gpuRecordCounterAvailable", false).put("firstPresentedPixelsObserved", false)
                }
            if (mounted) {
                CompositionLocalProvider(LocalLiteVisuals provides visuals.copy(material = MaterialTier.Clear)) {
                    LitePane(corner, glassStyle = AeroveGlassStyle(blur = 0f, refraction = 0f, bevel = 0f, rim = 0f)) {}
                }
            } else {
                // Non-optical readback probe: inspect inherited source without declaring glass demand.
                Box(corner.drawPlainBackdrop(backdrop = nested, shape = { OpticalPaneShape }, effects = {}))
            }
            LitePane(Modifier.align(Alignment.Center).size(140.dp, 72.dp)
                .onGloballyPositioned { probe.bounds["child"] = it.boundsInWindow(clipBounds = false) }, corner = LiteCorner.Control) {
                FixtureLooseContent("child-content", state.fixtureGeometry) {
                    LiteText("MMMM", Modifier.align(Alignment.Center)
                        .onGloballyPositioned { probe.bounds["front"] = it.boundsInWindow(clipBounds = false) },
                        style = LiteType.Headline, color = if (state.frontHue) Color.Cyan else Color.Magenta)
                }
            }
        }
    }
    CompositionLocalProvider(LocalLitePageActive provides (pose != SolidDemandPose.VisibleExit)) {
        if (pose == SolidDemandPose.DefaultNoGlass) {
            // Deliberately omit the API argument: this is the real production default.
            LitePane(parentModifier, selected = state.parentSelected, clipContent = false, content = content)
        } else {
            LitePane(parentModifier, selected = state.parentSelected, clipContent = false,
                exportForGlassDescendants = mounted, content = content)
        }
    }
}

@Composable
private fun NoLensControl(
    case: RenderCase,
    modifier: Modifier,
    corner: Dp = OpticalPaneShape.corner,
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
private fun NoLensSwitch(case: RenderCase, flatTrack: Boolean = true, foregroundControl: Boolean = false, measuredPosition: Boolean = false, probe: RenderProbe? = null) {
    val switchBackdrop = LocalLiteInkBackdrop.current ?: LocalLiteBackdrop.current
    Box(Modifier.size(60.dp, 48.dp).alpha(if (case.enabled || case.tier != MaterialTier.Solid) 1f else 0.48f), contentAlignment = Alignment.Center) {
        val visuals = LocalLiteVisuals.current
        val inactive = if (case.tier == MaterialTier.Frosted) 0.13f else 0.06f
        val trackTint = (if (case.checked) visuals.primary else visuals.ink).copy(alpha = if (!case.enabled) 0.03f else if (case.checked) 0.18f else inactive)
        val thumbTint = visuals.surface.copy(alpha = if (case.enabled) 0.08f else 0.03f)
        val finish = AeroveGlassStyle.regular(case.dark).copy(blur = 0f)
        val children: @Composable BoxScope.() -> Unit = {
            CompositionLocalProvider(LocalLiteBackdrop provides switchBackdrop) {
                val thumb: @Composable () -> Unit = {
                    NoLensControl(case, Modifier.size(24.dp).onGloballyPositioned { probe?.bounds?.set("switch-control-thumb", it.boundsInWindow()) }, LiteCorner.Full, case.checked,
                        solidColor = if (case.checked) Color.White else visuals.secondaryInk, glassStyle = finish, glassTint = thumbTint) {
                        if ((foregroundControl || !flatTrack) && case.tier != MaterialTier.Solid) {
                            val glyph = if (case.checked) FullGlyphs.check else FullGlyphs.close
                            val paint = visuals.ink.copy(alpha = if (case.enabled) 1f else 0.38f)
                            // The no-lens plane retains the original Aurora foreground intent.
                            Box(contentAlignment = Alignment.Center, propagateMinConstraints = false) {
                                LiteIcon(glyph, Modifier.align(Alignment.Center), paint, size = 14.dp)
                            }
                        }
                    }
                }
                if (measuredPosition) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp).onGloballyPositioned { probe?.bounds?.set("switch-control-track", it.boundsInWindow()) }) {
                        val thumbLeft = if (case.checked) maxWidth - 28.dp else 4.dp
                        Box(Modifier.offset { IntOffset(thumbLeft.roundToPx(), 4.dp.roundToPx()) }) { thumb() }
                    }
                } else Box(Modifier.offset(if (case.checked) 24.dp else 4.dp, 4.dp)) { thumb() }
            }
        }
        CompositionLocalProvider(LocalLiteBackdrop provides switchBackdrop) {
            if (flatTrack) NoLensControl(case, Modifier.size(52.dp, 32.dp), LiteCorner.Full, case.checked,
                solidColor = if (case.checked) visuals.primary else lerp(visuals.surface, visuals.ink, 0.12f), glassStyle = finish, glassTint = trackTint, content = children)
            else LitePane(Modifier.size(52.dp, 32.dp), corner = LiteCorner.Full, selected = case.checked,
                solidColor = if (case.checked) visuals.primary else lerp(visuals.surface, visuals.ink, 0.12f), glassStyle = finish, glassTint = trackTint, content = children)
        }
    }
}

internal class SdkInkReferenceCapture {
    val layers = ConcurrentHashMap<String, GraphicsLayer>()
    val bounds = ConcurrentHashMap<String, Rect>()
    val details = ConcurrentHashMap<String, JSONObject>()
}

/** An independent SDK source image: no production ink modifier, shader or layer helper. */
@Composable
private fun SdkInkReferenceGlyph(name: String, references: SdkInkReferenceCapture, modifier: Modifier, paint: Color) {
    val layer = rememberGraphicsLayer()
    val family = LocalLiteFontFamily.current
    val style = LiteType.Headline.copy(fontFamily = LiteType.Headline.fontFamily ?: family)
    SideEffect { references.layers[name] = layer }
    val recording = modifier.onGloballyPositioned { references.bounds[name] = it.boundsInWindow() }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
    val detail = JSONObject().put("paintArgb", paint.toArgb()).put("sourceAlphaIncluded", true)
        .put("renderer", if (name == "ink") "Direct Compose Material3 Text" else "Raw vector GlyphDrawing")
        .put("resolvedPlatformTypeface", "not directly observed")
    if (name == "ink") {
        Text("MMMMMM", recording, color = paint, style = style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                detail.put("text", result.layoutInput.text.text).put("fontSizeSp", style.fontSize.value)
                    .put("lineHeightSp", style.lineHeight.value).put("fontWeight", style.fontWeight?.weight ?: JSONObject.NULL)
                    .put("letterSpacingSp", style.letterSpacing.value).put("fontFamilyRequest", style.fontFamily.toString())
                    .put("layoutSizePx", JSONArray(listOf(result.size.width, result.size.height)))
                    .put("layoutConstraints", result.layoutInput.constraints.toString())
                references.details[name] = detail
            })
    } else {
        SideEffect { references.details[name] = detail.put("glyph", "FullGlyphs.logs").put("requestedSizeDp", 24) }
        GlyphDrawing(FullGlyphs.logs, recording.size(24.dp), paint, fill = 0f)
    }
}

/** Only the test scene releases inherited minima; production Pane layout is untouched. */
@Composable
private fun FixtureLooseContent(name: String, trace: JSONObject?, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), propagateMinConstraints = false) {
        SideEffect {
            trace?.put(name, JSONObject().put("incomingMinWidthPx", constraints.minWidth)
                .put("incomingMaxWidthPx", constraints.maxWidth).put("incomingMinHeightPx", constraints.minHeight)
                .put("incomingMaxHeightPx", constraints.maxHeight).put("propagateMinConstraints", false)
                .put("childMinWidthPx", 0).put("childMinHeightPx", 0))
        }
        content()
    }
}
