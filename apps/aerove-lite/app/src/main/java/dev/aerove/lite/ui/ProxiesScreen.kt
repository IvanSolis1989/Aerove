package dev.aerove.lite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.R

@Composable
internal fun ProxiesScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.groups.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_proxies), stringResource(R.string.lite_no_proxies_detail), FullGlyphs.proxies)
        }
        items(state.groups, key = { it.name }) { group -> ProxyGroupCard(group, state, actions) }
    }
}

@Composable
private fun ProxyGroupCard(group: ProxyGroup, state: LiteState, actions: LiteActions) {
    var expanded by rememberSaveable(group.name) { mutableStateOf(true) }
    val selectable = group.type.equals("Selector", true) || group.type.equals("select", true)
    val working = state.busy.isNotEmpty()
    LitePane(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    LiteText(group.name, style = LiteType.Headline)
                    LiteText(group.selected, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                }
                LiteIconButton(
                    stringResource(R.string.lite_test_group), FullGlyphs.speed,
                    { actions.testGroup(group.name) }, enabled = !working,
                )
                LiteIcon(if (expanded) FullGlyphs.chevronUp else FullGlyphs.chevronDown)
            }
            AnimatedVisibility(expanded) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!selectable) item { LiteText(
                        stringResource(R.string.lite_auto_group), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk,
                    ) }
                    if (group.nodes.isEmpty()) item { LiteText(stringResource(R.string.lite_group_empty), Modifier.padding(8.dp)) }
                    items(group.nodes, key = { it.name }) { node ->
                        val selected = node.name == group.selected
                        val interaction = if (selectable) Modifier.selectable(selected, enabled = !working, role = Role.RadioButton) {
                            actions.selectProxy(group.name, node.name)
                        } else Modifier
                        LitePane(Modifier.fillMaxWidth().then(interaction), corner = LiteCorner.Control, selected = selected) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    LiteText(node.name, style = LiteType.Body)
                                    LiteText(node.type, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                                }
                                LiteText(
                                    when {
                                        node.delayMillis == null -> stringResource(R.string.lite_latency_unknown)
                                        node.delayMillis < 0 -> stringResource(R.string.lite_timeout)
                                        else -> stringResource(R.string.lite_latency, node.delayMillis)
                                    },
                                    style = LiteType.Caption,
                                    color = LocalLiteVisuals.current.secondaryInk,
                                )
                                if (selected) LiteIcon(FullGlyphs.check, color = LocalLiteVisuals.current.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
