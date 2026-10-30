package dev.aerove.lite.flutter

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.RouteSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class MeowSelectionReceipt(val coreEpoch: ULong, val routeVersion: ULong, val selected: Map<String, String>)

class MeowRuntimeSelection private constructor(
    private val groups: suspend () -> List<ProxyGroup>,
    private val select: suspend (String, String) -> Unit,
    private val route: suspend () -> RouteSnapshot,
    private val setupPolicy: (suspend (RuntimeHandoff) -> Unit)?,
) {
    constructor(client: CoreApiClient, setupPolicy: (suspend (RuntimeHandoff) -> Unit)? = null) :
        this(client::groups, client::select, client::routeSnapshot, setupPolicy)

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
}
