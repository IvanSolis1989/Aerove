package dev.aerove.lite.data

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreStreamCloseOrderingTest {
    @Test fun synchronousOnClosedDuringReplyCannotEraseRemoteFailure() = runBlocking {
        for (code in listOf(1000, 1001, 1011)) {
            val channel = Channel<String>()
            var failure: IOException? = null
            var replies = 0
            closeCoreStreamBeforeReply(code, {
                failure = it
                assertTrue(channel.close(it))
            }) {
                replies++
                // Model the fastest possible writer: onClosed runs inside reply.
                assertFalse(channel.close())
            }
            assertEquals(1, replies)
            assertSame(failure, channel.receiveCatching().exceptionOrNull())
            assertEquals("Core stream closed ($code)", failure!!.message)
        }
    }

    @Test fun alreadyTerminatedOwnerKeepsItsFirstCauseAndStillReplies() = runBlocking {
        val channel = Channel<String>()
        val first = IOException("synthetic earlier failure")
        channel.close(first)
        var replies = 0
        closeCoreStreamBeforeReply(1001, { assertFalse(channel.close(it)) }) {
            replies++
            channel.close()
        }
        assertEquals(1, replies)
        assertSame(first, channel.receiveCatching().exceptionOrNull())
    }

    @Test fun replyFailureCannotUndoRecordedStreamFailure() = runBlocking {
        val channel = Channel<String>()
        var failure: IOException? = null
        val replyFailure = IllegalStateException("synthetic reply failure")
        try {
            closeCoreStreamBeforeReply(1011, { failure = it; channel.close(it) }) {
                throw replyFailure
            }
            fail("Reply failure must remain visible")
        } catch (actual: IllegalStateException) { assertSame(replyFailure, actual) }
        assertSame(failure, channel.receiveCatching().exceptionOrNull())
    }
}
