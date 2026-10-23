package dev.aerove.lite.requests

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okhttp3.Call

/** Retains cancellation while a blocking response body is being consumed, after headers resume. */
internal class DownloadCancellation(context: CoroutineContext) : AutoCloseable {
    private val active = AtomicReference<Call?>()
    private val guard = CoroutineScope(context).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { active.get()?.cancel() }
    }

    fun track(call: Call): Call {
        active.set(call)
        if (!guard.isActive) call.cancel()
        return call
    }

    override fun close() { guard.cancel() }
}
