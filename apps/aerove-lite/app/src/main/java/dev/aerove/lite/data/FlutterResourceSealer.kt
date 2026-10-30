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
    @Synchronized fun close() { if (!closed) { release(); closed = true } }
}

/** Owns the manager's candidate protection until the caller has copied and read back every file. */
class FlutterResourceLease internal constructor(val yaml: String, val directory: File,
    val resources: List<SealedFlutterResource>, private val release: suspend () -> Unit) {
    suspend fun closeAndJoin() = withContext(NonCancellable + Dispatchers.IO) { release() }
}

/** No profile/preferences repository: the sole input is the shared client's already compiled YAML. */
class FlutterResourceSealer(private val manager: ResourceBundleManager, private val defaultUserAgent: String) {
    init { require(dev.aerove.lite.requests.RequestPreferences.validUserAgent(defaultUserAgent)) }

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

    suspend fun prepare(source: String, checkCertificate: Boolean, selectedMap: Map<String, String> = emptyMap()): FlutterResourceLease {
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
            withContext(Dispatchers.IO) { inputs = manager.retainSealedInputs(root) }
            val prepared = manager.prepare(owner, source, policyFingerprint = sha256(policy.json().toString() + "\n" + JSONObject(selectedMap.toSortedMap())),
                requestPolicy = policy, selectedMap = selectedMap.toMap(), sealedInputs = inputs)
            bundle = prepared
            currentCoroutineContext().ensureActive()
            val directory = prepared.directory.toPath().toRealPath().toFile()
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            require(manifest.getString("compiledInputSha256") == sha256(source) &&
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
}
