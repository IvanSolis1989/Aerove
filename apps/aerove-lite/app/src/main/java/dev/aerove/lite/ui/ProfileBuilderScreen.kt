package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.profilebuilder.*
import kotlinx.coroutines.launch

private data class BuilderEdit(val section: BuilderSection, val index: Int?)

/** Root supplies a real repository-backed session and owns the active-profile reload after onSaved. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileBuilderScreen(session: ProfileBuilderSession, padding: PaddingValues,
    close: () -> Unit, onSaved: () -> Unit = {}, resources: BuilderResources = BuilderResources()) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var section by rememberSaveable(session.profileId) { mutableStateOf(BuilderSection.Proxies) }
    var edit by remember { mutableStateOf<BuilderEdit?>(null) }
    var menu by remember { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(session) { session.open() }
    val back = { if (state.dirty) discard = true else if (session.cancel()) close() }
    BackHandler(enabled = edit == null && menu == null) { back() }
    LaunchedEffect(section) { selected = emptySet() }
    val document = state.document
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("profile-builder")) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiteButton(stringResource(R.string.builder_close), back, enabled = !state.busy)
                LiteButton(stringResource(R.string.builder_save), { scope.launch { if (session.save()) onSaved() } },
                    Modifier.testTag("builder-save"), selected = true, enabled = !state.busy && state.dirty)
            }
        }
        if (session.isNew) item {
            LiteField(state.name, { session.renameDraft(it) }, stringResource(R.string.builder_name),
                Modifier.testTag("builder-draft-name"), enabled = !state.busy)
        }
        if (state.canUndo || state.canRedo) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiteButton(stringResource(R.string.builder_undo), session::undo, enabled = !state.busy && state.canUndo)
                LiteButton(stringResource(R.string.builder_redo), session::redo, enabled = !state.busy && state.canRedo)
            }
        }
        if (state.error != null) item { LiteText(requireNotNull(state.error), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        if (document == null) item {
            LiteButton(stringResource(R.string.builder_retry), { scope.launch { session.open() } }, enabled = !state.busy)
        } else {
            item {
                LitePane(Modifier.fillMaxWidth()) { Column {
                    BuilderSection.entries.forEach { candidate -> SettingsRow(builderSectionTitle(candidate),
                        document.entries(candidate).size.toString(), onClick = { section = candidate }) {
                        if (section == candidate) LiteIcon(FullGlyphs.check)
                    } }
                } }
            }
            if (section == BuilderSection.Proxies) item {
                resources.proxies.forEach { template ->
                    LiteButton(template.name, { session.edit { resources.bindProxy(requireNotNull(template.id), it) } },
                        selected = document.bindings.proxies[template.name] == template.id, enabled = !state.busy)
                }
            }
            if (section == BuilderSection.RuleProviders) item {
                resources.ruleProviders.forEach { template ->
                    LiteButton(template.name, { session.edit { resources.bindRuleProvider(requireNotNull(template.id), it) } },
                        selected = document.bindings.ruleProviders[template.name] == template.id, enabled = !state.busy)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteButton(stringResource(R.string.builder_add), { edit = BuilderEdit(section, null) }, enabled = !state.busy)
                    LiteButton(stringResource(R.string.builder_sort), { selected = emptySet(); sort = !sort }, selected = sort, enabled = !state.busy)
                    if (selected.isNotEmpty()) LiteButton(stringResource(R.string.builder_delete), {
                        session.edit { it.delete(section, selected) }; selected = emptySet()
                    }, destructive = true, enabled = !state.busy)
                }
            }
            val entries = document.entries(section)
            if (entries.isEmpty()) item { LiteText(stringResource(R.string.builder_empty)) }
            itemsIndexed(entries) { index, entry ->
                LitePane(Modifier.fillMaxWidth().testTag("builder-item-$index")
                    .combinedClickable(enabled = !state.busy, onClick = {
                        if (selected.isNotEmpty()) selected = if (index in selected) selected - index else selected + index
                        else edit = BuilderEdit(section, index)
                    }, onLongClick = { menu = index })) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiteText(if (section == BuilderSection.Rules) entry.fields["rule"].toString() else entry.name,
                            style = LiteType.Label)
                        if (entry.fields["type"] != null) LiteText(entry.fields["type"].toString(), style = LiteType.Caption)
                        if (index in selected) LiteText(stringResource(R.string.builder_selected))
                        if (sort) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiteButton(stringResource(R.string.builder_up), { session.edit { it.move(section, index, index - 1) } }, enabled = index > 0 && !state.busy)
                            LiteButton(stringResource(R.string.builder_down), { session.edit { it.move(section, index, index + 1) } }, enabled = index < entries.lastIndex && !state.busy)
                        }
                    }
                }
            }
        }
    }
    val overlay = LocalLiteOverlay.current
    val currentState by rememberUpdatedState(state)
    val owner = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    LaunchedEffect(edit, menu, discard) {
        val editor = edit
        val menuIndex = menu
        val content: (@Composable () -> Unit)? = when {
            editor != null -> ({
                val doc = currentState.document
                if (doc != null) ProfileBuilderEntryForm(doc, editor.section, editor.index, { edit = null }, resources = resources) { name, fields, bindings ->
                    session.edit { it.put(editor.section, editor.index, name, fields).withBindings(bindings) }
                    if (session.state.value.error == null) edit = null
                }
            })
            menuIndex != null -> ({
                val doc = currentState.document
                val entry = doc?.entries(section)?.getOrNull(menuIndex)
                if (entry != null) BuilderItemActions(doc, section, menuIndex, entry, { menu = null },
                    edit = { menu = null; edit = BuilderEdit(section, menuIndex) },
                    select = { selected = selected + menuIndex; menu = null },
                    change = { operation -> session.edit(operation); if (session.state.value.error == null) menu = null })
            })
            discard -> ({ ProfileMaintenanceSheet(stringResource(R.string.builder_unsaved), { discard = false }) {
                LiteButton(stringResource(R.string.builder_discard), { if (session.cancel()) close() }, destructive = true, enabled = !currentState.busy)
                LiteButton(stringResource(R.string.builder_save), { scope.launch {
                    if (session.save()) { onSaved(); close() }
                } }, enabled = !currentState.busy)
            } })
            else -> null
        }
        if (content != null) { owner[0] = content; overlay.content = content }
        else { if (overlay.content === owner[0]) overlay.dismiss(); owner[0] = null }
    }
    DisposableEffect(overlay) { onDispose { if (overlay.content === owner[0]) overlay.dismiss() } }
}

@Composable
private fun BuilderItemActions(document: ProfileBuilderDocument, section: BuilderSection, index: Int,
    entry: BuilderEntry, close: () -> Unit, edit: () -> Unit, select: () -> Unit,
    change: ((ProfileBuilderDocument) -> ProfileBuilderDocument) -> Unit) {
    var replacement by remember { mutableStateOf("") }
    var copyName by remember { mutableStateOf(entry.name + " 2") }
    ProfileMaintenanceSheet(entry.name, close) {
        LiteButton(stringResource(R.string.builder_edit), edit)
        LiteButton(stringResource(R.string.builder_select), select)
        if (section != BuilderSection.Rules) LiteField(copyName, { copyName = it }, stringResource(R.string.builder_name))
        LiteButton(stringResource(R.string.builder_duplicate), { change { it.duplicate(section, index, copyName) } })
        val refs = document.references(section, entry.name)
        if (refs.isNotEmpty()) {
            LiteText(stringResource(R.string.builder_references, refs.joinToString("\n")), style = LiteType.Caption)
            val options = if (section == BuilderSection.Proxies || section == BuilderSection.Groups) document.targets()
                else document.entries(section).map { it.name }
            BuilderChoice(stringResource(R.string.builder_replacement), replacement, options.filter { it != entry.name }) { replacement = it }
        }
        LiteButton(stringResource(R.string.builder_delete), {
            change { it.delete(section, setOf(index), replacement.takeIf { value -> value.isNotBlank() }) }
        }, destructive = true, enabled = refs.isEmpty() || replacement.isNotBlank())
    }
}

@Composable
internal fun builderSectionTitle(section: BuilderSection): String = stringResource(when (section) {
    BuilderSection.Proxies -> R.string.builder_proxies
    BuilderSection.Groups -> R.string.builder_groups
    BuilderSection.Rules -> R.string.builder_rules
    BuilderSection.ProxyProviders -> R.string.builder_proxy_providers
    BuilderSection.RuleProviders -> R.string.builder_rule_providers
})
