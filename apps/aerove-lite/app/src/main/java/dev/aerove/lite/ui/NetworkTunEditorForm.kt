package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.networksettings.*

internal enum class NetworkTunEditor { Mtu, Routes, ExcludedRoutes }

@Composable
internal fun NetworkTunEditorForm(kind: NetworkTunEditor, settings: NetworkSettings, busy: Boolean, error: String?,
    onChanged: (NetworkSettings) -> Unit, close: () -> Unit) {
    var mtu by rememberSaveable { mutableStateOf(settings.mtu.toString()) }
    var routes by remember { mutableStateOf(if (kind == NetworkTunEditor.Routes) settings.routeAddress else settings.routeExcludeAddress) }
    var pending by remember { mutableStateOf<NetworkSettings?>(null) }
    var observedBusy by remember { mutableStateOf(false) }
    var submittedError by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val enabled = !busy && pending == null
    val draft = when (kind) {
        NetworkTunEditor.Mtu -> settings.copy(mtu = mtu.toIntOrNull() ?: -1)
        NetworkTunEditor.Routes -> settings.copy(routeAddress = routes.map(String::trim))
        NetworkTunEditor.ExcludedRoutes -> settings.copy(routeExcludeAddress = routes.map(String::trim))
    }
    fun matches(wanted: NetworkSettings) = when (kind) {
        NetworkTunEditor.Mtu -> wanted.mtu == settings.mtu
        NetworkTunEditor.Routes -> wanted.routeAddress == settings.routeAddress
        NetworkTunEditor.ExcludedRoutes -> wanted.routeExcludeAddress == settings.routeExcludeAddress
    }
    LaunchedEffect(settings, busy, error, pending) {
        val wanted = pending ?: return@LaunchedEffect
        if (busy) observedBusy = true
        if (!busy && matches(wanted)) { pending = null; close() }
        else if (!busy && (observedBusy || error != submittedError)) { pending = null; observedBusy = false; failure = true }
    }
    LiteModal(stringResource(when (kind) {
        NetworkTunEditor.Mtu -> R.string.network_mtu
        NetworkTunEditor.Routes -> R.string.network_route_addresses
        NetworkTunEditor.ExcludedRoutes -> R.string.network_route_exclusions
    }), close) {
        if (kind == NetworkTunEditor.Mtu) {
            LiteField(mtu, { mtu = it; failure = false }, stringResource(R.string.network_mtu), keyboardType = KeyboardType.Number, enabled = enabled)
            LiteText(stringResource(R.string.network_mtu_range), style = LiteType.Caption)
        } else {
            routes.forEachIndexed { index, value ->
                LiteField(value, { next -> routes = routes.toMutableList().apply { set(index, next) }; failure = false },
                    stringResource(R.string.network_route_entry, index + 1), enabled = enabled)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiteButton(stringResource(R.string.network_remove_route), { routes = routes.filterIndexed { i, _ -> i != index } }, enabled = enabled)
                    if (index > 0) LiteButton(stringResource(R.string.network_move_route_up), {
                        routes = routes.toMutableList().apply { add(index - 1, removeAt(index)) }
                    }, enabled = enabled)
                    if (index < routes.lastIndex) LiteButton(stringResource(R.string.network_move_route_down), {
                        routes = routes.toMutableList().apply { add(index + 1, removeAt(index)) }
                    }, enabled = enabled)
                }
            }
            LiteButton(stringResource(R.string.network_add_route), { routes = routes + "" }, Modifier.testTag("network-add-route"), enabled = enabled)
        }
        if (attempted) validateNetworkSettings(draft).filter { issue ->
            if (kind == NetworkTunEditor.Mtu) issue.field == "network.mtu" else issue.field.startsWith(
                if (kind == NetworkTunEditor.Routes) "network.routeAddress[" else "network.routeExcludeAddress[")
        }.forEach { issue ->
            LiteText(if (issue.code == NetworkIssueCode.MtuRange) stringResource(R.string.network_mtu_range)
                else stringResource(R.string.network_invalid_cidr, issue.field.substringAfterLast('[').substringBefore(']').toIntOrNull()?.plus(1) ?: 1),
                style = LiteType.Caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
        if (failure) LiteText(stringResource(R.string.network_save_failed), style = LiteType.Caption,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiteButton(stringResource(R.string.network_cancel), close, Modifier.weight(1f))
            LiteButton(stringResource(R.string.network_save), {
                attempted = true
                if (validateNetworkSettings(draft).isEmpty()) {
                    if (matches(draft)) close() else {
                        pending = draft; observedBusy = false; submittedError = error; failure = false
                        try { onChanged(draft) } catch (_: Exception) { pending = null; failure = true }
                    }
                }
            }, Modifier.weight(1f).testTag("network-save"), selected = true, enabled = enabled)
        }
    }
}
