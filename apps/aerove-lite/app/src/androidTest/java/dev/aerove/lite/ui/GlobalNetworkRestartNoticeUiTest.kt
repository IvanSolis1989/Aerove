package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlobalNetworkRestartNoticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val required = mutableStateOf(true)
    private val covered = mutableStateOf(false)
    private var restarts = 0

    private fun open() {
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            LiteTheme(LiteSettings()) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop) {
                        GlobalNetworkRestartNotice(required.value, { restarts++ }, covered.value)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun explicitActionRunsOnceAndSameEpisodeDoesNotReappear() {
        open()
        compose.onNodeWithTag("global-network-restart-action").performClick()
        assertEquals(1, restarts)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("global-network-restart").assertDoesNotExist()
        compose.runOnUiThread { covered.value = true }
        compose.runOnUiThread { covered.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("global-network-restart").assertDoesNotExist()
    }

    @Test fun coveredModalDefersWarningAndFalseClosesWithoutRestart() {
        covered.value = true
        open()
        compose.onNodeWithTag("global-network-restart").assertDoesNotExist()
        compose.runOnUiThread { covered.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("global-network-restart").assertIsDisplayed()
        compose.runOnUiThread { required.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("global-network-restart").assertDoesNotExist()
        assertEquals(0, restarts)
    }

    @Test fun swipeDismissesUntilAnotherRequiredEpisode() {
        open()
        compose.onNodeWithTag("global-network-restart").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("global-network-restart").assertDoesNotExist()
        compose.runOnUiThread { required.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { required.value = true }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("global-network-restart").assertIsDisplayed()
        assertEquals(0, restarts)
    }
}
