package dev.aerove.lite.ui

import java.util.concurrent.atomic.AtomicLong

internal data class PixelCopyOwnershipSnapshot(
    val id: Long,
    val submitted: Boolean,
    val callbackResult: Int?,
    val closeRequested: Boolean,
    val released: Boolean,
) {
    val inFlight get() = submitted && callbackResult == null
}

/** The destination stays owned by an asynchronous request until its real completion callback. */
internal class PixelCopyOwnership(private val releaseDestination: () -> Unit) {
    private val id = nextId.incrementAndGet()
    private var submitted = false
    private var callbackResult: Int? = null
    private var closeRequested = false
    private var released = false

    @Synchronized fun submit() {
        check(!submitted && !closeRequested)
        submitted = true
    }

    @Synchronized fun complete(result: Int) {
        check(submitted)
        if (callbackResult != null) return
        callbackResult = result
        releaseIfFinished()
    }

    @Synchronized fun close() {
        closeRequested = true
        releaseIfFinished()
    }

    @Synchronized fun snapshot() = PixelCopyOwnershipSnapshot(id, submitted, callbackResult, closeRequested, released)

    private fun releaseIfFinished() {
        if (closeRequested && (!submitted || callbackResult != null) && !released) {
            releaseDestination()
            released = true
        }
    }

    private companion object { val nextId = AtomicLong(0) }
}

internal class PixelCopyOwnershipFailure(originalFailure: Throwable, val ownership: PixelCopyOwnershipSnapshot) :
    IllegalStateException("PixelCopy completion is unconfirmed; abort matrix with destination retained", originalFailure)
