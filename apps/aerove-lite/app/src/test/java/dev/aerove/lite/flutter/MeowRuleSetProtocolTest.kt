package dev.aerove.lite.flutter

import dev.aerove.lite.core.ownedOfflineResourceRead
import dev.aerove.lite.data.MAX_RULE_INPUT_BYTES
import dev.aerove.lite.data.NativeJsonRulePayloadInspector
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowRuleSetProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun home() = temporary.newFolder().canonicalFile
    private fun receipt(format: String = "mrs", behavior: String = "domain", count: Int = 2) =
        """{"format":"$format","behavior":"$behavior","count":$count}"""

    @Test fun originalSharedPathProducesNativeInspectedTextFromOneImmutableSnapshot() = runBlocking<Unit> {
        val home = home()
        val source = File(home, "rules.mrs").apply { writeBytes(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 7)) }
        val original = source.readBytes()
        val expected = "+.例子.test\n+.example.test\n"
        val inspected = AtomicReference<File>()
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ path ->
            val snapshot = File(path)
            assertNotEquals(source, snapshot)
            assertTrue(snapshot.toPath().startsWith(home.toPath()))
            assertArrayEquals(original, snapshot.readBytes())
            inspected.set(snapshot)
            source.writeText("a newer source is not this request's input")
            receipt()
        }, { path, behavior, format ->
            assertEquals(inspected.get(), File(path))
            assertEquals("domain", behavior); assertEquals("mrs", format)
            assertArrayEquals(original, File(path).readBytes())
            expected
        }))
        assertEquals(expected, protocol.dump(source.path))
        assertFalse(inspected.get().exists())
        assertTrue(home.listFiles()!!.none { it.name.startsWith(".ruleset-preview-") })
        assertEquals("a newer source is not this request's input", source.readText())
    }

    @Test fun existingNativeReceiptSelectsTextYamlAndIpCidrWithoutAnotherParser() = runBlocking<Unit> {
        val home = home()
        val source = File(home, "payload").apply { writeText("opaque fixture input") }
        for ((format, behavior) in listOf("text" to "classical", "yaml" to "domain", "mrs" to "ipcidr")) {
            val text = if (behavior == "ipcidr") "192.0.2.0/24\n" else "DOMAIN,example.test\n"
            val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ receipt(format, behavior) }, { _, actualBehavior, actualFormat ->
                assertEquals(behavior, actualBehavior); assertEquals(format, actualFormat)
                text
            }))
            assertEquals(text, protocol.dump(source.path))
        }
    }

    @Test fun malformedArgumentsAndPathsNeverReachTheNativeReader() = runBlocking<Unit> {
        val home = home()
        val outside = temporary.newFile().canonicalFile
        val calls = AtomicInteger()
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ calls.incrementAndGet(); receipt() }, { _, _, _ -> error("Unexpected dump") }))
        for (arguments in listOf(null, 7, mapOf("path" to outside.path), "", "bad\u0000path")) {
            assertEquals("bad_argument", assertThrows(MeowProtocolFailure::class.java) { runBlocking { protocol.dump(arguments) } }.code)
        }
        val nested = File(home, "nested").apply { mkdir() }
        val source = File(home, "rules").apply { writeText("example.test") }
        for (path in listOf("relative.mrs", outside.path, home.path, nested.path, File(home, "missing").path,
            File(nested, "../rules").path)) {
            assertEquals("path_error", assertThrows(MeowProtocolFailure::class.java) { runBlocking { protocol.dump(path) } }.code)
        }
        assertTrue(source.exists())
        assertEquals(0, calls.get())
    }

    @Test fun symbolicLeafAndParentCannotEscapeOrAliasThePrivateHome() = runBlocking<Unit> {
        val home = home()
        val outside = temporary.newFolder().canonicalFile
        val payload = File(outside, "rules").apply { writeText("example.test") }
        val leaf = File(home, "linked-file")
        val parent = File(home, "linked-directory")
        try { Files.createSymbolicLink(leaf.toPath(), payload.toPath()) }
        catch (failure: Exception) { assumeNoException("Host cannot create test symbolic links", failure) }
        Files.createSymbolicLink(parent.toPath(), outside.toPath())
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ error("Unsafe native input") }, { _, _, _ -> error("Unsafe native dump") }))
        try {
            for (path in listOf(leaf.path, File(parent, "rules").path)) {
                assertEquals("path_error", assertThrows(MeowProtocolFailure::class.java) { runBlocking { protocol.dump(path) } }.code)
            }
        } finally { Files.deleteIfExists(leaf.toPath()); Files.deleteIfExists(parent.toPath()) }
        assertEquals("example.test", payload.readText())
    }

    @Test fun windowsJunctionCannotRedirectThePrivateReader() = runBlocking<Unit> {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val home = home()
        val outside = temporary.newFolder().canonicalFile
        val payload = File(outside, "rules").apply { writeText("example.test") }
        val link = File(home, "redirect")
        junction(link, outside)
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ error("Unsafe reader") }, { _, _, _ -> error("Unsafe dump") }))
        try {
            assertEquals("path_error", assertThrows(MeowProtocolFailure::class.java) { runBlocking { protocol.dump(File(link, payload.name).path) } }.code)
        } finally { Files.deleteIfExists(link.toPath()) }
        assertEquals("example.test", payload.readText())
    }

    @Test fun aReplacedSnapshotDirectoryCannotRedirectDumpOrCleanupToForeignFiles() = runBlocking<Unit> {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val home = home()
        val source = File(home, "rules.mrs").apply { writeText("opaque source fixture") }
        val foreign = temporary.newFolder().canonicalFile
        val foreignFile = File(foreign, "payload.bin").apply { writeText("foreign data") }
        var original: File? = null
        var moved: File? = null
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ path ->
            val directory = File(path).parentFile
            val renamed = File(home, directory.name + "-moved")
            Files.move(directory.toPath(), renamed.toPath())
            original = directory; moved = renamed
            junction(directory, foreign)
            receipt()
        }, { _, _, _ -> error("A replaced snapshot must not reach dump") }))
        try {
            assertEquals("path_error", assertThrows(MeowProtocolFailure::class.java) { runBlocking { protocol.dump(source.path) } }.code)
            assertEquals("foreign data", foreignFile.readText())
        } finally {
            original?.let { Files.deleteIfExists(it.toPath()) }
            moved?.let { Files.deleteIfExists(File(it, "payload.bin").toPath()); Files.deleteIfExists(it.toPath()) }
        }
    }

    @Test fun nativeDiagnosticsAndMalformedReceiptsNeverBecomeEmptySuccess() = runBlocking<Unit> {
        val home = home()
        val source = File(home, "broken.mrs").apply { writeText("broken magic") }
        val diagnostic = "$.payload.mrs: invalid_magic"
        val broken = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ throw IOException(diagnostic) }, { _, _, _ -> error("Unexpected dump") }))
        val error = assertThrows(MeowProtocolFailure::class.java) { runBlocking { broken.dump(source.path) } }
        assertEquals("rule_set_error", error.code); assertEquals(diagnostic, error.message)
        for (receipt in listOf("{}", receipt(count = -1), receipt(behavior = "unknown"))) {
            val malformed = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ receipt }, { _, _, _ -> error("Receipt was not rejected") }))
            assertEquals("rule_set_error", assertThrows(MeowProtocolFailure::class.java) { runBlocking { malformed.dump(source.path) } }.code)
        }
        val mismatch = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ receipt() }, { _, _, _ -> throw IOException("$.behavior: rule_set_behavior_mismatch") }))
        assertTrue(assertThrows(MeowProtocolFailure::class.java) { runBlocking { mismatch.dump(source.path) } }.message!!.contains("rule_set_behavior_mismatch"))
        assertEquals(listOf(source), home.listFiles()!!.toList())
    }

    @Test fun theExisting64MiBInputFenceRunsBeforeAnyNativeAllocation() = runBlocking<Unit> {
        val home = home()
        val source = File(home, "oversize.mrs")
        assertEquals(64L * 1024 * 1024, MAX_RULE_INPUT_BYTES)
        RandomAccessFile(source, "rw").use { it.setLength(MAX_RULE_INPUT_BYTES + 1) }
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector({ error("Oversize allocation") }, { _, _, _ -> error("Oversize dump") }))
        assertEquals("input_too_large", assertThrows(MeowProtocolFailure::class.java) { runBlocking { protocol.dump(source.path) } }.code)
        assertEquals(listOf(source), home.listFiles()!!.toList())
    }

    @Test fun cancellationDuringInspectionJoinsTheOwnedWorkerBeforeDeletingItsInput() = runBlocking<Unit> { cancelledReader("inspect") }
    @Test fun cancellationDuringDumpJoinsTheOwnedWorkerBeforeDeletingItsInput() = runBlocking<Unit> { cancelledReader("dump") }

    private suspend fun CoroutineScope.cancelledReader(blockAt: String) {
        val home = home()
        val source = File(home, "rules.mrs").apply { writeText("opaque reader fixture") }
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val snapshot = AtomicReference<File>()
        suspend fun call(stage: String, path: String, value: String): String = ownedOfflineResourceRead(
            create = { 1L }, cancel = { cancelled.complete(Unit) }, closeJoin = {
                if (stage == blockAt) {
                    assertEquals(0L, release.count)
                    assertTrue(snapshot.get().exists())
                    joined.complete(Unit)
                }
            }, work = {
                if (stage == blockAt) {
                    snapshot.set(File(path)); entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                }
                value
            },
        )
        val protocol = MeowRuleSetProtocol(home, NativeJsonRulePayloadInspector(
            { call("inspect", it, receipt()) }, { path, _, _ -> call("dump", path, "example.test\n") }))
        val pending = async { protocol.dump(source.path) }
        try {
            withTimeout(4000) { entered.await() }
            val closing = launch { pending.cancelAndJoin() }
            withTimeout(4000) { cancelled.await() }
            assertFalse(closing.isCompleted)
            assertFalse(joined.isCompleted)
            assertTrue(snapshot.get().exists())
            release.countDown()
            withTimeout(4000) { closing.join(); joined.await() }
            assertTrue(pending.isCancelled)
            assertFalse(snapshot.get().exists())
            assertEquals(listOf(source), home.listFiles()!!.toList())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    private fun junction(link: File, target: File) {
        assertTrue(link.toPath().startsWith(temporary.root.canonicalFile.toPath()))
        assertTrue(target.toPath().startsWith(temporary.root.canonicalFile.toPath()))
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.path, target.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }
}
