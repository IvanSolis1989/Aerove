package dev.aerove.lite.overrides

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.*
import dev.aerove.lite.core.JniResourceFetch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowRuntimeConfigComposer
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.UserProxyPorts
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartConfigMeowScriptAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val base get() = requireNotNull(InstrumentationRegistry.getArguments().getString("smartConfigFixtureUrl")) {
        "A pinned smartConfigFixtureUrl fixture mirror is required"
    }.trimEnd('/').plus('/').toHttpUrl()

    private fun fetch(path: String, maximum: Int): ByteArray {
        require(!path.startsWith('/') && path.split('/').none { it == ".." })
        return http.newCall(Request.Builder().url(checkNotNull(base.resolve(path))).build()).execute().use { response ->
            check(response.isSuccessful) { "Fixture HTTP status ${response.code}" }
            val body = checkNotNull(response.body)
            check(body.contentLength() <= maximum)
            body.byteStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= maximum) { "Fixture byte limit" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun fixture(): Pair<JSONObject, String> {
        val manifestBytes = fetch("manifest.json", 512 * 1024)
        assertEquals(MANIFEST_SHA, sha(manifestBytes))
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        assertEquals(COMMIT, manifest.getString("smartCommit"))
        val spec = manifest.getJSONObject("script")
        val script = fetch(spec.getString("file"), 4 * 1024 * 1024)
        assertEquals(spec.getInt("bytes"), script.size)
        assertEquals(SCRIPT_SHA, sha(script))
        val bundled = context.assets.open(BundledMeowScript.ASSET).use { it.readBytes() }
        assertEquals(COMMIT, BundledMeowScript.COMMIT)
        assertEquals(SCRIPT_SHA, BundledMeowScript.SHA256)
        assertEquals(BundledMeowScript.BYTES, bundled.size)
        assertEquals(SCRIPT_SHA, sha(bundled))
        assertArrayEquals("Installed bundled script must equal the current fixed fixture", script, bundled)
        return manifest to bundled.toString(Charsets.UTF_8)
    }

    private fun input(): MutableMap<String, Any?> = linkedMapOf(
        "proxies" to listOf("香港 HKG 家宽", "台湾 TWN 家宽", "狮城 SGP 家宽", "日本 JPN 家宽",
            "美国 USA 家宽", "德国 DEU 家宽", "巴西 BRA 家宽", "南非 ZAF 家宽", "月球 ZZZ 家宽").mapIndexed { index, name ->
            linkedMapOf("name" to name, "type" to "ss", "server" to "192.0.2.${index + 1}", "port" to 443,
                "cipher" to "aes-128-gcm", "password" to "synthetic-only")
        },
        "proxy-groups" to listOf(mapOf("name" to "legacy", "type" to "select", "proxies" to listOf("DIRECT"))),
        "rule-providers" to mapOf("legacy" to mapOf("type" to "inline", "behavior" to "domain", "payload" to listOf("old.example"))),
        "rules" to listOf("MATCH,legacy"),
        "dns" to mapOf("enable" to true, "nameserver" to listOf("1.1.1.1")),
        "hosts" to mapOf("fixture.example" to listOf("192.0.2.40")),
        "sniffer" to mapOf("enable" to false),
        "listeners" to listOf(mapOf("name" to "fixture-socks", "type" to "socks5", "listen" to "127.0.0.1", "port" to 19091)),
        "tun" to mapOf("enable" to true, "mtu" to 1480, "dns-hijack" to listOf("any:53")),
        "mixed-port" to 7893, "mode" to "global", "ipv6" to true,
        "external-controller" to "127.0.0.1:19090", "secret" to "synthetic-source-secret",
    )

    private suspend fun repository(root: File, script: String): OverrideRepository = OverrideRepository(root).also {
        it.putScript(ScriptResource("pinned-smart", "Pinned Smart fixture", content = script, updatedAt = 0))
        it.setProfile(ProfileOverride("fixture-profile", OverrideMode.Script, "pinned-smart"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun realQuickJsRepositoryComposerAndAllPinnedRulePayloadsReachNativeValidation() = runBlocking<Unit> {
        val root = File(context.filesDir, "runtime/smart-script-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val (manifest, script) = fixture()
            val source = ConfigYaml.dump(input())
            val before = ConfigYaml.parse(source)
            val repository = repository(root, script)
            val stored = repository.snapshot()
            val transformed = OverridePipeline(repository, NativeScriptEngine()).resolve("fixture-profile", source)
            assertEquals(transformed, OverridePipeline(repository, NativeScriptEngine()).resolve("fixture-profile", source))
            assertEquals(stored, repository.snapshot())
            assertEquals(before, ConfigYaml.parse(source))
            val output = ConfigYaml.parse(transformed.yaml)
            for (key in listOf("dns", "hosts", "sniffer", "tun", "mixed-port", "mode", "ipv6", "external-controller", "secret", "listeners"))
                assertEquals("Preserved App field: $key", before[key], output[key])
            val expected = manifest.getJSONObject("expected")
            val groups = output["proxy-groups"] as List<Map<String, Any?>>
            val rules = output["rules"] as List<String>
            val providers = output["rule-providers"] as Map<String, Map<String, Any?>>
            assertEquals(expected.getInt("providerCount"), providers.size)
            assertEquals(expected.getInt("ruleCount"), rules.size)
            assertEquals(expected.getInt("businessCount"), groups.count { it["type"] == "select" })
            assertEquals(expected.getInt("regionCount"), groups.count { it["type"] == "url-test" })
            assertEquals(expected.getJSONArray("groupOrder").let { a -> List(a.length()) { a.getString(it) } }, groups.map { it["name"] })
            assertEquals(expected.getJSONArray("rules").let { a -> List(a.length()) { a.getString(it) } }, rules)
            val names = groups.map { it["name"] as String }.toSet() +
                (output["proxies"] as List<Map<String, Any?>>).map { it["name"] as String } + setOf("DIRECT", "REJECT")
            assertEquals(groups.size, groups.map { it["name"] }.toSet().size)
            groups.forEach { group -> (group["proxies"] as List<String>).forEach { assertTrue("Group member $it", it in names) } }
            rules.filter { it.startsWith("RULE-SET,") }.forEach { assertTrue(it.split(',')[1] in providers) }
            // Explicit private overrides retain the fixed script HTTP URL and named proxy policy.
            val payloads = manifest.getJSONArray("providers")
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { zip ->
                fun entry(path: String, data: ByteArray) { zip.putNextEntry(ZipEntry(path.removePrefix("./"))); zip.write(data); zip.closeEntry() }
                entry("config.yaml", source.toByteArray())
                var bytes = manifest.getJSONObject("script").getLong("bytes")
                repeat(payloads.length()) { index ->
                    val spec = payloads.getJSONObject(index)
                    val provider = checkNotNull(providers[spec.getString("name")])
                    assertEquals(spec.getString("sourceUrl"), provider["url"])
                    assertEquals(spec.getString("runtimePath"), provider["path"])
                    assertEquals("🚫 受限网站", provider["proxy"])
                    val body = fetch(spec.getString("file"), 64 * 1024 * 1024)
                    assertEquals(spec.getInt("bytes"), body.size)
                    assertEquals(spec.getString("sha256"), sha(body))
                    bytes += body.size
                    File(root, spec.getString("file")).apply { parentFile!!.mkdirs(); writeBytes(body) }
                }
                assertEquals(manifest.getLong("totalBytes"), bytes)
                val geo = manifest.getJSONArray("geo")
                assertEquals(3, geo.length())
                repeat(geo.length()) { index ->
                    val spec = geo.getJSONObject(index)
                    val body = fetch(spec.getString("file"), 64 * 1024 * 1024)
                    assertEquals(spec.getInt("bytes"), body.size)
                    assertEquals(spec.getString("sha256"), sha(body))
                    entry(spec.getString("file"), body)
                }
            }
            val runtime = File(context.filesDir, "runtime")
            val nativePrepared = JniResourceFetch.preparedValidator(context)
            var finalNativeCalls = 0
            var pendingPreflights = 0
            val validator = CompatibilityValidator.forMeow(NativeConfigValidator { yaml ->
                JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) }
            }, runtime,
                NativePreparedConfigValidator { yaml, readRoot ->
                    finalNativeCalls++
                    nativePrepared.validate(yaml, readRoot)
                }, NativePreparedRuleValidator { path, behavior, format ->
                    RuleResourcePreflight.parse(JniResourceFetch.preflightRuleResource(path, behavior, format), behavior, format).also {
                        if (it.requiresPreparedContext) pendingPreflights++
                    }
                }, MeowNativeCore::readVerifiedFeatures)
            val manager = ResourceBundleManager(runtime, validator, NativeResourceValidator { kind, path, behavior, format ->
                JniResourceFetch.offlineRead { MeowNativeCore.validateResource(it, kind, path, behavior, format) }
            },
                providerDownloads = JniResourceFetch.factory(context), downloader = ResourceDownloader())
            val pipeline = OverridePipeline(repository, NativeScriptEngine())
            var admission: String? = null
            var stale = false
            val sourcePolicy = object : ProfileSourcePolicy {
                override suspend fun forStart(profileId: String, logicalOriginalSource: String): ProfileSourceTransform {
                    admission?.let { throw CancellationException(it) }
                    val transformed = pipeline.resolve(profileId, logicalOriginalSource)
                    val fileInput = ConfigYaml.parse(transformed.yaml)
                    val geo = manifest.getJSONArray("geo")
                    repeat(geo.length()) { index ->
                        val spec = geo.getJSONObject(index)
                        fileInput[spec.getString("key")] = spec.getString("file")
                    }
                    return ProfileSourceTransform(ConfigYaml.dump(fileInput), transformed.fingerprint,
                        isCurrent = { !stale && pipeline.fingerprint(profileId, logicalOriginalSource) == transformed.fingerprint })
                }
            }
            val profiles = ProfileRepository(root, validator, resources = manager, sourcePolicy = sourcePolicy)
            val imported = profiles.importFile("Pinned real resources with explicit private overrides", archive.toByteArray().inputStream())
            repository.setProfile(ProfileOverride(imported.id, OverrideMode.Script, "pinned-smart"))
            suspend fun withPinnedOverrides(index: Int): PreparedProfile {
                if (index == payloads.length()) return profiles.prepareForStart(imported.id)
                val spec = payloads.getJSONObject(index)
                val key = "$.rule-providers.${spec.getString("name")}"
                val provider = checkNotNull(providers[spec.getString("name")])
                // Exact production source identity, followed by its real ProviderFetchPolicy identity.
                val sourceId = sha(org.json.JSONArray(listOf(key, "ruleset", provider["url"], provider["behavior"], provider["format"])).toString().toByteArray())
                val policy = checkNotNull(ProviderFetchPolicy.from(provider))
                val identity = sha((sourceId + "\n" + policy.identity()).toByteArray())
                return manager.withResourceOverride(imported.id, setOf(key),
                    File(root, spec.getString("file")).inputStream(), identity) { withPinnedOverrides(index + 1) }
            }
            val beforePreparedGate = finalNativeCalls
            val beforeStart = withPinnedOverrides(0)
            assertTrue("Prepared conversion must call real JNI", finalNativeCalls > beforePreparedGate)
            assertTrue("Geo rule preparation must remain pending until final conversion", pendingPreflights > 0)
            assertEquals(source, profiles.readConfig(imported.id))
            val prepared = profiles.prepareForStart(imported.id)
            assertEquals(beforeStart.revision, prepared.revision)
            assertEquals(beforeStart.generation, prepared.generation)
            val sealed = checkNotNull(prepared.resourceDirectory)
            assertTrue(sealed.canonicalPath.startsWith(File(runtime, "resources/bundles").canonicalPath + File.separator))
            assertFalse(sealed.name.startsWith(".pending-"))
            val receipt = JSONObject(File(sealed, "manifest.json").readText()).getJSONArray("records")
            repeat(payloads.length()) { index ->
                val spec = payloads.getJSONObject(index)
                val row = (0 until receipt.length()).map(receipt::getJSONObject)
                    .single { it.getString("key") == "$.rule-providers.${spec.getString("name")}" }
                assertEquals(spec.getString("sha256"), row.getString("sha256"))
                assertEquals("private-override", row.getString("transportOrigin"))
                assertEquals(spec.getString("sourceUrl"), row.getString("url"))
                val body = File(sealed, row.getString("file")).readBytes()
                assertEquals(spec.getString("sha256"), sha(body))
            }
            val cache = File(runtime, "meow-cache/test-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val composer = MeowRuntimeConfigComposer(runtime, "AEROVE-Smart-fixture/2", cache)
            val settings = LiteSettings(mode = ProxyMode.Global, ipv6 = true,
                network = NetworkSettings(ports = UserProxyPorts(mixed = 7893), mtu = 1480, dnsHijacking = true))
            val plan = composer.compose(prepared, settings, -1, 49152, PRIVATE_SECRET,
                initiallyParked = true, verifiedNativeFeatures = MeowNativeCore.readVerifiedFeatures())
            val engine = ConfigYaml.parse(plan.engineYaml)
            assertEquals(rules, engine["rules"])
            for (key in listOf("hosts", "sniffer")) assertEquals(output[key], engine[key])
            val preparedInput = ConfigYaml.parse(prepared.yaml)
            for (key in listOf("dns", "hosts", "sniffer", "tun", "listeners", "mixed-port", "mode", "ipv6", "external-controller", "secret"))
                assertEquals("Prepared host field: $key", output[key], preparedInput[key])
            assertEquals("global", engine["mode"])
            val json = JSONObject(plan.planJson())
            assertEquals(PRIVATE_SECRET, json.getJSONObject("privateControl").getString("secret"))
            assertEquals(1480, json.getJSONObject("tun").getInt("mtu"))
            assertEquals(7893, ConfigYaml.parse(json.getJSONObject("inbounds").getString("configYaml"))["mixed-port"])
            assertTrue(json.toString().contains("fixture-socks"))
            assertNull("Final full native conversion with actual sealed Geo", nativePrepared.validate(plan.engineYaml, sealed))
            val lease = profiles.retain(prepared)
            try {
                profiles.markUsable(prepared)
                admission = "fixture cancellation before source preparation"
                try { profiles.prepareForStart(imported.id); fail("Cancellation was ignored") }
                catch (_: CancellationException) { }
                admission = null
                assertEquals(prepared.revision, profiles.prepareForStart(imported.id).revision)
                stale = true
                try { profiles.prepareForStart(imported.id); fail("Stale policy was accepted") }
                catch (_: IllegalStateException) { }
                stale = false
                repository.putScript(ScriptResource("pinned-smart", "Failure", content = "function main(x) { throw new Error('fixture'); }", updatedAt = 1))
                try { profiles.prepareForStart(imported.id); fail("Script failure was ignored") }
                catch (_: ScriptExecutionException) { }
                assertEquals(source, profiles.readConfig(imported.id))
                assertTrue(sealed.isDirectory)
                repository.putScript(ScriptResource("pinned-smart", "Restored", content = script, updatedAt = 2))
                assertEquals(prepared.revision, profiles.prepareForStart(imported.id).revision)
            } finally { profiles.release(lease) }
            profiles.delete(imported.id)
            profiles.collectGarbage()
            cache.deleteRecursively()
            val invalid = output.toMutableMap().apply { put("firewall", true) }
            try {
                composer.compose(ConfigYaml.dump(invalid), settings, -1, 49152, PRIVATE_SECRET,
                    initiallyParked = true, verifiedNativeFeatures = MeowNativeCore.readVerifiedFeatures())
                fail("Unsupported host field was silently ignored")
            } catch (error: ConfigValidationException) { assertEquals("$.firewall", error.issues.single().path) }
        } finally { root.deleteRecursively() }
    }

    @Test fun realQuickJsPreservesInputAndScriptFailuresCannotCommitPartialOverrides() = runBlocking<Unit> {
        val root = File(context.cacheDir, "smart-script-errors-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val (_, script) = fixture()
            val repository = repository(root, script)
            val engine = NativeScriptEngine()
            val guarded = script + """

                const pinnedMain = main;
                main = function(input) {
                    const before = JSON.stringify(input);
                    let output, failed = false;
                    try { output = pinnedMain(input); } catch (_) { failed = true; }
                    if (JSON.stringify(input) !== before) throw new Error('fixture input mutation');
                    return {fixtureFailed: failed, fixtureSourceUnchanged: true, output: output};
                };
            """.trimIndent()
            val original = input()
            val positive = JSONObject(engine.evaluate(guarded, JSONObject(original).toString()))
            assertFalse(positive.getBoolean("fixtureFailed")); assertTrue(positive.getBoolean("fixtureSourceUnchanged"))
            val unresolved = input().apply { put("proxy-providers", mapOf("airport" to mapOf("type" to "http", "url" to "https://example.invalid/private.yaml"))) }
            val dependent = input().apply { put("dns", mapOf("nameserver-policy" to mapOf("rule-set:legacy" to listOf("1.1.1.1")))) }
            for (bad in listOf(unresolved, dependent)) {
                val source = ConfigYaml.dump(bad)
                val stored = repository.snapshot()
                try { OverridePipeline(repository, engine).resolve("fixture-profile", source); fail("Incompatible source was accepted") }
                catch (error: ScriptExecutionException) { assertEquals("script_error", error.code) }
                assertEquals(stored, repository.snapshot())
                assertEquals(bad, ConfigYaml.parse(source))
                val guardedError = JSONObject(engine.evaluate(guarded, JSONObject(bad).toString()))
                assertTrue(guardedError.getBoolean("fixtureFailed")); assertTrue(guardedError.getBoolean("fixtureSourceUnchanged"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun realJniNamedHttpProxyAndCancellationKeepWritersOwned() = runBlocking<Unit> {
        val expected = "payload:\n  - fixture.example\n".toByteArray()
        val fixture = ControlledConnectFixture(expected)
        val privateRoot = context.filesDir.canonicalFile
        val directory = File(privateRoot, "runtime/transport-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val candidate = ConfigYaml.dump(linkedMapOf(
            "proxies" to listOf(mapOf("name" to "fixture-proxy", "type" to "http", "server" to "127.0.0.1", "port" to fixture.proxy.localPort)),
            "proxy-groups" to listOf(mapOf("name" to "named-route", "type" to "select", "proxies" to listOf("fixture-proxy"))),
            "rules" to listOf("MATCH,DIRECT"), "dns" to mapOf("enable" to true, "nameserver" to listOf("1.1.1.1"))
        ))
        var ownedScope: ProviderDownloadScope? = null
        try {
            assertEquals(directory.absoluteFile, directory.canonicalFile)
            assertTrue(directory.toPath().startsWith(privateRoot.toPath()))
            val alias = File(directory, "../${directory.name}")
            try {
                JniResourceFetch.factory(context).open(ProviderDownloadContext(candidate, alias, null)).closeAndJoin()
                fail("Noncanonical staging alias bypassed the private-root guard")
            } catch (error: IllegalArgumentException) {
                assertEquals("Resource directory must be a canonical app-private child", error.message)
            }
            assertEquals(0, fixture.originConnections.get())
            assertTrue(fixture.observed.isEmpty())
            val scope = JniResourceFetch.factory(context).open(ProviderDownloadContext(candidate, directory, null))
            ownedScope = scope
            assertArrayEquals(expected, fixture.probeDirectOrigin())
            assertEquals("GET /probe HTTP/1.1", fixture.originRequests.poll(5, TimeUnit.SECONDS))
            assertEquals(1, fixture.originConnections.get())
            val destination = File(directory, "download.bin")
            val result = scope.download(ProviderDownloadRequest("http://${fixture.authority}/payload", "named-route", emptyMap(), destination, 1024))
            assertEquals("CONNECT ${fixture.authority} HTTP/1.1", fixture.observed.poll(5, TimeUnit.SECONDS))
            assertEquals("GET /payload HTTP/1.1", fixture.observed.poll(5, TimeUnit.SECONDS))
            assertEquals(1, fixture.originConnections.get())
            assertEquals(expected.size.toLong(), result.bytes)
            assertEquals(sha(expected), result.sha256)
            assertArrayEquals(expected, destination.readBytes())
            try {
                scope.download(ProviderDownloadRequest("http://${fixture.authority}/negative", "absent-route", emptyMap(), File(directory, "missing.bin"), 1024))
                fail("Unknown named route silently fell back")
            } catch (error: RuntimeException) {
                assertEquals(RuntimeException::class.java, error.javaClass)
                assertEquals("Rust error: proxy: download proxy 'absent-route' is not a known proxy or group", error.message)
            }
            assertFalse(File(directory, "missing.bin").exists())
            assertEquals("Unknown route must not reach the directly accessible origin", 1, fixture.originConnections.get())
            assertTrue(fixture.originRequests.isEmpty())
            assertTrue(fixture.observed.isEmpty())
            kotlinx.coroutines.coroutineScope {
                val running = async(kotlinx.coroutines.Dispatchers.IO) {
                    scope.download(ProviderDownloadRequest("http://${fixture.authority}/blocked", "named-route", emptyMap(), File(directory, "cancelled.bin"), 1024))
                }
                assertEquals("CONNECT ${fixture.authority} HTTP/1.1", fixture.observed.poll(5, TimeUnit.SECONDS))
                assertEquals("GET /blocked HTTP/1.1", fixture.observed.poll(5, TimeUnit.SECONDS))
                running.cancel()
                try { running.await(); fail("Cancelled transport returned success") } catch (_: CancellationException) { }
                scope.closeAndJoin()
                val length = File(directory, "cancelled.bin").takeIf(File::exists)?.length()
                fixture.release.countDown()
                fixture.proxyWorker.join(5000)
                assertFalse(fixture.proxyWorker.isAlive)
                assertEquals(length, File(directory, "cancelled.bin").takeIf(File::exists)?.length())
            }
            fixture.checkHealthy()
        } finally {
            try { ownedScope?.closeAndJoin() } finally {
                fixture.close()
                directory.deleteRecursively()
            }
        }
    }
    companion object {
        private const val COMMIT = "dba12215fe8f8d139a94fbd2d5b981282fbcf9c7"
        private const val SCRIPT_SHA = "c80118f8fad6f86ab17a6e2b2ecd9ed8dfe5e15ea27ab6f6d72e8a1b6b9a71a8"
        private const val MANIFEST_SHA = "234442db6cd3be995e4d5b323f50a360ff2c846b37e9cbd1e742b3f7833ab108"
        private const val PRIVATE_SECRET = "0123456789abcdef0123456789abcdef"
    }
}

// HOST_PROTOCOL_FIXTURE_BEGIN: extracted verbatim for the independent localhost protocol check.
private class ControlledConnectFixture(private val expected: ByteArray) : java.io.Closeable {
    private val loopback = java.net.InetAddress.getByName("127.0.0.1")
    val origin = java.net.ServerSocket(0, 8, loopback)
    val proxy = java.net.ServerSocket(0, 8, loopback)
    val authority = "127.0.0.1:${origin.localPort}"
    val observed = java.util.concurrent.LinkedBlockingQueue<String>()
    val originRequests = java.util.concurrent.LinkedBlockingQueue<String>()
    val originConnections = java.util.concurrent.atomic.AtomicInteger()
    val release = java.util.concurrent.CountDownLatch(1)
    private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()

    private fun request(socket: java.net.Socket): String = readHead(socket.getInputStream()).substringBefore("\r\n")
    private fun reply(socket: java.net.Socket) {
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${expected.size}\r\nConnection: close\r\n\r\n").toByteArray())
        socket.getOutputStream().write(expected)
        socket.getOutputStream().flush()
    }

    val originWorker = Thread {
        try {
            while (!origin.isClosed) {
                origin.accept().use { socket ->
                    socket.soTimeout = 10_000
                    originConnections.incrementAndGet()
                    originRequests.put(request(socket))
                    reply(socket)
                }
            }
        } catch (error: Throwable) { if (!origin.isClosed) failure.compareAndSet(null, error) }
    }.apply { isDaemon = true; start() }

    val proxyWorker = Thread {
        try {
            proxy.soTimeout = 15_000
            repeat(2) { index ->
                proxy.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val connect = request(socket)
                    observed.put(connect)
                    check(connect == "CONNECT $authority HTTP/1.1") { "Expected CONNECT for the controlled origin, got $connect" }
                    socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    val tunneled = request(socket)
                    observed.put(tunneled)
                    val path = if (index == 0) "/payload" else "/blocked"
                    check(tunneled == "GET $path HTTP/1.1") { "Expected origin-form GET in CONNECT tunnel, got $tunneled" }
                    if (index == 0) reply(socket)
                    else check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Blocked fixture was not released" }
                }
            }
        } catch (error: Throwable) { if (!proxy.isClosed) failure.compareAndSet(null, error) }
    }.apply { isDaemon = true; start() }

    fun probeDirectOrigin(): ByteArray = java.net.Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress(loopback, origin.localPort), 5000)
        socket.soTimeout = 5000
        socket.getOutputStream().write("GET /probe HTTP/1.1\r\nHost: $authority\r\nConnection: close\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        val input = socket.getInputStream()
        check(readHead(input).startsWith("HTTP/1.1 200 OK\r\n"))
        input.readBytes()
    }

    fun checkHealthy() { failure.get()?.let { throw AssertionError("Controlled HTTP fixture failed", it) } }

    override fun close() {
        release.countDown()
        proxy.close()
        origin.close()
        proxyWorker.join(5000)
        originWorker.join(5000)
        check(!proxyWorker.isAlive && !originWorker.isAlive) { "Controlled fixture writer did not join" }
    }

    companion object {
        fun readHead(input: java.io.InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            var tail = 0
            while (bytes.size() < 16384) {
                val next = input.read()
                check(next >= 0) { "HTTP peer closed before complete headers" }
                bytes.write(next)
                tail = (tail shl 8) or next
                if (tail == 0x0d0a0d0a) return bytes.toString("ISO-8859-1")
            }
            error("Controlled HTTP header limit")
        }
    }
}
// HOST_PROTOCOL_FIXTURE_END