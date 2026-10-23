package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.GlobalResourceWorkspace
import dev.aerove.lite.profilebuilder.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlobalResourceRootUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun toolsOpensRealSharedLibraryAndHeaderBackConsumesCategoryFirst() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "global-root-${UUID.randomUUID()}")
        val library = BuilderResourceLibrary(directory)
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(directory, "runtime"))
        val manager = ResourceBundleManager(File(directory, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null })
        val repository = ProfileRepository(directory, ProfileValidator {}, resources = manager, globalResources = library)
        val parser = object : NativeRulePayloadInspector {
            override fun inspect(path: String): RulePayloadInspection = error("No rule payload used")
            override fun dump(path: String, behavior: String, format: String): String = error("No rule payload used")
        }
        val workspace = GlobalResourceWorkspace(library, repository,
            GlobalRulePayloadRepository(directory, library, manager, parser, repository), { error("No node edit used") }, {})
        val node = runBlocking(Dispatchers.IO) {
            library.replace(library.snapshot(), BuilderResources(proxies = listOf(BuilderEntry("actual node", mapOf("type" to "direct")))))
                .resources.proxies.single().id!!
        }
        try {
            compose.replaceAppContent {
                IpQualityFixture(LiteState()) {
                    val base = LocalLiteFeatures.current()
                    val host = remember(base) { object : LiteFeatureHost by base { override val globalResources = workspace } }
                    CompositionLocalProvider(LocalLiteFeatures provides { host }, LocalLiteMotionOverride provides true) {
                        LiteApp(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false)), UiTestActions {})
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.lite_tab_tools)).performClick()
            compose.onNodeWithText(context.getString(R.string.global_resources_title)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.builder_proxies)).performClick()
            compose.onNodeWithTag("global-resource-row-$node").assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.lite_back)).performClick()
            compose.onNodeWithTag("global-resource-library").assertExists()
            compose.onNodeWithTag("global-resource-row-$node").assertDoesNotExist()
            assertEquals(node, runBlocking(Dispatchers.IO) { BuilderResourceLibrary(directory).snapshot().resources.proxies.single().id })
        } finally { runBlocking { workspace.closeAndJoin() }; directory.deleteRecursively() }
    }
}
