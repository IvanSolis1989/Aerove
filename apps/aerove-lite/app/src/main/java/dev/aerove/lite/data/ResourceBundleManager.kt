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
import dev.aerove.lite.resources.ManagedRuleResource
import dev.aerove.lite.resources.ResourceManagementRuleCache
import dev.aerove.lite.resources.LibraryRuleSource
import dev.aerove.lite.resources.CachedLibraryRule
import dev.aerove.lite.resources.LibraryCacheRestore
import dev.aerove.lite.dnssettings.validateDnsOutboundReferences

data class ResourceCheckReceipt(val checkedAt: Long, val sourceSha256: String, val sha256: String,
    val subscriptionUserInfo: String?) {
    init {
        require(checkedAt >= 0 && sourceSha256.matches(Regex("[0-9a-f]{64}")) && sha256.matches(Regex("[0-9a-f]{64}")))
        require(subscriptionUserInfo == null ||
            dev.aerove.lite.resources.ResourceSubscriptionInfo.parse(subscriptionUserInfo)?.header() == subscriptionUserInfo)
    }
    internal fun json(): JSONObject = JSONObject().put("checkedAt", checkedAt).put("sourceSha256", sourceSha256)
        .put("sha256", sha256).put("subscriptionUserInfo", subscriptionUserInfo ?: JSONObject.NULL)
    companion object {
        internal fun parse(row: JSONObject) = ResourceCheckReceipt(row.getLong("checkedAt"), row.getString("sourceSha256"),
            row.getString("sha256"), if (row.isNull("subscriptionUserInfo")) null else row.getString("subscriptionUserInfo"))
    }
}

internal data class ResourceBundle(
    val generation: String,
    val directory: File,
    val sourceSha256: String,
    val yaml: String,
    val nextRefreshAt: Long?,
    val compiledInputSha256: String = sourceSha256,
    val policyFingerprint: String = "",
    val sourcePolicyFingerprint: String = policyFingerprint,
    val refreshReceipts: Map<String, ResourceCheckReceipt> = emptyMap(),
)

class ResourceBundleManager(
    runtimeDirectory: File,
    private val validator: CompatibilityValidator,
    private val native: NativeResourceValidator,
    private val downloader: ResourceDownloader = ResourceDownloader(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val offlineRules: NativeRulePayloadInspector? = null,
) {
    private var providerDownloads: ProviderDownloadFactory? = null // Assigned only during this owned constructor.
    constructor(runtimeDirectory: File, validator: CompatibilityValidator, native: NativeResourceValidator,
        downloader: ResourceDownloader, clock: () -> Long) : this(runtimeDirectory, validator, native, downloader, clock, null)
    constructor(runtimeDirectory: File, validator: CompatibilityValidator, native: NativeResourceValidator,
        downloader: ResourceDownloader, clock: () -> Long = System::currentTimeMillis,
        offlineRules: NativeRulePayloadInspector? = null, providerDownloads: ProviderDownloadFactory) :
        this(runtimeDirectory, validator, native, downloader, clock, offlineRules) { this.providerDownloads = providerDownloads }
    private val root = File(runtimeDirectory, "resources").canonicalFile
    internal val migrationRoot: File get() = root
    private val state = states.computeIfAbsent(root.path) { BundleState() }
    private val library by lazy { state.snapshotFence.mutation { ResourceManagementRuleCache(File(root, "library"), validator, native, downloader, clock, offlineRules) } }

    internal suspend fun storeGlobalRule(name: String, url: String, localId: String?, source: File, digest: ResourceDigest,
        inspection: RulePayloadInspection): ManagedRuleResource = library.storePrepared(name, url, localId, source, digest, inspection)

    internal suspend fun <T> withGlobalRule(name: String, url: String, behavior: String, format: String, localId: String?,
        action: suspend (ManagedRuleResource, File) -> T): T = library.withPayload(name, url, behavior, format, localId, action)

    internal suspend fun pruneUnreferencedLocalRules(retainedIds: Set<String>) = library.pruneLocal(retainedIds)

    suspend fun syncLibraryRule(name: String, url: String, behavior: String, format: String): ManagedRuleResource =
        library.sync(name, url, behavior, format)

    suspend fun libraryRule(name: String, url: String, behavior: String, format: String): ManagedRuleResource? =
        library.snapshot(name, url, behavior, format)

    suspend fun <T> withLibraryRulesSnapshot(sources: List<LibraryRuleSource>, action: suspend (List<CachedLibraryRule>) -> T): T =
        library.withSnapshot(sources, action)

    suspend fun importLibraryRule(resource: ManagedRuleResource, input: InputStream): ManagedRuleResource =
        library.withRestoreTransaction { it.importResource(resource, input) }

    suspend fun <T> withLibraryRestoreTransaction(invalidateInFlight: Boolean = false, action: suspend (LibraryCacheRestore) -> T): T =
        library.withRestoreTransaction(invalidateInFlight, action)

    suspend fun importLocal(profileId: String, path: String, input: InputStream) = state.snapshotFence.mutation { withContext(Dispatchers.IO) {
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
    } }

    internal suspend fun prepare(profileId: String, source: String, previous: ResourceBundle? = null,
        refreshKeys: Set<String>? = null, logicalOriginalSource: String = source,
        policyFingerprint: String = "", geoUrls: Map<String, String> = emptyMap(),
        requestPolicy: ProviderRequestPolicy? = null, selectedMap: Map<String, String> = emptyMap(),
        sealedInputs: SealedResourceInputs? = null, tunReconciliation: MeowTunReconciliation? = null, geoResourceReader: HostGeoResourceReader? = null): ResourceBundle = state.snapshotFence.mutation { state.preparation.withPermit {
        require(policyFingerprint.isEmpty() || policyFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid source policy fingerprint" }
        checkIdentifier(profileId)
        val generation = UUID.randomUUID().toString()
        val identity = "$profileId/$generation"
        synchronized(state) { state.candidates.add(identity) }
        var completed = false
        try {
            val bundle = withContext(Dispatchers.IO) { build(profileId, source, generation, previous, refreshKeys, logicalOriginalSource, policyFingerprint, geoUrls, requestPolicy, selectedMap, sealedInputs,tunReconciliation,geoResourceReader) }
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
    } }

    private suspend fun build(profileId: String, source: String, generation: String,
        previous: ResourceBundle?, refreshKeys: Set<String>?, logicalOriginalSource: String, policyFingerprint: String,
        geoUrls: Map<String, String>, requestPolicy: ProviderRequestPolicy?, selectedMap: Map<String, String>, sealedInputs: SealedResourceInputs?, tunReconciliation: MeowTunReconciliation?, geoResourceReader: HostGeoResourceReader?): ResourceBundle {
        val validator=this.validator.withTunReconciliation(tunReconciliation)
        checkIdentifier(profileId)
        validator.validateManagedSource(source)
        val profileRoot = File(root, "bundles/$profileId").apply { mkdirs() }
        val candidate = File(profileRoot, ".pending-$generation").apply { check(mkdir()) }
        val destination = File(profileRoot, generation)
        val assets = File(candidate, "assets").apply { check(mkdir()) }
        val rootConfig = validator.resourceInput(source)
        val records = mutableListOf<Record>()
        val refreshReceipts = linkedMapOf<String, ResourceCheckReceipt>()
        val previousManifest = if (previous != null && refreshKeys != null)
            JSONObject(PrivateStorage(previous.directory).read("manifest.json", 1024 * 1024)) else null
        val reusable = if (previousManifest != null) {
            val rows = previousManifest.getJSONArray("records")
            (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("key") }
        } else emptyMap()
        var total = 0L
        fun localSource(path: String, key: String? = null): File = sealedInputs?.resolve(path,key)?.let { row ->
            File(row.sourcePath).also { require(sha256(it) == row.sha256) { "Sealed input resource changed" } }
        } ?: localFile(profileId, path)
        var downloadScope: ProviderDownloadScope? = null
        var transportRevision = 0
        var downloadRevision = -1
        var downloadRoute: String? = null
        var transportReadRoot: File? = null
        suspend fun closeDownloadScope() {
            val closing = downloadScope ?: return
            downloadScope = null
            downloadRevision = -1
            withContext(NonCancellable) {
                closing.closeAndJoin()
                transportReadRoot?.let { deletePrivateTree(candidate, it) }
                transportReadRoot = null
            }
        }
        suspend fun downloadProvider(url: String, file: File, limit: Long, policy: ProviderFetchPolicy): ResourceDigest {
            val factory = providerDownloads ?: throw problem("$.provider-download", "A captured Meow provider download owner is required")
            if (downloadScope != null && (downloadRevision != transportRevision || downloadRoute != policy.proxy)) closeDownloadScope()
            val scope = downloadScope ?: run {
                val transport = ProviderBootstrapGraph.capture(rootConfig, policy.proxy)
                val read = File(candidate, ".transport-read-${UUID.randomUUID()}").apply { check(mkdir()) }.canonicalFile
                transportReadRoot = read
                fun actualPath(path: String, key: String): String {
                    val final = File(path).absoluteFile.toPath()
                    val published = destination.absoluteFile.toPath()
                    require(final.startsWith(published)) { "$key: transport requires this candidate's materialized payload" }
                    val actual = File(candidate, published.relativize(final).toString()).canonicalFile
                    require(actual.isFile && !Files.isSymbolicLink(actual.toPath()) && actual.toPath().startsWith(assets.canonicalFile.toPath())) {
                        "$key: materialized transport payload is missing or outside candidate assets"
                    }
                    val captured = File(read, actual.name)
                    if (!captured.exists()) Files.copy(actual.toPath(), captured.toPath())
                    require(sha256(captured) == sha256(actual)) { "$key: captured transport payload changed" }
                    return captured.canonicalPath
                }
                (transport["proxy-providers"] as? Map<*, *>)?.forEach { (name, value) ->
                    @Suppress("UNCHECKED_CAST") val provider = value as MutableMap<String, Any?>
                    val key = "$.proxy-providers.$name"
                    require(provider["type"] == "file") { "$key: named download requires its provider payload to be materialized first" }
                    provider["path"] = actualPath(provider["path"] as String, "$key.path")
                    val payload = ConfigYaml.parse(File(provider["path"] as String).readText())
                    (payload["proxies"] as? List<*>)?.forEachIndexed { index, node ->
                        @Suppress("UNCHECKED_CAST") val proxy = node as MutableMap<String, Any?>
                        listOf("tls-cert", "tls-key", "ca").forEach { field ->
                            (proxy[field] as? String)?.let { proxy[field] = actualPath(it, "$key.proxies[$index].$field") }
                        }
                    }
                    val projected = File(read, "provider-${UUID.randomUUID()}.yaml")
                    check(projected.createNewFile()) { "Could not create transport projection" }
                    projected.writeText(ConfigYaml.dump(payload))
                    provider["path"] = projected.canonicalPath
                }
                (transport["proxies"] as? List<*>)?.forEachIndexed { index, node ->
                    @Suppress("UNCHECKED_CAST") val proxy = node as MutableMap<String, Any?>
                    listOf("tls-cert", "tls-key", "ca").forEach { field ->
                        (proxy[field] as? String)?.let { proxy[field] = actualPath(it, "$.proxies[$index].$field") }
                    }
                }
                for (field in listOf("mmdb", "asn-mmdb", "geosite"))
                    (transport[field] as? String)?.let { transport[field] = actualPath(it, "$.$field") }
                factory.open(ProviderDownloadContext(validator.providerDownloadInput(ConfigYaml.dump(transport)),
                    assets.canonicalFile, read, requestPolicy, selectedMap.toMap())).also {
                    downloadScope = it; downloadRevision = transportRevision; downloadRoute = policy.proxy
                }
            }
            currentCoroutineContext().ensureActive()
            val response = scope.download(ProviderDownloadRequest(url, policy.proxy, policy.headers, file, limit))
            currentCoroutineContext().ensureActive()
            require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == assets.canonicalFile &&
                response.bytes in 0..limit && file.length() == response.bytes && response.sha256.matches(Regex("[0-9a-f]{64}")) &&
                sha256(file) == response.sha256) { "Provider transport payload receipt does not match its owned file" }
            currentCoroutineContext().ensureActive()
            return ResourceDigest(response.sha256, response.bytes,
                dev.aerove.lite.resources.ResourceSubscriptionInfo.parse(response.subscriptionUserInfo)?.header())
        }
        val ruleGeo = initialGeoScan(rootConfig)

        suspend fun materialize(
            key: String,
            kind: String,
            url: String?,
            localPath: String?,
            behavior: String = "",
            format: String = "",
            interval: Long = 0,
            fetchPolicy: ProviderFetchPolicy? = null,
        ): Record {
            currentCoroutineContext().ensureActive()
            val location = url ?: localPath ?: throw problem(key, "A resource URL or relative local file is required")
            val globalLocalId = if (kind == "ruleset" && url == null) globalRuleLocalId(localPath) else null
            // Shared Dart's confined HTTP path is an output-cache hint, never a source file grant.
            // Captured acquisition always writes a fresh owned assets child and preserves this hint only in lineage.
            if (localPath != null && globalLocalId == null && (url == null || requestPolicy == null)) localSource(localPath,key)
            val sourceIdentity = materializedSourceIdentity(key, kind, location, behavior, format)
            val effectiveFetch = fetchPolicy ?: requestPolicy?.takeIf { url != null }?.let { ProviderFetchPolicy(null, emptyMap()) }
            val identity = if (effectiveFetch == null) sourceIdentity else sha256(sourceIdentity + "\n" + effectiveFetch.identity() +
                (requestPolicy?.let { "\n" + it.json().toString() } ?: ""))
            val file = File(assets, "$identity.bin")
            val limit = when (kind) {
                "proxy-provider", "source-proxy" -> MAX_PROFILE_BYTES.toLong()
                "ruleset" -> MAX_RULE_INPUT_BYTES
                else -> MAX_GEO_BYTES
            }
            val override = overridePath(key, identity).takeIf { localFile(profileId, it).isFile }
            val forced = key in refreshKeys.orEmpty() || ("*" in refreshKeys.orEmpty() && kind in setOf("proxy-provider", "ruleset"))
            val sharedKind = effectiveFetch == null && kind in setOf("ruleset", "mmdb", "geosite")
            val libraryAtPreparation = if ((url != null || globalLocalId != null) && sharedKind)
                library.snapshot(key, url ?: "", behavior, format, kind, globalLocalId) else null
            val effectiveFormat = libraryAtPreparation?.runtimeFormat ?: format
            val cached = reusable[key]?.takeIf { !forced && it.getString("sourceSha256") == identity &&
                (effectiveFetch == null || it.optString("transportOrigin") in setOf("captured-transport", "verified-generation", "private-override")) &&
                (it.optString("sharedCacheBaselineSha", it.getString("sha256")) == (libraryAtPreparation?.sha256 ?: "") ||
                    libraryAtPreparation == null || libraryAtPreparation.sha256 == it.getString("sha256")) }
            val migratedCandidate = if(url!=null && !forced && override==null) sealedInputs?.cached(key,url,behavior,format,effectiveFetch,requestPolicy,clock()) else null
            val migrated = migratedCandidate?.takeIf { input ->
                val original=input.metadata?.optJSONObject("nativeRecord")
                val baseline=original?.optString("sharedCacheBaselineSha")?.takeIf {it.isNotEmpty()}
                if(baseline==null || kind !in setOf("ruleset","mmdb","geosite")) true else {
                    val current=library.snapshot(key,url ?: "",behavior,format,kind,globalLocalId)
                    current==null || current.sha256==baseline
                }
            }
            var hostCopy: HostGeoResourceCopy? = null
            val digest = try {
                if (geoResourceReader != null && url != null && override == null && key in setOf("$.mmdb", "$.asn-mmdb", "$.geosite")) {
                    hostCopy = geoResourceReader.copyInto(key, url, file, limit)
                    currentCoroutineContext().ensureActive()
                    hostCopy?.let { copy ->
                        require(copy.bytes <= limit && file.isFile && !Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(assets.toPath()) && file.canonicalFile.toPath().startsWith(assets.toPath().toAbsolutePath().normalize())) { "Invalid Host geo copy destination or size" }
                        require(file.length() == copy.bytes && sha256(file) == copy.sha256) { "Host geo copy digest changed" }
                        require(copy.updatedAt != null && copy.updatedAt > 0 && copy.updatedAt <= clock()) { "Host geo copy age is missing or invalid" }
                    } ?: require(!file.exists()) { "Host geo reader left a partial declined copy" }
                }
                if (hostCopy != null) {
                    ResourceDigest(hostCopy.sha256, hostCopy.bytes, sourceUpdatedAt = hostCopy.updatedAt)
                } else if(migrated!=null) {
                    File(migrated.file.sourcePath).inputStream().use {copyResource(it,file,limit)}.also {
                        require(it.sha256==migrated.file.sha256) { "Shared cached resource changed" }
                    }.copy(subscriptionUserInfo=migrated.metadata?.optString("subscriptionUserInfo")?.takeIf {it.isNotEmpty()}, sourceUpdatedAt=migrated.metadata!!.getLong("updatedAt"))
                } else if (cached != null) {
                    val original = privateResource(checkNotNull(previous).directory, cached.getString("file"))
                    original.inputStream().use { if (globalLocalId != null) copyLocalRuleResource(it, file, limit) else copyResource(it, file, limit) }.also {
                        require(it.sha256 == cached.getString("sha256")) { "Resource cache content changed" }
                    }
                } else if (override != null) localFile(profileId, override).inputStream().use { copyResource(it, file, limit) }
                else if (url != null || globalLocalId != null) {
                    val shared = if (sharedKind && (!forced || libraryAtPreparation?.runtimeFormat != null || globalLocalId != null))
                        library.copyInto(key, url ?: "", behavior, format, file, limit, kind, globalLocalId) else null
                    shared ?: if (globalLocalId != null) throw problem(key, "Global rule payload is missing")
                    else if (effectiveFetch != null) downloadProvider(checkNotNull(url), file, limit, effectiveFetch)
                    else downloader.download(checkNotNull(url), file, limit)
                }
                else {
                    val original = localSource(checkNotNull(localPath),key)
                    if (!original.isFile) throw problem(key, "Local resource is missing; import $localPath for this profile")
                    original.inputStream().use { copyResource(it, file, limit) }.also { digest ->
                        sealedInputs?.resolve(localPath,key)?.let { require(digest.sha256 == it.sha256) { "Sealed input resource changed during copy" } }
                    }
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
            val record = Record(key, kind, identity, digest.sha256, digest.bytes, "assets/${file.name}", behavior, effectiveFormat, url,
                override ?: localPath, interval, (if (hostCopy != null) digest.sourceUpdatedAt ?: clock() else cached?.optLong("updatedAt", previousManifest?.getLong("createdAt") ?: clock()) ?: digest.sourceUpdatedAt ?: clock()),
                if (hostCopy == null && cached != null && !cached.isNull("subscriptionUserInfo")) cached.getString("subscriptionUserInfo") else digest.subscriptionUserInfo, override != null,
                if ((url != null || globalLocalId != null) && sharedKind) libraryAtPreparation?.sha256 ?: "" else null, format, globalLocalId, if (hostCopy != null) "host-catalog" else if (effectiveFetch == null) null else when {
                    cached != null || migrated != null -> "verified-generation"
                    override != null -> "private-override"
                    else -> "captured-transport"
                })
            val boundRecord = if (hostCopy != null) record.copy(hostGeoPreferencesSha256 = sha256(ConfigYaml.dump(mapOf("geodata" to rootConfig["geodata"])))) else record
            records += boundRecord
            if (url != null && (hostCopy != null || cached == null) && override == null) refreshReceipts[key] =
                ResourceCheckReceipt((if (hostCopy != null) hostCopy.updatedAt else migrated?.metadata?.getLong("checkedAt")) ?: clock(), identity, digest.sha256, digest.subscriptionUserInfo)
            return boundRecord
        }

        fun finalPath(record: Record): String = File(destination, record.file).path
        fun derived(key: String, kind: String, content: String, behavior: String = "", format: String = "yaml"): Record {
            val identity = sha256(JSONArray(listOf(key, kind, "derived")).toString())
            val filename = "$identity.yaml"
            PrivateStorage(assets).write(filename, content)
            val record = Record(key, kind, identity, sha256(content), content.toByteArray(Charsets.UTF_8).size.toLong(),
                "assets/$filename", behavior, format, null, null, 0, clock())
            records += record
            total += record.bytes
            return record
        }
        suspend fun materializeGeo() {
            val needed = ruleGeo.neededKeys(rootConfig)
            val configuredUrls = (rootConfig["geodata"] as? Map<*, *>)?.get("url") as? Map<*, *>
            val geo = listOf(
                Triple("mmdb", "mmdb" in needed, configuredUrls?.get("mmdb") as? String ?: geoUrls["mmdb"] ?: DEFAULT_COUNTRY_URL),
                Triple("asn-mmdb", "asn-mmdb" in needed, configuredUrls?.get("asn") as? String ?: geoUrls["asn-mmdb"] ?: DEFAULT_ASN_URL),
                Triple("geosite", "geosite" in needed, configuredUrls?.get("geosite") as? String ?: geoUrls["geosite"] ?: DEFAULT_GEOSITE_URL),
            )
            geo.forEach { (key, needed, defaultUrl) ->
                if (records.none { it.key == "$.$key" } && (needed || rootConfig[key] != null || rootConfig["$key-download-url"] != null)) {
                    val local = rootConfig[key] as? String
                    val explicitUrl = rootConfig["$key-download-url"] as? String
                    val url = explicitUrl ?: if (local == null || !localSource(local).isFile) defaultUrl else null
                    val record = materialize("$.$key", if (key == "geosite") "geosite" else "mmdb", url, local)
                    rootConfig[key] = finalPath(record)
                    transportRevision++
                }
                rootConfig.remove("$key-download-url")
            }

        }
        return try {
            (rootConfig["proxies"] as? List<*>)?.forEachIndexed { index, raw ->
                @Suppress("UNCHECKED_CAST")
                val proxy = raw as MutableMap<String, Any?>
                listOf("tls-cert", "tls-key", "ca").forEach { key ->
                    val path = proxy[key] as? String ?: return@forEach
                    val record = materialize("$.proxies[$index].$key", "private-file", null, path)
                    proxy[key] = finalPath(record)
                }
            }

            materializeGeo()
            listOf("proxy-providers", "rule-providers").forEach namespaceLoop@ { namespace ->
                val providers = rootConfig[namespace] as? Map<*, *> ?: return@namespaceLoop
                val entries = if (namespace == "proxy-providers") ProviderBootstrapGraph.orderedProviders(rootConfig, providers) else providers.entries.toList()
                entries.forEach providerLoop@ { (name, value) ->
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
                            ruleGeo.addAll(lines)
                            derived("$key.payload", "ruleset", ConfigYaml.dump(mapOf("payload" to lines)), behavior)
                        }
                    }
                    if (type == "inline") {
                        if (provider["path"] != null) localSource(provider["path"] as String)
                        return@providerLoop
                    }
                    val seconds = (provider["interval"] as? Number)?.toLong() ?: 0
                    if (seconds < 0 || seconds > MAX_INTERVAL_SECONDS) throw problem("$key.interval", "Invalid refresh interval")
                    val record = materialize(
                        key, if (rule) "ruleset" else "source-proxy",
                        if (type == "http") provider["url"] as? String ?: throw problem("$key.url", "Missing URL") else null,
                        provider["path"] as? String,
                        behavior, format, seconds, if (type == "http") ProviderFetchPolicy.from(provider) else null,
                    )
                    val payloadFile = File(candidate, record.file)
                    if (rule && record.format != "mrs") {
                        val check = validator.ruleLineCheck(key)
                        val scan = when (record.format) {
                            "yaml" -> { yamlRuleLines(payloadFile, key).forEachIndexed { index, line -> check.line(index, line); ruleGeo.add(line) }; null }
                            "text" -> payloadFile.forEachRuleLine(MAX_RULE_INPUT_BYTES) { index, line -> check.line(index, line); ruleGeo.add(line) }
                            else -> throw problem("$key.format", "Unsupported ruleset format")
                        }
                        check.finish()
                        scan?.requireEntryBound(key)
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
                    // Network metadata no longer applies to this sealed file.
                    // Its receipt distinguishes actual transport, verified reuse,
                    // and the user's explicit private-file replacement.
                    provider.remove("proxy")
                    provider.remove("header")
                    if (rule) { provider["path"] = finalPath(record); provider["format"] = record.format }
                    provider["interval"] = 0
                    if (!rule) transportRevision++
                }
            }

            materializeGeo()

            (rootConfig["proxies"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { proxies ->
                derived("$.proxies", "proxy-provider", ConfigYaml.dump(mapOf("proxies" to proxies)))
            }

            val sealedLineage = JSONArray()
            sealedInputs?.origins?.forEach { origin ->
                val directory = origin.lease.directory
                val files = JSONArray()
                origin.files.filter { it.relativePath in setOf("input.yaml", "config.yaml", "manifest.json") || it.relativePath.startsWith("lineage/") }.forEach { row ->
                    val relative = if (row.relativePath.startsWith("lineage/")) row.relativePath else
                        "lineage/${directory.parentFile.name}/${directory.name}/${row.relativePath}"
                    val file = File(candidate, relative).apply { parentFile.mkdirs() }
                    val existing = records.firstOrNull { it.file == relative }
                    if (existing == null) {
                        val digest = File(row.sourcePath).inputStream().use { copyResource(it, file, MAX_GEO_BYTES) }
                        require(digest.sha256 == row.sha256) { "Sealed input lineage changed during copy" }
                        total += digest.bytes
                        records += Record("$.sealedInputs.$relative", "sealed-lineage", sha256(row.sourcePath), digest.sha256,
                            digest.bytes, relative, "", "", null, row.sourcePath, 0, clock())
                        if (total > MAX_BUNDLE_BYTES || records.size > MAX_RESOURCES) throw problem("$", "Resource bundle limit exceeded")
                    } else require(existing.sha256 == row.sha256) { "Conflicting sealed input lineage" }
                    files.put(JSONObject().put("sourceFile", row.relativePath).put("file", relative).put("sha256", row.sha256))
                }
                sealedLineage.put(JSONObject().put("profileId", directory.parentFile.name).put("generation", directory.name)
                    .put("manifestSha256", origin.files.single { it.relativePath == "manifest.json" }.sha256).put("files", files))
            }
            sealedInputs?.verify()
            if (total > MAX_BUNDLE_BYTES || records.size > MAX_RESOURCES) throw problem("$", "Resource bundle limit exceeded")
            val prepared = ConfigYaml.dump(rootConfig)
            val store = PrivateStorage(candidate)
            store.write("input.yaml", source)
            store.write("config.yaml", prepared)
            val now = clock()
            val next = records.filter { it.interval > 0 && !it.localOverride }.minOfOrNull { it.updatedAt + it.interval * 1000 }
            val recordJson = JSONArray(records.map { it.toJson() })
            val effectiveFingerprint = resourcePolicyFingerprint(policyFingerprint, recordJson, actual = true)
            val manifest = JSONObject().put("schema", 1).put("profileId", profileId)
                .put("coreVersion", validator.coreVersion)
                .put("generation", generation).put("sourceSha256", sha256(logicalOriginalSource))
                .put("compiledInputSha256", sha256(source)).put("policyFingerprint", effectiveFingerprint)
                .put("sourcePolicyFingerprint", policyFingerprint)
                .put("configSha256", sha256(prepared)).put("createdAt", now)
                .put("nextRefreshAt", next ?: JSONObject.NULL)
                .put("records", recordJson)
                .put("requestPolicy", requestPolicy?.json() ?: JSONObject.NULL)
                .put("selectionCache", JSONObject(selectedMap))
            if (sealedLineage.length() > 0) manifest.put("sealedInputs", sealedLineage)
            if (previous != null && refreshKeys != null && previous.compiledInputSha256 == sha256(source) &&
                previous.sourcePolicyFingerprint == policyFingerprint && previous.policyFingerprint == effectiveFingerprint) {
                val oldRecords = JSONObject(PrivateStorage(previous.directory).read("manifest.json", 1024 * 1024)).getJSONArray("records")
                fun identity(rows: JSONArray) = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
                    listOf(it.getString("key"), it.getString("kind"), it.getString("format"), it.getString("sha256"),
                        it.optBoolean("localOverride", false).toString())
                } }.sortedBy { it.first() }
                if (identity(oldRecords) == identity(recordJson)) {
                    closeDownloadScope()
                    removeBundle(candidate)
                    synchronized(state) { state.candidates.remove("$profileId/$generation") }
                    return previous.copy(refreshReceipts = previous.refreshReceipts + refreshReceipts)
                }
            }
            store.write("manifest.json", manifest.toString())
            closeDownloadScope()
            currentCoroutineContext().ensureActive()
            Files.move(candidate.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                // Proxy loaders read referenced TLS files; validate after all final paths exist, before publishing the index.
                for (record in records) {
                    currentCoroutineContext().ensureActive()
                    if (record.kind !in setOf("private-file", "source-proxy", "sealed-lineage")) {
                        val file = File(destination, record.file)
                        // The preparation-only API decodes every byte, but may require this generation's Geo context.
                        // Both typed states still pass the mandatory full prepared converter below before admission.
                        val preparedRule = if (record.kind == "ruleset") try {
                            validator.preflightPreparedRule(file, record.behavior, record.format)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: ConfigValidationException) {
                            throw failure
                        } catch (failure: Exception) {
                            throw problem(record.key, "Rule resource preflight failed: ${failure.message?.take(2000) ?: failure.javaClass.simpleName}").also { it.initCause(failure) }
                        } else null
                        if (preparedRule == null) native.validate(record.kind, file.path, record.behavior, record.format)?.let {
                            throw problem(record.key, it)
                        }
                    }
                }
                val providerNames = records.filter { it.kind in setOf("proxy-provider", "source-proxy") }.flatMap { record ->
                    val nodes = ConfigYaml.parse(privateResource(destination, record.file).readText())["proxies"] as? List<*> ?: emptyList<Any?>()
                    nodes.mapNotNull { ((it as? Map<*, *>)?.get("name") as? String) }
                }.toSet()
                validateDnsOutboundReferences(rootConfig, providerNames)
                verifyPreparedBytes(destination, manifest.toString())
                validator.validatePrepared(prepared, destination)
                verifyPreparedBytes(destination, manifest.toString())
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                removeBundle(destination)
                throw failure
            }
            ResourceBundle(generation, destination, sha256(logicalOriginalSource), prepared, next, sha256(source), effectiveFingerprint, policyFingerprint,
                previous?.refreshReceipts.orEmpty() + refreshReceipts)
        } catch (failure: Throwable) {
            try { closeDownloadScope() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            removeBundle(candidate)
            throw failure
        }
    }

    /** The prepared native converter borrows this immutable generation; it never owns its files. */
    private fun verifyPreparedBytes(directory: File, expectedManifest: String) {
        val storage = PrivateStorage(directory)
        if (storage.read("manifest.json", 1024 * 1024) != expectedManifest)
            throw problem("$.resources", "Immutable resource manifest changed during validation")
        val manifest = JSONObject(expectedManifest)
        for ((name, hashKey) in listOf("input.yaml" to "compiledInputSha256", "config.yaml" to "configSha256")) {
            if (!manifest.has(hashKey)) continue // Legacy generations predate compiled input lineage.
            val file = privateResource(directory, name)
            if (!file.isFile || file.length() > MAX_PROFILE_BYTES || sha256(file) != manifest.getString(hashKey))
                throw problem("$.resources", "Immutable prepared configuration changed during validation")
        }
        val records = manifest.getJSONArray("records")
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val file = privateResource(directory, record.getString("file"))
            if (!file.isFile || file.length() != record.getLong("bytes") || sha256(file) != record.getString("sha256"))
                throw problem(record.getString("key"), "Immutable resource content changed during validation")
        }
    }

    internal suspend fun load(profileId: String, generation: String, source: String): ResourceBundle = withContext(Dispatchers.IO) {
        checkIdentifier(profileId)
        checkIdentifier(generation)
        val directory = File(root, "bundles/$profileId/$generation").canonicalFile
        check(directory.toPath().startsWith(root.toPath()))
        if (!directory.isDirectory) throw problem("$.resources", "Prepared resource generation is missing; refresh this profile")
        val storage = PrivateStorage(directory)
        val manifestSource = storage.read("manifest.json", 1024 * 1024)
        val manifest = JSONObject(manifestSource)
        require(manifest.getInt("schema") == 1 && manifest.getString("coreVersion") == validator.coreVersion && manifest.getString("profileId") == profileId &&
            manifest.getString("generation") == generation && manifest.getString("sourceSha256") == sha256(source)) {
            "Resource bundle does not match this profile revision"
        }
        val compiledInputSha = manifest.optString("compiledInputSha256", manifest.getString("sourceSha256"))
        val policyFingerprint = manifest.optString("policyFingerprint", "")
        require(compiledInputSha.matches(Regex("[0-9a-f]{64}")) &&
            (policyFingerprint.isEmpty() || policyFingerprint.matches(Regex("[0-9a-f]{64}")))) { "Invalid resource source lineage" }
        if (manifest.has("compiledInputSha256")) {
            val input = storage.file("input.yaml")
            require(input.isFile && !Files.isSymbolicLink(input.toPath()) && input.length() <= MAX_PROFILE_BYTES && sha256(input) == compiledInputSha) {
                "Compiled resource input changed"
            }
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
        verifyPreparedBytes(directory, manifestSource)
        ResourceBundle(generation, directory, sha256(source), yaml,
            if (manifest.isNull("nextRefreshAt")) null else manifest.getLong("nextRefreshAt"), compiledInputSha, policyFingerprint,
            manifest.optString("sourcePolicyFingerprint", policyFingerprint))
    }

    internal suspend fun refreshIntervals(bundle: ResourceBundle): Map<String, Long> = withContext(Dispatchers.IO) {
        val rows = JSONObject(PrivateStorage(bundle.directory).read("manifest.json", 1024 * 1024)).getJSONArray("records")
        (0 until rows.length()).map { rows.getJSONObject(it) }.associate { it.getString("key") to it.getLong("interval") * 1000 }
    }

    internal suspend fun refreshTimes(bundle: ResourceBundle, checked: Map<String, Long>): Map<String, Long> = withContext(Dispatchers.IO) {
        val records = JSONObject(PrivateStorage(bundle.directory).read("manifest.json", 1024 * 1024)).getJSONArray("records")
        buildMap {
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                val interval = record.getLong("interval")
                if (interval <= 0 || record.optBoolean("localOverride", false) || record.isNull("url")) continue
                val key = record.getString("key")
                put(key, maxOf(record.getLong("updatedAt"), checked[key] ?: 0L) + interval * 1000)
            }
        }
    }

    internal suspend fun currentPolicyFingerprint(bundle: ResourceBundle, sourcePolicy: String): String {
        val manifest = JSONObject(PrivateStorage(bundle.directory).read("manifest.json", 1024 * 1024))
        return resourcePolicyFingerprint(sourcePolicy, manifest.getJSONArray("records"), actual = false)
    }

    private suspend fun resourcePolicyFingerprint(sourcePolicy: String, records: JSONArray, actual: Boolean): String {
        val values = mutableListOf<List<String>>()
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val kind = record.getString("kind")
            val localId = if (record.isNull("globalLocalId")) null else record.getString("globalLocalId")
            if (kind !in setOf("ruleset", "mmdb", "geosite") || (record.isNull("url") && localId == null)) continue
            val url = if (record.isNull("url")) "" else record.getString("url")
            if (record.optString("transportOrigin") == "host-catalog") {
                require(record.getString("key") in setOf("$.mmdb", "$.asn-mmdb", "$.geosite") && kind in setOf("mmdb", "geosite")) { "Invalid Host geo resource kind" }
                val preferences = record.getString("hostGeoPreferencesSha256")
                require(preferences.matches(Regex("[0-9a-f]{64}"))) { "Invalid Host geo preference identity" }
                values += listOf(kind, url, record.getString("behavior"), record.getString("format"), record.getString("sha256"), "host-catalog", preferences)
                continue
            }
            if (!record.isNull("transportOrigin")) {
                require(record.getString("transportOrigin") in setOf("captured-transport", "verified-generation", "private-override")) {
                    "Unknown captured resource origin"
                }
                // These bytes came from this policy's captured transport, verified generation or private override.
                // An unrelated shared cache was never consulted and cannot invalidate this receipt.
                values += listOf(kind, url, record.getString("behavior"), record.getString("format"), record.getString("sha256"))
                continue
            }
            val cache = if (record.optBoolean("localOverride", false)) null else
                library.snapshot(record.getString("key"), url, record.getString("behavior"), record.optString("sourceFormat", record.getString("format")), kind, localId)
            val hash = record.getString("sha256")
            val baseline = if (record.isNull("sharedCacheBaselineSha")) hash else record.getString("sharedCacheBaselineSha")
            if (actual && !record.optBoolean("localOverride", false))
                require((cache?.sha256 ?: "") == baseline || (baseline == hash && cache == null)) { "Shared resource changed during preparation" }
            val effective = if (actual || cache == null || cache.sha256 == baseline) hash else cache.sha256
            values += listOf(kind, url, record.getString("behavior"), record.getString("format"), effective)
        }
        if (values.isEmpty() && sourcePolicy.isEmpty()) return ""
        return sha256(JSONArray(listOf(sourcePolicy, values.sortedBy { it.joinToString("\u0000") })).toString())
    }

    internal suspend fun discard(profileId: String, generation: String) = state.snapshotFence.mutation { withContext(Dispatchers.IO) {
        checkIdentifier(profileId)
        checkIdentifier(generation)
        synchronized(state) {
            try {
                val identity = "$profileId/$generation"
                if (identity in state.readers) state.pendingDiscards.add(identity)
                else removeBundle(File(root, "bundles/$identity"))
            }
            finally { state.candidates.remove("$profileId/$generation") }
        }
    } }

    internal fun committed(profileId: String, generation: String) = synchronized(state) {
        state.candidates.remove("$profileId/$generation")
        if (profileId in state.importing) state.committedImports.add(profileId)
    }

    internal fun retainSealedInputs(config: Map<String, Any?>): SealedResourceInputs {
        val retained = linkedMapOf<File, SealedResourceInputs.Origin>()
        val shared=linkedMapOf<String,SealedResourceInputs.SharedInput>()
        val privateRoot=root.parentFile.parentFile.canonicalFile
        fun privateShared(path: String): File? {
            val file=File(path).absoluteFile
            val relative=runCatching {privateRoot.toPath().relativize(file.toPath()).joinToString("/")}.getOrNull() ?: return null
            if(!relative.matches(Regex("(?:profiles/providers/[1-9][0-9]*/(?:rules|proxies)/[0-9a-f]{32}|providers/rules/[0-9a-f]{32}(?:\\.mrs)?)"))) return null
            require(file.canonicalFile==file && !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Unsafe shared resource path" }
            return file
        }
        fun captureShared(key: String,path: String,required: Boolean) {
            val file=privateShared(path) ?: return
            if(!file.isFile) {require(!required) { "Shared local resource is missing" };return}
            require(file.length()<=64L*1024*1024) { "Shared resource exceeds budget" }
            val receiptName=java.security.MessageDigest.getInstance("MD5").digest("native-receipt/$key".toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
            val receipt=checkNotNull(privateShared(File(file.parentFile.parentFile,"rules/$receiptName").path))
            val data=if(receipt.isFile) JSONObject(receipt.inputStream().use {it.readBounded(1024*1024).strictUtf8()}) else null
            if(data!=null) {
                require(data.getInt("schema")==1 && data.getString("key")==key && File(privateRoot,data.getString("file")).absoluteFile==file &&
                    file.length()==data.getLong("bytes") && sha256(file)==data.getString("sha256")) { "Shared resource receipt changed" }
            }
            shared[key]?.let {require(File(it.file.sourcePath).absoluteFile==file) { "Ambiguous shared source file" }}
            shared[key]=SealedResourceInputs.SharedInput(key,SealedFlutterResource("assets/shared-${shared.size}",file.path,sha256(file)),
                data?.let {SealedFlutterResource("receipts/shared-${shared.size}",receipt.path,sha256(receipt))},data)
            val dependencies=data?.optJSONArray("dependencies")
            if(dependencies!=null) for(index in 0 until dependencies.length()) {
                val child=dependencies.getJSONObject(index)
                val childKey=child.getString("key")
                require(childKey.startsWith("$key.proxies[")) { "Foreign shared resource dependency" }
                val target=checkNotNull(privateShared(File(privateRoot,child.getString("file")).path))
                require(target.isFile && target.length()==child.getLong("bytes") && sha256(target)==child.getString("sha256")) { "Shared dependency content changed" }
                shared[childKey]=SealedResourceInputs.SharedInput(childKey,SealedFlutterResource("assets/shared-${shared.size}",target.path,sha256(target)),null,child)
            }
        }
        config["aerove-native-resource-manifest"]?.let { raw ->
            require(raw is String) { "Shared resource manifest path must be text" }
            val file=checkNotNull(privateShared(raw)) { "Unsafe shared resource manifest path" }
            require(file.isFile && file.length()<=2*1024*1024) { "Shared resource manifest is missing or over budget" }
            val data=JSONObject(file.inputStream().use {it.readBounded(2*1024*1024).strictUtf8()})
            require(data.getInt("schema")==1 && data.getString("sourceProfileUuid").matches(Regex("[0-9a-f-]{36}"))) { "Shared resource manifest identity changed" }
            val entries=data.getJSONArray("inputs");require(entries.length()<=4096) { "Shared resource manifest count exceeds budget" }
            val indexedKeys=hashSetOf<String>()
            for(index in 0 until entries.length()) {
                val entry=entries.getJSONObject(index);val key=entry.getString("key")
                require(indexedKeys.add(key)) { "Ambiguous shared resource key" }
                val input=checkNotNull(privateShared(File(privateRoot,entry.getString("file")).path))
                val receipt=checkNotNull(privateShared(File(privateRoot,entry.getString("receiptFile")).path))
                require(input.isFile && input.length()==entry.getLong("bytes") && sha256(input)==entry.getString("sha256") &&
                    receipt.isFile && sha256(receipt)==entry.getString("receiptSha256")) { "Shared resource index checksum changed" }
                captureShared(key,input.path,true)
            }
        }
        (config["proxies"] as? List<*>)?.forEachIndexed {index,raw ->
            val proxy=raw as? Map<*,*> ?: return@forEachIndexed
            listOf("tls-cert","tls-key","ca").forEach {field ->(proxy[field] as? String)?.let {captureShared("$.proxies[$index].$field",it,true)} }
        }
        listOf("mmdb","asn-mmdb","geosite").forEach {key ->(config[key] as? String)?.let {captureShared("$.$key",it,true)} }
        for(namespace in listOf("proxy-providers","rule-providers")) {
            (config[namespace] as? Map<*,*>)?.forEach {(name,raw) ->
                val provider=raw as? Map<*,*> ?: return@forEach
                (provider["path"] as? String)?.let {captureShared("$.$namespace.$name",it,provider["type"]=="file")}
            }
        }

        val prefix = File(root, "bundles").path.replace('\\', '/') + "/"
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.values.forEach(::visit)
                is List<*> -> value.forEach(::visit)
                is String -> if (value.replace('\\', '/').startsWith(prefix)) {
                    val parts = value.replace('\\', '/').removePrefix(prefix).split('/')
                    require(parts.size >= 3) { "Invalid sealed resource path" }
                    checkIdentifier(parts[0]); checkIdentifier(parts[1])
                    val directory = File(root, "bundles/${parts[0]}/${parts[1]}").absoluteFile
                    val origin = retained.getOrPut(directory) {
                        val lease = retainGeneration(directory)
                        try { SealedResourceInputs.Origin(lease, SealedResourceInputs.readFiles(directory)) }
                        catch (failure: Throwable) { lease.close(); throw failure }
                    }
                    require(origin.files.any { it.relativePath.startsWith("assets/") && File(it.sourcePath).absoluteFile == File(value).absoluteFile }) {
                        "Only manifest-authorized sealed assets may be used as resource input"
                    }
                }
            }
        }
        try {
            (config["proxies"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { proxy ->
                listOf("tls-cert", "tls-key", "ca").forEach { visit(proxy[it]) }
            }
            listOf("mmdb", "asn-mmdb", "geosite").forEach { visit(config[it]) }
            listOf("proxy-providers", "rule-providers").forEach { namespace ->
                (config[namespace] as? Map<*, *>)?.values?.filterIsInstance<Map<*, *>>()
                    ?.filter { it["type"] == "file" }?.forEach { visit(it["path"]) }
            }
            return SealedResourceInputs(retained.values.toList(),shared.values.toList())
        }
        catch (failure: Throwable) { retained.values.toList().asReversed().forEach { it.lease.close() }; throw failure }
    }

    internal fun retainGeneration(directory: File): ResourceGenerationLease = state.snapshotFence.mutation { synchronized(state) {
        val profileId = directory.parentFile.name
        val generation = directory.name
        checkIdentifier(profileId); checkIdentifier(generation)
        val expected = File(root, "bundles/$profileId/$generation").absoluteFile
        require(directory.absoluteFile == expected && expected.toPath() == expected.toPath().toRealPath() && expected.isDirectory) {
            "Only this manager's existing immutable generation may be borrowed"
        }
        val identity = "$profileId/$generation"
        state.readers[identity] = Math.addExact(state.readers[identity] ?: 0, 1)
        ResourceGenerationLease(expected) { synchronized(state) {
            val count = checkNotNull(state.readers[identity])
            if (count == 1) {
                if (identity in state.pendingDiscards) {
                    removeBundle(expected)
                    state.pendingDiscards.remove(identity)
                }
                state.readers.remove(identity)
            } else state.readers[identity] = count - 1
        } }
    } }

    internal suspend fun <T> importArchive(profileId: String, input: InputStream, accept: suspend (String) -> T): T =
        importArchiveDecoded(profileId, input, { it.strictUtf8() }, accept)

    internal suspend fun <T> importArchiveDecoded(profileId: String, input: InputStream,
        decode: suspend (ByteArray) -> String, accept: suspend (String) -> T): T = state.snapshotFence.mutation {
        checkIdentifier(profileId)
        synchronized(state) { check(state.importing.add(profileId)) }
        val staging = File(root, "imports/.pending-$profileId")
        val destination = File(root, "imports/$profileId")
        val archive = File(root, "archives/$profileId.zip")
        var completed = false
        try {
            val source = withContext(Dispatchers.IO) {
                val bytes = ProfileArchive.extractBytes(input, archive, staging)
                val yaml = try { decode(bytes) } finally { bytes.fill(0) }
                require(yaml.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES) { "Profile is too large" }
                PrivateStorage(staging).write("config.yaml", yaml)
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                yaml
            }
            val result = accept(source)
            completed = true
            result
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

    internal suspend fun maintenanceCacheInventory(): Pair<Long, String> = library.withRestoreTransaction {
        var bytes = 0L
        val indices = mutableListOf<String>()
        val context = currentCoroutineContext()
        listOf("bundles", "imports", "archives", "library").forEach { name ->
            val directory = File(root, name)
            require(!Files.isSymbolicLink(directory.toPath())) { "Unsafe resource cache directory" }
            if (directory.isDirectory) Files.walk(directory.toPath()).use { paths ->
                paths.forEach { path ->
                    context.ensureActive()
                    if (!Files.isSymbolicLink(path) && Files.isRegularFile(path)) {
                        bytes = Math.addExact(bytes, Files.size(path))
                        if (name == "library" && path.parent == directory.toPath() &&
                            path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json"))) {
                            indices.add("${path.fileName}:${sha256(path.toFile())}")
                        }
                    }
                }
            }
        }
        bytes to sha256(indices.sorted().joinToString("\n"))
    }

    internal fun requireMaintenanceIdle() = synchronized(state) {
        check(state.importing.isEmpty() && state.candidates.isEmpty()) { "Resource preparation is still active" }
    }

    internal fun prune(retained: Map<String, Set<String>>, importProfiles: Set<String>) = state.snapshotFence.mutation { synchronized(state) {
        val protectedGenerations = state.candidates + state.readers.keys + retained.flatMap { (id, generations) -> generations.map { "$id/$it" } }
        File(root, "bundles").listFiles()?.forEach { profile ->
            if (!isIdentifier(profile.name) || Files.isSymbolicLink(profile.toPath()) || !profile.isDirectory) return@forEach
            profile.listFiles()?.forEach generationLoop@ { directory ->
                val generation = directory.name.removePrefix(".pending-")
                if (!isIdentifier(generation) || Files.isSymbolicLink(directory.toPath())) return@generationLoop
                if ("${profile.name}/$generation" !in protectedGenerations) removeBundle(directory)
            }
            if (profile.list()?.isEmpty() == true && protectedGenerations.none { it.startsWith("${profile.name}/") }) profile.delete()
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
    } }

    private fun localFile(profileId: String, path: String): File = privateResource(File(root, "imports/$profileId"), path)

    /** The caller owns the profile domain transaction, including its generation CAS. */
    internal suspend fun <T> withResourceOverride(profileId: String, keys: Set<String>?, input: InputStream? = null,
        sourceId: String? = null,
        accept: suspend () -> T): T = state.snapshotFence.mutation { withContext(Dispatchers.IO) {
        checkIdentifier(profileId)
        require(input == null || keys?.size == 1)
        val directory = localFile(profileId, ".overrides/sentinel").parentFile!!.apply { mkdirs() }
        val targets = if (input != null) keys!!.map { localFile(profileId, overridePath(it, checkNotNull(sourceId))) }
            else directory.listFiles().orEmpty().filter { file -> file.extension == "bin" &&
                (keys == null || keys.any { file.name.startsWith("${sha256(it)}-") }) }
        val backups = targets.filter(File::isFile).associateWith { File(directory, ".previous-${UUID.randomUUID()}") }
        val moved = mutableSetOf<File>()
        var writing = false
        var success = false
        try {
            backups.forEach { (target, backup) ->
                Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                moved += target
            }
            if (input != null) { writing = true; input.use { copyResource(it, targets.single(), MAX_GEO_BYTES) } }
            val value = accept()
            success = true
            value
        } finally {
            withContext(NonCancellable) {
                if (!success) {
                    if (writing) targets.forEach { it.delete() }
                    backups.forEach { (target, backup) ->
                        if (target in moved) Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                backups.values.forEach { it.delete() }
            }
        }
    } }

    /** Profiles-only restore keeps imported bytes private to each new profile, without mutating shared indexes. */
    internal fun localizeLibraryPayloads(source: String, payloads: List<CachedLibraryRule>, geoUrls: Map<String, String>): Map<String, File> {
        val config = ConfigYaml.parse(source)
        val result = linkedMapOf<String, File>()
        fun bind(key: String, kind: String, url: String, behavior: String = "", format: String = "") {
            val value = payloads.firstOrNull { it.resource.let { item -> item.kind == kind && item.url == url && item.behavior == behavior && item.format == format } } ?: return
            result[overridePath(key, materializedSourceIdentity(key, kind, url, behavior, format))] = value.file
        }
        val ruleGeo = GeoRuleScan()
        (config["rules"] as? List<*>)?.filterIsInstance<String>()?.let(ruleGeo::addAll)
        (config["rule-providers"] as? Map<*, *>)?.forEach { (name, raw) ->
            val provider = raw as? Map<*, *> ?: return@forEach
            val url = provider["url"] as? String ?: return@forEach
            if (provider["type"] != "http") return@forEach
            val behavior = provider["behavior"] as? String ?: "domain"
            val format = provider["format"] as? String ?: "yaml"
            bind("$.rule-providers.$name", "ruleset", url, behavior, format)
            payloads.firstOrNull { it.resource.kind == "ruleset" && it.resource.url == url && it.resource.behavior == behavior && it.resource.format == format }
                ?.takeIf { format != "mrs" }?.let { cached ->
                    // Scanned per rule line as preparation does, so a later line's GEOIP or GEOSITE still binds its database.
                    if (format == "text") cached.file.forEachRuleLine(MAX_RULE_INPUT_BYTES) { _, line -> ruleGeo.add(line) }.requireEntryBound("$.rule-providers.$name")
                    else (cached.file.readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"] as? List<*>)?.filterIsInstance<String>()?.let(ruleGeo::addAll)
                }
        }
        val dns = config["dns"] as? Map<*, *>
        val fallback = dns?.get("fallback") as? List<*>
        val filter = dns?.get("fallback-filter") as? Map<*, *>
        listOf(Triple("mmdb", ruleGeo.country || (!fallback.isNullOrEmpty() && filter?.get("geoip") != false), DEFAULT_COUNTRY_URL),
            Triple("asn-mmdb", ruleGeo.asn, DEFAULT_ASN_URL),
            Triple("geosite", ruleGeo.site, DEFAULT_GEOSITE_URL)).forEach { (key, needed, defaultUrl) ->
            if (needed || config["$key-download-url"] != null) {
                val url = config["$key-download-url"] as? String ?: geoUrls[key] ?: defaultUrl
                bind("$.$key", if (key == "geosite") "geosite" else "mmdb", url)
            }
        }
        return result
    }

    private fun materializedSourceIdentity(key: String, kind: String, location: String, behavior: String, format: String): String =
        sha256(JSONArray(listOf(key, kind, location, behavior, format)).toString())

    private fun overridePath(key: String, sourceId: String): String {
        require(key.startsWith("$.") && key.length <= 2048) { "Invalid resource key" }
        require(sourceId.matches(Regex("[0-9a-f]{64}"))) { "Invalid resource source identity" }
        return ".overrides/${sha256(key)}-$sourceId.bin"
    }

    private fun yamlRuleLines(file: File, path: String): List<String> {
        val content = file.readRuleYaml(MAX_RULE_INPUT_BYTES)
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

    /** The same per-line matches as collecting every rule line first, without keeping the lines. */
    internal fun requiresGeoCapture(source: String): Boolean {
        val root = ConfigYaml.parse(source)
        return initialGeoScan(root).neededKeys(root).isNotEmpty()
    }

    private fun initialGeoScan(root: Map<*, *>): GeoRuleScan = GeoRuleScan().apply {
        (root["rules"] as? List<*>)?.filterIsInstance<String>()?.let(::addAll)
        (root["rule-providers"] as? Map<*, *>)?.values?.filterIsInstance<Map<*, *>>()?.forEach { provider ->
            if (provider["behavior"] == "classical") {
                ((provider["inline-rules"] ?: provider["payload"]) as? List<*>)?.filterIsInstance<String>()?.let(::addAll)
            }
        }
    }

    private class GeoRuleScan {
        var country = false; private set
        var asn = false; private set
        var site = false; private set
        fun add(line: String) {
            country = country || GEOIP.containsMatchIn(line)
            asn = asn || ASN.containsMatchIn(line)
            site = site || GEOSITE.containsMatchIn(line)
        }
        fun addAll(lines: Iterable<String>) = lines.forEach(::add)
        fun neededKeys(root: Map<*, *>): Set<String> {
            val dns = root["dns"] as? Map<*, *>
            val fallback = dns?.get("fallback") as? List<*>
            val filter = dns?.get("fallback-filter") as? Map<*, *>
            return buildSet {
                if (country || (!fallback.isNullOrEmpty() && filter?.get("geoip") != false)) add("mmdb")
                if (asn) add("asn-mmdb")
                if (site || (dns?.get("nameserver-policy") as? Map<*, *>)?.keys?.any { (it as? String)?.startsWith("geosite:", ignoreCase = true) == true } == true) add("geosite")
            }
        }
    }

    internal suspend fun verifyMigrationLibrarySources(sources: List<LibraryRuleSource>) {
        if (!File(root, "library").isDirectory) {
            require(sources.none { it.localId != null }) { "runtime/resources/library: missing bound local original" }
        } else library.withSnapshot(sources) { }
    }

    internal suspend fun <T> withMigrationSnapshot(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        val existingLibrary = if (File(root, "library").isDirectory) library else null
        state.snapshotFence.snapshot {
            synchronized(state) {
                check(state.importing.isEmpty() && state.candidates.isEmpty() && state.readers.isEmpty()) {
                    "runtime/resources: active preparation, import or generation reader"
                }
            }
            if (existingLibrary != null) existingLibrary.withMigrationSnapshot(action) else action()
        }
    }

    private class BundleState {
        val snapshotFence = ResourceMutationFence()
        val preparation = Semaphore(1)
        val candidates = mutableSetOf<String>()
        val readers = mutableMapOf<String, Int>()
        val pendingDiscards = mutableSetOf<String>()
        val importing = mutableSetOf<String>()
        val committedImports = mutableSetOf<String>()
    }

    private data class Record(
        val key: String, val kind: String, val sourceId: String, val sha256: String, val bytes: Long,
        val file: String, val behavior: String, val format: String, val url: String?, val localPath: String?, val interval: Long,
        val updatedAt: Long,
        val subscriptionUserInfo: String? = null,
        val localOverride: Boolean = false,
        val sharedCacheBaselineSha: String? = null,
        val sourceFormat: String = format,
        val globalLocalId: String? = null,
        val transportOrigin: String? = null,
        val hostGeoPreferencesSha256: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().put("key", key).put("kind", kind).put("sourceSha256", sourceId)
            .put("sha256", sha256).put("bytes", bytes).put("file", file).put("behavior", behavior)
            .put("format", format).put("url", url ?: JSONObject.NULL).put("localPath", localPath ?: JSONObject.NULL)
            .put("interval", interval).put("updatedAt", updatedAt).put("subscriptionUserInfo", subscriptionUserInfo ?: JSONObject.NULL)
            .put("localOverride", localOverride).put("sharedCacheBaselineSha", sharedCacheBaselineSha ?: JSONObject.NULL)
            .put("sourceFormat", sourceFormat).put("globalLocalId", globalLocalId ?: JSONObject.NULL)
            .apply { transportOrigin?.let { put("transportOrigin", it) }; hostGeoPreferencesSha256?.let { put("hostGeoPreferencesSha256", it) } }
    }

    companion object {
        internal fun migrationFence(root: File): ResourceMutationFence =
            states.computeIfAbsent(root.canonicalPath) { BundleState() }.snapshotFence
        const val DEFAULT_COUNTRY_URL = "https://github.com/Loyalsoldier/geoip/releases/latest/download/Country.mmdb"
        const val DEFAULT_ASN_URL = "https://git.io/GeoLite2-ASN.mmdb"
        const val DEFAULT_GEOSITE_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/202406182210/geosite.dat"
        private const val MAX_GEO_BYTES = 64L * 1024 * 1024
        private const val MAX_BUNDLE_BYTES = 256L * 1024 * 1024
        private const val MAX_RESOURCES = 256
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

/** Preserve an actually empty local source without inventing a newline; ordinary downloads stay strict. */
internal suspend fun copyLocalRuleResource(input: InputStream, destination: File, limit: Long): ResourceDigest {
    currentCoroutineContext().ensureActive()
    val first = input.read()
    if (first >= 0) return copyResource(java.io.SequenceInputStream(java.io.ByteArrayInputStream(byteArrayOf(first.toByte())), input), destination, limit)
    require(!destination.exists()) { "Immutable resource already exists" }
    java.io.FileOutputStream(destination).use { it.fd.sync() }
    return ResourceDigest(sha256(""), 0)
}

internal class ResourceMutationFence {
    private var writers = 0
    private var exporting = false
    @PublishedApi internal fun beginMutation() = synchronized(this) {
        check(!exporting) { "runtime/resources: migration snapshot is active" }
        writers++
    }
    @PublishedApi internal fun endMutation() = synchronized(this) { check(writers > 0); writers-- }
    inline fun <T> mutation(action: () -> T): T {
        beginMutation()
        try { return action() } finally { endMutation() }
    }
    suspend fun <T> snapshot(action: suspend () -> T): T {
        synchronized(this) {
            check(!exporting && writers == 0) { "runtime/resources: active resource mutation" }
            exporting = true
        }
        try { return action() } finally { synchronized(this) { exporting = false } }
    }
}
