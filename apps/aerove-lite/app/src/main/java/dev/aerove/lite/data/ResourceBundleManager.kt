package dev.aerove.lite.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

internal data class ResourceBundle(
    val generation: String,
    val directory: File,
    val sourceSha256: String,
    val yaml: String,
    val nextRefreshAt: Long?,
)

class ResourceBundleManager(
    runtimeDirectory: File,
    private val validator: CompatibilityValidator,
    private val native: NativeResourceValidator,
    private val downloader: ResourceDownloader = ResourceDownloader(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = File(runtimeDirectory, "resources").canonicalFile
    private val state = states.computeIfAbsent(root.path) { BundleState() }

    suspend fun importLocal(profileId: String, path: String, input: InputStream) = withContext(Dispatchers.IO) {
        checkIdentifier(profileId)
        val directory = File(root, "imports/$profileId").apply { mkdirs() }
        val destination = privateResource(directory, path)
        destination.parentFile!!.mkdirs()
        val temporary = File(destination.parentFile, ".import-${UUID.randomUUID()}")
        try {
            input.use { copyResource(it, temporary, MAX_GEO_BYTES) }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    internal suspend fun prepare(profileId: String, source: String): ResourceBundle = state.preparation.withPermit {
        checkIdentifier(profileId)
        val generation = UUID.randomUUID().toString()
        val identity = "$profileId/$generation"
        synchronized(state) { state.candidates.add(identity) }
        var completed = false
        try {
            val bundle = withContext(Dispatchers.IO) { build(profileId, source, generation) }
            completed = true
            bundle
        } finally {
            if (!completed) withContext(NonCancellable + Dispatchers.IO) {
                synchronized(state) {
                    try {
                        removeBundle(File(root, "bundles/$profileId/.pending-$generation"))
                        removeBundle(File(root, "bundles/$identity"))
                    } finally { state.candidates.remove(identity) }
                }
            }
        }
    }

    private suspend fun build(profileId: String, source: String, generation: String): ResourceBundle {
        checkIdentifier(profileId)
        validator.validateManagedSource(source)
        val profileRoot = File(root, "bundles/$profileId").apply { mkdirs() }
        val candidate = File(profileRoot, ".pending-$generation").apply { check(mkdir()) }
        val destination = File(profileRoot, generation)
        val assets = File(candidate, "assets").apply { check(mkdir()) }
        val rootConfig = ConfigYaml.parse(source)
        val records = mutableListOf<Record>()
        var total = 0L
        val ruleLines = mutableListOf<String>()
        (rootConfig["rules"] as? List<*>)?.filterIsInstance<String>()?.let(ruleLines::addAll)

        suspend fun materialize(
            key: String,
            kind: String,
            url: String?,
            localPath: String?,
            behavior: String = "",
            format: String = "",
            interval: Long = 0,
        ): Record {
            currentCoroutineContext().ensureActive()
            val location = url ?: localPath ?: throw problem(key, "A resource URL or relative local file is required")
            if (localPath != null) localFile(profileId, localPath)
            val identity = sha256(JSONArray(listOf(key, kind, location, behavior, format)).toString())
            val file = File(assets, "$identity.bin")
            val limit = when (kind) {
                "proxy-provider", "source-proxy" -> MAX_PROFILE_BYTES.toLong()
                "ruleset" -> if (format == "mrs") MAX_MRS_BYTES else MAX_RULE_BYTES
                else -> MAX_GEO_BYTES
            }
            val digest = try {
                if (url != null) downloader.download(url, file, limit)
                else {
                    val original = localFile(profileId, checkNotNull(localPath))
                    if (!original.isFile) throw problem(key, "Local resource is missing; import $localPath for this profile")
                    original.inputStream().use { copyResource(it, file, limit) }
                }
            } catch (failure: ConfigValidationException) {
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw problem(key, "Resource download/copy failed: ${safeError(failure)}")
            }
            total += digest.bytes
            if (total > MAX_BUNDLE_BYTES || records.size >= MAX_RESOURCES) throw problem(key, "Resource bundle limit exceeded")
            val record = Record(key, kind, identity, digest.sha256, digest.bytes, "assets/${file.name}", behavior, format, url, localPath, interval)
            records += record
            return record
        }

        fun finalPath(record: Record): String = File(destination, record.file).path
        fun derived(key: String, kind: String, content: String, behavior: String = "", format: String = "yaml"): Record {
            val identity = sha256(JSONArray(listOf(key, kind, "derived")).toString())
            val filename = "$identity.yaml"
            PrivateStorage(assets).write(filename, content)
            val record = Record(key, kind, identity, sha256(content), content.toByteArray(Charsets.UTF_8).size.toLong(),
                "assets/$filename", behavior, format, null, null, 0)
            records += record
            total += record.bytes
            return record
        }
        return try {
            listOf("proxy-providers", "rule-providers").forEach namespaceLoop@ { namespace ->
                val providers = rootConfig[namespace] as? Map<*, *> ?: return@namespaceLoop
                providers.forEach providerLoop@ { (name, value) ->
                    @Suppress("UNCHECKED_CAST")
                    val provider = value as MutableMap<String, Any?>
                    val key = "$.$namespace.$name"
                    val type = provider["type"] as String
                    val rule = namespace == "rule-providers"
                    val behavior = if (rule) provider["behavior"] as String else ""
                    val format = if (rule) provider["format"] as? String ?: "yaml" else "yaml"
                    val inline = provider["inline-rules"] ?: provider["payload"]
                    if (inline != null) {
                        val lines = stringList(inline, "$key.payload")
                        if (lines.isNotEmpty()) {
                            validator.validateRulePayload(lines, "$key.payload")
                            ruleLines += lines
                            derived("$key.payload", "ruleset", ConfigYaml.dump(mapOf("payload" to lines)), behavior)
                        }
                    }
                    if (type == "inline") {
                        if (provider["path"] != null) localFile(profileId, provider["path"] as String)
                        return@providerLoop
                    }
                    val seconds = (provider["interval"] as? Number)?.toLong() ?: if (type == "http" && !rule) 86400 else 0
                    if (seconds < 0 || seconds > MAX_INTERVAL_SECONDS) throw problem("$key.interval", "Invalid refresh interval")
                    val record = materialize(
                        key, if (rule) "ruleset" else "source-proxy",
                        if (type == "http") provider["url"] as? String ?: throw problem("$key.url", "Missing URL") else null,
                        provider["path"] as? String,
                        behavior, format, seconds,
                    )
                    val payloadFile = File(candidate, record.file)
                    if (rule && format != "mrs") {
                        val text = payloadFile.inputStream().use { it.readBounded(MAX_RULE_BYTES.toInt()).strictUtf8() }
                        val lines = if (format == "yaml") yamlRuleLines(text, key) else if (format == "text") {
                            text.lineSequence().map(String::trim).filter {
                                it.isNotEmpty() && !it.startsWith('#') && !it.startsWith("//")
                            }.toList()
                        } else throw problem("$key.format", "Unsupported ruleset format")
                        validator.validateRulePayload(lines, key)
                        ruleLines += lines
                    }
                    if (!rule) {
                        val text = payloadFile.inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
                        validator.validateProxyPayload(text)
                        val payload = ConfigYaml.parse(text)
                        (payload["proxies"] as List<*>).forEachIndexed { index, value ->
                            @Suppress("UNCHECKED_CAST")
                            val proxy = value as MutableMap<String, Any?>
                            listOf("tls-cert", "tls-key", "ca").forEach certLoop@ { keyName ->
                                val original = proxy[keyName] as? String ?: return@certLoop
                                val cert = materialize("$key.proxies[$index].$keyName", "private-file", null, original)
                                proxy[keyName] = finalPath(cert)
                            }
                        }
                        provider["path"] = finalPath(derived("$key.prepared", "proxy-provider", ConfigYaml.dump(payload)))
                    }
                    provider["type"] = "file"
                    provider.remove("url")
                    if (rule) provider["path"] = finalPath(record)
                    provider["interval"] = 0
                }
            }

            val dns = rootConfig["dns"] as? Map<*, *>
            val fallback = dns?.get("fallback") as? List<*>
            val fallbackFilter = dns?.get("fallback-filter") as? Map<*, *>
            val needsCountry = ruleLines.any { GEOIP.containsMatchIn(it) } ||
                (!fallback.isNullOrEmpty() && fallbackFilter?.get("geoip") != false)
            val needsSite = ruleLines.any { GEOSITE.containsMatchIn(it) }
            val geo = listOf(
                Triple("mmdb", needsCountry, DEFAULT_COUNTRY_URL),
                Triple("asn-mmdb", ruleLines.any { ASN.containsMatchIn(it) }, DEFAULT_ASN_URL),
                Triple("geosite", needsSite, DEFAULT_GEOSITE_URL),
            )
            geo.forEach { (key, needed, defaultUrl) ->
                if (needed || rootConfig[key] != null || rootConfig["$key-download-url"] != null) {
                    val local = rootConfig[key] as? String
                    val explicitUrl = rootConfig["$key-download-url"] as? String
                    val url = explicitUrl ?: if (local == null || !localFile(profileId, local).isFile) defaultUrl else null
                    val record = materialize("$.$key", if (key == "geosite") "geosite" else "mmdb", url, local)
                    rootConfig[key] = finalPath(record)
                }
                rootConfig.remove("$key-download-url")
            }

            (rootConfig["proxies"] as? List<*>)?.forEachIndexed { index, raw ->
                @Suppress("UNCHECKED_CAST")
                val proxy = raw as MutableMap<String, Any?>
                listOf("tls-cert", "tls-key", "ca").forEach { key ->
                    val path = proxy[key] as? String ?: return@forEach
                    val record = materialize("$.proxies[$index].$key", "private-file", null, path)
                    proxy[key] = finalPath(record)
                }
            }
            (rootConfig["proxies"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { proxies ->
                derived("$.proxies", "proxy-provider", ConfigYaml.dump(mapOf("proxies" to proxies)))
            }

            if (total > MAX_BUNDLE_BYTES || records.size > MAX_RESOURCES) throw problem("$", "Resource bundle limit exceeded")
            val prepared = ConfigYaml.dump(rootConfig)
            val store = PrivateStorage(candidate)
            store.write("config.yaml", prepared)
            val now = clock()
            val next = records.filter { it.interval > 0 }.minOfOrNull { now + it.interval * 1000 }
            val manifest = JSONObject().put("schema", 1).put("profileId", profileId)
                .put("coreVersion", CompatibilityValidator.CORE_VERSION)
                .put("generation", generation).put("sourceSha256", sha256(source))
                .put("configSha256", sha256(prepared)).put("createdAt", now)
                .put("nextRefreshAt", next ?: JSONObject.NULL)
                .put("records", JSONArray(records.map { it.toJson() }))
            store.write("manifest.json", manifest.toString())
            currentCoroutineContext().ensureActive()
            Files.move(candidate.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                // Proxy loaders read referenced TLS files; validate after all final paths exist, before publishing the index.
                for (record in records) {
                    currentCoroutineContext().ensureActive()
                    if (record.kind != "private-file" && record.kind != "source-proxy") {
                        native.validate(record.kind, File(destination, record.file).path, record.behavior, record.format)?.let {
                            throw problem(record.key, it)
                        }
                    }
                }
                validator.validatePrepared(prepared, destination)
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                removeBundle(destination)
                throw failure
            }
            ResourceBundle(generation, destination, sha256(source), prepared, next)
        } catch (failure: Throwable) {
            removeBundle(candidate)
            throw failure
        }
    }

    internal suspend fun load(profileId: String, generation: String, source: String): ResourceBundle = withContext(Dispatchers.IO) {
        checkIdentifier(profileId)
        checkIdentifier(generation)
        val directory = File(root, "bundles/$profileId/$generation").canonicalFile
        check(directory.toPath().startsWith(root.toPath()))
        if (!directory.isDirectory) throw problem("$.resources", "Prepared resource generation is missing; refresh this profile")
        val storage = PrivateStorage(directory)
        val manifest = JSONObject(storage.read("manifest.json", 1024 * 1024))
        require(manifest.getInt("schema") == 1 && manifest.getString("coreVersion") == CompatibilityValidator.CORE_VERSION && manifest.getString("profileId") == profileId &&
            manifest.getString("generation") == generation && manifest.getString("sourceSha256") == sha256(source)) {
            "Resource bundle does not match this profile revision"
        }
        val records = manifest.getJSONArray("records")
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val file = privateResource(directory, record.getString("file"))
            if (!file.isFile || file.length() != record.getLong("bytes") || sha256(file) != record.getString("sha256")) {
                throw problem(record.getString("key"), "Immutable resource content changed or is missing")
            }
        }
        val yaml = storage.read("config.yaml")
        require(sha256(yaml) == manifest.getString("configSha256")) { "Prepared configuration changed" }
        validator.validatePrepared(yaml, directory)
        ResourceBundle(generation, directory, sha256(source), yaml,
            if (manifest.isNull("nextRefreshAt")) null else manifest.getLong("nextRefreshAt"))
    }

    internal suspend fun discard(profileId: String, generation: String) = withContext(Dispatchers.IO) {
        checkIdentifier(profileId)
        checkIdentifier(generation)
        synchronized(state) {
            try { removeBundle(File(root, "bundles/$profileId/$generation")) }
            finally { state.candidates.remove("$profileId/$generation") }
        }
    }

    internal fun committed(profileId: String, generation: String) = synchronized(state) {
        state.candidates.remove("$profileId/$generation")
        if (profileId in state.importing) state.committedImports.add(profileId)
    }

    internal suspend fun <T> importArchive(profileId: String, input: InputStream, accept: suspend (String) -> T): T {
        checkIdentifier(profileId)
        synchronized(state) { check(state.importing.add(profileId)) }
        val staging = File(root, "imports/.pending-$profileId")
        val destination = File(root, "imports/$profileId")
        val archive = File(root, "archives/$profileId.zip")
        var completed = false
        try {
            val source = withContext(Dispatchers.IO) {
                val yaml = ProfileArchive.extract(input, archive, staging)
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                yaml
            }
            val result = accept(source)
            completed = true
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(state) {
                    try {
                        archive.delete()
                        deletePrivateTree(File(root, "imports"), staging)
                        if (!completed && profileId !in state.committedImports) deletePrivateTree(File(root, "imports"), destination)
                    } finally {
                        state.importing.remove(profileId)
                        state.committedImports.remove(profileId)
                    }
                }
            }
        }
    }

    internal fun prune(retained: Map<String, Set<String>>, importProfiles: Set<String>) = synchronized(state) {
        val protectedGenerations = state.candidates + retained.flatMap { (id, generations) -> generations.map { "$id/$it" } }
        File(root, "bundles").listFiles()?.forEach { profile ->
            if (!isIdentifier(profile.name) || Files.isSymbolicLink(profile.toPath()) || !profile.isDirectory) return@forEach
            profile.listFiles()?.forEach generationLoop@ { directory ->
                val generation = directory.name.removePrefix(".pending-")
                if (!isIdentifier(generation) || Files.isSymbolicLink(directory.toPath())) return@generationLoop
                if ("${profile.name}/$generation" !in protectedGenerations) removeBundle(directory)
            }
            if (profile.list()?.isEmpty() == true) profile.delete()
        }
        val imported = importProfiles + state.importing + state.candidates.map { it.substringBefore('/') }
        File(root, "imports").listFiles()?.forEach { directory ->
            val id = directory.name.removePrefix(".pending-")
            if (isIdentifier(id) && id !in imported && !Files.isSymbolicLink(directory.toPath())) {
                deletePrivateTree(File(root, "imports"), directory)
            }
        }
        File(root, "archives").listFiles()?.forEach { archive ->
            if (archive.extension == "zip" && isIdentifier(archive.nameWithoutExtension) &&
                archive.nameWithoutExtension !in state.importing && !Files.isSymbolicLink(archive.toPath())) archive.delete()
        }
    }

    private fun localFile(profileId: String, path: String): File = privateResource(File(root, "imports/$profileId"), path)

    private fun yamlRuleLines(text: String, path: String): List<String> {
        val content = ConfigYaml.parse(text, MAX_RULE_BYTES.toInt())
        if (content.keys != setOf("payload")) throw problem(path, "Ruleset YAML must contain only payload; no fields are ignored")
        return stringList(content["payload"], "$path.payload")
    }

    private fun stringList(value: Any?, path: String): List<String> {
        if (value !is List<*> || value.any { it !is String }) throw problem(path, "Expected a list of rule strings")
        return value.filterIsInstance<String>()
    }

    private fun removeBundle(directory: File) {
        deletePrivateTree(File(root, "bundles"), directory)
    }

    private class BundleState {
        val preparation = Semaphore(1)
        val candidates = mutableSetOf<String>()
        val importing = mutableSetOf<String>()
        val committedImports = mutableSetOf<String>()
    }

    private data class Record(
        val key: String, val kind: String, val sourceId: String, val sha256: String, val bytes: Long,
        val file: String, val behavior: String, val format: String, val url: String?, val localPath: String?, val interval: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().put("key", key).put("kind", kind).put("sourceSha256", sourceId)
            .put("sha256", sha256).put("bytes", bytes).put("file", file).put("behavior", behavior)
            .put("format", format).put("url", url ?: JSONObject.NULL).put("localPath", localPath ?: JSONObject.NULL)
            .put("interval", interval)
    }

    companion object {
        const val DEFAULT_COUNTRY_URL = "https://github.com/Loyalsoldier/geoip/releases/latest/download/Country.mmdb"
        const val DEFAULT_ASN_URL = "https://git.io/GeoLite2-ASN.mmdb"
        const val DEFAULT_GEOSITE_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/202406182210/geosite.dat"
        private const val MAX_GEO_BYTES = 64L * 1024 * 1024
        private const val MAX_RULE_BYTES = 8L * 1024 * 1024
        private const val MAX_MRS_BYTES = 32L * 1024 * 1024
        private const val MAX_BUNDLE_BYTES = 256L * 1024 * 1024
        private const val MAX_RESOURCES = 128
        private const val MAX_INTERVAL_SECONDS = 366L * 24 * 60 * 60
        private val states = ConcurrentHashMap<String, BundleState>()
        private val GEOIP = Regex("(?:^|[,(])\\s*GEOIP\\s*,", RegexOption.IGNORE_CASE)
        private val GEOSITE = Regex("(?:^|[,(])\\s*GEOSITE\\s*,", RegexOption.IGNORE_CASE)
        private val ASN = Regex("(?:^|[,(])\\s*IP-ASN\\s*,", RegexOption.IGNORE_CASE)
        private fun checkIdentifier(value: String) { require(UUID.fromString(value).toString() == value) { "Invalid profile/generation id" } }
        private fun isIdentifier(value: String): Boolean = runCatching { checkIdentifier(value); true }.getOrDefault(false)
        private fun problem(path: String, reason: String): ConfigValidationException = ConfigValidationException(listOf(ConfigIssue(path, reason)))
        private fun safeError(error: Exception): String = when (error) {
            is IOException -> error.message?.takeIf {
                it.matches(Regex("Resource HTTP [0-9]{3}|Resource exceeds [0-9]+ bytes|Resource HTTPS downgrade refused|Resource redirect limit exceeded|Resource is empty"))
            } ?: "network or file I/O error"
            else -> "invalid resource request"
        }
    }
}
