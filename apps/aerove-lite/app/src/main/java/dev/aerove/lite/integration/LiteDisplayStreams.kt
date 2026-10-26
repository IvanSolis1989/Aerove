package dev.aerove.lite.integration

import dev.aerove.lite.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display leaves are published by the data owner; collecting one never subscribes to LiteState. */
interface LiteDisplayStreams {
    val traffic: StateFlow<Traffic>
    val trafficHistory: StateFlow<List<Traffic>>
    val trafficHistoryRevision: StateFlow<Long>
    val uptimeMillis: StateFlow<Long>
    val dashboardCounts: StateFlow<DashboardCounts>
    val connections: StateFlow<List<ConnectionInfo>>
    val diagnostics: StateFlow<DiagnosticsState>
    val localAddress: StateFlow<String?>
}

internal class MutableLiteDisplayStreams(initial: LiteState = LiteState()) : LiteDisplayStreams {
    private val rates = MutableStateFlow(initial.traffic)
    override val traffic = rates.asStateFlow()
    private val history = MutableStateFlow(initial.trafficHistory)
    override val trafficHistory = history.asStateFlow()
    private val revision = MutableStateFlow(initial.trafficHistoryRevision)
    override val trafficHistoryRevision = revision.asStateFlow()
    private val uptime = MutableStateFlow(initial.uptimeMillis)
    override val uptimeMillis = uptime.asStateFlow()
    private val counts = MutableStateFlow(initial.dashboardCounts)
    override val dashboardCounts = counts.asStateFlow()
    private val rows = MutableStateFlow(initial.connections)
    override val connections = rows.asStateFlow()
    private val checks = MutableStateFlow(initial.diagnostics)
    override val diagnostics = checks.asStateFlow()
    private val address = MutableStateFlow(initial.localAddress)
    override val localAddress = address.asStateFlow()

    fun attach(root: LiteState): LiteState = root.copy(traffic = traffic.value,
        trafficHistory = trafficHistory.value, trafficHistoryRevision = trafficHistoryRevision.value,
        uptimeMillis = uptimeMillis.value, dashboardCounts = dashboardCounts.value,
        connections = connections.value, diagnostics = diagnostics.value, localAddress = localAddress.value)

    fun publish(value: LiteState) {
        rates.value = value.traffic
        history.value = value.trafficHistory
        revision.value = value.trafficHistoryRevision
        uptime.value = value.uptimeMillis
        counts.value = value.dashboardCounts
        rows.value = value.connections
        checks.value = value.diagnostics
        address.value = value.localAddress
    }
}

/** LiteState is the low frequency application model. Legacy display fields remain fixture inputs only. */
internal fun LiteState.withoutDisplay(): LiteState = copy(traffic = Traffic(), trafficHistory = emptyList(),
    trafficHistoryRevision = 0L, uptimeMillis = 0L, dashboardCounts = DashboardCounts(),
    connections = emptyList(), diagnostics = DiagnosticsState(), localAddress = null)
