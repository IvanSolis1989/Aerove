package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.profilesource.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiles against real components; execution belongs to the Android device lane. */
class AgeBatchImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun host(content: @Composable () -> Unit) {
        compose.replaceAppContent {
            LiteTheme(LiteSettings(material = MaterialTier.Solid, aurora = false)) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                        LocalLiteListState provides rememberLazyListState(), LocalLitePageActive provides true,
                        LocalLiteMotionOverride provides true) { content() }
                }
            }
        }
    }
    @Test fun secretIsMaskedAndCancelDoesNotSubmit() {
        var submitted = 0; var dismissed = 0
        host { AgeSecretKeyPrompt(false, null, { dismissed++ }, { submitted++; it.close() }) }
        compose.onNodeWithTag("age-identity").performTextInput("synthetic-test-key")
        compose.onNodeWithTag("age-identity").assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
        compose.onNodeWithText(context.getString(R.string.age_cancel)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, submitted); assertEquals(1, dismissed) }
    }
    @Test fun batchModeKeepsSingleInputAndClearsPrivateIdentity() {
        var input: String? = null
        host { BatchImportForm(BatchImportState(), null, {}, { it.secret?.close(); fail("Unexpected single input") }, { input = it }, {}) }
        compose.onNodeWithContentDescription(context.getString(R.string.age_url)).performTextInput("https://example.com/sub")
        compose.onNodeWithTag("age-identity").performTextInput("synthetic-test-key")
        compose.onNodeWithText(context.getString(R.string.age_batch_mode)).performScrollTo().performClick()
        compose.onNodeWithTag("age-identity").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.age_import)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://example.com/sub", input) }
    }
}
