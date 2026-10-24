package dev.aerove.lite.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.test.platform.graphics.HardwareRendererCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue

/** One capture's diagnostic owner; never changes drawing, animation, or acceptance settings. */
internal class FrameCommitObservation(private val label: String, private val directory: File) : Closeable {
    private val id = nextId.incrementAndGet()
    private val main = Handler(Looper.getMainLooper())
    private val metricsThread = HandlerThread("capture-frame-$id").apply { start() }
    private val metricsHandler = Handler(metricsThread.looper)
    private val committed = CountDownLatch(1)
    private val commitAt = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val drawSeen = AtomicBoolean(false)
    private val events = ArrayList<JSONObject>()
    private var droppedEvents = 0
    private var metricsEvents = 0
    private val cleanupErrors = ConcurrentLinkedQueue<String>()
    private var view: View? = null
    private var window: Window? = null
    private var observer: ViewTreeObserver? = null
    private var metricsRegistered = false
    private var drawRegistered = false
    private var commitRegistered = false
    private var deadlinePassed = false
    @Volatile private var cleanupComplete = false

    private val commitCallback = Runnable {
        if (!closed.get()) {
            commitAt.compareAndSet(0, System.nanoTime())
            record("commitCallback")
            committed.countDown()
        }
    }
    private val removeDraw = Runnable { removeDrawOnMain() }
    private val drawListener = ViewTreeObserver.OnDrawListener {
        if (!closed.get() && drawSeen.compareAndSet(false, true)) {
            record("onDraw")
            // ViewTreeObserver forbids removing this listener inside dispatchOnDraw.
            main.post(removeDraw)
        }
    }
    private val metricsListener = Window.OnFrameMetricsAvailableListener { reportedWindow, frame, dropped ->
        if (!closed.get()) {
            record("frameMetrics", JSONObject()
                .put("windowIdentity", System.identityHashCode(reportedWindow))
                .put("sameWindow", reportedWindow === window)
                .put("droppedReports", dropped)
                .put("intendedVsyncNanos", frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                .put("vsyncNanos", frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .put("firstDraw", frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME))
                .put("totalNanos", frame.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("drawNanos", frame.getMetric(FrameMetrics.DRAW_DURATION))
                .put("syncNanos", frame.getMetric(FrameMetrics.SYNC_DURATION))
                .put("swapNanos", frame.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
                .put("gpuNanos", frame.getMetric(FrameMetrics.GPU_DURATION)))
        }
    }

    private fun record(event: String, values: JSONObject = JSONObject()) {
        synchronized(events) {
            if (closed.get()) return
            if (event == "frameMetrics") {
                if (metricsEvents == 32) { droppedEvents++; return }
                metricsEvents++
            }
            if (events.size == 64) { droppedEvents++; return }
            events += values.put("event", event).put("nanoTime", System.nanoTime())
                .put("thread", Thread.currentThread().name)
        }
    }

    fun registerAndInvalidate(target: View, targetWindow: Window) {
        check(Looper.myLooper() == Looper.getMainLooper())
        view = target
        window = targetWindow
        observer = target.viewTreeObserver
        record("registerStart", JSONObject()
            .put("viewIdentity", System.identityHashCode(target))
            .put("windowIdentity", System.identityHashCode(targetWindow))
            .put("drawingEnabled", HardwareRendererCompat.isDrawingEnabled())
            .put("attached", target.isAttachedToWindow).put("observerAlive", observer!!.isAlive))
        targetWindow.addOnFrameMetricsAvailableListener(metricsListener, metricsHandler)
        metricsRegistered = true
        observer!!.addOnDrawListener(drawListener)
        drawRegistered = true
        observer!!.registerFrameCommitCallback(commitCallback)
        commitRegistered = true
        record("registered")
        target.invalidate()
        record("invalidateReturned")
    }

    fun awaitOriginalDeadline(): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Never wait for a UI callback on UI" }
        val started = System.nanoTime()
        record("awaitStart", JSONObject().put("deadlineNanos", started + TimeUnit.SECONDS.toNanos(5)))
        val signalled = committed.await(5, TimeUnit.SECONDS)
        val withinDeadline = signalled && commitAt.get() - started <= TimeUnit.SECONDS.toNanos(5)
        deadlinePassed = withinDeadline
        record("awaitReturned", JSONObject().put("passedOriginalDeadline", withinDeadline))
        if (!withinDeadline) {
            // This failure is final. A late callback is evidence only and cannot change the result.
            record("originalDeadlineFailed", JSONObject().put("uiThreadStack",
                Looper.getMainLooper().thread.stackTrace.joinToString("\n")))
            val late = committed.await(1, TimeUnit.SECONDS)
            record("diagnosticTailEnded", JSONObject().put("callbackArrived", late))
        }
        return withinDeadline
    }

    private fun observers(): List<ViewTreeObserver> = listOfNotNull(observer, view?.viewTreeObserver).distinct()

    private fun removeDrawOnMain() {
        if (drawRegistered) {
            observers().filter { it.isAlive }.forEach { it.removeOnDrawListener(drawListener) }
            drawRegistered = false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val removed = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                main.removeCallbacks(removeDraw)
                cleanupAttempt("onDraw") { removeDrawOnMain() }
                if (commitRegistered) {
                    cleanupAttempt("commitCallback") {
                        observers().filter { it.isAlive }.forEach { it.unregisterFrameCommitCallback(commitCallback) }
                    }
                    commitRegistered = false
                }
                if (metricsRegistered) {
                    cleanupAttempt("frameMetrics") { window!!.removeOnFrameMetricsAvailableListener(metricsListener) }
                    metricsRegistered = false
                }
                cleanupComplete = true
            } finally { removed.countDown() }
        }
        var interrupted = false
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
            else {
                // Bounded cleanup cannot wait forever behind the UI work being diagnosed.
                main.postAtFrontOfQueue(cleanup)
                try { removed.await(1, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            metricsThread.quitSafely()
            if (Thread.currentThread() !== metricsThread && Looper.myLooper() != Looper.getMainLooper()) {
                try { metricsThread.join(1_000) } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val uiRemoved = cleanupComplete
        val threadAlive = metricsThread.isAlive
        val errors = cleanupErrors.toList()
        val trace = synchronized(events) {
            JSONObject().put("schema", 1).put("capture", label).put("captureId", id).put("clock", "System.nanoTime")
                .put("originalDeadlineSeconds", 5).put("failureDiagnosticTailSeconds", 1)
                .put("passedOriginalDeadline", deadlinePassed).put("events", JSONArray(events.toList()))
                .put("droppedEvents", droppedEvents).put("uiCleanupComplete", uiRemoved)
                .put("cleanupErrors", JSONArray(errors))
                .put("metricsThreadAliveAfterJoin", threadAlive)
                .put("performanceEvidence", false)
        }
        File(directory, "$label-frame-commit-$id.json").writeText(trace.toString(2))
        check(uiRemoved && errors.isEmpty() && !threadAlive) {
            "Frame observation owner cleanup was not confirmed; see its diagnostic JSON"
        }
    }

    private fun cleanupAttempt(owner: String, action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            cleanupErrors += "$owner: ${failure.javaClass.name}: ${failure.message}"
        }
    }

    private companion object { val nextId = AtomicLong(0) }
}
