package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import dev.aerove.lite.LiteFeatureHost
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import dev.aerove.lite.integration.ProxyPresentationWorkspace
import dev.aerove.lite.integration.ProfileImportWorkspace
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LiteAppRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class RenderingFeatureRoots(context: Context, private val state: MutableState<LiteState>) : Closeable {
        private val directory = File(context.cacheDir, "app-render-owner-${UUID.randomUUID()}")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val validationCalls = java.util.concurrent.atomic.AtomicInteger()
        private val networkCalls = java.util.concurrent.atomic.AtomicInteger()
        private val repository = ProfileRepository(directory, ProfileValidator {
            validationCalls.incrementAndGet(); error("Rendering must not validate a valid import")
        }, downloader = dev.aerove.lite.data.SubscriptionDownloader(okhttp3.OkHttpClient.Builder().addInterceptor {
            networkCalls.incrementAndGet(); throw java.io.IOException("Rendering imports must remain local")
        }.build()))
        private val proxies = ProxyPresentationWorkspace(scope, { state.value.settings.proxyPresentation }, repository::updateProxyGroupUi,
            { saved -> state.value = state.value.copy(profiles = state.value.profiles.map { if (it.id == saved.id) saved else it }) }, { true })
        private val imports = ProfileImportWorkspace(scope, repository, { true }, {
            state.value = state.value.copy(profiles = repository.list())
        }, { prompt -> state.value = state.value.copy(profileImportKeyPrompt = prompt) },
            { state.value = state.value.copy(profileImportVisible = false) }, { error("Unexpected rendering import failure") })

        @Composable fun Content(content: @Composable () -> Unit) {
            IpQualityFixture(state.value) {
                val base = LocalLiteFeatures.current()
                val host = remember(base) { object : LiteFeatureHost by base {
                    override val proxyPresentation = proxies
                    override val profileImports = imports
                } }
                val factory: () -> LiteFeatureHost = remember(host) { { host } }
                CompositionLocalProvider(LocalLiteFeatures provides factory, content = content)
            }
        }

        fun rejectedInput(input: String): Boolean = imports.state.value.let {
            it.input == input && it.errorCode == "invalid_input" && it.issues.isNotEmpty() && !it.preparing
        }

        fun assertNothingImported() {
            assertTrue(state.value.profiles.isEmpty())
            assertTrue(imports.batchState.value.items.isEmpty())
            assertTrue(runBlocking(Dispatchers.IO) { ProfileRepository(directory, ProfileValidator {}).list().isEmpty() })
            assertEquals(0, validationCalls.get())
            assertEquals(0, networkCalls.get())
        }

        override fun close() {
            try { runBlocking { try { imports.quiesce() } finally { proxies.closeAndJoin() } } }
            finally {
                try { imports.close() }
                finally { try { proxies.close() } finally { scope.cancel(); directory.deleteRecursively() } }
            }
        }
    }

    @Test
    fun productionPagesChromeAndModalRenderWithoutInvokingCore() {
        fullAuroraMesh(FullAuroraSeed, FullAuroraColors, case.dark)
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(LiteState(settings = case.settings))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        val roots = RenderingFeatureRoots(InstrumentationRegistry.getInstrumentation().targetContext, state)
        try {
            val probe = RenderProbe()
            compose.replaceAppContent {
                roots.Content {
                    val view = LocalView.current
                    val density = LocalDensity.current.density
                    val fontScale = LocalDensity.current.fontScale
                    SideEffect { probe.view = view; probe.density = density; probe.fontScale = fontScale }
                    Box(Modifier.fillMaxSize().onGloballyPositioned { probe.bounds["app"] = it.boundsInWindow() }) { LiteApp(state.value, actions) }
                }
            }
            val capture = HardwareCapture(compose, probe, "app_$case")
            val report = JSONObject().put("case", case.toString()).put("businessState", "In-memory instrumentation fixture; no live VPN or production fake nodes")
            fun save(label: String) {
                compose.mainClock.advanceTimeBy(400)
                capture.capture(label).close()
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fun text(id: Int) = context.getString(id)
            fun recordImportErrorFailure(invalidInput: String, failure: AssertionError) {
                val detail = try {
                    fun rect(values: androidx.compose.ui.geometry.Rect) = org.json.JSONArray(listOf(values.left, values.top, values.right, values.bottom))
                    val nodes = JSONObject()
                    listOf("profile-import-error", "profile-import-input", "profile-import-submit").forEach { tag ->
                        val value = runCatching {
                            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
                            JSONObject().put("boundsInRoot", rect(node.boundsInRoot))
                        }.getOrElse { diagnosticFailure ->
                            rethrowMatrixCancellation(diagnosticFailure, failure)
                            JSONObject().put("unavailable", "${diagnosticFailure.javaClass.name}:${diagnosticFailure.message}")
                        }
                        nodes.put(tag, value)
                    }
                    val rootBounds = runCatching {
                        rect(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                    }.getOrElse { diagnosticFailure ->
                        rethrowMatrixCancellation(diagnosticFailure, failure)
                        JSONObject().put("unavailable", "${diagnosticFailure.javaClass.name}:${diagnosticFailure.message}")
                    }
                    val viewport = JSONObject()
                    compose.activityRule.scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        val location = IntArray(2).also(decor::getLocationOnScreen)
                        val insets = decor.rootWindowInsets
                        viewport.put("decorBoundsOnScreen", org.json.JSONArray(listOf(location[0], location[1], decor.width, decor.height)))
                            .put("imeVisible", insets?.isVisible(android.view.WindowInsets.Type.ime()) ?: false)
                            .put("imeBottomInsetPx", insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0)
                    }
                    val directory = File(requireNotNull(context.getExternalFilesDir(null)), "captures/lite-app-import-error-failures")
                    check(directory.mkdirs() || directory.isDirectory) { "Cannot create failure diagnostics directory: $directory" }
                    val caseLabel = case.toString().replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100)
                    val inputLabel = if (invalidInput.startsWith("proxies:")) "proxies" else "rules"
                    val label = "profile-import-error-$caseLabel-$inputLabel-${System.currentTimeMillis()}"
                    val screenshot = File(directory, "$label.png")
                    val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                        "UiAutomation returned no display screenshot"
                    }
                    try {
                        screenshot.outputStream().use { output ->
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
                        }
                    } finally { bitmap.recycle() }
                    val metadata = File(directory, "$label.json")
                    metadata.writeText(JSONObject()
                        .put("case", case.toString())
                        .put("invalidInput", invalidInput)
                        .put("originalFailure", failure.toString())
                        .put("appBoundsInWindow", probe.bounds["app"]?.let(::rect) ?: JSONObject.NULL)
                        .put("composeRootBoundsInRoot", rootBounds)
                        .put("viewport", viewport)
                        .put("nodes", nodes)
                        .put("screenshot", screenshot.absolutePath)
                        .toString(2))
                    "savedScreenshot=${screenshot.absolutePath}; savedMetadata=${metadata.absolutePath}"
                } catch (diagnosticFailure: Throwable) {
                    rethrowMatrixCancellation(diagnosticFailure, failure)
                    "diagnosticFailed=${diagnosticFailure.javaClass.name}:${diagnosticFailure.message}"
                }
                failure.addSuppressed(AssertionError("Failure-only import error evidence: $detail"))
            }
            val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            fun navigate(id: Int) {
                compose.onNode(tab and hasText(text(id))).performClick()
                compose.mainClock.advanceTimeByFrame()
            }
            fun openTool(id: Int) {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(id)) and hasClickAction())
                compose.onNode(hasText(text(id)) and hasClickAction()).performClick()
                compose.mainClock.advanceTimeByFrame()
            }
            try {
                val disabledStart = hasContentDescription(text(R.string.lite_start)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasClickAction()
                compose.onNode(disabledStart, useUnmergedTree = true).assertIsDisplayed().assertIsNotEnabled()
                compose.onAllNodes(tab).assertCountEquals(3)
                compose.onNode(tab and hasText(text(R.string.lite_tab_proxies))).assertDoesNotExist()
                save("home")
                compose.runOnUiThread { state.value = state.value.copy(groups = listOf(ProxyGroup("Fixture group", "Selector", "DIRECT", listOf(ProxyNode("DIRECT", "Direct"))))) }
                // Auto-advance is disabled for deterministic optical captures; let this state change recompose before querying tabs.
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(tab).assertCountEquals(4)
                navigate(R.string.lite_tab_proxies)
                compose.onNodeWithText("Fixture group").assertExists()
                save("proxies-test-fixture")
                navigate(R.string.lite_tab_profiles)
                compose.onNodeWithText(text(R.string.lite_no_profiles)).assertExists()
                save("profiles-empty")
                compose.onNodeWithContentDescription(text(R.string.lite_add_subscription)).performClick()
                compose.mainClock.advanceTimeByFrame()
                // The clock is paused for captures; finish the real 260 ms sheet entry before scroll/IME interaction.
                compose.mainClock.advanceTimeBy(400)
                compose.onNode(tab).assertDoesNotExist()
                fun importInput() = compose.onNode(hasAnyAncestor(hasTestTag("profile-import-input")) and hasSetTextAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus), useUnmergedTree = true)
                val importScrollMatcher = hasScrollAction() and hasAnyDescendant(hasTestTag("profile-import-input"))
                fun importScroller(): SemanticsNodeInteraction {
                    val matches = compose.onAllNodes(importScrollMatcher, useUnmergedTree = true)
                    matches.assertCountEquals(1)
                    return matches[0]
                }
                fun scrollImportTargetIntoView(target: SemanticsNodeInteraction) {
                    if (!target.isDisplayed()) {
                        val scroller = importScroller().assertIsDisplayed()
                        val targetBounds = target.getUnclippedBoundsInRoot()
                        val viewport = scroller.getBoundsInRoot()
                        scroller.performTouchInput {
                            if (targetBounds.top < viewport.top) swipeDown(durationMillis = 400)
                            else swipeUp(durationMillis = 400)
                        }
                        compose.mainClock.advanceTimeBy(400)
                    }
                    target.assertIsDisplayed()
                }
                compose.onNodeWithTag("profile-import-submit").assertIsNotEnabled()
                for (invalidInput in listOf("proxies: [", "rules: [")) {
                    val input = importInput()
                    scrollImportTargetIntoView(input)
                    input.assertIsDisplayed().performTextReplacement(invalidInput)
                    compose.mainClock.advanceTimeByFrame()
                    val submit = compose.onNodeWithTag("profile-import-submit")
                    scrollImportTargetIntoView(submit)
                    submit.assertIsDisplayed().assertIsEnabled().performClick()
                    compose.waitUntil(3000) { roots.rejectedInput(invalidInput) }
                    compose.mainClock.advanceTimeByFrame()
                    val importErrorNode = compose.onNodeWithTag("profile-import-error")
                    try {
                        scrollImportTargetIntoView(importErrorNode)
                        importErrorNode.assertIsDisplayed().assertTextEquals(text(R.string.age_import_failed))
                    } catch (failure: AssertionError) {
                        recordImportErrorFailure(invalidInput, failure)
                        throw failure
                    }
                    importInput().assertTextEquals(invalidInput)
                    compose.onNodeWithTag("profile-import-submit").assertIsEnabled()
                    roots.assertNothingImported()
                }
                val closeButton = compose.onNodeWithContentDescription(text(R.string.lite_close))
                scrollImportTargetIntoView(closeButton)
                save("subscription-validation-modal")
                compose.onNodeWithContentDescription(text(R.string.lite_close)).performClick()
                compose.mainClock.advanceTimeByFrame()
                // The production sheet keeps its modal owner until the finite 260 ms exit completes.
                compose.mainClock.advanceTimeBy(400)
                navigate(R.string.lite_tab_tools)
                save("tools")
                openTool(R.string.lite_tab_connections)
                compose.onNodeWithText(text(R.string.lite_no_connections)).assertExists()
                save("connections-empty")
                compose.onNodeWithContentDescription(text(R.string.lite_back)).performClick()
                compose.mainClock.advanceTimeByFrame()
                openTool(R.string.lite_theme)
                save("theme")
                val nextTheme = text(if (case.aurora) R.string.lite_normal_theme else R.string.lite_aurora)
                val themeOption = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
                    hasText(nextTheme) and hasClickAction()
                val option = compose.onNode(themeOption)
                if (!option.isDisplayed()) {
                    val scrollers = compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true)
                    scrollers.assertCountEquals(1)
                    val scroller = scrollers[0].assertIsDisplayed()
                    val options = compose.onAllNodes(themeOption).fetchSemanticsNodes()
                    val above = options.singleOrNull()?.let {
                        option.getUnclippedBoundsInRoot().top < scroller.getBoundsInRoot().top
                    } ?: false
                    scroller.performTouchInput {
                        if (above) swipeDown(durationMillis = 400) else swipeUp(durationMillis = 400)
                    }
                    compose.mainClock.advanceTimeBy(400)
                }
                option.assertIsDisplayed().assertIsEnabled().performClick()
                compose.runOnUiThread { assertEquals(!case.aurora, state.value.settings.aurora) }
                save("theme-aurora-toggled")
                compose.runOnUiThread {
                    assertEquals(1, actions.settings.size)
                    assertTrue("These screenshot fixtures must not invoke a real import, VPN or core action", actions.events.all { it == "changeSettings" })
                }
                report.put("settingsActions", actions.settings.size).put("serviceActions", 0)
            } finally { try { capture.metrics(report) } finally { capture.close() } }
        } finally { roots.close() }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases(switchStates = false)
    }
}
