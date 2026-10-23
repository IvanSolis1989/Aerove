package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.notices.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClientNoticesHostUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val notices = ClientNotices()
    private val active = mutableStateOf(true)
    private val covered = mutableStateOf(false)
    private fun open() {
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                LiteTheme(LiteSettings(material = MaterialTier.Solid)) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                        CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                            LocalClientNotices provides notices) {
                            ClientNoticesHost(notices, active = active.value, covered = covered.value)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun everyStandardLevelRendersActualTextAndItsCorrectGlyphPresence() {
        open()
        for (level in NoticeLevel.entries) {
            var id = 0L
            compose.runOnUiThread { notices.clear(); id = notices.post("${level.wireName} fixture", level)!! }
            compose.onNodeWithText("${level.wireName} fixture").assertIsDisplayed()
            val icon = compose.onNodeWithTag("client-notice-icon-$id", useUnmergedTree = true)
            if (level == NoticeLevel.Info) icon.assertDoesNotExist() else icon.assertExists()
        }
    }
    @Test fun sameMessageReplacesOneActionAndActionIsConsumedOnce() {
        open(); var old = 0; var replacement = 0; var id = 0L
        compose.runOnUiThread {
            id = notices.post("same", action = ClientNoticeAction("old") { old++ })!!
            assertEquals(id, notices.post("same", action = ClientNoticeAction("retry") { replacement++ }))
        }
        compose.onAllNodesWithText("same").assertCountEquals(1)
        compose.onNodeWithTag("client-notice-action-$id", useUnmergedTree = true).performClick()
        compose.onNodeWithText("same").assertDoesNotExist()
        compose.runOnUiThread { assertFalse(notices.performAction(id)) }
        assertEquals(0, old); assertEquals(1, replacement)
    }
    @Test fun modalAndInactiveHostKeepQueueButDoNotRenderOrRunATimer() {
        covered.value = true; open()
        compose.runOnUiThread { notices.post("deferred", NoticeLevel.Warning) }
        compose.onNodeWithText("deferred").assertDoesNotExist()
        assertNull(notices.delayUntilNextEvent())
        compose.runOnUiThread { covered.value = false }
        compose.onNodeWithText("deferred").assertIsDisplayed()
        compose.runOnUiThread { active.value = false }
        compose.onNodeWithText("deferred").assertDoesNotExist(); assertNull(notices.delayUntilNextEvent())
        compose.runOnUiThread { active.value = true }
        compose.onNodeWithText("deferred").assertIsDisplayed()
    }
    @Test fun swipeAndAccessibilityDismissUseTheSameRealQueue() {
        open(); var id = 0L
        compose.runOnUiThread { id = notices.post("swipe")!! }
        compose.onNodeWithTag("client-notice-$id").performTouchInput { swipeRight() }
        compose.onNodeWithText("swipe").assertDoesNotExist()
        compose.runOnUiThread { id = notices.post("accessible")!! }
        compose.onNodeWithTag("client-notice-$id").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
        compose.onNodeWithText("accessible").assertDoesNotExist()
    }
}
