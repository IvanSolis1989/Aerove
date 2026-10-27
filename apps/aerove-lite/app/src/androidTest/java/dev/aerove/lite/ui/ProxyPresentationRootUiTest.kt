package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.toPixelMap
import java.io.ByteArrayOutputStream
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.data.*
import dev.aerove.lite.integration.ProxyPresentationWorkspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProxyPresentationRootUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualProxyTabPersistsUnfoldAndReplacesToolbarForSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "proxy-root-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ProfileRepository(directory, ProfileValidator {})
        val png = ByteArrayOutputStream().also { output ->
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.MAGENTA); it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); it.recycle()
            }
        }.toByteArray()
        val icon = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
        val iconLoader = dev.aerove.lite.images.NetworkGroupIconLoader(okhttp3.OkHttpClient(),
            LiteSettingsStore(directory).requestPolicy("AEROVE-Lite-Test"), scope, File(directory, "icon-cache"))
        val profile = runBlocking(Dispatchers.IO) { repository.importText("Main", "proxy-groups:\n  - name: group\n    type: select\n    icon: $icon\n    proxies: [DIRECT]\nrules:\n  - MATCH,DIRECT\n") }
        var state by mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false),
            profiles = listOf(profile), selectedProfile = profile.id, groups = listOf(ProxyGroup("group", "Selector", "DIRECT", listOf(ProxyNode("DIRECT", "Direct"))))))
        val client = CoreApiClient(ControlEndpoint(9091, "0123456789abcdef0123456789abcdef"))
        val workspace = ProxyPresentationWorkspace(scope, { state.settings.proxyPresentation }, repository::updateProxyGroupUi,
            { saved -> state = state.copy(profiles = listOf(saved)) }, { true })
        workspace.profile(profile)
        val sourceReads = java.util.concurrent.atomic.AtomicInteger()
        val sourceReturns = java.util.concurrent.atomic.AtomicInteger()
        workspace.bind(client, profile.id, client, {
            sourceReads.incrementAndGet()
            try { repository.readConfig(profile.id) } finally { sourceReturns.incrementAndGet() }
        }, { true })
        try {
            compose.replaceAppContent {
                IpQualityFixture(state) {
                    val base = LocalLiteFeatures.current()
                    val host = remember(base) { object : LiteFeatureHost by base { override val proxyPresentation = workspace
                        override val groupIcons: dev.aerove.lite.images.GroupIconLoader = iconLoader
                    } }
                    CompositionLocalProvider(LocalLiteFeatures provides { host }, LocalLiteMotionOverride provides true) {
                        LiteApp(state, UiTestActions({ state = state.copy(settings = it) }, state.settings))
                    }
                }
            }
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasText(context.getString(R.string.lite_tab_proxies))).performClick()
            try {
                compose.waitUntil(3000) { workspace.state.value.ready }
            } catch (failure: Throwable) {
                rethrowMatrixCancellation(failure)
                try {
                    val actual = workspace.state.value
                    android.util.Log.i("LiteProxyReadyFailure", org.json.JSONObject(mapOf(
                        "ready" to actual.ready, "failed" to actual.failed,
                        "profileMatches" to (actual.profileId == profile.id),
                        "sourceReads" to sourceReads.get(), "sourceReturns" to sourceReturns.get(),
                        "groupUrlCount" to actual.groupUrls.size, "groupIconCount" to actual.groupIcons.size,
                        "scopeActive" to (scope.coroutineContext[Job]?.isActive == true),
                        "lifecycle" to compose.activity.lifecycle.currentState.name,
                        "windowFocused" to compose.activity.hasWindowFocus(),
                        "finishing" to compose.activity.isFinishing,
                        "autoAdvance" to compose.mainClock.autoAdvance,
                        "failureType" to failure.javaClass.name,
                    )).toString())
                } catch (diagnostic: Throwable) {
                    rethrowMatrixCancellation(diagnostic, failure)
                    failure.addSuppressed(diagnostic)
                }
                throw failure
            }
            compose.waitUntil(3000) {
                runCatching {
                    val image = compose.onNodeWithTag("proxy-group-icon-group").captureToImage()
                    val pixel = image.toPixelMap()[image.width / 2, image.height / 2]
                    pixel.red > 0.95f && pixel.blue > 0.95f && pixel.green < 0.05f
                }.getOrDefault(false)
            }
            compose.onAllNodesWithContentDescription(context.getString(R.string.lite_expand_group, "group"))[0].performClick()
            compose.waitUntil(3000) { runBlocking(Dispatchers.IO) { repository.profileSummary(profile.id)?.unfoldedProxyGroups == setOf("group") } }
            assertEquals(setOf("group"), runBlocking(Dispatchers.IO) { ProfileRepository(directory, ProfileValidator {}).profileSummary(profile.id)!!.unfoldedProxyGroups })
            compose.onNodeWithContentDescription(context.getString(R.string.lite_search)).performClick()
            compose.onNodeWithTag("proxy-search-field").assertIsDisplayed().performTextInput("no match")
            compose.onNodeWithTag("proxy-presentation-options").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.proxy_presentation_clear_search)).performClick()
            val clearedNode = compose.onNodeWithTag("proxy-search-field").fetchSemanticsNode()
            val actualEmpty: Any? = clearedNode.config.getOrNull(SemanticsProperties.EditableText)
            val expectedEmpty = AnnotatedString("")
            android.util.Log.i("AEROVE-ProxyPredicate", org.json.JSONObject(mapOf(
                "kind" to "proxy-clear-editable-predicate", "uniqueTagMatch" to true, "nodeId" to clearedNode.id,
                "actual" to annotatedPredicateSnapshot(actualEmpty), "expected" to annotatedPredicateSnapshot(expectedEmpty),
                "wholeValueEqual" to (actualEmpty == expectedEmpty), "textEqual" to ((actualEmpty as? AnnotatedString)?.text == expectedEmpty.text),
                "inputText" to clearedNode.config.getOrNull(SemanticsProperties.InputText)?.text,
                "textList" to clearedNode.config.getOrNull(SemanticsProperties.Text)?.map { it.text },
                "selection" to clearedNode.config.getOrNull(SemanticsProperties.TextSelectionRange)?.let { listOf(it.start, it.end) },
            )).toString())
            compose.onNodeWithTag("proxy-search-field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("proxy-search-field").assertDoesNotExist()
            compose.onNodeWithTag("proxy-presentation-options").assertIsDisplayed()
        } finally { runBlocking { workspace.closeAndJoin(); iconLoader.closeAndJoin() }; client.close(); scope.cancel(); directory.deleteRecursively() }
    }
}

private fun annotatedPredicateSnapshot(value: Any?): Map<String, Any?> {
    if (value == null) return mapOf("isNull" to true)
    if (value !is AnnotatedString) return mapOf("isNull" to false, "class" to value.javaClass.name,
        "classLoader" to value.javaClass.classLoader?.toString(), "classLoaderIdentity" to System.identityHashCode(value.javaClass.classLoader),
        "isAnnotatedString" to false, "representation" to value.toString())
    val annotationStorage = runCatching {
        val getter = value.javaClass.declaredMethods.single { it.name.startsWith("getAnnotations$") && it.parameterCount == 0 }
        val raw = getter.invoke(value)
        mapOf("getter" to getter.name, "isNull" to (raw == null), "class" to raw?.javaClass?.name,
            "count" to (raw as? List<*>)?.size, "items" to (raw as? List<*>)?.map { it.toString() })
    }.getOrElse { mapOf("unavailable" to "${it.javaClass.name}: ${it.message}") }
    return mapOf(
        "isNull" to false, "class" to value.javaClass.name, "classLoader" to value.javaClass.classLoader?.toString(),
        "classLoaderIdentity" to System.identityHashCode(value.javaClass.classLoader),
        "text" to value.text, "textLength" to value.text.length, "codePoints" to value.text.codePoints().toArray().toList(),
        "spanStyles" to value.spanStyles.map { mapOf("start" to it.start, "end" to it.end, "tag" to it.tag, "item" to it.item.toString()) },
        "paragraphStyles" to value.paragraphStyles.map { mapOf("start" to it.start, "end" to it.end, "tag" to it.tag, "item" to it.item.toString()) },
        "stringAnnotations" to value.getStringAnnotations(0, value.length).map { mapOf("start" to it.start, "end" to it.end, "tag" to it.tag, "item" to it.item) },
        "annotationStorage" to annotationStorage,
    )
}
