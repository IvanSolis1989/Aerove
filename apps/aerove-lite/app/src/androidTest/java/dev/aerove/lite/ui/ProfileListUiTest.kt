package dev.aerove.lite.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.*
import dev.aerove.lite.profiles.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProfileListUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val yaml = "proxies: []\nproxy-groups: []\nrules: []\n"
    private val directory get() = storage
    private lateinit var storage: File
    private lateinit var repository: ProfileRepository
    private lateinit var maintenance: ProfileMaintenanceFacade
    private lateinit var feature: ProfileListFeature
    private lateinit var first: ProfileSummary
    private lateinit var second: ProfileSummary
    private lateinit var remote: ProfileSummary
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false)))
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }
    private val documents = ConcurrentHashMap<String, ByteArray>()
    private val registry = PickerRegistry()
    private var factories = 0
    private var blockNetwork = false
    private val networkStarted = CountDownLatch(1)
    private val releaseNetwork = CountDownLatch(1)

    private class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val requests = mutableListOf<Pair<Int, String>>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            requests += requestCode to contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input).action.orEmpty()
        }
        fun complete(uri: Uri?) {
            val code = requests.last().first
            dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) })
        }
    }

    @Before fun prepare() = runBlocking(Dispatchers.IO) {
        storage = File(context.cacheDir, "profile-list-ui-${UUID.randomUUID()}")
        val http = OkHttpClient.Builder().addInterceptor {
            if (blockNetwork) { networkStarted.countDown(); releaseNetwork.await(10, TimeUnit.SECONDS) }
            throw IOException("fixture subscription failure")
        }.build()
        repository = ProfileRepository(directory, ProfileValidator { require(it.contains("rules:")) }, SubscriptionDownloader(http))
        first = repository.importText("First", yaml)
        second = repository.importText("Second", yaml)
        remote = repository.importText("Remote", yaml, "https://example.invalid/subscription")
        maintenance = ProfileMaintenanceFacade(repository, scope)
        feature = ProfileListFeature(repository, maintenance, scope, object : ProfileDocuments {
            override suspend fun <T> read(reference: String, action: suspend (InputStream) -> T): T =
                requireNotNull(documents[reference]).inputStream().use { action(it) }
            override suspend fun write(reference: String, bytes: ByteArray) { documents[reference] = bytes.copyOf() }
            override suspend fun deleteCreated(reference: String): Boolean = documents.remove(reference) != null
        }, onChanged = { reload() })
        reload()
    }

    private suspend fun reload() {
        val profiles = repository.list()
        withContext(Dispatchers.Main) { state.value = state.value.copy(profiles = profiles) }
    }

    private val host: LiteFeatureHost get() = object : LiteFeatureHost {
        override val profileList: ProfileListFeature get() = feature
        override val backup: BackupFeature get() = error("Unused fixture backup")
        override fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession = error("Unused fixture editor")
        override fun closeEditor() = Unit
        override fun editorSaved(profileId: String) = Unit
        override fun importProfileText(name: String, input: String) = Unit
    }

    @After fun cleanup() {
        releaseNetwork.countDown()
        feature.close()
        maintenance.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun launch() {
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true, LocalActivityResultRegistryOwner provides registry,
                LocalLiteFeatures provides { factories++; host }) { LiteApp(state.value, actions) }
        }
        compose.onNodeWithText(context.getString(R.string.lite_tab_profiles)).performClick()
    }

    private fun menu(profile: ProfileSummary, resource: Int) {
        compose.onNodeWithTag("profile-${profile.id}").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(resource)).performScrollTo().performClick()
    }

    @Test fun rootSortHasOneConfirmedRepositoryWriteAndCopyUsesTheCurrentSubscriptionUrl() {
        launch()
        assertEquals(0, factories)
        menu(remote, R.string.profile_list_copy_url)
        compose.runOnUiThread {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(remote.url, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithTag("profile-list-sort").performScrollTo().performClick()
        val move = compose.onNodeWithTag("profile-order-${second.id}").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { assertTrue(move.first { it.label == context.getString(R.string.profile_list_move_up) }.action()) }
        assertEquals(first.id, runBlocking(Dispatchers.IO) { repository.list().first().id })
        compose.onNodeWithTag("profile-order-save").performScrollTo().performClick()
        compose.waitUntil { feature.state.value.notice == ProfileListNotice.Reordered && !feature.state.value.busy }
        assertEquals(second.id, runBlocking(Dispatchers.IO) { ProfileRepository(directory, ProfileValidator {}).list().first().id })
        compose.onNodeWithTag("profile-order-save").assertDoesNotExist()
    }

    @Test fun rootExportPickerDispatchesOnceAndCancellationLeavesTheStoredSourceUntouched() {
        launch()
        menu(first, R.string.profile_list_export)
        compose.waitUntil { registry.requests.size == 1 }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.requests.single().second)
        compose.runOnUiThread { state.value = state.value.copy(traffic = Traffic(upload = 1)) }
        compose.waitForIdle()
        assertEquals(1, registry.requests.size)
        compose.runOnUiThread { registry.complete(null) }
        compose.waitUntil { feature.state.value.notice == ProfileListNotice.Cancelled && !feature.state.value.busy }
        assertEquals(yaml, runBlocking(Dispatchers.IO) { repository.readConfig(first.id) })
        menu(first, R.string.profile_list_export)
        compose.waitUntil { registry.requests.size == 2 }
        val uri = Uri.parse("content://profile-list-fixture/export")
        compose.runOnUiThread { registry.complete(uri) }
        compose.waitUntil { feature.state.value.notice == ProfileListNotice.Exported && !feature.state.value.busy }
        assertArrayEquals(yaml.toByteArray(), documents[uri.toString()])
    }

    @Test fun rootReplacementPromptsForAutomaticUpdatesAndReadsTheValidatedSavedDocument() {
        launch()
        menu(remote, R.string.profile_list_replace)
        compose.onNodeWithText(context.getString(R.string.profile_list_replace_auto_prompt)).assertIsDisplayed()
        assertTrue(registry.requests.isEmpty())
        compose.onNodeWithText(context.getString(R.string.profile_list_disable_auto)).performClick()
        compose.waitUntil { registry.requests.size == 1 }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.requests.single().second)
        val uri = Uri.parse("content://profile-list-fixture/replacement")
        val replacement = yaml + "# real replacement fixture\n"
        documents[uri.toString()] = replacement.toByteArray()
        compose.runOnUiThread { registry.complete(uri) }
        compose.waitUntil { feature.state.value.notice == ProfileListNotice.Replaced && !feature.state.value.busy }
        val saved = runBlocking(Dispatchers.IO) { repository.list().first { it.id == remote.id } }
        assertFalse(saved.autoUpdate)
        assertEquals(remote.name, saved.name)
        assertEquals(replacement, runBlocking(Dispatchers.IO) { repository.readConfig(remote.id) })
    }

    @Test fun rootBatchShowsActualFailuresAndItsCancelControlRemainsUsableDuringTheUpdate() {
        launch()
        compose.onNodeWithTag("profile-list-update-all").performClick()
        compose.waitUntil { feature.state.value.notice == ProfileListNotice.Updated && !feature.state.value.busy }
        compose.onNodeWithText("Remote: fixture subscription failure").assertIsDisplayed()
        assertEquals(2, feature.state.value.batch?.skipped)
        assertEquals(1, feature.state.value.batch?.failures?.size)
        blockNetwork = true
        compose.onNodeWithTag("profile-list-update-all").performScrollTo().performClick()
        compose.waitUntil { networkStarted.count == 0L }
        compose.onNodeWithTag("profile-list-cancel").performClick()
        releaseNetwork.countDown()
        compose.waitUntil { feature.state.value.notice == ProfileListNotice.Cancelled && !feature.state.value.busy }
        assertEquals(yaml, runBlocking(Dispatchers.IO) { repository.readConfig(remote.id) })
    }
}
