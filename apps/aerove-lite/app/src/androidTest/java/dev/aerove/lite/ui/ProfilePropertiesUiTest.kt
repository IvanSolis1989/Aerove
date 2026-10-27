package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfilePropertiesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var initial = ProfileSummary("profile-fixture", "Original", "https://example.invalid/sub", 0,
        subscriptionUserInfo = "upload=1048576; download=1048576; total=4194304; expire=1700000000")
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false),
        profiles = listOf(initial)))
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }

    private lateinit var directory: java.io.File
    private lateinit var repository: dev.aerove.lite.data.ProfileRepository
    private lateinit var maintenance: dev.aerove.lite.data.ProfileMaintenanceFacade
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private lateinit var access: dev.aerove.lite.integration.ProfilePropertiesAccess

    @org.junit.Before fun preparePrivateRepository() {
        directory = java.io.File(context.cacheDir, "profile-properties-${java.util.UUID.randomUUID()}")
        repository = dev.aerove.lite.data.ProfileRepository(directory, dev.aerove.lite.data.ProfileValidator {})
        initial = kotlinx.coroutines.runBlocking { repository.importText("Original", "rules: [MATCH,DIRECT]", "https://example.invalid/sub") }
        initial = initial.copy(subscriptionUserInfo = "upload=1048576; download=1048576; total=4194304; expire=1700000000")
        state.value = state.value.copy(profiles = listOf(initial))
        maintenance = dev.aerove.lite.data.ProfileMaintenanceFacade(repository, scope)
        access = dev.aerove.lite.integration.RepositoryProfilePropertiesAccess(repository, maintenance, { true }, {
            state.value = state.value.copy(profiles = repository.list())
        })
    }

    @org.junit.After fun disposePrivateRepository() {
        maintenance.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        directory.deleteRecursively()
    }

    private fun launch() {
        compose.replaceAppContent {
            IpQualityFixture(state.value) {
                val base = LocalLiteFeatures.current()
                val host = androidx.compose.runtime.remember(base) { object : LiteFeatureHost by base {
                    override val profileProperties = access
                } }
                CompositionLocalProvider(LocalLiteMotionOverride provides true, LocalLiteFeatures provides { host }) { LiteApp(state.value, actions) }
            }
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasText(context.getString(R.string.lite_tab_profiles))).performClick()
    }

    private fun openProperties() {
        compose.onNodeWithTag("profile-${initial.id}").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.profile_properties)).performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithTag("profile-property-save").fetchSemanticsNodes().any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config } }
    }

    @Test fun rootPropertiesValidatesMinutesAndClosesOnlyAfterThePersistedMetadataReadBack() {
        launch()
        openProperties()
        compose.onNodeWithContentDescription(context.getString(R.string.profile_name)).performTextReplacement("Changed")
        compose.onNodeWithContentDescription(context.getString(R.string.profile_update_minutes)).performTextReplacement("0")
        compose.onNodeWithTag("profile-property-save").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.profile_interval_invalid)).assertIsDisplayed()
        assertTrue(actions.events.none { it.startsWith("updateProfileMetadata:") })
        compose.onNodeWithContentDescription(context.getString(R.string.profile_update_minutes)).performTextReplacement("5")
        compose.onNodeWithTag("profile-property-save").performScrollTo().performClick()
        compose.waitUntil(3000) { state.value.profiles.first().name == "Changed" }
        compose.onNodeWithTag("profile-property-save").assertDoesNotExist()
        val persisted = kotlinx.coroutines.runBlocking { dev.aerove.lite.data.ProfileRepository(directory, dev.aerove.lite.data.ProfileValidator {}).profileSummary(initial.id) }
        assertEquals("Changed", persisted?.name)
        assertEquals(300_000L, persisted?.updateIntervalMillis)
        compose.onNodeWithText("Changed").assertIsDisplayed()
    }

    @Test fun rootFailedSavePreservesTheInputAndCancelUpdateRemainsUsableWhileBusy() {
        val real = access
        access = object : dev.aerove.lite.integration.ProfilePropertiesAccess by real {
            override suspend fun save(expected: dev.aerove.lite.data.ProfileDocumentSnapshot, name: String, url: String?, autoUpdate: Boolean,
                intervalMillis: Long, secret: dev.aerove.lite.profilesource.AgeSecret?, replaceSecret: Boolean): ProfileSummary {
                throw java.io.IOException("fixture storage failure")
            }
        }
        launch()
        openProperties()
        compose.onNodeWithContentDescription(context.getString(R.string.profile_name)).performTextReplacement("Draft survives")
        compose.onNodeWithTag("profile-property-save").performScrollTo().performClick()
        compose.onNodeWithTag("profile-property-error").assertExists()
        assertEquals("Original", kotlinx.coroutines.runBlocking { repository.profileSummary(initial.id)?.name })
        compose.onNodeWithContentDescription(context.getString(R.string.profile_name)).assertTextEquals("Draft survives")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnUiThread { state.value = state.value.copy(busy = setOf("profile:${initial.id}"), updatingProfileIds = setOf(initial.id)) }
        compose.onNodeWithTag("profile-cancel-${initial.id}").performClick()
        assertTrue(actions.events.contains("cancelProfileUpdate:${initial.id}"))
    }

    @Test fun rootUsageDetailsFollowTheLatestHeaderWithoutSyntheticTotals() {
        launch()
        compose.onNodeWithTag("profile-usage-${initial.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.profile_usage_total)).assertIsDisplayed()
        compose.onNodeWithText(formatBytes(4_194_304)).assertIsDisplayed()
        compose.runOnUiThread {
            state.value = state.value.copy(profiles = listOf(initial.copy(subscriptionUserInfo = "upload=1; total=8388608; expire=0")))
        }
        compose.onNodeWithText(formatBytes(8_388_608)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_never_expires)).assertIsDisplayed()
        assertTrue(actions.events.none { it.startsWith("updateProfile:") })
    }
}
