package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
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

internal data class BackupProfileSource(val id: String, val summary: ProfileSummary, val source: String, val localResources: Map<String, File>)
internal data class BackupProfileInput(
    val id: String, val name: String, val url: String?, val updatedAt: Long,
    val source: String, val localResources: Map<String, File>,
    val autoUpdate: Boolean = true, val updateIntervalMillis: Long = 86_400_000L,
    val lastSuccessfulUpdateAt: Long? = null, val sourceIdentity: String? = null,
    val subscriptionUserInfo: String? = null, val contentDisposition: String? = null,
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
        var selected: String? = null
        privateDomainTransaction(root) {
            val snapshot = LiteSettingsStore(File(stage, "settings-snapshot"))
            snapshot.save(settingsStore.read(), vpnAuthorized = true)
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
                    metadata.put(JSONObject().put("id", entry.id).put("name", entry.summary.name)
                        .put("url", entry.summary.url ?: JSONObject.NULL).put("updatedAt", entry.summary.updatedAt)
                        .put("autoUpdate", entry.summary.autoUpdate).put("updateIntervalMillis", entry.summary.updateIntervalMillis)
                        .put("lastSuccessfulUpdateAt", entry.summary.lastSuccessfulUpdateAt ?: JSONObject.NULL)
                        .put("sourceIdentity", entry.summary.sourceIdentity)
                        .put("subscriptionUserInfo", entry.summary.subscriptionUserInfo ?: JSONObject.NULL)
                        .put("contentDisposition", entry.summary.contentDisposition ?: JSONObject.NULL))
                    require(entry.localResources.size <= 128) { "Too many local resources for a profile" }
                    require(entry.localResources.values.sumOf { it.length() } <= 256L * 1024 * 1024) { "Profile resource size limit exceeded" }
                    entry.localResources.forEach { (path, file) ->
                        copySnapshot(file, "profiles/${entry.id}/resources/$path", payload, records, copiedBytes)
                    }
                }
            }
        }
        val files = records.map { (name, file) -> BackupFileRecord(name, file.length(), sha256(file)) }
        checkLimits(files)
        val createdAt = clock()
        val manifest = JSONObject().put("format", FORMAT).put("schema", 1).put("createdAt", createdAt)
            .put("profiles", metadata).put("selectedId", selected ?: JSONObject.NULL)
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
        val names = records.map { it.path }.toSet()
        val expectedConfigs = ids.map { "profiles/$it/config.yaml" }.toSet()
        require(names.contains("settings.json") && names.containsAll(expectedConfigs)) { "Backup payload is incomplete" }
        require(names.all { name -> name == "settings.json" || name == "webdav.json" || name in expectedConfigs ||
            ids.any { name.startsWith("profiles/$it/resources/") } }) { "Unexpected application backup payload" }
        val actual = Files.walk(payload.toPath()).use { paths -> paths.filter { Files.isRegularFile(it) }
            .map { payload.toPath().relativize(it).joinToString("/") }.collect(java.util.stream.Collectors.toSet()) }
        require(actual == names + "manifest.json") { "Backup manifest does not describe all actual files" }
        records.forEach { record ->
            currentCoroutineContext().ensureActive()
            val file = privateResource(payload, record.path)
            require(file.isFile && file.length() == record.bytes && sha256(file) == record.sha256) { "Backup payload size/hash mismatch" }
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
        val incoming = ids.mapIndexed { index, id ->
            val row = rows.getJSONObject(index)
            val sourceFile = File(payload, "profiles/$id/config.yaml")
            val source = sourceFile.inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
            val prefix = "profiles/$id/resources/"
            val local = records.filter { it.path.startsWith(prefix) }
            require(local.size <= 128 && local.sumOf { it.bytes } <= 256L * 1024 * 1024) { "Profile resource limits exceeded" }
            val resources = local.associate { it.path.removePrefix(prefix) to privateResource(payload, it.path) }
            BackupProfileInput(id, row.getString("name"), if (row.isNull("url")) null else row.getString("url"),
                row.getLong("updatedAt"), source, resources,
                row.optBoolean("autoUpdate", true), row.optLong("updateIntervalMillis", 86_400_000L),
                if (row.isNull("lastSuccessfulUpdateAt")) null else row.getLong("lastSuccessfulUpdateAt"),
                if (row.isNull("sourceIdentity")) null else row.getString("sourceIdentity"),
                if (row.isNull("subscriptionUserInfo")) null else row.getString("subscriptionUserInfo"),
                if (row.isNull("contentDisposition")) null else row.getString("contentDisposition"))
        }
        currentCoroutineContext().ensureActive()
        val mapping = profiles.restoreBackup(incoming, selected, strategy == BackupRestoreStrategy.Override,
            scope == BackupRestoreScope.All) {
            if (scope == BackupRestoreScope.All) {
                settingsStore.save(restoredSettings, vpnAuthorized)
                if (webdav != null) saveWebDav(webdav)
            }
        }
        BackupRestoreResult(mapping, scope == BackupRestoreScope.All)
    }

    private suspend fun copySnapshot(source: File, path: String, destination: File, files: MutableMap<String, File>, copiedBytes: LongArray) {
        checkPath(path)
        require(!Files.isSymbolicLink(source.toPath()) && source.isFile) { "Unsafe backup source" }
        require(copiedBytes[0] + source.length() <= limits.expandedBytes) { "Backup expanded size limit exceeded" }
        val target = privateResource(destination, path).apply { parentFile!!.mkdirs() }
        source.inputStream().use { copyResource(it, target, limits.entryBytes) }
        require(files.put(path, target) == null) { "Duplicate backup payload" }
        copiedBytes[0] += target.length()
        require(files.size < limits.entries && copiedBytes[0] <= limits.expandedBytes) { "Backup limits exceeded" }
    }

    private fun checkLimits(files: List<BackupFileRecord>) {
        require(files.size < limits.entries && files.map { it.path.lowercase(Locale.ROOT) }.distinct().size == files.size) { "Duplicate or excessive backup entries" }
        files.forEach {
            checkPath(it.path)
            require(it.bytes in 1..limits.entryBytes && it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid backup file record" }
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
