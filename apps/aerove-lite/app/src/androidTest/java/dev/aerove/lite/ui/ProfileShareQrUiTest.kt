package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.R
import dev.aerove.lite.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileShareQrUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val firstUrl = "https://example.test/subscription?token=first%2Fexact"
    private val profile = mutableStateOf<ProfileSummary?>(ProfileSummary("id", "Current profile", firstUrl, 0))
    private val shown = mutableStateOf(true)
    private var dismisses = 0

    private fun open() {
        compose.replaceAppContent {
            LiteTheme(LiteSettings(material = MaterialTier.Solid, aurora = false)) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop) {
                        if (shown.value) ProfileShareQr(profile.value) { shown.value = false; dismisses++ }
                    }
                }
            }
        }
    }

    @Test fun latestStoredNameAndExactUrlReplaceThePreviouslyShownSymbol() {
        open()
        compose.waitUntil { compose.onAllNodesWithContentDescription(firstUrl).fetchSemanticsNodes().isNotEmpty() }
        val latest = "https://example.test/new?token=next%3D&name=中文"
        compose.runOnUiThread { profile.value = profile.value!!.copy(name = "Renamed profile", url = latest, subscriptionUserInfo = "not QR payload") }
        compose.waitUntil { compose.onAllNodesWithContentDescription(latest).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(firstUrl).assertDoesNotExist()
        compose.onNodeWithText("Renamed profile").assertExists()
        compose.onNodeWithTag("profile-share-qr-close").performClick()
        assertEquals(1, dismisses)
    }

    @Test fun oversizedLinkShowsTheActualCopyLinkAdviceWithoutAPlaceholderCode() {
        profile.value = profile.value!!.copy(url = "https://example.test/?token=" + "x".repeat(10000))
        open()
        compose.waitUntil { compose.onAllNodesWithTag("profile-share-qr-too-long").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.profile_qr_too_long)).assertExists()
        compose.onNodeWithTag("profile-share-qr-image").assertDoesNotExist()
    }

    @Test fun conversionToLocalDismissesTheOverlay() {
        open()
        compose.waitUntil { compose.onAllNodesWithTag("profile-share-qr-image").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { profile.value = profile.value!!.copy(url = null) }
        compose.waitUntil { dismisses == 1 }
        compose.onNodeWithTag("profile-share-qr-image").assertDoesNotExist()
    }

    @Test fun backClosesTheOverlayWithoutMutatingTheProfile() {
        open()
        compose.waitUntil { compose.onAllNodesWithTag("profile-share-qr-image").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { dismisses == 1 }
        assertEquals(firstUrl, profile.value!!.url)
        compose.onNodeWithTag("profile-share-qr-image").assertDoesNotExist()
    }
}
