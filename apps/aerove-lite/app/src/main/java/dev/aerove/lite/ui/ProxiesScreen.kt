package dev.aerove.lite.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.integration.ProxyPresentationWorkspace
import dev.aerove.lite.proxies.proxyDelayTargets
import dev.aerove.lite.proxies.proxySearch

@Composable
internal fun ProxiesScreen(state: LiteState, actions: LiteActions, padding: PaddingValues,
    workspace: ProxyPresentationWorkspace, query: String, locateRequest: Int) {
    val presentation by workspace.state.collectAsState()
    val active = LocalLitePageActive.current && LocalDiagnosticsForeground.current
    DisposableEffect(workspace, active) { workspace.active(active); onDispose { workspace.active(false) } }
    val byName = remember(state.groups) { state.groups.associateBy { it.name } }
    val testing = remember(state.groups, state.settings.proxyPresentation, query, presentation.groupUrls, presentation.delay.pending) {
        val matches = proxySearch(query)
        state.groups.filter { group -> proxyDelayTargets(group, byName, state.settings.proxyPresentation,
            presentation.groupUrls, matches).any { it in presentation.delay.pending } }.map { it.name }.toSet()
    }
    val iconLoader = if (presentation.groupIcons.isNotEmpty()) LocalLiteFeatures.current().groupIcons else null
    ProxyGroupsPresentation(state.groups, state.settings.proxyPresentation, query, presentation.delay.results,
        presentation.groupUrls, presentation.currentGroup, presentation.unfolded, testing,
        active && presentation.ready && state.busy.isEmpty(), padding,
        workspace::selectGroup, workspace::toggleGroup, actions::selectProxy,
        { workspace.testGroup(it, query) }, workspace::testNode, {}, groupIcon = iconLoader?.let { loader -> { group ->
            GroupIcon(presentation.groupIcons[group.name], loader, Modifier.testTag("proxy-group-icon-${group.name}"), active = active,
                fallback = { LiteText(proxyGroupMark(group.name), style = LiteType.Headline, maxLines = 1) })
        } }, locateRequest = locateRequest,
        pendingTargets = presentation.delay.pending, runningTargets = presentation.delay.running)
}
