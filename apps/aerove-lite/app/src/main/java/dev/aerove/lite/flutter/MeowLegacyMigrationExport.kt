package dev.aerove.lite.flutter

import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.WebDavBackupConfig
import dev.aerove.lite.data.deletePrivateTree
import dev.aerove.lite.data.privateDomainReadOnlyTransaction
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.sha256
import dev.aerove.lite.data.strictUtf8
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.profilebuilder.BuilderResources
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MeowLegacyExportException(val legacyField: String, val reason: String) :
    IllegalStateException("$legacyField: $reason")

/** Owns only the private export; closing never deletes, edits or marks the legacy source migrated. */
class MeowLegacyExportLease internal constructor(
    private val parent: File,
    private val directory: File,
    val manifestSha256: String,
) : Closeable {
    private val closed = AtomicBoolean()
    val manifest: File get() {
        check(!closed.get()) { "Legacy export lease is closed" }
        return File(directory, "manifest.json")
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) deletePrivateTree(parent, directory)
    }
    override fun toString() = "MeowLegacyExportLease([private])"
}

internal data class MeowMigrationOutput(val bytes: Long, val sha256: String)

class MeowLegacyMigrationExport(
    privateDirectory: File,
    private val profiles: ProfileRepository,
    private val settings: LiteSettingsStore,
    private val overrides: OverrideRepository,
) {
    private val root = privateDirectory.absoluteFile.toPath().normalize().toFile()
    init {
        require(listOf(profiles.migrationRoot, settings.migrationRoot, overrides.migrationRoot).all {
            it.canonicalFile == root.canonicalFile
        }) { "Migration repositories must share the exact private root" }
    }
    private val sourcePaths = listOf("profiles", "settings", "backups/webdav.json", "runtime/resources")
    private val requiredRecords = listOf("profiles/index.json", "settings/settings.json", "settings/overrides.json",
        "settings/builder-resources.yaml", "backups/webdav.json")
    private data class Record(val path: String, val bytes: Long, val hash: String, val stamp: String)

    suspend fun export(): MeowLegacyExportLease = exportProjection(emptyMap())

    internal suspend fun exportProjection(outputs: Map<String, MeowMigrationOutput>): MeowLegacyExportLease {
        var completed: MeowLegacyExportLease? = null
        try { return withContext(Dispatchers.IO) {
        safe(root)
        if (!root.isDirectory) fail("files", "private data root is missing")
        try {
            privateDomainReadOnlyTransaction(root) {
                preflightPaths()
                profiles.withMigrationSourceSnapshot {
                val before = inventory(outputs)
                if (requiredRecords.none { path -> before.any { it.path == path } }) fail("files", "no Native source records")
                validateSource()
                val parent = File(root, "native-lite-migration-exports")
                safe(parent)
                if (!parent.exists()) check(parent.mkdir())
                restrict(parent, true)
                val pending = File(parent, ".pending-${UUID.randomUUID()}")
                check(pending.mkdir()); restrict(pending, true)
                var published: File? = null
                try {
                    val payload = File(pending, "payload").apply { check(mkdir()); restrict(this, true) }
                    before.forEach { record ->
                        currentCoroutineContext().ensureActive()
                        val input = File(root, record.path)
                        safe(input)
                        val output = File(payload, record.path)
                        createParents(payload, output.parentFile!!)
                        FileOutputStream(output).use { stream ->
                            restrict(output, false)
                            input.inputStream().use { source ->
                                val buffer = ByteArray(8192)
                                var count = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val size = source.read(buffer)
                                    if (size < 0) break
                                    count += size
                                    if (count > record.bytes) fail(record.path, "source changed while copying")
                                    stream.write(buffer, 0, size)
                                }
                                if (count != record.bytes) fail(record.path, "source changed while copying")
                            }
                            stream.fd.sync()
                        }
                        if (output.length() != record.bytes || sha256(output) != record.hash) fail(record.path, "payload read-back mismatch")
                    }
                    if (inventory(outputs) != before) fail("files", "source changed during export")
                    val manifest = JSONObject().put("format", "aerove-lite-native-source-export").put("schema", 1)
                        .put("purpose", "source-recovery-candidate").put("migrationComplete", false)
                        .put("sourceRoots", JSONArray(sourcePaths))
                        .put("excludedNonSourceRoots", JSONArray(listOf("runtime/cache", "runtime-handoffs", "backups/archives")))
                        .put("presence", JSONObject().apply { requiredRecords.forEach { path -> put(path, before.any { it.path == path }) } })
                        .put("files", JSONArray(before.map { JSONObject().put("path", it.path).put("bytes", it.bytes)
                            .put("sha256", it.hash).put("role", if (it.path.startsWith("runtime/")) "resource-recovery-only" else "legacy-original") }))
                    val text = manifest.toString().toByteArray(Charsets.UTF_8)
                    if (text.size > 8 * 1024 * 1024) fail("manifest", "export manifest limit exceeded")
                    val document = File(pending, "manifest.json")
                    FileOutputStream(document).use { stream -> restrict(document, false); stream.write(text); stream.fd.sync() }
                    if (!document.readBytes().contentEquals(text)) fail("manifest", "read-back mismatch")
                    verifyPayload(pending, before)
                    currentCoroutineContext().ensureActive()
                    if (inventory(outputs) != before) fail("files", "source changed before publication")
                    val complete = File(parent, UUID.randomUUID().toString())
                    Files.move(pending.toPath(), complete.toPath(), ATOMIC_MOVE)
                    published = complete
                    verifyPayload(complete, before)
                    if (sha256(File(complete, "manifest.json")) != sha256(String(text, Charsets.UTF_8))) fail("manifest", "publication checksum mismatch")
                    currentCoroutineContext().ensureActive()
                    MeowLegacyExportLease(parent, complete, sha256(File(complete, "manifest.json"))).also { completed = it }
                } catch (failure: Throwable) {
                    published?.let { deletePrivateTree(parent, it) }
                    throw failure
                } finally { if (pending.exists()) deletePrivateTree(parent, pending) }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MeowLegacyExportException) { throw failure }
        catch (failure: Exception) {
            val resourceField = failure.message?.substringBefore(':')?.takeIf {
                it in setOf("runtime/resources", "runtime/resources/library") || it.matches(Regex("runtime/resources/library/[0-9a-f]{64}\\.json"))
            }
            if (resourceField != null) fail(resourceField, "resource owner is active, unavailable or source validation failed")
            fail("files/.domain.lock or .restore-journal", "read-only source transaction failed (${failure.javaClass.simpleName})")
        }
        } } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { completed?.close() }
            throw failure
        }
    }

    private fun preflightPaths() {
        sourcePaths.forEach { relative ->
            val base = File(root, relative)
            safe(base)
            if (Files.exists(base.toPath(), NOFOLLOW_LINKS)) Files.walk(base.toPath()).use { paths ->
                paths.forEach { safe(it.toFile()) }
            }
        }
    }

    private suspend fun validateSource() {
        val snapshot = checked("profiles/index.json") { profiles.migrationSnapshot() }
        if (File(root, "settings/settings.json").exists()) checked("settings/settings.json") { settings.migrationSnapshot() }
        val state = checked("settings/overrides.json") { overrides.migrationSnapshot() }
        if (state != null && state.profiles.any { profile -> snapshot.profiles.none { it.id == profile.profileId } }) {
            fail("settings/overrides.json.profiles[].profileId", "missing profile reference")
        }
        val builder = File(root, "settings/builder-resources.yaml")
        if (builder.exists()) checked("settings/builder-resources.yaml") { BuilderResources.parse(text(builder, 16 * 1024 * 1024)) }
        val dav = File(root, "backups/webdav.json")
        if (dav.exists()) checked("backups/webdav.json") { WebDavBackupConfig.decode(text(dav, 16384)) }
    }

    private suspend fun inventory(outputs: Map<String, MeowMigrationOutput>): List<Record> {
        val records = mutableListOf<Record>()
        var total = 0L
        for (relative in sourcePaths) {
            val base = File(root, relative)
            safe(base)
            if (!Files.exists(base.toPath(), NOFOLLOW_LINKS)) continue
            Files.walk(base.toPath()).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val path = iterator.next()
                    val file = path.toFile()
                    safe(file)
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attributes.isDirectory) {
                        if (file.name.startsWith(".pending-")) fail("runtime/resources", "unfinished resource source write")
                        continue
                    }
                    val name = root.toPath().relativize(path).joinToString("/")
                    if (file.name in setOf(".lock", ".domain.lock")) continue
                    if (name.length > 1024 || name.split('/').size > 32 || name.any { it.isISOControl() || it == ':' || it == '\\' }) {
                        fail("files", "ambiguous legacy relative path")
                    }
                    if (!attributes.isRegularFile) fail(name, "non-regular source")
                    val output = outputs[name]
                    if (output != null) {
                        if (attributes.size() != output.bytes || sha256(file) != output.sha256) fail(name, "registered shared output changed")
                        continue
                    }
                    if (name.endsWith(".pending") && outputs.containsKey(name.removeSuffix(".pending"))) {
                        if (attributes.size() > 64L * 1024 * 1024) fail(name, "shared staging limit exceeded")
                        continue
                    }
                    if (file.name.startsWith(".tmp-") || file.name.startsWith(".pending-")) fail(name, "unfinished source write")
                    if (attributes.size() > 64L * 1024 * 1024 || records.size >= 12000) fail(name, "export limit exceeded")
                    total = Math.addExact(total, attributes.size())
                    if (total > 512L * 1024 * 1024) fail("files", "export byte limit exceeded")
                    val hash = sha256(file)
                    val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (stamp(attributes) != stamp(after)) fail(name, "source changed while hashing")
                    records.add(Record(name, attributes.size(), hash, stamp(after)))
                }
            }
        }
        return records.sortedBy { it.path }
    }

    private fun verifyPayload(directory: File, expected: List<Record>) {
        val document = File(directory, "manifest.json")
        val manifest = JSONObject(text(document, 8 * 1024 * 1024))
        val rows = manifest.getJSONArray("files")
        if (rows.length() != expected.size) fail("manifest.files", "record count mismatch")
        expected.forEachIndexed { index, record ->
            val row = rows.getJSONObject(index)
            val payload = File(directory, "payload/${record.path}")
            safe(payload)
            if (row.getString("path") != record.path || row.getLong("bytes") != record.bytes ||
                row.getString("sha256") != record.hash || payload.length() != record.bytes || sha256(payload) != record.hash) {
                fail("manifest.files[$index]", "saved source identity mismatch")
            }
        }
    }

    private fun safe(file: File) {
        val path = file.absoluteFile.toPath().normalize()
        if (!path.startsWith(root.toPath())) fail("files", "path escapes private root")
        var cursor = path
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) fail("files", "symbolic link source or ancestor")
            cursor = cursor.parent
        }
        if (file.canonicalFile.toPath() != path) fail("files", "ambiguous private path")
    }

    private fun createParents(boundary: File, directory: File) {
        if (directory == boundary) return
        createParents(boundary, directory.parentFile!!)
        if (!directory.exists()) { check(directory.mkdir()); restrict(directory, true) }
    }
    private fun restrict(file: File, directory: Boolean) {
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        } else {
            val view = Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.AclFileAttributeView::class.java)
                ?: fail("candidate.permissions", "private file permissions unavailable")
            val owner = Files.getOwner(file.toPath())
            val entry = java.nio.file.attribute.AclEntry.newBuilder().setPrincipal(owner)
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission::class.java))
            if (directory) entry.setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,
                java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT)
            view.acl = listOf(entry.build())
            if (view.acl.any { it.principal() != owner }) fail("candidate.permissions", "private ACL read-back mismatch")
        }
    }
    private fun stamp(value: BasicFileAttributes) = "${value.fileKey()}:${value.size()}:${value.lastModifiedTime()}"
    private fun text(file: File, limit: Int) = file.inputStream().use { it.readBounded(limit).strictUtf8() }
    private suspend fun <T> checked(field: String, action: suspend () -> T): T = try { action() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            val detail = failure.message?.substringBefore(':')?.takeIf {
                it.matches(Regex("profiles/index\\.json\\.profiles\\[[0-9]+]\\.[A-Za-z0-9.]+"))
            }
            fail(detail ?: field, "invalid legacy source; no defaults or repairs applied")
        }
    private fun fail(field: String, reason: String): Nothing = throw MeowLegacyExportException(field, reason)
}
