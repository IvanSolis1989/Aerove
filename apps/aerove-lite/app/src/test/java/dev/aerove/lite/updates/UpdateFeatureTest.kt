package dev.aerove.lite.updates

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFeatureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = UpdateIdentity("0.1.0-beta.1", 1, listOf("arm64-v8a"))
    private val bytes = "synthetic APK bytes for checksum boundary tests".toByteArray()
    private fun release(code: Long = 2) = LiteRelease("lite-v0.2.0-beta.1+$code", "0.2.0-beta.1", code, "Lite release notes",
        url("lite-v0.2.0-beta.1+$code", LITE_APK_NAME), url("lite-v0.2.0-beta.1+$code", "SHA256SUMS"),
        url("lite-v0.2.0-beta.1+$code", "BUILD_INFO.json"), bytes.size.toLong())
    private fun url(tag: String, name: String) = "https://github.com/$LITE_REPOSITORY/releases/download/$tag/$name"
    private fun json(release: LiteRelease): JSONObject = JSONObject().put("tag_name", release.tag).put("body", release.notes)
        .put("draft", false).put("assets", JSONArray(listOf(LITE_APK_NAME, "SHA256SUMS", "BUILD_INFO.json").map { name ->
            JSONObject().put("name", name).put("size", release.bytes).put("browser_download_url", url(release.tag, name))
        }))

    private inner class Transport : UpdateTransport {
        var release = release()
        var requests = 0
        var downloads = 0
        var corrupt = false
        var wrongSigner = false
        var payload = bytes
        var failCheck = false
        var entered: CompletableDeferred<Unit>? = null
        var hold: CompletableDeferred<Unit>? = null
        var nonCancellableDownload = false
        override suspend fun releases(page: Int): String {
            requests++
            if (failCheck) error("fixture network failure")
            return JSONArray().put(JSONObject().put("tag_name", "v99.0.0+999")).put(json(release)).toString()
        }
        private fun info(): ByteArray = JSONObject().put("product", "AEROVE Lite").put("application_id", LITE_PACKAGE)
            .put("version", release.version).put("version_code", release.versionCode).put("abi", "arm64-v8a")
            .put("signer_sha256", if (wrongSigner) "0".repeat(64) else LITE_SIGNER_SHA256).put("apk_sha256", digest(payload))
            .put("min_sdk", 33).put("channel", "preview").put("source", "a".repeat(40)).put("tree", "b".repeat(40))
            .put("workflow", "https://github.com/$LITE_REPOSITORY/actions/runs/123").toString().toByteArray()
        override suspend fun metadata(url: String): ByteArray = if (url.endsWith("BUILD_INFO.json")) info() else
            "${digest(payload)}  $LITE_APK_NAME\n${digest(info())}  BUILD_INFO.json\n".toByteArray()
        override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long, Long) -> Unit) {
            downloads++
            destination.writeBytes(if (corrupt) ByteArray(payload.size) else payload)
            entered?.complete(Unit)
            if (nonCancellableDownload) withContext(NonCancellable) { hold?.await() } else hold?.await()
            progress(expectedBytes, expectedBytes)
        }
    }
    private class Platform : UpdatePlatform {
        var automatic = true
        var tag: String? = null
        var allowed = false
        var failReset = false
        override val packageName = LITE_PACKAGE
        override suspend fun identity() = UpdateIdentity("0.1.0-beta.1", 1, listOf("arm64-v8a"))
        override suspend fun automaticEnabled() = automatic
        override suspend fun promptedTag() = tag
        override suspend fun savePromptedTag(tag: String) { this.tag = tag }
        override suspend fun resetPromptedTag() { check(!failReset) { "fixture prompt reset failure" }; tag = null }
        override suspend fun canInstall() = allowed
        override suspend fun installUri(file: File) = "content://dev.aerove.lite.updates/verified_updates/${file.name}"
    }
    private suspend fun await(feature: UpdateFeature, predicate: (UpdateState) -> Boolean) = withTimeout(10_000) { feature.state.first(predicate) }

    @Test fun feedIsLiteOnlyAndCodeAdvancesWithoutSemanticDowngrade() {
        val release = release()
        assertEquals(release.tag, parseLiteReleases(JSONArray().put(JSONObject().put("tag_name", "v99.0.0+999")).put(json(release))).single().tag)
        assertEquals(release, selectLiteRelease(listOf(release), identity))
        assertNull(selectLiteRelease(listOf(release.copy(version = "0.0.1")), identity))
        assertNull(selectLiteRelease(listOf(release.copy(versionCode = 1)), identity))
        assertEquals(release, selectLiteRelease(listOf(release), identity.copy(version = release.version)))
        assertNull(selectLiteRelease(listOf(release), identity.copy(version = release.version, versionCode = release.versionCode)))
        assertTrue(ProductVersion.parse("0.2.0-beta.10") > ProductVersion.parse("0.2.0-beta.2"))
        assertTrue(ProductVersion.parse("0.2.0") > ProductVersion.parse("0.2.0-rc.99"))
        assertThrows(IllegalArgumentException::class.java) { ProductVersion.parse("0.2.0-beta.01") }
    }

    @Test fun allChannelsChooseHighestUpgradeableVersionAndSkipDraftForeignOrBadAssets() {
        fun candidate(version: String, code: Long) = release(code).copy(tag = "lite-v$version+$code", version = version,
            apkUrl = url("lite-v$version+$code", LITE_APK_NAME), sumsUrl = url("lite-v$version+$code", "SHA256SUMS"),
            buildInfoUrl = url("lite-v$version+$code", "BUILD_INFO.json"))
        val beta2 = candidate("0.2.0-beta.2", 3)
        val beta10 = candidate("0.2.0-beta.10", 4)
        val rc = candidate("0.2.0-rc.1", 5)
        val stable = candidate("0.2.0", 6)
        val installed = UpdateIdentity("0.2.0-beta.1", 2, listOf("arm64-v8a"))
        assertEquals(beta10, selectLiteRelease(listOf(candidate("0.1.0", 1), beta2, beta10), installed))
        assertEquals(stable, selectLiteRelease(listOf(beta2, stable, rc, beta10), installed))
        assertNull(selectLiteRelease(listOf(rc), installed.copy(version = "0.2.0")))
        assertNull(selectLiteRelease(listOf(stable.copy(versionCode = 2)), installed))
        val draft = json(candidate("99.0.0", 99)).put("draft", true)
        val badAssets = json(candidate("98.0.0", 98)).put("assets", JSONArray())
        val foreign = json(candidate("97.0.0", 97))
        foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.test/a.apk")
        assertEquals(listOf(beta10), parseLiteReleases(JSONArray().put(draft).put(badAssets).put(foreign)
            .put(JSONObject().put("tag_name", "v99.0.0+99")).put(JSONObject().put("tag_name", "lite-vbad+3")).put(json(beta10))))
    }

    @Test fun pageFailureOrTruncationNeverClaimsCurrentAndCheckNeverDownloads() = runBlocking<Unit> {
        val source = Transport()
        var pages = 0
        var failSecond = true
        val transport = object : UpdateTransport by source {
            override suspend fun releases(page: Int): String {
                pages++
                if (page == 2 && failSecond) error("fixture second page failure")
                return JSONArray(List(100) { json(source.release) }).toString()
            }
        }
        val repo = UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> })
        assertTrue(runCatching { repo.check(identity) }.isFailure)
        assertEquals(2, pages)
        failSecond = false; pages = 0
        assertTrue(runCatching { repo.check(identity) }.isFailure)
        assertEquals(5, pages)
        assertEquals(0, source.downloads)
        repo.closeAndJoin()
    }

    @Test fun foreignHostsRepositoriesQueriesAndWrongAbiAreRejected() {
        val release = release()
        listOf(release.apkUrl.replace("github.com", "github.com.evil.test"), release.apkUrl.replace("Aerove/", "Other/"), release.apkUrl + "?token=secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { trustedAssetUrl(it, release.tag, LITE_APK_NAME) }
        }
        assertThrows(IllegalArgumentException::class.java) { selectLiteRelease(listOf(release), identity.copy(supportedAbis = listOf("x86_64"))) }
    }

    @Test fun checksumAndProvenanceAreCheckedAndFailedDownloadPreservesUsableCache() = runBlocking<Unit> {
        val transport = Transport()
        val repo = UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> })
        val ready = repo.download(transport.release) { _, _ -> }
        assertArrayEquals(bytes, ready.file.readBytes())
        assertEquals(1, transport.downloads)
        repo.download(transport.release) { _, _ -> }
        assertEquals(1, transport.downloads)
        transport.payload = "next version synthetic APK".toByteArray()
        transport.release = release(3).copy(bytes = transport.payload.size.toLong())
        transport.corrupt = true
        assertTrue(runCatching { repo.download(transport.release) { _, _ -> } }.isFailure)
        assertArrayEquals(bytes, ready.file.readBytes())
        assertTrue(temporary.root.listFiles()!!.none { it.extension == "part" })
        transport.wrongSigner = true
        assertTrue(runCatching { repo.download(transport.release) { _, _ -> } }.isFailure)
        assertEquals(2, transport.downloads)
    }

    @Test fun cancelledDownloadCleansPartAndDoesNotPublishReady() = runBlocking<Unit> {
        val transport = Transport().apply { entered = CompletableDeferred(); hold = CompletableDeferred() }
        val repo = UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> })
        val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(repo, platform, scope) { platform.automatic = it }
        try {
            feature.check(); await(feature) { it.phase == UpdatePhase.Available }
            feature.download(); withTimeout(10_000) { transport.entered!!.await() }
            feature.cancel(); transport.hold!!.complete(Unit)
            scope.coroutineContext[Job]!!.children.first().children.forEach { it.join() }
            assertFalse(feature.state.value.shouldShowPrompt)
            assertTrue(temporary.root.listFiles()!!.none { it.extension == "part" || it.extension == "apk" })
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun startupOncePromptTagDismissAndDisableAreDistinct() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { platform.automatic = it }
        try {
            feature.autoCheckOnce(); await(feature) { it.phase == UpdatePhase.Available }
            assertEquals(transport.release.tag, platform.tag)
            feature.dismiss(); assertTrue(platform.automatic)
            assertEquals(0, transport.downloads)
            assertNull(feature.state.value.installRequest)
            feature.autoCheckOnce(); assertEquals(1, transport.requests)
            feature.check(); await(feature) { it.phase == UpdatePhase.Available && it.visible }
            feature.disableAutomatic(); await(feature) { !it.visible }
            assertFalse(platform.automatic)
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun unknownSourcesRoundTripAndOldReceiptsCannotInstallAnotherRequest() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { platform.automatic = it }
        try {
            feature.check(); await(feature) { it.phase == UpdatePhase.Available }
            feature.download(); await(feature) { it.phase == UpdatePhase.Ready }
            feature.requestInstall(); val permission = await(feature) { it.installRequest != null }.installRequest!!
            assertEquals(UpdateInstallKind.UnknownSources, permission.kind)
            assertTrue(feature.platformLaunched(permission.id))
            assertFalse(feature.platformLaunched(permission.id))
            platform.allowed = true
            feature.platformResult(permission.id)
            val install = await(feature) { it.installRequest?.kind == UpdateInstallKind.Install }.installRequest!!
            assertNotEquals(permission.id, install.id)
            feature.platformResult(permission.id)
            assertEquals(install, feature.state.value.installRequest)
            assertTrue(feature.platformLaunched(install.id))
            feature.platformResult(install.id)
            assertEquals(UpdatePhase.Ready, feature.state.value.phase)
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun automaticFailureIsSilentAndManualFailureIsVisible() = runBlocking<Unit> {
        val transport = Transport().apply { failCheck = true }; val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { }
        try {
            feature.autoCheckOnce()
            withTimeout(10_000) { while (transport.requests == 0 || feature.state.value.phase == UpdatePhase.Checking) yield() }
            assertFalse(feature.state.value.shouldShowPrompt)
            feature.check(); await(feature) { it.phase == UpdatePhase.Failed }
            assertTrue(feature.state.value.shouldShowPrompt)
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun disabledAutomaticSkipsFeedAndPreviouslyPromptedTagOnlySuppressesAutomaticPrompt() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform().apply { automatic = false }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { platform.automatic = it }
        feature.autoCheckOnce()
        scope.coroutineContext[Job]!!.children.toList().flatMap { it.children.toList() }.forEach { it.join() }
        assertEquals(0, transport.requests)
        feature.closeAndJoin(); scope.cancel()
        platform.automatic = true
        platform.tag = transport.release.tag
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { platform.automatic = it }
        try {
            feature.autoCheckOnce()
            scope.coroutineContext[Job]!!.children.toList().flatMap { it.children.toList() }.forEach { it.join() }
            assertEquals(1, transport.requests)
            assertFalse(feature.state.value.shouldShowPrompt)
            feature.check(); await(feature) { it.phase == UpdatePhase.Available && it.visible }
            assertEquals(2, transport.requests)
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun committedClearJoinsDownloadClearsExactCacheAndTagAndRejectsLatePublication() = runBlocking<Unit> {
        val transport = Transport().apply { entered = CompletableDeferred(); hold = CompletableDeferred(); nonCancellableDownload = true }
        val platform = Platform().apply { tag = "old-tag" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { }
        val unknown = File(temporary.root, "unrelated.part").apply { writeText("retain") }
        val stale = File(temporary.root, "a".repeat(64) + ".apk").apply { writeText("old owned cache") }
        try {
            feature.check(); await(feature) { it.phase == UpdatePhase.Available }
            feature.download(); withTimeout(5000) { transport.entered!!.await() }
            val reset = async { feature.clearUserState() }
            yield(); assertFalse(reset.isCompleted)
            transport.hold!!.complete(Unit); withTimeout(5000) { reset.await() }
            assertEquals(UpdateState(), feature.state.value)
            assertNull(platform.tag); assertFalse(stale.exists())
            assertEquals("retain", unknown.readText())
            assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith(".download-") || it.extension == "apk" })
            val requests = transport.requests
            feature.check(); feature.autoCheckOnce(); feature.download()
            assertEquals(requests, transport.requests)
            assertTrue(runCatching { feature.resumeAfterMaintenance() }.isFailure)
        } finally { transport.hold!!.complete(Unit); feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun maintenancePausePreservesVerifiedCacheButRejectsDispatchUntilResume() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { }
        try {
            feature.check(); await(feature) { it.phase == UpdatePhase.Available }
            feature.download(); await(feature) { it.phase == UpdatePhase.Ready }
            feature.requestInstall(); val old = await(feature) { it.installRequest != null }.installRequest!!
            feature.quiesce()
            assertFalse(feature.platformLaunched(old.id))
            feature.requestInstall(); assertNull(feature.state.value.installRequest)
            assertTrue(temporary.root.listFiles()!!.any { it.extension == "apk" })
            feature.resumeAfterMaintenance()
            feature.requestInstall()
            val fresh = await(feature) { it.installRequest != null }.installRequest!!
            assertNotEquals(old.id, fresh.id)
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun secondaryPromptFailureStillClearsCacheAndKeepsFeatureTerminal() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform().apply { tag = "persisted-tag"; failReset = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> })
        val feature = UpdateFeature(repository, platform, scope) { }
        val owned = File(temporary.root, "b".repeat(64) + ".apk").apply { writeText("owned") }
        try {
            val failure = runCatching { feature.clearUserState() }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(owned.exists())
            assertEquals("persisted-tag", platform.tag)
            assertEquals(UpdateState(), feature.state.value)
            assertTrue(runCatching { feature.resumeAfterMaintenance() }.isFailure)
            assertTrue(runCatching { repository.check(identity) }.isFailure)
        } finally { feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun uninitializedCacheClearTargetsOnlyGeneratedFileNamesAndReportsDirectoryCollision() = runBlocking<Unit> {
        val owned = File(temporary.root, "c".repeat(64) + ".apk").apply { writeText("owned") }
        val partial = File(temporary.root, ".download-12345678-1234-1234-1234-123456789abc.part").apply { writeText("partial") }
        val unknown = File(temporary.root, ".download-not-a-uuid.part").apply { writeText("retain") }
        val nested = temporary.newFolder("unrelated").resolve("d".repeat(64) + ".apk").apply { writeText("retain nested") }
        UpdateRepository.clearCachedUserState(temporary.root)
        assertFalse(owned.exists()); assertFalse(partial.exists())
        assertEquals("retain", unknown.readText()); assertEquals("retain nested", nested.readText())
        val collision = temporary.newFolder("e".repeat(64) + ".apk")
        assertTrue(runCatching { UpdateRepository.clearCachedUserState(temporary.root) }.isFailure)
        assertTrue(collision.isDirectory)
    }

    @Test fun closeAfterParentCancellationIsOffCallerThreadAndHasOneJoinableOwner() = runBlocking<Unit> {
        val caller = Thread.currentThread()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val source = Transport()
        val transport = object : UpdateTransport by source {
            override fun close() {
                assertNotSame("TLS/pool cleanup must not run on the caller", caller, Thread.currentThread())
                closed.incrementAndGet(); entered.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val repository = UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(repository, Platform(), scope) { }
        try {
            scope.cancel() // ViewModelScope is already cancelled before onCleared.
            feature.close()
            withTimeout(5000) { entered.await() }
            feature.close()
            val joined = async { feature.closeAndJoin() }
            yield(); assertFalse(joined.isCompleted)
            assertTrue(runCatching { repository.check(identity) }.isFailure)
            assertEquals(0, source.requests)
            release.countDown(); withTimeout(5000) { joined.await() }
            feature.closeAndJoin(); assertEquals(1, closed.get())
        } finally { release.countDown(); feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun closeJoinsCancelledFinishingDownloadBeforeClosingItsTransport() = runBlocking<Unit> {
        val source = Transport().apply { entered = CompletableDeferred(); hold = CompletableDeferred(); nonCancellableDownload = true }
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : UpdateTransport by source {
            override fun close() {
                assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
                closed.incrementAndGet()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), Platform(), scope) { }
        try {
            feature.check(); await(feature) { it.phase == UpdatePhase.Available }
            feature.download(); withTimeout(5000) { source.entered!!.await() }
            feature.cancel() // worker is null, but the old child still owns its finally/partial file.
            feature.close()
            val joined = async { feature.closeAndJoin() }
            yield(); assertFalse(joined.isCompleted); assertEquals(0, closed.get())
            source.hold!!.complete(Unit); withTimeout(5000) { joined.await() }
            assertEquals(1, closed.get()); assertEquals(UpdateState(), feature.state.value)
        } finally { source.hold!!.complete(Unit); feature.closeAndJoin(); scope.cancel() }
    }

    @Test fun terminalCloseFailureIsObservableAndRepeatedJoinKeepsTheSameFailure() = runBlocking<Unit> {
        val failure = java.io.IOException("fixture transport shutdown")
        val observerFailure = IllegalStateException("fixture observer shutdown")
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val observed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val transport = object : UpdateTransport by Transport() {
            override fun close() { closed.incrementAndGet(); throw failure }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), Platform(), scope,
            onCloseFailure = { observed.set(it); throw observerFailure }) { }
        try {
            scope.cancel(); feature.close()
            assertSame(failure, runCatching { feature.closeAndJoin() }.exceptionOrNull())
            assertSame(failure, observed.get())
            assertTrue(failure.suppressed.any { it === observerFailure })
            assertSame(failure, runCatching { feature.closeAndJoin() }.exceptionOrNull())
            assertEquals(1, closed.get())
        } finally { runCatching { feature.closeAndJoin() }; scope.cancel() }
    }

    @Test fun clearKeepsPrimaryPromptFailureAndStillJoinsFailedTransportClose() = runBlocking<Unit> {
        val failure = java.io.IOException("fixture close failure")
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : UpdateTransport by Transport() {
            override fun close() { closed.incrementAndGet(); throw failure }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), Platform().apply { failReset = true }, scope) { }
        try {
            val error = runCatching { feature.clearUserState() }.exceptionOrNull()
            assertEquals("fixture prompt reset failure", error?.message)
            assertTrue(error!!.suppressed.any { it === failure })
            assertEquals(1, closed.get())
            assertEquals(UpdateState(), feature.state.value)
        } finally { runCatching { feature.closeAndJoin() }; scope.cancel() }
    }

}
