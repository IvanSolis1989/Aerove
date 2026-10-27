package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LongState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.ColorMode
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import java.util.concurrent.CopyOnWriteArrayList

class FullAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun appearanceReceipts(name: String, stackAtFailure: Boolean = false, body: ((String, String) -> Unit) -> Unit) {
        val sequence = java.util.concurrent.atomic.AtomicInteger()
        val autoAdvance = compose.mainClock.autoAdvance
        fun step(label: String, details: String) {
            val ordinal = sequence.getAndIncrement()
            if (ordinal >= 128) return
            val now = System.nanoTime()
            try {
                val ui = Thread.currentThread() === android.os.Looper.getMainLooper().thread
                val receipt = JSONObject().put("suite", "FullAppearanceUiTest").put("method", name)
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
            if (stackAtFailure) try {
                step("main.stack.at.catch", android.os.Looper.getMainLooper().thread.stackTrace.take(16).joinToString("\n"))
            } catch (traceFailure: Throwable) {
                rethrowMatrixCancellation(traceFailure, failure)
                step("main.stack.unavailable", "type=${traceFailure.javaClass.name}")
            }
            throw failure
        }
    }

    private fun openTheme(step: ((String, String) -> Unit)? = null) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        step?.invoke("tools.tab.click.before", "role=Tab")
        compose.onNode(tab and hasText(text(R.string.lite_tab_tools))).performClick()
        step?.invoke("tools.tab.click.after", "role=Tab")
        step?.invoke("theme.row.scroll.before", "selector=theme+click")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_theme)) and hasClickAction())
        step?.invoke("theme.row.scroll.after", "selector=theme+click")
        step?.invoke("theme.row.click.before", "selector=theme+click")
        compose.onNode(hasText(text(R.string.lite_theme)) and hasClickAction()).performClick()
        step?.invoke("theme.row.click.after", "selector=theme+click")
    }

    private fun editFirstColor(step: ((String, String) -> Unit)? = null) {
        val description = context.getString(R.string.lite_aurora_edit_color, 1)
        step?.invoke("color.first.scroll.before", "index=0")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(description))
        step?.invoke("color.first.scroll.after", "index=0")
        step?.invoke("color.first.click.before", "index=0")
        compose.onNodeWithContentDescription(description).performClick()
        step?.invoke("color.first.click.after", "index=0")
    }

    @Test
    fun colorDraftCancelsOrCommitsWithoutLosingOtherAppearanceSettings() {
        val initial = LiteSettings(colorStyle = ColorStyle.Tinted, auroraRotationDay = 99991231)
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
        openTheme()
        editFirstColor()
        compose.onNodeWithTag("aurora-color-grid").performTouchInput { click(Offset(width * 0.46f, height * 0.55f)) }
        compose.runOnUiThread { assertTrue(actions.settings.isEmpty()) }
        compose.onNodeWithText(text(R.string.lite_cancel)).performClick()
        compose.runOnUiThread { assertEquals(initial, state.value.settings); assertTrue(actions.settings.isEmpty()) }
        editFirstColor()
        compose.onNodeWithTag("aurora-color-grid").performTouchInput { click(Offset(width * 0.46f, height * 0.55f)) }
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(auroraSeed = 832838199)) }
        compose.onNodeWithText(text(R.string.lite_confirm)).performClick()
        compose.runOnUiThread {
            assertEquals(1, actions.settings.size)
            assertNotEquals(initial.auroraColors[0], state.value.settings.auroraColors[0])
            assertEquals(initial.auroraColors.drop(1), state.value.settings.auroraColors.drop(1))
            assertEquals(832838199L, state.value.settings.auroraSeed)
            assertEquals(initial.colorStyle, state.value.settings.colorStyle)
            assertEquals(initial.auroraRotationDay, state.value.settings.auroraRotationDay)
            assertEquals(listOf("changeSettings"), actions.events)
        }
    }

    @Test
    fun paletteKeepsTheFullOneToThreeColorEditingBounds() {
        appearanceReceipts("paletteKeepsTheFullOneToThreeColorEditingBounds", stackAtFailure = true) { step ->
            val state = mutableStateOf(LiteState())
            val actions = UiTestActions { state.value = state.value.copy(settings = it) }
            step("content.replace.before", "motionOverride=true; paletteSize=${state.value.settings.auroraColors.size}")
            compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
            step("content.replace.after", "motionOverride=true")
            openTheme(step)
            repeat(2) {
                step("delete.iteration.before", "index=$it; paletteSize=${state.value.settings.auroraColors.size}")
                editFirstColor(step)
                step("delete.click.before", "index=$it")
                compose.onNodeWithText(text(R.string.lite_delete)).performClick()
                step("delete.click.after", "index=$it; paletteSize=${state.value.settings.auroraColors.size}")
            }
            step("single.color.assert.before", "paletteSize=${state.value.settings.auroraColors.size}")
            compose.runOnUiThread { assertEquals(1, state.value.settings.auroraColors.size) }
            step("single.color.assert.after", "paletteSize=1 asserted on UI thread")
            editFirstColor(step)
            step("delete.absent.assert.before", "paletteSize=${state.value.settings.auroraColors.size}")
            compose.onNodeWithText(text(R.string.lite_delete)).assertDoesNotExist()
            step("delete.absent.assert.after", "single-color Delete absent")
            step("editor.cancel.before", "single-color editor")
            compose.onNodeWithText(text(R.string.lite_cancel)).performClick()
            step("editor.cancel.after", "single-color editor")
            repeat(2) {
                step("add.click.before", "index=$it; paletteSize=${state.value.settings.auroraColors.size}")
                compose.onNodeWithContentDescription(text(R.string.lite_add_color)).performClick()
                step("add.click.after", "index=$it")
                step("add.confirm.before", "index=$it")
                compose.onNodeWithText(text(R.string.lite_confirm)).performClick()
                step("add.confirm.after", "index=$it; paletteSize=${state.value.settings.auroraColors.size}")
            }
            step("three.colors.assert.before", "paletteSize=${state.value.settings.auroraColors.size}")
            compose.runOnUiThread { assertEquals(3, state.value.settings.auroraColors.size) }
            step("three.colors.assert.after", "paletteSize=3 asserted on UI thread")
            step("add.absent.assert.before", "paletteSize=${state.value.settings.auroraColors.size}")
            compose.onNodeWithContentDescription(text(R.string.lite_add_color)).assertDoesNotExist()
            step("add.absent.assert.after", "three-color Add absent")
        }
    }

    @Test
    fun themePreviewStaysStillWhileTheWallpaperMoves() {
        appearanceReceipts("themePreviewStaysStillWhileTheWallpaperMoves") { step ->
            val state = mutableStateOf(LiteState(settings = LiteSettings(auroraRotationDay = 99991231)))
            step("content.replace.before", "motionOverride=false; seed=${state.value.settings.auroraSeed}")
            compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides false) { LiteApp(state.value, UiTestActions {}) } }
            step("content.replace.after", "motionOverride=false")
            openTheme(step)
            val preview = hasContentDescription(text(R.string.lite_preview))
            step("preview.scroll.before", "selector=preview content description")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(preview)
            step("preview.scroll.after", "selector=preview content description")
            var captureOrdinal = 0
            fun centre(): List<Int> {
                step("preview.capture.before", "ordinal=${++captureOrdinal}; original captureToImage")
                val map = compose.onNode(preview).captureToImage().toPixelMap()
                step("preview.capture.after", "ordinal=$captureOrdinal; width=${map.width}; height=${map.height}")
                return (map.height / 5 until map.height * 4 / 5).flatMap { y -> (map.width / 5 until map.width * 4 / 5).map { x -> map[x, y].toArgb() } }
            }
            val before = centre()
            step("preview.clock.advance.before", "deltaMillis=5000")
            compose.mainClock.advanceTimeBy(5_000)
            step("preview.clock.advance.after", "deltaMillis=5000")
            assertTrue("The preview must stay as still as Standard's SeededAuroraWallpaper(motion: false)", before == centre())
            step("preview.static.assert.after", "original equality passed")
        }
    }

    @Test
    fun wallpaperShuffleUsesTheAtomicActionWithoutRewritingSettings() {
        val initial = LiteSettings(auroraRotationDay = 99991231)
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
        openTheme()
        val label = text(R.string.lite_aurora_regenerate)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label) and hasClickAction())
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.runOnUiThread {
            assertEquals(initial, state.value.settings)
            assertTrue(actions.settings.isEmpty())
            assertEquals(listOf("shuffleAurora"), actions.events)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_normal_theme)) and hasClickAction())
        compose.onNode(hasText(text(R.string.lite_normal_theme)) and hasClickAction()).performClick()
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun colorStyleIsPartOfTheProductionRememberKey() {
        val settings = mutableStateOf(LiteSettings(aurora = false, colorMode = ColorMode.Light))
        compose.replaceAppContent {
            LiteTheme(settings.value) {
                Text(MaterialTheme.colorScheme.primary.toArgb().toString(), Modifier.testTag("resolved-primary"))
                Text(MaterialTheme.colorScheme.surface.toArgb().toString(), Modifier.testTag("resolved-surface"))
            }
        }
        for (dark in listOf(false, true)) for (style in ColorStyle.entries) {
            compose.runOnUiThread { settings.value = settings.value.copy(colorMode = if (dark) ColorMode.Dark else ColorMode.Light, colorStyle = style) }
            val expected = fullColorScheme(settings.value.themeColor.toInt(), dark, false, style)
            compose.onNodeWithTag("resolved-primary").assertTextEquals(expected.primary.toArgb().toString())
            compose.onNodeWithTag("resolved-surface").assertTextEquals(expected.surface.toArgb().toString())
        }
    }

    @Test
    fun customPaletteAndSeedReachTheActualHardwareWallpaper() {
        appearanceReceipts("customPaletteAndSeedReachTheActualHardwareWallpaper") { step ->
            val settings = LiteSettings(material = MaterialTier.Clear, colorMode = ColorMode.Dark)
            val state = mutableStateOf(LiteState(settings = settings))
            val probe = RenderProbe()
            step("content.replace.before", "motionOverride=true; seed=${settings.auroraSeed}; paletteSize=${settings.auroraColors.size}")
            compose.replaceAppContent {
                val view = LocalView.current
                val density = LocalDensity.current
                SideEffect { probe.view = view; probe.density = density.density; probe.fontScale = density.fontScale }
                CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { probe.bounds["app"] = it.boundsInWindow() }) { LiteApp(state.value, UiTestActions {}) }
                }
            }
            step("content.replace.after", "original production LiteApp fixture")
            HardwareCapture(compose, probe, "full-aurora-settings").use { evidence ->
                step("default.capture.before", "label=default; existing FrameCommit/PixelCopy receipt")
                evidence.capture("default", configuration = JSONObject().put("fixture", "production LiteApp, stopped core, reduced motion")).use { before ->
                    step("default.capture.after", "width=${before.bitmap.width}; height=${before.bitmap.height}")
                    step("custom.settings.before", "seed=832838199; paletteSize=2")
                    compose.runOnUiThread {
                        state.value = state.value.copy(settings = settings.copy(auroraColors = listOf(0xfff47231L, 0xfff0bb50L), auroraSeed = 832838199))
                    }
                    step("custom.settings.after", "seed=${state.value.settings.auroraSeed}; paletteSize=${state.value.settings.auroraColors.size}")
                    step("wallpaper.build.wait.before", "original timeoutMillis=10000")
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("aurora-wallpaper-building").fetchSemanticsNodes().isEmpty() }
                    step("wallpaper.build.wait.after", "original building nodes absent")
                    step("custom.idle.before", "original waitForIdle")
                    compose.waitForIdle()
                    step("custom.idle.after", "original waitForIdle returned")
                    step("custom.capture.before", "label=custom; existing FrameCommit/PixelCopy receipt")
                    evidence.capture("custom", configuration = JSONObject().put("fixture", "two custom colors, seed 832838199")).use { after ->
                        step("custom.capture.after", "width=${after.bitmap.width}; height=${after.bitmap.height}")
                        assertEquals(before.bitmap.width, after.bitmap.width)
                        assertEquals(before.bitmap.height, after.bitmap.height)
                        val changes = (1..8).map { sample ->
                            val x = 2
                            val y = before.bitmap.height * sample / 10
                            val a = before.bitmap.getPixel(x, y); val b = after.bitmap.getPixel(x, y)
                            (0..2).maxOf { shift -> abs((a ushr (shift * 8) and 255) - (b ushr (shift * 8) and 255)) }
                        }
                        step("wallpaper.pixels.assert.before", "original channelChanges=$changes")
                        assertTrue("Custom settings must change real uncovered wallpaper pixels", changes.count { it > 8 } >= 4)
                        step("wallpaper.pixels.assert.after", "original threshold passed")
                    }
                }
            }
        }
    }

    @Test
    fun interactionCapsFramesAndReturnsToIdleAfterTheFullGracePeriod() {
        val samples = CopyOnWriteArrayList<Long>()
        var activity: AuroraMotionActivity? = null
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides false) {
                LiteTheme(LiteSettings()) {
                    val motion = rememberAuroraMotionActivity("motion-fixture")
                    val time = rememberAuroraTime(true, activity = motion)
                    SideEffect { activity = motion }
                    LaunchedEffect(time) { snapshotFlow { time.longValue }.collect { samples += it } }
                    Box(Modifier.fillMaxSize().auroraMotionActivity(motion).testTag("motion-fixture"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        samples.clear()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Idle must publish 15fps on the device frame grid: ${samples.size}", samples.size in 14..16)
        compose.onNodeWithTag("motion-fixture").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnUiThread { assertEquals(10, requireNotNull(activity).framesPerSecond) }
        samples.clear()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Held interaction must publish 10fps: ${samples.size}", samples.size in 9..11)
        compose.onNodeWithTag("motion-fixture").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320)
        compose.runOnUiThread { assertTrue(requireNotNull(activity).active) }
        compose.mainClock.advanceTimeBy(50)
        compose.runOnUiThread { assertEquals(15, requireNotNull(activity).framesPerSecond) }
    }

    @Test
    fun reducedMotionRestartsTheClockAndAnInactiveActivityHoldsIt() {
        val reduced = mutableStateOf(false)
        var time: androidx.compose.runtime.LongState? = null
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides reduced.value) {
                LiteTheme(LiteSettings()) {
                    val clock = rememberAuroraTime(true)
                    SideEffect { time = clock }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread { assertTrue(requireNotNull(time).longValue > 0L); reduced.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { assertEquals(0L, requireNotNull(time).longValue) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnUiThread { assertEquals(0L, requireNotNull(time).longValue); reduced.value = false }
        compose.mainClock.advanceTimeBy(500)
        var frozen = 0L
        compose.runOnUiThread { frozen = requireNotNull(time).longValue; assertTrue("must restart from 0: $frozen", frozen in 1L..600_000L) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { frozen = requireNotNull(time).longValue; assertTrue(frozen > 0L) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnUiThread { assertEquals(frozen, requireNotNull(time).longValue) }
    }

    @Test
    fun wallpaperShowsTheRestPoseWheneverReducedMotionIsOn() {
        appearanceReceipts("wallpaperShowsTheRestPoseWheneverReducedMotionIsOn") { step ->
            for (reducedAtStart in listOf(true, false)) {
                val reduced = mutableStateOf(reducedAtStart)
                val probe = RenderProbe()
                val clock = java.util.concurrent.atomic.AtomicReference<LongState>()
                val gate = java.util.concurrent.atomic.AtomicReference<Boolean>()
                val restClock = java.util.concurrent.atomic.AtomicReference<LongState>()
                var runningImage: androidx.compose.ui.graphics.ImageBitmap? = null
                var restImage: androidx.compose.ui.graphics.ImageBitmap? = null
                var pair = 0
                val stamp = System.nanoTime()
                var originalFailure: Throwable? = null
                var safeToExport = true
                try {
                    compose.mainClock.autoAdvance = false
                    compose.replaceAppContent {
                        CompositionLocalProvider(LocalLiteMotionOverride provides reduced.value) {
                            LiteTheme(LiteSettings(colorMode = ColorMode.Dark)) {
                                val time = rememberAuroraTime(true)
                                val rest = remember { mutableLongStateOf(0L) }
                                val density = LocalDensity.current
                                val resolvedReduced = LocalLiteReducedMotion.current
                                SideEffect { clock.set(time); restClock.set(rest); gate.set(resolvedReduced); probe.density = density.density; probe.fontScale = density.fontScale }
                                Column {
                                    Box(Modifier.size(160.dp, 320.dp).testTag("running").onGloballyPositioned { probe.bounds["running"] = it.boundsInWindow() }) { AuroraWallpaper(time, FullAuroraColors, FullAuroraSeed, true, Modifier.fillMaxSize()) }
                                    Box(Modifier.size(160.dp, 320.dp).testTag("rest").onGloballyPositioned { probe.bounds["rest"] = it.boundsInWindow() }) { AuroraWallpaper(rest, FullAuroraColors, FullAuroraSeed, true, Modifier.fillMaxSize()) }
                                }
                            }
                        }
                    }
                    fun atRest(): Boolean {
                        pair++
                        runningImage = null; restImage = null
                        step("reduced.running.capture.before", "start=$reducedAtStart; pair=$pair; reduced=${gate.get()}; rawClockMicros=${clock.get()?.longValue}; bounds=${probe.bounds}")
                        val running = compose.onNodeWithTag("running").captureToImage().also { runningImage = it }.toPixelMap()
                        step("reduced.running.capture.after", "start=$reducedAtStart; pair=$pair; size=${running.width}x${running.height}; buffer=${running.buffer.size}; rawClockMicros=${clock.get()?.longValue}")
                        step("reduced.rest.capture.before", "start=$reducedAtStart; pair=$pair; reduced=${gate.get()}; restClockMicros=${restClock.get()?.longValue}; bounds=${probe.bounds}")
                        val resting = compose.onNodeWithTag("rest").captureToImage().also { restImage = it }.toPixelMap()
                        val equal = running.buffer.contentEquals(resting.buffer)
                        step("reduced.rest.capture.after", "start=$reducedAtStart; pair=$pair; size=${resting.width}x${resting.height}; buffer=${resting.buffer.size}; rawClockMicros=${clock.get()?.longValue}; equal=$equal")
                        return equal
                    }
                    compose.mainClock.advanceTimeBy(5_000)
                    assertEquals("reduced from the start: $reducedAtStart", reducedAtStart, atRest())
                    compose.runOnUiThread { reduced.value = true }
                    compose.mainClock.advanceTimeByFrame()
                    assertTrue("switching reduced motion on mid-run must show the rest pose", atRest())
                    compose.mainClock.advanceTimeBy(1_000)
                    assertTrue(atRest())
                    compose.runOnUiThread { reduced.value = false }
                    compose.mainClock.advanceTimeBy(5_000)
                    assertTrue("motion resumes from the rest pose", !atRest())
                } catch (failure: Throwable) {
                    safeToExport = false
                    rethrowMatrixCancellation(failure)
                    safeToExport = true
                    originalFailure = failure
                    throw failure
                } finally {
                    if (safeToExport) {
                        if (Thread.currentThread().isInterrupted) rethrowMatrixCancellation(InterruptedException("Interrupted before diagnostic export"), originalFailure)
                        try {
                            val root = java.io.File(requireNotNull(context.getExternalFilesDir(null)),
                                "captures/full-appearance-reduced/diagnostic/start-$reducedAtStart-$stamp")
                            check(root.mkdirs() || root.isDirectory)
                            val images = JSONObject()
                            listOf("running" to runningImage, "rest" to restImage).forEach { (name, image) -> if (image != null) {
                                val source = image.asAndroidBitmap()
                                val target = java.io.File(root, "$name.png")
                                target.outputStream().use { check(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" } }
                                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                                java.io.File(root, "$name.png.sha256").writeText("$hash  $name.png\n")
                                images.put(name, JSONObject().put("file", target.name).put("sha256", hash).put("width", image.width).put("height", image.height))
                            } }
                            java.io.File(root, "capture-pair.json").writeText(JSONObject().put("diagnosticOnly", true).put("reducedAtStart", reducedAtStart).put("pair", pair)
                                .put("sourceRevision", InstrumentationRegistry.getArguments().getString("sourceRevision", "not supplied"))
                                .put("captureApi", "original Compose captureToImage; latest original pair exported after original assertions")
                                .put("density", probe.density).put("fontScale", probe.fontScale).put("boundsInWindow", JSONObject(probe.bounds.mapValues { (_, rect) -> JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)) }))
                                .put("inputKey", JSONObject().put("seed", FullAuroraSeed).put("colors", JSONArray(FullAuroraColors)).put("dark", true))
                                .put("privateLayerKeyAndOriginObserved", false).put("phaseAndGateReceipt", "LiteUiStep before/after capture; composition gate snapshot is not a renderer trace")
                                .put("images", images).put("originalFailure", originalFailure?.let { "${it.javaClass.name}: ${it.message}" } ?: JSONObject.NULL).toString(2))
                        } catch (diagnosticFailure: Throwable) {
                            rethrowMatrixCancellation(diagnosticFailure, originalFailure)
                            if (originalFailure == null) throw diagnosticFailure else originalFailure!!.addSuppressed(diagnosticFailure)
                        }
                    }
                }
            }
        }
    }
}
