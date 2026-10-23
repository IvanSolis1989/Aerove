package dev.aerove.lite.profilebuilder

data class BuilderResourceRestoreMerge(
    val resources: BuilderResources,
    val proxyNames: Map<String, String>, val providerNames: Map<String, String>,
    val filterNames: Map<String, String>, val iconSetNames: Map<String, String>,
    val proxyIds: Map<String, String>, val providerIds: Map<String, String>,
    val filterIds: Map<String, String>, val iconSetIds: Map<String, String>,
    private val imported: BuilderResources,
) {
    fun rewriteSource(sourceYaml: String): String {
        val previous = BuilderResourceBindings.parse(sourceYaml)
        val stable = imported.migrateBindings(previous)
        fun mapped(values: Map<String, String>, id: String): String = values[id] ?: error("Missing imported resource ID: $id")
        val changed = stable.copy(
            proxies = stable.proxies.mapValues { (_, id) -> mapped(proxyIds, id) },
            ruleProviders = stable.ruleProviders.mapValues { (_, id) -> mapped(providerIds, id) },
            filters = stable.filters.mapValues { (_, binding) -> binding.copy(labels = binding.labels.map { mapped(filterIds, it) }) },
            icons = stable.icons.mapValues { (_, binding) -> binding.copy(set = mapped(iconSetIds, binding.set)) },
        )
        return if (changed == previous) sourceYaml else BuilderResourceBindings.replaceHeader(sourceYaml, changed)
    }
}

fun BuilderResources.mergeForRestore(imported: BuilderResources): BuilderResourceRestoreMerge {
    validate(); imported.validate()
    val used = enumerate().map { it.id }.toMutableSet()
    data class Merged<T>(val items: List<T>, val names: Map<String, String>, val ids: Map<String, String>)
    fun <T> merge(existing: List<T>, incoming: List<T>, duplicateNames: Boolean, name: (T) -> String, id: (T) -> String,
        change: (T, String, String) -> T): Merged<T> {
        val merged = existing.toMutableList()
        val names = linkedMapOf<String, String>(); val ids = linkedMapOf<String, String>()
        for (item in incoming) {
            val originalName = name(item); val originalId = id(item)
            val sameId = existing.singleOrNull { id(it) == originalId }
            if (sameId == item) { names[originalName] = originalName; ids[originalId] = originalId; continue }
            val sameName = if (duplicateNames) null else merged.singleOrNull { name(it) == originalName }
            if (sameName != null && change(sameName, originalName, originalId) == item) {
                names[originalName] = originalName; ids[originalId] = id(sameName); continue
            }
            var chosenName = originalName
            if (sameName != null) {
                var number = 2
                while (merged.any { name(it) == "$originalName $number" } || incoming.any { name(it) == "$originalName $number" }) number++
                chosenName = "$originalName $number"
            }
            var chosenId = originalId
            if (chosenId in used) do { chosenId = newBuilderResourceId() } while (chosenId in used)
            used += chosenId
            names[originalName] = chosenName; ids[originalId] = chosenId
            merged += change(item, chosenName, chosenId)
        }
        return Merged(merged, names, ids)
    }
    // Plan all incoming names before comparing payloads: a dependency collision can make an
    // otherwise equal dependent node distinct. Repeat until this propagates through the chain.
    val nodeNames = imported.proxies.associate { it.name to it.name }.toMutableMap()
    fun projectedNode(item: BuilderEntry, name: String = nodeNames.getValue(item.name)): BuilderEntry =
        item.copy(name = name, fields = projectBuilderProxyReferences(item.fields) { nodeNames[it] ?: it } + ("name" to name))
    var changed: Boolean
    do {
        changed = false
        imported.proxies.forEach { item ->
            if (nodeNames.getValue(item.name) == item.name) {
                val existing = proxies.singleOrNull { it.name == item.name }
                if (existing != null && existing.copy(id = item.id) != projectedNode(item)) {
                    var number = 2
                    while (proxies.any { it.name == "${item.name} $number" } || "${item.name} $number" in nodeNames.values) number++
                    nodeNames[item.name] = "${item.name} $number"
                    changed = true
                }
            }
        }
    } while (changed)
    val nodeItems = proxies.toMutableList()
    val nodeIds = linkedMapOf<String, String>()
    imported.proxies.forEach { item ->
        val projected = projectedNode(item)
        val originalId = requireNotNull(item.id)
        val sameId = proxies.singleOrNull { it.id == originalId }
        val sameName = proxies.singleOrNull { it.name == projected.name }
        val reusable = sameId?.takeIf { it == projected } ?: sameName?.takeIf { it.copy(id = originalId) == projected }
        if (reusable != null) nodeIds[originalId] = requireNotNull(reusable.id)
        else {
            var chosenId = originalId
            if (chosenId in used) do { chosenId = newBuilderResourceId() } while (chosenId in used)
            used += chosenId
            nodeIds[originalId] = chosenId
            nodeItems += projected.copy(id = chosenId)
        }
    }
    val nodes = Merged(nodeItems, nodeNames, nodeIds)
    val providers = merge(ruleProviders, imported.ruleProviders, false, BuilderEntry::name, { requireNotNull(it.id) }) { item, name, id -> item.copy(name = name, id = id) }
    val newFilters = merge(filters, imported.filters, false, BuilderFilter::label, BuilderFilter::id) { item, name, id -> item.copy(label = name, id = id) }
    val sets = merge(iconSets, imported.iconSets, true, BuilderIconSet::name, BuilderIconSet::id) { item, name, id -> item.copy(name = name, id = id) }
    val result = BuilderResources(nodes.items, providers.items, newFilters.items, sets.items).also { it.validate() }
    return BuilderResourceRestoreMerge(result, nodes.names, providers.names, newFilters.names, sets.names,
        nodes.ids, providers.ids, newFilters.ids, sets.ids, imported)
}

fun BuilderResources.referencedBy(sources: Iterable<String>): BuilderResources {
    val bindings = sources.map { migrateBindings(BuilderResourceBindings.parse(it)) }
    val nodes = bindings.flatMap { it.proxies.values }.toSet()
    val providers = bindings.flatMap { it.ruleProviders.values }.toSet()
    val labels = bindings.flatMap { it.filters.values.flatMap(BuilderFilterBinding::labels) }.toSet()
    val sets = bindings.flatMap { it.icons.values.map(BuilderIconBinding::set) }.toSet()
    require(nodes.all { id -> proxies.any { it.id == id } }) { "Missing bound proxy resource ID" }
    require(providers.all { id -> ruleProviders.any { it.id == id } }) { "Missing bound rule provider resource ID" }
    require(labels.all { id -> filters.any { it.id == id } }) { "Missing bound filter resource ID" }
    require(sets.all { id -> iconSets.any { it.id == id } }) { "Missing bound icon set resource ID" }
    return BuilderResources(proxies.filter { it.id in nodes }, ruleProviders.filter { it.id in providers },
        filters.filter { it.id in labels }, iconSets.filter { it.id in sets })
}
