package dev.aerove.lite.flutter

import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

class MeowRuleSetResponseTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun home() = temporary.newFolder().canonicalFile
    private fun call(id: String, method: String = "dumpRuleSet") = JSONObject().put("id", id).put("method", method).toString()
    private fun row(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun dispatcher(home: File, handler: MeowProtocolMethod, bytes: Int = 64 * 1024 * 1024) =
        MeowProtocolDispatcher(home, mapOf("dumpRuleSet" to handler),
            stringResponseBudgets = mapOf("dumpRuleSet" to MeowStringResponseBudget(bytes)))

    @Test fun ruleTextBeyond16MiBUsesAStandardLosslessFileEnvelopeWhileOrdinaryMethodsKeepTheirFence() = runBlocking<Unit> {
        val home = home()
        val text = ("+." + "x".repeat(80) + ".test\n").repeat(200_000)
        assertTrue(text.length > 16 * 1024 * 1024)
        val id = "preview \"\\🦊\u0001"
        val dispatcher = MeowProtocolDispatcher(home, mapOf("dumpRuleSet" to MeowProtocolMethod { text }, "getProxies" to MeowProtocolMethod { text }),
            stringResponseBudgets = mapOf("dumpRuleSet" to MeowStringResponseBudget(64 * 1024 * 1024)))
        try {
            val response = row(dispatcher.invoke(call(id)))
            assertEquals(id, response.getString("id"))
            val file = File(response.getString("file"))
            assertEquals(File(home, "responses"), file.parentFile)
            val saved = JSONObject(file.readText())
            assertEquals(id, saved.getString("id")); assertEquals(text, saved.getString("result"))
            assertFalse(saved.has("file")); assertFalse(saved.has("error"))
            val ordinary = row(dispatcher.invoke(call("ordinary", "getProxies")))
            assertEquals("spill_error", ordinary.getJSONObject("error").getString("code"))
            assertTrue(file.exists())
        } finally { assertTrue(dispatcher.closeAndJoin()) }
        assertTrue(File(home, "responses").listFiles()!!.isEmpty())
    }

    @Test fun dedicatedTextFenceCountsUtf8AndIncludesWorstCaseEscapesAndRequestIdentity() = runBlocking<Unit> {
        val home = home()
        assertEquals(6 * 64 * 1024 * 1024 + 2048, MeowStringResponseBudget(64 * 1024 * 1024).encodedBytes)
        val dispatcher = dispatcher(home, MeowProtocolMethod { "界".repeat(11) }, 32)
        try {
            assertEquals("output_too_large", row(dispatcher.invoke(call("utf8"))).getJSONObject("error").getString("code"))
            assertFalse(File(home, "responses").exists())
        } finally { assertTrue(dispatcher.closeAndJoin()) }
        val text = (0..31).map(Int::toChar).joinToString("") + "\"\\/東京🦊"
        MeowFlutterSpill(home, MeowSpillBudget(4096, 4096)).use { spill ->
            val file = File(spill.writeJsonStringResult("\u0001".repeat(256), text))
            val saved = JSONObject(file.readText())
            assertEquals("\u0001".repeat(256), saved.getString("id"))
            assertEquals(text, saved.getString("result"))
        }
    }

    @Test fun theFull64MiBTextBoundaryStillReturnsACompleteStandardResponse() = runBlocking<Unit> {
        val home = home()
        val text = "a".repeat(64 * 1024 * 1024)
        val dispatcher = dispatcher(home, MeowProtocolMethod { text })
        try {
            val response = row(dispatcher.invoke(call("full-budget")))
            val file = File(response.getString("file"))
            val saved = JSONObject(file.readText())
            assertEquals("full-budget", saved.getString("id"))
            assertEquals(text, saved.getString("result"))
            assertTrue(file.length() > 64L * 1024 * 1024)
        } finally { assertTrue(dispatcher.closeAndJoin()) }
    }

    @Test fun aggregateAndCountFencesAreSharedAndCloseDeletesOnlyThisOwnersFiles() = runBlocking<Unit> {
        val home = home()
        val budget = MeowSpillBudget(128, 160, 2)
        val first = MeowFlutterSpill(home, budget)
        val second = MeowFlutterSpill(home, budget)
        try {
            val foreign = File(first.writeJsonStringResult("a", "x".repeat(100)))
            assertTrue(foreign.length() < 160)
            val failure = assertThrows(MeowConfigProtocolException::class.java) {
                runBlocking { second.writeJsonStringResult("b", "x".repeat(40)) }
            }
            assertEquals("spill_error", failure.code)
            assertEquals(listOf(foreign), File(home, "responses").listFiles()!!.toList())
            second.close()
            assertTrue(foreign.exists())
        } finally { first.close(); second.close() }
        val owner = MeowFlutterSpill(home, MeowSpillBudget(128, 256, 1))
        try {
            val kept = File(owner.writeJsonStringResult("one", "value"))
            assertThrows(MeowConfigProtocolException::class.java) { runBlocking { owner.writeJsonStringResult("two", "value") } }
            assertEquals(listOf(kept), File(home, "responses").listFiles()!!.toList())
        } finally { owner.close() }
    }

    @Test fun fileFenceCountsActualEscapedBytesWhileStreamingAndRemovesPartialOutput() = runBlocking<Unit> {
        val home = home()
        MeowFlutterSpill(home, MeowSpillBudget(128, 256)).use { spill ->
            val failure = assertThrows(MeowConfigProtocolException::class.java) {
                runBlocking { spill.writeJsonStringResult("escaped", "\u0001".repeat(30)) }
            }
            assertEquals("spill_error", failure.code)
            assertTrue(File(home, "responses").listFiles()!!.isEmpty())
        }
    }

    @Test fun oneMethodGateCoversTheHandlerAndPreventsOverlappingLargeResults() = runBlocking<Unit> {
        val home = home()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val dispatcher = dispatcher(home, MeowProtocolMethod {
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            "actual result"
        })
        try {
            val first = async { dispatcher.invoke(call("first")) }
            withTimeout(4000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.invoke(call("second")) }
            assertEquals(1, calls.get()); assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals("actual result", row(withTimeout(4000) { first.await() }).getString("result"))
            assertEquals("actual result", row(withTimeout(4000) { second.await() }).getString("result"))
            assertEquals(2, calls.get())
        } finally { release.complete(Unit); assertTrue(dispatcher.closeAndJoin()) }
    }

    @Test fun cancelAndCloseJoinRealStreamingWritesWithoutDeletingAnotherOwnersResponse() = runBlocking<Unit> {
        val home = home()
        val foreignOwner = MeowFlutterSpill(home)
        val foreign = File(foreignOwner.write("foreign response", "json"))
        val text = "a".repeat(64 * 1024 * 1024)
        try {
            for (close in listOf(false, true)) {
                val dispatcher = dispatcher(home, MeowProtocolMethod { text })
                FileSystems.getDefault().newWatchService().use { watch ->
                    File(home, "responses").toPath().register(watch, ENTRY_CREATE)
                    val writing = async(Dispatchers.Default) { dispatcher.invoke(call("streaming")) }
                    try {
                        val event = withContext(Dispatchers.IO) { watch.poll(5, TimeUnit.SECONDS) }
                        assertNotNull("Actual stream never created its temporary file", event)
                        assertTrue(event!!.pollEvents().any { it.context().toString().endsWith(".tmp") })
                        assertFalse("Cancellation must reach an in-progress stream", writing.isCompleted)
                        if (close) assertTrue(withTimeout(5000) { dispatcher.closeAndJoin() })
                        else assertTrue(withTimeout(5000) { dispatcher.cancel("streaming") })
                        val answer = row(withTimeout(5000) { writing.await() })
                        assertEquals("cancelled", answer.getJSONObject("error").getString("code"))
                        assertEquals(listOf(foreign), File(home, "responses").listFiles()!!.toList())
                    } finally { assertTrue(dispatcher.closeAndJoin()); writing.join() }
                }
                assertTrue(foreign.exists())
            }
        } finally { foreignOwner.close() }
    }

    @Test fun callerCancellationAfterIoCompletesDiscardsTheUndeliveredFile() = runBlocking<Unit> { cancelledHandoff(false, false) }
    @Test fun protocolCancellationAfterIoCompletesDiscardsBeforeItsJoinAcknowledgement() = runBlocking<Unit> { cancelledHandoff(true, false) }
    @Test fun callerCancellationAfterEncodingCompletesDiscardsBeforeCallerJoin() = runBlocking<Unit> { cancelledHandoff(false, true) }
    @Test fun protocolCancellationBeforeCompletedResponseDeliveryStillDiscardsItsFile() = runBlocking<Unit> { cancelledHandoff(true, true) }
    @Test fun protocolCancellationAfterResponseDeliveryPreservesTheDeliveredReceipt() = runBlocking<Unit> {
        val home = home()
        val dispatcher = dispatcher(home, MeowProtocolMethod { "a".repeat(60_000) })
        try {
            val file = File(row(dispatcher.invoke(call("delivered"))).getString("file"))
            assertTrue(dispatcher.cancel("delivered"))
            assertEquals(60030L, file.length())
            assertEquals("a".repeat(60_000), JSONObject(file.readText()).getString("result"))
        } finally { assertTrue(dispatcher.closeAndJoin()) }
        assertTrue(File(home, "responses").listFiles()!!.isEmpty())
    }

    @Test fun cancellingAReusedIdDoesNotDiscardItsPreviouslyDeliveredResponse() = runBlocking<Unit> {
        val home = home()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val dispatcher = dispatcher(home, MeowProtocolMethod {
            if (calls.incrementAndGet() > 1) { entered.complete(Unit); release.await() }
            "a".repeat(60_000)
        })
        try {
            val file = File(row(dispatcher.invoke(call("reused"))).getString("file"))
            val next = async { dispatcher.invoke(call("reused")) }
            withTimeout(4000) { entered.await() }
            assertTrue(dispatcher.cancel("reused"))
            assertEquals(listOf(file), File(home, "responses").listFiles()!!.toList())
            assertEquals("cancelled", row(withTimeout(4000) { next.await() }).getJSONObject("error").getString("code"))
            assertEquals("a".repeat(60_000), JSONObject(file.readText()).getString("result"))
        } finally { release.complete(Unit); assertTrue(dispatcher.closeAndJoin()) }
        assertTrue(File(home, "responses").listFiles()!!.isEmpty())
    }
    private class QueuedCaller : CoroutineDispatcher() {
        private val work = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.put(block) }
        fun next(): Runnable = checkNotNull(work.poll(5, TimeUnit.SECONDS)) { "Expected a caller continuation" }
    }

    private suspend fun CoroutineScope.cancelledHandoff(protocolCancel: Boolean, afterEncoding: Boolean) {
        val home = home()
        val caller = QueuedCaller()
        val dispatcher = dispatcher(home, MeowProtocolMethod { "a".repeat(60_000) })
        val response = async(caller) { dispatcher.invoke(call("handoff")) }
        caller.next().run()
        caller.next().run()
        var continuation = caller.next()
        if (afterEncoding) { continuation.run(); continuation = caller.next() }
        val files = File(home, "responses").listFiles()!!.toList()
        assertEquals(1, files.size)
        assertEquals(60028L, files.single().length())
        try {
            val cancelling = if (protocolCancel) async(start = CoroutineStart.UNDISPATCHED) {
                val joined = dispatcher.cancel("handoff")
                assertTrue("Cancellation acknowledgement must follow file discard", File(home, "responses").listFiles()!!.isEmpty())
                joined
            } else null
            if (cancelling == null) response.cancel()
            continuation.run()
            while (!response.isCompleted) caller.next().run()
            if (cancelling != null) {
                assertTrue(cancelling.await())
                assertEquals("cancelled", row(response.await()).getJSONObject("error").getString("code"))
            } else assertTrue(response.isCancelled)
            assertTrue("cancel/join must discard before attachment close", File(home, "responses").listFiles()!!.isEmpty())
        } finally { assertTrue(dispatcher.closeAndJoin()) }
    }
}
