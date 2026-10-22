package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.networksettings.*

private enum class NetworkEditor { Ports, Authentication, Bypass }

@Composable
internal fun NetworkSettingsScreen(settings: NetworkSettings, busy: Boolean, padding: PaddingValues,
    onChanged: (NetworkSettings) -> Unit, error: String? = null,
    restartRequired: Boolean = false, onRestart: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    var editor by rememberSaveable { mutableStateOf<NetworkEditor?>(null) }
    val current by rememberUpdatedState(settings)
    val currentBusy by rememberUpdatedState(busy)
    val currentError by rememberUpdatedState(error)
    val change by rememberUpdatedState(onChanged)
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    LaunchedEffect(editor) {
        val kind = editor
        val content: (@Composable () -> Unit)? = kind?.let { ({
            NetworkEditorForm(kind, current, currentBusy, currentError, change) { editor = null }
        }) }
        if (content != null) { owned[0] = content; overlay.content = content }
        else { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    }
    DisposableEffect(overlay) { onDispose { if (overlay.content === owned[0]) overlay.dismiss() } }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("network-settings")) {
        if (error != null) item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteText(error, style = LiteType.Caption)
                    if (onRetry != null) LiteButton(stringResource(R.string.network_retry), onRetry,
                        Modifier.fillMaxWidth().testTag("network-retry"), enabled = !busy)
                }
            }
        }
        if (restartRequired && onRestart != null) item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteText(stringResource(R.string.network_restart_detail), style = LiteType.Caption)
                    LiteButton(stringResource(R.string.network_restart), onRestart,
                        Modifier.fillMaxWidth().testTag("network-restart"), enabled = !busy)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.network_vpn)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(stringResource(R.string.network_system_proxy)) {
                        LiteSwitch(settings.systemProxy, stringResource(R.string.network_system_proxy),
                            { change(current.copy(systemProxy = it)) }, !busy)
                    }
                    SettingsRow(stringResource(R.string.network_allow_bypass)) {
                        LiteSwitch(settings.allowBypass, stringResource(R.string.network_allow_bypass),
                            { change(current.copy(allowBypass = it)) }, !busy)
                    }
                    SettingsRow(stringResource(R.string.network_bypass_domains), settings.bypassDomains.joinToString(", "),
                        onClick = if (busy) null else ({ editor = NetworkEditor.Bypass })) { LiteIcon(FullGlyphs.chevronForward) }
                }
            }
        }
        if (settings.authentication.enabled) item {
            LiteText(stringResource(R.string.network_authenticated_proxy_detail), Modifier.padding(horizontal = 16.dp),
                style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        }
        item { SectionLabel(stringResource(R.string.network_inbound)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(stringResource(R.string.network_ports), settings.ports.mixed.toString(),
                        onClick = if (busy) null else ({ editor = NetworkEditor.Ports })) { LiteIcon(FullGlyphs.chevronForward) }
                    SettingsRow(stringResource(R.string.network_allow_lan)) {
                        LiteSwitch(settings.allowLan, stringResource(R.string.network_allow_lan),
                            { change(current.copy(allowLan = it)) }, !busy)
                    }
                    SettingsRow(stringResource(R.string.network_authentication)) {
                        LiteSwitch(settings.authentication.enabled, stringResource(R.string.network_authentication),
                            { change(current.withAuthenticationEnabled(it)) }, !busy)
                    }
                    if (settings.authentication.enabled) SettingsRow(stringResource(R.string.network_credentials),
                        onClick = if (busy) null else ({ editor = NetworkEditor.Authentication })) { LiteIcon(FullGlyphs.chevronForward) }
                }
            }
        }
    }
}

@Composable
private fun NetworkEditorForm(kind: NetworkEditor, settings: NetworkSettings, busy: Boolean, error: String?,
    onChanged: (NetworkSettings) -> Unit, close: () -> Unit) {
    var mixed by rememberSaveable { mutableStateOf(settings.ports.mixed.toString()) }
    var http by rememberSaveable { mutableStateOf(settings.ports.http.toString()) }
    var socks by rememberSaveable { mutableStateOf(settings.ports.socks.toString()) }
    var domains by rememberSaveable { mutableStateOf(settings.bypassDomains.joinToString("\n")) }
    // Credential drafts remain in memory instead of writing plaintext into Android saved state.
    var username by remember { mutableStateOf(settings.authentication.username) }
    var password by remember { mutableStateOf(settings.authentication.password) }
    var attempted by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<NetworkSettings?>(null) }
    var observedBusy by remember { mutableStateOf(false) }
    var submittedError by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val draft = when (kind) {
        NetworkEditor.Ports -> settings.copy(ports = UserProxyPorts(mixed.toIntOrNull() ?: -1, http.toIntOrNull() ?: -1, socks.toIntOrNull() ?: -1))
        NetworkEditor.Authentication -> settings.copy(authentication = settings.authentication.copy(
            username = username.trim().replace(":", ""), password = password.trim()))
        NetworkEditor.Bypass -> settings.copy(bypassDomains = domains.lineSequence().map(String::trim).filter(String::isNotEmpty).toList())
    }
    fun matches(wanted: NetworkSettings): Boolean = when (kind) {
        NetworkEditor.Ports -> wanted.ports == settings.ports
        NetworkEditor.Authentication -> wanted.authentication == settings.authentication
        NetworkEditor.Bypass -> wanted.bypassDomains == settings.bypassDomains
    }
    LaunchedEffect(settings, busy, error, pending) {
        val wanted = pending ?: return@LaunchedEffect
        if (busy) observedBusy = true
        if (!busy && matches(wanted)) { pending = null; close() }
        else if (!busy && (observedBusy || error != submittedError)) { pending = null; observedBusy = false; failed = true }
    }
    val issues = validateNetworkSettings(draft)
    val enabled = !busy && pending == null
    LiteModal(stringResource(when (kind) {
        NetworkEditor.Ports -> R.string.network_ports
        NetworkEditor.Authentication -> R.string.network_credentials
        NetworkEditor.Bypass -> R.string.network_bypass_domains
    }), close) {
        when (kind) {
            NetworkEditor.Ports -> {
                LiteText(stringResource(R.string.network_port_detail), style = LiteType.Caption)
                LiteField(mixed, { mixed = it; failed = false }, stringResource(R.string.network_mixed_port), keyboardType = KeyboardType.Number, enabled = enabled)
                LiteField(http, { http = it; failed = false }, stringResource(R.string.network_http_port), keyboardType = KeyboardType.Number, enabled = enabled)
                LiteField(socks, { socks = it; failed = false }, stringResource(R.string.network_socks_port), keyboardType = KeyboardType.Number, enabled = enabled)
            }
            NetworkEditor.Authentication -> {
                LiteField(username, { username = it; failed = false }, stringResource(R.string.network_username), enabled = enabled)
                NetworkPasswordField(password, { password = it; failed = false }, enabled)
            }
            NetworkEditor.Bypass -> LiteField(domains, { domains = it; failed = false },
                stringResource(R.string.network_bypass_entries), singleLine = false, enabled = enabled)
        }
        if (attempted && issues.isNotEmpty()) LiteText(networkIssueText(issues.first()), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        if (failed) LiteText(stringResource(R.string.network_save_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiteButton(stringResource(R.string.network_cancel), close, Modifier.weight(1f))
            LiteButton(stringResource(R.string.network_save), {
                attempted = true
                if (issues.isEmpty()) {
                    if (matches(draft)) close() else {
                        pending = draft; observedBusy = false; submittedError = error; failed = false
                        try { onChanged(draft) } catch (_: Exception) { pending = null; failed = true }
                    }
                }
            }, Modifier.weight(1f).testTag("network-save"), selected = true, enabled = enabled)
        }
    }
}

@Composable
private fun NetworkPasswordField(value: String, change: (String) -> Unit, enabled: Boolean) {
    val label = stringResource(R.string.network_password)
    val visuals = LocalLiteVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LiteText(label, style = LiteType.Label, color = visuals.secondaryInk)
        LitePane(Modifier.fillMaxWidth(), corner = LiteCorner.Control) {
            BasicTextField(value, change,
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(14.dp).semantics { contentDescription = label },
                enabled = enabled, singleLine = true, textStyle = LiteType.Body.copy(color = visuals.ink, fontFamily = LocalLiteFontFamily.current),
                cursorBrush = SolidColor(visuals.primary), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
        }
    }
}

@Composable
private fun networkIssueText(issue: NetworkIssue): String {
    val label = stringResource(when {
        issue.field.endsWith("mixed") -> R.string.network_mixed_port
        issue.field.endsWith("http") -> R.string.network_http_port
        issue.field.endsWith("socks") -> R.string.network_socks_port
        issue.field.endsWith("username") -> R.string.network_username
        issue.field.endsWith("password") -> R.string.network_password
        else -> R.string.network_bypass_domains
    })
    val reason = stringResource(when (issue.code) {
        NetworkIssueCode.PortConflict -> R.string.network_port_conflict
        NetworkIssueCode.PortRange -> R.string.network_port_range
        NetworkIssueCode.EmptyAccount -> R.string.network_account_required
        NetworkIssueCode.SocksCredentialLength -> R.string.network_socks_length
        NetworkIssueCode.CredentialLength -> R.string.network_credential_length
        NetworkIssueCode.DomainLength -> R.string.network_domain_length
        else -> R.string.network_invalid_value
    })
    return stringResource(R.string.network_field_issue, label, reason)
}
