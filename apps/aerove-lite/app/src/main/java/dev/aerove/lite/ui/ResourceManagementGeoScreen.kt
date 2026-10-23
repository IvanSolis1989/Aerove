package dev.aerove.lite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.resources.GeoResourceEntry
import dev.aerove.lite.resources.GeoResourceKind
import dev.aerove.lite.resources.GeoResourcePreferences

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ResourceManagementGeoSection(preferences: GeoResourcePreferences, entries: List<GeoResourceEntry>, busy: Set<String>,
    onChanged: (GeoResourcePreferences) -> Unit, onSync: (GeoResourceKind) -> Unit) {
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    val current by rememberUpdatedState(preferences)
    val change by rememberUpdatedState(onChanged)
    val clipboard = LocalClipboardManager.current
    fun close() { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    fun open(content: @Composable () -> Unit) { owned[0] = content; overlay.content = content }
    DisposableEffect(overlay) { onDispose { close() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LitePane(Modifier.fillMaxWidth()) { Column {
            SettingsRow(stringResource(R.string.proxy_tools_auto_update)) {
                LiteSwitch(preferences.autoUpdate, stringResource(R.string.proxy_tools_auto_update), { change(current.copy(autoUpdate = it)) })
            }
            if (preferences.autoUpdate) SettingsRow(stringResource(R.string.proxy_tools_update_interval),
                stringResource(R.string.proxy_tools_hours, preferences.intervalHours), onClick = {
                    open { GeoIntervalEditor(current, change, ::close) }
                }) { LiteIcon(FullGlyphs.chevronForward) }
        } }
        SectionLabel(stringResource(R.string.proxy_tools_geo_resources))
        GeoResourceKind.entries.forEach { kind ->
            val entry = entries.singleOrNull { it.kind == kind }
            val active = "geo:${kind.wire}" in busy || "*" in busy
            fun edit() { open { GeoUrlEditor(kind, current, change, ::close) } }
            LitePane(Modifier.fillMaxWidth().combinedClickable(enabled = !active, onClick = ::edit, onLongClick = {
                open {
                    LiteModal(kind.wire.uppercase(java.util.Locale.ROOT), ::close) {
                        LiteButton(stringResource(R.string.proxy_tools_sync), { close(); onSync(kind) }, Modifier.fillMaxWidth())
                        LiteButton(stringResource(R.string.proxy_tools_edit), ::edit, Modifier.fillMaxWidth())
                        LiteButton(stringResource(R.string.proxy_tools_copy_link), { clipboard.setText(AnnotatedString(current.urls.getValue(kind))); close() }, Modifier.fillMaxWidth())
                        LiteButton(stringResource(R.string.proxy_tools_cancel), ::close, Modifier.fillMaxWidth())
                    }
                }
            })) {
                SettingsRow(kind.wire.uppercase(java.util.Locale.ROOT), entry?.saved?.updatedAt?.let(::formatTime)
                    ?: stringResource(R.string.proxy_tools_unknown)) {
                    entry?.saved?.bytes?.let { LiteText(formatBytes(it), style = LiteType.Caption) }
                    if (active) LiteText(stringResource(R.string.proxy_tools_working), style = LiteType.Caption)
                    else LiteIconButton(stringResource(R.string.proxy_tools_sync), FullGlyphs.refresh, { onSync(kind) })
                }
            }
        }
    }
}

@Composable
private fun GeoUrlEditor(kind: GeoResourceKind, value: GeoResourcePreferences, onChanged: (GeoResourcePreferences) -> Unit, close: () -> Unit) {
    var url by rememberSaveable(kind, value.urls.getValue(kind)) { mutableStateOf(value.urls.getValue(kind)) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    LiteModal(kind.wire.uppercase(java.util.Locale.ROOT), close) {
        LiteField(url, { url = it; invalid = false }, stringResource(R.string.proxy_tools_resource_url))
        if (invalid) LiteText(stringResource(R.string.proxy_tools_invalid_geo_url), style = LiteType.Caption)
        LiteButton(stringResource(R.string.proxy_tools_reset), { url = kind.defaultUrl; invalid = false }, Modifier.fillMaxWidth())
        LiteButton(stringResource(R.string.proxy_tools_save), {
            val changed = runCatching { value.withUrl(kind, url.trim()) }.getOrNull()
            if (changed == null) invalid = true else { onChanged(changed); close() }
        }, Modifier.fillMaxWidth())
        LiteButton(stringResource(R.string.proxy_tools_cancel), close, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GeoIntervalEditor(value: GeoResourcePreferences, onChanged: (GeoResourcePreferences) -> Unit, close: () -> Unit) {
    var hours by rememberSaveable(value.intervalHours) { mutableStateOf(value.intervalHours.toString()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    LiteModal(stringResource(R.string.proxy_tools_update_interval), close) {
        LiteField(hours, { hours = it; invalid = false }, stringResource(R.string.proxy_tools_update_interval), keyboardType = KeyboardType.Number)
        if (invalid) LiteText(stringResource(R.string.proxy_tools_invalid_geo_interval), style = LiteType.Caption)
        LiteButton(stringResource(R.string.proxy_tools_save), {
            val parsed = hours.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }
            if (parsed == null) invalid = true else { onChanged(value.copy(intervalHours = parsed)); close() }
        }, Modifier.fillMaxWidth())
        LiteButton(stringResource(R.string.proxy_tools_cancel), close, Modifier.fillMaxWidth())
    }
}
