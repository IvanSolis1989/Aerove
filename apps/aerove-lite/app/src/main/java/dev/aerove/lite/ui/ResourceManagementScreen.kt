package dev.aerove.lite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.resources.ManagedResource
import dev.aerove.lite.resources.ResourceManagementSnapshot
import dev.aerove.lite.resources.ResourceManagementRemoteResource
import dev.aerove.lite.resources.GeoResourceEntry
import dev.aerove.lite.resources.GeoResourceKind
import dev.aerove.lite.resources.GeoResourcePreferences

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ResourceManagementScreen(snapshot: ResourceManagementSnapshot?, busy: Set<String>, padding: PaddingValues,
    onRefresh: (String?) -> Unit, onSideload: (ManagedResource) -> Unit, onPreview: (ManagedResource) -> Unit,
    onCancel: () -> Unit, error: String? = null, remoteResources: List<ResourceManagementRemoteResource> = emptyList(),
    onSyncRemote: (ResourceManagementRemoteResource) -> Unit = {}, onOpenRemote: ((ResourceManagementRemoteResource) -> Unit)? = null,
    geoPreferences: GeoResourcePreferences? = null, geoEntries: List<GeoResourceEntry> = emptyList(),
    onGeoChanged: (GeoResourcePreferences) -> Unit = {}, onGeoSync: (GeoResourceKind) -> Unit = {}) {
    val overlay = LocalLiteOverlay.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.profile_list_copied)
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    fun close() { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    DisposableEffect(overlay) { onDispose { close() } }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { LiteButton(stringResource(if (busy.isEmpty()) R.string.proxy_tools_update_all else R.string.proxy_tools_cancel),
            { if (busy.isEmpty()) onRefresh(null) else onCancel() }, Modifier.fillMaxWidth()) }
        if (error != null) item { LiteText(error, Modifier.padding(horizontal = 16.dp), style = LiteType.Caption) }
        if (geoPreferences != null) item { ResourceManagementGeoSection(geoPreferences, geoEntries, busy, onGeoChanged, onGeoSync) }
        items(remoteResources, key = { it.id }) { resource ->
            var anchor by remember(resource.id) { mutableStateOf(Rect.Zero) }
            val working = resource.id in busy || "*" in busy
            fun menu() {
                val content: @Composable () -> Unit = {
                    OverrideContextMenu(anchor, resource.name, ::close) {
                        if (!working) SettingsRow(stringResource(R.string.proxy_tools_sync), icon = FullGlyphs.refresh,
                            onClick = { close(); onSyncRemote(resource) })
                        if (onOpenRemote != null) SettingsRow(stringResource(R.string.proxy_tools_edit),
                            onClick = { close(); onOpenRemote(resource) })
                        SettingsRow(stringResource(R.string.proxy_tools_copy_link), icon = FullGlyphs.copy, onClick = {
                            clipboard.setText(AnnotatedString(resource.url)); close()
                            android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                        })
                    }
                }
                owned[0] = content; overlay.content = content
            }
            LitePane(Modifier.fillMaxWidth().onGloballyPositioned { anchor = it.boundsInRoot() }
                .pointerInput(resource, working, onSyncRemote, onOpenRemote) { detectTapGestures(onLongPress = { menu() }) }
                .semantics { onLongClick { menu(); true } }) {
                SettingsRow(resource.name, resource.updatedAt?.let(::formatTime) ?: stringResource(R.string.proxy_tools_unknown),
                    onClick = onOpenRemote?.let { open -> { open(resource) } }) {
                    Column {
                        resource.bytes?.let { LiteText(stringResource(R.string.proxy_tools_resource_bytes, it), style = LiteType.Caption) }
                        resource.count?.let { LiteText(it.toString(), style = LiteType.Caption) }
                    }
                    LiteIconButton(stringResource(R.string.proxy_tools_sync), FullGlyphs.refresh,
                        { onSyncRemote(resource) }, enabled = !working)
                }
            }
        }
        if (snapshot != null && snapshot.entries.isEmpty() && remoteResources.isEmpty()) item {
            EmptyContent(stringResource(R.string.proxy_tools_resources), stringResource(R.string.proxy_tools_resources_empty), FullGlyphs.tools)
        }
        items(snapshot?.entries.orEmpty().filter { it.replaceable }, key = { it.key }) { resource ->
            val working = resource.key in busy || "*" in busy
            LitePane(Modifier.fillMaxWidth().combinedClickable(enabled = !working,
                onClick = { onPreview(resource) }, onLongClick = {
                    val content: @Composable () -> Unit = {
                        LiteModal(resource.name, ::close) {
                            if (resource.url != null) LiteButton(stringResource(R.string.proxy_tools_sync), { close(); onRefresh(resource.key) }, Modifier.fillMaxWidth())
                            LiteButton(stringResource(R.string.proxy_tools_upload), { close(); onSideload(resource) }, Modifier.fillMaxWidth())
                            LiteButton(stringResource(R.string.proxy_tools_cancel), ::close, Modifier.fillMaxWidth())
                        }
                    }
                    owned[0] = content; overlay.content = content
                })) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiteText(resource.name, style = LiteType.Headline)
                    LiteText(stringResource(R.string.proxy_tools_resource_bytes, resource.bytes), style = LiteType.Caption)
                    LiteText(formatTime(resource.updatedAt), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                    if (resource.sideLoaded) LiteText(stringResource(R.string.proxy_tools_local_replacement), style = LiteType.Caption)
                    if (working) LiteText(stringResource(R.string.proxy_tools_working), style = LiteType.Caption)
                }
            }
        }
    }
}
