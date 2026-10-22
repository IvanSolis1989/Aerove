package dev.aerove.lite.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

internal class GroupRefresh<T>(
    private val session: TelemetrySession,
    private val read: suspend () -> T,
    private val failure: (Throwable) -> Unit,
    private val update: (T) -> Unit,
    private val retryIf: (T) -> Boolean = { false },
) {
    private val requests = Channel<Long>(Channel.CONFLATED)
    private var requested = 0L
    private var revision: ULong? = null
    private var failed = false

    init {
        val job = session.launch("groups-refresh", { requests.close() }) {
            for (stamp in requests) {
                for (attempt in 0 until 3) {
                    if (!session.current || stamp != requested) break
                    try {
                        val value = read()
                        if (!session.current || stamp != requested) break
                        if (attempt == 2 || !retryIf(value)) {
                            session.accept { update(value) }
                            break
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!session.current || stamp != requested) break
                        if (attempt == 2) {
                            failed = true
                            session.accept { failure(error) }
                            break
                        }
                    }
                    // Full retry() uses three attempts separated by midDuration.
                    delay(200)
                }
            }
        }
        if (job == null) requests.close()
    }

    fun changed(revision: ULong): Boolean {
        if (!session.current || (this.revision == revision && !failed)) return false
        this.revision = revision
        failed = false
        return requests.trySend(++requested).isSuccess
    }
}
