package dev.aerove.lite.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.ui.colorutilities.Hct
import kotlin.math.roundToInt

private enum class PaletteMode { Grid, Spectrum, Sliders }
private val gridHues = doubleArrayOf(230.0, 262.0, 285.0, 310.0, 340.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 145.0)
private val gridTones = doubleArrayOf(25.0, 33.0, 41.0, 49.0, 57.0, 65.0, 73.0, 81.0, 89.0)
private val paletteGrid by lazy {
    IntArray(120) { i ->
        if (i < 12) Hct.from(0.0, 0.0, 100.0 - i * 100.0 / 11.0).toInt()
        else Hct.from(gridHues[i % 12], 200.0, gridTones[i / 12 - 1]).toInt()
    }
}

@Composable
internal fun FullColorPalette(color: Int, onChanged: (Int) -> Unit) {
    var mode by remember { mutableStateOf(PaletteMode.Grid) }
    var hsv by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }) }
    LaunchedEffect(color) {
        if (android.graphics.Color.HSVToColor(hsv) != color) hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LiteChoiceRow(PaletteMode.entries, mode, { stringResource(when (it) {
            PaletteMode.Grid -> R.string.lite_palette_grid
            PaletteMode.Spectrum -> R.string.lite_palette_spectrum
            PaletteMode.Sliders -> R.string.lite_palette_sliders
        }) }, { mode = it })
        Box(Modifier.animateContentSize(tween(if (LocalLiteReducedMotion.current) 0 else 250, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)))) {
            when (mode) {
                PaletteMode.Grid -> ColorGrid(color, onChanged)
                PaletteMode.Spectrum -> ColorSpectrum(color, onChanged)
                PaletteMode.Sliders -> HsvSliders(hsv) { changed -> hsv = changed; onChanged(android.graphics.Color.HSVToColor(changed)) }
            }
        }
    }
}

@Composable
private fun Modifier.palettePick(pick: (Offset, IntSize) -> Unit): Modifier {
    val latest by rememberUpdatedState(pick)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            latest(down.position, size)
            do {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                latest(pointer.position, size)
                pointer.consume()
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
private fun ColorGrid(color: Int, onChanged: (Int) -> Unit) {
    val shape = rememberLiteShape(LiteCorner.Small)
    val label = stringResource(R.string.lite_palette_grid)
    Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(shape).testTag("aurora-color-grid")
        .semantics { contentDescription = label }
        .palettePick { position, size ->
            if (size.width > 0 && size.height > 0) {
                val x = (position.x * 12 / size.width).toInt().coerceIn(0, 11)
                val y = (position.y * 10 / size.height).toInt().coerceIn(0, 9)
                onChanged(paletteGrid[y * 12 + x])
            }
        }.drawWithCache {
            val cell = Size(size.width / 12, size.height / 10)
            val outline = shape.createOutline(cell, layoutDirection, this)
            val selected = paletteGrid.indexOf(color)
            onDrawBehind {
                paletteGrid.forEachIndexed { i, value -> drawRect(Color(value), Offset(i % 12 * cell.width, i / 12 * cell.height), cell) }
                if (selected >= 0) translate(selected % 12 * cell.width, selected / 12 * cell.height) {
                    drawOutline(outline, Color.White, style = Stroke(2.dp.toPx()))
                }
            }
        })
}

@Composable
private fun ColorSpectrum(color: Int, onChanged: (Int) -> Unit) {
    val label = stringResource(R.string.lite_palette_spectrum)
    val hue = remember { (0..6).map { Color.hsl((it * 60 % 360).toFloat(), 1f, 0.5f) } }
    val shape = rememberLiteShape(LiteCorner.Small)
    var picked by remember { mutableStateOf<Pair<Offset, Int>?>(null) }
    val selected = picked?.takeIf { it.second == color }?.first ?: run {
        val c = Color(color)
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
        Offset(hsv[0] / 360f, 1f - (maxOf(c.red, c.green, c.blue) + minOf(c.red, c.green, c.blue)) / 2)
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(shape).testTag("aurora-color-spectrum")
        .semantics { contentDescription = label }
        .palettePick { position, size ->
            if (size.width > 0 && size.height > 0) {
                val fraction = Offset((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
                val value = Color.hsl((fraction.x * 360).coerceAtMost(359.99f), 1f, 1 - fraction.y).toArgb()
                picked = fraction to value
                onChanged(value)
            }
        }.drawWithCache {
            val horizontal = Brush.horizontalGradient(hue)
            val vertical = Brush.verticalGradient(listOf(Color.White, Color.Transparent, Color.Black))
            val center = Offset(selected.x * size.width, selected.y * size.height)
            onDrawBehind {
                drawRect(horizontal)
                drawRect(vertical)
                drawCircle(Color(color), 14.dp.toPx(), center)
                drawCircle(Color.White, 14.dp.toPx(), center, style = Stroke(3.dp.toPx()))
            }
        })
}

@Composable
private fun HsvSliders(hsv: FloatArray, onChanged: (FloatArray) -> Unit) {
    fun changed(index: Int, value: Float) = onChanged(hsv.copyOf().apply { this[index] = value })
    fun tone(saturation: Float = hsv[1], brightness: Float = hsv[2]) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], saturation, brightness)))
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PaletteSlider(stringResource(R.string.lite_palette_hue), "${hsv[0].roundToInt()}°", hsv[0], 360f,
            (0..6).map { Color.hsv((it * 60 % 360).toFloat(), 1f, 1f) }, tone(1f, 1f)) { changed(0, it) }
        PaletteSlider(stringResource(R.string.lite_palette_saturation), "${(hsv[1] * 100).roundToInt()}%", hsv[1], 1f,
            listOf(tone(0f), tone(1f)), tone()) { changed(1, it) }
        PaletteSlider(stringResource(R.string.lite_palette_brightness), "${(hsv[2] * 100).roundToInt()}%", hsv[2], 1f,
            listOf(tone(brightness = 0f), tone(brightness = 1f)), tone()) { changed(2, it) }
    }
}

@Composable
private fun PaletteSlider(label: String, valueLabel: String, value: Float, maximum: Float, colors: List<Color>, thumb: Color, onChanged: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LiteText(label, style = LiteType.Label)
            LiteText(valueLabel, style = LiteType.Label)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp).progressSemantics(value, 0f..maximum).semantics {
            contentDescription = label
            stateDescription = valueLabel
            setProgress { onChanged(it.coerceIn(0f, maximum)); true }
        }.focusable().palettePick { position, size ->
            val inset = 14.dp.value * (size.height / 44f)
            val width = size.width - inset * 2
            if (width > 0) onChanged(((position.x - inset) / width).coerceIn(0f, 1f) * maximum)
        }) {
            val shape = rememberLiteShape(LiteCorner.Full)
            LitePane(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth().height(20.dp), corner = LiteCorner.Full) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(colors), shape))
            }
            LitePane(Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 28.dp) * (value / maximum)).size(28.dp)
                .border(BorderStroke(3.dp, Color.White), shape), corner = LiteCorner.Full,
                solidColor = thumb, glassTint = thumb.copy(alpha = 0.18f)) {}
        }
    }
}
