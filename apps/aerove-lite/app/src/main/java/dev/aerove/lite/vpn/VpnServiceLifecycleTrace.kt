package dev.aerove.lite.vpn

import dev.aerove.lite.VpnPhase
import java.util.concurrent.atomic.AtomicLong

internal object VpnServiceLifecycleTrace {
    // Pair this process-local sequence with the logcat PID.
    private val instances = AtomicLong()
    fun nextInstance(): Long = instances.incrementAndGet()

    enum class Command { None, Start, Stop, Reconfigure, RetryNetwork, RefreshPolicy, SystemStart, Restore, Other }
    enum class Event {
        Created, CommandReceived, MaintenanceIgnored, IntentAccepted, StartAdmission, StartQueued, StartEntered,
        StartRead, StartSubmitted, StartCancelled, StartRejected, StatePublished, StopJoinQueued, StopJoined,
        StopSelfUnqualified, StopSelfById, DestroyEntered, CoordinatorCloseBegin, CoordinatorClosed,
        NetworkRuntimeClosed, ScopesCancelBegin, ScopesCancelRequested, DestroyReturned,
    }

    fun format(event: Event, processInstance: Long, latestReceivedStartId: Int, latestReceivedCommand: Command,
        causeStartId: Int?, causeCommand: Command?, causeIntentGeneration: Long?, owner: Long,
        intentGeneration: Long, sessionGeneration: Long?, phase: VpnPhase?, visiblePhase: VpnPhase,
        planIntentGeneration: Long?, scopeActive: Boolean, coreScopeActive: Boolean): String =
        "schema=2 event=$event processInstance=$processInstance latestReceivedStartId=$latestReceivedStartId " +
            "latestReceivedCommand=$latestReceivedCommand causeStartId=$causeStartId causeCommand=$causeCommand " +
            "causeIntentGeneration=$causeIntentGeneration owner=$owner intentGeneration=$intentGeneration " +
            "sessionGeneration=$sessionGeneration phase=$phase visiblePhase=$visiblePhase " +
            "planIntentGeneration=$planIntentGeneration scopeActive=$scopeActive coreScopeActive=$coreScopeActive"
}
