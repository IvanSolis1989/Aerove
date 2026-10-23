package dev.aerove.lite.integration

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.ProfileDocumentSnapshot
import dev.aerove.lite.vpn.MaintenanceRuntimeContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class RestoreRuntimeTarget(val profileId: String?, val settings: LiteSettings)
data class RestoreRuntimeBaseline(val target: RestoreRuntimeTarget, val document: ProfileDocumentSnapshot?)

/** Stop and resume remain owned by the same service; the settings owner supplies its fenced phases. */
class RestoreRuntimeOwner(
    private val stop: suspend () -> MaintenanceRuntimeContext,
    private val capture: suspend (MaintenanceRuntimeContext) -> RestoreRuntimeBaseline,
    private val target: suspend () -> RestoreRuntimeTarget,
    private val resume: suspend (MaintenanceRuntimeContext, RestoreRuntimeTarget?, Boolean) -> Unit,
    private val rollbackSafe: suspend (Throwable, RestoreRuntimeBaseline) -> Boolean,
    private val resumeAfterSuccess: Boolean = true,
) {
    suspend fun execute(transaction: suspend (beforeWrite: suspend () -> Unit, afterLoad: suspend () -> Unit) -> Unit) {
        var context: MaintenanceRuntimeContext? = null
        var baseline: RestoreRuntimeBaseline? = null
        var completed = false
        try {
            transaction({
                check(context == null) { "Restore stop phase repeated" }
                val stopped = stop()
                context = stopped
                baseline = capture(stopped)
            }, {
                val stopped = checkNotNull(context) { "Restore was not stopped" }
                val actual = target()
                withContext(NonCancellable) { resume(stopped, actual, resumeAfterSuccess && stopped.userRequested) }
                completed = true
            })
            check(completed) { "Restore load phase was not completed" }
        } catch (failure: Throwable) {
            val stopped = context
            if (stopped != null) withContext(NonCancellable) {
                var safe = false
                try { baseline?.let { safe = rollbackSafe(failure, it) } }
                catch (verification: Throwable) { failure.addSuppressed(verification) }
                try { resume(stopped, baseline?.target.takeIf { safe }, safe && stopped.userRequested) }
                catch (recovery: Throwable) { failure.addSuppressed(recovery) }
            }
            throw failure
        }
    }
}
