package dev.aerove.lite

import android.content.Intent
import android.net.VpnService
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.vpn.LiteVpnService
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** CI-only production entry coverage; MainActivity owns its content, controller and feature provider. */
@RunWith(AndroidJUnit4::class)
class ProductionFeatureFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as LiteApplication

    @Test
    fun dashboardEditPersistsAndRealMemoryDetailReadsTheRunningCore() = runBlocking {
        val fixture = InstrumentationRegistry.getArguments().getString("fixtureUrl")
            ?: error("The isolated API33 CI fixtureUrl is required")
        require(fixture.matches(Regex("http://10\\.0\\.2\\.2:[0-9]+")))
        assertNull("CI must grant ACTIVATE_VPN before launching production MainActivity", VpnService.prepare(context))
        assertEquals("Do not take over an existing VPN session", VpnPhase.Stopped, graph.vpn.value.phase)
        val before = graph.profiles.snapshot()
        val settings = graph.settings.read()
        var profile: ProfileSummary? = null
        var activity: ActivityScenario<MainActivity>? = null
        var startRequested = false
        var primaryFailure: Throwable? = null
        try {
            val owned = graph.profiles.importText("Production flow ${UUID.randomUUID()}",
                "mode: direct\nrules:\n  - MATCH,DIRECT\n")
            profile = owned
            graph.profiles.select(owned.id)
            graph.settings.save(LiteSettings(mode = ProxyMode.Direct, aurora = false, material = MaterialTier.Solid, autoCheckUpdate = false,
                dashboardWidgets = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo,
                    DashboardWidget.OutboundMode)), vpnAuthorized = true)
            val launched = ActivityScenario.launch(MainActivity::class.java)
            activity = launched
            lateinit var controller: LiteController
            launched.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
            compose.waitUntil(15_000) { controller.state.value.initialized && controller.state.value.selectedProfile == owned.id }

            // This fixture starts with fresh settings, so complete the production consent gate first.
            compose.onNodeWithText(context.getString(R.string.maintenance_agree)).assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.maintenance_confirm)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(context.getString(R.string.maintenance_disclaimer_restate_hint))
                .performTextInput(context.getString(R.string.maintenance_disclaimer_no_service_statement))
            compose.onNodeWithText(context.getString(R.string.maintenance_confirm)).performClick()
            withTimeout(15_000) {
                controller.state.first { it.settings.acceptedDisclaimerVersion >= dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION && it.busy.isEmpty() }
            }
            assertEquals("Consent must persist through the production settings owner",
                dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION, graph.settings.read().acceptedDisclaimerVersion)
            compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).assertIsDisplayed().performClick()
            compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-row-outboundMode"))
            compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_remove,
                context.getString(R.string.lite_proxy_mode))).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.lite_confirm)).performClick()
            val expectedLayout = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo)
            withTimeout(15_000) { controller.state.first { it.settings.dashboardWidgets == expectedLayout && it.busy.isEmpty() } }
            assertEquals("Confirm must persist through the production settings owner",
                expectedLayout, graph.settings.read().dashboardWidgets)

            startRequested = true
            ContextCompat.startForegroundService(context,
                Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_START))
            val running = withTimeout(60_000) {
                graph.vpn.first { it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed }
            }
            assertEquals(running.error ?: "Production VPN must be ready", VpnPhase.Running, running.phase)
            val endpoint = checkNotNull(running.endpoint)
            CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { api ->
                val route = api.routeSnapshot()
                assertTrue("The native route owner must supply an actual epoch", route.coreEpoch > 0uL)
                val actualPicks = api.groups().associate { it.name to it.selected }.filterValues { it.isNotEmpty() }
                assertEquals("Route identity must contain actual core selections", actualPicks, route.picks)
                assertEquals("A stable direct session must retain its route identity", route, api.routeSnapshot())
                assertTrue("The real process RSS endpoint must supply positive bytes",
                    (api.memorySnapshot().processRssBytes ?: 0uL) > 0uL)
            }
            assertControlledFixture(fixture)
            compose.onNodeWithTag("dashboard-layout").performScrollToNode(hasTestTag("diagnostics-memory"))
            compose.onNodeWithTag("diagnostics-memory").performClick()
            compose.onNodeWithText(context.getString(R.string.diag_memory_in_use)).assertIsDisplayed()
            compose.waitUntil(20_000) {
                controller.display.diagnostics.value.let { it.memoryScope == "process-rss" &&
                    (it.memoryInUseBytes ?: 0) > 0 && it.memoryError == null }
            }
            compose.onAllNodesWithTag("diagnostics-memory-scope").onLast().assertTextEquals(
                context.getString(R.string.diag_memory_process_rss))
            assertTrue("Detail must observe the actual production controller sample",
                checkNotNull(controller.display.diagnostics.value.memoryInUseBytes) > 0)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                }
            }
            cleanup { activity?.close() }
            if (startRequested) cleanup {
                context.startService(Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
                withTimeout(15_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
            }
            cleanup { graph.settings.save(settings, vpnAuthorized = true) }
            cleanup { before.selectedId?.let { graph.profiles.select(it) } }
            cleanup { profile?.let { graph.profiles.delete(it.id) } }
            cleanup {
                assertEquals("Original settings must be restored", settings, graph.settings.read())
                val restored = graph.profiles.snapshot()
                assertEquals("Only the test profile may be removed", before, restored)
            }
            cleanupFailure?.let { failure ->
                val original = primaryFailure
                if (original == null) throw failure else original.addSuppressed(failure)
            }
        }
    }

    private fun assertControlledFixture(fixture: String) {
        val token = UUID.randomUUID().toString()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(15, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url("$fixture/$token").build()).execute().use { response ->
                assertTrue("Controlled fixture must really respond while the direct session is running", response.isSuccessful)
                assertTrue(checkNotNull(response.body).string().contains("AEROVE_LITE_FIXTURE:$token"))
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
