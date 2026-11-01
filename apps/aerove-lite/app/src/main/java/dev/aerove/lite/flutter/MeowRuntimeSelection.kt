package dev.aerove.lite.flutter

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.RouteSnapshot
import dev.aerove.lite.data.StartupSelectionSnapshot
import dev.aerove.lite.data.StartupSelectionUnsupported
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class MeowSelectionReceipt(val coreEpoch: ULong, val routeVersion: ULong, val selected: Map<String, String>,
    val requested: Map<String, String> = emptyMap(), val actual: Map<String, dev.aerove.lite.data.StartupSelectionGroup> = emptyMap(),
    val startupConsumption: Map<String, dev.aerove.lite.data.StartupSelectionConsumption> = emptyMap())

class MeowRuntimeSelection private constructor(
    private val groups: suspend () -> List<ProxyGroup>,
    private val select: suspend (String, String) -> Unit,
    private val route: suspend () -> RouteSnapshot,
    private val setupPolicy: (suspend (RuntimeHandoff) -> Unit)?,
    private val startupRead: (suspend () -> StartupSelectionSnapshot)? = null,
    private val startupRestore: (suspend (Map<String, String>, StartupSelectionSnapshot) -> StartupSelectionSnapshot)? = null,
) {
    constructor(client: CoreApiClient, setupPolicy: (suspend (RuntimeHandoff) -> Unit)? = null) :
        this(client::groups, client::select, client::routeSnapshot, setupPolicy, client::startupSelectionSnapshot, client::restoreStartupSelection)

    internal constructor(groups: suspend () -> List<ProxyGroup>, select: suspend (String, String) -> Unit,
        route: suspend () -> RouteSnapshot, @Suppress("UNUSED_PARAMETER") testBoundary: Unit) : this(groups, select, route, null)

    suspend fun apply(handoff: RuntimeHandoff, isCurrent: () -> Boolean): MeowSelectionReceipt {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw MeowConfigProtocolException("superseded", "Runtime selection intent changed")
        val policy = setupPolicy ?: throw MeowConfigProtocolException("implementation_blocked",
            "Startup test-url and certificate policy require an actual apply/readback owner", mapOf("field" to "$.test-url"))
        policy(handoff)
        val row = JSONObject(handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("selected-map")
        val mapping = row.keys().asSequence().associateWith { row.getString(it) }
        return apply(mapping, isCurrent)
    }

    suspend fun apply(mapping: Map<String, String>, isCurrent: () -> Boolean): MeowSelectionReceipt {
        suspend fun current() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw MeowConfigProtocolException("superseded", "Runtime selection intent changed")
        }
        current()
        if (startupRead != null && startupRestore != null) return applyStartup(mapping.toMap(), isCurrent)
        val before = route()
        if (before.coreEpoch == 0uL) throw MeowConfigProtocolException("selection_error", "Core is not ready for selection")
        val available = groups().associateBy { it.name }
        current()
        val expected = linkedMapOf<String, String>()
        for ((name, requested) in mapping) {
            val group = available[name] ?: continue
            if (group.type !in setOf("Selector", "select")) {
                if (group.type in setOf("URLTest", "url-test", "Fallback", "fallback"))
                    throw MeowConfigProtocolException("implementation_blocked", "Meow selection equivalence requires explicit group semantics",
                        mapOf("field" to "selected-map.$name", "type" to group.type))
                continue
            }
            // Standard Selector.ForceSet keeps stale names; selectedProxy resolves them to its first current member.
            val target = group.nodes.firstOrNull { it.name == requested }?.name ?: group.nodes.firstOrNull()?.name
                ?: throw MeowConfigProtocolException("selection_error", "Selector has no actual members", mapOf("field" to "selected-map.$name"))
            expected[name] = target
            if (group.selected != target) { current(); select(name, target); current() }
        }
        val actual = groups().associateBy { it.name }
        val after = route()
        current()
        if (after.coreEpoch != before.coreEpoch) throw MeowConfigProtocolException("superseded", "Core epoch changed during selection")
        expected.forEach { (name, selected) ->
            if (actual[name]?.selected != selected || after.picks[name] != selected)
                throw MeowConfigProtocolException("selection_error", "Actual group and route selection disagree", mapOf("field" to "selected-map.$name"))
        }
        return MeowSelectionReceipt(after.coreEpoch, after.routeVersion, expected.toMap())
    }
    private suspend fun applyStartup(requested: Map<String, String>, isCurrent: () -> Boolean): MeowSelectionReceipt {
        fun current() { if (!isCurrent()) throw MeowConfigProtocolException("superseded", "Runtime selection intent changed") }
        currentCoroutineContext().ensureActive(); current()
        val before = checkNotNull(startupRead).invoke()
        currentCoroutineContext().ensureActive(); current()
        val applied = try { checkNotNull(startupRestore).invoke(requested, before) }
        catch (failure: StartupSelectionUnsupported) {
            throw MeowConfigProtocolException("implementation_blocked", failure.reason,
                mapOf("field" to "selected-map.${failure.group}", "reason" to failure.reason))
        }
        currentCoroutineContext().ensureActive(); current()
        if (applied.requested != requested || applied.route.coreEpoch != before.route.coreEpoch || applied.graphToken != before.graphToken ||
            before.intentVersion == ULong.MAX_VALUE || applied.intentVersion != before.intentVersion + 1uL ||
            applied.route.routeVersion < before.route.routeVersion)
            throw MeowConfigProtocolException("selection_error", "Startup cache receipt identity differs from the request")
        val actual = checkNotNull(startupRead).invoke()
        currentCoroutineContext().ensureActive(); current()
        if (actual.graphToken != applied.graphToken || actual.intentVersion != applied.intentVersion || actual.route != applied.route || actual.groups != applied.groups)
            throw MeowConfigProtocolException("superseded", "Startup cache receipt was superseded before readback")
        if (applied.consumed.keys.any { it !in requested || before.groups[it]?.type !in setOf("Fallback", "fallback") })
            throw MeowConfigProtocolException("selection_error", "Unrequested startup consumption")
        val selected = linkedMapOf<String, String>()
        for ((name, raw) in requested) {
            val original = before.groups[name] ?: continue
            val group = actual.groups[name] ?: throw MeowConfigProtocolException("selection_error", "Startup group disappeared")
            val expected = when (original.type) {
                "Selector", "select" -> raw.takeIf { it in original.members } ?: original.members.first()
                "URLTest", "url-test" -> raw
                "Fallback", "fallback" -> {
                    val consumed = applied.consumed[name]
                    if (consumed == null) {
                        if (raw.isNotEmpty() && raw !in original.members)
                            throw MeowConfigProtocolException("selection_error", "Missing raw Fallback consumption proof")
                        raw
                    } else {
                        val validClear = consumed.fixedAfter.isEmpty() && raw in original.members
                        if (raw.isEmpty() || consumed.fixedBefore != raw || consumed.selected !in original.members ||
                            (consumed.fixedAfter != raw && !validClear) ||
                            (raw !in original.members && consumed.selected != original.members.first()) ||
                            (raw in original.members && consumed.fixedAfter == raw && consumed.selected != raw) ||
                            (validClear && consumed.selected !in original.members.drop(original.members.indexOf(raw) + 1) + original.members.first()))
                            throw MeowConfigProtocolException("selection_error", "Fallback startup consumption proof differs")
                        consumed.fixedAfter
                    }
                }
                else -> throw MeowConfigProtocolException("selection_error", "Unsupported startup group was accepted")
            }
            val automatic = original.type in setOf("URLTest", "url-test", "Fallback", "fallback")
            if (group.type != original.type || group.members != original.members ||
                (automatic && group.fixed != expected) || (!automatic && group.current != expected) ||
                actual.route.picks[name] != group.current)
                throw MeowConfigProtocolException("selection_error", "Actual startup fixed/current/route state differs", mapOf("field" to "selected-map.$name"))
            selected[name] = group.current
        }
        return MeowSelectionReceipt(actual.route.coreEpoch, actual.route.routeVersion, selected, requested, actual.groups, applied.consumed)
    }

}
