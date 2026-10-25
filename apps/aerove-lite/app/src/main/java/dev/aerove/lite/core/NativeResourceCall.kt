package dev.aerove.lite.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun <T> nativeResourceCall(
    cancelNative: () -> Unit,
    work: () -> T,
): T = coroutineScope {
    val running = async(Dispatchers.IO) { runCatching(work) }
    try { running.await().getOrThrow() }
    catch (cancel: CancellationException) {
        // await may recover a copy; keep cleanup on the cancelled owner's actual exception.
        val cancellation = try {
            currentCoroutineContext().ensureActive()
            cancel
        } catch (ownerCancellation: CancellationException) { ownerCancellation }
        try { cancelNative() } catch (failure: Throwable) { cancellation.addSuppressed(failure) }
        withContext(NonCancellable) { running.join() }
        throw cancellation
    }
}
