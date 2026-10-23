package dev.aerove.lite.data

import dev.aerove.lite.integration.ProfileImportWorkspace
import dev.aerove.lite.profilesource.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProfileImportWorkspaceTest {
    @Test fun actualEncryptedFilePromptsThenRetriesTheSameGrantAndPersistsPrivateKey() = runBlocking {
        val root = Files.createTempDirectory("import-file-key").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ProfileRepository(root, ProfileValidator {}, sourceDecoder = actualDecoder())
        var prompts = 0
        var opens = 0
        var closes = 0
        var publications = 0
        val workspace = ProfileImportWorkspace(owner, repository, { true }, { publications++ }, { if (it) prompts++ }, {}, { error("Unexpected import error") })
        try {
            val cipher = fixture("x25519.age").readBytes()
            workspace.importFile("Encrypted") {
                opens++
                object : ByteArrayInputStream(cipher) {
                    private var closed = false
                    override fun close() { if (!closed) { closes++; closed = true }; super.close() }
                }
            }
            withTimeout(5000) { workspace.state.first { it.filePrompt && !it.fileBusy } }
            assertEquals("secret_required", workspace.state.value.errorCode)
            assertTrue(repository.list().isEmpty())
            assertEquals(1, opens)
            val bytes = fixture("x25519.identity").readBytes()
            val key = try { AgeSecret(bytes) } finally { bytes.fill(0) }
            workspace.submitFileKey(key)
            withTimeout(5000) { workspace.state.first { !it.filePrompt && !it.fileBusy && !it.preparing } }
            withTimeout(5000) { while (runCatching { key.copyBytes().also { it.fill(0) } }.isSuccess) yield() }
            val saved = ProfileRepository(root, ProfileValidator {}).list().single()
            assertEquals(fixture("profile.yaml").readText(), repository.readConfig(saved.id))
            repository.readAgeSecret(saved.id)!!.use { secret ->
                val actual = secret.copyBytes()
                try { assertArrayEquals(fixture("x25519.identity").readBytes(), actual) } finally { actual.fill(0) }
            }
            assertEquals(1, prompts)
            assertEquals(2, opens)
            assertEquals(2, closes)
            assertEquals(1, publications)
        } finally { workspace.quiesce(); workspace.close(); owner.cancel(); root.deleteRecursively() }
    }

    @Test fun batchPartialSuccessAndMissingKeySurviveUserCancellation() = runBlocking {
        val root = Files.createTempDirectory("import-partial").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/encrypted" -> MockResponse().setBody("age-encryption.org/v1\nfixture")
                "/incompatible" -> MockResponse().setBody("proxies: [{name: invalid, type: fixture-unsupported}]\nrules: [MATCH,DIRECT]")
                "/slow" -> { release.await(5, TimeUnit.SECONDS); MockResponse().setBody("rules: [MATCH,DIRECT]") }
                else -> MockResponse().setBody("rules: [MATCH,DIRECT]")
            }
        }
        server.start()
        val repository = ProfileRepository(root, ProfileValidator { source ->
            if ("fixture-unsupported" in source) throw ConfigValidationException(listOf(ConfigIssue("$.proxies[0].type", "Unsupported fixture protocol")))
        }, sourceDecoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, _ -> error("Missing key must not invoke decrypt") }))
        val workspace = ProfileImportWorkspace(owner, repository, { true }, {}, {}, {}, {})
        try {
            workspace.batch(listOf("plain", "encrypted", "slow", "incompatible").joinToString("\n") { server.url("/$it").toString() })
            val partial = withTimeout(5000) { workspace.batchState.first { rows ->
                rows.items.any { it.status == ImportItemStatus.SUCCEEDED } && rows.items.any { it.errorCode == "secret_required" }
            } }
            withTimeout(5000) { workspace.state.first { it.itemIssues.values.flatten().any { issue -> issue.path == "$.proxies[0].type" } } }
            assertEquals("Unsupported fixture protocol", workspace.state.value.itemIssues.values.flatten().single().reason)
            workspace.cancel()
            workspace.quiesce()
            assertEquals(partial.items.single { it.status == ImportItemStatus.SUCCEEDED }.id, repository.list().single().id)
            assertTrue(workspace.batchState.value.items.any { it.status == ImportItemStatus.SUCCEEDED })
            assertTrue(workspace.batchState.value.items.any { it.status == ImportItemStatus.FAILED })
            assertTrue(workspace.batchState.value.items.any { it.status == ImportItemStatus.CANCELLED })
        } finally { release.countDown(); workspace.close(); owner.cancel(); server.shutdown(); root.deleteRecursively() }
    }

    @Test fun maintenanceWaitsForAnAlreadyCommittedRetiredPublicationAndRejectsNewKeys() = runBlocking<Unit> {
        val root = Files.createTempDirectory("import-tail").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = ProfileRepository(root, ProfileValidator {})
        val workspace = ProfileImportWorkspace(owner, repository, { true }, {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }, {}, {}, {})
        try {
            workspace.single(NamedProfileInput("First", "rules: [MATCH,DIRECT]"))
            withTimeout(5000) { entered.await() }
            workspace.cancel()
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { workspace.quiesce() }
            assertFalse(stopped.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { stopped.await() }
            val count = repository.list().size
            val refused = AgeSecret("private-fixture".toByteArray())
            workspace.single(NamedProfileInput("Rejected", "https://example.invalid", refused))
            assertTrue(runCatching { refused.copyBytes() }.isFailure)
            assertEquals(count, repository.list().size)
            var resumed = false
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                workspace.resumeAfterMaintenance()
                resumed = true
            }.join()
            assertTrue(resumed)
            workspace.single(NamedProfileInput("After maintenance", "rules: [MATCH,DIRECT]"))
            withTimeout(5000) { workspace.batchState.first { it.items.any { row -> row.name == "After maintenance" && row.status == ImportItemStatus.SUCCEEDED } } }
        } finally { release.complete(Unit); workspace.close(); owner.cancel(); root.deleteRecursively() }
    }

    private fun fixture(name: String) = File(checkNotNull(System.getProperty("aerove.age.fixtures")), name).also { check(it.isFile) }
    private fun actualDecoder() = AgeProfileSourceDecoder(AgeDecryptTransport { cipher, keys -> withContext(Dispatchers.IO) {
        val helper = File(checkNotNull(System.getProperty("aerove.age.helper"))).also { check(it.isFile) }
        val process = ProcessBuilder(helper.absolutePath).start()
        try {
            process.outputStream.use { output ->
                for (bytes in listOf(keys, cipher)) {
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
                    output.write(bytes)
                }
            }
            val result = CompletableFuture.supplyAsync {
                DataInputStream(process.inputStream).use { input ->
                    val size = Integer.reverseBytes(input.readInt())
                    require(size in 1..(MAX_PLAINTEXT_BYTES + 128))
                    ByteArray(size).also(input::readFully)
                }
            }.get(12, TimeUnit.SECONDS)
            try {
                check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
                if (result[0].toInt() != 0) throw AgeSourceException(result.copyOfRange(1, result.size).toString(Charsets.UTF_8))
                result.copyOfRange(1, result.size)
            } finally { result.fill(0) }
        } finally {
            process.outputStream.close(); process.inputStream.close(); process.errorStream.close()
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(2, TimeUnit.SECONDS))
        }
    } })
}
