package dev.aerove.lite.resources

import dev.aerove.lite.data.ResourceBundleManager
import dev.aerove.lite.profilebuilder.BuilderResourceKind
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.BuilderResourceSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

enum class ResourceManagementRemoteKind { IconSet, RuleSet, Script }
data class ResourceManagementRemoteResource(val id: String, val kind: ResourceManagementRemoteKind, val name: String,
    val url: String, val bytes: Long?, val updatedAt: Long?, val count: Int?)
data class ResourceManagementLibrarySnapshot(val builder: BuilderResourceSnapshot, val entries: List<ResourceManagementRemoteResource>)

/** Enumeration reuses the builder's unique definitions; only validated rule payloads belong to the bundle manager. */
class ResourceManagementLibraryRepository(private val library: BuilderResourceLibrary, private val manager: ResourceBundleManager,
    private val globalRules: dev.aerove.lite.data.GlobalRulePayloadRepository? = null) {
    private val updates = ConcurrentHashMap<String, CompletableDeferred<ResourceManagementLibrarySnapshot>>()
    suspend fun snapshot(): ResourceManagementLibrarySnapshot {
        val source = globalRules?.prepareLibrary() ?: library.snapshot()
        val entries = source.resources.enumerate().mapNotNull { entry ->
            val url = entry.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            when (entry.kind) {
                BuilderResourceKind.IconSet -> ResourceManagementRemoteResource("icon:${entry.id}", ResourceManagementRemoteKind.IconSet,
                    entry.name, url, entry.sourceBytes, entry.updatedAt, entry.count)
                BuilderResourceKind.RuleProvider -> {
                    val fields = source.resources.ruleProviders.single { it.id == entry.id }.fields
                    val saved = manager.libraryRule(entry.name, url, fields["behavior"] as? String ?: "classical", fields["format"] as? String ?: "yaml")
                    ResourceManagementRemoteResource("rule:${entry.id}", ResourceManagementRemoteKind.RuleSet,
                        entry.name, url, saved?.bytes, saved?.updatedAt, saved?.count)
                }
                else -> null
            }
        }
        return ResourceManagementLibrarySnapshot(source, entries)
    }

    suspend fun sync(id: String): ResourceManagementLibrarySnapshot {
        val result = CompletableDeferred<ResourceManagementLibrarySnapshot>()
        updates.putIfAbsent(id, result)?.let { return it.await() }
        try {
            return syncOwned(id).also { result.complete(it) }
        } catch (failure: Throwable) { result.completeExceptionally(failure); throw failure }
        finally { updates.remove(id, result) }
    }

    private suspend fun syncOwned(id: String): ResourceManagementLibrarySnapshot {
        val before = globalRules?.prepareLibrary() ?: library.snapshot()
        val entry = before.resources.enumerate().single {
            when (it.kind) { BuilderResourceKind.IconSet -> "icon:${it.id}"; BuilderResourceKind.RuleProvider -> "rule:${it.id}"; else -> "" } == id
        }
        when (entry.kind) {
            BuilderResourceKind.IconSet -> library.syncIconSet(before, entry.id)
            BuilderResourceKind.RuleProvider -> {
                val fields = before.resources.ruleProviders.single { it.id == entry.id }.fields
                if (globalRules != null) globalRules.sync(before, entry.id)
                else {
                    require(!(fields["path"] as? String).orEmpty().startsWith(".library/")) { "Global rule payload facade is required" }
                    manager.syncLibraryRule(entry.name, checkNotNull(entry.url), fields["behavior"] as? String ?: "classical", fields["format"] as? String ?: "yaml")
                    require(library.snapshot().encoded == before.encoded) { "Rule resource definition changed during download" }
                }
            }
            else -> error("Resource has no remote payload")
        }
        return snapshot()
    }
}
