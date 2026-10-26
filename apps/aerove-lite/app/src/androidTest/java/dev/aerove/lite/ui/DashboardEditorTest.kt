package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DashboardEditorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "dashboard-editor-${UUID.randomUUID()}")
    private val store = LiteSettingsStore(directory)
    private val settings = mutableStateOf(LiteSettings(aurora = false))
    private val open = mutableStateOf(true)
    private var writes = 0

    @After fun clean() { directory.deleteRecursively() }

    private fun read() = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
    private fun launch(initial: LiteSettings, identity: String, previewTap: (() -> Unit)? = null) {
        settings.value = initial
        open.value = true
        compose.replaceAppContent {
            key(identity) {
                CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                    LiteTheme(settings.value) {
                        val source = rememberLayerBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().layerBackdrop(source)) { LiteWallpaper(androidx.compose.runtime.remember { mutableLongStateOf(0L) }, Modifier.fillMaxSize()) }
                            CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source) {
                                if (open.value) DashboardEditor(settings.value, { value ->
                                    writes++
                                    runBlocking(Dispatchers.IO) { store.save(value) }
                                    settings.value = value
                                }, { open.value = false }, cardContent = if (previewTap == null) null else ({
                                    LiteButton("Card preview", previewTap, Modifier.fillMaxSize())
                                }))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scroll(tag: String) = compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag(tag))
    private fun confirm() = compose.onNodeWithContentDescription(context.getString(R.string.lite_confirm)).performClick()
    private fun moveUp(tag: String) {
        val actions = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread {
            assertTrue(actions.first { it.label == context.getString(R.string.lite_dashboard_move_up) }.action())
        }
    }

    @Test
    fun rootDashboardEditUsesRealCardsAndReturnsToTheSavedLayout() {
        val root = mutableStateOf(LiteState(settings = LiteSettings(aurora = false, material = MaterialTier.Solid,
            dashboardWidgets = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed))))
        val actions = UiTestActions { root.value = root.value.copy(settings = it) }
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(root.value, actions) }
        }
        compose.onNodeWithTag("dashboard-card-currentOutbound").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-upload-speed").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-card-networkSpeed").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).performClick()
        compose.onNodeWithTag("dashboard-row-currentOutbound").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-row-networkSpeed").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-add-networkSpeed").assertDoesNotExist()
        confirm()
        compose.onNodeWithTag("dashboard-layout").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-card-networkSpeed").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-upload-speed").assertIsDisplayed()
        assertEquals(listOf(DashboardWidget.CurrentOutbound), root.value.settings.dashboardWidgets)
        assertEquals(1, actions.settings.size)
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).performClick()
        compose.onNodeWithTag("dashboard-editor").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("dashboard-layout").assertIsDisplayed()
        assertEquals(1, actions.settings.size)
        assertTrue(actions.events.none { it == "connect" || it == "disconnect" })
    }

    @Test
    fun legacySpeedTypeIsExcludedAndOnlyConfirmedLayoutChangesSurviveRecreation() {
        val original = LiteSettings(aurora = false)
        runBlocking(Dispatchers.IO) { store.save(original) }
        assertEquals(10, DashboardWidget.entries.size)
        assertTrue(DashboardWidget.NetworkSpeed !in dashboardPresentationWidgets(read().dashboardWidgets))
        launch(read(), "first")
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_remove, context.getString(R.string.lite_status))).assertDoesNotExist()
        scroll("dashboard-add-memoryInfo")
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_add, context.getString(R.string.lite_dashboard_memory))).performClick()
        scroll("dashboard-row-memoryInfo")
        moveUp("dashboard-row-memoryInfo")
        assertEquals(0, writes)
        assertEquals(original, read())
        compose.runOnUiThread { settings.value = settings.value.copy(auroraSeed = 812893891) }
        confirm()
        val expected = (original.dashboardWidgets - DashboardWidget.NetworkSpeed).toMutableList().apply {
            add(size - 1, DashboardWidget.MemoryInfo)
        }
        assertEquals(expected, read().dashboardWidgets)
        assertEquals(812893891L, read().auroraSeed)
        assertEquals(1, writes)
        compose.activityRule.scenario.recreate()
        launch(read(), "recreated")
        scroll("dashboard-row-memoryInfo")
        compose.onNodeWithTag("dashboard-row-memoryInfo").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-add-networkSpeed").assertDoesNotExist()
        assertEquals(expected, read().dashboardWidgets)
        assertEquals(1, writes)
    }

    @Test
    fun cancelDiscardsTheDraftWithoutWritingTheSettings() {
        val original = LiteSettings(aurora = false)
        runBlocking(Dispatchers.IO) { store.save(original) }
        launch(original, "cancel")
        scroll("dashboard-add-memoryInfo")
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_add, context.getString(R.string.lite_dashboard_memory))).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_cancel)).performClick()
        assertEquals(original, read())
        assertEquals(0, writes)
    }

    @Test
    fun anEditorPreviewDoesNotInvokeTheCardsBusinessActions() {
        var taps = 0
        launch(LiteSettings(aurora = false, dashboardWidgets = listOf(DashboardWidget.CurrentOutbound)), "preview", { taps++ })
        compose.onNodeWithTag("dashboard-row-currentOutbound").performTouchInput { click() }
        assertEquals(0, taps)
        assertEquals(0, writes)
    }

    @Test
    fun longPressDragChangesOrderBeforeOneConfirmationWrite() {
        val legacy = LiteSettings(aurora = false, dashboardWidgets = listOf(
            DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed, DashboardWidget.OutboundMode,
            DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats,
        ))
        val initialSnapshot = runBlocking(Dispatchers.IO) { store.save(legacy) }
        assertEquals(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.OutboundMode,
            DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats), initialSnapshot.dashboardWidgets)
        launch(initialSnapshot, "drag")
        val first = compose.onNodeWithTag("dashboard-row-currentOutbound").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("dashboard-row-outboundMode").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("dashboard-row-outboundMode").performTouchInput {
            down(center)
            advanceEventTime(650)
            moveBy(Offset(0f, first.center.y - last.center.y))
            up()
        }
        val moved = compose.onNodeWithTag("dashboard-row-outboundMode").fetchSemanticsNode().boundsInRoot
        val current = compose.onNodeWithTag("dashboard-row-currentOutbound").fetchSemanticsNode().boundsInRoot
        assertTrue("The actual long-pressed row must move above the status row", moved.top < current.top)
        assertEquals(0, writes)
        assertEquals(initialSnapshot.dashboardWidgets, read().dashboardWidgets)
        confirm()
        val expectedWidgets = listOf(DashboardWidget.OutboundMode, DashboardWidget.CurrentOutbound,
            DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats)
        assertEquals(initialSnapshot.copy(dashboardWidgets = expectedWidgets), read())
        assertEquals(1, writes)
    }
}
