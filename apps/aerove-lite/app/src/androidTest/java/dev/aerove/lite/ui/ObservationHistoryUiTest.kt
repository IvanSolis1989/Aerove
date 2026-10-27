package dev.aerove.lite.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.R
import dev.aerove.lite.*
import dev.aerove.lite.observability.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.data.LiteSettingsStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ObservationHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = mutableStateOf(true)
    private val runtime = UUID.randomUUID().toString()
    private val requestId = UUID.randomUUID().toString()
    private val registry = PickerRegistry()
    private var flushes = 0
    private val tables = mutableStateOf(HistoryTablePreferences())
    private val preferencesDirectory by lazy { File(context.cacheDir, "history-ui-${UUID.randomUUID()}") }
    private val settings by lazy { LiteSettingsStore(preferencesDirectory) }
    private val writes = Mutex()
    private val feature by lazy { ObservabilityFeature(scope, object : HistoryDocuments {
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) {
            val target = File(reference)
            val digest = target.outputStream().use { writeHistoryExport(it, lines, isCurrent) }
            target.inputStream().use { verifyHistoryExport(it, digest, isCurrent) }
        }
        override suspend fun deleteCreated(reference: String) = File(reference).let { !it.exists() || it.delete() }
    }) { flushes++ } }

    private class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        val requests = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,
                contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input).action)
            requests += requestCode
        }
        fun cancel() { dispatchResult(requests.last(), Activity.RESULT_CANCELED, Intent()) }
    }

    private fun open(kind: HistoryKind, wide: Boolean = false, persist: Boolean = false) {
        feature.bindSession(1); feature.setForeground(true); feature.lease(HistoryKind.Requests)
        compose.replaceAppContent {
            val overlay = remember { LiteOverlayState() }
            LiteTheme(LiteSettings(material = MaterialTier.Solid, aurora = false)) {
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(LocalLiteOverlay provides overlay, LocalLitePageActive provides active.value,
                    LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                    LocalActivityResultRegistryOwner provides registry,
                    LocalDensity provides if (wide) Density(0.3f) else LocalDensity.current) {
                    Box {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                        val onChange: (HistoryTableChange) -> Unit = { change ->
                            if (!persist) tables.value = tables.value.apply(change)
                            else scope.launch { writes.withLock {
                                val current = settings.read()
                                settings.save(current.copy(historyTables = current.historyTables.apply(change)))
                                val readback = settings.read().historyTables
                                withContext(Dispatchers.Main) { tables.value = readback }
                            } }
                        }
                        when (kind) {
                            HistoryKind.Requests -> RequestsScreen(feature, PaddingValues(16.dp), tables.value, onChange)
                            HistoryKind.Dns -> DnsQueriesScreen(feature, PaddingValues(16.dp), tables.value, onChange)
                            HistoryKind.Logs -> LogsScreen(feature, PaddingValues(16.dp))
                        }
                        overlay.content?.invoke()
                    }
                }
            }
        }
    }

    @After fun close() { feature.close(); scope.cancel(); preferencesDirectory.deleteRecursively() }

    @Test fun actualRequestWireRendersSearchDetailsAndCopiesOriginalMetadata() {
        open(HistoryKind.Requests)
        val wire = """{"type":"observations","sessionId":"$runtime","generation":"1","cursor":"1","dropped":"0","events":[{"cursor":"1","kind":"request","data":{"id":"$requestId","start":"2026-10-23T00:00:00Z","metadata":{"network":"tcp","host":"example.test","destinationPort":443,"process":"Browser","processPath":"/real/browser"},"chains":["Node","Select"],"rule":"DOMAIN","rulePayload":"example.test","upload":"7","download":"9"}}]}"""
        compose.runOnUiThread { feature.acceptWindow(1, decodeObservationWindow(wire)) }
        compose.waitUntil { compose.onAllNodesWithTag("history-request-$requestId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-request-$requestId").assertExists()
        val searchField = compose.onNode(
            hasContentDescription(context.getString(R.string.lite_search)) and hasSetTextAction() and
                hasAnyAncestor(hasTestTag("history-search")),
            useUnmergedTree = true,
        )
        searchField.performTextReplacement("browser missing")
        compose.waitUntil { compose.onAllNodesWithText(context.getString(R.string.lite_no_matches)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.lite_no_matches)).assertExists()
        searchField.performTextReplacement("browser example")
        compose.waitUntil { compose.onAllNodesWithTag("history-request-$requestId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-request-$requestId").performClick()
        compose.onNodeWithText("/real/browser").assertExists()
        compose.onNodeWithText(context.getString(R.string.history_copy)).performScrollTo().performClick()
        compose.runOnUiThread {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val value = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(value.contains(requestId)); assertTrue(value.contains("/real/browser"))
        }
    }

    @Test fun dnsProvenanceAndFailureAreVisibleAndClearUsesTheRealFeatureAction() {
        open(HistoryKind.Dns)
        val query = DnsHistoryEntry("1:1", Instant.parse("2026-10-23T00:00:00Z"), "failure.test", "AAAA", "Rule",
            "udp://1.1.1.1:53", true, emptyList(), "SERVFAIL", "resolver refused", 9u)
        compose.runOnUiThread { feature.acceptWindow(1, ObservationWindow(runtime, 1u, 1u, 0u, listOf(ObservationEvent.Dns(1u, query)))) }
        compose.waitUntil { compose.onAllNodesWithTag("history-dns-$runtime:1:1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-dns-$runtime:1:1").performClick()
        compose.onNodeWithText("resolver refused").assertExists()
        compose.onNodeWithText("udp://1.1.1.1:53").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_close)).performClick()
        compose.onNodeWithTag("history-dns-flush").performClick()
        compose.waitUntil { feature.state.value.notice == HistoryNotice.DnsCacheCleared }
        assertEquals(1, flushes); assertEquals(1, feature.state.value.snapshot.dns.size)
    }

    @Test fun logExportUsesOnePickerAndCancellationPreservesRecords() {
        open(HistoryKind.Logs)
        compose.runOnUiThread { feature.acceptLog(1, LogLine(0, "warning", "real retained log")) }
        compose.onNodeWithTag("history-log-export").performClick()
        compose.waitUntil { registry.requests.size == 1 }
        compose.runOnUiThread { feature.acceptLog(1, LogLine(1, "info", "next")) }
        compose.waitForIdle(); assertEquals(1, registry.requests.size)
        compose.runOnUiThread { registry.cancel() }
        compose.waitUntil { feature.state.value.notice == HistoryNotice.Cancelled }
        compose.waitUntil { feature.state.value.snapshot.logs.size == 2 }
        assertEquals(2, feature.state.value.snapshot.logs.size)
    }

    @Test fun inactiveRetainedPageCannotLaunchNewPickerAndDoesNotClearCollectedHistory() {
        active.value = false; open(HistoryKind.Logs)
        feature.lease(HistoryKind.Logs).use {
            compose.runOnUiThread { feature.acceptLog(1, LogLine(0, "info", "kept")) }
            compose.waitUntil { feature.state.value.snapshot.logs.size == 1 }
        }
        compose.runOnUiThread { feature.requestLogExport() }
        compose.waitUntil { feature.state.value.document == null }
        assertTrue(registry.requests.isEmpty())
        assertEquals(1, feature.state.value.snapshot.logs.size)
        assertTrue(feature.observationsEnabled.value)
    }

    @Test fun tableDragWritesActualSettingsAndDoubleTapRestoresDefaultWidths() {
        open(HistoryKind.Requests, wide = true, persist = true)
        compose.runOnUiThread { feature.acceptWindow(1, ObservationWindow(runtime, 1u, 1u, 0u,
            listOf(ObservationEvent.Request(1u, RequestHistoryEntry(requestId, Instant.EPOCH, "tcp", host = "real.test"))))) }
        compose.waitUntil { compose.onAllNodesWithTag("history-resize-requests-time").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-resize-requests-time").performTouchInput { swipe(center, center + Offset(24f, 0f), 400) }
        compose.waitUntil { tables.value.widths["requests"]?.get("time")?.let { it > 80.0 } == true }
        assertEquals(tables.value, runBlocking { LiteSettingsStore(preferencesDirectory).read().historyTables })
        compose.onNodeWithTag("history-resize-requests-time").performTouchInput { doubleClick() }
        compose.waitUntil { tables.value.widths["requests"] == null }
        assertTrue(runBlocking { LiteSettingsStore(preferencesDirectory).read().historyTables.widths }.isEmpty())
    }

    @Test fun thirdHeaderSortTapRestoresUnsortedArrivalOrder() {
        open(HistoryKind.Requests, wide = true)
        compose.runOnUiThread { feature.acceptWindow(1, ObservationWindow(runtime, 1u, 2u, 0u, listOf(
            ObservationEvent.Request(1u, RequestHistoryEntry(requestId, Instant.ofEpochSecond(10), "tcp", host = "first.test")),
            ObservationEvent.Request(2u, RequestHistoryEntry(UUID.randomUUID().toString(), Instant.EPOCH, "tcp", host = "second.test"))))) }
        compose.waitUntil { compose.onAllNodesWithTag("history-sort-requests-time").fetchSemanticsNodes().isNotEmpty() }
        val sort = compose.onNodeWithTag("history-sort-requests-time")
        sort.performClick(); sort.assertTextContains("↓", substring = true)
        sort.performClick(); sort.assertTextContains("↑", substring = true)
        sort.performClick(); sort.assertTextEquals(context.getString(R.string.connections_started))
    }

    @Test fun pausedProjectionCannotRetainOldRuntimeRowsWhenTheEmptyStateIsConflated() {
        open(HistoryKind.Requests)
        compose.runOnUiThread { feature.acceptWindow(1, ObservationWindow(runtime, 1u, 1u, 0u,
            listOf(ObservationEvent.Request(1u, RequestHistoryEntry(requestId, Instant.EPOCH, "tcp", host = "old.test"))))) }
        compose.waitUntil { compose.onAllNodesWithTag("history-request-$requestId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-follow").performClick()
        val fresh = UUID.randomUUID().toString()
        compose.runOnUiThread {
            feature.bindSession(2)
            feature.acceptWindow(2, ObservationWindow(UUID.randomUUID().toString(), 1u, 1u, 0u,
                listOf(ObservationEvent.Request(1u, RequestHistoryEntry(fresh, Instant.EPOCH, "tcp", host = "new.test")))))
        }
        compose.waitUntil { compose.onAllNodesWithTag("history-request-$fresh").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-request-$requestId").assertDoesNotExist()
    }
}
