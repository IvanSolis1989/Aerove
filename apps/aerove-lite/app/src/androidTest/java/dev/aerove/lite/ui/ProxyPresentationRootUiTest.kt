package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.toPixelMap
import java.io.ByteArrayOutputStream
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.ProxyPresentationWorkspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProxyPresentationRootUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualProxyTabPersistsUnfoldAndReplacesToolbarForSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "proxy-root-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ProfileRepository(directory, ProfileValidator {})
        val png = ByteArrayOutputStream().also { output ->
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.MAGENTA); it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); it.recycle()
            }
        }.toByteArray()
        val icon = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
        val iconLoader = dev.aerove.lite.images.NetworkGroupIconLoader(okhttp3.OkHttpClient(),
            LiteSettingsStore(directory).requestPolicy("AEROVE-Lite-Test"), scope, File(directory, "icon-cache"))
        val profile = runBlocking(Dispatchers.IO) { repository.importText("Main", "proxy-groups:\n  - name: group\n    type: select\n    icon: $icon\n    proxies: [DIRECT]\nrules:\n  - MATCH,DIRECT\n") }
        var state by mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false),
            profiles = listOf(profile), selectedProfile = profile.id, groups = listOf(ProxyGroup("group", "Selector", "DIRECT", listOf(ProxyNode("DIRECT", "Direct"))))))
        val client = CoreApiClient(ControlEndpoint(9091, "0123456789abcdef0123456789abcdef"))
        val workspace = ProxyPresentationWorkspace(scope, { state.settings.proxyPresentation }, repository::updateProxyGroupUi,
            { saved -> state = state.copy(profiles = listOf(saved)) }, { true })
        workspace.profile(profile)
        workspace.bind(client, profile.id, client, { repository.readConfig(profile.id) }, { true })
        try {
            compose.replaceAppContent {
                IpQualityFixture(state) {
                    val base = LocalLiteFeatures.current()
                    val host = remember(base) { object : LiteFeatureHost by base { override val proxyPresentation = workspace
                        override val groupIcons: dev.aerove.lite.images.GroupIconLoader = iconLoader
                    } }
                    CompositionLocalProvider(LocalLiteFeatures provides { host }, LocalLiteMotionOverride provides true) {
                        LiteApp(state, UiTestActions({ state = state.copy(settings = it) }, state.settings))
                    }
                }
            }
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasText(context.getString(R.string.lite_tab_proxies))).performClick()
            compose.waitUntil(3000) { workspace.state.value.ready }
            compose.waitUntil(3000) {
                runCatching {
                    val image = compose.onNodeWithTag("proxy-group-icon-group").captureToImage()
                    val pixel = image.toPixelMap()[image.width / 2, image.height / 2]
                    pixel.red > 0.95f && pixel.blue > 0.95f && pixel.green < 0.05f
                }.getOrDefault(false)
            }
            compose.onAllNodesWithContentDescription(context.getString(R.string.lite_expand_group, "group"))[0].performClick()
            compose.waitUntil(3000) { runBlocking(Dispatchers.IO) { repository.profileSummary(profile.id)?.unfoldedProxyGroups == setOf("group") } }
            assertEquals(setOf("group"), runBlocking(Dispatchers.IO) { ProfileRepository(directory, ProfileValidator {}).profileSummary(profile.id)!!.unfoldedProxyGroups })
            compose.onNodeWithContentDescription(context.getString(R.string.lite_search)).performClick()
            compose.onNodeWithTag("proxy-search-field").assertIsDisplayed().performTextInput("no match")
            compose.onNodeWithTag("proxy-presentation-options").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.proxy_presentation_clear_search)).performClick()
            compose.onNodeWithTag("proxy-search-field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("proxy-search-field").assertDoesNotExist()
            compose.onNodeWithTag("proxy-presentation-options").assertIsDisplayed()
        } finally { runBlocking { workspace.closeAndJoin(); iconLoader.closeAndJoin() }; client.close(); scope.cancel(); directory.deleteRecursively() }
    }
}
