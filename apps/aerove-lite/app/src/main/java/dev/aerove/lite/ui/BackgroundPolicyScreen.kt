package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences

@Composable
internal fun BackgroundPolicySection(preferences: BackgroundPolicyPreferences, enabled: Boolean,
    onChange: (BackgroundPolicyPreferences) -> Unit, onOnDemand: () -> Unit, showStopAction: Boolean = true) {
    LitePane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiteText(stringResource(R.string.background_title), style = LiteType.Headline)
            SettingsRow(stringResource(R.string.background_auto_run)) {
                LiteSwitch(preferences.autoRun, stringResource(R.string.background_auto_run), { onChange(preferences.copy(autoRun = it)) }, enabled)
            }
            SettingsRow(stringResource(R.string.background_on_demand), onClick = { if (enabled) onOnDemand() }) {}
            SettingsRow(stringResource(R.string.background_minimize)) {
                LiteSwitch(preferences.minimizeOnExit, stringResource(R.string.background_minimize), { onChange(preferences.copy(minimizeOnExit = it)) }, enabled)
            }
            SettingsRow(stringResource(R.string.background_hidden)) {
                LiteSwitch(preferences.hidden, stringResource(R.string.background_hidden), { onChange(preferences.copy(hidden = it)) }, enabled)
            }
            if (showStopAction) SettingsRow(stringResource(R.string.background_notification_stop)) {
                LiteSwitch(preferences.showNotificationStopAction, stringResource(R.string.background_notification_stop),
                    { onChange(preferences.copy(showNotificationStopAction = it)) }, enabled)
            }
            SettingsRow(stringResource(R.string.background_close_connections)) {
                LiteSwitch(preferences.closeConnections, stringResource(R.string.background_close_connections), { onChange(preferences.copy(closeConnections = it)) }, enabled)
            }
            SettingsRow(stringResource(R.string.background_proxy_statistics)) {
                LiteSwitch(preferences.onlyStatisticsProxy, stringResource(R.string.background_proxy_statistics), { onChange(preferences.copy(onlyStatisticsProxy = it)) }, enabled)
            }
            LiteText(stringResource(R.string.background_description), style = LiteType.Caption)
        }
    }
}

@Composable
internal fun BackgroundPolicyScreen(preferences: BackgroundPolicyPreferences, onChange: (BackgroundPolicyPreferences) -> Unit,
    locationGranted: Boolean, batteryIgnored: Boolean, onRequestLocation: () -> Unit, onRequestBattery: () -> Unit,
    padding: PaddingValues = PaddingValues(16.dp), enabled: Boolean = true) {
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val entries = preferences.excludeSSIDs
    val list = rememberLazyListState()
    val drag = rememberOverrideDrag(entries, list, enabled && !editing)
    val moveUp = stringResource(R.string.background_move_up)
    val moveDown = stringResource(R.string.background_move_down)
    fun move(key: String, destination: Int) {
        val origin = entries.indexOf(key)
        if (origin >= 0 && destination in entries.indices) onChange(preferences.moveSsid(origin, destination))
    }
    LazyColumn(state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = drag.dragging == null) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(R.string.background_prerequisites), style = LiteType.Headline)
                    SettingsRow(stringResource(R.string.background_location)) {
                        LiteButton(stringResource(if (locationGranted) R.string.background_authorized else R.string.background_authorize), onRequestLocation, enabled = enabled)
                    }
                    SettingsRow(stringResource(R.string.background_battery)) {
                        LiteButton(stringResource(if (batteryIgnored) R.string.background_authorized else R.string.background_authorize), onRequestBattery, enabled = enabled)
                    }
                    LiteText(stringResource(R.string.background_permission_description), style = LiteType.Caption)
                }
            }
        }
        item {
            LiteText(stringResource(R.string.background_excluded), style = LiteType.Headline)
            LiteButton(stringResource(R.string.background_add), { original = null; editing = true }, enabled = enabled, icon = FullGlyphs.add)
            if (entries.isNotEmpty()) LiteButton(stringResource(R.string.background_select_all), {
                selected = if (selected.containsAll(entries)) emptySet() else entries.toSet()
            }, enabled = enabled)
            if (selected.isNotEmpty()) LiteButton(stringResource(R.string.background_delete), {
                onChange(preferences.deleteSsids(selected)); selected = emptySet()
            }, enabled = enabled, destructive = true)
        }
        if (entries.isEmpty()) item { LiteText(stringResource(R.string.background_empty)) }
        itemsIndexed(drag.keys, key = { _, ssid -> ssid }) { index, ssid ->
            LitePane(Modifier.fillMaxWidth().overrideDragVisual(drag, ssid)
                .overrideDragGesture(drag, ssid, enabled && !editing, commit = ::move)
                .overrideMoveAccessibility(index, entries.size, moveUp, moveDown) { move(ssid, it) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRow(ssid, onClick = { if (enabled) { original = ssid; editing = true } }) {
                        LiteSwitch(ssid in selected, ssid, { selected = if (it) selected + ssid else selected - ssid }, enabled)
                    }
                }
            }
        }
        item { LiteText(stringResource(R.string.background_excluded_description), style = LiteType.Caption) }
    }
    if (editing) {
        var value by remember(original) { mutableStateOf(original.orEmpty()) }
        val valid = BackgroundPolicyPreferences.validSsid(value) && (value == original || value !in entries) &&
            (original == null || original in entries)
        LiteModal(stringResource(if (original == null) R.string.background_add else R.string.background_edit), { editing = false }) {
            LiteField(value, { value = it }, stringResource(R.string.background_ssid))
            if (!valid) LiteText(stringResource(R.string.background_invalid), style = LiteType.Caption)
            LiteButton(stringResource(R.string.background_save), { onChange(preferences.editSsid(original, value)); editing = false }, enabled = enabled && valid)
            LiteButton(stringResource(R.string.background_cancel), { editing = false })
        }
    }
}
