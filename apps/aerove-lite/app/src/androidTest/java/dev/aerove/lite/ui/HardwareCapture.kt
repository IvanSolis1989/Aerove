package dev.aerove.lite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    fun beginMatrixCollection() {
        check(root.listFiles()?.isEmpty() == true) {
            "Matrix evidence directory must be fresh; never reuse earlier case images"
        }
    }

    fun capture(label: String, windowImage: String = "$label-window.png", configuration: JSONObject? = null): CapturedFrame {
        rule.waitForIdle()
        FrameCommitObservation(label, root).use { observation ->
            rule.runOnUiThread {
                assertTrue("A software View canvas is not an optical acceptance path", probe.view.isHardwareAccelerated)
                assertTrue("The tested window must be visible and focused", probe.view.isShown && probe.view.hasWindowFocus())
                observation.registerAndInvalidate(probe.view, probe.view.context.activity().window)
            }
            assertTrue("No hardware frame was committed", observation.awaitOriginalDeadline())
        }
        return copyCommittedWindow(label, windowImage, configuration)
    }

    fun captureMatrix(label: String, windowImage: String, configuration: JSONObject): MatrixCapturedResult {
        rule.waitForIdle()
        var originalFailure: Throwable? = null
        var diagnosticFailure: Throwable? = null
        var frame: CapturedFrame? = null
        var originalPassed = false
        var originalFailurePhase: String? = null
        var phase = "register"
        val observation = FrameCommitObservation(label, root)
        try {
            observation.use {
                rule.runOnUiThread {
                    assertTrue("A software View canvas is not an optical acceptance path", probe.view.isHardwareAccelerated)
                    assertTrue("The tested window must be visible and focused", probe.view.isShown && probe.view.hasWindowFocus())
                    observation.registerAndInvalidate(probe.view, probe.view.context.activity().window)
                    observation.assertMatrixTargetUnchanged()
                }
                phase = "original-frame-commit"
                originalPassed = observation.awaitOriginalDeadline()
                if (!originalPassed) {
                    originalFailure = AssertionError("No hardware frame was committed")
                    originalFailurePhase = phase
                }
                phase = "diagnostic-frame-commit"
                val submitted = originalPassed || observation.awaitMatrixDiagnosticSubmission()
                if (submitted) {
                    phase = if (originalPassed) "original-pixel-copy" else "diagnostic-pixel-copy"
                    val evidence = observation.matrixSubmissionEvidence()
                    originalFailure?.let { evidence.put("originalFailure", "${it.javaClass.name}: ${it.message}").put("originalFailurePhase", originalFailurePhase) }
                    if (!originalPassed) check(File(root, "diagnostic").let { it.mkdirs() || it.isDirectory })
                    val captureLabel = if (originalPassed) label else "diagnostic/$label"
                    val captureImage = if (originalPassed) windowImage else "diagnostic/$label-window.png"
                    val captureConfiguration = JSONObject(configuration.toString())
                        .put("windowImage", captureImage).put("metadata", "$captureLabel.json")
                    frame = copyCommittedWindow(
                        captureLabel, captureImage, captureConfiguration,
                        includeDisplay = originalPassed, submissionEvidence = evidence,
                        verifyTarget = { observation.assertMatrixTargetUnchanged() },
                    )
                }
                phase = "cleanup"
            }
        } catch (failure: Throwable) {
            rethrowMatrixCancellation(failure, originalFailure)
            if (originalFailure == null) {
                originalFailure = failure
                originalFailurePhase = phase
            } else diagnosticFailure = failure
            frame?.close()
            frame = null
            frames.forEach { it.close() }
            if (!observation.cleanupConfirmed()) throw MatrixCaptureOwnershipFailure(requireNotNull(originalFailure))
                .also { if (it.cause !== failure) it.addSuppressed(failure) }
        }
        return MatrixCapturedResult(frame, originalPassed && originalFailure == null && frame != null,
            originalFailure, diagnosticFailure, originalFailurePhase, phase)
    }

    private fun copyCommittedWindow(
        label: String, windowImage: String, configuration: JSONObject?, includeDisplay: Boolean = true,
        submissionEvidence: JSONObject? = null, verifyTarget: (() -> Unit)? = null,
    ): CapturedFrame {
        lateinit var target: WindowCaptureTarget
        instrumentation.runOnMainSync {
            verifyTarget?.invoke()
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
        try {
            frame.copyOwnership.submit()
            PixelCopy.request(window, bitmap, {
                result.set(it)
                frame.copyOwnership.complete(it)
                Log.i("HardwareCapture", "PixelCopy callback label=$label owner=${frame.copyOwnership.snapshot()}")
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy did not return", copied.await(5, TimeUnit.SECONDS))
            assertEquals("PixelCopy failed; this must not become a skipped/passing screenshot", PixelCopy.SUCCESS, result.get())
            recordCopyOwnership(label, window, frame, null)
        } catch (failure: Throwable) {
            // Freeze the classification at failure: a racing late callback cannot resume the matrix.
            val failedOwnership = frame.copyOwnership.snapshot()
            val terminal = if (failedOwnership.inFlight) PixelCopyOwnershipFailure(failure, failedOwnership) else failure
            try { frame.close() } catch (cleanup: Throwable) { terminal.addSuppressed(cleanup) }
            try { recordCopyOwnership(label, window, frame, failure, failedOwnership) }
            catch (evidenceFailure: Throwable) { terminal.addSuppressed(evidenceFailure) }
            throw terminal
        }
        verifyTarget?.let { verify -> instrumentation.runOnMainSync { verify() } }
        val windowHash = save(bitmap, windowImage)
        val screenHash = if (includeDisplay) {
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation screenshot failed" }
            try { save(screen, "$label-screen.png") } finally { screen.recycle() }
        } else null
        val evidence = JSONObject()
            .put("method", if (includeDisplay) "PixelCopy(Window), plus unedited UiAutomation display screenshot" else "PixelCopy(Window); diagnostic only, no display screenshot")
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
            .put("displayImage", if (includeDisplay) "$label-screen.png" else JSONObject.NULL)
            .put("displaySha256", screenHash ?: JSONObject.NULL)
            .put("configuration", configuration ?: JSONObject.NULL)
            .put("stateOrigin", "instrumentation fixture; no VPN or core action is invoked")
            .put("performanceEvidence", false)
        submissionEvidence?.let { evidence.put("matrixSubmission", it).put("diagnosticOnly", it.getBoolean("diagnosticOnly")) }
        evidence.put("pixelCopyOwnerId", frame.copyOwnership.snapshot().id)
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

    private fun recordCopyOwnership(
        label: String, window: Window, frame: CapturedFrame, failure: Throwable?,
        failedOwnership: PixelCopyOwnershipSnapshot? = null,
    ) {
        val state = frame.copyOwnership.snapshot()
        val evidence = JSONObject().put("ownerId", state.id).put("capture", label)
            .put("windowIdentity", System.identityHashCode(window))
            .put("destinationBitmapIdentity", System.identityHashCode(frame.bitmap))
            .put("submitted", state.submitted).put("callbackResult", state.callbackResult ?: JSONObject.NULL)
            .put("inFlight", state.inFlight).put("closeRequested", state.closeRequested)
            .put("destinationReleased", state.released).put("failure", failure?.toString() ?: JSONObject.NULL)
            .put("failureSnapshotInFlight", failedOwnership?.inFlight ?: JSONObject.NULL)
            .put("terminalAbort", failedOwnership?.inFlight == true)
            .put("performanceEvidence", false)
        File(root, "$label-pixel-copy.json").writeText(evidence.toString(2))
    }

    private fun save(bitmap: Bitmap, name: String): String {
        val target = File(root, name)
        target.outputStream().use { assertTrue("PNG encode failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("PNG was not saved", target.length() > 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(root, "$name.sha256").writeText("$digest  $name\n")
        return digest
    }
}

internal data class MatrixCapturedResult(
    val frame: CapturedFrame?, val accepted: Boolean, val originalFailure: Throwable?,
    val diagnosticFailure: Throwable?, val originalFailurePhase: String?, val finalPhase: String,
) : Closeable {
    override fun close() { frame?.close() }
}

private data class WindowCaptureTarget(val window: Window, val width: Int, val height: Int, val screenOrigin: IntArray)

internal class CapturedFrame(val bitmap: Bitmap) : Closeable {
    val copyOwnership = PixelCopyOwnership { if (!bitmap.isRecycled) bitmap.recycle() }
    var evidence = JSONObject()
    fun pixel(x: Float, y: Float): Int {
        assertTrue("Non-finite or out-of-image pixel coordinate", x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < bitmap.width && y < bitmap.height)
        val ix = x.toInt()
        val iy = y.toInt()
        assertTrue("Probe lies outside the actual image: ($ix,$iy)", ix in 0 until bitmap.width && iy in 0 until bitmap.height)
        return bitmap.getPixel(ix, iy)
    }
    override fun close() {
        val firstClose = !copyOwnership.snapshot().closeRequested
        copyOwnership.close()
        if (firstClose) Log.i("HardwareCapture", "PixelCopy destination close owner=${copyOwnership.snapshot()}")
    }
}

private tailrec fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("The Compose test root is not attached to an Activity")
}
