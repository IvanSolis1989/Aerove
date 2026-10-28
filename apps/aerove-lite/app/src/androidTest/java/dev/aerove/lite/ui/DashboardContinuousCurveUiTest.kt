package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.Traffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test

/** The user cancelled local text clearance: a paragraph's blank interior must not erase the area fill. */
class DashboardContinuousCurveUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun returningToTheDashboardRetainsTheRunningCurve() {
        val active = mutableStateOf(true)
        val state = LiteState(trafficHistory = List(30) { index ->
            Traffic((index % 6 + 1) * 2048L, (index % 4 + 1) * 4096L)
        }, trafficHistoryRevision = 30)
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                LiteTheme(LiteSettings(aurora = false, material = MaterialTier.Solid)) {
                    Box(Modifier.size(300.dp, 180.dp).background(Color(0xFFB6D1EB)).testTag("retained-curves")) {
                        StatusTrafficBackground(state, running = true, active = active.value, measured = true,
                            Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        fun pixels(): IntArray {
            val image = compose.onNodeWithTag("retained-curves").captureToImage()
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        val before = pixels()
        compose.runOnUiThread { active.value = false }
        compose.waitForIdle()
        assertArrayEquals(before, pixels())
        compose.runOnUiThread { active.value = true }
        compose.waitForIdle()
        assertArrayEquals(before, pixels())
    }

    @Test fun addingForegroundTextLeavesTheFillContinuousBetweenItsGlyphs() {
        val shown = mutableStateOf(false)
        val samples = List(30) { Traffic(8192, 4096) }
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                LiteTheme(LiteSettings(aurora = false, material = MaterialTier.Solid)) {
                    Box(Modifier.size(300.dp, 180.dp).background(Color(0xFFB6D1EB)).testTag("continuous-curves")) {
                        DashboardTrafficBackdrop(samples, running = true, active = true, Modifier.fillMaxSize())
                        if (shown.value) Text("I             I", Modifier.align(Alignment.Center), color = Color.Black,
                            fontSize = 44.sp)
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("continuous-curves").captureToImage().toPixelMap()
        compose.runOnUiThread { shown.value = true }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("continuous-curves").captureToImage().toPixelMap()
        // These pixels lie inside the paragraph's bounds, in the empty region between its two real glyphs.
        val x = before.width / 2
        val y = before.height / 2
        for (dx in -3..3) for (dy in -3..3) assertEquals(before[x + dx, y + dy], after[x + dx, y + dy])
    }
}
