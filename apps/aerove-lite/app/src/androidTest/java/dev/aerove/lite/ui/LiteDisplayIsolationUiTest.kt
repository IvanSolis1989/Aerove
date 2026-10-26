package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.navigation.TabAnimation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiteDisplayIsolationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hiddenScenesDoNotMeasureOrCollectAndReturningKeepsTheirComposition() {
        val selected = mutableStateOf(0)
        val visible = mutableStateOf(true)
        val values = List(3) { MutableStateFlow(0) }
        val layouts = IntArray(3)
        val emissions = IntArray(3)
        val last = IntArray(3)
        val identity = arrayOfNulls<Any>(3)
        compose.replaceAppContent {
            val overlay = remember { LiteOverlayState() }
            CompositionLocalProvider(LocalLiteOverlay provides overlay, LocalLiteReducedMotion provides true,
                LocalDiagnosticsForeground provides true) {
                LiteTabTransitionHost(listOf(0, 1, 2), selected.value, TabAnimation.Fade, active = true,
                    visible = visible.value, pageActive = visible.value, modifier = Modifier.fillMaxSize()) { item, _ ->
                    val token = remember { Any() }
                    val value = displayValue(values[item], 0).value
                    SideEffect { identity[item] = token; emissions[item]++; last[item] = value }
                    Layout(content = { Box(Modifier.fillMaxSize()) }) { children, constraints ->
                        layouts[item]++
                        val child = children.single().measure(constraints)
                        layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, 0) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val first = compose.runOnIdle { identity[0] }
        compose.runOnUiThread { selected.value = 1 }
        compose.waitForIdle()
        compose.runOnUiThread { selected.value = 2 }
        compose.waitForIdle()
        val hiddenLayouts = layouts.copyOf()
        val hiddenEmissions = emissions.copyOf()
        compose.runOnUiThread { values[0].value = 99; values[1].value = 88 }
        compose.waitForIdle()
        assertEquals(hiddenLayouts[0], layouts[0])
        assertEquals(hiddenLayouts[1], layouts[1])
        assertEquals(hiddenEmissions[0], emissions[0])
        assertEquals(hiddenEmissions[1], emissions[1])
        compose.runOnUiThread { visible.value = false }
        compose.waitForIdle()
        val noneMeasured = layouts.copyOf()
        compose.runOnUiThread { selected.value = 0; values[0].value = 100 }
        compose.waitForIdle()
        assertTrue(noneMeasured.contentEquals(layouts))
        compose.runOnUiThread { visible.value = true }
        compose.waitUntil { last[0] == 100 }
        assertSame(first, identity[0])
        assertTrue(layouts[0] > noneMeasured[0])
    }
}
