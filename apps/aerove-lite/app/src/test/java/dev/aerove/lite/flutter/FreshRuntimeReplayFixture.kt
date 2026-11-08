package dev.aerove.lite.flutter

import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.data.*
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** One test-only producer handoff; validator stubs do not establish native acceptance. */
fun main() {
    val root = File(checkNotNull(System.getProperty("freshRuntime.fixture"))).canonicalFile
    try { runBlocking { exportFreshFixture(root) } }
    catch (cause: Throwable) {
        File(root, "JVM-FAILED.txt").writeText(cause.stackTraceToString().take(1024 * 1024))
        throw IllegalStateException("Fresh fixture stage failed; inspect owned JVM-FAILED.txt")
    }
}

private suspend fun exportFreshFixture(root: File) {
    val producerFile = File(root, "producer-manifest.json")
    val producer = JSONObject(producerFile.readText())
    require(producer.getInt("schema") == 1 && producer.getString("kind") == "fresh-default-host-producer")
    require(producer.getBoolean("synthetic") && !producer.getBoolean("original312Input") && !producer.getBoolean("nativeAccepted"))
    require(producer.getString("source").matches(Regex("[0-9a-f]{40}")))
    require(producer.isNull("selectedProfile") && producer.getInt("acceptedDisclaimerVersion") == 0)
    val inputNames = mutableSetOf<String>()
    val inputs = producer.getJSONArray("files")
    repeat(inputs.length()) { index ->
        val row = inputs.getJSONObject(index)
        val name = row.getString("path")
        require(name.matches(Regex("[a-z-]+\\.(json|yaml)")) && inputNames.add(name))
        val file = File(root, name)
        require(file.canonicalFile == file.absoluteFile && file.isFile && file.length() <= 8L * 1024 * 1024)
        require(file.length() == row.getLong("bytes") && runtimeSha256(file.readBytes()) == row.getString("sha256"))
    }
    require(inputNames == setOf("producer.yaml", "setup-wire.json", "shared-state.json", "effective-config.json", "source-proof.json", "transport-calls.json"))
    val home = File(producer.getString("home"))
    require(home.canonicalFile == home.absoluteFile && home.parentFile == root && home.name == "producer-home")
    require(File(producer.getString("configFile")).canonicalFile == File(home, "config.yaml").canonicalFile)
    require(File(home, "config.yaml").readBytes().contentEquals(File(root, "producer.yaml").readBytes()))
    val wire = protocolObject(File(root, "setup-wire.json").readText())
    require(wire["base"] == null)
    require(meowJsonEqual(JSONObject(wire["data-source-proof"] as Map<*, *>), JSONObject(File(root, "source-proof.json").readText())))
    val output = File(root, "native")
    require(!output.exists()) { "Refuse to overwrite native replay evidence" }
    check(output.mkdir())
    val records = JSONArray()
    fun save(name: String, bytes: ByteArray) {
        require(!name.startsWith('/') && name.split('/').none { it.isEmpty() || it == "." || it == ".." })
        val file = File(output, name)
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        file.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
        records.put(JSONObject().put("path", name).put("bytes", bytes.size).put("sha256", runtimeSha256(bytes)))
    }
    fun copyTree(source: File, prefix: String) {
        check(source.isDirectory)
        var total = 0L
        val files = source.walkTopDown().filter { it.isFile }.toList()
        require(files.size <= 4096)
        for (file in files.sortedBy { it.relativeTo(source).invariantSeparatorsPath }) {
            require(file.toPath() == file.toPath().toRealPath() && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
            total += file.length(); require(total <= 256L * 1024 * 1024)
            save("$prefix/${file.relativeTo(source).invariantSeparatorsPath}", file.readBytes())
        }
    }
    var staged: MeowRuntimeSnapshot? = null
    val effects = object : MeowSetupEffects {
        override fun isInitialized() = true
        override suspend fun readRuntime(): MeowSetupRuntime? = null
        override suspend fun applyUpdate(expected: MeowSetupRuntime?, projection: MeowSetupProjection) {}
        override suspend fun refreshApplied(expected: MeowSetupRuntime, setupParamsJson: String) = false
        override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) { check(staged == null); staged = snapshot }
    }
    val store = RuntimeHandoffStore(home)
    MeowConfigProtocol(home, NativeConfigValidator { null }, { emptySet() }, sourceValidator = NativeSourceConfigValidator { null }).use { config ->
        val setup = MeowSetupProtocol(home, store, config, effects,
            preparedValidator = NativePreparedConfigValidator { _, _ -> null },
            verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
        check(setup.syncState(protocolObject(File(root, "shared-state.json").readText())).isEmpty())
        val calls = JSONArray(File(root, "transport-calls.json").readText())
        var setupCalls = 0
        repeat(calls.length()) { index ->
            val call = calls.getJSONObject(index)
            when (call.getString("method")) {
                "updateConfig" -> check(setup.updateConfig(protocolObject(call.getJSONObject("arguments").toString())).isEmpty())
                "setupConfig" -> {
                    setupCalls++
                    check(meowJsonEqual(call.getJSONObject("arguments"), JSONObject(wire)))
                    check(setup.setupConfig(wire).isEmpty())
                }
                "getIsInit", "initClash", "getProxies", "getAppliedSetup", "getExternalProviders" -> Unit
                else -> error("Unsupported producer transport trace operation")
            }
        }
        check(setupCalls == 1)
    }
    val snapshot = checkNotNull(staged) { "Actual setup did not produce a staged snapshot" }
    check(!snapshot.userRequested) { "Fresh default graph must not imply VPN intent" }
    val lease = FlutterRuntimeResources.create(home, snapshot) { store.isGraphCandidate(snapshot.revision, snapshot.intentEpoch) }
    try {
        val settings = MeowRuntimeSnapshotSettings.decode(snapshot.handoff.runtimeSettingsJson)
        val prepared = PreparedProfile(
            profileId = snapshot.profileId,
            source = lease.yaml,
            yaml = lease.yaml,
            revision = snapshot.revision,
            generation = snapshot.handoff.compiledSha256,
            resourceDirectory = lease.readRoot,
            nextRefreshAt = null,
        )
        val cache = File(home, "meow-cache/fresh-host").apply { check(mkdirs()) }
        ServerSocket(0).use { reservation ->
            val actual = MeowRuntimeConfigComposer(home, cacheDirectory = cache).composeFlutter(prepared, lease, settings,
                -1, reservation.localPort, "f".repeat(32), NativeCore.RuntimeMode.Proxy,
                SystemDnsSnapshot("lo", mapOf("lo" to listOf("127.0.0.1"))),
                TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6,
                    settings.network.tunInet4Address, settings.network.tunInet6Address, settings.network.tunDnsHijack, settings.network),
                true, null, emptySet())
            val originalPlan = actual.planJson() // Capture the actual composer result exactly once.
            require(!JSONObject(originalPlan).has("hostBinding")) { "Host fixture cannot fabricate live Android Geo capabilities" }
            save("final-plan.original.json", originalPlan.toByteArray())
            save("engine.original.yaml", actual.engineYaml.toByteArray())
            copyTree(lease.readRoot, "closure/read")
            val retainedRead = File(output, "closure/read").apply { check(isDirectory || mkdirs()) }.canonicalFile
            copyTree(cache, "closure/cache")
            val retainedCache = File(output, "closure/cache").apply { check(isDirectory || mkdirs()) }.canonicalFile
            val engine = actual.engineYaml.replace(lease.readRoot.path, retainedRead.path)
            val adapted = actual.withResources(engine, retainedRead, actual.revision, actual.resourceGeneration)
            val adaptedPlan = JSONObject(adapted.planJson()).put("cacheRoot", retainedCache.path).toString()
            save("final-plan.json", adaptedPlan.toByteArray())
            save("engine.yaml", engine.toByteArray())
            save("producer-manifest.json", producerFile.readBytes())
            repeat(inputs.length()) { index ->
                val name = inputs.getJSONObject(index).getString("path")
                save("input/$name", File(root, name).readBytes())
            }
            val manifest = JSONObject().put("schema", 1).put("kind", "fresh-shared-producer-owned-loader")
                .put("synthetic", true).put("original312Input", false).put("nativeAccepted", false)
                .put("source", producer.getString("source")).put("fd", -1).put("runtimeMode", "proxy").put("initiallyParked", true)
                .put("nativeValidationStubbed", true).put("businessProofValidation", "hashed-real-producer-payload-only")
                .put("physicalContext", "synthetic-host-loopback-dns-not-Android").put("controlPortReservedDuringCompositionOnly", true)
                .put("plan", "final-plan.json").put("planSha256", runtimeSha256(adaptedPlan.toByteArray()))
                .put("originalPlanSha256", runtimeSha256(originalPlan.toByteArray()))
                .put("engineSha256", runtimeSha256(engine.toByteArray()))
                .put("originalEngineSha256", runtimeSha256(actual.engineYaml.toByteArray()))
                .put("pathRebase", JSONObject().put("readFrom", lease.readRoot.path).put("readTo", retainedRead.path)
                    .put("cacheFrom", cache.path).put("cacheTo", retainedCache.path))
                .put("files", records)
            File(output, "final-plan-manifest.json").writeText(manifest.toString(2) + "\n")
        }
    } finally { lease.closeAfterNativeJoin() }
    println("Fresh shared producer final plan exported; native validation remains pending")
}
