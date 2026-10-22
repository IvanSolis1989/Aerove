package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(
    privateDirectory: File,
    private val validator: ProfileValidator,
    private val downloader: SubscriptionDownloader = SubscriptionDownloader(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val storage = PrivateStorage(File(privateDirectory, "profiles"))

    suspend fun snapshot(): ProfileSnapshot = storage.transaction {
        val index = load()
        ProfileSnapshot(index.entries.map { it.summary }, index.selected)
    }

    suspend fun list(): List<ProfileSummary> = snapshot().profiles

    suspend fun importText(name: String, yaml: String, url: String? = null): ProfileSummary {
        checkName(name)
        url?.let(SubscriptionDownloader::parseUrl)
        validate(yaml)
        return storage.transaction {
            val index = load()
            check(index.entries.size < 1000) { "Profile limit reached" }
            val id = UUID.randomUUID().toString()
            val summary = ProfileSummary(id, name.trim(), url, clock())
            val revision = writeRevision(yaml)
            save(index.copy(
                entries = index.entries + Entry(summary, revision),
                selected = index.selected ?: id,
            ))
            summary
        }
    }

    suspend fun importFile(name: String, input: InputStream): ProfileSummary {
        val yaml = withContext(Dispatchers.IO) {
            input.use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
        }
        return importText(name, yaml)
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
        validate(yaml)
        return storage.transaction {
            val index = load()
            val old = index.entry(id)
            if (old.current != expectedRevision) throw ProfileConflictException(id)
            val updated = old.copy(
                summary = old.summary.copy(updatedAt = clock()),
                current = writeRevision(yaml),
                previous = old.current,
            )
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) updated else it }))
            updated.summary
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
                .put("lastUsable", e.lastUsable ?: JSONObject.NULL))
        }
        storage.write("index.json", JSONObject().put("schema", 1).put("profiles", array)
            .put("selected", index.selected ?: JSONObject.NULL).toString())
        val referenced = index.entries.flatMap { listOfNotNull(it.current, it.previous, it.lastUsable) }.toSet()
        storage.root.listFiles()?.filter { it.name.matches(REVISION) && it.name !in referenced }
            ?.forEach { runCatching { java.nio.file.Files.deleteIfExists(it.toPath()) } }
    }

    private fun checkName(name: String) {
        require(name.isNotBlank() && name.length <= 200 && name.none { it.isISOControl() }) { "Invalid profile name" }
    }

    private data class Entry(
        val summary: ProfileSummary,
        val current: String,
        val previous: String? = null,
        val lastUsable: String? = null,
    )

    private data class Index(val entries: List<Entry>, val selected: String?) {
        fun entry(id: String): Entry = entries.find { it.summary.id == id }
            ?: throw IOException("Profile does not exist")
    }

    companion object {
        private val REVISION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.yaml")
        private fun nullableString(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.getString(key)
    }
}
