package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.profilesource.*
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.overrides.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryAgeTest {
    @Test fun candidateCannotOverwriteMetadataChangedAfterItsDownloadStarted() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val data = makeData(temporary.newFolder(), actualDecoder())
            val profile = data.profiles.importText("Original", simple, server.url("/original").toString())
            val before = data.profiles.documentSnapshot(profile.id)
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val facade = ProfileMaintenanceFacade(data.profiles, owner)
            try {
                server.enqueue(MockResponse().setBody(simple).setBodyDelay(1, TimeUnit.SECONDS))
                val pending = async { runCatching { facade.editCandidate(before, "Stale candidate", server.url("/candidate").toString(), false, 1234) } }
                withContext(Dispatchers.IO) { server.takeRequest() }
                val external = data.profiles.editMetadata(profile.id, "External edit", profile.url, true, 7654)
                assertTrue(pending.await().exceptionOrNull() is ProfileConflictException)
                val actual = data.profiles.documentSnapshot(profile.id)
                assertEquals(external, actual.profile)
                assertEquals(before.revision, actual.revision)
                assertEquals(before.yaml, actual.yaml)
            } finally { facade.quiesce(); facade.close(); owner.cancel() }
        }
    }

    @Test fun candidateBadUrlOrKeyPreservesCompleteOldDocumentAndPrivateIdentity() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val data = makeData(temporary.newFolder(), actualDecoder())
            identity("x25519").use { original ->
                server.enqueue(MockResponse().setBody(Buffer().write(fixture("x25519.age").readBytes())))
                val profile = data.profiles.addSubscription("Original", server.url("/original").toString(), original)
                val before = data.profiles.documentSnapshot(profile.id)
                val lineage = data.profiles.sourceLineage(profile.id)
                identity("hybrid").use { wrong ->
                    server.enqueue(MockResponse().setBody(Buffer().write(fixture("x25519.age").readBytes())))
                    assertTrue(runCatching { data.profiles.editCandidate(before, "Wrong", server.url("/wrong").toString(), false, 1234, wrong, true) }.exceptionOrNull() is AgeSourceException)
                }
                server.enqueue(MockResponse().setResponseCode(404))
                assertTrue(runCatching { data.profiles.editCandidate(before, "Bad URL", server.url("/missing").toString(), false, 1234) }.exceptionOrNull() is IOException)
                assertEquals(before, data.profiles.documentSnapshot(profile.id))
                val actualLineage = data.profiles.sourceLineage(profile.id)
                assertEquals(lineage.encrypted, actualLineage.encrypted)
                assertEquals(lineage.downloadSha256, actualLineage.downloadSha256)
                assertEquals(lineage.logicalSourceSha256, actualLineage.logicalSourceSha256)
                assertEquals(lineage.downloadedSourceSha256, actualLineage.downloadedSourceSha256)
                assertSecret(original, data.profiles.readAgeSecret(profile.id))
            }
        }
    }

    @Test fun candidateSuccessPublishesMetadataPlaintextAndPrivateKeyTogetherThroughActor() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val data = makeData(temporary.newFolder(), actualDecoder())
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var callbacks = 0
            val facade = ProfileMaintenanceFacade(data.profiles, owner, onUpdated = { summary, request ->
                assertEquals(summary.sourceIdentity, request.sourceIdentity)
                assertTrue(request.isCurrent())
                assertTrue(data.profiles.isCurrentSource(summary.id, request.sourceIdentity))
                callbacks++
            })
            try {
                identity("x25519").use { original ->
                    server.enqueue(MockResponse().setBody(Buffer().write(fixture("x25519.age").readBytes())))
                    val profile = data.profiles.addSubscription("Original", server.url("/original").toString(), original)
                    identity("hybrid").use { replacement ->
                        server.enqueue(MockResponse().setHeader("subscription-userinfo", "upload=5").setBody(Buffer().write(fixture("hybrid.age").readBytes())))
                        val saved = facade.editCandidate(data.profiles.documentSnapshot(profile.id), "Changed", server.url("/candidate").toString(), false, 3456, replacement, true)
                        val actual = makeData(data.root, actualDecoder()).profiles
                        assertEquals(saved, actual.profileSummary(profile.id))
                        assertNotEquals(profile.sourceIdentity, saved.sourceIdentity)
                        assertEquals("Changed", saved.name)
                        assertEquals(server.url("/candidate").toString(), saved.url)
                        assertFalse(saved.autoUpdate)
                        assertEquals(3456L, saved.updateIntervalMillis)
                        assertEquals("upload=5", saved.subscriptionUserInfo)
                        assertEquals(fixture("profile.yaml").readText(), actual.readConfig(profile.id))
                        assertSecret(replacement, actual.readAgeSecret(profile.id))
                        assertEquals(1, callbacks)
                        val edited = facade.editCandidate(actual.documentSnapshot(profile.id), "Only metadata", saved.url, true, 4567)
                        assertEquals(saved.sourceIdentity, edited.sourceIdentity)
                        assertEquals(1, callbacks)
                    }
                }
            } finally { facade.quiesce(); facade.close(); owner.cancel() }
        }
    }

    @Test fun cancelledCandidateJoinsItsOwnFlightAndCannotOverwriteNextCandidate() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val data = makeData(temporary.newFolder(), actualDecoder())
            val original = data.profiles.importText("Original", simple, server.url("/original").toString())
            val before = data.profiles.documentSnapshot(original.id)
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val facade = ProfileMaintenanceFacade(data.profiles, owner)
            try {
                server.enqueue(MockResponse().setBody(simple).setBodyDelay(2, TimeUnit.SECONDS))
                val caller = launch { facade.editCandidate(before, "Cancelled", server.url("/slow").toString(), false, 1234) }
                withContext(Dispatchers.IO) { server.takeRequest() }
                caller.cancelAndJoin()
                assertEquals(before, data.profiles.documentSnapshot(original.id))
                server.enqueue(MockResponse().setBody(simple))
                val saved = facade.editCandidate(before, "Latest", server.url("/latest").toString(), true, 4567)
                assertEquals(saved, data.profiles.profileSummary(original.id))
                assertEquals("Latest", saved.name)
            } finally { facade.quiesce(); facade.close(); owner.cancel() }
        }
    }

    @get:Rule val temporary = TemporaryFolder()
    @Test fun fileImportReturnsCommittedReceiptWhenDispatcherCancelsAfterIndexCommit() = runBlocking<Unit> {
        for (zipped in listOf(false, true)) {
            val root = temporary.newFolder()
            val data = makeData(root, actualDecoder())
            val ciphertext = fixture("hybrid.age").readBytes()
            val content = if (!zipped) ciphertext else ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("config.yaml")); zip.write(ciphertext); zip.closeEntry()
                }
            }.toByteArray()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    Dispatchers.Default.dispatch(context, Runnable {
                        val index = File(root, "profiles/index.json")
                        if (index.isFile && org.json.JSONObject(index.readText()).getJSONArray("profiles").length() == 1 && cancelled.compareAndSet(false, true))
                            context[Job]!!.cancel(CancellationException("Cancel committed dispatcher return"))
                        block.run()
                    })
                }
            }
            var receipt: dev.aerove.lite.ProfileSummary? = null
            var failure: Throwable? = null
            identity("hybrid").use { key ->
                val operation = launch(dispatcher) {
                    try { receipt = data.profiles.importFile("Imported", ByteArrayInputStream(content), key) }
                    catch (error: Throwable) { failure = error }
                }
                operation.join()
                assertTrue("Cancellation must hit dispatcher return", cancelled.get())
                assertNull("Already committed import must return its receipt", failure)
                val saved = checkNotNull(receipt)
                val fresh = makeData(root, actualDecoder())
                assertEquals(saved, fresh.profiles.list().single())
                assertEquals(fixture("profile.yaml").readText(), fresh.profiles.readConfig(saved.id))
                assertSecret(key, fresh.profiles.readAgeSecret(saved.id))
                if (zipped) assertTrue(File(root, "runtime/resources/imports/${saved.id}/config.yaml").isFile)
            }
            assertTrue(File(root, "runtime/resources/imports").listFiles().orEmpty().none { it.name.startsWith(".pending-") })
            assertTrue(File(root, "runtime/resources/archives").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun fileImportCancelledBeforeCommitPublishesNothingAndWipesDecodeKeyCopy() = runBlocking<Unit> {
        for (zipped in listOf(false, true)) {
            val root = temporary.newFolder()
            val entered = CompletableDeferred<Unit>()
            var privateCopy: ByteArray? = null
            val decoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, keys ->
                privateCopy = keys; entered.complete(Unit); awaitCancellation()
            })
            val data = makeData(root, decoder)
            val ciphertext = fixture("hybrid.age").readBytes()
            val content = if (!zipped) ciphertext else ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("config.yaml")); zip.write(ciphertext); zip.closeEntry()
                }
            }.toByteArray()
            identity("hybrid").use { key ->
                val operation = launch(Dispatchers.Default) { data.profiles.importFile("Cancelled", ByteArrayInputStream(content), key) }
                entered.await(); operation.cancelAndJoin()
                assertTrue(checkNotNull(privateCopy).all { it == 0.toByte() })
                assertTrue(makeData(root, decoder).profiles.list().isEmpty())
                assertTrue(File(root, "runtime/resources/imports").listFiles().orEmpty().isEmpty())
                assertTrue(File(root, "runtime/resources/archives").listFiles().orEmpty().isEmpty())
            }
        }
    }
    private val simple = "rules: ['MATCH,DIRECT']"
    private data class Data(val root: File, val profiles: ProfileRepository, val settings: LiteSettingsStore, val backup: BackupRepository)
    private fun makeData(root: File, decoder: AgeProfileSourceDecoder): Data {
        val settings = LiteSettingsStore(root)
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        val resources = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null })
        val pipeline = ProfileSourcePipeline(BuilderResourceLibrary(root), OverridePipeline(OverrideRepository(root),
            ScriptEngine { _, _ -> error("Unexpected script execution") }), settings::read)
        val profiles = ProfileRepository(root, validator, resources = resources, sourcePolicy = pipeline, sourceDecoder = decoder)
        return Data(root, profiles, settings, BackupRepository(root, profiles, settings))
    }
    private fun fixture(name: String): File {
        val root = System.getProperty("aerove.age.fixtures")?.let(::File)
        assumeTrue("Real pinned Go age fixtures are required", root?.isDirectory == true)
        return File(root!!, name).also { assertTrue(it.isFile) }
    }
    private fun identity(kind: String): AgeSecret {
        val bytes = fixture("$kind.identity").readBytes()
        try { return AgeSecret(bytes) } finally { bytes.fill(0) }
    }
    private fun actualDecoder(): AgeProfileSourceDecoder {
        val helper = System.getProperty("aerove.age.helper")?.let(::File)
        assumeTrue("Real pinned Go age adapter is required", helper?.isFile == true)
        return AgeProfileSourceDecoder(AgeDecryptTransport { cipher, identities -> withContext(Dispatchers.IO) {
            val process = ProcessBuilder(helper!!.absolutePath).start()
            try {
                process.outputStream.use { output ->
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(identities.size).array())
                    output.write(identities)
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cipher.size).array())
                    output.write(cipher)
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
    private suspend fun bytes(data: Data): ByteArray = temporary.newFile().let { data.backup.createBackup(it); it.readBytes() }
    private fun unpack(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { files ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { input -> while (true) {
            val entry = input.nextEntry ?: break
            files[entry.name] = input.readBytes()
        } }
    }
    private fun assertSecret(expected: AgeSecret, actual: AgeSecret?) {
        assertNotNull(actual)
        val left = expected.copyBytes(); val right = actual!!.use { it.copyBytes() }
        try { assertArrayEquals(left, right) } finally { left.fill(0); right.fill(0) }
    }

    @Test fun legacyBackupWithoutPrivateMetadataRemainsReadableAndDoesNotInventAKeyOrDownloadHash() = runBlocking<Unit> {
        val decoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, _ -> error("No encrypted source") })
        val source = makeData(temporary.newFolder(), decoder)
        val profile = source.profiles.importText("Legacy", simple)
        val files = unpack(bytes(source)).toMutableMap()
        val privatePath = "profiles/${profile.id}/source-private.json"
        files.remove(privatePath)
        val manifest = org.json.JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        val old = manifest.getJSONArray("files")
        manifest.put("files", org.json.JSONArray((0 until old.length()).map { old.getJSONObject(it) }.filter { it.getString("path") != privatePath }))
        files["manifest.json"] = manifest.toString().toByteArray()
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> files.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry()
        } } }.toByteArray()
        val target = makeData(temporary.newFolder(), decoder)
        val restored = target.backup.restore(ByteArrayInputStream(archive)).profileIds.getValue(profile.id)
        assertNull(target.profiles.readAgeSecret(restored))
        assertNull(target.profiles.sourceLineage(restored).downloadSha256)
        assertEquals(sha256(simple), target.profiles.sourceLineage(restored).logicalSourceSha256)
        assertEquals(simple, target.profiles.readConfig(restored))
    }

    @Test fun strictPlaintextSourcePreservesBomBytesAcrossFreshReadAndBackup() = runBlocking<Unit> {
        val decoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, _ -> error("No encrypted source") })
        val original = "\uFEFF" + simple
        val source = makeData(temporary.newFolder(), decoder)
        val profile = source.profiles.importFile("BOM", ByteArrayInputStream(original.toByteArray()))
        assertEquals(original, source.profiles.readConfig(profile.id))
        assertEquals(sha256(original), source.profiles.sourceLineage(profile.id).logicalSourceSha256)
        val target = makeData(temporary.newFolder(), decoder)
        val restored = target.backup.restore(ByteArrayInputStream(bytes(source))).profileIds.getValue(profile.id)
        assertEquals(original, target.profiles.readConfig(restored))
        assertEquals(sha256(original), target.profiles.sourceLineage(restored).logicalSourceSha256)
        target.profiles.prepareForStart(restored)
    }

    @Test fun actualX25519AndHybridBecomePlaintextWithIndependentDownloadAndCompiledIdentity() = runBlocking<Unit> {
        val decoder = actualDecoder()
        val expected = fixture("profile.yaml").readText()
        for (kind in listOf("x25519", "hybrid")) {
            val data = makeData(temporary.newFolder(kind), decoder)
            identity(kind).use { secret ->
                val source = fixture("$kind.age")
                val summary = data.profiles.importFile(kind, source.inputStream(), secret)
                assertEquals(expected, data.profiles.readConfig(summary.id))
                assertSecret(secret, data.profiles.readAgeSecret(summary.id))
                val provenance = data.profiles.sourceLineage(summary.id)
                assertTrue(provenance.encrypted)
                assertEquals(sha256(source), provenance.downloadSha256)
                assertEquals(sha256(expected), provenance.downloadedSourceSha256)
                assertEquals(sha256(expected), provenance.logicalSourceSha256)
                assertNotEquals(provenance.downloadSha256, provenance.logicalSourceSha256)
                val prepared = data.profiles.prepareForStart(summary.id)
                val manifest = org.json.JSONObject(File(prepared.resourceDirectory, "manifest.json").readText())
                assertEquals(provenance.logicalSourceSha256, manifest.getString("sourceSha256"))
                assertEquals(sha256(File(prepared.resourceDirectory, "input.yaml").readText()), manifest.getString("compiledInputSha256"))
                assertEquals(expected, prepared.source)
                val fresh = makeData(data.root, decoder)
                assertSecret(secret, fresh.profiles.readAgeSecret(summary.id))
                assertEquals(expected, fresh.profiles.readConfig(summary.id))
                assertFalse(data.profiles.snapshot().toString().contains("AGE-SECRET-KEY"))
                assertEquals("AgeSecret([redacted])", secret.toString())
            }
        }
    }

    @Test fun subscriptionRefreshUsesPrivateSavedIdentityAndWrongKeyNeverReplacesPlaintext() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            val cipher = fixture("x25519.age").readBytes()
            val expected = fixture("profile.yaml").readText()
            val data = makeData(temporary.newFolder(), actualDecoder())
            server.enqueue(MockResponse().setBody(Buffer().write(cipher)))
            val missing = runCatching { data.profiles.addSubscription("Missing", server.url("/missing").toString()) }.exceptionOrNull()
            assertEquals("secret_required", (missing as AgeSourceException).code)
            assertTrue(data.profiles.list().isEmpty())
            identity("x25519").use { secret ->
                server.enqueue(MockResponse().setHeader("subscription-userinfo", "upload=1; download=2; total=3").setBody(Buffer().write(cipher)))
                val profile = data.profiles.addSubscription("Encrypted", server.url("/profile").toString(), secret)
                val before = data.profiles.documentSnapshot(profile.id)
                server.enqueue(MockResponse().setBody(Buffer().write(cipher)))
                makeData(data.root, actualDecoder()).profiles.update(profile.id)
                val after = data.profiles.documentSnapshot(profile.id)
                assertNotEquals(before.revision, after.revision)
                assertEquals(expected, after.yaml)
                assertEquals(before.profile.sourceIdentity, after.profile.sourceIdentity)
                identity("hybrid").use { wrong -> data.profiles.setAgeSecret(profile.id, wrong) }
                val lastGood = data.profiles.documentSnapshot(profile.id)
                server.enqueue(MockResponse().setBody(Buffer().write(cipher)))
                assertTrue(runCatching { data.profiles.update(profile.id) }.exceptionOrNull() is AgeSourceException)
                assertEquals(lastGood, data.profiles.documentSnapshot(profile.id))
                data.profiles.editMetadataWithSecret(profile.id, "Corrected", profile.url, true, profile.updateIntervalMillis, secret)
                server.enqueue(MockResponse().setBody(Buffer().write(cipher)))
                data.profiles.update(profile.id)
                assertEquals(expected, data.profiles.readConfig(profile.id))
                assertSecret(secret, data.profiles.readAgeSecret(profile.id))
            }
        } finally { server.shutdown() }
    }

    @Test fun keyEditRejectsAnOlderDownloadedUpdateBeforeCommit() = runBlocking<Unit> {
        val server = MockWebServer(); val requested = CountDownLatch(1); val release = CountDownLatch(1)
        val cipher = fixture("x25519.age").readBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/blocked") { requested.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                return MockResponse().setBody(Buffer().write(cipher))
            }
        }; server.start()
        try {
            val data = makeData(temporary.newFolder(), actualDecoder())
            identity("x25519").use { secret ->
                val profile = data.profiles.addSubscription("Encrypted", server.url("/first").toString(), secret)
                data.profiles.editMetadata(profile.id, profile.name, server.url("/blocked").toString(), true, profile.updateIntervalMillis)
                val before = data.profiles.documentSnapshot(profile.id)
                val update = async(Dispatchers.Default) { runCatching { data.profiles.update(profile.id) } }
                assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
                identity("hybrid").use { replacement ->
                    data.profiles.setAgeSecret(profile.id, replacement)
                    release.countDown()
                    assertTrue(update.await().exceptionOrNull() is CancellationException)
                    assertEquals(before.revision, data.profiles.documentSnapshot(profile.id).revision)
                    assertSecret(replacement, data.profiles.readAgeSecret(profile.id))
                }
            }
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun privateKeysFollowBackupIdRemapAndRollbackWithoutEnteringManifestOrSource() = runBlocking<Unit> {
        val decoder = actualDecoder()
        val source = makeData(temporary.newFolder(), decoder)
        identity("x25519").use { sourceKey ->
            val profile = source.profiles.importFile("Secret", fixture("x25519.age").inputStream(), sourceKey)
            val archive = bytes(source)
            val files = unpack(archive)
            assertFalse(files.getValue("manifest.json").toString(Charsets.UTF_8).contains("ageIdentity"))
            assertTrue(files.containsKey("profiles/${profile.id}/source-private.json"))
            assertEquals(fixture("profile.yaml").readText(), files.getValue("profiles/${profile.id}/config.yaml").toString(Charsets.UTF_8))
            for (scope in BackupRestoreScope.entries) {
                val target = makeData(temporary.newFolder(), decoder)
                identity("hybrid").use { existingKey ->
                    val existing = target.profiles.importFile("Existing", fixture("hybrid.age").inputStream(), existingKey)
                    val result = target.backup.restore(ByteArrayInputStream(archive), scope, BackupRestoreStrategy.Compatible)
                    val mapped = result.profileIds.getValue(profile.id)
                    assertNotEquals(profile.id, mapped)
                    assertSecret(sourceKey, target.profiles.readAgeSecret(mapped))
                    assertSecret(existingKey, target.profiles.readAgeSecret(existing.id))
                    assertEquals(source.profiles.sourceLineage(profile.id).downloadSha256, target.profiles.sourceLineage(mapped).downloadSha256)
                    val index = File(target.root, "profiles/index.json"); val before = index.readBytes()
                    val failure = runCatching { atomicDomainRestore(target.root) {
                        target.profiles.setAgeSecret(mapped, existingKey)
                        assertSecret(existingKey, target.profiles.readAgeSecret(mapped))
                        throw IOException("rollback after private metadata mutation")
                    } }.exceptionOrNull()!!
                    assertTrue(rollbackVerified(failure))
                    assertArrayEquals(before, index.readBytes())
                    assertSecret(sourceKey, target.profiles.readAgeSecret(mapped))
                    val replaced = target.backup.restore(ByteArrayInputStream(archive), BackupRestoreScope.All, BackupRestoreStrategy.Override)
                    assertEquals(1, target.profiles.list().size)
                    assertSecret(sourceKey, target.profiles.readAgeSecret(replaced.profileIds.getValue(profile.id)))
                }
            }
        }
    }

    @Test fun encryptedZipRootBecomesPlaintextBeforeResourceGenerationAndKeepsKey() = runBlocking<Unit> {
        val data = makeData(temporary.newFolder(), actualDecoder())
        val archive = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("config.yaml")); zip.write(fixture("hybrid.age").readBytes()); zip.closeEntry()
        } }.toByteArray()
        identity("hybrid").use { key ->
            val profile = data.profiles.importFile("Bundle", ByteArrayInputStream(archive), key)
            assertEquals(fixture("profile.yaml").readText(), data.profiles.readConfig(profile.id))
            assertEquals(fixture("profile.yaml").readText(), File(data.root, "runtime/resources/imports/${profile.id}/config.yaml").readText())
            assertSecret(key, data.profiles.readAgeSecret(profile.id))
            assertEquals(sha256(fixture("hybrid.age")), data.profiles.sourceLineage(profile.id).downloadSha256)
        }
    }

    @Test fun realBatchKeepsSavedPlainItemWhenEncryptedItemFailsAndRemainingNetworkIsCancelled() = runBlocking<Unit> {
        val server = MockWebServer(); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/slow" -> { release.await(10, TimeUnit.SECONDS); MockResponse().setBody(simple) }
                "/encrypted" -> MockResponse().setBody("age-encryption.org/v1\nnot-a-plain-config")
                else -> MockResponse().setBody(simple)
            }
        }; server.start()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val decoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, _ -> error("No decrypt call is expected without a key") })
            val data = makeData(temporary.newFolder(), decoder)
            val plan = NamedImportPlanner().batch(listOf("plain", "encrypted", "slow").joinToString("\n") { server.url("/$it").toString() })
            val session = NamedBatchImportSession(owner, ProfileImportExecutor { data.profiles.importPlanned(it) })
            session.start(plan)
            val partial = withTimeout(5_000) { session.state.first { state ->
                state.items.any { it.status == ImportItemStatus.SUCCEEDED } && state.items.any { it.errorCode == "secret_required" }
            } }
            val saved = partial.items.single { it.status == ImportItemStatus.SUCCEEDED }
            session.cancel()
            withTimeout(5_000) { session.state.first { !it.running } }
            assertEquals(listOf(saved.id), data.profiles.list().map { it.id })
            assertEquals(simple, data.profiles.readConfig(saved.id))
            assertEquals(ImportItemStatus.SUCCEEDED, session.state.value.items.single { it.id == saved.id }.status)
        } finally { release.countDown(); owner.cancel(); server.shutdown() }
    }
}
