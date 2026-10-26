package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.dnssettings.*

@Composable
internal fun HostsPreferencesScreen(preferences: DnsPreferences, busy: Boolean, padding: PaddingValues,
    onChanged: (DnsPreferences) -> Long, receipt: DnsSaveReceipt?, error: String? = null) {
    var editing by rememberSaveable { mutableStateOf(false) }
    DnsEditorOverlay(editing) { close ->
        HostsPreferenceForm(preferences, busy, error, onChanged, receipt) { editing = false; close() }
    }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("hosts-preferences")) {
        item { LiteText(stringResource(R.string.dns_preferences_hosts_detail), Modifier.padding(horizontal = 16.dp), style = LiteType.Caption) }
        if (error != null) item { LiteText(error, Modifier.padding(horizontal = 16.dp), style = LiteType.Caption) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.dns_preferences_hosts), stringResource(R.string.dns_preferences_count, preferences.hosts.size),
                    onClick = if (busy) null else ({ editing = true })) { LiteIcon(FullGlyphs.chevronForward) }
            }
        }
        preferences.hosts.forEach { (domain, targets) -> item(domain) {
            LitePane(Modifier.fillMaxWidth()) { SettingsRow(domain, targets.joinToString(", ")) {} }
        } }
    }
}

@Composable
private fun HostsPreferenceForm(preferences: DnsPreferences, busy: Boolean, error: String?,
    onChanged: (DnsPreferences) -> Long, receipt: DnsSaveReceipt?, close: () -> Unit) {
    var entries by remember { mutableStateOf(preferences.hosts.map { it.key to it.value.joinToString(", ") }) }
    var pending by remember { mutableStateOf<PendingDnsSave?>(null) }
    var issue by remember { mutableStateOf<DnsPreferencesException?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    val enabled = !busy && pending == null
    LiteModal(stringResource(R.string.dns_preferences_hosts), close) {
        if (error != null) LiteText(error, style = LiteType.Caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        DnsSaveReadback(preferences, busy, pending, receipt, { failed -> pending = null; saveFailed = failed }, close)
        DnsMapFields(entries, { entries = it }, enabled)
        DnsIssueText(issue)
        if (saveFailed) LiteText(stringResource(R.string.dns_preferences_save_failed), style = LiteType.Caption,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        DnsFormButtons(enabled, close) {
            issue = null; saveFailed = false
            try {
                val draft = preferences.copy(hosts = dnsMapDraft(entries)).requireValid().snapshot()
                if (draft == preferences) close() else { pending = PendingDnsSave(draft, onChanged(draft)) }
            } catch (failure: DnsPreferencesException) { issue = failure; pending = null }
            catch (_: Exception) { pending = null; saveFailed = true }
        }
    }
}
