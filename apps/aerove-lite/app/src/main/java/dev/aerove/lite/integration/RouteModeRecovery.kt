package dev.aerove.lite.integration

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.vpn.CoreEndpoint
import kotlinx.coroutines.withTimeout

/** Compensation is owned cleanup, including after the foreground client was closed. */
internal class RouteModeRecovery(private val endpoint: CoreEndpoint,
    private val readSaved: suspend () -> LiteSettings,
    private val owns: () -> Boolean,
    private val republish: suspend (forceAfterStop: Boolean) -> Unit,
    private val stop: suspend (CoreEndpoint, String) -> Boolean) {
    suspend fun recover(cause: Exception) {
        try {
            withTimeout(10_000) {
                val saved = readSaved()
                if (!owns()) return@withTimeout
                CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.setMode(saved.mode) }
                if (owns()) republish(false)
            }
        } catch (failure: Exception) {
            failure.addSuppressed(cause)
            try { stop(endpoint, "Runtime mode recovery failed: ${failure.message ?: failure.javaClass.simpleName}") }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup); throw failure }
            try { republish(true) } catch (readback: Exception) { failure.addSuppressed(readback) }
            throw failure
        }
    }
}
