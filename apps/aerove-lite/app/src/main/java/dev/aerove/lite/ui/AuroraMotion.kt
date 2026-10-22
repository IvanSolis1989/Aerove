package dev.aerove.lite.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalLiteMotionOverride = staticCompositionLocalOf<Boolean?> { null }
internal val LocalLiteReducedMotion = staticCompositionLocalOf { false }
internal val LocalAuroraPhase = staticCompositionLocalOf<State<Float>> { mutableStateOf(0f) }

@Composable
internal fun rememberLiteReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = !ValueAnimator.areAnimatorsEnabled() ||
        Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return LocalLiteMotionOverride.current ?: reduced
}

internal class AuroraMotionActivity(private val scope: CoroutineScope) {
    var active by mutableStateOf(false)
        private set
    val framesPerSecond: Int get() = if (active) 10 else 15
    private var pressed = false
    private var flinging = false
    private var idle: Job? = null

    fun pointer(value: Boolean) {
        if (pressed != value) { pressed = value; touch() }
    }

    fun fling(value: Boolean) { flinging = value; touch() }
    fun touch() { idle?.cancel(); idle = null; active = true; release() }
    fun reset() { idle?.cancel(); idle = null; pressed = false; flinging = false; active = false }

    private fun release() {
        if (pressed || flinging) return
        idle = scope.launch(start = CoroutineStart.UNDISPATCHED) { delay(350); active = false; idle = null }
    }
}

@Composable
internal fun rememberAuroraMotionActivity(page: Any?): AuroraMotionActivity {
    val scope = rememberCoroutineScope()
    val activity = remember(scope) { AuroraMotionActivity(scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduced = LocalLiteReducedMotion.current
    DisposableEffect(page, lifecycle, reduced) {
        if (reduced) activity.reset()
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) activity.reset()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); activity.reset() }
    }
    return activity
}

@Composable
internal fun Modifier.auroraMotionActivity(activity: AuroraMotionActivity, enabled: Boolean = true): Modifier {
    if (!enabled || LocalLiteReducedMotion.current) return this
    val scroll = remember(activity) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed != Offset.Zero) activity.touch()
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                activity.fling(true)
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                activity.fling(false)
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(scroll).pointerInput(activity) {
        try {
            awaitPointerEventScope {
                while (true) activity.pointer(awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed })
            }
        } finally { activity.reset() }
    }
}

@Composable
internal fun rememberAuroraPhase(enabled: Boolean, restartKey: Any? = null, activity: AuroraMotionActivity? = null): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduced = LocalLiteReducedMotion.current
    val phase = remember(restartKey) { mutableStateOf(0f) }
    LaunchedEffect(enabled, lifecycle, reduced, phase) {
        if (enabled && !reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val base = phase.value
            var origin = -1L
            var grid = 0L
            var previousRate = activity?.framesPerSecond ?: 15
            while (isActive) {
                val now = withFrameNanos { it }
                if (origin < 0) { origin = now; grid = now }
                phase.value = base + (now - origin) / 90_000_000_000f
                val fps = activity?.framesPerSecond ?: 15
                if (previousRate != fps) { grid = now; previousRate = fps }
                val frame = (now - grid) * fps / 1_000_000_000L
                val due = grid + ((frame + 1) * 1_000_000_000L + fps - 1) / fps
                delay(maxOf(1L, (due - now + 999_999L) / 1_000_000L))
            }
        }
    }
    return phase
}
