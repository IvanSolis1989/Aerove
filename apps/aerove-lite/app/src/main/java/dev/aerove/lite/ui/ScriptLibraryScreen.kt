package dev.aerove.lite.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.strictUtf8
import dev.aerove.lite.overrides.OverrideRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.overrides.ScriptLibraryState
import dev.aerove.lite.overrides.ScriptResource
import dev.aerove.lite.data.SubscriptionDownloader
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
internal fun ScriptLibraryScreen(state: ScriptLibraryState, onPut: (ScriptResource) -> Unit,
    onEdit: (ScriptResource) -> Unit, onDelete: (String) -> Unit, onMove: (String, Int) -> Unit,
    onUpdate: (String) -> Unit, onCancel: (String) -> Unit, onImport: (String, String) -> Unit,
    padding: PaddingValues = PaddingValues(16.dp)) {
    val overlay = LocalLiteOverlay.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sorting by remember { mutableStateOf(false) }
    val list = LocalLiteListState.current
    val latestMove by rememberUpdatedState(onMove)
    val latestState by rememberUpdatedState(state)
    val scriptIds = remember(state.scripts) { state.scripts.map { it.id } }
    val scriptById = remember(state.scripts) { state.scripts.associateBy { it.id } }
    val drag = rememberOverrideDrag(scriptIds, list, sorting && state.busyIds.isEmpty())
    val up = stringResource(R.string.override_up); val down = stringResource(R.string.override_down)
    var importFailed by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.substringBeforeLast('.')?.take(180) ?: "script"
                    val content = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBounded(OverrideRepository.MAX_SCRIPT_BYTES).strictUtf8() }
                    name to content
                }
                onImport(imported.first, imported.second)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { importFailed = true }
        }
    }
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    fun close() { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    fun show(content: @Composable () -> Unit) { owned[0] = content; overlay.content = content }
    fun properties(script: ScriptResource?) { show { ScriptPropertiesForm(script, latestState, ::close, onPut) } }
    DisposableEffect(overlay) { onDispose { close() } }
    LazyColumn(state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = drag.dragging == null) {
        item { LiteButton(stringResource(R.string.override_add_script), { properties(null) }, Modifier.fillMaxWidth(), icon = FullGlyphs.add) }
        item { LiteButton(stringResource(R.string.override_import_file), { importFailed = false; importFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) }
        if (state.scripts.size > 1) item { LiteButton(stringResource(if (sorting) R.string.override_done else R.string.override_sort),
            { sorting = !sorting }, Modifier.testTag("override-script-sort"), selected = sorting, enabled = state.busyIds.isEmpty()) }
        if (importFailed) item { LiteText(stringResource(R.string.override_import_failed), style = LiteType.Caption) }
        if (state.errorCode != null) item { LiteText(stringResource(if (state.errorCode == "apply_failed") R.string.override_script_failed else R.string.override_update_failed), style = LiteType.Caption) }
        if (state.scripts.isEmpty()) item { EmptyContent(stringResource(R.string.override_scripts), stringResource(R.string.override_scripts_empty), ScriptCodeGlyph) }
        itemsIndexed(drag.keys, key = { _, id -> id }) { index, id ->
            val script = scriptById[id] ?: return@itemsIndexed
            val busy = script.id in state.busyIds
            var bounds by remember(script.id) { mutableStateOf(Rect.Zero) }
            LitePane(Modifier.fillMaxWidth().overrideDragVisual(drag, script.id)
                .overrideMoveAccessibility(index, drag.keys.size, up, down) { if (!busy) latestMove(script.id, it) }
                .onGloballyPositioned { bounds = it.boundsInWindow() }.testTag("override-script-row-$index")
                .combinedClickable(onClick = { if (!busy && !sorting) onEdit(script) }, onLongClick = {
                if (!sorting) {
                show {
                    OverrideContextMenu(bounds, script.name, ::close) {
                        if (!busy) SettingsRow(stringResource(R.string.override_edit), onClick = { close(); onEdit(script) })
                        if (!busy) SettingsRow(stringResource(R.string.override_properties), onClick = { properties(script) })
                        if (script.url != null) SettingsRow(stringResource(if (busy) R.string.override_cancel else R.string.override_update), onClick = { close(); if (busy) onCancel(script.id) else onUpdate(script.id) })
                        if (script.url != null) SettingsRow(stringResource(R.string.override_copy_url), onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(script.name, script.url)); close()
                        })
                        if (!busy) SettingsRow(stringResource(R.string.override_delete), onClick = {
                            show { LiteModal(stringResource(R.string.override_delete), ::close) {
                                LiteText(stringResource(R.string.override_delete_confirm))
                                LiteButton(stringResource(R.string.override_delete), { close(); onDelete(script.id) }, destructive = true)
                            } }
                        })
                    }
                }
                }
            })) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiteText(script.name, style = LiteType.Headline)
                    LiteText(stringResource(R.string.override_script_info, script.byteSize, DateFormat.getDateTimeInstance().format(Date(script.updatedAt))), style = LiteType.Caption)
                    LiteText(stringResource(if (script.url == null) R.string.override_local else R.string.override_remote), style = LiteType.Caption)
                    if (busy) LiteButton(stringResource(R.string.override_cancel), { onCancel(script.id) })
                }
                if (sorting) {
                    val description = stringResource(R.string.override_drag_item, script.name)
                    Box(Modifier.size(48.dp).testTag("override-script-handle-$index").semantics { contentDescription = description }
                        .overrideDragGesture(drag, script.id, !busy && drag.accepting, immediate = true) { id, destination -> latestMove(id, destination) }, contentAlignment = Alignment.Center) {
                        LiteIcon(OverrideDragGlyph)
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ScriptPropertiesForm(script: ScriptResource?, state: ScriptLibraryState, dismiss: () -> Unit, save: (ScriptResource) -> Unit) {
    var name by remember(script?.id) { mutableStateOf(script?.name.orEmpty()) }
    var url by remember(script?.id) { mutableStateOf(script?.url.orEmpty()) }
    val id = remember(script?.id) { script?.id ?: UUID.randomUUID().toString() }
    var pending by remember(id) { mutableStateOf<ScriptResource?>(null) }
    var baseline by remember(id) { mutableStateOf(0L) }
    var failed by remember(id) { mutableStateOf(false) }
    val receipt = state.saves[id]
    LaunchedEffect(state.scripts, receipt, pending) {
        val expected = pending ?: return@LaunchedEffect
        if (state.scripts.any { it.id == expected.id && it.name == expected.name && it.url == expected.url }) dismiss()
        else if (receipt != null && receipt.serial > baseline && !receipt.busy) { pending = null; failed = true }
    }
    val validUrl = url.isBlank() || runCatching { SubscriptionDownloader.parseUrl(url.trim()) }.isSuccess
    ProfileMaintenanceSheet(stringResource(R.string.override_properties), dismiss) {
        LiteField(name, { name = it }, stringResource(R.string.override_name))
        LiteField(url, { url = it }, stringResource(R.string.override_url))
        if (!validUrl) LiteText(stringResource(R.string.override_invalid_url), style = LiteType.Caption)
        if (failed) LiteText(stringResource(R.string.override_update_failed), style = LiteType.Caption)
        LiteButton(stringResource(R.string.override_save), {
            val candidate = (script ?: ScriptResource(id, name.trim(), content = "function main(config) {\n  return config;\n}\n"))
                .copy(name = name.trim(), url = url.trim().takeIf { it.isNotEmpty() })
            if (candidate == script) dismiss() else {
                baseline = receipt?.serial ?: 0; pending = candidate; failed = false; save(candidate)
            }
        }, enabled = pending == null && name.isNotBlank() && name.length <= 200 && validUrl)
    }
}
