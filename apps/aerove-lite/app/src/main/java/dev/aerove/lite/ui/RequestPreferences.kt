package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.aerove.lite.R
import dev.aerove.lite.requests.RequestPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

private val UserAgentEditGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(4, 20, 1), vertex(8.75, 18.75, 1), vertex(20.25, 7.25, 1.5),
        vertex(16.75, 3.75, 1.5), vertex(5.25, 15.25, 1)), closed = true),
    glyphPolyline(listOf(vertex(13.25, 7.25), vertex(16.75, 10.75)), role = LiteGlyphRole.Detail),
))
private val UserAgentDragGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(4, 7), vertex(20, 7)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(4, 12), vertex(20, 12)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(4, 17), vertex(20, 17)), role = LiteGlyphRole.Line),
))

@Composable
internal fun RequestPreferencesSection(preferences: RequestPreferences, enabled: Boolean,
    onChange: (RequestPreferences) -> Unit, onUserAgents: () -> Unit) {
    Column {
        SectionLabel(stringResource(R.string.requests_title))
        LitePane(Modifier.fillMaxWidth()) {
            Column {
            SettingsRow(stringResource(R.string.requests_user_agent), onClick = { if (enabled) onUserAgents() }) {
                LiteText(preferences.globalUserAgent ?: stringResource(R.string.requests_default), style = LiteType.Caption)
                LiteIcon(FullGlyphs.chevronForward)
            }
            SettingsRow(stringResource(R.string.requests_certificate)) {
                LiteSwitch(preferences.checkCertificate, stringResource(R.string.requests_certificate),
                    { onChange(preferences.copy(checkCertificate = it)) }, enabled)
            }
            }
        }
    }
}

@Composable
internal fun UserAgentPreferencesScreen(preferences: RequestPreferences, onChange: (RequestPreferences) -> Unit,
    padding: PaddingValues = PaddingValues(16.dp), enabled: Boolean = true) {
    val current by rememberUpdatedState(preferences)
    val currentEnabled by rememberUpdatedState(enabled)
    val save by rememberUpdatedState(onChange)
    val overlay = LocalLiteOverlay.current
    val list = LocalLiteListState.current
    val density = LocalDensity.current
    var sorting by rememberSaveable { mutableStateOf(false) }
    var held by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<List<String>?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    val entries = draft ?: preferences.visibleUserAgents()
    val moveUp = stringResource(R.string.requests_move_up)
    val moveDown = stringResource(R.string.requests_move_down)
    fun move(from: Int, to: Int) { if (currentEnabled) save(current.moveUserAgent(from, to)) }
    fun edit(previous: String?) {
        overlay.content = { UserAgentEditDialog(current, currentEnabled, previous, save, overlay::dismiss) }
    }
    fun delete(value: String) {
        overlay.content = { UserAgentDeleteDialog(current, currentEnabled, value, save, overlay::dismiss) }
    }
    fun reorderHeld() {
        val key = held ?: return
        val rows = draft ?: return
        val dragged = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "agent:$key" } ?: return
        val center = origin + distance + dragged.size / 2
        val hit = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key is String && it.key.toString().removePrefix("agent:") in rows && it.key != "agent:$key" && center in it.offset.toFloat()..(it.offset + it.size).toFloat()
        } ?: return
        val from = rows.indexOf(key); val to = rows.indexOf(hit.key.toString().removePrefix("agent:"))
        if (from >= 0 && to >= 0) draft = rows.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun finish(commit: Boolean) {
        val ordered = draft
        if (commit && currentEnabled && ordered != null) {
            val latest = current.visibleUserAgents()
            val next = ordered.filter { it in latest } + latest.filterNot { it in ordered }
            if (next != latest) save(current.copy(userAgents = next))
        }
        held = null; draft = null; distance = 0f
    }
    LaunchedEffect(enabled, sorting) { if (!enabled || !sorting) finish(false) }
    LaunchedEffect(held) {
        snapshotFlow {
            if (held == null) 0f else {
                val center = origin + distance
                val edge = with(density) { 48.dp.toPx() }
                val info = list.layoutInfo
                when { center < info.viewportStartOffset + edge -> -16f
                    center > info.viewportEndOffset - edge -> 16f; else -> 0f }
            }
        }.collectLatest { amount -> if (amount != 0f) while (isActive) { list.scrollBy(amount); reorderHeld(); delay(16) } }
    }
    BackHandler(sorting) { sorting = false }
    LazyColumn(state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("user-agents-list")) {
        item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiteButton(stringResource(R.string.requests_add), { edit(null) }, enabled = enabled, icon = FullGlyphs.add)
                if (entries.size > 1) LiteButton(stringResource(R.string.profile_list_sort), { sorting = !sorting }, selected = sorting, enabled = enabled)
            }
        }
        item("default-agent") {
            LitePane(Modifier.fillMaxWidth().testTag("user-agent-default").semantics { selected = preferences.globalUserAgent == null }) {
                SettingsRow(stringResource(R.string.requests_default), onClick = { if (enabled) save(current.copy(globalUserAgent = null)) }) {
                    if (preferences.globalUserAgent == null) LiteIcon(FullGlyphs.check)
                }
            }
        }
        itemsIndexed(entries, key = { _, value -> "agent:$value" }) { index, value ->
            var bounds by remember(value) { mutableStateOf(Rect.Zero) }
            val dragging = held == value
            LitePane(Modifier.fillMaxWidth().testTag("user-agent-$value").onGloballyPositioned { bounds = it.boundsInWindow() }
                .zIndex(if (dragging) 1f else 0f).graphicsLayer {
                    translationY = if (dragging) origin + distance - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "agent:$value" }?.offset ?: 0) else 0f
                    scaleX = if (dragging) 1.03f else 1f; scaleY = scaleX
                }.semantics {
                    selected = preferences.globalUserAgent == value
                    customActions = listOfNotNull(
                        if (index > 0) CustomAccessibilityAction(moveUp) { move(index, index - 1); true } else null,
                        if (index < entries.lastIndex) CustomAccessibilityAction(moveDown) { move(index, index + 1); true } else null)
                }.combinedClickable(enabled = enabled, onClick = { save(current.copy(globalUserAgent = value)) }, onLongClick = {
                    if (!sorting) overlay.content = { UserAgentContextMenu(bounds, overlay::dismiss, { edit(value) }, { delete(value) }) }
                }), selected = preferences.globalUserAgent == value) {
                SettingsRow(value, trailing = {
                    if (preferences.globalUserAgent == value) LiteIcon(FullGlyphs.check)
                    if (sorting) Box(Modifier.testTag("user-agent-drag-$value").padding(12.dp).pointerInput(value, enabled) {
                        if (enabled) detectDragGestures(onDragStart = {
                            draft = current.visibleUserAgents(); held = value; distance = 0f
                            origin = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "agent:$value" }?.offset?.toFloat() ?: 0f
                        }, onDrag = { change, amount -> change.consume(); distance += amount.y; reorderHeld() },
                            onDragEnd = { finish(true) }, onDragCancel = { finish(false) })
                    }) { LiteIcon(UserAgentDragGlyph) }
                    else LiteIconButton(stringResource(R.string.requests_edit), UserAgentEditGlyph, { edit(value) }, enabled = enabled)
                })
            }
        }
    }
}

@Composable
private fun AwaitUserAgentCommit(expected: RequestPreferences?, current: RequestPreferences, enabled: Boolean,
    close: () -> Unit, failed: () -> Unit) {
    var sawBusy by remember(expected) { mutableStateOf(false) }
    LaunchedEffect(expected, current, enabled) {
        if (expected != null) {
            if (current == expected) close()
            else if (!enabled) sawBusy = true
            else if (sawBusy) failed()
        }
    }
}

@Composable
private fun UserAgentEditDialog(preferences: RequestPreferences, enabled: Boolean, previous: String?,
    save: (RequestPreferences) -> Unit, close: () -> Unit) {
    var value by remember(previous) { mutableStateOf(previous.orEmpty()) }
    var expected by remember { mutableStateOf<RequestPreferences?>(null) }
    val valid = RequestPreferences.validUserAgent(value.trim()) && (value.trim() == previous || value.trim() !in preferences.visibleUserAgents())
    AwaitUserAgentCommit(expected, preferences, enabled, close) { expected = null }
    LiteModal(stringResource(if (previous == null) R.string.requests_add else R.string.requests_edit), close) {
        LiteField(value, { value = it }, stringResource(R.string.requests_user_agent), enabled = enabled && expected == null)
        if (!valid) LiteText(stringResource(R.string.requests_invalid), style = LiteType.Caption)
        LiteButton(stringResource(R.string.requests_save), {
            val next = preferences.editUserAgent(previous, value); expected = next; save(next)
        }, enabled = enabled && expected == null && valid)
    }
}

@Composable
private fun UserAgentDeleteDialog(preferences: RequestPreferences, enabled: Boolean, value: String,
    save: (RequestPreferences) -> Unit, close: () -> Unit) {
    var expected by remember { mutableStateOf<RequestPreferences?>(null) }
    AwaitUserAgentCommit(expected, preferences, enabled, close) { expected = null }
    LiteModal(stringResource(R.string.requests_delete), close) {
        LiteText(stringResource(R.string.requests_delete_confirm)); LiteText(value)
        LiteButton(stringResource(R.string.requests_delete), {
            val next = preferences.deleteUserAgent(value); expected = next; save(next)
        }, enabled = enabled && expected == null, destructive = true)
    }
}

@Composable
private fun UserAgentContextMenu(anchor: Rect, close: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    BackHandler(onBack = close)
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(onClick = close))
        val width = minOf(240.dp, maxWidth - 32.dp)
        val left = (with(density) { anchor.right.toDp() } - width).coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val top = with(density) { anchor.top.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxHeight - 160.dp))
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.absoluteOffset(left, top).width(width).testTag("user-agent-menu").pointerInput(Unit) { detectTapGestures {} },
            corner = LiteCorner.Card, solidColor = fill,
            glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.padding(8.dp)) {
                SettingsRow(stringResource(R.string.requests_edit), onClick = { close(); edit() })
                SettingsRow(stringResource(R.string.requests_delete), onClick = { close(); delete() })
            }
        }
    }
}
