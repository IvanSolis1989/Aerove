package dev.aerove.lite.flutter

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowProtocolDispatcherTest {
    private fun call(id: String, method: String, arguments: Any? = null) = JSONObject()
        .put("id", id).put("method", method).put("arguments", arguments ?: JSONObject.NULL).toString()
    private fun row(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun error(bytes: ByteArray) = row(bytes).getJSONObject("error").getString("code")
    private fun home() = Files.createTempDirectory("meow-protocol-").toFile()

    @Test fun methodInventoryMatchesTheActualSharedEnum() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "packages/aerove_client/lib/core/method.dart").isFile }
        val enum = File(root, "packages/aerove_client/lib/core/method.dart").readText()
            .substringAfter("enum CoreMethod {").substringBefore("}")
        val names = Regex("(?m)^\\s*([A-Za-z][A-Za-z0-9]*)\\s*,").findAll(enum).map { it.groupValues[1] }.toSet()
        assertEquals(55, names.size)
        assertEquals(names, MeowProtocolDispatcher.METHODS)
    }

    @Test fun realJsonWirePreservesIdentityNumbersAndNulls() = runBlocking {
        val home = home()
        val dispatcher = MeowProtocolDispatcher(home, mapOf("getProxies" to MeowProtocolMethod { it }))
        try {
            val result = row(dispatcher.invoke(call("a", "getProxies", JSONObject()
                .put("up", 9_000_000_000_000_001L).put("nullable", JSONObject.NULL).put("enabled", false))))
            assertEquals("a", result.getString("id"))
            assertFalse(result.has("error"))
            assertEquals(9_000_000_000_000_001L, result.getJSONObject("result").getLong("up"))
            assertTrue(result.getJSONObject("result").isNull("nullable"))
            assertFalse(result.getJSONObject("result").getBoolean("enabled"))
        } finally { dispatcher.closeAndJoin(); home.deleteRecursively() }
    }

    @Test fun errorsRemainExplicitAndUnknownMethodsEchoAnExistingValidId() = runBlocking {
        val home = home()
        val dispatcher = MeowProtocolDispatcher(home, mapOf("validateConfig" to MeowProtocolMethod {
            throw MeowConfigProtocolException("field_invalid", "$.dns requires an object", mapOf("field" to "dns"))
        }))
        try {
            val unknown = row(dispatcher.invoke(call("unknown", "invented")))
            assertEquals("unknown", unknown.getString("id"))
            assertEquals("bad_method", unknown.getJSONObject("error").getString("code"))
            assertEquals("implementation_missing", error(dispatcher.invoke(call("missing", "getConfig"))))
            assertEquals("bad_method", error(dispatcher.invoke(call("event", "message"))))
            assertEquals("bad_request", error(dispatcher.invoke("[1,2]")))
            val diagnostic = row(dispatcher.invoke(call("validate", "validateConfig"))).getJSONObject("error")
            assertEquals("field_invalid", diagnostic.getString("code"))
            assertEquals("dns", diagnostic.getJSONObject("details").getString("field"))
            assertTrue(dispatcher.missingMethods.contains("getConfig"))
            assertFalse(dispatcher.missingMethods.contains("message"))
        } finally { dispatcher.closeAndJoin(); home.deleteRecursively() }
    }

    @Test fun duplicateIdCannotReplaceOrCancelTheOriginalOperation() = runBlocking {
        val home = home()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val dispatcher = MeowProtocolDispatcher(home, mapOf("getProxies" to MeowProtocolMethod {
            calls.incrementAndGet(); entered.complete(Unit); release.await(); "original"
        }))
        try {
            val original = async { dispatcher.invoke(call("same", "getProxies")) }
            withTimeout(5_000) { entered.await() }
            assertEquals("duplicate_call", error(dispatcher.invoke(call("same", "getProxies"))))
            assertEquals(1, calls.get())
            release.complete(Unit)
            assertEquals("original", row(original.await()).getString("result"))
        } finally { dispatcher.closeAndJoin(); home.deleteRecursively() }
    }

    @Test fun cancellationAcknowledgementWaitsForActualOperationCleanup() = runBlocking {
        val home = home()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val dispatcher = MeowProtocolDispatcher(home, mapOf("getProxies" to MeowProtocolMethod {
            entered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(30); cleaned = true } }
        }))
        try {
            val original = async { dispatcher.invoke(call("cancel", "getProxies")) }
            withTimeout(5_000) { entered.await() }
            assertTrue(dispatcher.cancel("cancel"))
            assertTrue(cleaned)
            assertEquals("cancelled", error(original.await()))
            assertTrue(dispatcher.cancel("already-finished"))
        } finally { dispatcher.closeAndJoin(); home.deleteRecursively() }
    }

    @Test fun aCancellationDeadlineNeverClaimsAnUnconfirmedOperationJoined() = runBlocking {
        val home = home()
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val dispatcher = MeowProtocolDispatcher(home, mapOf("getProxies" to MeowProtocolMethod {
            entered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }))
        try {
            val original = async { dispatcher.invoke(call("slow", "getProxies")) }
            withTimeout(5_000) { entered.await() }
            assertFalse(dispatcher.cancel("slow", budgetMillis = 10))
            cleanup.complete(Unit)
            assertEquals("cancelled", error(original.await()))
            assertTrue(dispatcher.closeAndJoin())
        } finally { cleanup.complete(Unit); dispatcher.closeAndJoin(); home.deleteRecursively() }
    }

    @Test fun largeEnvelopeUsesTheSamePrivateResponseDirectoryAndCloseKeepsForeignFiles() = runBlocking {
        val home = home()
        val expected = "large payload ".repeat(10_000)
        val dispatcher = MeowProtocolDispatcher(home, mapOf("getProxies" to MeowProtocolMethod { expected }))
        try {
            val envelope = row(dispatcher.invoke(call("large", "getProxies")))
            assertEquals("large", envelope.getString("id"))
            assertTrue(envelope.isNull("result"))
            val file = File(envelope.getString("file"))
            assertEquals(File(home, "responses"), file.parentFile)
            assertEquals(expected, JSONObject(file.readText()).getString("result"))
            val foreign = File(file.parentFile, "other-owner.json").apply { writeText("foreign") }
            assertTrue(dispatcher.closeAndJoin())
            assertFalse(file.exists())
            assertEquals("foreign", foreign.readText())
            assertEquals("closed", error(dispatcher.invoke(call("after-close", "getProxies"))))
        } finally { dispatcher.closeAndJoin(); home.deleteRecursively() }
    }

    @Test fun callerCancellationCancelsAndJoinsItsChildBeforeTheIdCanBeReused() = runBlocking {
        val home = home()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val calls = AtomicInteger()
        val dispatcher = MeowProtocolDispatcher(home, mapOf("getProxies" to MeowProtocolMethod {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            } else "replacement"
        }))
        try {
            val original = async { dispatcher.invoke(call("owner", "getProxies")) }
            withTimeout(5_000) { entered.await() }
            original.cancelAndJoin()
            assertTrue(cleaned)
            assertEquals("replacement", row(dispatcher.invoke(call("owner", "getProxies"))).getString("result"))
        } finally { dispatcher.closeAndJoin(); home.deleteRecursively() }
    }
}
