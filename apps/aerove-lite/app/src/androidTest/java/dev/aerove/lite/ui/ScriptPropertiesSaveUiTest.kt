package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.overrides.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScriptPropertiesSaveUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "script-properties-${UUID.randomUUID()}")
    @After fun clean() { directory.deleteRecursively() }
    @Test fun failedReceiptRetainsInputAndOnlySavedReadbackDismisses() {
        val repository = OverrideRepository(directory)
        val old = ScriptResource("script", "Original", content = "old")
        runBlocking(Dispatchers.IO) { repository.putScript(old) }
        val state = mutableStateOf(ScriptLibraryState(scripts = listOf(old)))
        var attempts = 0L
        compose.replaceAppContent {
            LiteTheme(LiteSettings(material = MaterialTier.Solid, aurora = false)) {
                val backdrop = rememberLayerBackdrop(); val overlay = remember { LiteOverlayState() }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                        LocalLiteListState provides rememberLazyListState(), LocalLiteOverlay provides overlay,
                        LocalLitePageActive provides true, LocalLiteMotionOverride provides true) {
                        ScriptLibraryScreen(state.value, { candidate ->
                            attempts++
                            if (attempts == 1L) state.value = state.value.copy(saves = mapOf(candidate.id to ScriptSaveReceipt(attempts, false, "save_failed")))
                            else {
                                runBlocking(Dispatchers.IO) { repository.putScript(candidate) }
                                state.value = state.value.copy(scripts = runBlocking(Dispatchers.IO) { repository.snapshot().scripts },
                                    saves = mapOf(candidate.id to ScriptSaveReceipt(attempts, false)))
                            }
                        }, {}, {}, { _, _ -> }, {}, {}, { _, _ -> })
                        overlay.content?.invoke()
                    }
                }
            }
        }
        compose.onNodeWithTag("override-script-row-0").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.override_properties)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.override_name)).performTextReplacement("Changed")
        compose.onNodeWithText(context.getString(R.string.override_save)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.override_name)).assertTextContains("Changed")
        assertEquals("Original", runBlocking(Dispatchers.IO) { repository.snapshot().scripts.single().name })
        compose.onNodeWithText(context.getString(R.string.override_save)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.override_name)).assertDoesNotExist()
        assertEquals("Changed", runBlocking(Dispatchers.IO) { repository.snapshot().scripts.single().name })
    }
}
