package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.PrivateStorage
import dev.aerove.lite.data.SubscriptionDownloader
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import dev.aerove.lite.regexp2.ExactFilterRuntime

data class BuilderFilter(val label: String, val regex: String, val id: String = newBuilderResourceId())
data class BuilderIcon(val name: String, val url: String)
data class BuilderIconSet(val name: String, val url: String = "", val icons: List<BuilderIcon>, val updatedAt: Long? = null, val sourceBytes: Long? = null, val id: String = newBuilderResourceId())
enum class BuilderResourceKind { Proxy, RuleProvider, Filter, IconSet }
data class BuilderResourceItem(val kind: BuilderResourceKind, val name: String, val url: String?,
    val count: Int?, val updatedAt: Long?, val sourceBytes: Long?, val id: String = "")

class BuilderResources(
    proxies: List<BuilderEntry> = emptyList(),
    ruleProviders: List<BuilderEntry> = emptyList(),
    filters: List<BuilderFilter> = emptyList(),
    iconSets: List<BuilderIconSet> = emptyList(),
) {
    val proxies = proxies.map { it.copy(fields = it.fields + ("name" to it.name), id = it.id ?: newBuilderResourceId()) }
    val ruleProviders = ruleProviders.map { it.copy(id = it.id ?: newBuilderResourceId()) }
    val filters = filters.toList()
    val iconSets = iconSets.toList()
    fun copy(proxies: List<BuilderEntry> = this.proxies, ruleProviders: List<BuilderEntry> = this.ruleProviders,
        filters: List<BuilderFilter> = this.filters, iconSets: List<BuilderIconSet> = this.iconSets): BuilderResources =
        BuilderResources(proxies, ruleProviders, filters, iconSets)
    override fun equals(other: Any?): Boolean = other is BuilderResources && proxies == other.proxies && ruleProviders == other.ruleProviders && filters == other.filters && iconSets == other.iconSets
    override fun hashCode(): Int = listOf(proxies, ruleProviders, filters, iconSets).hashCode()
    override fun toString(): String = "BuilderResources(proxies=${proxies.size},ruleProviders=${ruleProviders.size},filters=${filters.size},iconSets=${iconSets.size})"
    fun enumerate(): List<BuilderResourceItem> =
        proxies.map { BuilderResourceItem(BuilderResourceKind.Proxy, it.name, null, 1, null, null, requireNotNull(it.id)) } +
        ruleProviders.map { BuilderResourceItem(BuilderResourceKind.RuleProvider, it.name, it.fields["url"] as? String, null, null, null, requireNotNull(it.id)) } +
        filters.map { BuilderResourceItem(BuilderResourceKind.Filter, it.label, null, null, null, null, it.id) } +
        iconSets.map { BuilderResourceItem(BuilderResourceKind.IconSet, it.name, it.url.takeIf(String::isNotEmpty), it.icons.size, it.updatedAt, it.sourceBytes, it.id) }
    /** Call before managed resource preparation/native validation at every actual start/reload. */
    suspend fun resolve(sourceYaml: String): String {
        if (BuilderResourceBindings.parse(sourceYaml).isEmpty()) return sourceYaml
        var document = ProfileBuilderDocument.parse(sourceYaml)
        val bindings = migrateBindings(document.bindings)
        val localProxyNames = bindings.proxies.entries.groupBy({ it.value }, { it.key })
        fun localTarget(globalName: String): String {
            val resource = proxies.singleOrNull { it.name == globalName } ?: return globalName
            val locals = localProxyNames[resource.id].orEmpty()
            require(locals.size <= 1) { "Ambiguous local binding for proxy resource: $globalName" }
            return locals.singleOrNull() ?: globalName
        }
        bindings.proxies.forEach { (localName, resourceName) ->
            val entry = proxies.singleOrNull { it.id == resourceName }
                ?: error("$.proxies.$localName: Missing resource $resourceName")
            val index = document.entries(BuilderSection.Proxies).indexOfFirst { it.name == localName }
            require(index >= 0) { "$.proxies.$localName: Missing linked node" }
            document = document.put(BuilderSection.Proxies, index, localName, projectBuilderProxyReferences(entry.fields, ::localTarget))
        }
        bindings.ruleProviders.forEach { (localName, resourceName) ->
            val entry = ruleProviders.singleOrNull { it.id == resourceName }
                ?: error("$.rule-providers.$localName: Missing resource $resourceName")
            val index = document.entries(BuilderSection.RuleProviders).indexOfFirst { it.name == localName }
            require(index >= 0) { "$.rule-providers.$localName: Missing linked provider" }
            document = document.put(BuilderSection.RuleProviders, index, localName, entry.fields)
        }
        bindings.filters.forEach { (group, binding) ->
            val index = document.entries(BuilderSection.Groups).indexOfFirst { it.name == group }
            require(index >= 0) { "$.proxy-groups.$group: Missing filter group" }
            val matches = binding.labels.flatMap { label ->
                val filter = filters.singleOrNull { it.id == label } ?: error("$.proxy-groups.$group: Missing filter $label")
                matching(filter, document).filter { it != group }
            }
            val fields = document.entries(BuilderSection.Groups)[index].fields
            document = document.put(BuilderSection.Groups, index, group, fields + ("proxies" to (binding.manualMembers + matches).distinct()))
        }
        bindings.icons.forEach { (group, binding) ->
            val set = iconSets.singleOrNull { it.id == binding.set } ?: error("$.proxy-groups.$group.icon: Missing icon set ${binding.set}")
            val icon = set.icons.singleOrNull { it.url == binding.url } ?: error("$.proxy-groups.$group.icon: Missing icon ${binding.url}")
            document = applyIcon(icon, document, group)
        }
        val issues = document.issues()
        require(issues.isEmpty()) { issues.joinToString("\n") { "${it.path}: ${it.reason}" } }
        // Core sees resolved native YAML only; client references remain in the saved source revision.
        return document.withBindings(BuilderResourceBindings()).yaml()
    }

    fun bindProxy(id: String, document: ProfileBuilderDocument): ProfileBuilderDocument {
        val entry = proxy(id)
        val bindings = migrateBindings(document.bindings)
        return applyProxy(id, document).withBindings(bindings.copy(proxies = bindings.proxies + (entry.name to id)))
    }
    fun bindRuleProvider(id: String, document: ProfileBuilderDocument): ProfileBuilderDocument {
        val entry = ruleProvider(id)
        val bindings = migrateBindings(document.bindings)
        return applyRuleProvider(id, document).withBindings(bindings.copy(ruleProviders = bindings.ruleProviders + (entry.name to id)))
    }

    fun validate() {
        val ids = proxies.map { requireNotNull(it.id) } + ruleProviders.map { requireNotNull(it.id) } + filters.map { it.id } + iconSets.map { it.id }
        require(ids.all(::validBuilderResourceId) && ids.distinct().size == ids.size) { "Duplicate or invalid resource ID" }
        listOf(proxies.map { it.name }, ruleProviders.map { it.name }, filters.map { it.label }, iconSets.map { it.name }).forEachIndexed { index, names ->
            require(names.all { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) }) { "Invalid resource name" }
            require(index == 3 || names.distinct().size == names.size) { "Duplicate resource name" }
        }
        var nodes = ProfileBuilderDocument.empty()
        proxies.forEach { nodes = nodes.put(BuilderSection.Proxies, null, it.name, it.fields) }
        filters.forEach { filter ->
            require(filter.regex.isNotBlank()) { "Filter expression is required" }
        }
        iconSets.forEach { set ->
            require(set.url.isEmpty() || isHttp(set.url)) { "Invalid icon set URL" }
            require(set.icons.isNotEmpty() && set.icons.size <= 10000) { "Invalid icon set size" }
            require(set.icons.all { it.name.isNotBlank() && isHttp(it.url) }) { "Invalid icon" }
            require(set.icons.map { it.url }.distinct().size == set.icons.size) { "Duplicate icon URL" }
        }
    }

    suspend fun matching(filter: BuilderFilter, document: ProfileBuilderDocument): List<String> {
        val nodes = document.entries(BuilderSection.Proxies).map { it.name }
        return ExactFilterRuntime.indices(listOf(filter.regex), emptyList(), nodes).map(nodes::get)
    }

    fun applyProxy(id: String, document: ProfileBuilderDocument): ProfileBuilderDocument {
        val entry = proxy(id)
        return document.put(BuilderSection.Proxies, document.entries(BuilderSection.Proxies).indexOfFirst { it.name == entry.name }.takeIf { it >= 0 }, entry.name, entry.fields)
    }

    fun applyRuleProvider(id: String, document: ProfileBuilderDocument): ProfileBuilderDocument {
        val entry = ruleProvider(id)
        return document.put(BuilderSection.RuleProviders, document.entries(BuilderSection.RuleProviders).indexOfFirst { it.name == entry.name }.takeIf { it >= 0 }, entry.name, entry.fields)
    }

    suspend fun applyFilter(filter: BuilderFilter, document: ProfileBuilderDocument, group: String): ProfileBuilderDocument {
        val index = document.entries(BuilderSection.Groups).indexOfFirst { it.name == group }
        require(index >= 0) { "Unknown group: $group" }
        val entry = document.entries(BuilderSection.Groups)[index]
        val members = matching(filter, document).filter { it != group }
        val previous = (entry.fields["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        return document.put(BuilderSection.Groups, index, group, entry.fields + ("proxies" to (previous + members).distinct()))
    }

    fun applyIcon(icon: BuilderIcon, document: ProfileBuilderDocument, group: String): ProfileBuilderDocument {
        val index = document.entries(BuilderSection.Groups).indexOfFirst { it.name == group }
        require(index >= 0) { "Unknown group: $group" }
        return document.put(BuilderSection.Groups, index, group, document.entries(BuilderSection.Groups)[index].fields + ("icon" to icon.url))
    }

    fun yaml(): String {
        validate()
        return ConfigYaml.dump(linkedMapOf(
            "schema" to 2,
            "proxies" to proxies.map { mapOf("id" to it.id, "name" to it.name, "fields" to it.fields.filterKeys { key -> key != "name" }) },
            "rule-providers" to ruleProviders.map { mapOf("id" to it.id, "name" to it.name, "fields" to it.fields) },
            "filters" to filters.map { mapOf("id" to it.id, "label" to it.label, "regex" to it.regex) },
            "icon-sets" to iconSets.map { set -> mapOf("id" to set.id, "name" to set.name, "url" to set.url,
                "updated-at" to set.updatedAt, "source-bytes" to set.sourceBytes, "icons" to set.icons.map { mapOf("name" to it.name, "url" to it.url) }) },
        ))
    }

    companion object {
        fun parse(yaml: String): BuilderResources {
            val root = ConfigYaml.parse(yaml)
            require(root.keys.all { it in setOf("schema", "proxies", "rule-providers", "filters", "icon-sets") }) { "Unknown resource library field" }
            val version = (root["schema"] as? Number)?.toInt()
            require(version in 1..2) { "Unknown resource library version" }
            require(root["filters"] is List<*> && root["icon-sets"] is List<*>) { "Invalid resource collection" }
            fun mapping(value: Any?): Map<*, *> = value as? Map<*, *> ?: error("Invalid resource entry")
            fun text(map: Map<*, *>, key: String): String = map[key] as? String ?: error("Invalid resource $key")
            fun id(map: Map<*, *>, kind: BuilderResourceKind, name: String): String = if (version == 1) legacyBuilderResourceId(kind, name)
                else text(map, "id").also { require(validBuilderResourceId(it)) { "Invalid resource ID" } }
            fun entries(key: String, kind: BuilderResourceKind): List<BuilderEntry> {
                if (version == 1) {
                    val graph = ProfileBuilderDocument.parse(ConfigYaml.dump(root.filterKeys { it == key }))
                    val section = if (kind == BuilderResourceKind.Proxy) BuilderSection.Proxies else BuilderSection.RuleProviders
                    return graph.entries(section).map { it.copy(id = legacyBuilderResourceId(kind, it.name)) }
                }
                val values = root[key] as? List<*> ?: error("Invalid resource collection: $key")
                return values.map { value -> val item = mapping(value); val name = text(item, "name")
                    val fields = mapping(item["fields"]).entries.associate { (key, field) -> require(key is String); key to field }
                    BuilderEntry(name, fields, id(item, kind, name))
                }
            }
            val filters = (root["filters"] as List<*>).map { value -> val item = mapping(value); val name = text(item, "label")
                BuilderFilter(name, text(item, "regex"), id(item, BuilderResourceKind.Filter, name)) }
            val sets = (root["icon-sets"] as List<*>).map { value ->
                val item = mapping(value); val name = text(item, "name")
                val icons = (item["icons"] as? List<*>)?.map { icon -> mapping(icon).let { BuilderIcon(text(it, "name"), text(it, "url")) } } ?: error("Missing icons")
                BuilderIconSet(name, text(item, "url"), icons, (item["updated-at"] as? Number)?.toLong(), (item["source-bytes"] as? Number)?.toLong(), id(item, BuilderResourceKind.IconSet, name))
            }
            if (version == 1) require(sets.map { it.name }.distinct().size == sets.size) { "Ambiguous legacy icon set name" }
            return BuilderResources(entries("proxies", BuilderResourceKind.Proxy), entries("rule-providers", BuilderResourceKind.RuleProvider), filters, sets).also { it.validate() }
        }

        /** Same gallery contract as Standard parseIconSet: invalid and duplicate URLs are skipped. */
        fun parseIconSet(content: String, url: String = "", fallbackName: String = "", now: Long = System.currentTimeMillis()): BuilderIconSet {
            val root = JSONObject(content)
            val array = root.getJSONArray("icons")
            require(array.length() <= 10000) { "Icon set is too large" }
            val seen = mutableSetOf<String>()
            val icons = (0 until array.length()).mapNotNull { index ->
                val icon = array.optJSONObject(index) ?: return@mapNotNull null
                val address = icon.opt("url") as? String ?: return@mapNotNull null
                if (!isHttp(address) || !seen.add(address)) return@mapNotNull null
                val name = (icon.opt("name") as? String)?.trim().orEmpty().ifEmpty {
                    URI(address).path.substringAfterLast('/').ifEmpty { URI(address).host }
                }
                BuilderIcon(name, address)
            }
            require(icons.isNotEmpty()) { "Icon set has no valid icons" }
            val name = (root.opt("name") as? String)?.trim().orEmpty().ifEmpty { fallbackName }
            require(name.isNotBlank()) { "Icon set name is required" }
            return BuilderIconSet(name, url, icons, now, content.toByteArray(Charsets.UTF_8).size.toLong())
        }

        private fun isHttp(value: String): Boolean = runCatching {
            URI(value).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() }
        }.getOrDefault(false)
    }
}

data class BuilderResourceSnapshot(val resources: BuilderResources, val encoded: String?) {
    val schemaVersion: Int get() = encoded?.let { (ConfigYaml.parse(it)["schema"] as? Number)?.toInt() ?: error("Invalid resource schema") } ?: 2
}

class BuilderRemoteRefreshTicket internal constructor(internal val owner: BuilderResourceLibrary,
    internal val epoch: Long, internal val revision: Long, internal val id: String)

/** Shares the existing settings domain lock/backup directory; never overwrites settings.json. */
class BuilderResourceLibrary(privateDirectory: File, private val downloader: SubscriptionDownloader = SubscriptionDownloader()) {
    private val storage = PrivateStorage(File(privateDirectory, "settings"))
    private val refreshEpoch = AtomicLong()
    private val entryRevisions = ConcurrentHashMap<String, Long>()

    suspend fun beginRemoteRefresh(expected: BuilderResourceSnapshot, id: String, kind: BuilderResourceKind): BuilderRemoteRefreshTicket = storage.transaction {
        val current = read()
        require(expected.schemaVersion == 2 && current.schemaVersion == 2) { "Resource identity migration is required before refresh" }
        fun target(resources: BuilderResources): Any = when (kind) {
            BuilderResourceKind.IconSet -> resources.iconSet(id)
            BuilderResourceKind.RuleProvider -> resources.ruleProvider(id)
            else -> error("Resource has no remote payload")
        }
        require(target(current.resources) == target(expected.resources)) { "Resource changed before download" }
        BuilderRemoteRefreshTicket(this, refreshEpoch.get(), entryRevisions[id] ?: 0L, id)
    }
    /** Call while holding the shared private domain for the final target compare/merge/commit. */
    fun requireRemoteRefreshCurrent(ticket: BuilderRemoteRefreshTicket) {
        require(ticket.owner === this && ticket.epoch == refreshEpoch.get() && ticket.revision == (entryRevisions[ticket.id] ?: 0L)) {
            "Resource refresh was superseded"
        }
    }
    suspend fun invalidateRemoteRefreshes() = storage.transaction { refreshEpoch.incrementAndGet(); Unit }
    private fun revised(before: BuilderResources, after: BuilderResources) {
        fun entries(resources: BuilderResources): Map<String, Any> =
            (resources.proxies.map { requireNotNull(it.id) to it } + resources.ruleProviders.map { requireNotNull(it.id) to it } +
                resources.filters.map { it.id to it } + resources.iconSets.map { it.id to it }).toMap()
        val old = entries(before); val next = entries(after)
        (old.keys + next.keys).filter { old[it] != next[it] }.forEach { id -> entryRevisions[id] = (entryRevisions[id] ?: 0L) + 1L }
    }
    suspend fun snapshot(): BuilderResourceSnapshot = storage.transaction { read() }

    suspend fun replace(expected: BuilderResourceSnapshot, resources: BuilderResources): BuilderResourceSnapshot {
        require(expected.schemaVersion == 2) { "Resource identity migration is required before editing" }
        resources.validate()
        if (resources.filters.isNotEmpty()) ExactFilterRuntime.indices(resources.filters.map { it.regex }, emptyList(), emptyList(), validateOnly = true)
        val encoded = withContext(Dispatchers.Default) { resources.yaml() }
        return storage.transaction {
            val current = read()
            require(current.encoded == expected.encoded) { "Resources changed; reload before saving" }
            storage.write(FILE, encoded)
            read().also { require(it.encoded == encoded) { "Resource readback failed" }; revised(current.resources, it.resources) }
        }
    }

    suspend fun commitIdentityMigration(expected: BuilderResourceSnapshot, plan: BuilderIdentityMigrationPlan): BuilderResourceSnapshot {
        require(plan.expectedLibraryEncoded == expected.encoded) { "Identity migration changed" }
        val encoded = plan.libraryYaml ?: return expected
        require(BuilderResources.parse(encoded) == expected.resources) { "Identity migration changed resource definitions" }
        return storage.transaction {
            require(read().encoded == expected.encoded) { "Resources changed during identity migration" }
            storage.write(FILE, encoded)
            read().also { require(it.encoded == encoded) { "Resource readback failed" } }
        }
    }

    suspend fun importIconSet(expected: BuilderResourceSnapshot, content: String, url: String = "", fallbackName: String = ""): BuilderResourceSnapshot {
        val set = withContext(Dispatchers.Default) { BuilderResources.parseIconSet(content, url, fallbackName) }
        return replace(expected, expected.resources.copy(iconSets = expected.resources.iconSets + set))
    }

    suspend fun syncIconSet(expected: BuilderResourceSnapshot, id: String): BuilderResourceSnapshot {
        val previous = expected.resources.iconSet(id)
        require(previous.url.isNotEmpty()) { "Icon set has no remote URL" }
        val ticket = beginRemoteRefresh(expected, id, BuilderResourceKind.IconSet)
        val content = downloader.download(previous.url)
        val set = withContext(Dispatchers.Default) { BuilderResources.parseIconSet(content, previous.url, previous.name).copy(name = previous.name, id = previous.id) }
        return storage.transaction {
            requireRemoteRefreshCurrent(ticket)
            val current = read()
            require(current.resources.iconSet(id) == previous) { "Icon set changed during download" }
            val resources = current.resources.copy(iconSets = current.resources.iconSets.map { if (it.id == id) set else it })
            val encoded = resources.yaml()
            storage.write(FILE, encoded)
            read().also { require(it.encoded == encoded) { "Resource readback failed" }; revised(current.resources, it.resources) }
        }
    }

    suspend fun editIconSet(expected: BuilderResourceSnapshot, id: String, name: String, url: String, refresh: Boolean = false): BuilderResourceSnapshot {
        val previous = expected.resources.iconSet(id)
        val changed = previous.copy(name = name, url = url)
        val replacement = if (refresh || previous.url != url) {
            require(url.isNotEmpty()) { "Icon set has no remote URL" }
            val content = downloader.download(url)
            withContext(Dispatchers.Default) { BuilderResources.parseIconSet(content, url, name).copy(name = name, id = id) }
        } else changed
        return replace(expected, expected.resources.copy(iconSets = expected.resources.iconSets.map { if (it.id == id) replacement else it }))
    }

    suspend fun importRemoteIconSet(expected: BuilderResourceSnapshot, url: String, fallbackName: String): BuilderResourceSnapshot {
        val address = url.trim()
        val existing = expected.resources.iconSets.firstOrNull { it.url == address }
        val requested = fallbackName.trim()
        if (existing != null) {
            val updated = if (requested.isEmpty()) existing else existing.copy(name = requested)
            val edited = expected.copy(resources = expected.resources.copy(iconSets = expected.resources.iconSets.map { if (it === existing) updated else it }))
            val content = downloader.download(updated.url)
            val set = withContext(Dispatchers.Default) { BuilderResources.parseIconSet(content, updated.url, updated.name).copy(name = updated.name, id = updated.id) }
            return replace(expected, edited.resources.copy(iconSets = edited.resources.iconSets.map { if (it.id == updated.id) set else it }))
        }
        val content = downloader.download(address)
        val fallback = requested.ifEmpty { URI(address).path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { URI(address).host } }
        val parsed = withContext(Dispatchers.Default) { BuilderResources.parseIconSet(content, address, fallback) }
        val imported = if (requested.isEmpty()) parsed else parsed.copy(name = requested)
        return replace(expected, expected.resources.copy(iconSets = expected.resources.iconSets + imported))
    }

    private fun read(): BuilderResourceSnapshot {
        val encoded = if (storage.file(FILE).exists()) storage.read(FILE) else null
        return BuilderResourceSnapshot(encoded?.let(BuilderResources::parse) ?: BuilderResources(), encoded)
    }

    companion object { private const val FILE = "builder-resources.yaml" }
}
