package dev.aerove.lite.flutter

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.vpn.OwnedStartCancellation
import dev.aerove.lite.vpn.VpnSnapshot
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Flutter owns its attachment; the existing Service remains the sole VPN/runtime owner. */
object MeowFlutterHost {
    fun attach(activity: FlutterActivity, engine: FlutterEngine) {
        check(activity.application is LiteApplication) { "Meow host requires the Lite application" }
        if (!engine.plugins.has(MeowAppPlugin::class.java)) engine.plugins.add(MeowAppPlugin())
        if (!engine.plugins.has(MeowServiceBridge::class.java)) engine.plugins.add(MeowServiceBridge())
    }
}

private class MeowServiceBridge : FlutterPlugin, ActivityAware {
    private lateinit var graph: LiteApplication
    private lateinit var serviceChannel: MethodChannel
    private lateinit var coreChannel: MethodChannel
    private lateinit var scope: CoroutineScope
    private val main = Handler(Looper.getMainLooper())
    private val attachmentGate = Mutex()
    private val runtimeIntentGate = Mutex()
    private val listenerCommandGate = Mutex()
    private val replies = ConcurrentHashMap.newKeySet<Reply>()
    private val intent = AtomicLong()
    private val intentSignal = MutableStateFlow(0L)
    private var activity: ActivityPluginBinding? = null
    @Volatile private var attached = false
    @Volatile private var current: MeowEngineAttachment? = null
    private var retiring: MeowEngineAttachment? = null
    private val permissionLedger = MeowPermissionLedger<PermissionWait>()
    @Volatile private var permission: MeowPermissionEntry<PermissionWait>? = null
    @Volatile private var startIntent: StartIntent? = null
    private val commands by lazy {
        createMeowFlutterCommandFacade(graph,
            canPrepare = { attached && current != null && !graph.maintenanceActive },
            canConnect = { attached && current != null && !graph.maintenanceActive })
    }

    private data class PermissionWait(val attempt: MeowFlutterPermissionStart, val revision: Long,
        val completed: CompletableDeferred<Boolean>, val dispatched: AtomicBoolean)
    private data class StartIntent(val token: Long, val wireRevision: Int, val owner: Long, val generation: Long,
        val adopted: Boolean = false)

    private val activityResult = PluginRegistry.ActivityResultListener { code, resultCode, _ ->
        val resolved = permissionLedger.resolve(code) ?: return@ActivityResultListener false
        if (resolved.cancelled || permission !== resolved.entry) return@ActivityResultListener true
        val waiting = resolved.entry.value
        permission = null
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val granted = resultCode == Activity.RESULT_OK && waiting.revision == intent.get() &&
                    attached && VpnService.prepare(graph) == null
                val sent = commands.completePermissionConnect(waiting.attempt, granted) is MeowFlutterPermissionResult.Sent
                if (sent) waiting.dispatched.set(true)
                waiting.completed.complete(sent)
            } catch (failure: Throwable) { waiting.completed.completeExceptionally(failure) }
        }
        true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        check(!attached)
        graph = binding.applicationContext as? LiteApplication ?: error("Meow host has the wrong application")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceChannel = MethodChannel(binding.binaryMessenger, "com.follow.clash/service")
        coreChannel = MethodChannel(binding.binaryMessenger, "com.follow.clash/core")
        attached = true
        serviceChannel.setMethodCallHandler(::serviceCall)
        coreChannel.setMethodCallHandler(::coreCall)
        scope.launch {
            combine(graph.vpn, graph.flutterRuntimeIdentity) { snapshot, _ -> snapshot }.collectLatest { snapshot ->
                val observedIntent = startIntent
                try { current?.updateRuntime(snapshot) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("MeowFlutter", "Runtime observation could not attach", failure) }
                onMain {
                    val followsStart = observedIntent != null && (snapshot.serviceOwner == observedIntent.owner &&
                        (snapshot.sessionGeneration > observedIntent.generation || observedIntent.adopted && snapshot.sessionGeneration == observedIntent.generation) ||
                        snapshot.serviceOwner > observedIntent.owner && snapshot.sessionGeneration > 0)
                    if (attached && observedIntent != null && startIntent === observedIntent && intent.get() == observedIntent.token &&
                        graph.vpn.value === snapshot && followsStart &&
                        snapshot.endpoint == null && snapshot.phase in setOf(VpnPhase.Stopped, VpnPhase.Failed)) {
                        startIntent = null
                        serviceChannel.invokeMethod("stopped", observedIntent.wireRevision)
                    }
                }
            }
        }
    }

    private fun serviceCall(call: MethodCall, result: MethodChannel.Result) {
        val reply = Reply(result).also(replies::add)
        if (!attached) return reply.error("detached", "Meow engine is detached")
        val revision = when (call.method) {
            "start", "park", "stop" -> {
                val number = call.arguments as? Int
                    ?: return reply.error("bad_argument", "Service command revision must be an integer")
                newIntent().also { token ->
                    val baseline = graph.vpn.value
                    startIntent = if (call.method == "stop") null else StartIntent(token, number, baseline.serviceOwner, baseline.sessionGeneration)
                }
            }
            "shutdown" -> newIntent()
            else -> intent.get()
        }
        run(reply) {
            when (call.method) {
                "init" -> attachmentGate.withLock {
                    retiring?.let {
                        if (!it.closeAndJoin()) throw MeowProtocolFailure("termination_unconfirmed", "Previous Meow attachment has not joined")
                        retiring = null
                    }
                    if (current == null) {
                        MeowNativeCore.readCapabilities()
                        current = MeowEngineAttachment(graph.filesDir, graph.runtimeHandoffs, graph.vpn, scope,
                            JniResourceFetch.preparedValidator(graph), { MeowNativeCore.readCapabilities(); Unit }, ::events,
                            { failure -> android.util.Log.w("MeowFlutter", "UI telemetry failed for the owned runtime", failure) },
                            object : MeowFlutterRuntime {
                                override fun identityIsCurrent(identity: RuntimeAppliedIdentity) = graph.flutterRuntimeIdentityIsCurrent(identity)
                                override suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean) = graph.setFlutterRuntimeListening(expected, enabled)
                                override suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection) = graph.applyFlutterRuntimeUpdate(expected, projection)
                                override suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String) = graph.refreshFlutterRuntimeSetup(expected, setupParamsJson)
                                override suspend fun defaultTestUrl(expected: MeowSetupRuntime) = graph.flutterRuntimeDefaultTestUrl(expected)
                            }, resourceSealer = graph.flutterResourceSealer)
                    }
                    ""
                }
                "shutdown" -> {
                    cancelPermission(revision)
                    detachAttachment(revision)
                }
                "syncState" -> requireAttachment().setup.syncState(meowObject(call.arguments))
                "getRunTime" -> requireAttachment().runTime()
                "setUiTelemetryActive" -> {
                    requireAttachment().setUiActive(call.arguments as? Boolean
                        ?: throw MeowProtocolFailure("bad_argument", "UI telemetry state must be a boolean"))
                    null
                }
                "start" -> start(revision)
                "stop" -> {
                    cancelPermission(revision)
                    runtimeIntentGate.withLock {
                        if (revision != intent.get()) throw MeowProtocolFailure("superseded", "Stop intent was superseded")
                        graph.runtimeHandoffs.readCurrent()?.let { graph.runtimeHandoffs.invalidate(it.intentEpoch) }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (revision != intent.get()) throw MeowProtocolFailure("superseded", "Stop intent was superseded before dispatch")
                        commands.disconnectAndJoin() is MeowFlutterStopResult.Confirmed
                    }
                }
                "park" -> listenerCommandGate.withLock {
                    if (!attached || revision != intent.get()) false
                    else {
                        val owner = requireAttachment()
                        owner.setListening(false)
                        markAdopted(owner, revision)
                    }
                }
                else -> throw MeowProtocolFailure("bad_method", "Unknown Meow service method")
            }
        }
    }

    private fun coreCall(call: MethodCall, result: MethodChannel.Result) {
        val reply = Reply(result).also(replies::add)
        if (!attached) return reply.error("detached", "Meow engine is detached")
        run(reply) {
            val owner = requireAttachment()
            when (call.method) {
                "invokeMethod" -> owner.dispatcher.invoke(call.arguments as? String
                    ?: throw MeowProtocolFailure("bad_argument", "Core request must be JSON text"))
                "cancel" -> {
                    val id = call.arguments as? String
                        ?: throw MeowProtocolFailure("bad_argument", "Cancellation requires a request id")
                    if (!owner.dispatcher.cancel(id)) throw MeowProtocolFailure("termination_unconfirmed", "Core request cancellation did not join")
                    null
                }
                else -> throw MeowProtocolFailure("bad_method", "Unknown Meow core channel method")
            }
        }
    }

    private fun requireAttachment(): MeowEngineAttachment = current
        ?: throw MeowProtocolFailure("not_initialized", "Meow service attachment is not initialized")

    private suspend fun start(revision: Long): Boolean {
        val owner = requireAttachment()
        if (!attached || revision != intent.get()) return false
        cancelPermission(revision)
        val adopted = listenerCommandGate.withLock {
            val existing = existingRuntime(owner, revision)
            if (existing == null) false else {
                if (!graph.vpn.value.listening) owner.setListening(true)
                markAdopted(owner, revision)
            }
        }
        if (adopted) return true
        val requested = runtimeIntentGate.withLock {
            if (!attached || revision != intent.get() || owner !== current) return false
            val staged = owner.setup.staged()
                ?: throw MeowProtocolFailure("configuration_missing", "No validated Flutter runtime snapshot is staged")
            graph.runtimeHandoffs.beginStart(staged.revision, staged.intentEpoch)
        }
        val dispatched = AtomicBoolean(false)
        try { return authorizeStart(owner, requested, revision, dispatched) }
        finally {
            if (!dispatched.get()) withContext(NonCancellable) {
                runtimeIntentGate.withLock {
                    val stillRequested = graph.runtimeHandoffs.readCurrent()
                    if (stillRequested?.revision == requested.revision && stillRequested.intentEpoch == requested.intentEpoch && stillRequested.userRequested) {
                        try { graph.runtimeHandoffs.invalidate(requested.intentEpoch) }
                        catch (superseded: IllegalStateException) { if (superseded.message != "runtime_stop_superseded") throw superseded }
                    }
                }
            }
        }
    }

    private suspend fun existingRuntime(owner: MeowEngineAttachment, revision: Long): MeowSetupRuntime? {
        if (!attached || owner !== current || revision != intent.get()) return null
        owner.readRuntime()?.let { return it }
        val starting = graph.vpn.value.phase == VpnPhase.Starting && graph.runtimeHandoffs.readCurrent()?.userRequested == true
        if ((graph.vpn.value.phase == VpnPhase.Running || starting) && graph.runtimeHandoffs.hasAuthority) {
            val settled = withTimeoutOrNull(45_000) {
                combine(graph.vpn, graph.flutterRuntimeIdentity, intentSignal) { snapshot, identity, _ -> snapshot to identity }
                    .first { (snapshot, identity) -> !attached || revision != intent.get() ||
                        snapshot.phase !in setOf(VpnPhase.Starting, VpnPhase.Running) || identity != null }
            }
            if (settled == null) {
                if (!attached || owner !== current || revision != intent.get()) return null
                throw MeowProtocolFailure("termination_unconfirmed", "The existing Meow service transition has not settled")
            }
        }
        if (!attached || owner !== current || revision != intent.get()) return null
        return owner.readRuntime()
    }

    private suspend fun markAdopted(owner: MeowEngineAttachment, revision: Long): Boolean {
        val existing = owner.readRuntime() ?: return false
        return withContext(Dispatchers.Main.immediate) {
            val actual = graph.vpn.value
            if (!attached || owner !== current || revision != intent.get() ||
                actual.serviceOwner != existing.serviceOwner || actual.sessionGeneration != existing.sessionGeneration ||
                !graph.runtimeHandoffs.isCurrent(existing.revision, existing.intentEpoch)) false
            else { startIntent = startIntent?.takeIf { it.token == revision }?.copy(adopted = true); true }
        }
    }

    private suspend fun authorizeStart(owner: MeowEngineAttachment, requested: MeowRuntimeSnapshot,
        revision: Long, dispatched: AtomicBoolean): Boolean {
        if (!attached || revision != intent.get() || owner !== current) return false
        val binding = MeowFlutterRuntimeBinding(requested.revision, requested.intentEpoch)
        val vpnRequired = JSONObject(requested.handoff.vpnOptionsJson).getBoolean("enable")
        val prepare = withContext(Dispatchers.Main.immediate) {
            if (!vpnRequired) null else {
                val host = activity?.activity
                    ?: throw MeowProtocolFailure("activity_unavailable", "VPN authorization requires an attached Activity")
                VpnService.prepare(host)
            }
        }
        if (prepare == null) {
            return withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (!attached || revision != intent.get() || owner !== current) return@withContext false
                val request = commands.prepareOwnedConnect(binding) ?: return@withContext false
                commands.connectOwned(request).also { if (it) dispatched.set(true) }
            }
        }
        val attempt = withContext(Dispatchers.Main.immediate) {
            if (!attached || revision != intent.get() || owner !== current) null
            else commands.preparePermissionConnect(binding)
        } ?: return false
        val waiting = PermissionWait(attempt, revision, CompletableDeferred(), dispatched)
        var receipt: MeowPermissionEntry<PermissionWait>? = null
        var launched = false
        try {
            withContext(Dispatchers.Main.immediate) {
                if (!attached || revision != intent.get() || current !== owner)
                    throw CancellationException("VPN authorization was superseded")
                check(permission == null) { "A VPN authorization request is already active" }
                val issued = permissionLedger.issue(waiting)
                receipt = issued
                permission = issued
                checkNotNull(activity?.activity) { "VPN Activity detached before authorization" }
                    .startActivityForResult(prepare, issued.requestCode)
                launched = true
            }
            return waiting.completed.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                receipt?.let {
                    if (permission === it) permission = null
                    if (launched) permissionLedger.cancel(it) else permissionLedger.resolve(it.requestCode)
                }
            }
            if (!attempt.resolved.get()) {
                withContext(NonCancellable) {
                    val cancelled = commands.cancelPermissionConnect(attempt)
                    if (cancelled == OwnedStartCancellation.TerminationUnconfirmed)
                        throw MeowProtocolFailure("termination_unconfirmed", "Pending VPN authorization did not join")
                }
            }
        }
    }

    private suspend fun cancelPermission(expectedIntent: Long? = null) {
        val pending = withContext(Dispatchers.Main.immediate) {
            if (expectedIntent != null && expectedIntent != intent.get()) null
            else permission.also { it?.let(permissionLedger::cancel); permission = null }
        }?.value ?: return
        val outcome = commands.cancelPermissionConnect(pending.attempt)
        if (outcome == OwnedStartCancellation.TerminationUnconfirmed) {
            val failure = MeowProtocolFailure("termination_unconfirmed", "Pending VPN start did not join")
            pending.completed.completeExceptionally(failure)
            throw failure
        }
        pending.completed.complete(false)
    }

    private suspend fun detachAttachment(expectedIntent: Long? = null): Boolean = attachmentGate.withLock {
        if (expectedIntent != null && expectedIntent != intent.get()) return@withLock false
        val previous = retiring ?: current ?: return@withLock true
        current = null
        retiring = previous
        if (!previous.closeAndJoin()) return@withLock false
        retiring = null
        true
    }

    private fun events(owner: MeowEngineAttachment, rows: List<Map<String, Any?>>) {
        val data = JSONObject().put("method", "message").put("arguments", JSONArray(rows)).toString().toByteArray(Charsets.UTF_8)
        onMain { if (attached && current === owner) serviceChannel.invokeMethod("event", data) }
    }

    private fun run(reply: Reply, work: suspend () -> Any?) {
        scope.launch {
            try { reply.success(work()) }
            catch (failure: CancellationException) { reply.error("cancelled", "Meow attachment operation cancelled") }
            catch (failure: MeowProtocolFailure) { reply.error(failure.code, failure.message ?: failure.code, failure.details) }
            catch (failure: MeowConfigProtocolException) { reply.error(failure.code, failure.message ?: failure.code, failure.details) }
            catch (failure: Exception) { reply.error("method_failed", failure.message ?: failure.javaClass.simpleName) }
        }
    }

    private inner class Reply(private val result: MethodChannel.Result) {
        private val done = AtomicBoolean(false)
        fun success(value: Any?) = finish { result.success(value) }
        fun error(code: String, message: String, details: Any? = null) = finish { result.error(code, message, details) }
        private fun finish(action: () -> Unit) = onMain {
            if (done.compareAndSet(false, true)) { replies.remove(this); action() }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        unbindActivity()
        activity = binding
        binding.addActivityResultListener(activityResult)
    }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivityForConfigChanges() = unbindActivity()
    override fun onDetachedFromActivity() {
        unbindActivity()
        scope.launch { cancelPermission() }
    }
    private fun unbindActivity() {
        activity?.removeActivityResultListener(activityResult)
        activity = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (!attached) return
        attached = false
        newIntent()
        serviceChannel.setMethodCallHandler(null)
        coreChannel.setMethodCallHandler(null)
        unbindActivity()
        // Cleanup is independent of the cancelled engine scope; it never sends ACTION_STOP.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { cancelPermission(); detachAttachment() }
            finally {
                scope.cancel()
                replies.toList().forEach { it.error("detached", "Meow engine is detached") }
                coroutineContext.cancel()
            }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
    private fun newIntent(): Long = intent.incrementAndGet().also { intentSignal.value = it }
}
