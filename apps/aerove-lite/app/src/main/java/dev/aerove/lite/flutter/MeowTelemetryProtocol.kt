package dev.aerove.lite.flutter

import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.RouteSnapshot
import java.time.Instant
import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One endpoint/owner/epoch binding. Detaching this session never starts or stops a VPN. */
class MeowProtocolSession(private val client: CoreApiClient, val owner: String, val generation: Long, val coreEpoch: ULong,
    parentScope: CoroutineScope, isCurrent: () -> Boolean,
    emitBatch: (List<Map<String, Any?>>) -> Unit,
    onFailure: (Throwable) -> Unit,
    applied: MeowAppliedQueries? = null,
    statisticsOnlyProxy: Boolean? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Default)
    private val current = { !closed.get() && lifetime.isActive && isCurrent() }
    private val telemetry = MeowTelemetryProtocol(client, coreEpoch, scope, current, emitBatch, onFailure, statisticsOnlyProxy)
    private val queries = MeowQueryProtocol(client, telemetry, applied, coreEpoch, owner, generation)

    init { require(owner.isNotBlank() && generation >= 0 && coreEpoch > 0uL) }

    suspend fun invoke(method: String, arguments: Any?): Any? = coroutineScope {
        checkCurrent()
        val task = scope.async {
            checkCurrent()
            client.protocolErrors(::protocolHttpFailure) { queries.invoke(method, arguments) }
                .also { currentCoroutineContext().ensureActive(); checkCurrent() }
        }
        try { task.await() } finally { withContext(NonCancellable) { task.cancelAndJoin() } }
    }

    suspend fun setUiTelemetryActive(active: Boolean) { checkCurrent(); telemetry.setActive(active) }
    fun observationReceipt(): MeowObservationReceipt? = telemetry.observationReceipt()

    private fun checkCurrent() {
        if (!current()) throw MeowQueryException(if (closed.get()) "closed" else "stale_session", "Meow protocol session is no longer current")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) { telemetry.close(); lifetime.cancel() }
    }

    suspend fun closeAndJoin() { close(); lifetime.join() }
}

/** Retained native loss/watermark evidence; it is not an invented shared CoreEvent. */
data class MeowObservationReceipt(val sessionId: String, val generation: ULong,
    val activationCursor: ULong, val cursor: ULong, val dropped: ULong)

internal class MeowTelemetryProtocol(private val client: CoreApiClient, private val coreEpoch: ULong,
    private val scope: CoroutineScope, private val current: () -> Boolean,
    private val emitBatch: (List<Map<String, Any?>>) -> Unit,
    private val onFailure: (Throwable) -> Unit, private val statisticsOnlyProxy: Boolean?) : AutoCloseable {
    private val controls = Mutex()
    private val gate = Any()
    private var active = false
    private var closed = false
    private var activation = 0L
    private var logEnabled = false
    private var routeEnabled = false
    private var producer: Job? = null
    private var logger: Job? = null
    private var sample: Map<String, Any?>? = null
    private var receipt: MeowObservationReceipt? = null
    private var awaitingActivation = true
    private var lastRoute: Pair<ULong, ULong>? = null
    private var watchIntent = 0L
    private var routeRead: Deferred<RouteSnapshot>? = null
    private var routeRefresh: Deferred<RouteSnapshot>? = null
    private val loaded = mutableSetOf<Triple<String, String, ULong>>()
    private val retiredObservationSessions = mutableSetOf<String>()

    suspend fun setActive(value: Boolean) = controls.withLock {
        val jobs = synchronized(gate) {
            checkOpen()
            if (active == value) return@withLock
            active = value; activation++
            val previous = listOfNotNull(producer, logger)
            producer = null; logger = null
            previous.forEach(Job::cancel)
            previous
        }
        jobs.forEach { it.join() }
        if (value) {
            client.setTelemetryObservations(true)
            synchronized(gate) {
                checkOpen()
                awaitingActivation = true
                val lease = activation
                producer = launchOwned(lease) { client.protocolTelemetry().collect { frame -> consume(frame, lease) } }
                if (logEnabled) logger = launchLogs(lease)
            }
        } else client.setTelemetryObservations(false)
    }

    suspend fun setLogEnabled(value: Boolean) = controls.withLock {
        val old = synchronized(gate) {
            checkOpen()
            if (logEnabled == value) return@withLock
            logEnabled = value
            logger.also { logger = null; it?.cancel() }
        }
        old?.join()
        synchronized(gate) { if (value && active && !closed && current()) logger = launchLogs(activation) }
    }

    private fun launchLogs(lease: Long): Job = launchOwned(lease) {
        client.logs().collect { line ->
            val type = when (line.level.lowercase()) {
                "warn", "warning" -> "warning"
                "trace", "debug" -> "debug"
                "info" -> "info"
                "error", "fatal" -> "error"
                "silent" -> "silent"
                else -> invalidCore("Unknown native log level: ${line.level}")
            }
            synchronized(gate) {
                if (valid(lease) && logEnabled) publish(listOf(event("log", mapOf("LogLevel" to type,
                    "Payload" to line.message, "dateTime" to Instant.ofEpochMilli(line.time).toString()))))
            }
        }
    }

    private fun launchOwned(lease: Long, work: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.DEFAULT) {
        try { work() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            val context = currentCoroutineContext()
            synchronized(gate) {
                context.ensureActive()
                if (valid(lease)) onFailure(failure)
            }
        }
    }

    fun traffic(onlyProxy: Boolean, total: Boolean): Map<String, Any?> = synchronized(gate) {
        checkOpen()
        val policy = statisticsOnlyProxy ?: missing("getTraffic", "no confirmed native StatisticsPolicies receipt")
        if (onlyProxy != policy) throw MeowQueryException("statistics_policy_mismatch", "Requested traffic policy does not match the applied native policy")
        val value = sample ?: throw MeowQueryException("sample_unavailable", "No native traffic sample has arrived for this session")
        mapOf("up" to value[if (total) "uploadTotal" else "up"], "down" to value[if (total) "downloadTotal" else "down"])
    }

    fun delayResult(name: String, url: String, value: Int): Map<String, Any?> = synchronized(gate) {
        checkOpen()
        val row = protocolMap(protocolCopy(mapOf("name" to name, "url" to url, "value" to value)), "delay")
        if (active) publish(listOf(event("delay", row)))
        row
    }

    suspend fun watchRoute(value: Boolean): Map<String, Any?>? = coroutineScope {
        val request = if (value) async(start = CoroutineStart.LAZY) { client.routeSnapshot() } else null
        val retired: List<Job>
        val intent = synchronized(gate) {
            checkOpen()
            watchIntent++
            retired = listOfNotNull(routeRead, routeRefresh)
            retired.forEach(Job::cancel)
            routeRead = request; routeRefresh = null; routeEnabled = value
            request?.start()
            watchIntent
        }
        retired.forEach { it.join() }
        if (request == null) return@coroutineScope null
        try {
            val route = request.await()
            synchronized(gate) {
                checkOpen()
                if (watchIntent != intent || !routeEnabled) throw MeowQueryException("superseded", "Route watch intent was replaced")
                if (route.coreEpoch != coreEpoch) throw MeowQueryException("stale_session", "Route epoch does not match this session")
                if (lastRoute?.let { route.routeVersion < it.second } == true)
                    throw MeowQueryException("route_superseded", "Route readback moved backwards")
                lastRoute = route.coreEpoch to route.routeVersion
            }
            protocolStamp(route.coreEpoch, route.routeVersion)
        } finally {
            request.cancelAndJoin()
            synchronized(gate) { if (routeRead === request) routeRead = null }
        }
    }

    fun route(epoch: ULong, version: ULong) = synchronized(gate) {
        checkOpen()
        if (epoch != coreEpoch) throw MeowQueryException("stale_session", "Route epoch changed")
        val stamp = epoch to version
        if (lastRoute?.let { version <= it.second } == true) return@synchronized
        lastRoute = stamp
        if (routeEnabled && active) publish(listOf(event("routeChanged", protocolStamp(epoch, version))))
    }

    private suspend fun consume(source: String, lease: Long) {
        val root = protocolObject(source)
        when (root["type"]) {
            "traffic" -> {
                val next = listOf("up", "down", "uploadTotal", "downloadTotal").associateWith { key ->
                    protocolNumber(protocolU64(root[key], key))
                }
                synchronized(gate) { if (valid(lease)) sample = next }
            }
            "groupsChanged" -> {
                val version = protocolU64(root["routeVersion"], "routeVersion")
                refreshRoute(lease, version)
            }
            "providersLoaded" -> {
                val rows = protocolList(root["providers"], "providers").map { value ->
                    val row = protocolMap(value, "provider")
                    val kind = protocolText(row["kind"], "kind")
                    if (kind !in setOf("proxy", "rule")) invalidCore("Invalid loaded provider kind")
                    val name = protocolName(row["name"], "name")
                    val revision = protocolU64(row["revision"], "revision")
                    if (revision == 0uL || row["phase"] !in setOf("initial", "update") || row["changed"] !is Boolean)
                        invalidCore("Invalid provider success receipt")
                    Triple(kind, name, revision)
                }
                synchronized(gate) {
                    if (valid(lease)) publish(rows.filter { loaded.add(it) }.map { event("loaded", it.second) })
                }
            }
            "observations" -> synchronized(gate) { if (valid(lease)) observations(root) }
            else -> invalidCore("Unknown native telemetry frame")
        }
    }

    private suspend fun refreshRoute(lease: Long, notifiedVersion: ULong) = coroutineScope {
        val request = async(start = CoroutineStart.LAZY) { client.routeSnapshot() }
        val accepted = synchronized(gate) {
            if (!valid(lease) || !routeEnabled || lastRoute?.let { notifiedVersion <= it.second } == true) false
            else { routeRefresh = request; request.start(); true }
        }
        if (!accepted) { request.cancelAndJoin(); return@coroutineScope }
        try {
            val snapshot = try { request.await() } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                return@coroutineScope // watch=false cancelled this read, not the other telemetry consumers.
            }
            synchronized(gate) {
                if (valid(lease) && routeEnabled) {
                    if (snapshot.routeVersion < notifiedVersion) invalidCore("Route readback predates its native notification")
                    route(snapshot.coreEpoch, snapshot.routeVersion)
                }
            }
        } finally {
            request.cancelAndJoin()
            synchronized(gate) { if (routeRefresh === request) routeRefresh = null }
        }
    }

    private fun observations(root: Map<String, Any?>) {
        val session = protocolText(root["sessionId"], "sessionId")
        try { if (UUID.fromString(session).toString() != session) invalidCore("Invalid observation session") }
        catch (failure: IllegalArgumentException) { invalidCore("Invalid observation session", failure) }
        val generation = protocolU64(root["generation"], "generation")
        val cursor = protocolU64(root["cursor"], "cursor")
        val dropped = protocolU64(root["dropped"], "dropped")
        val activationCursor = root["activationCursor"]?.let { protocolU64(it, "activationCursor") }
        val before = receipt
        if (generation == 0uL || (activationCursor != null && activationCursor > cursor)) invalidCore("Invalid observation watermark")
        if (session in retiredObservationSessions || (before?.sessionId == session && generation < before.generation)) return
        val same = before?.let { it.sessionId == session && it.generation == generation } == true
        if (!same && !awaitingActivation) throw MeowQueryException("stale_session", "Observation identity changed without activation")
        if (awaitingActivation && activationCursor == null) invalidCore("Missing native observation activation watermark")
        if (same && !awaitingActivation && activationCursor != null && activationCursor != before!!.activationCursor)
            invalidCore("Observation activation changed without a new subscription")
        val floor = if (awaitingActivation) maxOf(if (same) before!!.cursor else 0uL, activationCursor!!) else before!!.cursor
        if (!awaitingActivation && cursor < floor) return // A replay cannot move the accepted cursor backwards.
        val batch = mutableListOf<Map<String, Any?>>()
        val traffic = linkedMapOf<String, Map<String, Any?>>()
        var previous = 0uL
        val values = protocolList(root["events"], "events")
        if (values.size > 32) invalidCore("Too many observation events")
        for (raw in values) {
            val event = protocolMap(raw, "observation")
            val number = protocolU64(event["cursor"], "cursor")
            if (number <= previous || number > cursor) invalidCore("Invalid observation event order")
            previous = number
            if (number <= floor) continue
            val data = protocolMap(event["data"], "data")
            when (event["kind"]) {
                "request" -> batch += event("request", protocolTracker(data))
                "requestTraffic", "requestClosed" -> {
                    val id = protocolName(data["id"], "id")
                    traffic[id] = mapOf("id" to id, "upload" to protocolNumber(protocolU64(data["upload"], "upload")),
                        "download" to protocolNumber(protocolU64(data["download"], "download")))
                }
                "dns" -> {
                    val initiator = data["initiator"] as? String
                    // Unknown initiators and DNS failures are still history, never discarded.
                    val mapped = when (initiator) { "App" -> "app"; "Rule" -> "rule"; "Direct" -> "direct"; "Proxy" -> "proxy"; "Other" -> "other"; else -> null }
                    val aliases = mapOf("initiator" to mapped, "delay" to protocolNumber(protocolU64(data["delay"], "delay")))
                    batch += event("dns", data + aliases + if (initiator != null && mapped == null) mapOf("native-initiator" to initiator) else emptyMap())
                }
                else -> invalidCore("Unknown observation kind")
            }
        }
        if (traffic.isNotEmpty()) batch += event("requestTraffic", traffic.values.toList())
        if (before != null && before.sessionId != session) retiredObservationSessions += before.sessionId
        receipt = MeowObservationReceipt(session, generation, activationCursor ?: before!!.activationCursor, maxOf(cursor, floor),
            if (same) maxOf(dropped, before!!.dropped) else dropped)
        awaitingActivation = false
        publish(batch)
    }

    fun observationReceipt(): MeowObservationReceipt? = synchronized(gate) { receipt }
    private fun valid(lease: Long) = !closed && active && activation == lease && current()
    private fun checkOpen() {
        if (closed || !current()) throw MeowQueryException(if (closed) "closed" else "stale_session", "Telemetry session is no longer current")
    }
    private fun event(type: String, data: Any?): Map<String, Any?> = protocolMap(protocolCopy(mapOf("type" to type, "data" to data)), "event")
    private fun publish(batch: List<Map<String, Any?>>) {
        if (batch.isNotEmpty() && !closed && current()) emitBatch(Collections.unmodifiableList(batch.toList()))
    }

    override fun close() = synchronized(gate) {
        if (!closed) {
            closed = true; active = false; activation++
            producer?.cancel(); logger?.cancel()
            routeRead?.cancel(); routeRefresh?.cancel()
            client.setTelemetryObservations(false)
        }
    }
}
