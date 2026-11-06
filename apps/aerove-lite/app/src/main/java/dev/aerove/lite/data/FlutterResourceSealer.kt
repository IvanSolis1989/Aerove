package dev.aerove.lite.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SealedFlutterResource(val relativePath: String, val sourcePath: String, val sha256: String)

internal class ResourceGenerationLease(val directory: File, private val release: () -> Unit) {
    private var closed = false
    @Synchronized fun close() { if (!closed) { try { release() } finally { closed = true } } }
}

/** Owns the manager's candidate protection until the caller has copied and read back every file. */
class FlutterResourceLease internal constructor(val yaml: String, val directory: File,
    val resources: List<SealedFlutterResource>, private val release: suspend () -> Unit) {
    suspend fun closeAndJoin() = withContext(NonCancellable + Dispatchers.IO) { release() }
}

/** No profile/preferences repository: the sole input is the shared client's already compiled YAML. */
class FlutterResourceSealer(private val manager: ResourceBundleManager, private val defaultUserAgent: String) {
    init { require(dev.aerove.lite.requests.RequestPreferences.validUserAgent(defaultUserAgent)) }

    fun requiresGeoCapture(source: String): Boolean = manager.requiresGeoCapture(source)

    suspend fun borrow(source: String, directory: File): FlutterResourceLease {
        var retained: ResourceGenerationLease? = null
        try {
            return withContext(Dispatchers.IO) {
                val owner = manager.retainGeneration(directory).also { retained = it }
                FlutterResourceLease(source, owner.directory, SealedResourceInputs.readFiles(owner.directory)) { owner.close() }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { retained?.close() }
            throw failure
        }
    }

    suspend fun prepare(source: String, checkCertificate: Boolean, selectedMap: Map<String, String> = emptyMap(), tunReconciliation: MeowTunReconciliation? = null, geoResourceReader: HostGeoResourceReader? = null,
        refreshKeys: Set<String>? = null, replacements: Map<String, SealedFlutterResource> = emptyMap(),
        previous: dev.aerove.lite.flutter.MeowRuntimeSnapshot? = null, sourceOwnerSha256: String? = null,
        sourceBindings: Map<String, SealedFlutterResource> = emptyMap()): FlutterResourceLease {
        require(sourceOwnerSha256 == null || sourceOwnerSha256.matches(Regex("[0-9a-f]{64}")))
        val root = ConfigYaml.parse(source)
        val userAgent = root["global-ua"]?.let {
            it as? String ?: throw ConfigValidationException(listOf(ConfigIssue("$.global-ua", "Expected string host policy")))
        } ?: defaultUserAgent
        if (!dev.aerove.lite.requests.RequestPreferences.validUserAgent(userAgent))
            throw ConfigValidationException(listOf(ConfigIssue("$.global-ua", "Invalid HTTP User-Agent")))
        val policy = ProviderRequestPolicy(userAgent, checkCertificate)
        // This UUID identifies only the resource transaction, never a second user/profile record.
        val owner = UUID.randomUUID().toString()
        var inputs = SealedResourceInputs(emptyList())
        var bundle: ResourceBundle? = null
        try {
            withContext(Dispatchers.IO) { inputs = manager.retainSealedInputs(root, sourceBindings) }
            val manualOverrides = linkedMapOf<String, ProviderManualOverride>()
            for (namespace in listOf("proxy-providers", "rule-providers")) {
                (root[namespace] as? Map<*, *>)?.forEach { (name, raw) ->
                    val definition = raw as? Map<*, *> ?: return@forEach
                    val key = "$.$namespace.$name"
                    if (definition["type"] == "file" && key in replacements) {
                        val sourceOwner = checkNotNull(sourceOwnerSha256) { "A verified business owner is required for a persistent file-provider edit" }
                        val path = definition["path"] as String
                        val baseline = checkNotNull(inputs.resolve(path, key)) { "Original file-provider source is not captured" }
                        manualOverrides[key] = ProviderManualOverride(sourceOwner, definitionHash(definition), baseline.sha256)
                    }
                }
            }
            if (previous != null) {
                val preferred = previousProviderInputs(previous, root)
                val shadowed = inputs.shared.filter { row -> preferred.any { it.key == row.key } }.flatMap { listOfNotNull(it.file, it.receiptFile) }
                inputs = SealedResourceInputs(inputs.origins, preferred + inputs.shared.filter { row -> preferred.none { it.key == row.key } }, inputs.witnesses + shadowed)
            }
            val executableSource=if(root.remove("aerove-native-resource-manifest")!=null) ConfigYaml.dump(root) else source
            val prepared = manager.prepare(owner, executableSource, logicalOriginalSource=source, policyFingerprint = sha256(policy.json().toString() + "\n" + JSONObject(selectedMap.toSortedMap())),
                requestPolicy = policy, selectedMap = selectedMap.toMap(), sealedInputs = inputs,tunReconciliation=tunReconciliation,geoResourceReader=geoResourceReader,
                refreshKeys = refreshKeys, replacements = replacements, manualOverrides = manualOverrides)
            bundle = prepared
            currentCoroutineContext().ensureActive()
            val directory = prepared.directory.toPath().toRealPath().toFile()
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            require(manifest.getString("sourceSha256")==sha256(source) && manifest.getString("compiledInputSha256") == sha256(executableSource) &&
                manifest.getString("configSha256") == sha256(prepared.yaml)) { "Sealed Flutter source changed" }
            val resources = withContext(Dispatchers.IO) { inputs.verify(); SealedResourceInputs.readFiles(directory) }
            return FlutterResourceLease(prepared.yaml, directory, resources) {
                try { manager.discard(owner, prepared.generation) } finally { inputs.close() }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { bundle?.let { manager.discard(owner, it.generation) } } finally { inputs.close() }
            }
            throw failure
        }
    }

    private fun definitionHash(definition: Map<*, *>): String = dev.aerove.lite.flutter.nativeLiteJsonHash(definition)

    private fun previousProviderInputs(snapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot, current: Map<String, Any?>): List<SealedResourceInputs.SharedInput> {
        fun owned(relative: String): File {
            val asset = snapshot.handoff.resources.single { it.relativePath == relative }
            val file = File(snapshot.resourceDirectory, relative)
            require(file.toPath() == file.toPath().toRealPath() && Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                file.toPath().startsWith(snapshot.resourceDirectory.toPath()) && sha256(file) == asset.sha256) { "Previous provider source changed" }
            return file
        }
        if (snapshot.handoff.resources.none { it.relativePath == "manifest.json" }) return emptyList()
        val manifest = JSONObject(owned("manifest.json").readText())
        require(manifest.getString("coreVersion") == MeowUnsupportedOutbounds.CORE)
        val input = owned("input.yaml")
        require(sha256(input) == manifest.getString("compiledInputSha256"))
        val source = ConfigYaml.parse(input.readText())
        val rows = manifest.getJSONArray("records")
        return (0 until rows.length()).map(rows::getJSONObject).mapNotNull { record ->
            val kind = record.getString("kind")
            val key = record.getString("key")
            val namespace = when {
                kind == "source-proxy" -> "proxy-providers"
                kind == "ruleset" && key.startsWith("$.rule-providers.") && !key.endsWith(".payload") -> "rule-providers"
                else -> return@mapNotNull null
            }
            val definition = (source[namespace] as? Map<*, *>)?.get(key.removePrefix("$.$namespace.")) as? Map<*, *> ?: return@mapNotNull null
            val manualOverride = record.optJSONObject("manualOverride")?.let(ProviderManualOverride::parse)
            if (definition["type"] == "file") {
                return@mapNotNull null
            } else if (definition["type"] != "http") return@mapNotNull null
            val nextDefinition = (current[namespace] as? Map<*, *>)?.get(key.removePrefix("$.$namespace.")) as? Map<*, *> ?: return@mapNotNull null
            if (nextDefinition["type"] != "http") return@mapNotNull null
            val file = owned(record.getString("file"))
            require(sha256(file) == record.getString("sha256"))
            val metadata = JSONObject().put("sourceDefinition", JSONObject(definition)).put("nativeRecord", record)
                .put("updatedAt", record.getLong("updatedAt")).put("checkedAt", record.getLong("updatedAt"))
                .put("interval", if (record.optBoolean("localOverride")) 0 else record.getLong("interval"))
                .put("subscriptionUserInfo", record.optString("subscriptionUserInfo").takeUnless { it == "null" } ?: "")
            manifest.optJSONObject("requestPolicy")?.let { metadata.put("requestPolicy", it) }
            SealedResourceInputs.SharedInput(key, SealedFlutterResource(record.getString("file"), file.path, record.getString("sha256")), null, metadata, manualOverride)
        }
    }
}
