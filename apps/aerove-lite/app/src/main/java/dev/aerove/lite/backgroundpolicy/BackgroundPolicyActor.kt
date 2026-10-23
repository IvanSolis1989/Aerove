package dev.aerove.lite.backgroundpolicy

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

data class ListenerPolicyIntent(val revision: Long, val listening: Boolean, val suspended: Boolean)

class BackgroundPolicyActor(
    scope: CoroutineScope,
    private val apply: suspend (ListenerPolicyIntent) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private data class Input(val revision: Long, val running: Boolean, val safeMode: Boolean,
        val rules: List<String>, val ssid: String?)
    private val revisions = AtomicLong()
    private val inputs = Channel<Input>(Channel.CONFLATED)
    private val decision = MutableStateFlow<ListenerPolicyIntent?>(null)
    suspend fun latestDecision(): ListenerPolicyIntent = decision.first { it != null && isCurrent(it) }!!
    private val worker = scope.launch {
        var applied: Pair<Boolean, Boolean>? = null
        var rules: List<String>? = null
        var exclusions = emptySet<String>()
        for (input in inputs) {
            if (rules !== input.rules) { rules = input.rules; exclusions = input.rules.toHashSet() }
            val excluded = input.ssid != null && input.ssid in exclusions
            val next = Pair(input.running && !input.safeMode && !excluded, input.running && !input.safeMode && excluded)
            val intent = ListenerPolicyIntent(input.revision, next.first, next.second)
            decision.value = intent
            if (next == applied) continue
            try {
                apply(intent)
                // A revision skipped after awaiting the lifecycle lock cannot establish a newer baseline.
                applied = if (input.revision == revisions.get()) next else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // No periodic retries: a fresh observation or explicit retry is required.
                onFailure(error)
            }
        }
    }

    fun update(preferences: BackgroundPolicyPreferences, userWantsRunning: Boolean, safeMode: Boolean, ssid: String?): Long {
        val revision = revisions.incrementAndGet()
        check(inputs.trySend(Input(revision, userWantsRunning, safeMode, preferences.excludeSSIDs, ssid)).isSuccess) {
            "Background policy is closed"
        }
        return revision
    }

    fun isCurrent(intent: ListenerPolicyIntent): Boolean = intent.revision == revisions.get()

    override fun close() { inputs.close(); worker.cancel() }
}
