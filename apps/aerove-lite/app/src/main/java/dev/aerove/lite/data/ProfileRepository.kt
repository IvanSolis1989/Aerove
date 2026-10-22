package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    private suspend fun importText(id: String, name: String, yaml: String, url: String?): ProfileSummary {
        checkName(name)
        url?.let(SubscriptionDownloader::parseUrl)
        val bundle = prepareBundle(id, yaml)
        var committed = false
        try { return storage.transaction {
            val index = load()
            check(index.entries.size < 1000) { "Profile limit reached" }
            val summary = ProfileSummary(id, name.trim(), url, clock())
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

    suspend fun addSubscription(name: String, url: String): ProfileSummary =
        importText(name, downloader.download(url), url)

    suspend fun update(id: String): ProfileSummary {
        val original = storage.transaction { load().entry(id) }
        val url = original.summary.url ?: throw IOException("Local profile requires a replacement file")
        return replace(id, downloader.download(url), original.current)
    }

    suspend fun replace(id: String, yaml: String): ProfileSummary {
        val revision = storage.transaction { load().entry(id).current }
        return replace(id, yaml, revision)
    }

    private suspend fun replace(id: String, yaml: String, expectedRevision: String): ProfileSummary {
        val bundle = prepareBundle(id, yaml)
        var committed = false
        try { return storage.transaction {
            val index = load()
            val old = index.entry(id)
            if (old.current != expectedRevision) throw ProfileConflictException(id)
            val updated = old.copy(
                summary = old.summary.copy(updatedAt = clock()),
                current = writeRevision(yaml),
                previous = old.current,
                bundle = bundle?.generation,
                previousBundle = old.bundle,
                nextRefreshAt = bundle?.nextRefreshAt,
            )
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) updated else it }))
            committed = true
            bundle?.let { resources?.committed(id, it.generation) }
            updated.summary
        } } finally { if (!committed && bundle != null) withContext(NonCancellable) { resources?.discard(id, bundle.generation) } }
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

    suspend fun importResource(id: String, path: String, input: InputStream): PreparedProfile {
        storage.transaction { load().entry(id) }
        checkNotNull(resources) { "Resource manager is not configured" }.importLocal(id, path, input)
        return refreshResources(id)
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
                ProfileSummary(id, row.getString("name"), nullableString(row, "url"), row.getLong("updatedAt")),
                revision("current") ?: throw IOException("Profile has no configuration"),
                revision("previous"),
                revision("lastUsable"),
                nullableString(row, "bundle")?.also(::checkGeneration),
                nullableString(row, "previousBundle")?.also(::checkGeneration),
                nullableString(row, "lastUsableBundle")?.also(::checkGeneration),
                if (row.isNull("nextRefreshAt")) null else row.getLong("nextRefreshAt"),
            )
        }
        require(entries.map { it.summary.id }.distinct().size == entries.size) { "Duplicate profile id" }
        val selected = nullableString(json, "selected")
        require(selected == null || entries.any { it.summary.id == selected }) { "Invalid selected profile" }
        return Index(entries, selected)
    }

    private fun save(index: Index) {
        val array = JSONArray()
        index.entries.forEach { e ->
            array.put(JSONObject().put("id", e.summary.id).put("name", e.summary.name)
                .put("url", e.summary.url ?: JSONObject.NULL).put("updatedAt", e.summary.updatedAt)
                .put("current", e.current).put("previous", e.previous ?: JSONObject.NULL)
                .put("lastUsable", e.lastUsable ?: JSONObject.NULL)
                .put("bundle", e.bundle ?: JSONObject.NULL).put("previousBundle", e.previousBundle ?: JSONObject.NULL)
                .put("lastUsableBundle", e.lastUsableBundle ?: JSONObject.NULL)
                .put("nextRefreshAt", e.nextRefreshAt ?: JSONObject.NULL))
        }
        storage.write("index.json", JSONObject().put("schema", 1).put("profiles", array)
            .put("selected", index.selected ?: JSONObject.NULL).toString())
        runCatching { collectGarbage(index) }
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
