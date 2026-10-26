package dev.aerove.lite.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LogLine
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.observability.HistoryDocuments
import dev.aerove.lite.observability.HistoryKind
import dev.aerove.lite.observability.ObservabilityFeature
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryDocumentControlUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = mutableStateOf(false)
    private val deleted = CopyOnWriteArrayList<String>()
    private val saved = CopyOnWriteArrayList<String>()
    private val feature = ObservabilityFeature(scope, object : HistoryDocuments {
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) { saved += reference }
        override suspend fun deleteCreated(reference: String): Boolean { deleted += reference; return true }
    }) {}
    private val currentFeature = mutableStateOf(feature)
    private val additionalFeatures = mutableListOf<ObservabilityFeature>()
    private val registry = PickerRegistry()

    private class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        val requests = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,
                contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input).action)
            requests += requestCode
        }
        fun created(uri: String) { dispatchResult(requests.last(), Activity.RESULT_OK, Intent().setData(Uri.parse(uri))) }
    }

    private fun mount() {
        feature.bindSession(1); feature.setForeground(true); feature.lease(HistoryKind.Requests)
        compose.replaceAppContent {
            val overlay = androidx.compose.runtime.remember { LiteOverlayState() }
            LiteTheme(LiteSettings(material = MaterialTier.Solid, aurora = false)) {
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(LocalLiteOverlay provides overlay, LocalLitePageActive provides active.value,
                    LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                    LocalActivityResultRegistryOwner provides registry) {
                    Box {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                        LogsScreen(currentFeature.value, PaddingValues(16.dp))
                    }
                }
            }
        }
    }

    @After fun close() { feature.close(); additionalFeatures.forEach { it.close() }; scope.cancel() }

    @Test fun inactiveLateExportIsCancelledWithoutResumingHistoryPresentation() {
        mount()
        repeat(2) { attempt ->
            compose.runOnUiThread {
                feature.acceptLog(1, LogLine(attempt.toLong(), "info", "retained-$attempt"))
                feature.requestLogExport()
                assertNotNull(feature.state.value.document)
            }
            compose.waitUntil { feature.state.value.document == null }
            compose.onNodeWithText("retained-$attempt").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(registry.requests.isEmpty())
                assertEquals(attempt + 1, feature.state.value.snapshot.logs.size)
                assertTrue(feature.observationsEnabled.value)
                assertTrue(saved.isEmpty())
            }
        }
        compose.runOnUiThread { active.value = true }
        compose.waitForIdle()
        assertTrue(registry.requests.isEmpty())
    }

    @Test fun inactiveAfterDispatchRetiresTheCreateAndCleansItsLateResultOnce() {
        active.value = true
        mount()
        compose.runOnUiThread { feature.acceptLog(1, LogLine(0, "info", "retained")); feature.requestLogExport() }
        compose.waitUntil { registry.requests.size == 1 }
        compose.runOnUiThread { active.value = false }
        compose.waitUntil { feature.state.value.document == null }
        val reference = "content://history-document-control/late-result"
        compose.runOnUiThread { registry.created(reference) }
        compose.waitUntil { deleted.size == 1 }
        compose.runOnUiThread { active.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(reference), deleted.toList())
            assertTrue(saved.isEmpty())
            assertEquals(1, registry.requests.size)
            assertNull(feature.state.value.document)
            assertEquals(1, feature.state.value.snapshot.logs.size)
            assertTrue(feature.observationsEnabled.value)
        }
    }

    @Test fun replacementWithTheSameRequestIdCannotReceiveTheOldPickersUri() {
        active.value = true
        mount()
        var originalId = 0L
        compose.runOnUiThread {
            feature.acceptLog(1, LogLine(0, "info", "original")); feature.requestLogExport()
            originalId = feature.state.value.document!!.id
        }
        compose.waitUntil { registry.requests.size == 1 }
        val savedReplacement = CopyOnWriteArrayList<Pair<String, List<String>>>()
        val deletedReplacement = CopyOnWriteArrayList<String>()
        val replacement = ObservabilityFeature(scope, object : HistoryDocuments {
            override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) {
                savedReplacement += reference to lines
            }
            override suspend fun deleteCreated(reference: String): Boolean { deletedReplacement += reference; return true }
        }) {}
        additionalFeatures += replacement
        compose.runOnUiThread {
            replacement.bindSession(2); replacement.setForeground(true); replacement.lease(HistoryKind.Requests)
            replacement.acceptLog(2, LogLine(0, "info", "replacement")); replacement.requestLogExport()
            assertEquals(originalId, replacement.state.value.document!!.id)
            currentFeature.value = replacement
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertFalse(replacement.state.value.document!!.dispatched)
            feature.close()
        }
        val oldReference = "content://history-document-control/old-owner"
        compose.runOnUiThread { registry.created(oldReference) }
        compose.waitUntil { deleted.size == 1 && registry.requests.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf(oldReference), deleted.toList())
            assertTrue(saved.isEmpty())
            assertTrue(savedReplacement.isEmpty())
            assertTrue(deletedReplacement.isEmpty())
            assertEquals(originalId, replacement.state.value.document!!.id)
            assertTrue(replacement.state.value.document!!.dispatched)
        }
        val newReference = "content://history-document-control/new-owner"
        compose.runOnUiThread { registry.created(newReference) }
        compose.waitUntil { savedReplacement.size == 1 }
        compose.runOnIdle {
            assertEquals(newReference, savedReplacement.single().first)
            assertEquals(listOf("1970-01-01T00:00:00Z [info] replacement"), savedReplacement.single().second)
            assertNull(replacement.state.value.document)
            assertEquals(listOf(oldReference), deleted.toList())
        }
    }

}
