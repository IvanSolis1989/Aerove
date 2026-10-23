package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ConfigYaml

enum class BuilderSection(val key: String, val namedMap: Boolean = false) {
    Proxies("proxies"), Groups("proxy-groups"), Rules("rules"),
    ProxyProviders("proxy-providers", true), RuleProviders("rule-providers", true),
}

data class BuilderIssue(val path: String, val reason: String)
data class BuilderEntry(val name: String, val fields: Map<String, Any?>, val id: String? = null)

/** Immutable edits retain all configuration fields outside the edited item. */
class ProfileBuilderDocument private constructor(private val root: Map<String, Any?>, val bindings: BuilderResourceBindings = BuilderResourceBindings()) {
    fun entries(section: BuilderSection): List<BuilderEntry> = when {
        section == BuilderSection.Rules -> (root[section.key] as? List<*>)?.mapIndexed { index, value ->
            BuilderEntry(index.toString(), mapOf("rule" to value))
        }.orEmpty()
        section.namedMap -> (root[section.key] as? Map<*, *>)?.map { (name, value) ->
            BuilderEntry(name as String, stringMap(value))
        }.orEmpty()
        else -> (root[section.key] as? List<*>)?.map { value ->
            val fields = stringMap(value)
            BuilderEntry(fields["name"] as? String ?: "", fields)
        }.orEmpty()
    }

    fun targets(): List<String> = BUILT_INS.toList() +
        entries(BuilderSection.Proxies).map { it.name } + entries(BuilderSection.Groups).map { it.name }

    fun put(section: BuilderSection, index: Int?, name: String, fields: Map<String, Any?>): ProfileBuilderDocument {
        val items = entries(section).toMutableList()
        require(index == null || index in items.indices) { "Item no longer exists" }
        if (section != BuilderSection.Rules) {
            require(name.isNotBlank() && name.none { it.isISOControl() }) { "Name is required" }
            require(items.withIndex().none { it.index != index && it.value.name == name }) { "Duplicate name: $name" }
            if (section == BuilderSection.Proxies || section == BuilderSection.Groups) {
                require(name !in BUILT_INS) { "Reserved name: $name" }
                val other = if (section == BuilderSection.Proxies) BuilderSection.Groups else BuilderSection.Proxies
                require(entries(other).none { it.name == name }) { "Duplicate target: $name" }
            }
        }
        val item = BuilderEntry(name, if (section.namedMap || section == BuilderSection.Rules) fields else fields + ("name" to name))
        val old = index?.let { items[it].name }
        if (index == null) items.add(item) else items[index] = item
        // Editing an imported template makes the edited item local; explicit bind operations restore its link.
        val detached = when (section) {
            BuilderSection.Proxies -> bindings.copy(proxies = bindings.proxies - old.orEmpty())
            BuilderSection.RuleProviders -> bindings.copy(ruleProviders = bindings.ruleProviders - old.orEmpty())
            BuilderSection.Groups -> bindings.copy(filters = bindings.filters - old.orEmpty(), icons = bindings.icons - old.orEmpty())
            else -> bindings
        }
        val next = withEntries(section, items).withBindings(detached)
        return if (old != null && old != name && section != BuilderSection.Rules) next.renameReferences(section, old, name) else next
    }

    fun move(section: BuilderSection, from: Int, to: Int): ProfileBuilderDocument {
        val entries = entries(section).toMutableList()
        require(from in entries.indices && to in entries.indices) { "Invalid item order" }
        entries.add(to, entries.removeAt(from))
        return withEntries(section, entries)
    }

    fun duplicate(section: BuilderSection, index: Int, name: String): ProfileBuilderDocument {
        val item = entries(section)[index]
        val appended = put(section, null, name, item.fields)
        return appended.move(section, appended.entries(section).lastIndex, index + 1)
    }

    /** A referenced item can only be removed with an explicit replacement. */
    fun delete(section: BuilderSection, indices: Set<Int>, replacement: String? = null): ProfileBuilderDocument {
        val items = entries(section)
        require(indices.all { it in items.indices }) { "Item no longer exists" }
        val names = indices.map { items[it].name }.toSet()
        require(replacement == null || replacement !in names) { "Replacement is being deleted" }
        var next = withEntries(section, items.filterIndexed { index, _ -> index !in indices })
        next = next.withBindings(when (section) {
            BuilderSection.Proxies -> bindings.copy(proxies = bindings.proxies - names)
            BuilderSection.RuleProviders -> bindings.copy(ruleProviders = bindings.ruleProviders - names)
            BuilderSection.Groups -> bindings.copy(filters = bindings.filters - names, icons = bindings.icons - names)
            else -> bindings
        })
        if (section != BuilderSection.Rules) names.forEach { name ->
            val references = next.references(section, name)
            require(references.isEmpty() || replacement != null) { "Referenced by: ${references.joinToString()}" }
            if (replacement != null) {
                val candidates = if (section == BuilderSection.Proxies || section == BuilderSection.Groups) next.targets()
                    else next.entries(section).map { it.name }
                require(replacement in candidates) { "Unknown replacement: $replacement" }
                next = next.renameReferences(section, name, replacement)
            }
        }
        return next
    }

    fun references(section: BuilderSection, name: String): List<String> {
        val result = mutableListOf<String>()
        if (section == BuilderSection.Proxies || section == BuilderSection.Groups) {
            entries(BuilderSection.Groups).forEachIndexed { index, e ->
                if (name in (e.fields["proxies"] as? List<*>).orEmpty()) result += "$.proxy-groups[$index].proxies"
            }
            entries(BuilderSection.Proxies).forEachIndexed { index, e ->
                listOf("dialer-proxy", "connect-via").forEach { if (e.fields[it] == name) result += "$.proxies[$index].$it" }
            }
            entries(BuilderSection.Rules).forEachIndexed { index, e ->
                if (ruleParts(e.fields["rule"].toString()).target == name) result += "$.rules[$index]"
            }
        } else if (section == BuilderSection.ProxyProviders) {
            entries(BuilderSection.Groups).forEachIndexed { index, e ->
                if (name in (e.fields["use"] as? List<*>).orEmpty()) result += "$.proxy-groups[$index].use"
            }
        } else if (section == BuilderSection.RuleProviders) {
            entries(BuilderSection.Rules).forEachIndexed { index, e ->
                val rule = ruleParts(e.fields["rule"].toString())
                if (rule.type == "RULE-SET" && rule.payload == name) result += "$.rules[$index]"
            }
        }
        return result
    }

    fun issues(): List<BuilderIssue> {
        val issues = mutableListOf<BuilderIssue>()
        val targets = targets().toSet()
        val graph = linkedMapOf<String, List<String>>()
        val providers = entries(BuilderSection.ProxyProviders).map { it.name }.toSet()
        entries(BuilderSection.Groups).forEachIndexed { index, e ->
            val members = (e.fields["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            graph[e.name] = members
            members.filter { it !in targets }.forEach { issues += BuilderIssue("$.proxy-groups[$index].proxies", "Unknown target: $it") }
            (e.fields["use"] as? List<*>)?.filter { it !in providers }?.forEach {
                issues += BuilderIssue("$.proxy-groups[$index].use", "Unknown provider: $it")
            }
        }
        entries(BuilderSection.Proxies).forEachIndexed { index, e ->
            val refs = listOf("dialer-proxy", "connect-via").mapNotNull { e.fields[it] as? String }.filter { it.isNotEmpty() }
            graph[e.name] = refs
            refs.filter { it !in targets }.forEach { issues += BuilderIssue("$.proxies[$index]", "Unknown dialer: $it") }
        }
        val completed = mutableSetOf<String>()
        graph.keys.forEach { start ->
            if (start !in completed) {
                val active = linkedSetOf(start)
                val stack = java.util.ArrayDeque<Pair<String, Iterator<String>>>()
                stack.push(start to graph[start].orEmpty().iterator())
                while (stack.isNotEmpty()) {
                    val (name, children) = requireNotNull(stack.peek())
                    if (!children.hasNext()) {
                        stack.pop(); active.remove(name); completed.add(name)
                    } else {
                        val child = children.next()
                        if (child in active) issues += BuilderIssue("$", "Reference cycle: ${(active.toList() + child).joinToString(" → ")}")
                        else if (child in graph && child !in completed) {
                            active.add(child); stack.push(child to graph[child].orEmpty().iterator())
                        }
                    }
                }
            }
        }
        entries(BuilderSection.Rules).forEachIndexed { index, e ->
            val rule = runCatching { ruleParts(e.fields["rule"] as String) }.getOrElse {
                issues += BuilderIssue("$.rules[$index]", "Invalid rule"); return@forEachIndexed
            }
            if (rule.target !in targets) issues += BuilderIssue("$.rules[$index]", "Unknown target: ${rule.target}")
            if (rule.type == "RULE-SET" && entries(BuilderSection.RuleProviders).none { it.name == rule.payload })
                issues += BuilderIssue("$.rules[$index]", "Unknown rule provider: ${rule.payload}")
        }
        return issues
    }

    fun yaml(): String = bindings.header() + ConfigYaml.dump(root)

    fun sameContent(other: ProfileBuilderDocument?): Boolean = other != null && root == other.root && bindings == other.bindings

    fun withBindings(value: BuilderResourceBindings): ProfileBuilderDocument = ProfileBuilderDocument(root, value)

    private fun withEntries(section: BuilderSection, entries: List<BuilderEntry>): ProfileBuilderDocument {
        val value: Any = when {
            section == BuilderSection.Rules -> entries.map { it.fields.getValue("rule") }
            section.namedMap -> entries.associateTo(linkedMapOf()) { it.name to it.fields }
            else -> entries.map { it.fields }
        }
        return ProfileBuilderDocument(root + (section.key to value), bindings)
    }

    private fun renameReferences(section: BuilderSection, old: String, new: String): ProfileBuilderDocument {
        var next = this
        if (section == BuilderSection.Proxies || section == BuilderSection.Groups || section == BuilderSection.ProxyProviders) {
            next = next.withEntries(BuilderSection.Groups, next.entries(BuilderSection.Groups).map { entry ->
                val key = if (section == BuilderSection.ProxyProviders) "use" else "proxies"
                val members = entry.fields[key] as? List<*>
                if (members == null) entry else entry.copy(fields = entry.fields + (key to members.map { if (it == old) new else it }))
            })
        }
        if (section == BuilderSection.Proxies || section == BuilderSection.Groups) {
            next = next.withEntries(BuilderSection.Proxies, next.entries(BuilderSection.Proxies).map { entry ->
                entry.copy(fields = entry.fields.mapValues { (key, value) ->
                    if (key in setOf("dialer-proxy", "connect-via") && value == old) new else value
                })
            })
        }
        if (section != BuilderSection.ProxyProviders) next = next.withEntries(BuilderSection.Rules, next.entries(BuilderSection.Rules).map { entry ->
            val parts = ruleParts(entry.fields["rule"].toString())
            val line = when {
                section == BuilderSection.RuleProviders && parts.type == "RULE-SET" && parts.payload == old -> parts.copy(payload = new).encode()
                section != BuilderSection.RuleProviders && parts.target == old -> parts.copy(target = new).encode()
                else -> entry.fields["rule"].toString()
            }
            entry.copy(fields = mapOf("rule" to line))
        })
        return next
    }

    companion object {
        val BUILT_INS = linkedSetOf("DIRECT", "REJECT")
        fun parse(yaml: String): ProfileBuilderDocument {
            val root = ConfigYaml.parse(yaml)
            BuilderSection.entries.forEach { section ->
                if (root[section.key] != null) {
                    val value = root[section.key]
                    if (section.namedMap) {
                        require(value is Map<*, *> && value.values.all { it is Map<*, *> }) { "$.${section.key}: Expected named mappings" }
                    } else {
                        require(value is List<*>) { "$.${section.key}: Expected a list" }
                        if (section == BuilderSection.Rules) require(value.all { it is String }) { "$.rules: Expected rule strings" }
                        else require(value.all { it is Map<*, *> && it["name"] is String }) { "$.${section.key}: Expected named entries" }
                    }
                }
            }
            val document = ProfileBuilderDocument(root, BuilderResourceBindings.parse(yaml))
            BuilderSection.entries.filter { it != BuilderSection.Rules }.forEach { section ->
                val names = document.entries(section).map { it.name }
                require(names.distinct().size == names.size) { "$.${section.key}: Duplicate names" }
            }
            return document
        }
        fun empty(): ProfileBuilderDocument = parse("proxies: []\nproxy-groups: []\nrules: ['MATCH,DIRECT']\n")
        private fun stringMap(value: Any?): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String }) { "Expected named fields" }
            return value.entries.associateTo(linkedMapOf()) { (key, item) -> key as String to item }
        }
    }
}

data class BuilderRule(val type: String, val payload: String, val target: String, val noResolve: Boolean = false) {
    fun encode(): String = (listOf(type) + (if (type == "MATCH" || type == "FINAL") emptyList() else listOf(payload)) + target +
        if (noResolve) listOf("no-resolve") else emptyList()).joinToString(",")
}

/** Split only top-level commas so logical rule payloads survive target edits. */
fun ruleParts(line: String): BuilderRule {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    line.forEachIndexed { index, char ->
        if (char == '(') depth++
        if (char == ')') depth--
        require(depth >= 0) { "Unbalanced rule" }
        if (char == ',' && depth == 0) { parts += line.substring(start, index).trim(); start = index + 1 }
    }
    require(depth == 0) { "Unbalanced rule" }
    parts += line.substring(start).trim()
    val noResolve = parts.lastOrNull() == "no-resolve"
    if (noResolve) parts.removeAt(parts.lastIndex)
    val terminal = parts.firstOrNull() in setOf("MATCH", "FINAL")
    require(parts.size == if (terminal) 2 else 3) { "Invalid rule fields" }
    require(parts.none { it.isBlank() }) { "Empty rule field" }
    return BuilderRule(parts.first(), if (terminal) "" else parts[1], parts.last(), noResolve)
}
