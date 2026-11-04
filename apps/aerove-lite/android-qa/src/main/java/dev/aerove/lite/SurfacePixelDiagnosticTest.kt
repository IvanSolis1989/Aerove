package dev.aerove.lite

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One readback, then one compositor capture; no product types, input actions, or state injection. */
class SurfacePixelDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val mainWorkOutstanding = AtomicInteger(0)
    private val mainWorkTeardownRequired = AtomicBoolean(false)

    @Test(timeout = 120_000)
    fun captureLateSurfaceAndCompositor() {
        val token = argument("qaToken")
        require(token.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid qaToken" }
        check(context.packageName == TARGET && instrumentation.context.packageName == HARNESS) {
            "Unexpected target or harness package"
        }
        check(Build.VERSION.SDK_INT == 33 && Process.myUid() == context.applicationInfo.uid) {
            "Require the owned API33 target App process"
        }
        val parent = File(requireNotNull(context.getExternalFilesDir(null)), "surface-pixel-diagnostic")
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create diagnostic parent" }
        val root = File(parent, token)
        check(root.mkdir()) { "qaToken directory must be new" }
        val receipt = JSONObject().put("schema", 1).put("producer", javaClass.name)
            .put("diagnosticOnly", true).put("accepted", false).put("visualApproved", false)
            .put("captureCompleted", false).put("status", "running")
            .put("qaTokenSha256", digest(token.toByteArray(Charsets.UTF_8)))
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("sdkInt", Build.VERSION.SDK_INT)
            .put("startedElapsedRealtimeNanos", now())
            .put("observation", "instrumented fresh install; Launcher initialization may write normal App data")
            .put("atomicSameFrame", false).put("activityFinishRequestedByTest", false)
            .put("activityTeardownOwner", "AndroidJUnitRunner after test; host owns installation and AVD cleanup")
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "surface-pixel-diagnostic-worker").apply { isDaemon = true }
        }
        val callbacks = HandlerThread("surface-pixel-diagnostic-callback")
        var activeTask: Future<*>? = null
        var copyPending = false
        val copyStatus = AtomicInteger(-1)
        val copyStart = AtomicLong(0)
        val copyReturn = AtomicLong(0)
        val copyCallback = AtomicLong(0)
        val compositorStart = AtomicLong(0)
        val compositorEnd = AtomicLong(0)
        val surfaceEpoch = AtomicLong(0)
        val surfaceObserver = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceEpoch.incrementAndGet() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceEpoch.incrementAndGet()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceEpoch.incrementAndGet() }
        }
        var observedHolder: SurfaceHolder? = null
        var surfaceBitmap: Bitmap? = null
        var compositorBitmap: Bitmap? = null
        var stage = "identity"
        fun saveReceipt() = File(root, "receipt.json").writeText(receipt.toString(2) + "\n")
        saveReceipt()
        try {
            receipt.put("originalApp", originalIdentity()).put("harness", harnessIdentity())
                .put("execution", JSONObject()
                    .put("source", sourceArgument("executorSourceSha"))
                    .put("run", numberArgument("executionRun"))
                    .put("attempt", numberArgument("executionAttempt")))
            stage = "launcher"
            saveReceipt()
            val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(TARGET)) {
                "No production Launcher Intent"
            }
            check(launch.action == Intent.ACTION_MAIN && launch.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                "Intent is not MAIN/LAUNCHER"
            }
            val resolved = requireNotNull(launch.resolveActivityInfo(context.packageManager, 0))
            check(resolved.packageName == TARGET && resolved.name == LAUNCHER && resolved.processName == TARGET) {
                "Unexpected Launcher component or process"
            }
            receipt.put("launcherSubmittedNanos", now())
            val launchTask = worker.submit(Callable { instrumentation.startActivitySync(launch) })
            activeTask = launchTask
            val activity = launchTask.get(25, TimeUnit.SECONDS)
            activeTask = null
            check(activity.packageName == TARGET && activity.componentName.className == LAUNCHER)
            val launched = now()
            receipt.put("launcherReturnedNanos", launched).put("lateFrameDelayMs", 15_000)
            stage = "late-frame-wait"
            saveReceipt()
            val lateAt = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < lateAt) {
                Thread.sleep(minOf(100L, lateAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
            stage = "surface-snapshot"
            val before = onMain {
                val initial = snapshot(activity, surfaceEpoch.get())
                initial.view.holder.addCallback(surfaceObserver)
                observedHolder = initial.view.holder
                snapshot(activity, surfaceEpoch.get())
            }
            receipt.put("beforeSurfaceCopy", before.json)
            val destination = Bitmap.createBitmap(before.width, before.height, Bitmap.Config.ARGB_8888)
            surfaceBitmap = destination
            destination.eraseColor(0xfff10df1.toInt())
            val finished = CountDownLatch(1)
            callbacks.start()
            val callbackHandler = Handler(callbacks.looper)
            stage = "surface-copy"
            receipt.put("surfaceCopy", JSONObject().put("method", "PixelCopy.request(SurfaceView,Bitmap,listener,Handler)")
                .put("submittedNanos", now()).put("destinationSentinelArgb", "fff10df1"))
            saveReceipt()
            copyPending = true
            val copyTask = worker.submit(Callable {
                copyStart.set(now())
                try {
                    PixelCopy.request(before.view, destination, { result ->
                        copyStatus.set(result)
                        copyCallback.set(now())
                        finished.countDown()
                    }, callbackHandler)
                } finally {
                    copyReturn.set(now())
                }
            })
            activeTask = copyTask
            copyTask.get(20, TimeUnit.SECONDS)
            activeTask = null
            copyPending = false
            check(finished.await(3, TimeUnit.SECONDS)) { "PixelCopy callback deadline exceeded" }
            check(copyStatus.get() == PixelCopy.SUCCESS) { "PixelCopy returned a failure status" }
            val afterCopy = onMain { snapshot(activity, surfaceEpoch.get()) }
            checkSame(before, afterCopy)
            receipt.put("afterSurfaceCopy", afterCopy.json)
            receipt.getJSONObject("surfaceCopy").put("image", savePng(root, "surface.png", destination))
            stage = "compositor-copy"
            val beforeCompositor = onMain { snapshot(activity, surfaceEpoch.get()) }
            checkSame(before, beforeCompositor)
            receipt.put("beforeCompositorCopy", beforeCompositor.json)
            receipt.put("compositorCopy", JSONObject().put("method", "UiAutomation.takeScreenshot")
                .put("uiAutomationFirstAccessAfterSurfacePng", true).put("submittedNanos", now()))
            saveReceipt()
            // First and only UiAutomation access is after the successful Surface PNG has been saved.
            val screenshotTask = worker.submit(Callable {
                compositorStart.set(now())
                try {
                    instrumentation.uiAutomation.takeScreenshot()
                } finally {
                    compositorEnd.set(now())
                }
            })
            activeTask = screenshotTask
            val screenshot = requireNotNull(screenshotTask.get(15, TimeUnit.SECONDS)) {
                "UiAutomation returned no screenshot"
            }
            activeTask = null
            compositorBitmap = screenshot
            val afterCompositor = onMain { snapshot(activity, surfaceEpoch.get()) }
            checkSame(before, afterCompositor)
            check(screenshot.width == afterCompositor.displayWidth && screenshot.height == afterCompositor.displayHeight) {
                "Compositor dimensions do not match the actual display"
            }
            receipt.put("afterCompositorCopy", afterCompositor.json)
            receipt.getJSONObject("compositorCopy").put("image", savePng(root, "compositor.png", screenshot))
            stage = "complete"
            receipt.put("captureCompleted", true).put("status", "captured")
        } catch (failure: Throwable) {
            receipt.put("status", "failed").put("failureClass", failure.javaClass.name)
                .put("failureCauseClass", failure.cause?.javaClass?.name)
            // Do not export arbitrary exception messages, tokens, or App data into the receipt.
            throw failure
        } finally {
            val pending = activeTask?.isDone == false
            val mainPending = mainWorkOutstanding.get() != 0 || mainWorkTeardownRequired.get()
            val nativeCopyPending = copyPending && pending
            receipt.optJSONObject("surfaceCopy")?.put("startNanos", copyStart.get())
                ?.put("requestReturnedNanos", copyReturn.get())?.put("callbackNanos", copyCallback.get())
                ?.put("result", copyStatus.get())
            receipt.optJSONObject("compositorCopy")?.put("startNanos", compositorStart.get())
                ?.put("endNanos", compositorEnd.get())
            receipt.put("stage", stage).put("finishedElapsedRealtimeNanos", now())
                .put("workerStillPending", pending).put("pixelCopyStillPending", nativeCopyPending)
                .put("resourceCleanup", if (pending || mainPending) "pending-runner-process-teardown" else "complete")
                .put("uiAutomationTeardownOwner", "Instrumentation/AndroidJUnitRunner")
            try {
                if (!nativeCopyPending) {
                    surfaceBitmap?.recycle()
                    if (callbacks.isAlive) {
                        callbacks.quitSafely()
                        callbacks.join(1_000)
                        if (callbacks.isAlive) receipt.put("resourceCleanup", "callback-thread-still-alive")
                    }
                }
                compositorBitmap?.recycle()
                observedHolder?.let { holder -> onMain { holder.removeCallback(surfaceObserver) } }
                // shutdown does not interrupt/cancel a native operation. Runner/host own process teardown.
                worker.shutdown()
                if (!pending && !worker.awaitTermination(1, TimeUnit.SECONDS)) {
                    receipt.put("resourceCleanup", "worker-thread-still-alive")
                }
            } catch (_: InterruptedException) {
                receipt.put("resourceCleanup", "interrupted-pending-runner-process-teardown")
                worker.shutdown()
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                receipt.put("resourceCleanup", "cleanup-failed").put("cleanupFailureClass", failure.javaClass.name)
                worker.shutdown()
            } finally {
                val mainOutstanding = mainWorkOutstanding.get()
                val mainTeardown = mainWorkTeardownRequired.get()
                receipt.put("mainWorkOutstanding", mainOutstanding).put("mainWorkTeardownRequired", mainTeardown)
                    .put("mainWorkStillPending", mainOutstanding != 0 || mainTeardown)
                if (mainOutstanding != 0 || mainTeardown) {
                    receipt.put("resourceCleanup", "pending-runner-process-teardown")
                }
                val cleanupFailed = receipt.getString("resourceCleanup") != "complete"
                val captured = receipt.getString("status") == "captured"
                if (captured && cleanupFailed) {
                    receipt.put("status", "failed").put("failureClass", IllegalStateException::class.java.name)
                }
                saveReceipt()
                if (captured && cleanupFailed) error("Diagnostic resource cleanup incomplete")
            }
        }
    }

    private fun originalIdentity(): JSONObject {
        check(argument("originalAppApkSha256") == ORIGINAL_APK)
        check(argument("originalAppSignerSha256") == ORIGINAL_SIGNER)
        check(argument("originalAppVersionCode") == "28")
        return installedIdentity(TARGET, ORIGINAL_APK, ORIGINAL_SIGNER, 28)
            .put("source", "2a38627ca1c224f6ebf71c3e42052912699decdb")
            .put("buildRun", "41779045595").put("buildAttempt", "1")
            .put("provenance", "fixed original identity; host must retain actual archive/API binding")
    }

    private fun harnessIdentity(): JSONObject {
        val signer = hashArgument("harnessSignerSha256")
        check(signer == ORIGINAL_SIGNER) { "Harness must retain the independent Lite signer" }
        return installedIdentity(HARNESS, hashArgument("harnessApkSha256"), signer, null)
            .put("source", sourceArgument("harnessSourceSha"))
            .put("buildRun", numberArgument("harnessBuildRun"))
            .put("buildAttempt", numberArgument("harnessBuildAttempt"))
    }

    private fun installedIdentity(name: String, expectedApk: String, expectedSigner: String, code: Long?): JSONObject {
        val manager = context.packageManager
        // The runtime harness Context identifies its real APK without target-app package-visibility assumptions.
        val harnessApp = instrumentation.context.applicationInfo
        val info = if (name == TARGET) manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            else requireNotNull(manager.getPackageArchiveInfo(harnessApp.sourceDir, PackageManager.GET_SIGNING_CERTIFICATES)) {
                "Cannot parse the runtime harness APK"
            }
        val app = if (name == TARGET) requireNotNull(info.applicationInfo) else harnessApp
        check(info.packageName == name && app.packageName == name) { "Runtime package identity mismatch" }
        check(app.splitSourceDirs.isNullOrEmpty()) { "Split APK identity is outside this diagnostic" }
        val signers = requireNotNull(requireNotNull(info.signingInfo).apkContentsSigners)
        check(signers.size == 1) { "Exactly one current signer is required" }
        val signer = digest(signers.single().toByteArray())
        val apk = fileDigest(File(app.sourceDir))
        check(apk == expectedApk && signer == expectedSigner) { "Installed APK or signer mismatch" }
        if (code != null) check(info.longVersionCode == code) { "Original versionCode mismatch" }
        return JSONObject().put("package", name).put("apkSha256", apk).put("signerSha256", signer)
            .put("versionCode", info.longVersionCode).put("versionName", info.versionName)
    }

    private data class Snapshot(val view: SurfaceView, val surface: Surface, val lifecycleEpoch: Long,
        val width: Int, val height: Int, val displayWidth: Int, val displayHeight: Int,
        val rotation: Int, val json: JSONObject)

    @Suppress("DEPRECATION")
    private fun snapshot(activity: Activity, lifecycleEpoch: Long): Snapshot {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
            "Activity is not the live focused window"
        }
        val decor = activity.window.decorView
        check(decor.windowToken != null) { "Window has no token" }
        val candidates = mutableListOf<SurfaceView>()
        fun visit(view: View) {
            if (view is SurfaceView && view.isAttachedToWindow && view.isShown) candidates += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(decor)
        check(candidates.size == 1) { "Require exactly one visible attached SurfaceView" }
        val view = candidates.single()
        val frame = Rect(view.holder.surfaceFrame)
        val surface = view.holder.surface
        val visible = Rect()
        check(surface.isValid && frame.width() > 0 && frame.height() > 0 && view.width > 0 && view.height > 0 &&
            view.getGlobalVisibleRect(visible) && !visible.isEmpty) { "Surface is not valid and visible" }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val display = requireNotNull(activity.display)
        val displaySize = Point().also(display::getRealSize)
        val json = JSONObject().put("elapsedRealtimeNanos", now()).put("pid", Process.myPid())
            .put("activity", activity.componentName.flattenToString())
            .put("activityIdentity", System.identityHashCode(activity))
            .put("windowIdentity", System.identityHashCode(activity.window))
            .put("windowTokenIdentity", System.identityHashCode(decor.windowToken))
            .put("windowFlags", activity.window.attributes.flags).put("hasWindowFocus", activity.hasWindowFocus())
            .put("viewClass", view.javaClass.name).put("viewIdentity", System.identityHashCode(view))
            .put("surfaceIdentity", System.identityHashCode(surface)).put("surfaceLifecycleEpoch", lifecycleEpoch)
            .put("generationMethod", "public SurfaceHolder.Callback observed epoch since registration; not native generation")
            .put("surfaceFrame", rect(frame)).put("bufferWidth", frame.width()).put("bufferHeight", frame.height())
            .put("viewWidth", view.width).put("viewHeight", view.height)
            .put("screenX", location[0]).put("screenY", location[1]).put("globalVisibleRect", rect(visible))
            .put("displayWidth", displaySize.x).put("displayHeight", displaySize.y).put("rotation", display.rotation)
            .put("locales", context.resources.configuration.locales.toLanguageTags())
        return Snapshot(view, surface, lifecycleEpoch, frame.width(), frame.height(),
            displaySize.x, displaySize.y, display.rotation, json)
    }

    private fun checkSame(before: Snapshot, after: Snapshot) {
        check(before.view === after.view && before.surface === after.surface &&
            before.lifecycleEpoch == after.lifecycleEpoch && before.width == after.width && before.height == after.height &&
            before.rotation == after.rotation && before.displayWidth == after.displayWidth &&
            before.displayHeight == after.displayHeight) { "Activity Surface or display changed during capture" }
        for (key in listOf("activityIdentity", "windowIdentity", "windowTokenIdentity", "screenX", "screenY",
            "viewWidth", "viewHeight", "globalVisibleRect")) {
            check(before.json.get(key).toString() == after.json.get(key).toString()) { "Window or View geometry changed" }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val handler = Handler(Looper.getMainLooper())
        // State guards dispatch, not FutureTask.isDone: cancel(false) cannot stop an already running body.
        val state = AtomicInteger(0) // queued=0, running=1, finished=2, cancelled-before-start=3
        mainWorkOutstanding.incrementAndGet()
        val task = FutureTask(Callable {
            try {
                block()
            } finally {
                state.set(2)
                mainWorkOutstanding.decrementAndGet()
            }
        })
        val dispatch = Runnable {
            if (state.compareAndSet(0, 1)) task.run()
        }
        fun stopWaiting() {
            if (state.compareAndSet(0, 3)) {
                handler.removeCallbacks(dispatch)
                task.cancel(false)
                mainWorkOutstanding.decrementAndGet()
            } else {
                // It may already have registered the observer. Do not claim that process-local cleanup is complete.
                mainWorkTeardownRequired.set(true)
            }
        }
        if (!handler.post(dispatch)) {
            stopWaiting()
            error("Main Looper rejected snapshot")
        }
        return try {
            task.get(3, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            stopWaiting()
            throw failure
        } catch (failure: InterruptedException) {
            stopWaiting()
            throw failure
        }
    }

    private fun savePng(root: File, name: String, bitmap: Bitmap): JSONObject {
        val file = File(root, name)
        check(file.createNewFile()) { "Capture output must be new" }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
        check(file.length() > 0) { "PNG is empty" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        check(options.outWidth == bitmap.width && options.outHeight == bitmap.height && options.outMimeType == "image/png") {
            "Encoded PNG dimensions or format mismatch"
        }
        return JSONObject().put("file", name).put("bytes", file.length()).put("sha256", fileDigest(file))
            .put("width", bitmap.width).put("height", bitmap.height).put("config", bitmap.config?.name)
            .put("encoding", "lossless PNG; no crop, resize or nonblack assertion")
    }

    private fun argument(name: String): String = requireNotNull(arguments.getString(name)) { "Missing argument: $name" }
    private fun hashArgument(name: String): String = argument(name).also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA256 argument: $name" }
    }
    private fun sourceArgument(name: String): String = argument(name).also {
        require(it.matches(Regex("[0-9a-f]{40}"))) { "Invalid source SHA argument: $name" }
    }
    private fun numberArgument(name: String): String = argument(name).also {
        require(it.matches(Regex("[1-9][0-9]*"))) { "Invalid positive identity argument: $name" }
    }
    private fun rect(value: Rect): JSONArray = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun now(): Long = SystemClock.elapsedRealtimeNanos()
    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65_536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val TARGET = "dev.aerove.lite"
        const val HARNESS = "dev.aerove.lite.test"
        const val LAUNCHER = "dev.aerove.lite.FlutterMainActivity"
        const val ORIGINAL_APK = "e0e4ddb3cf4868377a903c7c031ee6dcc5715a1e303d303a012bcb8a366f26f1"
        const val ORIGINAL_SIGNER = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
    }
}
