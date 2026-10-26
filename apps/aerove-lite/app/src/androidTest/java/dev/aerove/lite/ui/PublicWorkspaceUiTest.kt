package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.OverrideWorkspace
import dev.aerove.lite.profilebuilder.*
import dev.aerove.lite.profiles.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PublicWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun suspendedCoreKeepsRunningControlAndDisconnectsInsteadOfStartingAnotherRun() {
        val actions = UiTestActions {}
        val state = LiteState(phase = VpnPhase.Running, suspended = true, listening = false,
            profiles = listOf(ProfileSummary("suspended-fixture", "Suspended fixture", null, 0)), selectedProfile = "suspended-fixture",
            listenerError = "Listener rejected", settings = LiteSettings(material = MaterialTier.Solid, aurora = false))
        compose.replaceAppContent { LiteApp(state, actions) }
        compose.onNodeWithText(context.getString(R.string.integration_suspended)).assertIsDisplayed()
        compose.onNodeWithText("Listener rejected").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.integration_suspended)).performClick()
        assertEquals(listOf("disconnect"), actions.events.filter { it == "connect" || it == "disconnect" })
        assertEquals(VpnPhase.Running, state.phase)
    }

    @Test fun actualProfilesEntryCreatesOnlyAnInMemoryDraftAndBackLeavesRepositoryUntouched() {
        val directory = File(context.cacheDir, "root-builder-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var validationCalls = 0
        val repository = ProfileRepository(directory, ProfileValidator {
            validationCalls++
            error("An unsaved draft must not reach native validation")
        })
        val maintenance = ProfileMaintenanceFacade(repository, scope)
        val list = ProfileListFeature(repository, maintenance, scope,
            AndroidProfileDocuments(context.contentResolver), onChanged = {})
        var draft: ProfileBuilderSession? = null
        val host = object : LiteFeatureHost {
            override val profileDetails: dev.aerove.lite.integration.ProfileDetailWorkspace get() = error("Unused builder fixture profile details")
            override val resources: dev.aerove.lite.integration.ResourcePageWorkspace get() = error("Unused builder fixture resources page")
            override val globalResources: dev.aerove.lite.integration.GlobalResourceWorkspace get() = error("Unused builder fixture global resources")
            override val groupIcons: dev.aerove.lite.images.GroupIconLoader get() = error("Unused builder fixture group icons")
            override val resourceManagement: dev.aerove.lite.resources.ResourceManagementSession get() = error("Unused builder fixture resources")
            override val profileImports: dev.aerove.lite.integration.ProfileImportWorkspace get() = error("Unused builder fixture imports")
            override val profileProperties: dev.aerove.lite.integration.ProfilePropertiesAccess get() = error("Unused builder fixture properties")
            override val profileList = list
            override val proxyPresentation: dev.aerove.lite.integration.ProxyPresentationWorkspace get() = error("Unused builder fixture proxies")
            override val backup: BackupFeature get() = error("Unused fixture backup")
            override val ipQuality: dev.aerove.lite.ipquality.IpQualityFeature get() = error("Unused fixture quality")
            override val updates: dev.aerove.lite.updates.UpdateFeature get() = error("Unused fixture updates")
            override val observability: dev.aerove.lite.observability.ObservabilityFeature get() = error("Unused fixture history")
            override val appAccess: dev.aerove.lite.appaccess.AppAccessFeature get() = error("Unused fixture applications")
            override val maintenance: dev.aerove.lite.maintenance.MaintenanceFeature get() = error("Unused fixture maintenance")
            override fun closeAppAccess() = Unit
            override fun developerLogsTest() = error("Unused fixture developer logs")
            override fun developerMessageTest() = error("Unused fixture developer messages")
            override val overrides: OverrideWorkspace get() = error("Unused fixture overrides")
            override suspend fun createProfileBuilder(name: String) = ProfileBuilderSession.create(repository, name).also { draft = it }
            override fun profileBuilder(profileId: String) = requireNotNull(draft).also { check(it.profileId == profileId) }
            override suspend fun profileBuilderResources() = BuilderResourceLibrary(directory).snapshot().resources
            override fun closeProfileBuilder() { draft?.cancel() }
            override fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession = error("Unused fixture editor")
            override fun closeEditor() = Unit
            override fun editorSaved(profileId: String) = error("An unsaved draft cannot report saved")
            override fun importProfileText(name: String, input: String) = error("Unused fixture import")
        }
        try {
            compose.replaceAppContent {
                CompositionLocalProvider(LocalLiteMotionOverride provides true, LocalLiteFeatures provides { host }) {
                    LiteApp(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false)), UiTestActions {})
                }
            }
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasText(context.getString(R.string.lite_tab_profiles))).performClick()
            compose.onNodeWithTag("profile-create-custom").performScrollTo().performClick()
            compose.onNodeWithTag("profile-builder").assertIsDisplayed()
            assertTrue(requireNotNull(draft).isNew)
            assertNull(requireNotNull(draft).savedProfileId)
            assertTrue(runBlocking(Dispatchers.IO) { repository.list().isEmpty() })
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("profile-create-custom").assertExists()
            assertTrue(runBlocking(Dispatchers.IO) { ProfileRepository(directory, ProfileValidator { error("Unexpected validation") }).list().isEmpty() })
            assertEquals(0, validationCalls)
        } finally {
            list.close()
            maintenance.close()
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
