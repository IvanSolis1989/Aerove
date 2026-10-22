package dev.aerove.lite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class AuroraWallpaperKey(val colors: List<Long>, val seed: Long, val dark: Boolean)
private class AuroraWallpaperLayer(val key: AuroraWallpaperKey, val origin: Float, initialAlpha: Float) {
    val alpha = Animatable(initialAlpha)
}

@Composable
internal fun LiteWallpaper(
    phase: State<Float>,
    modifier: Modifier = Modifier,
    colors: List<Long> = FullAuroraColors,
    seed: Long = FullAuroraSeed,
) {
    val visuals = LocalLiteVisuals.current
    if (!visuals.aurora) {
        Box(modifier.background(visuals.page))
        return
    }
    AuroraWallpaper(phase, colors, seed, visuals.dark, modifier)
}

@Composable
internal fun AuroraWallpaper(
    phase: State<Float>, colors: List<Long>, seed: Long, dark: Boolean, modifier: Modifier = Modifier,
) {
    val requested = remember(colors, seed, dark) { AuroraWallpaperKey(colors.toList(), seed, dark) }
    val layers = remember {
        fullAuroraMesh(seed, colors, dark)
        mutableStateListOf(AuroraWallpaperLayer(requested, phase.value, 1f))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(requested, foreground) {
        if (foreground && layers.last().key != requested) {
            withContext(Dispatchers.Default) { fullAuroraMesh(requested.seed, requested.colors, requested.dark) }
            layers.add(AuroraWallpaperLayer(requested, phase.value, 0f))
        }
    }
    val reduced = LocalLiteReducedMotion.current
    val target = layers.last()
    Box(modifier.background(LocalLiteVisuals.current.page).testTag(if (target.key == requested) "aurora-wallpaper-ready" else "aurora-wallpaper-building")) {
        layers.forEach { layer -> key(layer) {
            val destination = if (layer === target) 1f else 0f
            LaunchedEffect(layer, target, lifecycle, reduced) {
                if (reduced) layer.alpha.snapTo(destination)
                else lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    val duration = (350 * abs(destination - layer.alpha.value)).roundToInt()
                    layer.alpha.animateTo(destination, tween(duration, easing = LinearEasing))
                    if (destination == 0f) layers.remove(layer)
                }
                if (reduced && destination == 0f) layers.remove(layer)
            }
            val frame = layer.key
            val localPhase = remember(layer, phase) { derivedStateOf { (phase.value - layer.origin).coerceAtLeast(0f) } }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = layer.alpha.value }
                .fullAuroraWallpaper(localPhase, frame.dark, frame.colors, frame.seed))
        }
        }
    }
}
