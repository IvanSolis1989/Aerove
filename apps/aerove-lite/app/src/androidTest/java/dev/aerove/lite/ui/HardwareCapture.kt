package dev.aerove.lite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class RenderProbe {
    lateinit var view: View
    var density = 1f
    var fontScale = 1f
    var surface = 0
    var selectedSurface = 0
    var switchTrack = 0
    var switchThumb = 0
    var ink = 0
    val bounds = ConcurrentHashMap<String, Rect>()
}

internal class HardwareCapture(
    private val rule: ComposeContentTestRule,
    private val probe: RenderProbe,
    name: String,
) : Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(requireNotNull(context.getExternalFilesDir(null)), "captures/${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}")
    private val frames = mutableListOf<CapturedFrame>()

    init {
        assertTrue("The product and optical tests require API33+", Build.VERSION.SDK_INT >= 33)
        assertTrue("Cannot create capture directory", root.mkdirs() || root.isDirectory)
    }

    fun capture(label: String, windowImage: String = "$label-window.png", configuration: JSONObject? = null): CapturedFrame {
        rule.waitForIdle()
        val committed = CountDownLatch(1)
        rule.runOnUiThread {
            assertTrue("A software View canvas is not an optical acceptance path", probe.view.isHardwareAccelerated)
            assertTrue("The tested window must be visible and focused", probe.view.isShown && probe.view.hasWindowFocus())
            probe.view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            probe.view.invalidate()
        }
        assertTrue("No hardware frame was committed", committed.await(5, TimeUnit.SECONDS))
        lateinit var target: WindowCaptureTarget
        instrumentation.runOnMainSync {
            val window = probe.view.context.activity().window
            val decor = window.decorView
            target = WindowCaptureTarget(window, decor.width, decor.height, IntArray(2).also { decor.getLocationOnScreen(it) })
        }
        val (window, width, height, screenOrigin) = target
        assertTrue("Empty capture target", width > 0 && height > 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val frame = CapturedFrame(bitmap).also { frames += it }
        val copied = CountDownLatch(1)
        val result = AtomicInteger(-1)
        PixelCopy.request(window, bitmap, {
            result.set(it)
            copied.countDown()
        }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy did not return", copied.await(5, TimeUnit.SECONDS))
        assertEquals("PixelCopy failed; this must not become a skipped/passing screenshot", PixelCopy.SUCCESS, result.get())
        val windowHash = save(bitmap, windowImage)
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation screenshot failed" }
        val screenHash = try { save(screen, "$label-screen.png") } finally { screen.recycle() }
        val evidence = JSONObject()
            .put("method", "PixelCopy(Window), plus unedited UiAutomation display screenshot")
            .put("api", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("hardware", Build.HARDWARE)
            .put("rendererBackend", InstrumentationRegistry.getArguments().getString("rendererBackend", "not supplied; hardware acceleration alone does not identify a physical GPU"))
            .put("model", Build.MODEL)
            .put("deviceKind", InstrumentationRegistry.getArguments().getString("deviceKind", "not supplied"))
            .put("virtualDeviceHint", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.MODEL.contains("Emulator"))
            .put("version", BuildConfig.VERSION_NAME)
            .put("sourceRevision", InstrumentationRegistry.getArguments().getString("sourceRevision", "not supplied"))
            .put("density", probe.density)
            .put("fontScale", probe.fontScale)
            .put("windowWidth", width)
            .put("windowHeight", height)
            .put("windowOriginOnScreen", JSONArray(screenOrigin.toList()))
            .put("colorSpace", bitmap.colorSpace?.name)
            .put("windowImage", windowImage)
            .put("windowSha256", windowHash)
            .put("displayImage", "$label-screen.png")
            .put("displaySha256", screenHash)
            .put("configuration", configuration ?: JSONObject.NULL)
            .put("stateOrigin", "instrumentation fixture; no VPN or core action is invoked")
            .put("performanceEvidence", false)
        val boxes = JSONObject()
        probe.bounds.forEach { (key, rect) -> boxes.put(key, JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))) }
        evidence.put("windowCoordinates", boxes)
        File(root, "$label.json").writeText(evidence.toString(2))
        frame.evidence = evidence
        return frame
    }

    fun metrics(values: JSONObject) { File(root, "metrics.json").writeText(values.toString(2)) }
    fun manifest(values: JSONObject) { File(root, "manifest.json").writeText(values.toString(2)) }

    override fun close() { frames.forEach { it.close() }; frames.clear() }

    private fun save(bitmap: Bitmap, name: String): String {
        val target = File(root, name)
        target.outputStream().use { assertTrue("PNG encode failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("PNG was not saved", target.length() > 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(root, "$name.sha256").writeText("$digest  $name\n")
        return digest
    }
}

private data class WindowCaptureTarget(val window: Window, val width: Int, val height: Int, val screenOrigin: IntArray)

internal class CapturedFrame(val bitmap: Bitmap) : Closeable {
    var evidence = JSONObject()
    fun pixel(x: Float, y: Float): Int {
        assertTrue("Non-finite or out-of-image pixel coordinate", x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < bitmap.width && y < bitmap.height)
        val ix = x.toInt()
        val iy = y.toInt()
        assertTrue("Probe lies outside the actual image: ($ix,$iy)", ix in 0 until bitmap.width && iy in 0 until bitmap.height)
        return bitmap.getPixel(ix, iy)
    }
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

private tailrec fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("The Compose test root is not attached to an Activity")
}
