package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.overrides.OverrideRule
import dev.aerove.lite.overrides.OverrideRuleDraft
import java.util.UUID

/** Both rule editors use Standard's select-control + row edit + long-press reorder interaction. */
@Composable
internal fun OverrideRulesList(entries: List<OverrideRule>, onChange: (List<OverrideRule>) -> Unit,
    padding: PaddingValues, enabled: Boolean = true, searchable: Boolean = false,
    header: LazyListScope.() -> Unit = {}, footer: LazyListScope.() -> Unit = {}) {
    val list = LocalLiteListState.current
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    val current by rememberUpdatedState(entries)
    val latestChange by rememberUpdatedState(onChange)
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    val byId = remember(entries) { entries.associateBy { it.id } }
    val ids = remember(entries) { entries.map { it.id } }
    val visible = remember(entries, query) { if (query.isBlank()) entries else entries.filter { it.value.contains(query.trim(), ignoreCase = true) } }
    val visibleIds = remember(visible) { visible.map { it.id } }
    val drag = rememberOverrideDrag(ids, list, enabled && query.isBlank() && selectedIds.isEmpty())
    val shownKeys = if (query.isBlank()) drag.keys else visibleIds
    val up = stringResource(R.string.override_up); val down = stringResource(R.string.override_down)
    fun close() { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    fun show(content: @Composable () -> Unit) { owned[0] = content; overlay.content = content }
    fun edit(entry: OverrideRule?) {
        show { OverrideRuleForm(entry?.value, ::close, { raw ->
            val next = if (entry == null) current + OverrideRule(UUID.randomUUID().toString(), raw)
                else current.map { if (it.id == entry.id) it.copy(value = raw) else it }
            latestChange(next); close()
        }, enabled) }
    }
    fun move(id: String, destination: Int) {
        val from = current.indexOfFirst { it.id == id }
        if (from >= 0 && destination in current.indices && from != destination)
            latestChange(current.toMutableList().apply { add(destination, removeAt(from)) })
    }
    fun deleteSelected() {
        val ids = visible.map { it.id }.filter { it in selectedIds }.toSet()
        if (ids.isEmpty()) return
        show { LiteModal(stringResource(R.string.override_delete), ::close) {
            LiteText(stringResource(R.string.override_delete_rules_confirm, ids.size))
            LiteButton(stringResource(R.string.override_delete), {
                latestChange(current.filterNot { it.id in ids }); selectedIds -= ids; close()
            }, Modifier.testTag("override-confirm-delete"), enabled = enabled, destructive = true)
            LiteButton(stringResource(R.string.override_cancel), ::close)
        } }
    }
    BackHandler(selectedIds.isNotEmpty() && overlay.content == null) { selectedIds = emptySet() }
    DisposableEffect(overlay) { onDispose { close() } }
    LaunchedEffect(entries) { selectedIds = selectedIds.intersect(entries.map { it.id }.toSet()) }
    LazyColumn(state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(0.dp), userScrollEnabled = drag.dragging == null) {
        header()
        if (searchable) item(key = OverrideListKey.Search) {
            LiteField(query, { query = it }, stringResource(R.string.override_search), Modifier.padding(bottom = 12.dp).testTag("override-rule-search"), enabled = enabled)
        }
        item(key = OverrideListKey.Actions) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedIds.isEmpty()) {
                    LiteButton(stringResource(R.string.override_add_rule), { edit(null) }, Modifier.testTag("override-add-rule"), enabled = enabled, icon = FullGlyphs.add)
                    LiteButton(stringResource(R.string.override_presets), { show { OverridePresetSheet(::close) { values ->
                        latestChange(current + values.map { OverrideRule(UUID.randomUUID().toString(), it) })
                    } } }, enabled = enabled)
                } else {
                    LiteButton(stringResource(R.string.override_select_all), {
                        val ids = visible.map { it.id }.toSet(); selectedIds = if (selectedIds.containsAll(ids)) emptySet() else ids
                    }, Modifier.testTag("override-select-all"), enabled = enabled)
                    LiteButton(stringResource(R.string.override_delete), ::deleteSelected, Modifier.testTag("override-delete-selected"),
                        enabled = enabled && visible.any { it.id in selectedIds }, destructive = true)
                    LiteButton(stringResource(R.string.override_done), { selectedIds = emptySet() }, Modifier.testTag("override-selection-done"))
                }
            }
        }
        itemsIndexed(shownKeys, key = { _, id -> id }) { index, id ->
            val entry = byId[id] ?: return@itemsIndexed
            val draft = remember(entry.value) { OverrideRuleDraft.parse(entry.value) }
            val selected = entry.id in selectedIds
            val dragEnabled = enabled && query.isBlank() && selectedIds.isEmpty() && drag.accepting
            LitePane(Modifier.fillMaxWidth().overrideDragVisual(drag, entry.id)
                .overrideDragGesture(drag, entry.id, dragEnabled, commit = ::move)
                .then(if (enabled && query.isBlank() && selectedIds.isEmpty()) Modifier.overrideMoveAccessibility(index, shownKeys.size, up, down) { move(entry.id, it) } else Modifier)
                .testTag("override-rule-row-$index").semantics { this.selected = selected }
                .clickable(enabled = enabled) {
                    if (selectedIds.isNotEmpty()) selectedIds = if (selected) selectedIds - entry.id else selectedIds + entry.id else edit(entry)
                }, selected = selected) {
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteIconButton(stringResource(if (selected) R.string.override_deselect_rule else R.string.override_select_rule, index + 1), if (selected) FullGlyphs.check else OverrideSelectGlyph,
                        { selectedIds = if (selected) selectedIds - entry.id else selectedIds + entry.id },
                        Modifier.testTag("override-rule-select-$index"), enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        LiteText(draft.action, style = LiteType.Body, maxLines = 1)
                        LiteText(draft.content, style = LiteType.Caption, maxLines = 1, color = LocalLiteVisuals.current.secondaryInk)
                    }
                    LiteText(draft.target, Modifier.widthIn(max = 144.dp), style = LiteType.Body, maxLines = 1)
                }
            }
        }
        footer()
    }
}

internal fun profileOverrideRules(values: List<String>): List<OverrideRule> {
    val occurrences = mutableMapOf<String, Int>()
    return values.map { value -> val occurrence = occurrences.getOrDefault(value, 0); occurrences[value] = occurrence + 1
        OverrideRule("profile:${value.length}:$value:$occurrence", value) }
}

private val OverrideSelectGlyph = LiteGlyph(listOf(glyphCircle(12, 12, 8.5, role = LiteGlyphRole.Line)))
private enum class OverrideListKey { Search, Actions }
