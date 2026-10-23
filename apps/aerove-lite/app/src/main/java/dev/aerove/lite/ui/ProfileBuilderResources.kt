package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.profilebuilder.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface ResourceEditor {
    data class Graph(val section: BuilderSection, val index: Int?) : ResourceEditor
    data class Filter(val index: Int?) : ResourceEditor
    data class IconSet(val index: Int?) : ResourceEditor
}

/** A shared library route. Root supplies file picking and receives the actual saved/read-back snapshot. */
@Composable
internal fun ProfileBuilderResourcesScreen(library: BuilderResourceLibrary, deletion: GlobalResourceDeletion, padding: PaddingValues,
    onChanged: (BuilderResourceSnapshot) -> Unit, readIconFile: (suspend () -> String?)? = null) {
    var snapshot by remember(library) { mutableStateOf<BuilderResourceSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<ResourceEditor?>(null) }
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf<Job?>(null) }
    fun closeEditor() { operation?.cancel(); editor = null }
    fun runOperation(action: suspend (BuilderResourceSnapshot) -> BuilderResourceSnapshot) {
        val expected = snapshot ?: return
        if (busy) return
        busy = true
        error = null
        operation = scope.launch {
            try { val saved = action(expected); snapshot = saved; onChanged(saved); editor = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    fun reload() {
        if (busy) return
        busy = true
        scope.launch {
            try { snapshot = library.snapshot(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(library) { reload() }
    val resources = snapshot?.resources
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("builder-resources")) {
        if (error != null) item { LiteText(requireNotNull(error), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        item { LiteButton(stringResource(R.string.builder_retry), { reload() }, enabled = !busy) }
        if (resources != null) {
            for (section in listOf(BuilderSection.Proxies, BuilderSection.RuleProviders)) {
                item { SectionLabel(builderSectionTitle(section)) }
                val entries = if (section == BuilderSection.Proxies) resources.proxies else resources.ruleProviders
                items(entries.size) { index ->
                    SettingsRow(entries[index].name, onClick = if (busy) null else ({ editor = ResourceEditor.Graph(section, index) }))
                }
                item { LiteButton(stringResource(R.string.builder_add), { editor = ResourceEditor.Graph(section, null) }, enabled = !busy) }
            }
            item { SectionLabel(stringResource(R.string.builder_filters)) }
            items(resources.filters.size) { index -> SettingsRow(resources.filters[index].label, resources.filters[index].regex,
                onClick = if (busy) null else ({ editor = ResourceEditor.Filter(index) })) }
            item { LiteButton(stringResource(R.string.builder_add), { editor = ResourceEditor.Filter(null) }, enabled = !busy) }
            item { SectionLabel(stringResource(R.string.builder_icon_sets)) }
            items(resources.iconSets.size) { index -> SettingsRow(resources.iconSets[index].name, resources.iconSets[index].icons.size.toString(),
                onClick = if (busy) null else ({ editor = ResourceEditor.IconSet(index) })) }
            item {
                LiteButton(stringResource(R.string.builder_import_url), { editor = ResourceEditor.IconSet(null) }, enabled = !busy)
                if (readIconFile != null) LiteButton(stringResource(R.string.builder_import_file), {
                    runOperation { expected -> readIconFile()?.let { library.importIconSet(expected, it, fallbackName = "Icons") } ?: expected }
                }, enabled = !busy)
            }
        }
    }
    val overlay = LocalLiteOverlay.current
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentBusy by rememberUpdatedState(busy)
    val currentError by rememberUpdatedState(error)
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    LaunchedEffect(editor) {
        val edit = editor
        val content: (@Composable () -> Unit)? = if (edit == null) null else ({
            val current = currentSnapshot
            if (current != null) when (edit) {
                is ResourceEditor.Graph -> {
                    val graph = resourceGraph(current.resources)
                    ProfileBuilderEntryForm(graph, edit.section, edit.index, { editor = null }, busy = currentBusy, operationError = currentError,
                        actions = {
                            if (edit.index != null) {
                                LiteButton(stringResource(R.string.builder_delete), { runOperation { expected ->
                                    val kind = if (edit.section == BuilderSection.Proxies) BuilderResourceKind.Proxy else BuilderResourceKind.RuleProvider
                                    val entries = if (kind == BuilderResourceKind.Proxy) expected.resources.proxies else expected.resources.ruleProviders
                                    val next = if (kind == BuilderResourceKind.Proxy) expected.resources.copy(proxies = entries.filterIndexed { i, _ -> i != edit.index })
                                        else expected.resources.copy(ruleProviders = entries.filterIndexed { i, _ -> i != edit.index })
                                    deletion.delete(expected, kind, setOf(requireNotNull(entries[edit.index].id)), next)
                                } }, destructive = true, enabled = !currentBusy)
                                if (edit.index > 0) LiteButton(stringResource(R.string.builder_up), { runOperation { expected ->
                                    val changed = graph.move(edit.section, edit.index, edit.index - 1)
                                    library.replace(expected, if (edit.section == BuilderSection.Proxies) expected.resources.copy(proxies = preserveResourceIds(changed.entries(edit.section), expected.resources.proxies))
                                        else expected.resources.copy(ruleProviders = preserveResourceIds(changed.entries(edit.section), expected.resources.ruleProviders)))
                                } }, enabled = !currentBusy)
                            }
                        }) { name, fields, _ ->
                        val changed = graph.put(edit.section, edit.index, name, fields)
                        runOperation { expected -> library.replace(expected, if (edit.section == BuilderSection.Proxies)
                            expected.resources.copy(proxies = preserveResourceIds(changed.entries(edit.section), expected.resources.proxies, edit.index, name)) else expected.resources.copy(ruleProviders = preserveResourceIds(changed.entries(edit.section), expected.resources.ruleProviders, edit.index, name))) }
                    }
                }
                is ResourceEditor.Filter -> BuilderFilterForm(current.resources.filters.getOrNull(edit.index ?: -1), currentBusy,
                    currentError, { closeEditor() }, { filter -> runOperation { expected ->
                        val list = expected.resources.filters.toMutableList()
                        if (edit.index == null) list.add(filter) else list[edit.index] = filter
                        library.replace(expected, expected.resources.copy(filters = list))
                    } }, delete = if (edit.index == null) null else ({ runOperation { expected ->
                        deletion.delete(expected, BuilderResourceKind.Filter, setOf(expected.resources.filters[edit.index].id), expected.resources.copy(filters = expected.resources.filters.filterIndexed { i, _ -> i != edit.index }))
                    } }), moveUp = if (edit.index == null || edit.index == 0) null else ({ runOperation { expected ->
                        val items = expected.resources.filters.toMutableList().apply { add(edit.index - 1, removeAt(edit.index)) }
                        library.replace(expected, expected.resources.copy(filters = items))
                    } }))
                is ResourceEditor.IconSet -> BuilderIconSetForm(current.resources.iconSets.getOrNull(edit.index ?: -1), currentBusy,
                    currentError, { closeEditor() }, sync = { name, url -> runOperation { expected ->
                        if (edit.index == null) library.importRemoteIconSet(expected, url, name)
                        else {
                            library.editIconSet(expected, expected.resources.iconSets[edit.index].id, name, url, refresh = true)
                        }
                    } }, delete = if (edit.index == null) null else ({ runOperation { expected ->
                        deletion.delete(expected, BuilderResourceKind.IconSet, setOf(expected.resources.iconSets[edit.index].id), expected.resources.copy(iconSets = expected.resources.iconSets.filterIndexed { i, _ -> i != edit.index }))
                    } }), moveUp = if (edit.index == null || edit.index == 0) null else ({ runOperation { expected ->
                        val items = expected.resources.iconSets.toMutableList().apply { add(edit.index - 1, removeAt(edit.index)) }
                        library.replace(expected, expected.resources.copy(iconSets = items))
                    } }))
            }
        })
        if (content != null) { owned[0] = content; overlay.content = content }
        else { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    }
    DisposableEffect(overlay) { onDispose { if (overlay.content === owned[0]) overlay.dismiss() } }
}

private fun resourceGraph(resources: BuilderResources): ProfileBuilderDocument {
    var document = ProfileBuilderDocument.empty()
    resources.proxies.forEach { document = document.put(BuilderSection.Proxies, null, it.name, it.fields) }
    resources.ruleProviders.forEach { document = document.put(BuilderSection.RuleProviders, null, it.name, it.fields) }
    return document
}

@Composable
private fun BuilderFilterForm(initial: BuilderFilter?, busy: Boolean, error: String?, close: () -> Unit,
    save: (BuilderFilter) -> Unit, delete: (() -> Unit)?, moveUp: (() -> Unit)?) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var regex by remember { mutableStateOf(initial?.regex.orEmpty()) }
    val valid = label.isNotBlank() && regex.isNotBlank()
    ProfileMaintenanceSheet(stringResource(R.string.builder_filters), close) {
        LiteField(label, { label = it }, stringResource(R.string.builder_name), enabled = !busy)
        LiteField(regex, { regex = it }, stringResource(R.string.builder_regex), enabled = !busy)
        error?.let { LiteText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        LiteButton(stringResource(R.string.builder_save), { save(initial?.copy(label = label.trim(), regex = regex) ?: BuilderFilter(label.trim(), regex)) }, enabled = valid && !busy)
        if (delete != null) LiteButton(stringResource(R.string.builder_delete), delete, destructive = true, enabled = !busy)
        if (moveUp != null) LiteButton(stringResource(R.string.builder_up), moveUp, enabled = !busy)
    }
}

@Composable
private fun BuilderIconSetForm(initial: BuilderIconSet?, busy: Boolean, error: String?, close: () -> Unit,
    sync: (String, String) -> Unit, delete: (() -> Unit)?, moveUp: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    ProfileMaintenanceSheet(stringResource(R.string.builder_icon_sets), close) {
        LiteField(name, { name = it }, stringResource(R.string.builder_name), enabled = !busy)
        LiteField(url, { url = it }, "URL", enabled = !busy)
        error?.let { LiteText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        initial?.icons?.forEach { LiteText(it.name, style = LiteType.Caption) }
        LiteButton(stringResource(R.string.builder_sync), { sync(name.trim(), url.trim()) }, enabled = !busy && name.isNotBlank() && url.isNotBlank())
        if (delete != null) LiteButton(stringResource(R.string.builder_delete), delete, destructive = true, enabled = !busy)
        if (moveUp != null) LiteButton(stringResource(R.string.builder_up), moveUp, enabled = !busy)
    }
}

private fun preserveResourceIds(entries: List<BuilderEntry>, previous: List<BuilderEntry>, index: Int? = null, name: String? = null): List<BuilderEntry> = entries.map { entry -> entry.copy(id = if (name != null && entry.name == name && index != null) previous[index].id else previous.singleOrNull { it.name == entry.name }?.id) }
