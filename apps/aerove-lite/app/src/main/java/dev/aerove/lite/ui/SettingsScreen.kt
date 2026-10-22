package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.ColorMode
import dev.aerove.lite.InstalledApp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    val enabled = state.busy.isEmpty()
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionLabel(stringResource(R.string.lite_appearance)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(R.string.lite_material), style = LiteType.Headline)
                    LiteChoiceRow(
                        MaterialTier.entries, state.settings.material, { materialLabel(it) },
                        { actions.changeSettings(state.settings.copy(material = it)) }, enabled = enabled,
                    )
                    LiteText(stringResource(R.string.lite_color_mode), style = LiteType.Label)
                    LiteChoiceRow(
                        ColorMode.entries, state.settings.colorMode,
                        { stringResource(when (it) { ColorMode.System -> R.string.lite_color_system; ColorMode.Light -> R.string.lite_color_light; ColorMode.Dark -> R.string.lite_color_dark }) },
                        { actions.changeSettings(state.settings.copy(colorMode = it)) }, enabled = enabled,
                    )
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_aurora), stringResource(R.string.lite_aurora_detail)) {
                    LiteSwitch(state.settings.aurora, stringResource(R.string.lite_aurora), { actions.changeSettings(state.settings.copy(aurora = it)) }, enabled)
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(R.string.lite_accent), style = LiteType.Label)
                    val swatches = listOf(
                        0xFF6B52AEL to R.string.lite_theme_violet,
                        0xFF235FA4L to R.string.lite_theme_blue,
                        0xFF00786BL to R.string.lite_theme_teal,
                        0xFFA83864L to R.string.lite_theme_rose,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        swatches.forEach { (color, label) ->
                            val selected = state.settings.themeColor == color
                            LitePane(
                                Modifier.weight(1f).selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                    actions.changeSettings(state.settings.copy(themeColor = color))
                                }, corner = LiteCorner.Control, selected = selected,
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.size(24.dp).background(Color(color.toInt()), rememberLiteShape(LiteCorner.Full)))
                                    LiteText(stringResource(label), style = LiteType.Caption, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
internal fun GeneralScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding) {
        item { SectionLabel(stringResource(R.string.lite_background)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_auto_start), stringResource(R.string.lite_auto_start_detail)) {
                    LiteSwitch(state.settings.autoStart, stringResource(R.string.lite_auto_start), { actions.changeSettings(state.settings.copy(autoStart = it)) }, state.busy.isEmpty())
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_open_logs), stringResource(R.string.lite_open_logs_detail)) {
                    LiteSwitch(state.settings.openLogs, stringResource(R.string.lite_open_logs), { actions.changeSettings(state.settings.copy(openLogs = it)) }, state.busy.isEmpty())
                }
            }
        }
    }
}

@Composable
internal fun DnsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var servers by rememberSaveable(state.settings.dnsServers) { mutableStateOf(state.settings.dnsServers.joinToString("\n")) }
    var customDns by rememberSaveable(state.settings.overrideDns) { mutableStateOf(state.settings.overrideDns) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val entries = servers.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_dns_custom), if (customDns) null else stringResource(R.string.lite_dns_follow_profile)) {
                    LiteSwitch(customDns, stringResource(R.string.lite_dns_custom), {
                        attempted = false
                        if (!it && state.settings.overrideDns) actions.changeSettings(state.settings.copy(overrideDns = false))
                        else customDns = it
                    }, state.busy.isEmpty())
                }
            }
        }
        if (customDns) {
            item {
                LitePane(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiteField(servers, { servers = it }, stringResource(R.string.lite_dns_servers), singleLine = false, enabled = state.busy.isEmpty())
                        if (attempted && entries.isEmpty()) LiteText(stringResource(R.string.lite_dns_empty), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        LiteButton(
                            stringResource(R.string.lite_save),
                            {
                                attempted = true
                                if (entries.isNotEmpty()) actions.changeSettings(state.settings.copy(overrideDns = true, dnsServers = entries))
                            }, Modifier.fillMaxWidth(), selected = true, enabled = state.busy.isEmpty() && (!state.settings.overrideDns || entries != state.settings.dnsServers),
                        )
                    }
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_ipv6), stringResource(R.string.lite_ipv6_detail)) {
                    LiteSwitch(state.settings.ipv6, stringResource(R.string.lite_ipv6), { actions.changeSettings(state.settings.copy(ipv6 = it)) }, state.busy.isEmpty())
                }
            }
        }
        item { LiteText(stringResource(R.string.lite_settings_saved_notice), Modifier.padding(4.dp), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk) }
    }
}

@Composable
internal fun AppFilterScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var draftMode by rememberSaveable(state.settings.appFilterMode) { mutableStateOf(state.settings.appFilterMode) }
    var draftPackages by rememberSaveable(state.settings.packages) { mutableStateOf(state.settings.packages.toList()) }
    val dirty = draftMode != state.settings.appFilterMode || draftPackages.toSet() != state.settings.packages
    val valid = draftMode != AppFilterMode.Include || draftPackages.isNotEmpty()
    LaunchedEffect(Unit) { actions.loadInstalledApps() }
    val matching by produceState<List<InstalledApp>>(emptyList(), state.installedApps, query) {
        value = withContext(Dispatchers.Default) {
            state.installedApps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
        }
    }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteChoiceRow(
                        AppFilterMode.entries, draftMode, { appFilterLabel(it) },
                        { draftMode = it }, enabled = state.busy.isEmpty(),
                    )
                    LiteText(
                        stringResource(when (draftMode) { AppFilterMode.All -> R.string.lite_apps_all_detail; AppFilterMode.Include -> R.string.lite_apps_include_detail; AppFilterMode.Exclude -> R.string.lite_apps_exclude_detail }),
                        style = LiteType.Label, color = LocalLiteVisuals.current.secondaryInk,
                    )
                    if (!valid) LiteText(stringResource(R.string.lite_apps_include_empty), style = LiteType.Caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiteButton(stringResource(R.string.lite_cancel), {
                            draftMode = state.settings.appFilterMode
                            draftPackages = state.settings.packages.toList()
                        }, Modifier.weight(1f), enabled = dirty && state.busy.isEmpty())
                        LiteButton(stringResource(R.string.lite_apply), {
                            actions.changeSettings(state.settings.copy(appFilterMode = draftMode, packages = draftPackages.toSet()))
                        }, Modifier.weight(1f), selected = true, enabled = dirty && valid && state.busy.isEmpty())
                    }
                }
            }
        }
        if (draftMode != AppFilterMode.All) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteField(query, { query = it }, stringResource(R.string.lite_search), Modifier.weight(1f))
                    LiteIconButton(stringResource(R.string.lite_refresh), FullGlyphs.refresh, actions::loadInstalledApps, enabled = state.busy.isEmpty())
                }
            }
            item { LiteText(stringResource(R.string.lite_apps_selected, draftPackages.size), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk) }
            if (matching.isEmpty()) item {
                LiteText(stringResource(if (state.busy.isNotEmpty()) R.string.lite_busy else R.string.lite_apps_empty), Modifier.padding(16.dp))
            }
            items(matching, key = { it.packageName }) { app ->
                val checked = app.packageName in draftPackages
                LitePane(
                    Modifier.fillMaxWidth().toggleable(checked, enabled = state.busy.isEmpty(), role = Role.Checkbox) {
                        draftPackages = if (it) (draftPackages + app.packageName).distinct() else draftPackages - app.packageName
                    },
                    selected = checked,
                ) {
                    SettingsRow(app.label, app.packageName) {
                        if (checked) LiteIcon(FullGlyphs.check, color = LocalLiteVisuals.current.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun materialLabel(tier: MaterialTier): String = stringResource(when (tier) {
    MaterialTier.Solid -> R.string.lite_solid
    MaterialTier.Frosted -> R.string.lite_frosted
    MaterialTier.Clear -> R.string.lite_clear
})

@Composable
private fun appFilterLabel(mode: AppFilterMode): String = stringResource(when (mode) {
    AppFilterMode.All -> R.string.lite_apps_all
    AppFilterMode.Include -> R.string.lite_apps_include
    AppFilterMode.Exclude -> R.string.lite_apps_exclude
})
