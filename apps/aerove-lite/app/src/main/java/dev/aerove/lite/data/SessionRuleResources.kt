package dev.aerove.lite.data

import dev.aerove.lite.vpn.CoreEndpoint
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One session owns these mutable copies; sealed bundles remain immutable and retained by their leases. */
class SessionRuleResources private constructor(private val privateDirectory: File,
    initial: PreparedProfile, private val profiles: ProfileRepository, val readRoot: File,
    private val ownerCurrent: () -> Boolean, private val initialRecords: Map<String, Asset>) {
    private data class Asset(val id: String, val key: String, val kind: String, val file: String, val bytes: Long,
        val sha256: String, val url: String?, val behavior: String, val format: String)
    private class CleanupFailures {
        var failure: Exception? = null
        suspend fun attempt(action: suspend () -> Unit) { try { action() } catch (error: Exception) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        fun finish() { failure?.let { throw it } }
    }
    private val gate = Mutex()
    private data class Snapshot(val prepared: PreparedProfile, val configuration: MeowRuntimeConfig?)
    @Volatile private var snapshot = Snapshot(initial, null)
    val prepared: PreparedProfile get() = snapshot.prepared
    val configuration: MeowRuntimeConfig? get() = snapshot.configuration
    private var held: ProfileLease? = null
    private val retired = mutableSetOf<ProfileLease>()
    private var closed = false
    private var tainted = false

    fun configure(config: MeowRuntimeConfig): MeowRuntimeConfig {
        check(configuration == null && ownerCurrent()) { "Session resources are not current" }
        val paths = initialRecords.values.associate { privateResource(checkNotNull(prepared.resourceDirectory), it.file).path to
            privateResource(readRoot, it.file).path }
        @Suppress("UNCHECKED_CAST")
        val yaml = ConfigYaml.dump(remap(ConfigYaml.parse(config.engineYaml), paths) as Map<String, Any?>)
        return config.withResources(yaml, readRoot, prepared.revision, prepared.generation).also { snapshot = Snapshot(prepared, it) }
    }

    suspend fun reload(next: PreparedProfile, endpoint: CoreEndpoint, current: () -> Boolean): Boolean = gate.withLock {
        if (closed || tainted || !ownerCurrent() || !current() || next.profileId != prepared.profileId) return@withLock false
        if (next.revision == prepared.revision && next.generation == prepared.generation && next.yaml == prepared.yaml && next.source == prepared.source) return@withLock true
        privateDomainTransaction(privateDirectory) {
            val actual = profiles.prepare(next.profileId)
            if (actual.revision != next.revision || actual.generation != next.generation || actual.source != next.source) return@privateDomainTransaction false
            val before = prepared
            if (before.source != next.source) return@privateDomainTransaction false
            val oldRecords = records(before)
            val newRecords = records(next)
            if (oldRecords.keys != newRecords.keys) return@privateDomainTransaction false
            fun normalized(value: PreparedProfile, assets: Map<String, Asset>): Any? = remap(ConfigYaml.parse(value.yaml), assets.values.associate {
                privateResource(checkNotNull(value.resourceDirectory), it.file).path to "asset:${it.id}"
            })
            if (normalized(before, oldRecords) != normalized(next, newRecords)) return@privateDomainTransaction false
            val oldRoot = ConfigYaml.parse(before.yaml)
            val changed = newRecords.values.filter { it.sha256 != oldRecords.getValue(it.id).sha256 || it.bytes != oldRecords.getValue(it.id).bytes }
            val names = changed.map { asset ->
                val old = oldRecords.getValue(asset.id)
                val name = asset.key.removePrefix("$.rule-providers.")
                val provider = (oldRoot["rule-providers"] as? Map<*, *>)?.get(name) as? Map<*, *>
                if (!asset.key.startsWith("$.rule-providers.") || asset.kind != "ruleset" || old.kind != "ruleset" || provider?.get("type") != "file" ||
                    listOf(old.url, old.behavior, old.format) != listOf(asset.url, asset.behavior, asset.format)) return@privateDomainTransaction false
                name
            }
            val lease = profiles.retain(next)
            retired += lease // Own every acquired lease before any operation that can fail.
            val staged = linkedMapOf<File, File>()
            val backups = linkedMapOf<File, File>()
            var accepted = false
            var cancelled: CancellationException? = null
            try {
                withContext(Dispatchers.IO) {
                    for (asset in changed) {
                        currentCoroutineContext().ensureActive()
                        val target = privateResource(readRoot, initialRecords.getValue(asset.id).file)
                        val pending = File(target.parentFile, ".pending-${UUID.randomUUID()}")
                        staged[target] = pending
                        copyAsset(privateResource(checkNotNull(next.resourceDirectory), asset.file), pending, asset)
                    }
                }
                check(ownerCurrent() && current()) { "Rule reload was superseded" }
                withContext(NonCancellable + Dispatchers.IO) {
                    for ((target, pending) in staged) {
                        val backup = File(target.parentFile, ".before-${UUID.randomUUID()}")
                        Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        backups[target] = backup
                        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    }
                }
                CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client ->
                    for (name in names) {
                        currentCoroutineContext().ensureActive()
                        check(ownerCurrent() && current()) { "Rule reload was superseded" }
                        client.reloadRuleProvider(name)
                    }
                }
                currentCoroutineContext().ensureActive()
                check(ownerCurrent() && current()) { "Rule reload was superseded" }
                profiles.markUsable(next)
                val previous = held
                // The session lock and domain CAS cover file, Core receipt, lease and lineage publication together.
                withContext(NonCancellable) {
                    check(ownerCurrent() && current()) { "Rule reload was superseded" }
                    held = lease
                    retired -= lease
                    previous?.let { retired += it }
                    snapshot = Snapshot(next, checkNotNull(configuration).withResources(checkNotNull(configuration).engineYaml,
                        readRoot, next.revision, next.generation))
                    accepted = true
                    previous?.let { profiles.release(it); retired -= it }
                }
                true
            } catch (error: CancellationException) { cancelled = error; throw error }
            catch (_: Exception) { false }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val cleanup = CleanupFailures()
                    if (!accepted) {
                        tainted = backups.isNotEmpty()
                        for ((target, backup) in backups) cleanup.attempt {
                            Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        }
                        cleanup.attempt { profiles.release(lease); retired -= lease }
                    }
                    for (pending in staged.values) cleanup.attempt { Files.deleteIfExists(pending.toPath()); Unit }
                    // Failed restore backups remain owned by readRoot until Native join and close.
                    if (accepted) for (backup in backups.values) cleanup.attempt { Files.deleteIfExists(backup.toPath()); Unit }
                    if (cancelled != null && cleanup.failure != null) cancelled!!.addSuppressed(cleanup.failure!!)
                    else cleanup.finish()
                }
            }
        }
    }

    /** The Host calls this only after Native and its owned file readers have joined. */
    suspend fun closeAfterNativeJoin() = withContext(NonCancellable + Dispatchers.IO) { gate.withLock {
        closed = true
        held?.let { retired += it; held = null }
        val cleanup = CleanupFailures()
        for (lease in retired.toList()) cleanup.attempt { profiles.release(lease); retired -= lease }
        cleanup.attempt { deletePrivateTree(privateDirectory, readRoot) }
        cleanup.finish()
    } }

    companion object {
        suspend fun create(privateDirectory: File, initial: PreparedProfile, profiles: ProfileRepository,
            current: () -> Boolean): SessionRuleResources = withContext(Dispatchers.IO) {
            val runtime = File(privateDirectory, "runtime/meow-read").canonicalFile
            require(runtime.toPath().startsWith(privateDirectory.canonicalFile.toPath()))
            check(runtime.isDirectory || runtime.mkdirs())
            val directory = File(runtime, UUID.randomUUID().toString())
            check(directory.mkdir())
            try {
                val assets = records(initial)
                for (asset in assets.values) {
                    currentCoroutineContext().ensureActive(); check(current()) { "Session resources were superseded" }
                    val target = privateResource(directory, asset.file)
                    check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                    copyAsset(privateResource(checkNotNull(initial.resourceDirectory), asset.file), target, asset)
                }
                SessionRuleResources(privateDirectory.canonicalFile, initial, profiles, directory.canonicalFile, current, assets)
            } catch (failure: Throwable) { deletePrivateTree(privateDirectory, directory); throw failure }
        }
        private suspend fun records(value: PreparedProfile): Map<String, Asset> = withContext(Dispatchers.IO) {
            val directory = value.resourceDirectory ?: return@withContext emptyMap()
            val text = File(directory, "manifest.json").inputStream().use { it.readBounded(1024 * 1024).strictUtf8() }
            val manifest = JSONObject(text)
            check(manifest.getString("generation") == value.generation) { "Resource generation changed" }
            val rows = manifest.getJSONArray("records")
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                Asset("${row.getString("key")}#$index", row.getString("key"), row.getString("kind"), row.getString("file"), row.getLong("bytes"), row.getString("sha256"),
                    if (row.isNull("url")) null else row.getString("url"), row.optString("behavior"), row.optString("format"))
            }.associateBy(Asset::id)
        }
        private suspend fun copyAsset(source: File, target: File, asset: Asset) {
            val limit = when (asset.kind) { "ruleset" -> MAX_RULE_INPUT_BYTES; "proxy-provider", "source-proxy" -> MAX_PROFILE_BYTES.toLong(); else -> MAX_RULE_INPUT_BYTES }
            require(asset.bytes in 0..limit)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= limit && bytes <= asset.bytes) { "Resource exceeds its verified byte limit" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            check(bytes == asset.bytes && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256) { "Resource bytes changed" }
        }
        private fun remap(value: Any?, paths: Map<String, String>): Any? = when (value) {
            is String -> paths[value] ?: value
            is Map<*, *> -> value.entries.associate { it.key to remap(it.value, paths) }
            is List<*> -> value.map { remap(it, paths) }
            else -> value
        }
    }
}
