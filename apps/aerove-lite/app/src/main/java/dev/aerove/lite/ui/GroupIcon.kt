package dev.aerove.lite.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.aerove.lite.images.GroupIconImage
import dev.aerove.lite.images.GroupIconLoader
import dev.aerove.lite.images.decodeRasterIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

internal sealed interface GroupIconState {
    data object Empty : GroupIconState
    data object Loading : GroupIconState
    data object Ready : GroupIconState
    data class Error(val failure: Throwable) : GroupIconState
}

@Composable
internal fun GroupIcon(url: String?, loader: GroupIconLoader, modifier: Modifier = Modifier, size: Dp = 24.dp,
    active: Boolean = true, contentDescription: String? = null, onState: (GroupIconState) -> Unit = {},
    fallback: @Composable () -> Unit) {
    var image by remember(url, loader) { mutableStateOf<GroupIconImage?>(null) }
    var state by remember(url, loader) { mutableStateOf<GroupIconState>(if (url.isNullOrEmpty()) GroupIconState.Empty else GroupIconState.Loading) }
    var drawing by remember(url, loader) { mutableStateOf(false) }
    val report by rememberUpdatedState(onState)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resources = LocalResources.current
    val animate = !LocalLiteReducedMotion.current
    var drawable by remember(url, loader, resources) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(state) { report(state) }
    LaunchedEffect(url, loader, active, lifecycle, resources) {
        if (!url.isNullOrEmpty() && active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (image == null) state = GroupIconState.Loading
                loader.observe(url).collect { loaded ->
                    image = loaded
                    drawable = when (loaded) {
                        is GroupIconImage.Encoded -> loaded.state.newDrawable(resources).mutate()
                        is GroupIconImage.Animated -> decodeRasterIcon(loaded.bytes)
                        else -> null
                    }
                    state = GroupIconState.Ready
                    drawing = true
                }
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state = GroupIconState.Error(failure) }
            finally { drawing = false }
        }
    }
    val semantics = if (contentDescription == null) modifier else modifier.semantics { this.contentDescription = contentDescription }
    Box(semantics.size(size)) {
        when (val loaded = image) {
            is GroupIconImage.Raster -> Image(loaded.bitmap.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Fit)
            is GroupIconImage.Encoded, is GroupIconImage.Animated -> drawable?.let { GroupIconDrawable(it, drawing && animate, Modifier.size(size)) }
            null -> if (state is GroupIconState.Error || state == GroupIconState.Empty) fallback()
        }
    }
}

@Composable
private fun GroupIconDrawable(drawable: Drawable, active: Boolean, modifier: Modifier) {
    var revision by remember(drawable) { mutableIntStateOf(0) }
    DisposableEffect(drawable, active) {
        val handler = Handler(Looper.getMainLooper())
        val token = Any()
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { if (active) revision++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) {
                if (active) handler.postAtTime(what, token, maxOf(SystemClock.uptimeMillis(), at))
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what, token) }
        }
        drawable.setVisible(active, false)
        (drawable as? Animatable)?.let { if (active) it.start() else it.stop() }
        onDispose {
            (drawable as? Animatable)?.stop()
            drawable.setVisible(false, false)
            drawable.callback = null
            handler.removeCallbacksAndMessages(token)
        }
    }
    Canvas(modifier) {
        revision
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(size.width / width, size.height / height)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val left = ((size.width - targetWidth) / 2).toInt()
        val top = ((size.height - targetHeight) / 2).toInt()
        drawable.setBounds(left, top, left + targetWidth, top + targetHeight)
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
