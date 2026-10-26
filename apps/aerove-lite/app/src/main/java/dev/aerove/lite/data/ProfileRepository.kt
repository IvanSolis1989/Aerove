package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.profilesource.*
import dev.aerove.lite.resources.LibraryRuleSource
import dev.aerove.lite.resources.CachedLibraryRule
import dev.aerove.lite.resources.LibraryCacheRestore
import dev.aerove.lite.profilebuilder.*
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

data class ProfileMaintenanceInventory(val revision: String, val profiles: Int, val retainedFiles: Int, val cacheBytes: Long)

class UserDataResetResult(val cleanupFailure: Throwable?) {
    val cleanupComplete: Boolean get() = cleanupFailure == null
    override fun toString(): String = "UserDataResetResult(cleanupComplete=$cleanupComplete)"
}

/** Verified source asset opened by an editor; private identity fields are not caller-constructible. */
class ProfileResourceEditSnapshot internal constructor(
    val profileId: String, val key: String, val profileSourceIdentity: String,
    val revision: String, val generation: String, val sourceSha256: String,
    val contentSha256: String, val bytes: ByteArray, internal val restoreEpoch: Long, internal val repositoryRoot: String,
    internal val editorOversize: Boolean = false,
) {
    override fun toString(): String = "ProfileResourceEditSnapshot([content redacted])"
}
data class ProfileDocumentSnapshot(val profile: ProfileSummary, val revision: String, val yaml: String)

data class ProfileSourceTransform(val compiledYaml: String, val policyFingerprint: String,
    val isCurrent: suspend () -> Boolean = { true }, val geoUrls: Map<String, String> = emptyMap())

interface ProfileSourcePolicy {
    suspend fun forData(profileId: String, logicalOriginalSource: String): ProfileSourceTransform =
        ProfileSourceTransform(logicalOriginalSource, "")
    suspend fun forStart(profileId: String, logicalOriginalSource: String): ProfileSourceTransform
    companion object {
        val identity = object : ProfileSourcePolicy {
            override suspend fun forStart(profileId: String, logicalOriginalSource: String) = ProfileSourceTransform(logicalOriginalSource, "")
        }
    }
}

class ProfileRepository(
    privateDirectory: File,
    private val validator: ProfileValidator,
    private val downloader: SubscriptionDownloader = SubscriptionDownloader(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val resources: ResourceBundleManager? = null,
    private val sourcePolicy: ProfileSourcePolicy = ProfileSourcePolicy.identity,
    private val sourceDecoder: AgeProfileSourceDecoder? = null,
    private val globalResources: BuilderResourceLibrary? = null,
) {
    private val storage = PrivateStorage(File(privateDirectory, "profiles"))
    private val leases = liveProfiles.computeIfAbsent(storage.root.path) { mutableMapOf() }
    private val resourceEditEpoch = resourceEditEpochs.computeIfAbsent(storage.root.path) { java.util.concurrent.atomic.AtomicLong() }
    private var migratedResourceStamp: String? = null // Accessed only under the shared domain transaction.

    suspend fun snapshot(): ProfileSnapshot = storage.transaction {
        val index = load()
        runCatching { collectGarbage(index) }
        ProfileSnapshot(index.entries.map { it.summary }, index.selected)
    }

    /** Called only inside the central maintenance gate after all profile writers have joined. */
    suspend fun inspectMaintenance(): ProfileMaintenanceInventory = storage.transaction { maintenanceInventory(load()) }

    private suspend fun maintenanceInventory(index: Index): ProfileMaintenanceInventory {
        val paths = listOf("settings/settings.json", "profiles/index.json", "settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json")
        val stamps = paths.map { path ->
            val file = File(storage.root.parentFile, path)
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.toPath().startsWith(storage.root.parentFile!!.toPath())) { "Unsafe maintenance metadata" }
            require(!file.exists() || (file.isFile && file.length() <= when (path) {
                "profiles/index.json" -> MAX_PROFILE_INDEX_BYTES.toLong()
                "settings/settings.json" -> 128L * 1024
                "settings/overrides.json", "settings/builder-resources.yaml" -> 16L * 1024 * 1024
                else -> 1024L * 1024
            })) { "Invalid maintenance metadata" }
            "$path:${if (file.exists()) sha256(file) else "absent"}"
        }
        val cache = resources?.maintenanceCacheInventory() ?: (0L to "")
        val retained = index.entries.flatMap { listOfNotNull(it.current, it.previous, it.lastUsable) } + leases.values.map { it.revision }
        return ProfileMaintenanceInventory(sha256((stamps + cache.second).joinToString("\n")), index.entries.size,
            retained.distinct().size, cache.first)
    }

    suspend fun pruneCache(expectedRevision: String? = null) = storage.transaction {
        val index = load()
        if (expectedRevision != null) check(maintenanceInventory(index).revision == expectedRevision) { "Maintenance data changed" } // An unreadable index must never turn live files into orphans.
        collectGarbage(index)
        val library = File(storage.root.parentFile, "settings/builder-resources.yaml")
        val defined = if (library.isFile) BuilderResources.parse(library.inputStream().use { it.readBounded(16 * 1024 * 1024).strictUtf8() })
            .ruleProviders.mapNotNull { if (it.fields["type"] == "file") globalRuleLocalId(it.fields["path"] as? String) else null } else emptyList()
        val revisions = index.entries.flatMap { listOfNotNull(it.current, it.previous, it.lastUsable) } + leases.values.map { it.revision }
        val originals = revisions.distinct().flatMap { localGlobalRuleSources(readRevision(it)).mapNotNull { source -> source.localId } }
        resources?.pruneUnreferencedLocalRules((defined + originals).toSet())
    }

    /** Caller must stop/join the runtime and quiesce every writer, then exit after success. */
    suspend fun resetUserData(expectedRevision: String? = null, resetPreferences: suspend () -> Unit): UserDataResetResult = storage.transaction {
        check(leases.isEmpty()) { "Cannot reset while a profile is leased" }
        resources?.requireMaintenanceIdle()
        if (expectedRevision != null) check(maintenanceInventory(load()).revision == expectedRevision) { "Maintenance data changed" }
        globalResources?.invalidateRemoteRefreshes()
        resourceEditEpoch.incrementAndGet()
        atomicDomainRestore(storage.root.parentFile!!) {
            resetPreferences()
            save(Index(emptyList(), null), collect = false)
            listOf("settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json").forEach { path ->
                java.nio.file.Files.deleteIfExists(File(storage.root.parentFile, path).toPath())
            }
            check(load() == Index(emptyList(), null)) { "Profile reset readback failed" }
            check(listOf("settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json")
                .none { File(storage.root.parentFile, it).exists() }) { "Library reset readback failed" }
        }
        // Destructive orphan cleanup is deliberately after the logical journal commit.
        // A cleanup error cannot claim that the already-committed reset rolled back.
        val failure = try {
            collectGarbage(Index(emptyList(), null))
            resources?.pruneUnreferencedLocalRules(emptySet())
            null
        } catch (error: Throwable) { error }
        UserDataResetResult(failure)
    }

    suspend fun list(): List<ProfileSummary> = snapshot().profiles

    /** Policy reads must not collect a compiled candidate before its index commit. */
    suspend fun profileSummary(id: String): ProfileSummary? = storage.transaction {
        load().entries.firstOrNull { it.summary.id == id }?.summary
    }

    /** UI metadata only: no source epoch, prepared generation or runtime apply changes. */
    suspend fun updateProxyGroupUi(id: String, currentGroup: String?, unfoldedGroups: Set<String>): ProfileSummary {
        val unfolded = unfoldedGroups.toSet()
        return storage.transaction {
            val index = load()
            val previous = index.entry(id)
            val summary = previous.summary.copy(currentProxyGroup = currentGroup, unfoldedProxyGroups = unfolded)
            val updated = previous.copy(summary = summary)
            currentCoroutineContext().ensureActive()
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) updated else it }), collect = false)
            load().entry(id).summary.also { check(it == summary) { "Proxy group UI readback failed" } }
        }
    }

    suspend fun importText(name: String, yaml: String, url: String? = null): ProfileSummary =
        importBytes(UUID.randomUUID().toString(), name, yaml.toByteArray(Charsets.UTF_8), url)

    suspend fun importText(name: String, yaml: String, url: String?, custom: Boolean): ProfileSummary =
        importBytes(UUID.randomUUID().toString(), name, yaml.toByteArray(Charsets.UTF_8), url, custom = custom)

    private suspend fun importText(id: String, name: String, yaml: String, url: String?, download: SubscriptionDownloader.Download? = null, custom: Boolean = false,
        sourceMetadata: ProfileSourceMetadata = ProfileSourceMetadata.plain(yaml),
        onCommitted: (ProfileSummary) -> Unit = {}): ProfileSummary {
        checkGeneration(id)
        checkName(name)
        sourceMetadata.verify(yaml)
        url?.let(SubscriptionDownloader::parseUrl)
        val bundle = prepareBundle(id, yaml)
        var committed = false
        try { return storage.transaction {
            val index = load()
            check(index.entries.size < 1000) { "Profile limit reached" }
            check(index.entries.none { it.summary.id == id }) { "Profile identity already exists" }
            currentCoroutineContext().ensureActive()
            val timestamp = clock()
            val summary = ProfileSummary(id, name.trim(), url, timestamp,
                sourceIdentity = UUID.randomUUID().toString(), lastSuccessfulUpdateAt = if (download != null) timestamp else null,
                subscriptionUserInfo = download?.subscriptionUserInfo, contentDisposition = download?.contentDisposition, custom = custom)
            val revision = writeRevision(yaml)
            save(index.copy(
                entries = index.entries + Entry(summary, revision, bundle = bundle?.generation, nextRefreshAt = bundle?.nextRefreshAt, sourceMetadata = sourceMetadata),
                selected = index.selected ?: id,
            ))
            val persisted = load().entry(id)
            check(persisted.summary == summary && readSource(persisted) == yaml) { "Profile commit readback mismatch" }
            committed = true
            bundle?.let { resources?.committed(id, it.generation) }
            onCommitted(summary)
            summary
        } } finally { if (!committed && bundle != null) withContext(NonCancellable) { resources?.discard(id, bundle.generation) } }
    }

    suspend fun importFile(name: String, input: InputStream): ProfileSummary = importFile(name, input, null)

    suspend fun importFile(name: String, input: InputStream, secret: AgeSecret?): ProfileSummary {
        checkName(name)
        var receipt: ProfileSummary? = null
        val saved: (ProfileSummary) -> Unit = { receipt = it }
        return try { input.buffered().use { buffered ->
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
                var decoded: DecodedProfileSource? = null
                manager.importArchiveDecoded(id, buffered, { bytes -> decodeSource(bytes, secret).also { decoded = it }.yaml }) { yaml ->
                    importText(id, name, yaml, null, sourceMetadata = ProfileSourceMetadata.decoded(checkNotNull(decoded), secret), onCommitted = saved)
                }
            } else {
                val bytes = withContext(Dispatchers.IO) { buffered.readBounded(MAX_CIPHERTEXT_BYTES) }
                try { importBytes(UUID.randomUUID().toString(), name, bytes, null, secret, onCommitted = saved) } finally { bytes.fill(0) }
            }
        } } catch (cancelled: CancellationException) {
            // Preserve a read-back commit across cancellable dispatcher returns, including archive cleanup.
            receipt ?: throw cancelled
        }
    }

    suspend fun addSubscription(name: String, url: String): ProfileSummary = addSubscription(name, url, null)

    suspend fun addSubscription(name: String, url: String, secret: AgeSecret?): ProfileSummary =
        addSubscription(UUID.randomUUID().toString(), name, url, secret)

    private suspend fun addSubscription(id: String, name: String, url: String, secret: AgeSecret?,
        onCommitted: (ProfileSummary) -> Unit = {}): ProfileSummary {
        checkName(name)
        val raw = downloader.downloadRaw(url)
        try {
            val decoded = decodeSource(raw.bytes, secret)
            val headers = SubscriptionDownloader.Download(decoded.yaml, raw.subscriptionUserInfo, raw.contentDisposition)
            return importText(id, name, decoded.yaml, url, headers,
                sourceMetadata = ProfileSourceMetadata.decoded(decoded, secret), onCommitted = onCommitted)
        } finally { raw.bytes.fill(0) }
    }

    suspend fun importPlanned(item: PlannedProfileImport): ProfileSummary {
        checkGeneration(item.id)
        var receipt: ProfileSummary? = null
        val saved: (ProfileSummary) -> Unit = { receipt = it }
        return try {
            when (val entry = item.entry) {
                is ProfileLinkEntry.Subscription -> addSubscription(item.id, entry.name, entry.url, item.secret, saved)
                is ProfileLinkEntry.Inline -> importBytes(item.id, entry.name, entry.config.toByteArray(Charsets.UTF_8), null, item.secret, onCommitted = saved)
            }
        } catch (cancelled: CancellationException) {
            // A completed, read-back commit is a success even if cancellation arrives on dispatcher return.
            receipt ?: throw cancelled
        }
    }

    suspend fun update(id: String, request: ProfileMaintenanceRequest? = null): ProfileSummary {
        val original = storage.transaction { load().entry(id) }
        checkRequest(original.summary, request)
        val url = original.summary.url ?: throw IOException("Local profile requires a replacement file")
        val raw = downloader.downloadRaw(url)
        val secret = original.sourceMetadata?.secret()
        try {
            val decoded = decodeSource(raw.bytes, secret)
            val headers = SubscriptionDownloader.Download(decoded.yaml, raw.subscriptionUserInfo, raw.contentDisposition)
            return replace(id, decoded.yaml, original.current, original.summary.sourceIdentity, request, headers,
                sourceMetadata = ProfileSourceMetadata.decoded(decoded, secret))
        } finally { secret?.close(); raw.bytes.fill(0) }
    }

    suspend fun editCandidate(expected: ProfileDocumentSnapshot, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long, secret: AgeSecret? = null, replaceSecret: Boolean = false,
        request: ProfileMaintenanceRequest? = null): ProfileSummary {
        checkName(name)
        require(intervalMillis > 0) { "Update interval must be positive" }
        url?.let(SubscriptionDownloader::parseUrl)
        val original = storage.transaction {
            load().entry(expected.profile.id).also {
                if (it.current != expected.revision || it.summary != expected.profile) throw ProfileConflictException(expected.profile.id)
                checkRequest(it.summary, request)
            }
        }
        val prior = original.sourceMetadata ?: ProfileSourceMetadata.plain(expected.yaml)
        val candidateMetadata = if (replaceSecret) prior.withSecret(secret) else prior
        val candidateSummary = original.summary.copy(name = name.trim(), url = url, autoUpdate = autoUpdate, updateIntervalMillis = intervalMillis)
        if (url == original.summary.url && candidateMetadata.sameSecret(prior)) return storage.transaction {
            val latest = load()
            val old = latest.entry(expected.profile.id)
            if (old.current != expected.revision || old.summary != expected.profile) throw ProfileConflictException(expected.profile.id)
            checkRequest(old.summary, request)
            currentCoroutineContext().ensureActive()
            save(latest.copy(entries = latest.entries.map { if (it.summary.id == old.summary.id) it.copy(summary = candidateSummary) else it }))
            load().entry(old.summary.id).summary.also { require(it == candidateSummary) { "Profile metadata readback failed" } }
        }
        val candidateUrl = url ?: throw IllegalArgumentException("A subscription URL is required to validate changed source credentials")
        val candidateSecret = candidateMetadata.secret()
        try {
            val raw = downloader.downloadRaw(candidateUrl)
            try {
                val decoded = decodeSource(raw.bytes, candidateSecret)
                return replace(expected.profile.id, decoded.yaml, expected.revision, original.summary.sourceIdentity, request,
                    SubscriptionDownloader.Download(decoded.yaml, raw.subscriptionUserInfo, raw.contentDisposition), replaceSource = true,
                    sourceMetadata = ProfileSourceMetadata.decoded(decoded, candidateSecret),
                    candidateSummary = candidateSummary, expectedMetadata = expected.profile)
            } finally { raw.bytes.fill(0) }
        } finally { candidateSecret?.close() }
    }

    private suspend fun importBytes(id: String, name: String, bytes: ByteArray, url: String?, secret: AgeSecret? = null,
        custom: Boolean = false, onCommitted: (ProfileSummary) -> Unit = {}): ProfileSummary {
        val decoded = decodeSource(bytes, secret)
        return importText(id, name, decoded.yaml, url, custom = custom,
            sourceMetadata = ProfileSourceMetadata.decoded(decoded, secret), onCommitted = onCommitted)
    }

    private suspend fun decodeSource(bytes: ByteArray, secret: AgeSecret?): DecodedProfileSource =
        (sourceDecoder ?: plainDecoder).decode(bytes, secret)

    suspend fun readAgeSecret(id: String): AgeSecret? = storage.transaction { load().entry(id).sourceMetadata?.secret() }

    suspend fun sourceLineage(id: String): ProfileSourceLineage = storage.transaction {
        val entry = load().entry(id)
        val source = readSource(entry)
        (entry.sourceMetadata ?: ProfileSourceMetadata.plain(source)).lineage()
    }

    suspend fun setAgeSecret(id: String, secret: AgeSecret?): ProfileSummary = storage.transaction {
        val index = load()
        val old = index.entry(id)
        val metadata = old.sourceMetadata ?: ProfileSourceMetadata.plain(readSource(old))
        val changed = metadata.withSecret(secret)
        if (metadata.sameSecret(changed)) old.summary else {
            val summary = old.summary.copy(sourceIdentity = UUID.randomUUID().toString())
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) it.copy(summary = summary, sourceMetadata = changed) else it }))
            summary
        }
    }

    suspend fun replace(id: String, yaml: String): ProfileSummary {
        val revision = storage.transaction { load().entry(id).current }
        return replace(id, yaml, revision, replaceSource = true)
    }

    suspend fun documentSnapshot(id: String): ProfileDocumentSnapshot = storage.transaction {
        load().entry(id).let { ProfileDocumentSnapshot(it.summary, it.current, readSource(it)) }
    }

    suspend fun replaceDocument(expected: ProfileDocumentSnapshot, input: InputStream,
        disableAutoUpdate: Boolean = false, isCurrent: () -> Boolean = { true }): ProfileSummary {
        val secret = readAgeSecret(expected.profile.id)
        try { return replaceDocumentWithSecret(expected, input, secret, disableAutoUpdate, isCurrent) }
        finally { secret?.close() }
    }

    suspend fun replaceDocumentWithSecret(expected: ProfileDocumentSnapshot, input: InputStream, secret: AgeSecret?,
        disableAutoUpdate: Boolean = false, isCurrent: () -> Boolean = { true }): ProfileSummary {
        storage.transaction {
            val latest = load().entry(expected.profile.id)
            if (latest.current != expected.revision) throw ProfileConflictException(expected.profile.id)
            if (latest.summary.sourceIdentity != expected.profile.sourceIdentity || !isCurrent()) throw CancellationException("Profile source changed")
        }
        val bytes = withContext(Dispatchers.IO) { input.readBounded(MAX_CIPHERTEXT_BYTES) }
        try {
            val decoded = decodeSource(bytes, secret)
            return replace(expected.profile.id, decoded.yaml, expected.revision, expected.profile.sourceIdentity,
                replaceSource = true, commitGuard = isCurrent, disableAutoUpdate = disableAutoUpdate,
                sourceMetadata = ProfileSourceMetadata.decoded(decoded, secret))
        } finally { bytes.fill(0) }
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
        commitGuard: () -> Boolean = { true }, disableAutoUpdate: Boolean = false,
        previousResources: ResourceBundle? = null, refreshResourceKeys: Set<String>? = null,
        sourceMetadata: ProfileSourceMetadata? = null, candidateSummary: ProfileSummary? = null,
        expectedMetadata: ProfileSummary? = null): ProfileSummary {
        if (!commitGuard()) throw CancellationException("Profile operation superseded")
        val bundle = prepareBundle(id, yaml, previousResources, refreshResourceKeys)
        var committed = false
        val write: suspend () -> ProfileSummary = { storage.transaction {
            val index = load()
            val old = index.entry(id)
            if (old.current != expectedRevision) throw ProfileConflictException(id)
            if (expectedMetadata != null && old.summary != expectedMetadata) throw ProfileConflictException(id)
            if (expectedSource != null && old.summary.sourceIdentity != expectedSource) throw CancellationException("Profile source changed")
            currentCoroutineContext().ensureActive()
            checkRequest(old.summary, request)
            if (!commitGuard()) throw CancellationException("Profile operation superseded")
            val timestamp = clock()
            val updated = old.copy(
                summary = (candidateSummary ?: old.summary).copy(updatedAt = timestamp,
                    autoUpdate = if (disableAutoUpdate) false else candidateSummary?.autoUpdate ?: old.summary.autoUpdate,
                    sourceIdentity = if (replaceSource) UUID.randomUUID().toString() else old.summary.sourceIdentity,
                    lastSuccessfulUpdateAt = if (download != null) timestamp else if (replaceSource) null else old.summary.lastSuccessfulUpdateAt,
                    subscriptionUserInfo = if (download != null) download.subscriptionUserInfo else if (replaceSource) null else old.summary.subscriptionUserInfo,
                    contentDisposition = if (download != null) download.contentDisposition else if (replaceSource) null else old.summary.contentDisposition),
                current = writeRevision(yaml),
                previous = old.current,
                bundle = bundle?.generation,
                previousBundle = old.bundle,
                nextRefreshAt = bundle?.nextRefreshAt,
                sourceMetadata = sourceMetadata ?: if (sha256(yaml) == old.sourceMetadata?.logicalSourceSha256) old.sourceMetadata
                    else ProfileSourceMetadata.plain(yaml).let { plain -> old.sourceMetadata?.secret()?.use { plain.withSecret(it) } ?: plain },
            )
            checkRequest(old.summary, request)
            if (!commitGuard()) throw CancellationException("Profile operation superseded")
            currentCoroutineContext().ensureActive()
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) updated else it }), collect = candidateSummary == null)
            if (candidateSummary != null) {
                val actual = load().entry(id)
                require(actual.current == updated.current && actual.summary == updated.summary &&
                    actual.sourceMetadata?.sameSource(checkNotNull(updated.sourceMetadata)) == true) { "Candidate profile readback failed" }
                readSource(actual)
            }
            if (candidateSummary == null) {
                committed = true
                bundle?.let { resources?.committed(id, it.generation) }
            }
            updated.summary
        } }
        try {
            if (candidateSummary == null) return write()
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                atomicDomainRestore(storage.root.parentFile!!) {
                    write().also { if (request != null && !request.isCurrent()) throw CancellationException("Candidate update superseded") }
                }.also {
                    committed = true
                    bundle?.let { resources?.committed(id, it.generation) }
                }
            }
        } finally { if (!committed && bundle != null) withContext(NonCancellable) { resources?.discard(id, bundle.generation) } }
    }

    suspend fun isCurrentSource(id: String, sourceIdentity: String): Boolean = storage.transaction {
        load().entries.any { it.summary.id == id && it.summary.sourceIdentity == sourceIdentity }
    }

    suspend fun migrateGlobalResourceIdentity(library: BuilderResourceLibrary): BuilderResourceSnapshot = privateDomainTransaction(storage.root.parentFile!!) {
        val expected = library.snapshot()
        val index = storage.transaction { load() }
        val stamp = resourceIdentityStamp(expected, index)
        if (expected.schemaVersion == 2 && migratedResourceStamp == stamp) return@privateDomainTransaction expected
        val sources = storage.transaction { load().entries.associate { it.summary.id to readSource(it) } }
        val plan = expected.planIdentityMigration(sources)
        val changed = plan.sources != sources
        val result = if (expected.schemaVersion == 2 && !changed) expected else atomicDomainRestore(storage.root.parentFile!!) {
            applyGlobalResourceIdentityPlan(library, expected, sources, plan)
        }
        migratedResourceStamp = resourceIdentityStamp(result, storage.transaction { load() })
        result
    }

    internal suspend fun migrateGlobalResourceIdentityInTransaction(library: BuilderResourceLibrary,
        expected: BuilderResourceSnapshot): BuilderResourceSnapshot {
        val sources = storage.transaction { load().entries.associate { it.summary.id to readSource(it) } }
        val plan = expected.planIdentityMigration(sources)
        return applyGlobalResourceIdentityPlan(library, expected, sources, plan)
    }

    private suspend fun applyGlobalResourceIdentityPlan(library: BuilderResourceLibrary, expected: BuilderResourceSnapshot,
        sources: Map<String, String>, plan: BuilderIdentityMigrationPlan): BuilderResourceSnapshot {
        rewriteGlobalResourceHeaders { id, source -> checkNotNull(plan.sources[id]).also {
            require(sources[id] == source) { "Profile changed during resource identity migration" }
        } }
        return if (expected.schemaVersion == 1) library.commitIdentityMigration(expected, plan) else expected
    }

    private fun resourceIdentityStamp(snapshot: BuilderResourceSnapshot, index: Index): String =
        sha256(JSONArray(listOf(snapshot.encoded, index.entries.map { listOf(it.summary.id, it.current) })).toString())

    suspend fun commitGlobalResourceMutation(library: BuilderResourceLibrary, expected: BuilderResourceSnapshot,
        next: BuilderResources, removed: Map<BuilderResourceKind, Set<String>> = emptyMap()): BuilderResourceSnapshot =
        privateDomainTransaction(storage.root.parentFile!!) {
            require(library.snapshot().encoded == expected.encoded) { "Resources changed; reload before saving" }
            atomicDomainRestore(storage.root.parentFile!!) { commitGlobalResourceMutationInTransaction(library, expected, next, removed) }
        }

    internal suspend fun commitGlobalResourceMutationInTransaction(library: BuilderResourceLibrary, expected: BuilderResourceSnapshot,
        next: BuilderResources, removed: Map<BuilderResourceKind, Set<String>> = emptyMap()): BuilderResourceSnapshot {
        val dropped = BuilderResourceKind.entries.associateWith { kind ->
            expected.resources.enumerate().filter { it.kind == kind }.map { it.id }.toSet() -
                next.enumerate().filter { it.kind == kind }.map { it.id }.toSet()
        }.filterValues { it.isNotEmpty() }
        require(removed.filterValues { it.isNotEmpty() } == dropped) { "Deleted resource IDs require an exact reference-detachment plan" }
        val stable = migrateGlobalResourceIdentityInTransaction(library, expected)
        if (removed.values.any { it.isNotEmpty() }) rewriteGlobalResourceHeaders { _, source ->
            removed.entries.fold(source) { current, (kind, ids) -> stable.resources.detachReferences(current, kind, ids) }
        }
        return library.replace(stable, next)
    }

    /** Domain journal owns the index; immutable revisions and leased generations remain available. */
    private suspend fun rewriteGlobalResourceHeaders(transform: (String, String) -> String) = storage.transaction {
        val index = load()
        val entries = index.entries.map { old ->
            val source = readSource(old)
            val rewritten = transform(old.summary.id, source)
            if (rewritten == source) old else {
                val header = Regex("(?m)^\uFEFF?[ \\t]*# aerove-builder-bindings: [^\\r\\n]*(?:\\r?\\n|$)")
                fun body(value: String) = value.replace(header) { if (it.value.startsWith('\uFEFF')) "\uFEFF" else "" }
                require(body(source) == body(rewritten)) { "Resource identity migration changed profile body" }
                old.copy(current = writeRevision(rewritten), previous = old.current, previousBundle = old.bundle,
                    bundle = null, nextRefreshAt = null,
                    sourceMetadata = (old.sourceMetadata ?: ProfileSourceMetadata.plain(source)).rewritten(rewritten))
            }
        }
        if (entries != index.entries) save(index.copy(entries = entries), collect = false)
        Unit
    }

    suspend fun editMetadata(id: String, name: String, url: String?, autoUpdate: Boolean, intervalMillis: Long): ProfileSummary =
        editMetadataInternal(id, name, url, autoUpdate, intervalMillis, false, null)

    suspend fun editMetadataWithSecret(id: String, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long, secret: AgeSecret?): ProfileSummary =
        editMetadataInternal(id, name, url, autoUpdate, intervalMillis, true, secret)

    private suspend fun editMetadataInternal(id: String, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long, replaceSecret: Boolean, secret: AgeSecret?): ProfileSummary {
        checkName(name)
        url?.let(SubscriptionDownloader::parseUrl)
        require(intervalMillis > 0) { "Update interval must be positive" }
        return storage.transaction {
            val index = load()
            val old = index.entry(id)
            val before = old.sourceMetadata ?: ProfileSourceMetadata.plain(readSource(old))
            val metadata = if (replaceSecret) before.withSecret(secret) else before
            val changedSource = old.summary.url != url || !metadata.sameSecret(before)
            val summary = old.summary.copy(name = name.trim(), url = url, autoUpdate = autoUpdate,
                updateIntervalMillis = intervalMillis,
                sourceIdentity = if (changedSource) UUID.randomUUID().toString() else old.summary.sourceIdentity,
                lastSuccessfulUpdateAt = if (changedSource) null else old.summary.lastSuccessfulUpdateAt,
                subscriptionUserInfo = if (changedSource) null else old.summary.subscriptionUserInfo,
                contentDisposition = if (changedSource) null else old.summary.contentDisposition)
            save(index.copy(entries = index.entries.map { if (it.summary.id == id) it.copy(summary = summary, sourceMetadata = metadata) else it }))
            summary
        }
    }

    private fun checkRequest(summary: ProfileSummary, request: ProfileMaintenanceRequest?) {
        if (request != null && (request.profileId != summary.id || request.sourceIdentity != summary.sourceIdentity || !request.isCurrent())) {
            throw CancellationException("Profile update superseded")
        }
    }

    suspend fun prepare(id: String): PreparedProfile {
        var pair = storage.transaction { load().entry(id).let { it to readSource(it) } }
        if (resources != null && pair.first.bundle == null) {
            replace(id, pair.second, pair.first.current)
            pair = storage.transaction { load().entry(id).let { it to readSource(it) } }
        }
        val (entry, source) = pair
        val bundle = entry.bundle?.let { checkNotNull(resources) { "Resource manager is required for this profile" }.load(id, it, source) }
        if (bundle == null) validate(source)
        return PreparedProfile(id, source, bundle?.yaml ?: source, entry.current, bundle?.generation, bundle?.directory, bundle?.nextRefreshAt)
    }

    suspend fun prepareForStart(id: String): PreparedProfile {
        globalResources?.let { migrateGlobalResourceIdentity(it) }
        val (entry, source) = storage.transaction { load().entry(id).let { it to readSource(it) } }
        val transform = sourcePolicy.forStart(id, source)
        val manager = resources
        if (manager == null) {
            validate(transform.compiledYaml)
            check(transform.isCurrent()) { "Profile policy changed during preparation" }
            return PreparedProfile(id, source, transform.compiledYaml, entry.current, null, null, null)
        }
        val previous = entry.bundle?.let { manager.load(id, it, source) }
        if (previous != null && previous.compiledInputSha256 == sha256(transform.compiledYaml) &&
            previous.sourcePolicyFingerprint == transform.policyFingerprint &&
            previous.policyFingerprint == manager.currentPolicyFingerprint(previous, transform.policyFingerprint)) {
            check(transform.isCurrent()) { "Profile policy changed during preparation" }
            return PreparedProfile(id, source, previous.yaml, entry.current, previous.generation, previous.directory, previous.nextRefreshAt)
        }
        val bundle = manager.prepare(id, transform.compiledYaml, previous, refreshKeys = emptySet(), logicalOriginalSource = source,
            policyFingerprint = transform.policyFingerprint, geoUrls = transform.geoUrls)
        var committed = false
        try {
            // Policy callbacks may read this repository's metadata; keep them outside its storage mutex.
            check(transform.isCurrent() && bundle.policyFingerprint == manager.currentPolicyFingerprint(bundle, transform.policyFingerprint)) {
                "Profile policy changed during preparation"
            }
            storage.transaction {
                val index = load()
                val current = index.entry(id)
                if (current.current != entry.current || current.bundle != entry.bundle || current.summary.custom != entry.summary.custom) {
                    throw ProfileConflictException(id)
                }
                currentCoroutineContext().ensureActive()
                save(index.copy(entries = index.entries.map { if (it.summary.id == id) it.copy(bundle = bundle.generation,
                    nextRefreshAt = bundle.nextRefreshAt) else it }), collect = false)
                manager.committed(id, bundle.generation)
                committed = true
            }
            return PreparedProfile(id, source, bundle.yaml, entry.current, bundle.generation, bundle.directory, bundle.nextRefreshAt)
        } finally { if (!committed) manager.discard(id, bundle.generation) }
    }

    suspend fun refreshResources(id: String): PreparedProfile = privateDomainTransaction(storage.root.parentFile!!) {
        val manager = checkNotNull(resources) { "Resource manager is not configured" }
        val (entry, source) = storage.transaction { load().entry(id).let { it to readSource(it) } }
        manager.withResourceOverride(id, null) {
            replace(id, source, entry.current, refreshResourceKeys = setOf("*"))
            prepare(id)
        }
    }

    suspend fun refreshIfDue(id: String): PreparedProfile? {
        val entry = storage.transaction { load().entry(id) }
        val due = entry.nextRefreshAt ?: return null
        return if (clock() >= due) refreshResources(id) else null
    }

    /** Explicit sync restores the original URL after a local side-load, without changing source identity. */
    suspend fun refreshResource(id: String, key: String): PreparedProfile = mutateResource(id, key, null)

    suspend fun sideLoadResource(id: String, key: String, input: InputStream): PreparedProfile = mutateResource(id, key, input)

    suspend fun resourceEditSnapshot(id: String, key: String, textLimit: Int = MAX_PROFILE_BYTES): ProfileResourceEditSnapshot =
        privateDomainTransaction(storage.root.parentFile!!) {
            val manager = checkNotNull(resources) { "Resource manager is not configured" }
            val prepared = prepare(id)
            manager.load(id, checkNotNull(prepared.generation), prepared.source)
            val record = replaceableResourceRecord(prepared, key)
            val entry = storage.transaction { load().entry(id) }
            // A file the editor would refuse is hashed in place; its bytes are never loaded.
            val bytes = withContext(Dispatchers.IO) {
                val file = privateResource(checkNotNull(prepared.resourceDirectory), record.getString("file"))
                if (file.editorTextBytes() > textLimit) null.also { check(sha256(file) == record.getString("sha256")) { "Resource content changed" } }
                else file.readBytes().also {
                    check(java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } == record.getString("sha256")) { "Resource content changed" }
                }
            }
            ProfileResourceEditSnapshot(id, key, entry.summary.sourceIdentity, prepared.revision,
                prepared.generation, record.getString("sourceSha256"), record.getString("sha256"), bytes ?: ByteArray(0), resourceEditEpoch.get(), storage.root.path,
                editorOversize = bytes == null)
        }

    suspend fun replaceResource(expected: ProfileResourceEditSnapshot, input: InputStream): PreparedProfile =
        mutateResource(expected.profileId, expected.key, input, expected)

    private suspend fun replaceableResourceRecord(prepared: PreparedProfile, key: String): JSONObject = withContext(Dispatchers.IO) {
        val rows = JSONObject(File(checkNotNull(prepared.resourceDirectory), "manifest.json").readText()).getJSONArray("records")
        (0 until rows.length()).map(rows::getJSONObject).singleOrNull {
            it.getString("key") == key && it.getString("kind") !in setOf("proxy-provider", "private-file")
        } ?: throw IllegalArgumentException("Resource is not a replaceable source asset")
    }

    private suspend fun mutateResource(id: String, key: String, input: InputStream?, expected: ProfileResourceEditSnapshot? = null): PreparedProfile =
        privateDomainTransaction(storage.root.parentFile!!) {
            val manager = checkNotNull(resources) { "Resource manager is not configured" }
            val before = prepare(id)
            val record = replaceableResourceRecord(before, key)
            val previous = manager.load(id, checkNotNull(before.generation), before.source)
            if (expected != null) {
                val entry = storage.transaction { load().entry(id) }
                val actual = privateResource(checkNotNull(before.resourceDirectory), record.getString("file"))
                if (storage.root.path != expected.repositoryRoot || resourceEditEpoch.get() != expected.restoreEpoch || entry.summary.sourceIdentity != expected.profileSourceIdentity ||
                    before.revision != expected.revision || before.generation != expected.generation ||
                    record.getString("sourceSha256") != expected.sourceSha256 || record.getString("sha256") != expected.contentSha256 ||
                    withContext(Dispatchers.IO) { sha256(actual) } != expected.contentSha256) throw ProfileConflictException(id)
            }
            manager.withResourceOverride(id, setOf(key), input, record.getString("sourceSha256")) {
                replace(id, before.source, before.revision, previousResources = previous, refreshResourceKeys = setOf(key))
                prepare(id)
            }
        }

    suspend fun retain(prepared: PreparedProfile): ProfileLease {
        var acquired: ProfileLease? = null
        try {
            val lease = storage.transaction {
                require(prepared.revision.matches(REVISION) && storage.file(prepared.revision).isFile &&
                    readRevision(prepared.revision) == prepared.source) { "Prepared profile revision was reclaimed; prepare it again" }
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
        if (old.current != prepared.revision || old.bundle != prepared.generation || readSource(old) != prepared.source) {
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
            entry to readRevision(usable)
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
                    previous = old.current, previousBundle = old.bundle, nextRefreshAt = bundle?.nextRefreshAt,
                    sourceMetadata = ProfileSourceMetadata.plain(source).let { plain -> old.sourceMetadata?.secret()?.use { plain.withSecret(it) } ?: plain }) else it
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
            val source = readSource(entry)
            val bundle = entry.bundle?.let { checkNotNull(resources).load(entry.summary.id, it, source) }
            if (bundle == null) validate(source)
            val local = linkedMapOf<String, File>()
            bundle?.let {
                val manifest = JSONObject(File(it.directory, "manifest.json").inputStream().use { stream -> stream.readBounded(1024 * 1024).strictUtf8() })
                val records = manifest.getJSONArray("records")
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    if (!record.isNull("globalLocalId")) continue // Export original shared payload once through the library snapshot.
                    if (record.isNull("localPath") || (!record.isNull("url") && !record.getString("localPath").startsWith(".overrides/"))) continue
                    val normalized = privateResource(it.directory, record.getString("localPath"))
                    val path = it.directory.toPath().relativize(normalized.toPath()).joinToString("/")
                    val file = privateResource(it.directory, record.getString("file"))
                    val previous = local.put(path, file)
                    require(previous == null || sha256(previous) == sha256(file)) { "Local resource snapshot has conflicting content" }
                }
            }
            BackupProfileSource(entry.backupId ?: entry.summary.id, entry.summary, source, local,
                entry.sourceMetadata ?: ProfileSourceMetadata.plain(source))
        }
        action(entries, index.entries.find { it.summary.id == index.selected }?.let { it.backupId ?: it.summary.id })
    }

    internal suspend fun <T> withBackupLibrarySnapshot(sources: List<LibraryRuleSource>,
        action: suspend (List<CachedLibraryRule>) -> T): T =
        resources?.withLibraryRulesSnapshot(sources, action) ?: action(emptyList())

    internal fun localizeBackupLibraryPayloads(source: String, payloads: List<CachedLibraryRule>, geoUrls: Map<String, String>): Map<String, File> {
        check(resources != null || payloads.isEmpty()) { "Resource manager is required to restore library payloads" }
        return resources?.localizeLibraryPayloads(source, payloads, geoUrls).orEmpty()
    }

    internal suspend fun <T> withBackupLibraryRestore(action: suspend (LibraryCacheRestore?) -> T): T {
        globalResources?.invalidateRemoteRefreshes()
        return resources?.withLibraryRestoreTransaction(invalidateInFlight = true) { action(it) } ?: action(null)
    }

    internal suspend fun restoreBackup(
        incoming: List<BackupProfileInput>, selectedId: String?, replaceExisting: Boolean,
        restoreSelection: Boolean, applySettings: suspend () -> Unit,
    ): Map<String, String> = restoreBackupMapped(incoming, selectedId, replaceExisting, restoreSelection) { _, _ -> applySettings() }

    internal suspend fun restoreBackupMapped(
        incoming: List<BackupProfileInput>,
        selectedId: String?,
        replaceExisting: Boolean,
        restoreSelection: Boolean,
        extraRestorePaths: List<String> = emptyList(),
        applySettings: suspend (Map<String, String>, Set<String>) -> Unit,
    ): Map<String, String> = privateDomainTransaction(storage.root.parentFile!!) {
        require(incoming.size <= 1000 && incoming.map { it.id }.distinct().size == incoming.size) { "Invalid backup profile count" }
        require(selectedId == null || incoming.any { it.id == selectedId }) { "Invalid backup selection" }
        resourceEditEpoch.incrementAndGet()
        val before = storage.transaction { load() }
        val identities = incoming.map { it.id }.toSet()
        val kept = if (replaceExisting) emptyList() else before.entries.filterNot { (it.backupId ?: it.summary.id) in identities }
        require(kept.size + incoming.size <= 1000) { "Profile limit reached" }
        val candidates = mutableListOf<Pair<String, ResourceBundle>>()
        val restored = mutableListOf<Entry>()
        val mapping = incoming.associate { it.id to UUID.randomUUID().toString() }
        val liveIds = kept.map { it.summary.id }.toSet() + mapping.values
        try {
            atomicDomainRestore(storage.root.parentFile!!, extraRestorePaths) {
                applySettings(mapping, liveIds)
                for (input in incoming) {
                    require(UUID.fromString(input.id).toString() == input.id) { "Invalid backup profile id" }
                    checkName(input.name)
                    input.url?.let(SubscriptionDownloader::parseUrl)
                    require(input.updatedAt >= 0) { "Invalid profile timestamp" }
                    require(input.updateIntervalMillis > 0 && (input.lastSuccessfulUpdateAt == null || input.lastSuccessfulUpdateAt >= 0)) { "Invalid profile maintenance metadata" }
                    require(input.sourceIdentity == null || (input.sourceIdentity.isNotBlank() && input.sourceIdentity.length <= 16384)) { "Invalid source identity" }
                    require(listOfNotNull(input.subscriptionUserInfo, input.contentDisposition).all { it.length <= 8192 }) { "Profile response header too large" }
                    input.sourceMetadata?.verify(input.source)
                val id = mapping.getValue(input.id)
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
                        subscriptionUserInfo = input.subscriptionUserInfo, contentDisposition = input.contentDisposition, custom = input.custom,
                        currentProxyGroup = input.currentProxyGroup, unfoldedProxyGroups = input.unfoldedProxyGroups.toSet()), writeRevision(input.source),
                        bundle = bundle?.generation, nextRefreshAt = bundle?.nextRefreshAt, backupId = input.id,
                        sourceMetadata = input.sourceMetadata ?: ProfileSourceMetadata.plain(input.source))
                }
                val previousIdentity = before.entries.find { it.summary.id == before.selected }?.let { it.backupId ?: it.summary.id }
                val selected = when {
                    restoreSelection -> mapping[selectedId]
                    !replaceExisting && kept.any { it.summary.id == before.selected } -> before.selected
                    !replaceExisting && previousIdentity in mapping -> mapping[previousIdentity]
                    else -> mapping[selectedId] ?: kept.firstOrNull()?.summary?.id ?: restored.firstOrNull()?.summary?.id
                }
                storage.transaction { save(Index(kept + restored, selected), collect = false) }
            }
            candidates.forEach { (id, bundle) -> resources?.committed(id, bundle.generation) }
            runCatching { storage.transaction { collectGarbage(load()) } }
            mapping
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                candidates.forEach { (id, bundle) ->
                    try { resources?.discard(id, bundle.generation) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup); invalidateRestoreRollback(failure, cleanup) }
                }
                try { storage.transaction { collectGarbage(load()) } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup); invalidateRestoreRollback(failure, cleanup) }
            }
            throw failure
        }
    }

    suspend fun readConfig(id: String): String = storage.transaction {
        readSource(load().entry(id))
    }

    suspend fun readPreviousConfig(id: String): String? = storage.transaction {
        load().entry(id).previous?.let { readRevision(it) }
    }

    suspend fun readLastUsableConfig(id: String): String? = storage.transaction {
        load().entry(id).lastUsable?.let { readRevision(it) }
    }

    suspend fun markUsable(id: String, appliedYaml: String) = storage.transaction {
        check(resources == null) { "Use markUsable(PreparedProfile) to confirm the exact resource generation" }
        val index = load()
        val old = index.entry(id)
        if (readSource(old) != appliedYaml) throw ProfileConflictException(id)
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

    private suspend fun prepareBundle(id: String, source: String, previous: ResourceBundle? = null,
        refreshKeys: Set<String>? = null): ResourceBundle? {
        val transform = sourcePolicy.forData(id, source)
        val manager = resources
        if (manager == null) { validate(transform.compiledYaml); return null }
        val bundle = manager.prepare(id, transform.compiledYaml, previous, refreshKeys, source, transform.policyFingerprint, transform.geoUrls)
        if (!transform.isCurrent()) {
            manager.discard(id, bundle.generation)
            throw CancellationException("Profile policy changed during preparation")
        }
        return bundle
    }

    private fun readRevision(name: String): String = storage.file(name).inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8PreservingBom() }
    private fun readSource(entry: Entry): String = readRevision(entry.current).also { entry.sourceMetadata?.verify(it) }

    private fun writeRevision(yaml: String): String {
        val filename = "${UUID.randomUUID()}.yaml"
        storage.write(filename, yaml)
        return filename
    }

    private fun load(): Index {
        if (!storage.file("index.json").exists()) return Index(emptyList(), null)
        val json = JSONObject(storage.read("index.json", MAX_PROFILE_INDEX_BYTES))
        require(json.getInt("schema") == 1) { "Unsupported profile index version" }
        val records = json.getJSONArray("profiles")
        val entries = (0 until records.length()).map { i ->
            val row = records.getJSONObject(i)
            val id = row.getString("id")
            val proxyGroupUi = row.readProxyGroupUi()
            require(UUID.fromString(id).toString() == id) { "Invalid profile id" }
            fun revision(key: String): String? = if (row.isNull(key)) null else {
                row.getString(key).also { require(it.matches(REVISION)) { "Invalid revision path" } }
            }
            Entry(
                ProfileSummary(id, row.getString("name"), nullableString(row, "url"), row.getLong("updatedAt"),
                    autoUpdate = row.optBoolean("autoUpdate", true), updateIntervalMillis = row.optLong("updateIntervalMillis", 86_400_000L).also { require(it > 0) },
                    lastSuccessfulUpdateAt = if (row.isNull("lastSuccessfulUpdateAt")) null else row.getLong("lastSuccessfulUpdateAt").also { require(it >= 0) },
                    sourceIdentity = row.optString("sourceIdentity", id).also { require(it.isNotBlank() && it.length <= 16384) },
                    subscriptionUserInfo = nullableString(row, "subscriptionUserInfo"), contentDisposition = nullableString(row, "contentDisposition"),
                    custom = if (row.has("custom")) row.get("custom").also { require(it is Boolean) { "Invalid profile custom flag" } } as Boolean else false,
                    currentProxyGroup = proxyGroupUi.current, unfoldedProxyGroups = proxyGroupUi.unfolded),
                revision("current") ?: throw IOException("Profile has no configuration"),
                revision("previous"),
                revision("lastUsable"),
                nullableString(row, "bundle")?.also(::checkGeneration),
                nullableString(row, "previousBundle")?.also(::checkGeneration),
                nullableString(row, "lastUsableBundle")?.also(::checkGeneration),
                if (row.isNull("nextRefreshAt")) null else row.getLong("nextRefreshAt"),
                nullableString(row, "backupId")?.also(::checkGeneration),
                if (row.isNull("sourceMetadata")) null else ProfileSourceMetadata.parse(row.optJSONObject("sourceMetadata")
                    ?: throw IllegalArgumentException("Invalid private profile source metadata")),
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
                .put("custom", e.summary.custom)
                .putProxyGroupUi(e.summary.currentProxyGroup, e.summary.unfoldedProxyGroups)
                .put("current", e.current).put("previous", e.previous ?: JSONObject.NULL)
                .put("lastUsable", e.lastUsable ?: JSONObject.NULL)
                .put("bundle", e.bundle ?: JSONObject.NULL).put("previousBundle", e.previousBundle ?: JSONObject.NULL)
                .put("lastUsableBundle", e.lastUsableBundle ?: JSONObject.NULL)
                .put("nextRefreshAt", e.nextRefreshAt ?: JSONObject.NULL).put("backupId", e.backupId ?: JSONObject.NULL)
                .put("sourceMetadata", e.sourceMetadata?.json() ?: JSONObject.NULL))
        }
        val encoded = JSONObject().put("schema", 1).put("profiles", array)
            .put("selected", index.selected ?: JSONObject.NULL).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_INDEX_BYTES) { "Profile metadata limit reached" }
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
        val sourceMetadata: ProfileSourceMetadata? = null,
    )

    private data class Index(val entries: List<Entry>, val selected: String?) {
        fun entry(id: String): Entry = entries.find { it.summary.id == id }
            ?: throw IOException("Profile does not exist")
    }

    companion object {
        private val plainDecoder = AgeProfileSourceDecoder(AgeDecryptTransport { _, _ -> throw AgeSourceException("decrypt_failed") })
        private val resourceEditEpochs = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
        private val liveProfiles = ConcurrentHashMap<String, MutableMap<String, PreparedProfile>>()
        private val REVISION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.yaml")
        private fun nullableString(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.getString(key)
        private fun checkGeneration(value: String) { require(UUID.fromString(value).toString() == value) }
    }
}
