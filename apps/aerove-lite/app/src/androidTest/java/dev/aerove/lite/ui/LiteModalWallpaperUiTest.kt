package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiteModalWallpaperUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sameWindowTranslucentModalKeepsRealClockMovingButOpaqueScanStopsIt() {
        compose.mainClock.autoAdvance = false
        val modal = mutableStateOf(false)
        val scanOpaque = mutableStateOf(false)
        var clock: LongState? = null
        compose.replaceAppContent {
            LiteWindowFocusHost { focused ->
                CompositionLocalProvider(LocalLiteMotionOverride provides false) {
                    LiteTheme(LiteSettings(aurora = true)) {
                        val time = rememberAuroraTime(liteWallpaperVisible(focused, scanOpaque.value, false))
                        SideEffect { clock = time }
                        val source = rememberLayerBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().layerBackdrop(source)) { LiteWallpaper(time, Modifier.fillMaxSize()) }
                            CompositionLocalProvider(LocalLiteBackdrop provides source) {
                                if (modal.value) LiteModal("modal", { modal.value = false }) { Text("translucent modal") }
                            }
                        }
                    }
                }
            }
        }
        fun advanceUntil(predicate: () -> Boolean) = compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeBy(100); predicate()
        }
        advanceUntil { clock?.longValue?.let { it > 0L } == true }
        val before = compose.runOnIdle { clock!!.longValue }
        compose.runOnUiThread { modal.value = true }
        advanceUntil { clock!!.longValue > before }
        compose.runOnUiThread { scanOpaque.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val stopped = compose.runOnIdle { clock!!.longValue }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertEquals(stopped, compose.runOnIdle { clock!!.longValue })
        compose.runOnUiThread { scanOpaque.value = false }
        advanceUntil { clock!!.longValue > stopped }
    }
}
