package dev.aerove.lite.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.integration.ResourcePageWorkspace
import dev.aerove.lite.resources.scriptResourceItems

@Composable
internal fun ResourcePage(state: LiteState, actions: LiteActions, workspace: ResourcePageWorkspace,
    padding: PaddingValues) {
    val data by workspace.session.state.collectAsState()
    val operation by workspace.state.collectAsState()
    val scripts by workspace.scripts.state.collectAsState()
    val active = LocalLitePageActive.current
    LaunchedEffect(active) { if (active) workspace.load() }
    val error = stringResource(R.string.editor_error)
    // Standard ResourcesView lists global assets; profile providers belong to ProfileDetailSheet.
    ResourceManagementScreen(null,
        data.busy + (if (operation.updating) setOf("*") else emptySet()) + scripts.busyIds.map { "script:$it" }, padding,
        onRefresh = { key -> check(key == null); workspace.updateAll() },
        onSideload = { kotlin.error("Global resources do not expose profile-provider upload") },
        onPreview = { kotlin.error("Global resources do not expose profile-provider preview") },
        onCancel = workspace::cancel,
        error = if (data.failure != null) error else operation.failures.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        remoteResources = data.library + scriptResourceItems(scripts.scripts), onSyncRemote = workspace::sync,
        geoPreferences = state.settings.geoResources, geoEntries = data.geo,
        onGeoChanged = { actions.changeSettings(state.settings.copy(geoResources = it)) }, onGeoSync = workspace.session::syncGeo)
}
