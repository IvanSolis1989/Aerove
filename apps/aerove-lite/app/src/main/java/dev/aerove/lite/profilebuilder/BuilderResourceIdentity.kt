package dev.aerove.lite.profilebuilder

import java.util.UUID

internal fun newBuilderResourceId(): String = UUID.randomUUID().toString()
internal fun legacyBuilderResourceId(kind: BuilderResourceKind, name: String): String =
    UUID.nameUUIDFromBytes(("AEROVE-builder-resource-v1:${kind.name}\u0000$name").toByteArray(Charsets.UTF_8)).toString()
internal fun validBuilderResourceId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

fun BuilderResources.proxy(id: String): BuilderEntry = proxies.singleOrNull { it.id == id } ?: error("Missing proxy resource ID: $id")
fun BuilderResources.ruleProvider(id: String): BuilderEntry = ruleProviders.singleOrNull { it.id == id } ?: error("Missing rule provider resource ID: $id")
fun BuilderResources.filter(id: String): BuilderFilter = filters.singleOrNull { it.id == id } ?: error("Missing filter resource ID: $id")
fun BuilderResources.iconSet(id: String): BuilderIconSet = iconSets.singleOrNull { it.id == id } ?: error("Missing icon set resource ID: $id")

fun BuilderResources.migrateBindings(bindings: BuilderResourceBindings): BuilderResourceBindings {
    if (bindings.schema == 2) {
        bindings.proxies.values.forEach(::proxy)
        bindings.ruleProviders.values.forEach(::ruleProvider)
        bindings.filters.values.flatMap { it.labels }.forEach(::filter)
        bindings.icons.values.map { it.set }.forEach(::iconSet)
        return bindings
    }
    require(bindings.schema == 1) { "Unknown resource binding version" }
    fun <T> byName(items: List<T>, value: String, name: (T) -> String, id: (T) -> String): String {
        val matches = items.filter { name(it) == value }
        require(matches.size == 1) { "Missing or ambiguous legacy resource: $value" }
        return id(matches.single())
    }
    return bindings.copy(schema = 2,
        proxies = bindings.proxies.mapValues { (_, name) -> byName(proxies, name, BuilderEntry::name) { requireNotNull(it.id) } },
        ruleProviders = bindings.ruleProviders.mapValues { (_, name) -> byName(ruleProviders, name, BuilderEntry::name) { requireNotNull(it.id) } },
        filters = bindings.filters.mapValues { (_, binding) -> binding.copy(labels = binding.labels.map { label -> byName(filters, label, BuilderFilter::label, BuilderFilter::id) }) },
        icons = bindings.icons.mapValues { (_, binding) -> binding.copy(set = byName(iconSets, binding.set, BuilderIconSet::name, BuilderIconSet::id)) })
}

fun BuilderResources.migrateSourceHeader(source: String): String {
    val before = BuilderResourceBindings.parse(source)
    val migrated = migrateBindings(before)
    return if (before == migrated) source else BuilderResourceBindings.replaceHeader(source, migrated)
}

fun BuilderResources.detachReferences(source: String, kind: BuilderResourceKind, ids: Set<String>): String {
    val before = BuilderResourceBindings.parse(source)
    val stable = migrateBindings(before)
    val next = when (kind) {
        BuilderResourceKind.Proxy -> stable.copy(proxies = stable.proxies.filterValues { it !in ids })
        BuilderResourceKind.RuleProvider -> stable.copy(ruleProviders = stable.ruleProviders.filterValues { it !in ids })
        BuilderResourceKind.Filter -> stable.copy(filters = stable.filters.mapValues { (_, binding) -> binding.copy(labels = binding.labels.filterNot { it in ids }) }.filterValues { it.labels.isNotEmpty() })
        BuilderResourceKind.IconSet -> stable.copy(icons = stable.icons.filterValues { it.set !in ids })
    }
    return if (before == next) source else BuilderResourceBindings.replaceHeader(source, next)
}

class BuilderIdentityMigrationPlan(val expectedLibraryEncoded: String?, val libraryYaml: String?, val sources: Map<String, String>) {
    override fun toString(): String = "BuilderIdentityMigrationPlan(profiles=${sources.size})"
}
fun BuilderResourceSnapshot.planIdentityMigration(sources: Map<String, String>): BuilderIdentityMigrationPlan =
    BuilderIdentityMigrationPlan(encoded, if (encoded == null) null else resources.yaml(), sources.mapValues { (_, source) -> resources.migrateSourceHeader(source) })

/** Only outbound-name fields are projected; credentials and transport strings remain literal. */
internal fun projectBuilderProxyReferences(fields: Map<String, Any?>, target: (String) -> String): Map<String, Any?> =
    fields.mapValues { (key, value) -> if (key in setOf("dialer-proxy", "connect-via") && value is String && value.isNotEmpty()) target(value) else value }
