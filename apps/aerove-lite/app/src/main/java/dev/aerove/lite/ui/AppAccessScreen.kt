package dev.aerove.lite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.appaccess.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppAccessScreen(feature: AppAccessFeature, state: AppAccessState, padding: PaddingValues,
    onBack: () -> Unit, onAuthorize: () -> Unit, registerBackHandler: ((() -> Unit)?) -> Unit,
    active: Boolean = true) {
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    val latestBack by rememberUpdatedState(onBack)
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val view by produceState<List<InstalledApplication>>(emptyList(), state.applications, state.draft, state.pinned, state.query) {
        value = withContext(Dispatchers.Default) { viewApplications(state.applications, state.draft, state.pinned, state.query) }
    }
    val leave: () -> Unit = {
        if (!current.saving) {
            feature.cancelSmartSelection()
            if (!current.dirty) latestBack()
            else overlay.content = {
                LiteModal(stringResource(R.string.access_tip), overlay::dismiss) {
                    LiteText(stringResource(R.string.access_save_changes))
                    LiteButton(stringResource(R.string.access_save), { overlay.dismiss(); feature.save { latestBack() } })
                    LiteButton(stringResource(R.string.access_discard), { feature.resetDraft(); overlay.dismiss(); latestBack() })
                    LiteButton(stringResource(R.string.access_cancel), overlay::dismiss)
                }
            }
        }
    }
    val latestLeave by rememberUpdatedState(leave)
    DisposableEffect(feature, registerBackHandler) {
        registerBackHandler { latestLeave() }
        onDispose { registerBackHandler(null) }
    }
    BackHandler { latestLeave() }
    LaunchedEffect(feature, active) { feature.setForeground(active) }
    DisposableEffect(feature) { onDispose { feature.setForeground(false) } }
    val enabled = !state.saving && !state.classifying && state.permissionGranted
    val visibleNames = view.map { it.packageName }.toSet()
    val allSelected = state.draft.selected.containsAll(visibleNames)
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val showMenu: () -> Unit = {
        overlay.content = {
            AppAccessMenu(anchor, current, overlay::dismiss,
                change = feature::change, smart = { overlay.dismiss(); feature.intelligentSelect() },
                export = {
                    // Android clipboard is touched only from this explicit user action.
                    clipboard.setPrimaryClip(ClipData.newPlainText("application packages", feature.exportClipboard()))
                    feature.copied(); overlay.dismiss()
                },
                import = {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) feature.importClipboard(clip.getItemAt(0).coerceToText(context).toString())
                    overlay.dismiss()
                })
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiteField(state.query, feature::query, stringResource(R.string.access_search), Modifier.weight(1f))
            LiteIconButton(stringResource(R.string.access_settings), FullGlyphs.settings, showMenu,
                Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }, enabled = !state.saving && !state.classifying)
            if (state.draft.enabled && visibleNames.isNotEmpty()) LiteIconButton(
                stringResource(if (allSelected) R.string.access_deselect_all else R.string.access_select_all),
                if (allSelected) AccessSelectionGlyphs.deselect else AccessSelectionGlyphs.selectAll,
                { feature.toggleVisible(visibleNames) }, enabled = enabled)
            if (state.dirty) LiteIconButton(stringResource(R.string.access_save), FullGlyphs.check, { feature.save() }, enabled = !state.saving && !state.classifying && !state.loading)
        }
        if (!state.permissionGranted) {
            EmptyContent(stringResource(R.string.access_permission_required), stringResource(R.string.access_permission_description), FullGlyphs.appsList) {
                LiteButton(stringResource(R.string.access_authorize), onAuthorize)
            }
        } else {
            LazyColumn(state = LocalLiteListState.current, modifier = Modifier.weight(1f), contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (!state.loaded && state.loading) item { LiteText(stringResource(R.string.access_loading), Modifier.padding(16.dp)) }
                else if (view.isEmpty()) item { EmptyContent(stringResource(R.string.access_no_data), stringResource(R.string.access_search), FullGlyphs.appsList) }
                else items(view, key = { it.packageName }) { application ->
                    AppAccessApplicationRow(application, application.packageName in state.draft.selected, enabled && state.draft.enabled,
                        feature, active, { feature.toggle(application.packageName) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.draft.enabled) {
                    LiteText(stringResource(if (state.draft.mode == AppAccessMode.Include) R.string.access_allow_description else R.string.access_exclude_description),
                        Modifier.weight(1f), style = LiteType.Caption)
                    LiteText(stringResource(R.string.access_selected, state.draft.selected.intersect(visibleNames).size), style = LiteType.Caption)
                } else {
                    LiteText(stringResource(R.string.access_disabled_description), Modifier.weight(1f), style = LiteType.Caption)
                    LiteButton(stringResource(R.string.access_turn_on), { feature.change(state.draft.copy(enabled = true)) }, enabled = enabled)
                }
            }
        }
        state.notice?.let { notice -> LiteText(stringResource(when (notice) {
            AppAccessNotice.LoadFailed -> R.string.access_load_failed
            AppAccessNotice.SaveFailed -> R.string.access_save_failed
            AppAccessNotice.ClassificationFailed -> R.string.access_classification_failed
            AppAccessNotice.InvalidClipboard -> R.string.access_invalid_clipboard
            AppAccessNotice.Copied -> R.string.access_copied
        }), Modifier.padding(16.dp), style = LiteType.Caption) }
        if (state.classifying || state.saving) LiteText(stringResource(R.string.access_loading), Modifier.padding(16.dp))
    }
}

@Composable
private fun AppAccessApplicationRow(application: InstalledApplication, selected: Boolean, enabled: Boolean,
    feature: AppAccessFeature, active: Boolean, toggle: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, application.packageName, application.lastUpdateTime, active) {
        if (active) value = feature.icon(application.packageName)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(selected, enabled, Role.Checkbox) { toggle() }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp)) }
            ?: LiteIcon(FullGlyphs.appsList, Modifier.size(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LiteText(application.label)
            LiteText(application.packageName, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        }
        if (selected) LiteIcon(FullGlyphs.check)
    }
}

@Composable
private fun AppAccessMenu(anchor: Rect, state: AppAccessState, dismiss: () -> Unit,
    change: (AppAccessPreferences) -> Unit, smart: () -> Unit, export: () -> Unit, import: () -> Unit) {
    var section by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val title = stringResource(R.string.access_settings)
    BackHandler { if (section == 0) dismiss() else section = 0 }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(onClick = dismiss))
        val width = minOf(300.dp, maxWidth - 32.dp)
        val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
        val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
        val left = (with(density) { anchor.right.toDp() } - width).coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val height = (maxHeight - safeTop - safeBottom - 32.dp).coerceAtLeast(0.dp)
        val top = with(density) { anchor.top.toDp() }.coerceIn(safeTop + 16.dp, maxOf(safeTop + 16.dp, maxHeight - safeBottom - minOf(height, 440.dp) - 16.dp))
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.absoluteOffset(left, top).width(width).pointerInput(Unit) { detectTapGestures {} },
            corner = LiteCorner.Card, solidColor = fill,
            glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()).padding(8.dp).semantics { paneTitle = title }) {
                fun apply(value: AppAccessPreferences) { change(value); dismiss() }
                if (section != 0) SettingsRow(stringResource(R.string.access_back), onClick = { section = 0 })
                when (section) {
                    0 -> {
                        AccessMenuChoice(stringResource(R.string.access_turn_on), state.draft.enabled) { apply(state.draft.copy(enabled = true)) }
                        AccessMenuChoice(stringResource(R.string.access_turn_off), !state.draft.enabled) { apply(state.draft.copy(enabled = false)) }
                        SettingsRow(stringResource(R.string.access_settings), onClick = { section = 1 })
                        SettingsRow(stringResource(R.string.access_actions), onClick = { section = 2 })
                    }
                    1 -> {
                        SectionLabel(stringResource(R.string.access_mode))
                        AppAccessMode.entries.forEach { mode -> AccessMenuChoice(stringResource(if (mode == AppAccessMode.Include) R.string.access_include else R.string.access_exclude), mode == state.draft.mode) { apply(state.draft.copy(mode = mode)) } }
                        SectionLabel(stringResource(R.string.access_sort))
                        AppAccessSort.entries.forEach { sort -> AccessMenuChoice(stringResource(when (sort) {
                            AppAccessSort.None -> R.string.access_default
                            AppAccessSort.Name -> R.string.access_name
                            AppAccessSort.Time -> R.string.access_time
                        }), sort == state.draft.sort) { apply(state.draft.copy(sort = sort)) } }
                        SectionLabel(stringResource(R.string.access_source))
                        AccessMenuChoice(stringResource(R.string.access_system_apps), !state.draft.hideSystem) { apply(state.draft.copy(hideSystem = !state.draft.hideSystem)) }
                        AccessMenuChoice(stringResource(R.string.access_no_internet), !state.draft.hideNoInternet) { apply(state.draft.copy(hideNoInternet = !state.draft.hideNoInternet)) }
                    }
                    2 -> {
                        SettingsRow(stringResource(R.string.access_smart), onClick = smart)
                        SettingsRow(stringResource(R.string.access_clipboard_export), onClick = export)
                        SettingsRow(stringResource(R.string.access_clipboard_import), onClick = import)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessMenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.semantics { this.selected = selected }) {
        SettingsRow(label, onClick = onClick) { if (selected) LiteIcon(FullGlyphs.check) }
    }
}

/** Exact Standard selectAll/deselect glyph vertices, using Lite's existing glyph renderer. */
private object AccessSelectionGlyphs {
    private val frame = listOf(
        glyphPolyline(listOf(vertex(3.5, 7), vertex(3.5, 3.5, 2.5), vertex(7, 3.5)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(20.5, 7), vertex(20.5, 3.5, 2.5), vertex(17, 3.5)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(20.5, 17), vertex(20.5, 20.5, 2.5), vertex(17, 20.5)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(3.5, 17), vertex(3.5, 20.5, 2.5), vertex(7, 20.5)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(10.6, 3.5), vertex(13.4, 3.5)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(20.5, 10.6), vertex(20.5, 13.4)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(13.4, 20.5), vertex(10.6, 20.5)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(3.5, 13.4), vertex(3.5, 10.6)), role = LiteGlyphRole.Line),
    )
    val selectAll = LiteGlyph(frame + glyphPolyline(listOf(vertex(8, 12.3), vertex(10.8, 15.1, 0.4), vertex(16, 9.2)), role = LiteGlyphRole.Line))
    val deselect = LiteGlyph(frame + glyphPolyline(listOf(vertex(8, 8), vertex(16, 16)), role = LiteGlyphRole.Line))
}
