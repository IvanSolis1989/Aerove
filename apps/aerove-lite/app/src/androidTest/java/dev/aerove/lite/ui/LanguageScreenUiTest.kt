package dev.aerove.lite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import org.json.JSONObject

class LanguageScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "language-menu-${UUID.randomUUID()}")
    private val state = mutableStateOf(LiteState())
    private val store = LiteSettingsStore(directory)
    private var writes = 0
    @After fun clean() { directory.deleteRecursively() }
    private fun text(id: Int) = context.getString(id)
    private fun read() = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
    private fun languageReceipts(body: ((String, String) -> Unit) -> Unit) {
        val sequence = java.util.concurrent.atomic.AtomicInteger()
        val autoAdvance = compose.mainClock.autoAdvance
        fun step(label: String, details: String) {
            val ordinal = sequence.getAndIncrement()
            if (ordinal >= 128) return
            val now = System.nanoTime()
            try {
                val ui = Thread.currentThread() === android.os.Looper.getMainLooper().thread
                val receipt = JSONObject().put("suite", "LanguageScreenUiTest")
                    .put("method", "actualToolsRowOpensAnAnchoredCheckedMenuAndSelectionClosesAndPersists")
                    .put("ordinal", ordinal).put("step", label).put("nanoTime", now)
                    .put("thread", Thread.currentThread().name).put("autoAdvance", autoAdvance)
                    .put("clockMillis", if (ui) JSONObject.NULL else compose.mainClock.currentTime)
                    .put("details", details.take(2600)).put("deadlineObservation", false)
                android.util.Log.i("LiteUiStep", receipt.toString())
            } catch (failure: Throwable) {
                rethrowMatrixCancellation(failure)
                System.err.println("LiteUiStep receipt error: ${failure.javaClass.name}")
            }
        }
        step("method.body.enter", "runner deadline anchor unavailable; no scheduled sample")
        try {
            body(::step)
            step("method.body.return", "original assertions returned")
        } catch (failure: Throwable) {
            rethrowMatrixCancellation(failure)
            step("caught.failure", "type=${failure.javaClass.name}; original throwable rethrown")
            try {
                step("main.stack.at.catch", android.os.Looper.getMainLooper().thread.stackTrace.take(16).joinToString("\n"))
            } catch (traceFailure: Throwable) {
                rethrowMatrixCancellation(traceFailure, failure)
                step("main.stack.unavailable", "type=${traceFailure.javaClass.name}")
            }
            throw failure
        }
    }

    private fun launchTools(step: ((String, String) -> Unit)? = null) {
        val actions = UiTestActions { settings ->
            step?.invoke("settings.save.before", "locale=${settings.locale}; writes=$writes")
            runBlocking(Dispatchers.IO) { store.save(settings) }
            step?.invoke("settings.save.persisted", "locale=${settings.locale}")
            writes++
            state.value = state.value.copy(settings = settings)
            step?.invoke("settings.state.published", "locale=${settings.locale}; writes=$writes")
        }
        step?.invoke("content.replace.before", "motionOverride=platform; locale=${state.value.settings.locale}")
        compose.replaceAppContent { LiteApp(state.value, actions) }
        step?.invoke("content.replace.after", "original LiteApp fixture")
        step?.invoke("tools.tab.click.before", "role=Tab")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text(R.string.lite_tab_tools))).performClick()
        step?.invoke("tools.tab.click.after", "role=Tab")
        step?.invoke("language.row.scroll.before", "tag=language-preference-row")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("language-preference-row"))
        step?.invoke("language.row.scroll.after", "tag=language-preference-row")
    }

    @Test fun actualToolsRowOpensAnAnchoredCheckedMenuAndSelectionClosesAndPersists() {
        languageReceipts { step ->
            launchTools(step)
            step("language.row.open.before", "tag=language-preference-row")
            compose.onNodeWithTag("language-preference-row").performClick()
            step("language.row.open.after", "tag=language-preference-row")
            step("menu.display.assert.before", "tag=language-menu")
            compose.onNodeWithTag("language-menu").assertIsDisplayed()
            step("menu.display.assert.after", "tag=language-menu")
            step("system.selected.assert.before", "tag=locale-option-system")
            compose.onNodeWithTag("locale-option-system").assertIsSelected()
            step("system.selected.assert.after", "tag=locale-option-system")
            for (tag in listOf("en", "es", "fr", "ja", "ko", "ru", "zh_CN")) {
                step("locale.select.before", "tag=$tag; writes=$writes")
                compose.onNodeWithTag("locale-option-$tag").performScrollTo().performClick()
                step("locale.select.after", "tag=$tag; writes=$writes")
                step("locale.read.assert.before", "expectedTag=$tag")
                assertEquals(tag, read().locale)
                step("locale.read.assert.after", "persistedTag=$tag asserted; writes=$writes")
                step("menu.closed.assert.before", "selectedTag=$tag")
                compose.onNodeWithTag("language-menu").assertDoesNotExist()
                step("menu.closed.assert.after", "selectedTag=$tag")
                step("menu.reopen.before", "selectedTag=$tag")
                compose.onNodeWithTag("language-preference-row").assertIsDisplayed().performClick()
                step("menu.reopen.after", "selectedTag=$tag")
                step("locale.reopened.selected.assert.before", "tag=$tag")
                compose.onNodeWithTag("locale-option-$tag").performScrollTo().assertIsSelected()
                step("locale.reopened.selected.assert.after", "tag=$tag")
            }
            step("system.select.before", "tag=locale-option-system; writes=$writes")
            compose.onNodeWithTag("locale-option-system").performScrollTo().performClick()
            step("system.select.after", "writes=$writes")
            step("system.read.assert.before", "expectedLocale=null")
            assertNull(read().locale)
            step("system.read.assert.after", "persisted null asserted")
            assertEquals(8, writes)
            step("writes.assert.after", "writes=8 asserted")
            compose.onNodeWithTag("language-menu").assertDoesNotExist()
            step("system.menu.closed.assert.after", "tag=language-menu")
        }
    }

    @Test fun currentValueButtonAlsoOpensAndOutsideOrBackOnlyCancel() {
        launchTools()
        compose.onNodeWithTag("language-preference-value").performClick()
        compose.onNodeWithTag("language-menu").assertIsDisplayed()
        compose.onNodeWithTag("language-menu-dismiss").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("language-menu").assertDoesNotExist()
        assertEquals(0, writes)
        compose.onNodeWithTag("language-preference-value").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("language-menu").assertDoesNotExist()
        compose.onNodeWithTag("language-preference-row").assertIsDisplayed()
        assertEquals(0, writes)
        assertNull(read().locale)
    }
}
