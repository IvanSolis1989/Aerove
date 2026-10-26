package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.ResourcePageWorkspace
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.*
import dev.aerove.lite.resources.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ResourcePageRootUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualToolsResourcesEntryDisplaysGeoAndPersistsAutoUpdateThroughTheSettingsOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "resource-root-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = LiteSettingsStore(directory)
        var state by mutableStateOf(LiteState(settings = runBlocking(Dispatchers.IO) { store.read() }
            .copy(material = MaterialTier.Solid, aurora = false)))
        val validator = CompatibilityValidator(NativeConfigValidator { error("Global Resources must not compile a profile") }, File(directory, "runtime"))
        val inspector = object : NativeRulePayloadInspector {
            override suspend fun inspect(path: String): RulePayloadInspection = error("No payload operation")
            override suspend fun dump(path: String, behavior: String, format: String): String = error("No payload operation")
        }
        val library = BuilderResourceLibrary(directory)
        val manager = ResourceBundleManager(File(directory, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> error("No sync operation") }, offlineRules = inspector)
        val profiles = ProfileRepository(directory, ProfileValidator { error("No selected profile preparation") }, resources = manager, globalResources = library)
        val remote = ResourceManagementLibraryRepository(library, manager, GlobalRulePayloadRepository(directory, library, manager, inspector, profiles))
        val repository = ResourceManagementRepository(profiles, offlineRules = inspector)
        val scripts = ScriptLibrarySession(OverrideRepository(directory), SubscriptionDownloader())
        val geo = GeoResourceCatalog(File(directory, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> error("No sync operation") }, ResourceDownloader(), { state.settings.geoResources })
        val session = ResourceManagementSession(repository, scope, {}, library = remote, geoCatalog = geo)
        val maintenance = ResourceManagementMaintenance(geo, remote, scripts, { state.settings.geoResources }, {})
        val workspace = ResourcePageWorkspace(session, scripts, maintenance, repository, scope, { null }, { true }, { false }, { throw it })
        try {
            compose.replaceAppContent {
                IpQualityFixture(state) {
                    val base = LocalLiteFeatures.current()
                    val host = remember(base) { object : LiteFeatureHost by base { override val resources = workspace } }
                    CompositionLocalProvider(LocalLiteFeatures provides { host }, LocalLiteMotionOverride provides true) {
                        LiteApp(state, UiTestActions { requested -> scope.launch {
                            store.save(requested); state = state.copy(settings = store.read())
                        } })
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.lite_tab_tools)).performClick()
            compose.onNodeWithText(context.getString(R.string.proxy_tools_resources)).performScrollTo().performClick()
            compose.onNodeWithText("MMDB").assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.proxy_tools_auto_update)).performClick()
            compose.waitUntil(3000) { runBlocking(Dispatchers.IO) { store.read().geoResources.autoUpdate } }
            assertTrue(runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read().geoResources.autoUpdate })
            compose.onNodeWithText(context.getString(R.string.proxy_tools_update_interval)).assertExists()
            assertNull(session.state.value.profileId)
        } finally {
            runBlocking { workspace.quiesce(); session.closeAndJoin(); scripts.closeAndJoin() }
            workspace.close(); scope.cancel(); directory.deleteRecursively()
        }
    }
}
