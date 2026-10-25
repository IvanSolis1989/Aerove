package dev.aerove.lite.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal suspend fun <T> nativeResourceCall(
    cancelNative: () -> Unit,
    work: () -> T,
): T = coroutineScope {
    val running = async(Dispatchers.IO) { runCatching(work) }
    try { running.await().getOrThrow() }
    catch (cancel: CancellationException) {
        try { cancelNative() } catch (failure: Throwable) { cancel.addSuppressed(failure) }
        withContext(NonCancellable) { running.join() }
        throw cancel
    }
}
