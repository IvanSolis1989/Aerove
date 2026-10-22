package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ProfileDocumentSnapshot(val profile: ProfileSummary, val revision: String, val yaml: String)

class ProfileRepository(
    privateDirectory: File,
    private val validator: ProfileValidator,
    private val downloader: SubscriptionDownloader = SubscriptionDownloader(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val resources: ResourceBundleManager? = null,
) {
    private val storage = PrivateStorage(File(privateDirectory, "profiles"))
    private val leases = liveProfiles.computeIfAbsent(storage.root.path) { mutableMapOf() }

    suspend fun snapshot(): ProfileSnapshot = storage.transaction {
        val index = load()
        runCatching { collectGarbage(index) }
        ProfileSnapshot(index.entries.map { it.summary }, index.selected)
    }

    suspend fun list(): List<ProfileSummary> = snapshot().profiles

    suspend fun importText(name: String, yaml: String, url: String? = null): ProfileSummary =
        importText(UUID.randomUUID().toString(), name, yaml, url)

    private suspend fun importText(id: String, name: String, yaml: String, url: String?, download: SubscriptionDownloader.Download? = null): ProfileSummary {
        checkName(name)
        url?.let(SubscriptionDownloader::parseUrl)
        val bundle = prepareBundle(id, yaml)
        var committed = false
        try { return storage.transaction {
            val index = load()
            check(index.entries.size < 1000) { "Profile limit reached" }
            val timestamp = clock()
            val summary = ProfileSummary(id, name.trim(), url, timestamp,
                sourceIdentity = UUID.randomUUID().toString(), lastSuccessfulUpdateAt = if (download != null) timestamp else null,
                subscriptionUserInfo = download?.subscriptionUserInfo, contentDisposition = download?.contentDisposition)
            val revision = writeRevision(yaml)
            save(index.copy(
                entries = index.entries + Entry(summary, revision, bundle = bundle?.generation, nextRefreshAt = bundle?.nextRefreshAt),
                selected = index.selected ?: id,
            ))
            committed = true
            bundle?.let { resources?.committed(id, it.generation) }
            summary
        } } finally { if (!committed && bundle != null) withContext(NonCancellable) { resources?.discard(id, bundle.generation) } }
    }

    suspend fun importFile(name: String, input: InputStream): ProfileSummary {
        checkName(name)
        return input.buffered().use { buffered ->
            val zip = withContext(Dispatchers.IO) {
                buffered.mark(4)
                val signature = buffered.readNBytes(4)
                buffered.reset()
                signature.size == 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() &&
                    (signature[2].toInt() to signature[3].toInt()) in setOf(3 to 4, 5 to 6, 7 to 8)
            }
            if (zip) {
                val manager = checkNotNull(resources) { "ZIP profiles require the resource manager" }
                val id = UUID.randomUUID().toString()
                manager.importArchive(id, buffered) { yaml -> importText(id, name, yaml, null) }
            } else {
                val yaml = withContext(Dispatchers.IO) { buffered.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
                importText(name, yaml)
            }
        }
    }

    suspend fun addSubscription(name: String, url: String): ProfileSummary {
        val download = downloader.downloadResult(url)
        return importText(UUID.randomUUID().toString(), name, download.yaml, url, download)
    }

    suspend fun update(id: String, request: ProfileMaintenanceRequest? = null): ProfileSummary {
        val original = storage.transaction { load().entry(id) }
        checkRequest(original.summary, request)
        val url = original.summary.url ?: throw IOException("Local profile requires a replacement file")
        val download = downloader.downloadResult(url)
        return replace(id, download.yaml, original.current, original.summary.sourceIdentity, request, download)
    }

    suspend fun replace(id: String, yaml: String): ProfileSummary {
        val revision = storage.transaction { load().entry(id).current }
        return replace(id, yaml, revision, replaceSource = true)
    }

    suspend fun documentSnapshot(id: String): ProfileDocumentSnapshot = storage.transaction {
        load().entry(id).let { ProfileDocumentSnapshot(it.summary, it.current, storage.read(it.current)) }
    }

    suspend fun replaceDocument(expected: ProfileDocumentSnapshot, input: InputStream,
        disableAutoUpdate: Boolean = false, isCurrent: () -> Boolean = { true }): ProfileSummary {
        storage.transaction {
            val latest = load().entry(expected.profile.id)
            if (latest.current != expected.revision) throw ProfileConflictException(expected.profile.id)
            if (latest.summary.sourceIdentity != expected.profile.sourceIdentity || !isCurrent()) {
                throw CancellationException("Profile source changed")
            }
        }
        val yaml = withContext(Dispatchers.IO) { input.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
        return replace(expected.profile.id, yaml, expected.revision, expected.profile.sourceIdentity,
            replaceSource = true, commitGuard = isCurrent, disableAutoUpdate = disableAutoUpdate)
    }

    /** Save a stale editor's order against current entries; deletions stay deleted and new imports remain appended. */
    suspend fun reorder(ids: List<String>, isCurrent: () -> Boolean = { true }): ProfileSnapshot = storage.transaction {
        require(ids.size <= 1000 && ids.distinct().size == ids.size) { "Invalid profile order" }
        val index = load()
        val latest = index.entries.associateBy { it.summary.id }
        val requested = ids.toSet()
        val entries = ids.mapNotNull(latest::get) + index.entries.filterNot { it.summary.id in requested }
        val updated = index.copy(entries = entries)
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Profile order superseded")
        save(updated)
        ProfileSnapshot(entries.map { it.summary }, updated.selected)
    }

    private suspend fun replace(id: String, yaml: String, expectedRevision: String,
        expectedSource: String? = null, request: ProfileMaintenanceRequest? = null,
        download: SubscriptionDownloader.Download? = null, replaceSource: Boolean = false,
        commitGuard: () -> Boolean = { true }, disableAutoUpdate: Boolean = false): ProfileSummary {
        if (!commitGuard()) throw CancellationException("Profile operation superseded")
        val bundle = prepareBundle(id, yaml)
        var committed = false
        try { return storage.transaction {
            val index = load()
            val old = index.entry(id)
            if (old.current != expectedRevision) throw ProfileConflictException(id)
            if (expectedSource != null && old.summary.sourceIdentity != expectedSource) throw CancellationException("Profile source changed")
            currentCoroutineContext().ensureActive()
            checkRequest(old.summary, request)
            if (!commitGuard()) throw CancellationException("Profile operation superseded")
            val timestamp = clock()
            val updated = old.copy(
                summary = old.summary.copy(updatedAt = timestamp,
                    autoUpdate = if (disableAutoUpdate) false else old.summary.autoUpdate,
                    sourceIdentity = if (replaceSource) UUID.randomUUID().toString() else old.summary.sourceIdentity,
                    lastSuccessfulUpdateAt = if (download != null) timestamp else if (replaceSource) null else old.summary.lastSuccessfulUpdateAt,
                    subscriptionUserInfo = if (download != null) download.subscriptionUserInfo else if (replaceSource) null else old.summary.subscriptionUserInfo,
                    contentDisposition = if (download != null) download.contentDisposition else if (replaceSource) null else old.summary.contentDisposition),
                current = writeRevision(yaml),
                previous = old.current,
                bundle = bundle?.generation,
                previousBundle = old.bundle,
                nextRefreshAt = bundle?.nextRefreshAt,
            )
            checkRequest(old.summary, request)
            if (!commitGuard()) throw CancellationException("Profile operation superseded")
            currentCoroutineContext().ensureActive()
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) updated else it }))
            committed = true
            bundle?.let { resources?.committed(id, it.generation) }
            updated.summary
        } } finally { if (!committed && bundle != null) withContext(NonCancellable) { resources?.discard(id, bundle.generation) } }
    }

    suspend fun isCurrentSource(id: String, sourceIdentity: String): Boolean = storage.transaction {
        load().entries.any { it.summary.id == id && it.summary.sourceIdentity == sourceIdentity }
    }

    suspend fun editMetadata(id: String, name: String, url: String?, autoUpdate: Boolean, intervalMillis: Long): ProfileSummary {
        checkName(name)
        url?.let(SubscriptionDownloader::parseUrl)
        require(intervalMillis > 0) { "Update interval must be positive" }
        return storage.transaction {
            val index = load()
            val old = index.entry(id)
            val changedSource = old.summary.url != url
            val summary = old.summary.copy(name = name.trim(), url = url, autoUpdate = autoUpdate,
                updateIntervalMillis = intervalMillis,
                sourceIdentity = if (changedSource) UUID.randomUUID().toString() else old.summary.sourceIdentity,
                lastSuccessfulUpdateAt = if (changedSource) null else old.summary.lastSuccessfulUpdateAt,
                subscriptionUserInfo = if (changedSource) null else old.summary.subscriptionUserInfo,
                contentDisposition = if (changedSource) null else old.summary.contentDisposition)
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) it.copy(summary = summary) else it }))
            summary
        }
    }

    private fun checkRequest(summary: ProfileSummary, request: ProfileMaintenanceRequest?) {
        if (request != null && (request.profileId != summary.id || request.sourceIdentity != summary.sourceIdentity || !request.isCurrent())) {
            throw CancellationException("Profile update superseded")
        }
    }

    suspend fun prepare(id: String): PreparedProfile {
        var pair = storage.transaction { load().entry(id).let { it to storage.read(it.current) } }
        if (resources != null && pair.first.bundle == null) {
            replace(id, pair.second, pair.first.current)
            pair = storage.transaction { load().entry(id).let { it to storage.read(it.current) } }
        }
        val (entry, source) = pair
        val bundle = entry.bundle?.let { checkNotNull(resources) { "Resource manager is required for this profile" }.load(id, it, source) }
        if (bundle == null) validate(source)
        return PreparedProfile(id, source, bundle?.yaml ?: source, entry.current, bundle?.generation, bundle?.directory, bundle?.nextRefreshAt)
    }

    suspend fun prepareForStart(id: String): PreparedProfile {
        if (resources != null) {
            storage.transaction {
                check(load().entry(id).bundle != null) { "Refresh this profile to prepare its resource bundle before starting" }
            }
        }
        return prepare(id)
    }

    suspend fun refreshResources(id: String): PreparedProfile {
        checkNotNull(resources) { "Resource manager is not configured" }
        val (entry, source) = storage.transaction { load().entry(id).let { it to storage.read(it.current) } }
        replace(id, source, entry.current)
        return prepare(id)
    }

    suspend fun refreshIfDue(id: String): PreparedProfile? {
        val entry = storage.transaction { load().entry(id) }
        val due = entry.nextRefreshAt ?: return null
        return if (clock() >= due) refreshResources(id) else null
    }

    suspend fun retain(prepared: PreparedProfile): ProfileLease {
        var acquired: ProfileLease? = null
        try {
            val lease = storage.transaction {
                require(prepared.revision.matches(REVISION) && storage.file(prepared.revision).isFile &&
                    storage.read(prepared.revision) == prepared.source) { "Prepared profile revision was reclaimed; prepare it again" }
                val token = UUID.randomUUID().toString()
                leases[token] = prepared
                ProfileLease(storage.root.path, token).also { acquired = it }
            }
            prepared.generation?.let {
                val verified = checkNotNull(resources).load(prepared.profileId, it, prepared.source)
                require(verified.yaml == prepared.yaml && verified.directory == prepared.resourceDirectory) {
                    "Prepared profile does not match its verified resource bundle"
                }
            }
            return lease
        } catch (failure: Throwable) {
            acquired?.let { release(it) }
            throw failure
        }
    }

    suspend fun release(lease: ProfileLease) = withContext(NonCancellable) {
        require(lease.root == storage.root.path) { "Profile lease belongs to another repository" }
        storage.transaction {
            leases.remove(lease.token)
            collectGarbage(load())
        }
    }

    suspend fun collectGarbage() = storage.transaction { collectGarbage(load()) }

    suspend fun markUsable(prepared: PreparedProfile) = storage.transaction {
        val index = load()
        val old = index.entry(prepared.profileId)
        if (old.current != prepared.revision || old.bundle != prepared.generation || storage.read(old.current) != prepared.source) {
            throw ProfileConflictException(prepared.profileId)
        }
        save(index.copy(entries = index.entries.map {
            if (it.summary.id == prepared.profileId) it.copy(lastUsable = it.current, lastUsableBundle = it.bundle) else it
        }))
    }

    suspend fun restoreLastUsable(id: String): PreparedProfile {
        val (before, source) = storage.transaction {
            val entry = load().entry(id)
            val usable = entry.lastUsable ?: throw IOException("No confirmed usable profile revision")
            entry to storage.read(usable)
        }
        val bundle = if (resources != null) {
            val generation = before.lastUsableBundle ?: throw IOException("No confirmed usable resource generation")
            resources.load(id, generation, source)
        } else { validate(source); null }
        storage.transaction {
            val index = load()
            val old = index.entry(id)
            if (old.current != before.current || old.bundle != before.bundle || old.lastUsable != before.lastUsable ||
                old.lastUsableBundle != before.lastUsableBundle) {
                throw ProfileConflictException(id)
            }
            val revision = checkNotNull(before.lastUsable)
            save(index.copy(entries = index.entries.map {
                if (it.summary.id == id) it.copy(current = revision, bundle = old.lastUsableBundle,
                    previous = old.current, previousBundle = old.bundle, nextRefreshAt = bundle?.nextRefreshAt) else it
            }))
        }
        return prepare(id)
    }

    suspend fun importResource(id: String, path: String, input: InputStream): PreparedProfile = privateDomainTransaction(storage.root.parentFile!!) {
        storage.transaction { load().entry(id) }
        checkNotNull(resources) { "Resource manager is not configured" }.importLocal(id, path, input)
        refreshResources(id)
    }

    internal suspend fun <T> withBackupSnapshot(action: suspend (List<BackupProfileSource>, String?) -> T): T = storage.transaction {
        val index = load()
        val entries = index.entries.map { entry ->
            val source = storage.read(entry.current)
            val bundle = entry.bundle?.let { checkNotNull(resources).load(entry.summary.id, it, source) }
            if (bundle == null) validate(source)
            val local = linkedMapOf<String, File>()
            bundle?.let {
                val manifest = JSONObject(File(it.directory, "manifest.json").inputStream().use { stream -> stream.readBounded(1024 * 1024).strictUtf8() })
                val records = manifest.getJSONArray("records")
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    if (!record.isNull("url") || record.isNull("localPath")) continue
                    val normalized = privateResource(it.directory, record.getString("localPath"))
                    val path = it.directory.toPath().relativize(normalized.toPath()).joinToString("/")
                    val file = privateResource(it.directory, record.getString("file"))
                    val previous = local.put(path, file)
                    require(previous == null || sha256(previous) == sha256(file)) { "Local resource snapshot has conflicting content" }
                }
            }
            BackupProfileSource(entry.backupId ?: entry.summary.id, entry.summary, source, local)
        }
        action(entries, index.entries.find { it.summary.id == index.selected }?.let { it.backupId ?: it.summary.id })
    }

    internal suspend fun restoreBackup(
        incoming: List<BackupProfileInput>,
        selectedId: String?,
        replaceExisting: Boolean,
        restoreSelection: Boolean,
        applySettings: suspend () -> Unit,
    ): Map<String, String> = privateDomainTransaction(storage.root.parentFile!!) {
        require(incoming.size <= 1000 && incoming.map { it.id }.distinct().size == incoming.size) { "Invalid backup profile count" }
        require(selectedId == null || incoming.any { it.id == selectedId }) { "Invalid backup selection" }
        val before = storage.transaction { load() }
        val identities = incoming.map { it.id }.toSet()
        val kept = if (replaceExisting) emptyList() else before.entries.filterNot { (it.backupId ?: it.summary.id) in identities }
        require(kept.size + incoming.size <= 1000) { "Profile limit reached" }
        val candidates = mutableListOf<Pair<String, ResourceBundle>>()
        val restored = mutableListOf<Entry>()
        try {
            for (input in incoming) {
                require(UUID.fromString(input.id).toString() == input.id) { "Invalid backup profile id" }
                checkName(input.name)
                input.url?.let(SubscriptionDownloader::parseUrl)
                require(input.updatedAt >= 0) { "Invalid profile timestamp" }
                require(input.updateIntervalMillis > 0 && (input.lastSuccessfulUpdateAt == null || input.lastSuccessfulUpdateAt >= 0)) { "Invalid profile maintenance metadata" }
                require(input.sourceIdentity == null || (input.sourceIdentity.isNotBlank() && input.sourceIdentity.length <= 16384)) { "Invalid source identity" }
                require(listOfNotNull(input.subscriptionUserInfo, input.contentDisposition).all { it.length <= 8192 }) { "Profile response header too large" }
                val id = UUID.randomUUID().toString()
                val bundle = if (resources != null) {
                    input.localResources.forEach { (path, file) ->
                        resources.importLocal(id, path, file.inputStream())
                    }
                    checkNotNull(prepareBundle(id, input.source)).also { candidates += id to it }
                } else {
                    require(input.localResources.isEmpty()) { "Resource manager is required to restore local resources" }
                    validate(input.source)
                    null
                }
                restored += Entry(ProfileSummary(id, input.name.trim(), input.url, input.updatedAt,
                    autoUpdate = input.autoUpdate, updateIntervalMillis = input.updateIntervalMillis,
                    lastSuccessfulUpdateAt = input.lastSuccessfulUpdateAt, sourceIdentity = input.sourceIdentity ?: UUID.randomUUID().toString(),
                    subscriptionUserInfo = input.subscriptionUserInfo, contentDisposition = input.contentDisposition), writeRevision(input.source),
                    bundle = bundle?.generation, nextRefreshAt = bundle?.nextRefreshAt, backupId = input.id)
            }
            val mapping = incoming.zip(restored).associate { (source, entry) -> source.id to entry.summary.id }
            val previousIdentity = before.entries.find { it.summary.id == before.selected }?.let { it.backupId ?: it.summary.id }
            val selected = when {
                restoreSelection -> mapping[selectedId]
                !replaceExisting && kept.any { it.summary.id == before.selected } -> before.selected
                !replaceExisting && previousIdentity in mapping -> mapping[previousIdentity]
                else -> mapping[selectedId] ?: kept.firstOrNull()?.summary?.id ?: restored.firstOrNull()?.summary?.id
            }
            atomicDomainRestore(storage.root.parentFile!!) {
                applySettings()
                storage.transaction { save(Index(kept + restored, selected), collect = false) }
            }
            candidates.forEach { (id, bundle) -> resources?.committed(id, bundle.generation) }
            runCatching { storage.transaction { collectGarbage(load()) } }
            mapping
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                candidates.forEach { (id, bundle) ->
                    try { resources?.discard(id, bundle.generation) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                try { storage.transaction { collectGarbage(load()) } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    suspend fun readConfig(id: String): String = storage.transaction {
        storage.read(load().entry(id).current)
    }

    suspend fun readPreviousConfig(id: String): String? = storage.transaction {
        load().entry(id).previous?.let { storage.read(it) }
    }

    suspend fun readLastUsableConfig(id: String): String? = storage.transaction {
        load().entry(id).lastUsable?.let { storage.read(it) }
    }

    suspend fun markUsable(id: String, appliedYaml: String) = storage.transaction {
        check(resources == null) { "Use markUsable(PreparedProfile) to confirm the exact resource generation" }
        val index = load()
        val old = index.entry(id)
        if (storage.read(old.current) != appliedYaml) throw ProfileConflictException(id)
        save(index.copy(entries = index.entries.map {
            if (it.summary.id == id) it.copy(lastUsable = it.current) else it
        }))
    }

    suspend fun select(id: String) = storage.transaction {
        val index = load()
        index.entry(id)
        save(index.copy(selected = id))
    }

    suspend fun delete(id: String) = storage.transaction {
        val index = load()
        index.entry(id)
        val remaining = index.entries.filterNot { it.summary.id == id }
        save(Index(remaining, if (index.selected == id) remaining.firstOrNull()?.summary?.id else index.selected))
    }

    private suspend fun validate(yaml: String) = withContext(Dispatchers.IO) {
        require(yaml.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES) { "Profile is too large" }
        validator.validate(yaml)
    }

    private suspend fun prepareBundle(id: String, source: String): ResourceBundle? {
        return if (resources != null) resources.prepare(id, source) else { validate(source); null }
    }

    private fun writeRevision(yaml: String): String {
        val filename = "${UUID.randomUUID()}.yaml"
        storage.write(filename, yaml)
        return filename
    }

    private fun load(): Index {
        if (!storage.file("index.json").exists()) return Index(emptyList(), null)
        val json = JSONObject(storage.read("index.json", 1024 * 1024))
        require(json.getInt("schema") == 1) { "Unsupported profile index version" }
        val records = json.getJSONArray("profiles")
        val entries = (0 until records.length()).map { i ->
            val row = records.getJSONObject(i)
            val id = row.getString("id")
            require(UUID.fromString(id).toString() == id) { "Invalid profile id" }
            fun revision(key: String): String? = if (row.isNull(key)) null else {
                row.getString(key).also { require(it.matches(REVISION)) { "Invalid revision path" } }
            }
            Entry(
                ProfileSummary(id, row.getString("name"), nullableString(row, "url"), row.getLong("updatedAt"),
                    autoUpdate = row.optBoolean("autoUpdate", true), updateIntervalMillis = row.optLong("updateIntervalMillis", 86_400_000L).also { require(it > 0) },
                    lastSuccessfulUpdateAt = if (row.isNull("lastSuccessfulUpdateAt")) null else row.getLong("lastSuccessfulUpdateAt").also { require(it >= 0) },
                    sourceIdentity = row.optString("sourceIdentity", id).also { require(it.isNotBlank() && it.length <= 16384) },
                    subscriptionUserInfo = nullableString(row, "subscriptionUserInfo"), contentDisposition = nullableString(row, "contentDisposition")),
                revision("current") ?: throw IOException("Profile has no configuration"),
                revision("previous"),
                revision("lastUsable"),
                nullableString(row, "bundle")?.also(::checkGeneration),
                nullableString(row, "previousBundle")?.also(::checkGeneration),
                nullableString(row, "lastUsableBundle")?.also(::checkGeneration),
                if (row.isNull("nextRefreshAt")) null else row.getLong("nextRefreshAt"),
                nullableString(row, "backupId")?.also(::checkGeneration),
            )
        }
        require(entries.map { it.summary.id }.distinct().size == entries.size) { "Duplicate profile id" }
        val selected = nullableString(json, "selected")
        require(selected == null || entries.any { it.summary.id == selected }) { "Invalid selected profile" }
        return Index(entries, selected)
    }

    private fun save(index: Index, collect: Boolean = true) {
        val array = JSONArray()
        index.entries.forEach { e ->
            array.put(JSONObject().put("id", e.summary.id).put("name", e.summary.name)
                .put("url", e.summary.url ?: JSONObject.NULL).put("updatedAt", e.summary.updatedAt)
                .put("autoUpdate", e.summary.autoUpdate).put("updateIntervalMillis", e.summary.updateIntervalMillis)
                .put("lastSuccessfulUpdateAt", e.summary.lastSuccessfulUpdateAt ?: JSONObject.NULL)
                .put("sourceIdentity", e.summary.sourceIdentity)
                .put("subscriptionUserInfo", e.summary.subscriptionUserInfo ?: JSONObject.NULL)
                .put("contentDisposition", e.summary.contentDisposition ?: JSONObject.NULL)
                .put("current", e.current).put("previous", e.previous ?: JSONObject.NULL)
                .put("lastUsable", e.lastUsable ?: JSONObject.NULL)
                .put("bundle", e.bundle ?: JSONObject.NULL).put("previousBundle", e.previousBundle ?: JSONObject.NULL)
                .put("lastUsableBundle", e.lastUsableBundle ?: JSONObject.NULL)
                .put("nextRefreshAt", e.nextRefreshAt ?: JSONObject.NULL).put("backupId", e.backupId ?: JSONObject.NULL))
        }
        val encoded = JSONObject().put("schema", 1).put("profiles", array)
            .put("selected", index.selected ?: JSONObject.NULL).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Profile metadata limit reached" }
        storage.write("index.json", encoded)
        if (collect) runCatching { collectGarbage(index) }
    }

    private fun collectGarbage(index: Index) {
        val referenced = (index.entries.flatMap { listOfNotNull(it.current, it.previous, it.lastUsable) } +
            leases.values.map { it.revision }).toSet()
        storage.root.listFiles()?.filter { it.name.matches(REVISION) && it.name !in referenced }
            ?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
        val bundles = mutableMapOf<String, MutableSet<String>>()
        index.entries.forEach { entry ->
            bundles.getOrPut(entry.summary.id) { mutableSetOf() }.addAll(listOfNotNull(entry.bundle, entry.previousBundle, entry.lastUsableBundle))
        }
        leases.values.forEach { profile -> profile.generation?.let {
            bundles.getOrPut(profile.profileId) { mutableSetOf() }.add(it)
        } }
        resources?.prune(bundles, index.entries.map { it.summary.id }.toSet() + leases.values.map { it.profileId })
    }

    private fun checkName(name: String) {
        require(name.isNotBlank() && name.length <= 200 && name.none { it.isISOControl() }) { "Invalid profile name" }
    }

    private data class Entry(
        val summary: ProfileSummary,
        val current: String,
        val previous: String? = null,
        val lastUsable: String? = null,
        val bundle: String? = null,
        val previousBundle: String? = null,
        val lastUsableBundle: String? = null,
        val nextRefreshAt: Long? = null,
        val backupId: String? = null,
    )

    private data class Index(val entries: List<Entry>, val selected: String?) {
        fun entry(id: String): Entry = entries.find { it.summary.id == id }
            ?: throw IOException("Profile does not exist")
    }

    companion object {
        private val liveProfiles = ConcurrentHashMap<String, MutableMap<String, PreparedProfile>>()
        private val REVISION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.yaml")
        private fun nullableString(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.getString(key)
        private fun checkGeneration(value: String) { require(UUID.fromString(value).toString() == value) }
    }
}
