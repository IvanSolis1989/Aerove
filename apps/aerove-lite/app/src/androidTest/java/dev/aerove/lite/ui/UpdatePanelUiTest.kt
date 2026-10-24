package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.ipquality.IpQualityFeature
import dev.aerove.lite.profiles.ProfileListFeature
import dev.aerove.lite.updates.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpdatePanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directory by lazy { File(context.cacheDir, "update-ui-${UUID.randomUUID()}") }
    @Volatile private var automatic = true
    private var prompted: String? = null
    @Volatile private var checks = 0
    private val feature by lazy {
        val tag = "lite-v0.2.0-beta.1+2"
        val transport = object : UpdateTransport {
            override suspend fun releases(page: Int): String {
                checks++
                return JSONArray().put(JSONObject().put("tag_name", tag).put("body", "Lite-specific release notes")
                    .put("assets", JSONArray(listOf(LITE_APK_NAME, "SHA256SUMS", "BUILD_INFO.json").map { name ->
                        JSONObject().put("name", name).put("size", 10).put("browser_download_url", "https://github.com/$LITE_REPOSITORY/releases/download/$tag/$name")
                    }))).toString()
            }
            override suspend fun metadata(url: String): ByteArray = error("No download in UI prompt test")
            override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long, Long) -> Unit) = error("No download in UI prompt test")
        }
        val platform = object : UpdatePlatform {
            override val packageName = LITE_PACKAGE
            override suspend fun identity() = UpdateIdentity("0.1.0-beta.1", 1, listOf("arm64-v8a"))
            override suspend fun automaticEnabled() = automatic
            override suspend fun promptedTag() = prompted
            override suspend fun savePromptedTag(tag: String) { prompted = tag }
            override suspend fun resetPromptedTag(): Unit = error("Prompt reset is unused in the update prompt UI fixture")
            override suspend fun canInstall() = false
            override suspend fun installUri(file: File) = error("No install in UI prompt test")
        }
        UpdateFeature(UpdateRepository(transport, directory, UpdateApkVerifier { _, _ -> }), platform, scope) { automatic = it }
    }
    private val host get() = object : LiteFeatureHost {
        override val updates: UpdateFeature get() = feature
        override val groupIcons: dev.aerove.lite.images.GroupIconLoader get() = error("Unused update fixture group icons")
        override val resourceManagement: dev.aerove.lite.resources.ResourceManagementSession get() = error("Unused update fixture resource management")
        override val profileProperties: dev.aerove.lite.integration.ProfilePropertiesAccess get() = error("Unused update fixture properties")
        override val profileImports: dev.aerove.lite.integration.ProfileImportWorkspace get() = error("Unused update fixture imports")
        override val globalResources: dev.aerove.lite.integration.GlobalResourceWorkspace get() = error("Unused update fixture global resources")
        override val resources: dev.aerove.lite.integration.ResourcePageWorkspace get() = error("Unused update fixture resources")
        override val profileDetails: dev.aerove.lite.integration.ProfileDetailWorkspace get() = error("Unused update fixture profile details")
        override val overrides: dev.aerove.lite.integration.OverrideWorkspace get() = error("Unused update fixture override workspace")
        override val proxyPresentation: dev.aerove.lite.integration.ProxyPresentationWorkspace get() = error("Unused update fixture proxy presentation")
        override val appAccess: dev.aerove.lite.appaccess.AppAccessFeature get() = error("Unused update fixture application access")
        override fun closeAppAccess() = Unit
        override val maintenance: dev.aerove.lite.maintenance.MaintenanceFeature get() = error("Unused update fixture maintenance")
        override fun developerLogsTest(): Unit = error("Unused update fixture developer logs action")
        override fun developerMessageTest(): Unit = error("Unused update fixture developer message action")
        override val observability: dev.aerove.lite.observability.ObservabilityFeature get() = error("Unused fixture observability")
        override suspend fun createProfileBuilder(name: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession = error("Unused fixture profile builder creation")
        override fun profileBuilder(profileId: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession = error("Unused fixture profile builder")
        override suspend fun profileBuilderResources(): dev.aerove.lite.profilebuilder.BuilderResources = error("Unused fixture profile builder resources")
        override fun closeProfileBuilder(): Unit = error("Unused fixture close profile builder")
        override val backup: BackupFeature get() = error("Unused fixture backup")
        override val profileList: ProfileListFeature get() = error("Unused fixture profiles")
        override val ipQuality: IpQualityFeature get() = error("Unused fixture quality")
        override fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession = error("Unused fixture editor")
        override fun closeEditor() = Unit
        override fun editorSaved(profileId: String) = Unit
        override fun importProfileText(name: String, input: String) = Unit
    }
    private fun launch() {
        compose.replaceAppContent {
            val update by feature.state.collectAsState()
            CompositionLocalProvider(LocalLiteMotionOverride provides true, LocalLiteFeatures provides { host }) {
                LiteApp(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false),
                    updatePromptVisible = update.shouldShowPrompt), UiTestActions {})
            }
        }
    }
    @After fun cleanup() { kotlinx.coroutines.runBlocking { feature.closeAndJoin() }; scope.cancel(); directory.deleteRecursively() }

    @Test fun startupPromptShowsNotesAndCloseKeepsAutomaticChecksEnabled() {
        launch()
        compose.runOnUiThread { feature.autoCheckOnce() }
        compose.waitUntil { feature.state.value.phase == UpdatePhase.Available }
        compose.onNodeWithText("Lite-specific release notes").assertIsDisplayed()
        compose.onNodeWithText("lite-v0.2.0-beta.1+2").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_close)).performScrollTo().performClick()
        compose.waitUntil { !feature.state.value.shouldShowPrompt }
        assertTrue(automatic)
        assertEquals("lite-v0.2.0-beta.1+2", prompted)
        compose.runOnUiThread { feature.autoCheckOnce() }
        compose.waitUntil { feature.state.value.phase == UpdatePhase.Available }
        assertEquals(1, checks)
    }

    @Test fun doNotRemindPersistsThroughOwnerCallbackAndAboutKeepsLicenses() {
        launch()
        compose.runOnUiThread { feature.autoCheckOnce() }
        compose.waitUntil { feature.state.value.phase == UpdatePhase.Available }
        compose.onNodeWithText(context.getString(R.string.update_no_remind)).performScrollTo().performClick()
        compose.waitUntil { !automatic && !feature.state.value.shouldShowPrompt }
        compose.onNodeWithText(context.getString(R.string.lite_tab_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.lite_about)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.lite_license_aerove)).assertExists()
        compose.onNodeWithText(context.getString(R.string.update_check)).performScrollTo().performClick()
        compose.waitUntil { checks == 2 && feature.state.value.shouldShowPrompt }
        compose.onNodeWithText("Lite-specific release notes").assertIsDisplayed()
    }
}
