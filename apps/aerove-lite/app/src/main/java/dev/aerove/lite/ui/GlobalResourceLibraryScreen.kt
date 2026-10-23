package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.data.ProfileEditorSource
import dev.aerove.lite.editor.EditorLanguage
import dev.aerove.lite.images.GroupIconLoader
import dev.aerove.lite.profilebuilder.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun globalResourceTitle(kind: BuilderResourceKind?): Int = when (kind) {
    null -> R.string.global_resources_title
    BuilderResourceKind.Proxy -> R.string.builder_proxies
    BuilderResourceKind.RuleProvider -> R.string.builder_rule_providers
    BuilderResourceKind.Filter -> R.string.builder_filters
    BuilderResourceKind.IconSet -> R.string.builder_icon_sets
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GlobalResourceLibraryScreen(session: GlobalResourceLibrarySession, padding: PaddingValues,
    onBack: () -> Unit, onChanged: (BuilderResourceSnapshot) -> Unit,
    readFile: suspend () -> GlobalLibraryFile?, rules: GlobalRuleLibraryAccess,
    proxyLinks: GlobalProxyLinkCodec, iconLoader: GroupIconLoader) {
    val state by session.state.collectAsState()
    val latestChanged by rememberUpdatedState(onChanged)
    val scope = rememberCoroutineScope()
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var confirmation by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var confirmationNames by remember { mutableStateOf(emptyList<String>()) }
    val quickEditLabel = stringResource(R.string.global_resources_quick_edit)
    fun close() {
        confirmation?.complete(false); confirmation = null
        operation?.cancel(); session.cancel()
        if (overlay.content === owned[0]) overlay.dismiss()
        owned[0] = null; session.clearTarget()
    }
    fun show(body: @Composable () -> Unit) { owned[0] = body; overlay.content = body }
    fun run(closeAfter: Boolean = true, task: suspend () -> Boolean) {
        if (state.busy) return
        operation = scope.launch {
            val before = session.state.value.snapshot?.encoded
            if (task()) {
                session.state.value.snapshot?.takeIf { it.encoded != before }?.let(latestChanged)
                if (closeAfter) close()
            }
        }
    }
    fun ruleEditor(name: String, previous: String?, preview: GlobalRuleLibraryPreview) {
        val opened = requireNotNull(session.state.value.snapshot)
        val editor = if (!preview.editable) ProfileEditorSession.preview(name) { preview.text }
        else ProfileEditorSession.editable(name, object : ProfileEditorSource {
            override suspend fun read() = preview.text
            override suspend fun replace(expected: String, replacement: String) {
                check(expected == preview.text)
                check(session.perform { current ->
                    require(current?.encoded == opened.encoded)
                    rules.saveText(requireNotNull(current), previous, name, replacement)
                }) { session.state.value.error ?: "Rule resource save failed" }
                session.state.value.snapshot?.let(latestChanged)
            }
        })
        show { LitePane(Modifier.fillMaxSize()) { EditorScreen(editor, ::close, ::close, language = EditorLanguage.Yaml) } }
    }
    fun openRule(name: String) {
        run(closeAfter = false) { session.perform { expected ->
            val current = requireNotNull(expected)
            val preview = rules.preview(current, name)
            ruleEditor(current.resources.ruleProvider(name).name, name, preview)
            current
        } }
    }
    fun delete(kind: BuilderResourceKind, names: Set<String>) {
        if (names.isEmpty()) return
        show {
            val current by session.state.collectAsState()
            ProfileMaintenanceSheet(stringResource(R.string.builder_delete), ::close) {
                LiteText(stringResource(R.string.global_resources_delete_confirm, names.map { id -> session.state.value.snapshot!!.resources.enumerate().single { it.id == id }.name }.joinToString(", ")))
                current.error?.let { LiteText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                LiteButton(stringResource(R.string.builder_delete), { run {
                    if (kind != BuilderResourceKind.RuleProvider) session.remove(kind, names)
                    else session.perform { expected ->
                        var result = requireNotNull(expected)
                        for (name in names) result = rules.delete(result, name)
                        result
                    }
                } }, enabled = !current.busy, destructive = true)
                LiteButton(stringResource(R.string.global_resources_cancel), ::close, enabled = !current.busy)
            }
        }
    }
    fun proxy(previousName: String?) {
        val resources = requireNotNull(session.state.value.snapshot).resources
        val graph = GlobalResourceLibrarySession.graph(resources)
        val index = previousName?.let { name -> resources.proxies.indexOfFirst { it.id == name } }
        show {
            val current by session.state.collectAsState()
            ProfileBuilderEntryForm(graph, BuilderSection.Proxies, index, ::close,
                busy = current.busy, operationError = current.error) { name, fields, _ -> run { session.putProxy(previousName, name, fields, proxyLinks::validate) } }
        }
    }
    fun filter(previousName: String?) {
        val initial = session.state.value.snapshot?.resources?.filters?.singleOrNull { it.id == previousName }
        show {
            val current by session.state.collectAsState()
            GlobalFilterForm(initial, current.busy, current.error, ::close) { label, regex -> run { session.putFilter(previousName, label, regex) } }
        }
    }
    fun iconOptions(name: String) {
        val initial = requireNotNull(session.state.value.snapshot).resources.iconSets.single { it.id == name }
        show {
            val current by session.state.collectAsState()
            GlobalNameUrlForm(stringResource(R.string.global_resources_options), initial.name,
                initial.url.takeIf(String::isNotEmpty), current.busy, current.error, ::close) { label, url -> run { session.editIcons(name, label, url.orEmpty()) } }
        }
    }
    fun ruleOptions(name: String) {
        val initial = requireNotNull(session.state.value.snapshot).resources.ruleProviders.single { it.id == name }
        val address = (initial.fields["url"] as? String)?.takeIf(String::isNotEmpty)
        show {
            val current by session.state.collectAsState()
            GlobalNameUrlForm(stringResource(R.string.global_resources_options), initial.name, address,
                current.busy, current.error, ::close) { label, url -> run { session.perform { expected -> rules.options(requireNotNull(expected), name, label, url.orEmpty()) } } }
        }
    }
    fun importUrl(kind: BuilderResourceKind) {
        show {
            val current by session.state.collectAsState()
            GlobalNameUrlForm(stringResource(R.string.builder_import_url), "", "", current.busy, current.error, ::close,
                optionalName = true) { name, url -> run {
                if (kind == BuilderResourceKind.IconSet) session.importIconsUrl(name, requireNotNull(url))
                else session.perform { expected -> rules.importUrl(requireNotNull(expected), name, requireNotNull(url)) }
            } }
        }
    }
    fun scratch() {
        show {
            val current by session.state.collectAsState()
            GlobalNameUrlForm(stringResource(R.string.global_resources_scratch), "", null, current.busy, current.error, ::close) { name, _ ->
                ruleEditor(name, null, GlobalRuleLibraryPreview("", true))
            }
        }
    }
    fun quickEdit() {
        run(closeAfter = false) { session.perform { expected ->
            val before = requireNotNull(expected)
            val original = proxyLinks.encode(before.resources.proxies).joinToString("\n")
            val editor = ProfileEditorSession.editable(quickEditLabel, object : ProfileEditorSource {
                override suspend fun read() = original
                override suspend fun replace(expected: String, replacement: String) {
                    require(expected == original)
                    val decoded = proxyLinks.decode(replacement)
                    proxyLinks.validate(decoded)
                    val removed = before.resources.proxies.map { it.name }.toSet() - decoded.map { it.name }.toSet()
                    if (removed.isNotEmpty()) {
                        val decision = CompletableDeferred<Boolean>(); confirmationNames = removed.toList(); confirmation = decision
                        // The confirmation belongs to this editor, so cancelling restores the unchanged document.
                        if (!decision.await()) throw CancellationException("Edit cancelled")
                    }
                    check(session.replaceProxies(decoded, before.encoded)) { session.state.value.error ?: "Resource save failed" }
                    session.state.value.snapshot?.let(latestChanged)
                }
            })
            show {
                val pending = confirmation
                LitePane(Modifier.fillMaxSize()) { EditorScreen(editor, ::close, ::close, language = EditorLanguage.Yaml) }
                if (pending != null) ProfileMaintenanceSheet(stringResource(R.string.global_resources_quick_edit), {
                    pending.complete(false); confirmation = null
                }) {
                    LiteText(stringResource(R.string.global_resources_replaced_confirm, confirmationNames.joinToString(", ")))
                    LiteButton(stringResource(R.string.builder_save), { pending.complete(true); confirmation = null })
                    LiteButton(stringResource(R.string.global_resources_cancel), { pending.complete(false); confirmation = null })
                }
            }
            before
        } }
    }
    fun iconPreview(name: String) {
        val set = requireNotNull(session.state.value.snapshot).resources.iconSets.single { it.id == name }
        show { GlobalIconPreview(set, iconLoader, ::close) }
    }
    fun edit(kind: BuilderResourceKind, name: String) = when (kind) {
        BuilderResourceKind.Proxy -> proxy(name)
        BuilderResourceKind.Filter -> filter(name)
        BuilderResourceKind.RuleProvider -> ruleOptions(name)
        BuilderResourceKind.IconSet -> iconOptions(name)
    }
    LaunchedEffect(session) { session.load() }
    LaunchedEffect(state.target, state.snapshot) {
        val target = state.target
        if (target != null && state.snapshot != null && !state.busy) { session.clearTarget(); edit(target.kind, target.id) }
    }
    DisposableEffect(session, overlay) { onDispose { close() } }
    BackHandler(overlay.content == null) { if (!session.back()) onBack() }
    val kind = state.category
    val list = LocalLiteListState.current
    val visible = session.visible()
    val allNames = kind?.let(session::ids).orEmpty()
    val drag = rememberOverrideDrag(allNames, list, !state.busy && kind != null && state.query.isBlank() && state.selected.isEmpty())
    val up = stringResource(R.string.builder_up); val down = stringResource(R.string.builder_down)
    fun move(name: String, destination: Int) {
        val order = allNames.toMutableList(); val from = order.indexOf(name)
        if (from >= 0 && from != destination && destination in order.indices) {
            order.add(destination, order.removeAt(from)); run(closeAfter = false) { session.reorder(requireNotNull(kind), order) }
        }
    }
    LazyColumn(state = list, contentPadding = padding, userScrollEnabled = drag.dragging == null,
        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("global-resource-library")) {
        if (state.error != null) item { LiteText(requireNotNull(state.error), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        if (state.snapshot == null) item { LiteButton(stringResource(R.string.builder_retry), { run(closeAfter = false) { session.load() } }, enabled = !state.busy) }
        else if (kind == null) item {
            LitePane(Modifier.fillMaxWidth()) { Column {
                BuilderResourceKind.entries.forEach { category ->
                    SettingsRow(stringResource(globalResourceTitle(category)), icon = globalResourceGlyph(category), onClick = { session.open(category) }) {
                        LiteIcon(FullGlyphs.chevronForward)
                    }
                }
            } }
        } else {
            item { LiteButton(stringResource(R.string.global_resources_back), { session.back() }, enabled = !state.busy) }
            if (kind == BuilderResourceKind.Proxy || kind == BuilderResourceKind.Filter) item {
                LiteField(state.query, session::query, stringResource(R.string.global_resources_search), enabled = !state.busy)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.selected.isNotEmpty()) {
                        LiteButton(stringResource(R.string.global_resources_select_all), session::selectVisible, enabled = !state.busy)
                        LiteButton(stringResource(R.string.builder_delete), { delete(kind, visible.map { it.id }.filter { it in state.selected }.toSet()) }, enabled = !state.busy, destructive = true)
                        LiteButton(stringResource(R.string.global_resources_done), { session.back() }, enabled = !state.busy)
                    } else {
                        when (kind) {
                            BuilderResourceKind.Proxy -> {
                                LiteButton(stringResource(R.string.builder_add), { proxy(null) }, enabled = !state.busy)
                                LiteButton(quickEditLabel, ::quickEdit, enabled = !state.busy)
                            }
                            BuilderResourceKind.Filter -> LiteButton(stringResource(R.string.builder_add), { filter(null) }, enabled = !state.busy)
                            else -> LiteButton(stringResource(R.string.builder_add), { show {
                                val current by session.state.collectAsState()
                                ProfileMaintenanceSheet(stringResource(R.string.builder_add), ::close) {
                                    if (kind == BuilderResourceKind.RuleProvider) LiteButton(stringResource(R.string.global_resources_scratch), ::scratch, enabled = !current.busy)
                                    LiteButton(stringResource(R.string.builder_import_url), { importUrl(kind) }, enabled = !current.busy)
                                    LiteButton(stringResource(R.string.builder_import_file), { run {
                                        val file = readFile() ?: return@run false
                                        if (kind == BuilderResourceKind.IconSet) session.importIcons(file)
                                        else session.perform { expected -> rules.importFile(requireNotNull(expected), file.name.substringBeforeLast('.'), file) }
                                    } }, enabled = !current.busy)
                                }
                            } }, enabled = !state.busy)
                        }
                        if ((kind == BuilderResourceKind.RuleProvider || kind == BuilderResourceKind.IconSet) && allNames.size > 1)
                            LiteButton(stringResource(if (state.sorting) R.string.global_resources_done else R.string.builder_sort), { session.sort(!state.sorting) }, enabled = !state.busy, selected = state.sorting)
                    }
                }
            }
            if (visible.isEmpty()) item { LiteText(stringResource(R.string.builder_empty), style = LiteType.Caption) }
            val shown = if (state.query.isBlank()) drag.keys.toList() else visible.map { it.id }
            items(shown, key = { it }) { name ->
                val item = visible.firstOrNull { it.id == name } ?: return@items
                val selectable = kind == BuilderResourceKind.Proxy || kind == BuilderResourceKind.Filter
                val canDrag = !state.busy && state.query.isBlank() && state.selected.isEmpty() && (selectable || state.sorting)
                var anchor by remember { mutableStateOf(Rect.Zero) }
                fun contextMenu() { show {
                    OverrideContextMenu(anchor, item.name, ::close) {
                        if (kind == BuilderResourceKind.RuleProvider) {
                            LiteButton(stringResource(R.string.global_resources_preview), { openRule(name) })
                            val remote = (state.snapshot!!.resources.ruleProviders.single { it.id == name }.fields["url"] as? String).orEmpty().isNotEmpty()
                            if (remote) LiteButton(stringResource(R.string.builder_sync), { run { session.perform { expected -> rules.sync(requireNotNull(expected), name) } } })
                        } else {
                            val remote = state.snapshot!!.resources.iconSets.single { it.id == name }.url.isNotEmpty()
                            if (remote) LiteButton(stringResource(R.string.builder_sync), { run { session.syncIcons(name) } })
                        }
                        LiteButton(stringResource(R.string.global_resources_options), { edit(kind, name) })
                        LiteButton(stringResource(R.string.builder_delete), { delete(kind, setOf(name)) }, destructive = true)
                    }
                } }
                LitePane(Modifier.fillMaxWidth().onGloballyPositioned { anchor = it.boundsInRoot() }
                    .overrideDragVisual(drag, name)
                    .then(if (canDrag) Modifier.overrideDragGesture(drag, name, true, commit = ::move) else Modifier)
                    .overrideMoveAccessibility(allNames.indexOf(name), allNames.size, up, down) { move(name, it) }
                    .combinedClickable(enabled = !state.busy, onClick = {
                        if (state.selected.isNotEmpty()) session.toggleSelected(name)
                        else when (kind) { BuilderResourceKind.RuleProvider -> openRule(name); BuilderResourceKind.IconSet -> iconPreview(name); else -> edit(kind, name) }
                    }, onLongClick = if (selectable || state.sorting) null else ::contextMenu)
                    .testTag("global-resource-row-$name"), selected = name in state.selected) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (kind == BuilderResourceKind.IconSet) {
                            val set = state.snapshot!!.resources.iconSets.single { it.id == name }
                            GroupIcon(set.icons.firstOrNull()?.url, iconLoader, active = overlay.content == null && LocalLitePageActive.current,
                                contentDescription = set.name, fallback = { LiteIcon(GlobalResourceGlyphs.photos) })
                        }
                        Column(Modifier.weight(1f)) {
                            LiteText(item.name)
                            when (kind) {
                                BuilderResourceKind.Filter -> LiteText(item.fields[1], style = LiteType.Caption)
                                BuilderResourceKind.Proxy -> LiteText(item.fields.drop(1).take(3).joinToString(" · "), style = LiteType.Caption)
                                BuilderResourceKind.IconSet -> {
                                    val set = state.snapshot!!.resources.iconSets.single { it.id == name }
                                    LiteText(stringResource(if (set.url.isBlank()) R.string.global_resources_local else R.string.global_resources_remote) + " · " + set.icons.size, style = LiteType.Caption)
                                }
                                BuilderResourceKind.RuleProvider -> GlobalRuleInfo(rules, requireNotNull(state.snapshot), name)
                            }
                        }
                        if (selectable) LiteIconButton(stringResource(R.string.builder_select), if (name in state.selected) FullGlyphs.check else GlobalSelectGlyph, { session.toggleSelected(name) })
                        else if (state.sorting) LiteIcon(OverrideDragGlyph) else LiteIcon(FullGlyphs.chevronForward)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalFilterForm(initial: BuilderFilter?, busy: Boolean, error: String?, close: () -> Unit, save: (String, String) -> Unit) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var regex by remember { mutableStateOf(initial?.regex.orEmpty()) }
    ProfileMaintenanceSheet(stringResource(if (initial == null) R.string.builder_add else R.string.builder_edit), close) {
        LiteField(label, { label = it }, stringResource(R.string.builder_name), enabled = !busy)
        LiteField(regex, { regex = it }, stringResource(R.string.builder_regex), enabled = !busy)
        error?.let { LiteText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        LiteButton(stringResource(R.string.builder_save), { save(label, regex) }, enabled = !busy && label.isNotBlank() && regex.isNotBlank())
    }
}

@Composable
private fun GlobalNameUrlForm(title: String, initialName: String, initialUrl: String?, busy: Boolean, error: String?, close: () -> Unit,
    optionalName: Boolean = false, save: (String, String?) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    ProfileMaintenanceSheet(title, close) {
        LiteField(name, { name = it }, stringResource(R.string.builder_name), enabled = !busy)
        if (initialUrl != null) LiteField(url, { url = it }, stringResource(R.string.global_resources_url), enabled = !busy)
        error?.let { LiteText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        LiteButton(stringResource(R.string.builder_save), { save(name.trim(), initialUrl?.let { url.trim() }) }, enabled = !busy && (optionalName || name.isNotBlank()) && (initialUrl == null || url.isNotBlank()))
    }
}

@Composable
private fun GlobalRuleInfo(rules: GlobalRuleLibraryAccess, snapshot: BuilderResourceSnapshot, name: String) {
    var info by remember(name, snapshot.encoded) { mutableStateOf<GlobalRuleLibraryInfo?>(null) }
    var error by remember(name, snapshot.encoded) { mutableStateOf<String?>(null) }
    LaunchedEffect(name, snapshot.encoded) {
        try { info = rules.info(snapshot, name) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    info?.let { value -> LiteText(listOf(value.behavior, stringResource(if (value.remote) R.string.global_resources_remote else R.string.global_resources_local), value.count?.toString().orEmpty()).filter(String::isNotEmpty).joinToString(" · "), style = LiteType.Caption) }
    error?.let { LiteText(it, style = LiteType.Caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
}

@Composable
private fun GlobalIconPreview(set: BuilderIconSet, loader: GroupIconLoader, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    val icons = set.icons.filter { icon -> terms.all { icon.name.lowercase().contains(it) } }
    BackHandler(onBack = close)
    LitePane(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiteIconButton(stringResource(R.string.global_resources_back), FullGlyphs.arrowBack, close)
            LiteText(set.name, style = LiteType.Title)
        }
        LiteField(query, { query = it }, stringResource(R.string.global_resources_search))
        if (icons.isEmpty()) LiteText(stringResource(R.string.builder_empty))
        LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(icons, key = { it.url }) { icon -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GroupIcon(icon.url, loader, size = 40.dp, contentDescription = icon.name, fallback = { LiteIcon(GlobalResourceGlyphs.photos) })
                LiteText(icon.name, style = LiteType.Caption)
            } }
        }
    } }
}

private fun globalResourceGlyph(kind: BuilderResourceKind): LiteGlyph = when (kind) {
    BuilderResourceKind.Proxy -> FullGlyphs.proxies
    BuilderResourceKind.RuleProvider -> GlobalResourceGlyphs.resources
    BuilderResourceKind.Filter -> GlobalResourceGlyphs.filter
    BuilderResourceKind.IconSet -> GlobalResourceGlyphs.photos
}
private val GlobalSelectGlyph = LiteGlyph(listOf(glyphCircle(12, 12, 8.8)))
private object GlobalResourceGlyphs {
    val resources = LiteGlyph(listOf(glyphBox(3, 3.5, 21, 8.5, 1.8), glyphBox(4.5, 10.8, 19.5, 20.5, 2.2), glyphPolyline(listOf(vertex(10, 14.2), vertex(14, 14.2)), role = LiteGlyphRole.Detail)))
    val filter = LiteGlyph(listOf(glyphPolyline(listOf(vertex(4, 7), vertex(20, 7)), role = LiteGlyphRole.Line), glyphPolyline(listOf(vertex(7, 12), vertex(17, 12)), role = LiteGlyphRole.Line), glyphPolyline(listOf(vertex(10, 17), vertex(14, 17)), role = LiteGlyphRole.Line)))
    val photos = LiteGlyph(listOf(glyphBox(3, 6.4, 17.6, 21, 2.6), glyphPolyline(listOf(vertex(6.4, 3), vertex(21, 3, 2.6), vertex(21, 17.6)), role = LiteGlyphRole.Line), glyphPolyline(listOf(vertex(3, 17.2), vertex(7.8, 12.4, 0.8), vertex(12.2, 16.8, 0.8), vertex(14, 15, 0.8), vertex(17.6, 18.6)), role = LiteGlyphRole.Detail), glyphCircle(13.4, 10.6, 1.4, role = LiteGlyphRole.Detail, solid = true)))
}
