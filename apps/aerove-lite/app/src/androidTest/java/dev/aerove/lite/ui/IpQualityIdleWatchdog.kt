package dev.aerove.lite.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Wall-clock owner independent of Espresso, Compose dispatchers and the UI looper. */
internal class CaseDeadline(private val millis: Long, private val expire: () -> Unit) : Closeable {
    private val state = AtomicInteger(0) // active, completed, timed out
    private val completed = CountDownLatch(1)
    private val started = System.nanoTime()
    private val worker = Thread({
        val remaining = TimeUnit.MILLISECONDS.toNanos(millis) - (System.nanoTime() - started)
        if ((remaining <= 0 || !completed.await(remaining, TimeUnit.NANOSECONDS)) && state.compareAndSet(0, 2)) expire()
    }, "ip-quality-case-deadline").apply { isDaemon = true; start() }
    val timedOut: Boolean get() = state.get() == 2
    val workerAlive: Boolean get() = worker.isAlive

    override fun close() {
        if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(millis) && state.compareAndSet(0, 2)) expire()
        if (state.compareAndSet(0, 1)) completed.countDown()
        if (Thread.currentThread() !== worker) worker.join(1_000)
        check(!worker.isAlive) { "Case deadline owner did not join" }
        check(!timedOut) { "WATCHDOG_FAIL: original 120-second case deadline exceeded" }
    }
}

/** Only the named QA83 case is bounded; no production animation or idle policy is changed. */
internal class IpQualityIdleWatchdog : TestRule {
    private val phase = AtomicReference("rule.not-entered")
    private val lifecycle = AtomicReference("not observed")
    private val calls = AtomicReference<() -> Int>({ -1 })
    private val events = ArrayList<JSONObject>()
    fun observeCalls(value: () -> Int) { calls.set(value) }
    fun mark(value: String) {
        phase.set(value)
        synchronized(events) {
            if (events.size < 64) events += JSONObject().put("phase", value).put("nanoTime", System.nanoTime())
                .put("lookupCalls", calls.get().invoke())
        }
        Log.i(TAG, "milestone=$value calls=${calls.get().invoke()}")
    }

    override fun apply(base: Statement, description: Description): Statement {
        if (description.methodName != CASE) return base
        return object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                val app = context.applicationContext as Application
                val pid = Process.myPid()
                val callback = object : Application.ActivityLifecycleCallbacks {
                    private fun seen(activity: Activity, state: String) {
                        lifecycle.set("${activity.javaClass.name}@${System.identityHashCode(activity)}:$state")
                    }
                    override fun onActivityCreated(a: Activity, b: Bundle?) = seen(a, "created")
                    override fun onActivityStarted(a: Activity) = seen(a, "started")
                    override fun onActivityResumed(a: Activity) = seen(a, "resumed")
                    override fun onActivityPaused(a: Activity) = seen(a, "paused")
                    override fun onActivityStopped(a: Activity) = seen(a, "stopped")
                    override fun onActivityDestroyed(a: Activity) = seen(a, "destroyed")
                    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
                }
                app.registerActivityLifecycleCallbacks(callback)
                try {
                    mark("rule.enter-before-compose")
                    CaseDeadline(120_000) {
                        try {
                        val output = File(requireNotNull(context.getExternalFilesDir(null)), "captures/ip-quality-idle")
                        val timeout = JSONObject().put("outcome", "WATCHDOG_FAIL")
                            .put("case", CASE).put("pid", pid).put("targetPackage", context.packageName)
                            .put("deadlineMillis", 120_000).put("phase", phase.get())
                            .put("lifecycle", lifecycle.get()).put("lookupCalls", calls.get().invoke())
                            .put("nanoTime", System.nanoTime()).put("performanceEvidence", false)
                            .put("appSource", InstrumentationRegistry.getArguments().getString("sourceRevision", "not supplied"))
                            .put("testSource", InstrumentationRegistry.getArguments().getString("testSourceRevision", "not supplied"))
                        timeout.put("milestones", synchronized(events) { JSONArray(events.toList()) })
                        val threads = JSONArray()
                        Thread.getAllStackTraces().forEach { (thread, stack) ->
                            threads.put(JSONObject().put("thread", thread.name).put("state", thread.state.name)
                                .put("stack", stack.joinToString("\n")))
                        }
                        timeout.put("threads", threads)
                            check(output.mkdirs() || output.isDirectory)
                            FileOutputStream(File(output, "watchdog-fail.json")).use { stream ->
                                stream.write(timeout.toString(2).toByteArray(Charsets.UTF_8))
                                stream.flush()
                                stream.fd.sync()
                            }
                        } catch (failure: Exception) {
                            Log.e(TAG, "WATCHDOG_FAIL evidence write failed", failure)
                        } finally {
                            Log.e(TAG, "WATCHDOG_FAIL pid=$pid case=$CASE phase=${phase.get()}; intentional runner termination")
                            // Prefer runner finish; a blocked UiAutomation/Binder cannot consume the whole CI job.
                            Thread({
                                CountDownLatch(1).await(3, TimeUnit.SECONDS)
                                Log.e(TAG, "WATCHDOG_FAIL own-PID fallback=$pid")
                                Process.killProcess(pid)
                            }, "ip-quality-timeout-fallback").apply { isDaemon = true; start() }
                            instrumentation.finish(Activity.RESULT_CANCELED,
                                Bundle().apply { putString("shortMsg", "WATCHDOG_FAIL: $CASE at ${phase.get()}") })
                        }
                    }.use {
                        base.evaluate()
                        mark("rule.base-returned-after-teardown")
                    }
                } finally { app.unregisterActivityLifecycleCallbacks(callback) }
            }
        }
    }

    private companion object {
        const val CASE = "actualDashboardEntrySharesOwnerAndRendersSourceFailureRetryAndRecovery"
        const val TAG = "IpQualityIdleWatchdog"
    }
}
