package dev.aerove.lite.ui

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.scan.ScanFailure
import dev.aerove.lite.scan.ScanPreview
import dev.aerove.lite.scan.ScanSession
import dev.aerove.lite.scan.ScanLayout
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ScanScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    routeSettled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    val close by rememberUpdatedState(onClose)
    val session = remember(context, owner) { ScanSession(context, { result(it) }, { close() }) }
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState == Lifecycle.State.RESUMED) }
    fun permissionGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(permissionGranted()) }
    var requested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted ->
        granted = permitted
        session.update(routeSettled && resumed, permitted)
    }
    val album = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { session.album(it) }
    DisposableEffect(owner, session) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState == Lifecycle.State.RESUMED
            granted = permissionGranted()
            if (!resumed) session.update(false, granted)
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session.dispose()
        }
    }
    LaunchedEffect(routeSettled, resumed, granted) {
        session.update(routeSettled && resumed, granted)
        if (routeSettled && resumed && !granted && !requested) {
            requested = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    BackHandler { session.close() }
    val failure = session.failure
    val cameraFailure = failure == ScanFailure.Permission || failure == ScanFailure.Camera
    val visuals = LocalLiteVisuals.current
    val hostView = LocalView.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, hostView).apply {
                isAppearanceLightStatusBars = cameraFailure && !visuals.dark
                isAppearanceLightNavigationBars = cameraFailure && !visuals.dark
            }
        }
    }
    DisposableEffect(activity, visuals.dark) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, hostView).apply {
                    isAppearanceLightStatusBars = !visuals.dark
                    isAppearanceLightNavigationBars = !visuals.dark
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ScanPreview(it).also(session::attach) },
            modifier = Modifier.fillMaxSize(),
        )
        if (cameraFailure) {
            Box(Modifier.fillMaxSize().background(visuals.page).safeDrawingPadding()
                .padding(top = 72.dp).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                LitePane(Modifier.padding(24.dp).widthIn(max = 420.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LiteText(stringResource(if (failure == ScanFailure.Permission) R.string.lite_scan_permission else R.string.lite_scan_camera_error))
                        LiteButton(stringResource(R.string.lite_scan_retry), {
                            granted = permissionGranted()
                            if (!granted) permission.launch(Manifest.permission.CAMERA) else session.retry()
                        })
                        if (failure == ScanFailure.Permission) LiteButton(stringResource(R.string.lite_scan_settings), {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        })
                    }
                }
            }
        } else {
            ScanFrame(session.invalid, session.cameraRunning && routeSettled && resumed) {
                if (session.hasTorch) ScanControl(
                    stringResource(if (session.torch) R.string.lite_scan_torch_off else R.string.lite_scan_torch_on),
                    if (session.torch) ScanGlyphs.torch else ScanGlyphs.torchOff,
                    false,
                    enabled = session.cameraRunning,
                    selected = session.torch,
                    size = 64.dp,
                    onClick = session::toggleTorch,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScanControl(stringResource(R.string.lite_scan_close), FullGlyphs.close, cameraFailure, onClick = session::close)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScanControl(stringResource(R.string.lite_scan_album), ScanGlyphs.album, cameraFailure, enabled = !session.decodingImage) {
                    if (session.beginAlbum()) {
                        runCatching { album.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .onFailure { session.album(null) }
                    }
                }
            }
        }
        if (failure == ScanFailure.Image || failure == ScanFailure.NoCode || session.decodingImage) {
            LitePane(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(when {
                        session.decodingImage -> R.string.lite_scan_decoding
                        failure == ScanFailure.NoCode -> R.string.lite_scan_no_code
                        else -> R.string.lite_scan_image_error
                    }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (!session.decodingImage) LiteButton(stringResource(R.string.lite_scan_continue), session::dismissImageFailure)
                }
            }
        }
    }
}

@Composable
private fun ScanControl(
    label: String,
    glyph: LiteGlyph,
    errorSurface: Boolean,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: () -> Unit,
) {
    if (errorSurface) {
        LiteIconButton(label, glyph, onClick, enabled = enabled)
        return
    }
    val visuals = LocalLiteVisuals.current
    val translucent = visuals.material != MaterialTier.Solid && !LocalLiteHighContrast.current
    val fill = if (selected) Color(0xFFFFD54F) else if (translucent) Color(0x73000000) else Color(0xFF252525)
    val ink = if (selected) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        Modifier.size(size).background(fill, rememberLiteShape(LiteCorner.Full))
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphDrawing(glyph, Modifier.size(if (size == 64.dp) 32.dp else 24.dp), ink, 1f)
    }
}

@Composable
private fun ScanFrame(invalid: Boolean, running: Boolean, torch: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val contrast = MaterialTheme.colorScheme.inversePrimary
    val density = LocalDensity.current
    val reduced = LocalLiteReducedMotion.current
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, reduced) {
        phase = 0f
        if (running && !reduced) {
            val start = withFrameNanos { it }
            while (isActive) withFrameNanos { phase = ((it - start) % 4_800_000_000L) / 4_800_000_000f }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val hintHeight = with(density) { (LiteType.Callout.lineHeight.toPx() * 2).toDp() }
        val layout = ScanLayout.calculate(maxWidth.value, maxHeight.value, hintHeight.value)
        val corner = listOf(LiteCorner.None, LiteCorner.ExtraSmall, LiteCorner.Small, LiteCorner.Control,
            LiteCorner.Block, LiteCorner.Card, LiteCorner.Sheet).last { it.value <= layout.window.width / 3 }
        val shape = rememberLiteShape(corner)
        Box(Modifier.fillMaxSize().drawWithCache {
            val width = layout.window.width.dp.toPx()
            if (width <= 0f) return@drawWithCache onDrawBehind {}
            val origin = Offset(layout.window.left.dp.toPx(), layout.window.top.dp.toPx())
            val outline = shape.createOutline(Size(width, width), layoutDirection, density) as Outline.Generic
            val window = Path().apply { addPath(outline.path, origin) }
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addPath(window)
            }
            onDrawBehind {
            drawPath(scrim, Color.Black.copy(alpha = 0.6f))
            drawPath(window, color, style = Stroke(2.5.dp.toPx()))
            if (running && !reduced) clipPath(window) {
                val sweep = (phase * 2) % 1f
                val opacity = if (sweep < 0.1f) sweep / 0.1f else if (sweep > 0.85f) (1 - sweep) / 0.15f else 1f
                val y = origin.y + width * sweep
                val top = maxOf(origin.y, y - width * 0.45f)
                val trail = Size(width, y - top)
                val colors = (0..12).map { contrast.copy(alpha = (opacity * 0.24f * sin(PI * it / 12)).toFloat().coerceIn(0f, 1f)) }
                drawRect(Brush.horizontalGradient(colors, origin.x, origin.x + width), Offset(origin.x, top), trail)
                val cell = width / 18
                for (i in 0..18) {
                    val x = origin.x + cell * i
                    drawLine(contrast.copy(alpha = opacity * 0.3f), Offset(x, top), Offset(x, y), 1.dp.toPx())
                    val row = origin.y + cell * i
                    if (row in top..y) drawLine(contrast.copy(alpha = opacity * 0.3f), Offset(origin.x, row), Offset(origin.x + width, row), 1.dp.toPx())
                }
                drawLine(highlight.copy(alpha = opacity), Offset(origin.x, y), Offset(origin.x + width, y), 3.dp.toPx())
            }
            }
        })
        Text(
            stringResource(if (invalid) R.string.lite_scan_invalid else R.string.lite_scan_hint),
            Modifier.align(Alignment.TopStart).offset(layout.hint.left.dp, layout.hint.top.dp).width(layout.hint.width.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = if (invalid) MaterialTheme.colorScheme.error else Color.White,
            textAlign = TextAlign.Center,
            style = LiteType.Callout.copy(fontFamily = LocalLiteFontFamily.current),
            maxLines = 2,
        )
        Box(Modifier.align(Alignment.TopStart).offset(layout.torch.left.dp, layout.torch.top.dp)) { torch() }
    }
}

private object ScanGlyphs {
    val album = LiteGlyph(listOf(
        glyphBox(3, 6.4, 17.6, 21, 2.6),
        glyphPolyline(listOf(vertex(6.4, 3), vertex(21, 3, 2.6), vertex(21, 17.6)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(3, 17.2), vertex(7.8, 12.4, 0.8), vertex(12.2, 16.8, 0.8), vertex(14, 15, 0.8), vertex(17.6, 18.6)), role = LiteGlyphRole.Detail),
        glyphCircle(13.4, 10.6, 1.4, LiteGlyphRole.Detail, solid = true),
    ))
    val torchOff = LiteGlyph(listOf(
        glyphPolyline(listOf(vertex(2.24, 17.66, 1.8), vertex(6.34, 21.76, 1.8), vertex(12.92, 15.18, 1.2), vertex(15.61, 14.76, 0.8), vertex(16.74, 13.63, 1.2), vertex(10.37, 7.26, 1.2), vertex(9.24, 8.39, 0.8), vertex(8.82, 11.08, 1.2)), closed = true),
        glyphCircle(8.68, 15.32, 1.1, LiteGlyphRole.Detail, solid = true),
    ))
    val torch = LiteGlyph(torchOff.shapes + listOf(
        glyphPolyline(listOf(vertex(13.27, 5.64), vertex(15.25, 3.66)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(15.82, 8.18), vertex(17.8, 6.2)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(18.36, 10.73), vertex(20.34, 8.75)), role = LiteGlyphRole.Line),
    ))
}
