package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.profilebuilder.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ProfileBuilderEntryForm(document: ProfileBuilderDocument, section: BuilderSection, index: Int?,
    close: () -> Unit, resources: BuilderResources = BuilderResources(), busy: Boolean = false,
    operationError: String? = null, actions: @Composable () -> Unit = {}, save: (String, Map<String, Any?>, BuilderResourceBindings) -> Unit) {
    val initial = remember(document, section, index) { index?.let { document.entries(section)[it] } }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var fields by remember { mutableStateOf(initial?.fields?.filterKeys { it != "name" } ?: when (section) {
        BuilderSection.Proxies -> mapOf("type" to "ss", "server" to "", "port" to 443L, "cipher" to "aes-128-gcm", "password" to "")
        BuilderSection.Groups -> mapOf("type" to "select", "proxies" to listOf("DIRECT"))
        BuilderSection.Rules -> mapOf("rule" to "MATCH,DIRECT")
        else -> mapOf("type" to "http", "url" to "", "path" to "", "interval" to 86400L)
    }) }
    var error by remember { mutableStateOf<String?>(null) }
    val filterScope = rememberCoroutineScope()
    var filterJob by remember { mutableStateOf<Job?>(null) }
    var filtering by remember { mutableStateOf(false) }
    val stableBindings = remember(document, resources) { resources.migrateBindings(document.bindings) }
    var selectedFilters by remember { mutableStateOf(stableBindings.filters[initial?.name]?.labels.orEmpty()) }
    var manualMembers by remember { mutableStateOf(stableBindings.filters[initial?.name]?.manualMembers ?:
        (initial?.fields?.get("proxies") as? List<*>)?.filterIsInstance<String>().orEmpty()) }
    var selectedIcon by remember { mutableStateOf(stableBindings.icons[initial?.name]) }
    ProfileMaintenanceSheet(builderSectionTitle(section), { if (!busy) close() }) {
        CompositionLocalProvider(LocalBuilderEnabled provides !busy) {
        if (section != BuilderSection.Rules) LiteField(name, { name = it }, stringResource(R.string.builder_name), Modifier.testTag("builder-entry-name"), enabled = !busy)
        if (section == BuilderSection.Rules) {
            BuilderRuleForm(fields["rule"].toString(), document.targets(), document.entries(BuilderSection.RuleProviders).map { it.name }) {
                fields = mapOf("rule" to it)
            }
        } else {
            val type = fields["type"]?.toString().orEmpty()
            val types = when (section) {
                BuilderSection.Proxies -> ProfileBuilderSchema.proxyTypes
                BuilderSection.Groups -> ProfileBuilderSchema.groupTypes
                BuilderSection.RuleProviders -> listOf("http", "file", "inline")
                else -> listOf("http", "file")
            }
            BuilderChoice(stringResource(R.string.builder_type), type, types) { fields = fields + ("type" to it) }
            BuilderMapFields(fields.filterKeys { it != "type" }, ProfileBuilderSchema.fields(section, type).filter { it != "type" },
                document, 0) {
                if (it["proxies"] != fields["proxies"]) {
                    selectedFilters = emptyList(); manualMembers = (it["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                }
                if (it["icon"] != fields["icon"]) selectedIcon = null
                fields = it + ("type" to type)
            }
            if (section == BuilderSection.Groups) {
                resources.filters.forEach { filter -> LiteButton(filter.label, {
                    filterJob?.cancel()
                    val previousManual = if (selectedFilters.isEmpty()) (fields["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty() else manualMembers
                    val nextFilters = if (filter.id in selectedFilters) selectedFilters - filter.id else selectedFilters + filter.id
                    filtering = true
                    filterJob = filterScope.launch {
                        try {
                            val matching = resources.filters.filter { it.id in nextFilters }.flatMap { resources.matching(it, document) }.filter { it != name }
                            manualMembers = previousManual
                            selectedFilters = nextFilters
                            fields = fields + ("proxies" to (manualMembers + matching).distinct())
                            error = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                        finally { filtering = false }
                    }
                }, selected = filter.id in selectedFilters, enabled = !busy && !filtering) }
                var iconSet by remember { mutableStateOf<String?>(null) }
                resources.iconSets.forEach { set -> LiteButton(set.name, { iconSet = if (iconSet == set.id) null else set.id }, enabled = !busy) }
                resources.iconSets.firstOrNull { it.id == iconSet }?.icons?.forEach { icon ->
                    LiteButton(icon.name, { fields = fields + ("icon" to icon.url); selectedIcon = BuilderIconBinding(requireNotNull(iconSet), icon.url); iconSet = null }, enabled = !busy)
                }
            }
        }
        (error ?: operationError)?.let { LiteText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        LiteButton(stringResource(R.string.builder_save), {
            try {
                val converted = normalizeBuilderFields(fields)
                // Check identity and references before dismissing; allow incomplete graph until whole-document save.
                document.put(section, index, name.trim(), converted)
                val previousName = initial?.name.orEmpty()
                var bindings = stableBindings
                if (section == BuilderSection.Groups) {
                    bindings = bindings.copy(filters = bindings.filters - previousName, icons = bindings.icons - previousName)
                    if (selectedFilters.isNotEmpty()) bindings = bindings.copy(filters = bindings.filters + (name.trim() to BuilderFilterBinding(selectedFilters, manualMembers)))
                    selectedIcon?.let { bindings = bindings.copy(icons = bindings.icons + (name.trim() to it)) }
                } else if (section == BuilderSection.Proxies) bindings = bindings.copy(proxies = bindings.proxies - previousName)
                else if (section == BuilderSection.RuleProviders) bindings = bindings.copy(ruleProviders = bindings.ruleProviders - previousName)
                save(name.trim(), converted, bindings)
            } catch (failure: Exception) { error = failure.message }
        }, Modifier.fillMaxWidth().testTag("builder-entry-save"), selected = true, enabled = !busy && !filtering)
        actions()
        }
    }
}

@Composable
private fun BuilderRuleForm(line: String, targets: List<String>, providers: List<String>, changed: (String) -> Unit) {
    val initial = remember { runCatching { ruleParts(line) }.getOrDefault(BuilderRule("DOMAIN", "", "DIRECT")) }
    var type by remember { mutableStateOf(initial.type) }
    var payload by remember { mutableStateOf(initial.payload) }
    var target by remember { mutableStateOf(initial.target) }
    var noResolve by remember { mutableStateOf(initial.noResolve) }
    fun emit() { changed(BuilderRule(type, payload, target, noResolve).encode()) }
    BuilderChoice(stringResource(R.string.builder_type), type, ProfileBuilderSchema.ruleTypes) { type = it; emit() }
    if (type !in listOf("MATCH", "FINAL")) {
        if (type == "RULE-SET") BuilderChoice(stringResource(R.string.builder_payload), payload, providers) { payload = it; emit() }
        else LiteField(payload, { payload = it; emit() }, stringResource(R.string.builder_payload), Modifier.testTag("builder-rule-payload"), enabled = LocalBuilderEnabled.current)
    }
    BuilderChoice(stringResource(R.string.builder_target), target, targets) { target = it; emit() }
    SettingsRow("no-resolve") { LiteSwitch(noResolve, "no-resolve", { noResolve = it; emit() }, enabled = LocalBuilderEnabled.current) }
}

@Composable
internal fun BuilderChoice(label: String, value: String, options: List<String>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = LocalBuilderEnabled.current
    SettingsRow(label, value, onClick = if (enabled) ({ expanded = !expanded }) else null) { LiteIcon(FullGlyphs.chevronForward) }
    if (expanded) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.distinct().forEach { item -> LiteButton(item, { changed(item); expanded = false }, selected = value == item, enabled = enabled) }
    }
}

@Composable
private fun BuilderMapFields(fields: Map<String, Any?>, known: List<String>, document: ProfileBuilderDocument,
    depth: Int, parent: String = "", changed: (Map<String, Any?>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var customKey by remember { mutableStateOf("") }
    val enabled = LocalBuilderEnabled.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.forEach { (key, value) -> key(key) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BuilderValueField(key, value, document, depth) { changed(fields + (key to it)) }
                LiteButton(stringResource(R.string.builder_remove_field, key), { changed(fields - key) }, destructive = true, enabled = enabled)
            }
        } }
        LiteButton(stringResource(R.string.builder_add_field), { adding = !adding }, enabled = enabled)
        if (adding) {
            (known - fields.keys).forEach { key -> LiteButton(key, {
                val default = if (key == "host" && parent == "h2-opts") emptyList<String>() else ProfileBuilderSchema.defaultValue(key)
                changed(fields + (key to default)); adding = false
            }, enabled = enabled) }
            // Free-form header names are fields, not a raw configuration editor.
            if (known.isEmpty()) {
                LiteField(customKey, { customKey = it }, stringResource(R.string.builder_field_name), enabled = enabled)
                LiteButton(stringResource(R.string.builder_add), {
                    if (customKey.isNotBlank() && customKey !in fields) {
                        changed(fields + (customKey to "")); customKey = ""; adding = false
                    }
                }, enabled = enabled && customKey.isNotBlank() && customKey !in fields)
            }
        }
    }
}

@Composable
private fun BuilderValueField(label: String, value: Any?, document: ProfileBuilderDocument, depth: Int,
    changed: (Any?) -> Unit) {
    val enabled = LocalBuilderEnabled.current
    val kind = when (value) {
        is Map<*, *> -> BuilderValueKind.Object
        is List<*> -> BuilderValueKind.List
        is Boolean -> BuilderValueKind.Toggle
        is Number -> BuilderValueKind.Number
        is String -> if (ProfileBuilderSchema.kind(label) == BuilderValueKind.Number) BuilderValueKind.Number else BuilderValueKind.Text
        else -> ProfileBuilderSchema.kind(label)
    }
    when (kind) {
        BuilderValueKind.Toggle -> SettingsRow(label) { LiteSwitch(value == true, label, { changed(it) }, enabled = enabled) }
        BuilderValueKind.Number -> LiteField(value?.toString().orEmpty(), { changed(it) }, label,
            keyboardType = if (label in ProfileBuilderSchema.decimalFields) KeyboardType.Decimal else KeyboardType.Number, enabled = enabled)
        BuilderValueKind.Object -> {
            var expanded by remember { mutableStateOf(false) }
            val map = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
            SettingsRow(label, map.size.toString(), onClick = if (enabled) ({ expanded = !expanded }) else null) { LiteIcon(FullGlyphs.chevronForward) }
            if (expanded && depth < 48) Column(Modifier.padding(start = 12.dp)) {
                BuilderMapFields(map, ProfileBuilderSchema.nestedFields(label), document, depth + 1, label) { changed(it) }
            }
        }
        BuilderValueKind.List -> {
            val list = value as? List<*> ?: emptyList<Any?>()
            SectionLabel(label)
            list.forEachIndexed { index, item -> key(index) {
                val replace = { next: Any? -> changed(list.mapIndexed { i, old -> if (i == index) next else old }) }
                val choices = when (label) {
                    "proxies" -> document.targets()
                    "use" -> document.entries(BuilderSection.ProxyProviders).map { it.name }
                    else -> emptyList()
                }
                if (choices.isNotEmpty()) BuilderChoice("${index + 1}", item.toString(), choices) { replace(it) }
                else BuilderValueField("${index + 1}", item, document, depth + 1, replace)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiteButton(stringResource(R.string.builder_delete), { changed(list.filterIndexed { i, _ -> i != index }) }, destructive = true, enabled = enabled)
                    if (index > 0) LiteButton(stringResource(R.string.builder_up), {
                        changed(list.toMutableList().apply { add(index - 1, removeAt(index)) })
                    }, enabled = enabled)
                }
            } }
            LiteButton(stringResource(R.string.builder_add), { changed(list + "") }, enabled = enabled)
        }
        BuilderValueKind.Text -> {
            if (label == "dialer-proxy" || label == "connect-via") BuilderChoice(label, value?.toString().orEmpty(), listOf("") + document.targets()) { changed(it) }
            else LiteField(value?.toString().orEmpty(), { changed(it) }, label,
                keyboardType = if (label.contains("password") || label == "private-key") KeyboardType.Password else KeyboardType.Text, enabled = enabled)
        }
    }
}

private val LocalBuilderEnabled = staticCompositionLocalOf { true }

private fun normalizeBuilderFields(fields: Map<String, Any?>): Map<String, Any?> = fields.mapValues { (key, value) ->
    when (value) {
        is Map<*, *> -> normalizeBuilderFields(value.entries.associate { it.key.toString() to it.value })
        is List<*> -> value.map { if (it is Map<*, *>) normalizeBuilderFields(it.entries.associate { e -> e.key.toString() to e.value }) else it }
        is String -> if (ProfileBuilderSchema.kind(key) == BuilderValueKind.Number) {
            if (key in ProfileBuilderSchema.decimalFields) value.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw IllegalArgumentException("$key: ${value.take(100)}")
            else value.toLongOrNull() ?: throw IllegalArgumentException("$key: ${value.take(100)}")
        } else value
        else -> value
    }
}
