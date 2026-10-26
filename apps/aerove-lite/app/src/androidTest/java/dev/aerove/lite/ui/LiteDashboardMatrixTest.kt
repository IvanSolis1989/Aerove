package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.R
import dev.aerove.lite.Traffic
import dev.aerove.lite.VpnPhase
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiteDashboardMatrixTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionDashboardTwelveAppearances() {
        for (dark in listOf(false, true)) fullAuroraMesh(FullAuroraSeed, FullAuroraColors, dark)
        val sourceRevision = InstrumentationRegistry.getArguments().getString("sourceRevision")
        assertTrue("Pass the built commit SHA through sourceRevision", sourceRevision?.matches(Regex("[0-9a-fA-F]{40}")) == true)
        compose.mainClock.autoAdvance = false
        val cases = dashboardCases()
        assertEquals(12, cases.map { Triple(it.tier, it.aurora, it.dark) }.distinct().size)
        val reference = dashboardFixture()
        val selected = mutableStateOf<DashboardCaptureCase?>(null)
        val actions = UiTestActions { error("A fixed screenshot must not mutate settings") }
        val probe = RenderProbe()
        compose.replaceAppContent {
            val view = LocalView.current
            val density = LocalDensity.current
            SideEffect { probe.view = view; probe.density = density.density; probe.fontScale = density.fontScale }
            Box(Modifier.fillMaxSize().onGloballyPositioned { probe.bounds["app"] = it.boundsInWindow() }) {
                val case = selected.value
                if (case != null) key(case.number) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        IpQualityFixture(reference.copy(settings = case.settings)) {
                            LiteApp(reference.copy(settings = case.settings), actions)
                        }
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cells = cases.map { it.json().put("captured", false) }
        val manifest = JSONObject()
            .put("schema", 1).put("startedAtMillis", System.currentTimeMillis())
            .put("sourceRevision", sourceRevision)
            .put("columns", JSONArray(listOf("简约纯色", "柔光磨砂", "流光清透")))
            .put("rows", JSONArray(listOf("浅普通", "浅极光", "深普通", "深极光")))
            .put("cases", JSONArray(cells)).put("complete", false).put("completedCount", 0)
            .put("fixture", JSONObject().put("origin", "androidTest only; real production LiteApp, Pane and Backdrop")
                .put("phase", "Running is display data, not evidence of a running VPN")
                .put("outbound", "198.51.100.8").put("countryCode", "SG").put("organization", "Example ISP")
                .put("kind", "Residential").put("localAddress", "192.0.2.8").put("uptimeMillis", 0)
                .put("quality", "fixed androidTest lookup; no provider request or real IP quality evidence")
                .put("traffic", "zero upload/download rates and totals; thirty zero-valued androidTest samples render flat baselines, not network history"))
            .put("dataSha256", sha256(reference.toString())).put("locale", context.resources.configuration.locales.toLanguageTags())
            .put("composeAdvancePerAppearanceMillis", 400).put("layoutDirection", "LTR")
            .put("entryClock", "Each appearance first composes from an empty/fresh key, including the first cell")
            .put("vpnActions", 0).put("networkActions", 0).put("performanceEvidence", false)
        val backgrounds = mutableMapOf<Int, List<Int>>()
        val ledger = MatrixEvidenceLedger(cases.size)
        val failures = JSONArray()
        manifest.put("diagnosticCapturedCount", 0).put("failures", failures)
        HardwareCapture(compose, probe, "dashboard-matrix").use { capture ->
            capture.beginMatrixCollection()
            capture.manifest(manifest)
            var matrixExitFailure: Throwable? = null
            try {
                for ((index, case) in cases.withIndex()) {
                    var accepted = false
                    var diagnosticCaptured = false
                    var caseFailure: Throwable? = null
                    var caseExitFailure: Throwable? = null
                    try {
                        assertEquals("Only appearance settings may differ between cells", reference, reference.copy(settings = case.settings).copy(settings = reference.settings))
                        compose.runOnUiThread { selected.value = case }
                        compose.mainClock.advanceTimeBy(400)
                        compose.waitForIdle()
                        compose.runOnUiThread {
                            val window = compose.activity.window
                            val bars = WindowCompat.getInsetsController(window, window.decorView)
                            assertEquals("Status icon color must follow app theme independently of the OS", !case.dark, bars.isAppearanceLightStatusBars)
                            assertEquals("Navigation icon color must follow app theme independently of the OS", !case.dark, bars.isAppearanceLightNavigationBars)
                            @Suppress("DEPRECATION")
                            assertEquals(android.graphics.Color.TRANSPARENT, window.statusBarColor)
                            @Suppress("DEPRECATION")
                            assertEquals(android.graphics.Color.TRANSPARENT, window.navigationBarColor)
                            assertTrue(window.isNavigationBarContrastEnforced)
                            assertTrue(!window.isStatusBarContrastEnforced)
                        }
                        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
                        compose.onNode(tab and hasText(context.getString(R.string.lite_tab_home))).assertIsSelected()
                        compose.onNodeWithText("198.51.100.8").assertIsDisplayed()
                        compose.onNodeWithText("Example ISP").assertIsDisplayed()
                        compose.onNodeWithText(context.getString(R.string.lite_upload), useUnmergedTree = true).assertIsDisplayed()
                        compose.onNodeWithText(context.getString(R.string.lite_download), useUnmergedTree = true).assertIsDisplayed()
                        compose.onNodeWithContentDescription(context.getString(R.string.lite_stop)).assertIsEnabled()
                        compose.onNodeWithContentDescription(context.getString(R.string.lite_vpn)).assertIsOn()
                        capture.captureMatrix(case.stem, "${case.stem}.png", case.json()).use { result ->
                            result.originalFailure?.let {
                                caseFailure = it
                                cells[index].put("originalFailure", JSONObject().put("type", it.javaClass.name)
                                    .put("message", it.message).put("phase", result.originalFailurePhase))
                            }
                            result.diagnosticFailure?.let {
                                requireNotNull(caseFailure).addSuppressed(it)
                                cells[index].put("diagnosticFailure", "${it.javaClass.name}: ${it.message}")
                            }
                            cells[index].put("captureFinalPhase", result.finalPhase)
                            if (result.accepted) {
                                val frame = requireNotNull(result.frame)
                                val box = requireNotNull(probe.bounds["app"])
                                val samples = listOf(0.3f, 0.45f, 0.6f, 0.75f).map { fraction -> frame.pixel(box.left + 2 * probe.density, box.top + box.height * fraction) }
                                val row = backgrounds[case.row]
                                if (row == null) backgrounds[case.row] = samples
                                else assertTrue("A material change must not change the wallpaper phase or data", samples.zip(row).maxOf { (a, b) -> channelError(a, b) } <= 2)
                                cells[index].put("windowSha256", frame.evidence.getString("windowSha256"))
                                    .put("displayImage", frame.evidence.getString("displayImage"))
                                    .put("displaySha256", frame.evidence.getString("displaySha256"))
                                    .put("width", frame.bitmap.width).put("height", frame.bitmap.height)
                                    .put("backgroundSamples", JSONArray(samples))
                                accepted = true
                            } else result.frame?.let { frame ->
                                diagnosticCaptured = true
                                cells[index].put("diagnosticWindowImage", frame.evidence.getString("windowImage"))
                                    .put("diagnosticWindowSha256", frame.evidence.getString("windowSha256"))
                                    .put("diagnosticMetadata", "diagnostic/${case.stem}.json")
                                    .put("diagnosticSubmission", frame.evidence.getJSONObject("matrixSubmission"))
                            }
                        }
                        compose.runOnUiThread {
                            assertTrue("Screenshots cannot invoke the real core or services", actions.events.isEmpty())
                            assertTrue("Dashboard fixtures cannot declare Connections telemetry demand", actions.connectionTelemetry.isEmpty())
                        }
                    } catch (failure: Throwable) {
                        accepted = false
                        if (caseFailure == null) caseFailure = failure else caseFailure!!.addSuppressed(failure)
                        try {
                            rethrowMatrixCancellation(failure)
                        } catch (terminal: Throwable) {
                            caseExitFailure = terminal
                            throw terminal
                        }
                    } finally {
                        if (!accepted && caseFailure == null) caseFailure = AssertionError("Matrix case did not complete original acceptance")
                        cells[index].put("captured", accepted).put("diagnosticCaptured", diagnosticCaptured)
                        ledger.record(case.number, accepted, diagnosticCaptured, caseFailure)
                        caseFailure?.let { failures.put(JSONObject().put("number", case.number)
                            .put("type", it.javaClass.name).put("message", it.message)
                            .put("suppressed", JSONArray(it.suppressed.map { error -> "${error.javaClass.name}: ${error.message}" }))) }
                        manifest.put("completedCount", ledger.acceptedCount)
                            .put("diagnosticCapturedCount", ledger.diagnosticCapturedCount)
                            .put("attemptedCount", index + 1)
                        preserveMatrixEvidenceFailure(caseExitFailure ?: caseFailure) { capture.manifest(manifest) }
                    }
                }
                ledger.assertAllAccepted()
                for ((first, second) in listOf(1 to 2, 3 to 4, 1 to 3)) {
                    val difference = requireNotNull(backgrounds[first]).zip(requireNotNull(backgrounds[second])).maxOf { (a, b) -> channelError(a, b) }
                    assertTrue("The requested dark/Aurora setting must reach the actual window", difference > 8)
                }
                manifest.put("complete", true)
            } catch (failure: Throwable) {
                matrixExitFailure = failure
                preserveMatrixEvidenceFailure(failure) {
                    manifest.put("failure", "${failure.javaClass.name}: ${failure.message}")
                }
                throw failure
            } finally {
                preserveMatrixEvidenceFailure(matrixExitFailure) {
                    manifest.put("finishedAtMillis", System.currentTimeMillis())
                    capture.manifest(manifest)
                }
            }
        }
    }
}

private data class DashboardCaptureCase(val number: Int, val row: Int, val column: Int, val tier: MaterialTier, val aurora: Boolean, val dark: Boolean) {
    private val tierKey get() = when (tier) { MaterialTier.Solid -> "solid"; MaterialTier.Frosted -> "standard"; MaterialTier.Clear -> "ultra" }
    val stem get() = String.format(Locale.ROOT, "%02d-%s-%s-%s", number, tierKey, if (aurora) "aurora" else "theme", if (dark) "dark" else "light")
    val settings get() = LiteSettings(material = tier, aurora = aurora, colorMode = if (dark) ColorMode.Dark else ColorMode.Light, themeColor = 0xff007aff)
    fun json(): JSONObject = JSONObject().put("number", number).put("row", row).put("column", column).put("tier", tier.name)
        .put("aurora", aurora).put("dark", dark).put("windowImage", "$stem.png").put("metadata", "$stem.json")
}

private fun dashboardCases(): List<DashboardCaptureCase> = buildList {
    for (dark in listOf(false, true)) for (aurora in listOf(false, true)) for ((column, tier) in MaterialTier.entries.withIndex()) {
        add(DashboardCaptureCase(size + 1, (if (dark) 2 else 0) + (if (aurora) 1 else 0) + 1, column + 1, tier, aurora, dark))
    }
}

private fun dashboardFixture() = LiteState(
    phase = VpnPhase.Running,
    profiles = listOf(ProfileSummary("screenshot-only", "AEROVE Lite preview fixture", null, 0)),
    selectedProfile = "screenshot-only",
    groups = listOf(ProxyGroup("Fixture selector", "Selector", "DIRECT", listOf(ProxyNode("DIRECT", "Direct")))),
    traffic = Traffic(),
    trafficHistory = List(30) { Traffic() },
    trafficHistoryRevision = 30,
    outboundIdentity = OutboundIdentity("198.51.100.8", "SG", "Example ISP", "Residential"),
    localAddress = "192.0.2.8",
    uptimeMillis = 0,
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
