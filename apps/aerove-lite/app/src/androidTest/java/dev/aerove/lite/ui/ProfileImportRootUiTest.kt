package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.ProfileImportWorkspace
import dev.aerove.lite.profilesource.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileImportRootUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var privateDirectory: File

    private fun fixture(block: (ProfileRepository, ProfileImportWorkspace, () -> LiteState) -> Unit) {
        val directory = File(context.cacheDir, "root-import-${UUID.randomUUID()}")
        privateDirectory = directory
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var state by mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false)))
        val repository = ProfileRepository(directory, ProfileValidator {},
            sourceDecoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, _ -> error("No key must not invoke decrypt") }))
        val workspace = ProfileImportWorkspace(scope, repository, { true }, {
            state = state.copy(profiles = repository.list())
        }, { prompt -> state = state.copy(profileImportKeyPrompt = prompt) },
            { state = state.copy(profileImportVisible = false) }, { error("Unexpected fixture failure") })
        try {
            compose.replaceAppContent {
                IpQualityFixture(state) {
                    val base = LocalLiteFeatures.current()
                    val host = remember(base) { object : LiteFeatureHost by base { override val profileImports = workspace } }
                    CompositionLocalProvider(LocalLiteFeatures provides { host }, LocalLiteMotionOverride provides true) {
                        LiteApp(state, UiTestActions {})
                    }
                }
            }
            block(repository, workspace) { state }
        } finally { runBlocking { workspace.quiesce() }; workspace.close(); scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun actualProfilesAddEntryWaitsForRealRepositoryReceiptAndDisplaysSavedProfile() = fixture { repository, _, _ ->
        compose.onNodeWithText(context.getString(R.string.lite_tab_profiles)).performClick()
        compose.onNodeWithText(context.getString(R.string.lite_add_subscription)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.age_name)).performTextInput("Root import")
        compose.onNodeWithTag("profile-import-input").performTextInput("rules: [MATCH,DIRECT]")
        compose.onNodeWithTag("profile-import-submit").performScrollTo().performClick()
        compose.waitUntil(3000) { runBlocking(Dispatchers.IO) { repository.list().isNotEmpty() } }
        compose.waitUntil(3000) { compose.onAllNodesWithTag("profile-import-input").fetchSemanticsNodes().isEmpty() }
        val saved = runBlocking(Dispatchers.IO) { ProfileRepository(privateDirectory, ProfileValidator {}).list().single() }
        assertEquals("Root import", saved.name)
        assertEquals("rules: [MATCH,DIRECT]", runBlocking(Dispatchers.IO) { repository.readConfig(saved.id) })
        compose.onNodeWithTag("profile-${saved.id}").assertExists()
        compose.onNodeWithTag("profile-import-input").assertDoesNotExist()
    }

    @Test fun missingFileKeyUsesRootCoveredPromptAndBackCancelsWithoutAnotherOpen() = fixture { repository, workspace, state ->
        var opens = 0
        compose.runOnUiThread {
            workspace.importFile("Encrypted") { opens++; "age-encryption.org/v1\nfixture".byteInputStream() }
        }
        compose.waitUntil(3000) { state().profileImportKeyPrompt }
        compose.onNodeWithText(context.getString(R.string.age_secret_title)).assertIsDisplayed()
        compose.onNodeWithTag("age-identity").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(3000) { !state().profileImportKeyPrompt }
        assertEquals(1, opens)
        assertTrue(runBlocking(Dispatchers.IO) { repository.list().isEmpty() })
    }
}
