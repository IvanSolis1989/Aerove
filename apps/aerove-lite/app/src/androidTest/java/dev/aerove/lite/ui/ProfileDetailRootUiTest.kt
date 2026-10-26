package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.ProfileDetailWorkspace
import dev.aerove.lite.overrides.OverrideMode
import dev.aerove.lite.providers.ProviderKind
import dev.aerove.lite.resources.*
import dev.aerove.lite.vpn.CoreEndpoint
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileDetailRootUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun dashboardSelectedProfileOpensRealProviderAndSharedReadOnlyEditor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir,"detail-root-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when(request.path) {
                    "/payload" -> "payload:\n - '+.actual.example'\n"
                    "/providers/proxies" -> "{\"providers\":{}}"
                    else -> """{"providers":{"one":{"vehicleType":"HTTP","behavior":"domain","format":"yaml","ruleCount":1}}}"""
                })
            }
            server.start()
            val validator = CompatibilityValidator(NativeConfigValidator { null }, File(directory,"runtime"))
            val manager = ResourceBundleManager(File(directory,"runtime"),validator,NativeResourceValidator { _,_,_,_ -> null })
            val repository = ProfileRepository(directory,validator,resources=manager)
            val profile = runBlocking(Dispatchers.IO) { repository.importText("Actual selected profile", """
                rule-providers:
                  one: {type: http, url: '${server.url("/payload")}', path: one.yaml, behavior: domain}
                rules: ['RULE-SET,one,DIRECT', 'MATCH,DIRECT']
            """.trimIndent()) }
            val prepared = runBlocking(Dispatchers.IO) { repository.prepare(profile.id) }
            val file = File(directory,"applied.yaml").also { it.writeText(prepared.yaml) }
            val endpoint = CoreEndpoint(server.port,"fixture-private-secret-00000000000000",AppliedConfigSnapshot(profile.id,file),prepared.revision,prepared.generation)
            val library = dev.aerove.lite.profilebuilder.BuilderResourceLibrary(directory)
            val inspector = object : NativeRulePayloadInspector {
                override suspend fun inspect(path: String) = RulePayloadInspection("text","domain",1)
                override suspend fun dump(path: String, behavior: String, format: String) = File(path).readText()
            }
            val managed = dev.aerove.lite.integration.ProfileDetailResources(repository,ResourceManagementRepository(repository),library,
                GlobalRulePayloadRepository(directory,library,manager,inspector,repository))
            val workspace = ProfileDetailWorkspace(repository,ResourceManagementRepository(repository),scope,{profile},{endpoint},{true},
                { error("Read-only preview must not apply") },{},{1L},{OverrideMode.Standard},managed)
            workspace.foreground(true);workspace.telemetryActive(true)
            val state = LiteState(profiles=listOf(profile),selectedProfile=profile.id,
                settings=LiteSettings(material=MaterialTier.Solid,aurora=false,dashboardWidgets=listOf(DashboardWidget.Profiles)))
            try {
                compose.replaceAppContent {
                    IpQualityFixture(state) {
                        val base = LocalLiteFeatures.current()
                        val host = remember(base) { object : LiteFeatureHost by base { override val profileDetails=workspace } }
                        CompositionLocalProvider(LocalLiteFeatures provides {host},LocalLiteMotionOverride provides true) { LiteApp(state,UiTestActions {}) }
                    }
                }
                compose.onNodeWithText(profile.name).performClick()
                compose.onNodeWithTag("profile-detail-sheet").assertIsDisplayed()
                compose.waitUntil(5000) { workspace.state.value.resources.isNotEmpty() && workspace.state.value.busy.isEmpty() }
                compose.onNodeWithTag("provider-${ProviderKind.Rule}-one").performScrollTo().performClick()
                compose.onNodeWithTag("provider-editor").assertIsDisplayed()
                compose.waitUntil(5000) { workspace.state.value.document?.session?.state?.value?.initialText != null }
                assertTrue(workspace.state.value.document!!.session.readOnly)
                assertTrue(workspace.state.value.document!!.session.state.value.initialText!!.contains("actual.example"))
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("provider-editor").assertDoesNotExist()
                compose.onNodeWithTag("profile-detail-sheet").assertExists()
            } finally { runBlocking { workspace.quiesce() };workspace.close();scope.cancel();directory.deleteRecursively() }
        }
    }
}
