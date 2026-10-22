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
            hold?.await()
            progress(expectedBytes, expectedBytes)
        }
    }
    private class Platform : UpdatePlatform {
        var automatic = true
        var tag: String? = null
        var allowed = false
        override val packageName = LITE_PACKAGE
        override suspend fun identity() = UpdateIdentity("0.1.0-beta.1", 1, listOf("arm64-v8a"))
        override suspend fun automaticEnabled() = automatic
        override suspend fun promptedTag() = tag
        override suspend fun savePromptedTag(tag: String) { this.tag = tag }
        override suspend fun canInstall() = allowed
        override suspend fun installUri(file: File) = "content://dev.aerove.lite.updates/verified_updates/${file.name}"
    }
    private suspend fun await(feature: UpdateFeature, predicate: (UpdateState) -> Boolean) = withTimeout(10_000) { feature.state.first(predicate) }

    @Test fun feedIsLiteOnlyAndVersionCodeAndSemverMustBothAdvance() {
        val release = release()
        assertEquals(release.tag, parseLiteReleases(JSONArray().put(JSONObject().put("tag_name", "v99.0.0+999")).put(json(release))).single().tag)
        assertEquals(release, selectLiteRelease(listOf(release), identity))
        assertNull(selectLiteRelease(listOf(release.copy(version = "0.0.1")), identity))
        assertNull(selectLiteRelease(listOf(release.copy(versionCode = 1)), identity))
        assertTrue(ProductVersion.parse("0.2.0-beta.10") > ProductVersion.parse("0.2.0-beta.2"))
        assertTrue(ProductVersion.parse("0.2.0") > ProductVersion.parse("0.2.0-rc.99"))
        assertThrows(IllegalArgumentException::class.java) { ProductVersion.parse("0.2.0-beta.01") }
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
        } finally { feature.close(); scope.cancel() }
    }

    @Test fun startupOncePromptTagDismissAndDisableAreDistinct() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { platform.automatic = it }
        try {
            feature.autoCheckOnce(); await(feature) { it.phase == UpdatePhase.Available }
            assertEquals(transport.release.tag, platform.tag)
            feature.dismiss(); assertTrue(platform.automatic)
            feature.autoCheckOnce(); assertEquals(1, transport.requests)
            feature.check(); await(feature) { it.phase == UpdatePhase.Available && it.visible }
            feature.disableAutomatic(); await(feature) { !it.visible }
            assertFalse(platform.automatic)
        } finally { feature.close(); scope.cancel() }
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
        } finally { feature.close(); scope.cancel() }
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
        } finally { feature.close(); scope.cancel() }
    }

    @Test fun disabledAutomaticSkipsFeedAndPreviouslyPromptedTagOnlySuppressesAutomaticPrompt() = runBlocking<Unit> {
        val transport = Transport(); val platform = Platform().apply { automatic = false }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var feature = UpdateFeature(UpdateRepository(transport, temporary.root, UpdateApkVerifier { _, _ -> }), platform, scope) { platform.automatic = it }
        feature.autoCheckOnce()
        scope.coroutineContext[Job]!!.children.toList().flatMap { it.children.toList() }.forEach { it.join() }
        assertEquals(0, transport.requests)
        feature.close(); scope.cancel()
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
        } finally { feature.close(); scope.cancel() }
    }
}
