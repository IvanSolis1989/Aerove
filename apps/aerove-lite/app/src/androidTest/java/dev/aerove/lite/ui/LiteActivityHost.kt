package dev.aerove.lite.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.aerove.lite.MainActivity

internal typealias LiteComposeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

internal fun LiteComposeRule.replaceAppContent(content: @Composable () -> Unit) {
    runOnUiThread { activity.setContent(content = content) }
    if (!mainClock.autoAdvance) mainClock.advanceTimeByFrame()
    waitForIdle()
}
