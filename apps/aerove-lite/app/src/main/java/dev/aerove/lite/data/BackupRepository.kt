package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.profilebuilder.BuilderResources
import dev.aerove.lite.profilebuilder.mergeForRestore
import dev.aerove.lite.profilebuilder.referencedBy
import dev.aerove.lite.resources.LibraryRuleSource
import dev.aerove.lite.resources.ManagedRuleResource
import dev.aerove.lite.resources.CachedLibraryRule
import dev.aerove.lite.resources.GeoResourceKind
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class BackupRestoreScope { All, ProfilesOnly }
enum class BackupRestoreStrategy { Compatible, Override }

data class BackupLimits(
    val archiveBytes: Long = 256L * 1024 * 1024,
    val expandedBytes: Long = 512L * 1024 * 1024,
    val entryBytes: Long = 64L * 1024 * 1024,
    val entries: Int = 12000,
) {
    init {
        require(archiveBytes in 1..256L * 1024 * 1024 && expandedBytes in 1..512L * 1024 * 1024 &&
            entryBytes in 1..64L * 1024 * 1024 && entries in 1..12000) { "Invalid backup limits" }
    }
}

data class BackupFileRecord(val path: String, val bytes: Long, val sha256: String)
data class AppBackupManifest(val createdAt: Long, val profiles: Int, val files: List<BackupFileRecord>)
data class BackupRestoreResult(val profileIds: Map<String, String>, val restoredSettings: Boolean)

internal data class BackupProfileSource(val id: String, val summary: ProfileSummary, val source: String, val localResources: Map<String, File>, val sourceMetadata: ProfileSourceMetadata? = null)
internal data class BackupProfileInput(
    val id: String, val name: String, val url: String?, val updatedAt: Long,
    val source: String, val localResources: Map<String, File>,
    val autoUpdate: Boolean = true, val updateIntervalMillis: Long = 86_400_000L,
    val lastSuccessfulUpdateAt: Long? = null, val sourceIdentity: String? = null,
    val subscriptionUserInfo: String? = null, val contentDisposition: String? = null,
    val custom: Boolean = false,
    val sourceMetadata: ProfileSourceMetadata? = null,
    val currentProxyGroup: String? = null, val unfoldedProxyGroups: Set<String> = emptySet(),
)

class BackupRepository(
    privateDirectory: File,
    private val profiles: ProfileRepository,
    private val settingsStore: LiteSettingsStore,
    private val limits: BackupLimits = BackupLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = privateDirectory.canonicalFile
    private val storage = PrivateStorage(File(root, "backups"))

    suspend fun readWebDav(): WebDavBackupConfig? = privateDomainTransaction(root) {
        storage.transaction { if (storage.file("webdav.json").exists()) WebDavBackupConfig.decode(storage.read("webdav.json", 16384)) else null }
    }

    suspend fun saveWebDav(config: WebDavBackupConfig?) = privateDomainTransaction(root) {
        storage.transaction {
            if (config == null) Files.deleteIfExists(storage.file("webdav.json").toPath())
            else storage.write("webdav.json", config.encode())
            Unit
        }
    }

    suspend fun createBackup(destination: File): AppBackupManifest = work { stage ->
        val payload = File(stage, "payload").apply { check(mkdir()) }
        val records = linkedMapOf<String, File>()
        val copiedBytes = longArrayOf(0)
        val metadata = JSONArray()
        val resourceMetadata = JSONArray()
        var selected: String? = null
        privateDomainTransaction(root) {
            val snapshot = LiteSettingsStore(File(stage, "settings-snapshot"))
            val currentSettings = settingsStore.read()
            snapshot.save(currentSettings, vpnAuthorized = true)
            copySnapshot(File(stage, "settings-snapshot/settings/settings.json"), "settings.json", payload, records, copiedBytes)
            readWebDav()?.let { config ->
                val file = File(payload, "webdav.json").apply { writeText(config.encode()) }
                records["webdav.json"] = file
                copiedBytes[0] += file.length()
            }
            profiles.withBackupSnapshot { entries, current ->
                require(entries.size <= 1000 && entries.map { it.id }.distinct().size == entries.size) { "Invalid profile snapshot" }
                selected = current
                entries.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    val configPath = "profiles/${entry.id}/config.yaml"
                    val config = privateResource(payload, configPath).apply { parentFile!!.mkdirs(); writeText(entry.source) }
                    require(config.length() <= MAX_PROFILE_BYTES) { "Profile is too large" }
                    records[configPath] = config
                    copiedBytes[0] += config.length()
                    entry.sourceMetadata?.let { sourceMetadata ->
                        sourceMetadata.verify(entry.source)
                        val privatePath = "profiles/${entry.id}/source-private.json"
                        val text = sourceMetadata.json().toString()
                        val size = text.toByteArray(Charsets.UTF_8).size
                        require(size <= MAX_SOURCE_METADATA_BYTES && size <= limits.entryBytes && copiedBytes[0] + size <= limits.expandedBytes) {
                            "Private profile metadata size limit exceeded"
                        }
                        val file = privateResource(payload, privatePath).apply { writeText(text) }
                        records[privatePath] = file
                        copiedBytes[0] += file.length()
                    }
                    metadata.put(JSONObject().put("id", entry.id).put("name", entry.summary.name)
                        .put("url", entry.summary.url ?: JSONObject.NULL).put("updatedAt", entry.summary.updatedAt)
                        .put("autoUpdate", entry.summary.autoUpdate).put("updateIntervalMillis", entry.summary.updateIntervalMillis)
                        .put("lastSuccessfulUpdateAt", entry.summary.lastSuccessfulUpdateAt ?: JSONObject.NULL)
                        .put("sourceIdentity", entry.summary.sourceIdentity)
                        .put("subscriptionUserInfo", entry.summary.subscriptionUserInfo ?: JSONObject.NULL)
                        .put("contentDisposition", entry.summary.contentDisposition ?: JSONObject.NULL)
                        .put("custom", entry.summary.custom)
                        .putProxyGroupUi(entry.summary.currentProxyGroup, entry.summary.unfoldedProxyGroups))
                    require(entry.localResources.size <= 128) { "Too many local resources for a profile" }
                    require(entry.localResources.values.sumOf { it.length() } <= 256L * 1024 * 1024) { "Profile resource size limit exceeded" }
                    entry.localResources.forEach { (path, file) ->
                        copySnapshot(file, "profiles/${entry.id}/resources/$path", payload, records, copiedBytes)
                    }
                }
                val profileIds = entries.associate { it.summary.id to it.id }
                val overrides = libraryFile(OVERRIDES_PATH)
                if (overrides.isFile) {
                    val state = OverrideRepository.decode(readLibrary(overrides))
                    writeLibrarySnapshot(OverrideRepository.encode(OverrideRepository.remapProfiles(state, profileIds)),
                        OVERRIDES_PATH, payload, records, copiedBytes)
                }
                val builder = libraryFile(BUILDER_PATH)
                val resources = if (builder.isFile) BuilderResources.parse(readLibrary(builder)) else BuilderResources()
                resources.referencedBy(entries.map { it.source })
                if (builder.isFile) writeLibrarySnapshot(resources.yaml(), BUILDER_PATH, payload, records, copiedBytes)
                profiles.withBackupLibrarySnapshot(librarySources(resources) + entries.flatMap { localGlobalRuleSources(it.source) } + GeoResourceKind.entries.map { kind ->
                    LibraryRuleSource(kind.wire, currentSettings.geoResources.urls.getValue(kind), "", "", kind.assetKind)
                }) { cached ->
                    cached.forEach { value ->
                        val path = "library/resources/${resourceMetadata.length()}.bin"
                        copySnapshot(value.file, path, payload, records, copiedBytes)
                        resourceMetadata.put(encodeLibraryResource(value.resource).put("path", path))
                    }
                }
            }
        }
        val files = records.map { (name, file) -> BackupFileRecord(name, file.length(), sha256(file)) }
        checkLimits(files)
        val createdAt = clock()
        val manifest = JSONObject().put("format", FORMAT).put("schema", 1).put("createdAt", createdAt)
            .put("profiles", metadata).put("selectedId", selected ?: JSONObject.NULL).put("resourcePayloads", resourceMetadata)
            .put("files", JSONArray(files.map { JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256) }))
        val document = File(payload, "manifest.json").apply { writeText(manifest.toString()) }
        require(document.length() <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
        require(files.sumOf { it.bytes } + document.length() <= limits.expandedBytes) { "Backup expanded size limit exceeded" }
        val target = destination.absoluteFile
        require(!Files.isSymbolicLink(target.toPath()) && (!target.canonicalFile.toPath().startsWith(root.toPath()) ||
            (target.canonicalFile.parentFile == storage.root && target.name.endsWith(".zip") && !target.name.startsWith('.')))) { "Backup destination overlaps application data" }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temporary = File(target.parentFile, ".backup-${UUID.randomUUID()}")
        try {
            writeZip(temporary, mapOf("manifest.json" to document) + records, limits.archiveBytes)
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        AppBackupManifest(createdAt, metadata.length(), files)
    }

    suspend fun restore(
        input: InputStream,
        scope: BackupRestoreScope = BackupRestoreScope.All,
        strategy: BackupRestoreStrategy = BackupRestoreStrategy.Compatible,
        vpnAuthorized: Boolean = false,
    ): BackupRestoreResult = work { stage ->
        val payload = File(stage, "payload")
        input.use { ProfileArchive.extractFiles(it, File(stage, "download.zip"), payload, ProfileArchive.Limits(
            limits.archiveBytes, limits.expandedBytes, limits.entryBytes, limits.entries, "manifest.json", MAX_MANIFEST_BYTES.toLong(),
        )) }
        val json = JSONObject(File(payload, "manifest.json").inputStream().use { it.readBounded(MAX_MANIFEST_BYTES).strictUtf8() })
        require(json.getString("format") == FORMAT && json.getInt("schema") == 1) { "Unsupported application backup format or version" }
        require(json.getLong("createdAt") >= 0) { "Invalid backup timestamp" }
        val rows = json.getJSONArray("profiles")
        require(rows.length() <= 1000) { "Too many backup profiles" }
        val ids = (0 until rows.length()).map { index -> rows.getJSONObject(index).getString("id").also(::checkId) }
        require(ids.distinct().size == ids.size) { "Duplicate backup profile id" }
        val selected = if (json.isNull("selectedId")) null else json.getString("selectedId")
        require(selected == null || selected in ids) { "Invalid backup selection" }
        val listed = json.getJSONArray("files")
        val records = (0 until listed.length()).map { index ->
            val record = listed.getJSONObject(index)
            BackupFileRecord(record.getString("path"), record.getLong("bytes"), record.getString("sha256"))
        }
        checkLimits(records)
        val resources = json.optJSONArray("resourcePayloads") ?: JSONArray()
        require(resources.length() <= limits.entries) { "Too many library payloads" }
        val libraryPayloads = List(resources.length()) { index ->
            val value = resources.getJSONObject(index)
            val path = value.getString("path")
            require(path.matches(Regex("library/resources/[0-9]+\\.bin"))) { "Invalid library payload path" }
            path to decodeLibraryResource(value)
        }
        require(libraryPayloads.map { it.first }.distinct().size == libraryPayloads.size) { "Duplicate library payload" }
        val names = records.map { it.path }.toSet()
        val expectedConfigs = ids.map { "profiles/$it/config.yaml" }.toSet()
        require(names.contains("settings.json") && names.containsAll(expectedConfigs)) { "Backup payload is incomplete" }
        require(names.all { name -> name == "settings.json" || name == "webdav.json" || name in LIBRARY_PATHS || name in libraryPayloads.map { it.first } || name in expectedConfigs || ids.any { name == "profiles/$it/source-private.json" } ||
            ids.any { name.startsWith("profiles/$it/resources/") } }) { "Unexpected application backup payload" }
        val actual = Files.walk(payload.toPath()).use { paths -> paths.filter { Files.isRegularFile(it) }
            .map { payload.toPath().relativize(it).joinToString("/") }.collect(java.util.stream.Collectors.toSet()) }
        require(actual == names + "manifest.json") { "Backup manifest does not describe all actual files" }
        records.forEach { record ->
            currentCoroutineContext().ensureActive()
            val file = privateResource(payload, record.path)
            require(file.isFile && file.length() == record.bytes && sha256(file) == record.sha256) { "Backup payload size/hash mismatch" }
        }
        libraryPayloads.forEach { (path, resource) ->
            val record = records.singleOrNull { it.path == path }
            require(record != null && record.bytes == resource.bytes && record.sha256 == resource.sha256) { "Library metadata does not match payload" }
        }
        require(File(payload, "settings.json").length() <= 128 * 1024) { "Backup settings are too large" }
        val stagedSettings = File(stage, "settings-restore/settings").apply { mkdirs() }
        Files.copy(File(payload, "settings.json").toPath(), File(stagedSettings, "settings.json").toPath())
        val parser = LiteSettingsStore(stagedSettings.parentFile!!)
        val restoredSettings = parser.read()
        parser.save(restoredSettings, vpnAuthorized = scope == BackupRestoreScope.ProfilesOnly || vpnAuthorized)
        val webdav = if ("webdav.json" in names) {
            WebDavBackupConfig.decode(File(payload, "webdav.json").inputStream().use { it.readBounded(16384).strictUtf8() })
        } else null
        val importedOverrides = if (OVERRIDES_PATH in names) OverrideRepository.decode(readLibrary(privateResource(payload, OVERRIDES_PATH))) else null
        val importedBuilder = if (BUILDER_PATH in names) BuilderResources.parse(readLibrary(privateResource(payload, BUILDER_PATH))) else null
        val incoming = ids.mapIndexed { index, id ->
            val row = rows.getJSONObject(index)
            val sourceFile = File(payload, "profiles/$id/config.yaml")
            val source = sourceFile.inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8PreservingBom() }
            val metadataPath = "profiles/$id/source-private.json"
            val sourceMetadata = if (metadataPath in names) ProfileSourceMetadata.parse(privateResource(payload, metadataPath)
                .inputStream().use { it.readBounded(MAX_SOURCE_METADATA_BYTES).strictUtf8() }).also { it.verify(source) } else null
            val prefix = "profiles/$id/resources/"
            val local = records.filter { it.path.startsWith(prefix) }
            require(local.size <= 128 && local.sumOf { it.bytes } <= 256L * 1024 * 1024) { "Profile resource limits exceeded" }
            val resources = local.associate { it.path.removePrefix(prefix) to privateResource(payload, it.path) }
            val proxyGroupUi = row.readProxyGroupUi()
            BackupProfileInput(id, row.getString("name"), if (row.isNull("url")) null else row.getString("url"),
                row.getLong("updatedAt"), source, resources,
                row.optBoolean("autoUpdate", true), row.optLong("updateIntervalMillis", 86_400_000L),
                if (row.isNull("lastSuccessfulUpdateAt")) null else row.getLong("lastSuccessfulUpdateAt"),
                if (row.isNull("sourceIdentity")) null else row.getString("sourceIdentity"),
                if (row.isNull("subscriptionUserInfo")) null else row.getString("subscriptionUserInfo"),
                if (row.isNull("contentDisposition")) null else row.getString("contentDisposition"),
                if (row.has("custom")) row.get("custom").also { require(it is Boolean) { "Invalid profile custom flag" } } as Boolean else false,
                sourceMetadata, proxyGroupUi.current, proxyGroupUi.unfolded)
        }
        currentCoroutineContext().ensureActive()
        val mapping = privateDomainTransaction(root) {
            val currentBuilder = libraryFile(BUILDER_PATH).let { if (it.isFile) BuilderResources.parse(readLibrary(it)) else BuilderResources() }
            val localIds = libraryPayloads.mapNotNull { it.second.localId }.distinct().associateWith { java.util.UUID.randomUUID().toString() }
            val importedPayloads = libraryPayloads.map { (path, value) -> path to value.copy(localId = value.localId?.let(localIds::getValue)) }
            val chosenBuilder = importedBuilder?.let { if (scope == BackupRestoreScope.ProfilesOnly) it.referencedBy(incoming.map { entry -> entry.source }) else it }?.let { chosen ->
                chosen.copy(ruleProviders = chosen.ruleProviders.map { entry ->
                    val id = if (entry.fields["type"] == "file") globalRuleLocalId(entry.fields["path"] as? String) else null
                    if (id == null) entry else entry.copy(fields = entry.fields + ("path" to "$GLOBAL_RULE_PATH_PREFIX${localIds.getValue(id)}"))
                })
            }
            val mergedBuilder = chosenBuilder?.let { imported ->
                (if (scope == BackupRestoreScope.All && strategy == BackupRestoreStrategy.Override) BuilderResources() else currentBuilder).mergeForRestore(imported)
            }
            var restoredInputs = incoming.map { entry ->
                var rewritten = mergedBuilder?.rewriteSource(entry.source) ?: entry.source
                val localSources = localGlobalRuleSources(rewritten)
                if (localSources.any { it.localId in localIds }) {
                    var document = dev.aerove.lite.profilebuilder.ProfileBuilderDocument.parse(rewritten)
                    for (local in localSources) {
                        val nextId = local.localId?.let(localIds::get) ?: continue
                        val values = document.entries(dev.aerove.lite.profilebuilder.BuilderSection.RuleProviders)
                        val position = values.indexOfFirst { it.name == local.name }
                        require(position >= 0) { "Restored local provider is missing" }
                        document = document.put(dev.aerove.lite.profilebuilder.BuilderSection.RuleProviders, position, local.name,
                            values[position].fields + ("path" to "$GLOBAL_RULE_PATH_PREFIX$nextId"))
                    }
                    rewritten = document.yaml()
                }
                entry.copy(source = rewritten, sourceMetadata = entry.sourceMetadata?.rewritten(rewritten))
            }
            (mergedBuilder?.resources ?: currentBuilder).referencedBy(restoredInputs.map { it.source })
            val currentOverrides = libraryFile(OVERRIDES_PATH).let { if (it.isFile) OverrideRepository.decode(readLibrary(it)) else dev.aerove.lite.overrides.OverrideSnapshot() }
            val neededSources = librarySources(chosenBuilder ?: BuilderResources()) + restoredInputs.flatMap { localGlobalRuleSources(it.source) }
            val cachePayloads = importedPayloads.filter { (_, value) -> scope == BackupRestoreScope.All ||
                (value.runtimeFormat != null || value.localId != null) && neededSources.any { source ->
                    source.kind == value.kind && source.url == value.url && source.behavior == value.behavior && source.format == value.format && source.localId == value.localId
                }
            }.map { (path, resource) ->
                val originalId = importedBuilder?.ruleProviders?.singleOrNull { it.name == resource.name }?.id
                val targetId = originalId?.let { mergedBuilder?.providerIds?.get(it) }
                val label = targetId?.let { id -> mergedBuilder?.resources?.ruleProviders?.single { it.id == id }?.name } ?: resource.name
                path to resource.copy(name = label)
            }
            if (scope == BackupRestoreScope.ProfilesOnly && libraryPayloads.isNotEmpty()) {
                val geo = settingsStore.read().geoResources
                val urls = GeoResourceKind.entries.associate { it.sourceKey to geo.urls.getValue(it) }
                val values = importedPayloads.filter { it.second.runtimeFormat == null && it.second.localId == null }
                    .map { (path, value) -> CachedLibraryRule(value, privateResource(payload, path)) }
                restoredInputs = restoredInputs.map { entry ->
                    val resolved = (mergedBuilder?.resources ?: currentBuilder).resolve(entry.source)
                    val local = profiles.localizeBackupLibraryPayloads(resolved, values, urls) + entry.localResources
                    require(local.size <= 128 && local.values.sumOf { it.length() } <= 256L * 1024 * 1024) { "Profile resource limits exceeded" }
                    entry.copy(localResources = local)
                }
            }
            profiles.withBackupLibraryRestore { cache ->
                require(cachePayloads.isEmpty() || cache != null) { "Resource manager is required to restore library payloads" }
                val extraPaths = cache?.journalPaths(cachePayloads.map { it.second }).orEmpty().map { file ->
                    require(file.canonicalFile.toPath().startsWith(root.toPath())) { "Foreign library cache index" }
                    root.toPath().relativize(file.canonicalFile.toPath()).joinToString("/")
                }.distinct()
                profiles.restoreBackupMapped(restoredInputs, selected, strategy == BackupRestoreStrategy.Override,
                    scope == BackupRestoreScope.All, extraPaths) { ids, liveIds ->
                    cachePayloads.forEach { (path, resource) ->
                        checkNotNull(cache).importResource(resource, privateResource(payload, path).inputStream())
                    }
                    if (importedOverrides != null) {
                        val restored = OverrideRepository.mergeRestore(currentOverrides, importedOverrides, ids,
                            strategy == BackupRestoreStrategy.Override, scope == BackupRestoreScope.ProfilesOnly, liveIds)
                        PrivateStorage(File(root, "settings")).write(OverrideRepository.FILE_NAME, OverrideRepository.encode(restored))
                    }
                    if (mergedBuilder != null) PrivateStorage(File(root, "settings")).write("builder-resources.yaml", mergedBuilder.resources.yaml())
                    if (scope == BackupRestoreScope.All) {
                        settingsStore.save(restoredSettings, vpnAuthorized)
                        if (webdav != null) saveWebDav(webdav)
                    }
                }
            }
        }
        BackupRestoreResult(mapping, scope == BackupRestoreScope.All)
    }

    private fun librarySources(resources: BuilderResources): List<LibraryRuleSource> = resources.ruleProviders.mapNotNull { entry ->
        val local = if (entry.fields["type"] == "file") globalRuleLocalId(entry.fields["path"] as? String) else null
        val url = entry.fields["url"] as? String ?: if (local != null) "" else return@mapNotNull null
        LibraryRuleSource(entry.name, url, entry.fields["behavior"] as? String ?: "domain", entry.fields["format"] as? String ?: "yaml", localId = local)
    }

    private fun encodeLibraryResource(value: ManagedRuleResource): JSONObject = JSONObject().put("name", value.name)
        .put("url", value.url).put("behavior", value.behavior).put("format", value.format).put("kind", value.kind)
        .put("bytes", value.bytes).put("sha256", value.sha256).put("updatedAt", value.updatedAt).put("count", value.count ?: JSONObject.NULL)
        .put("localId", value.localId ?: JSONObject.NULL).put("runtimeFormat", value.runtimeFormat ?: JSONObject.NULL)
        .put("runtimeSha256", value.runtimeSha256 ?: JSONObject.NULL).put("runtimeBytes", value.runtimeBytes ?: JSONObject.NULL)

    private fun decodeLibraryResource(value: JSONObject): ManagedRuleResource = ManagedRuleResource(value.getString("name"),
        value.getString("url"), value.getString("behavior"), value.getString("format"), value.getLong("bytes"),
        value.getString("sha256"), value.getLong("updatedAt"), if (value.isNull("count")) null else value.getInt("count"),
        value.optString("kind", "ruleset"), if (value.isNull("localId")) null else value.getString("localId"),
        if (value.isNull("runtimeFormat")) null else value.getString("runtimeFormat"),
        if (value.isNull("runtimeSha256")) null else value.getString("runtimeSha256"),
        if (value.isNull("runtimeBytes")) null else value.getLong("runtimeBytes")).also {
        require(it.name.isNotBlank() && it.updatedAt >= 0 && (it.bytes > 0 || it.bytes == 0L && it.localId != null) && it.sha256.matches(Regex("[0-9a-f]{64}")) &&
            it.kind in setOf("ruleset", "mmdb", "geosite")) { "Invalid library resource metadata" }
    }

    private fun libraryFile(path: String): File = privateResource(root, path).also {
        require(!Files.isSymbolicLink(it.toPath())) { "Unsafe backup library" }
    }

    private fun readLibrary(file: File): String = file.inputStream().use { it.readBounded(MAX_LIBRARY_BYTES).strictUtf8() }

    private suspend fun writeLibrarySnapshot(text: String, path: String, payload: File,
        records: MutableMap<String, File>, copiedBytes: LongArray) {
        val file = privateResource(payload, path).apply { parentFile!!.mkdirs() }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_LIBRARY_BYTES && bytes.size <= limits.entryBytes &&
            copiedBytes[0] + bytes.size <= limits.expandedBytes) { "Backup library size limit exceeded" }
        file.writeBytes(bytes)
        check(records.put(path, file) == null)
        copiedBytes[0] += bytes.size
    }

    private suspend fun copySnapshot(source: File, path: String, destination: File, files: MutableMap<String, File>, copiedBytes: LongArray) {
        checkPath(path)
        require(!Files.isSymbolicLink(source.toPath()) && source.isFile) { "Unsafe backup source" }
        require(copiedBytes[0] + source.length() <= limits.expandedBytes) { "Backup expanded size limit exceeded" }
        val target = privateResource(destination, path).apply { parentFile!!.mkdirs() }
        source.inputStream().use { if (path.startsWith("library/resources/")) copyLocalRuleResource(it, target, limits.entryBytes) else copyResource(it, target, limits.entryBytes) }
        require(files.put(path, target) == null) { "Duplicate backup payload" }
        copiedBytes[0] += target.length()
        require(files.size < limits.entries && copiedBytes[0] <= limits.expandedBytes) { "Backup limits exceeded" }
    }

    private fun checkLimits(files: List<BackupFileRecord>) {
        require(files.size < limits.entries && files.map { it.path.lowercase(Locale.ROOT) }.distinct().size == files.size) { "Duplicate or excessive backup entries" }
        files.forEach {
            checkPath(it.path)
            require((it.bytes in 1..limits.entryBytes || it.bytes == 0L && it.path.startsWith("library/resources/")) && it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid backup file record" }
        }
        require(files.sumOf { it.bytes } <= limits.expandedBytes) { "Backup expanded size limit exceeded" }
    }

    private suspend fun <T> work(action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val directory = File(storage.root, ".work-${UUID.randomUUID()}").apply { check(mkdir()) }
        var failure: Throwable? = null
        try { action(directory) } catch (error: Throwable) { failure = error; throw error } finally {
            withContext(NonCancellable) {
                try { deletePrivateTree(storage.root, directory) } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    companion object {
        const val FORMAT = "aerove-lite-app-backup"
        private const val OVERRIDES_PATH = "settings/overrides.json"
        private const val BUILDER_PATH = "settings/builder-resources.yaml"
        private val LIBRARY_PATHS = setOf(OVERRIDES_PATH, BUILDER_PATH)
        private const val MAX_LIBRARY_BYTES = 16 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private fun checkId(id: String) { require(UUID.fromString(id).toString() == id) { "Invalid backup profile id" } }
        private fun checkPath(path: String) {
            require(path.isNotEmpty() && path.length <= 1024 && path.split('/').size <= 32 &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                path.none { it.isISOControl() || it == ':' || it == '\\' }) { "Unsafe backup path" }
        }
    }
}

private suspend fun writeZip(destination: File, entries: Map<String, File>, limit: Long) {
    FileOutputStream(destination).use { output ->
        val bounded = object : FilterOutputStream(output) {
            var count = 0L
            override fun write(value: Int) { checkSize(1); out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { checkSize(length); out.write(bytes, offset, length) }
            private fun checkSize(size: Int) { count += size; require(count <= limit) { "Backup archive size limit exceeded" } }
        }
        ZipOutputStream(BufferedOutputStream(bounded)).use { zip ->
            val buffer = ByteArray(65536)
            entries.forEach { (path, file) ->
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                file.inputStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            output.fd.sync()
        }
    }
}
