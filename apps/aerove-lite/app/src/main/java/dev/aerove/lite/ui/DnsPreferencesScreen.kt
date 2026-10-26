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
internal fun DnsPreferencesScreen(preferences: DnsPreferences, busy: Boolean, padding: PaddingValues,
    onChanged: (DnsPreferences) -> Long, receipt: DnsSaveReceipt?, customProfile: Boolean = false, error: String? = null) {
    var editing by rememberSaveable { mutableStateOf<DnsField?>(null) }
    DnsEditorOverlay(editing != null) { close ->
        editing?.let { field -> DnsFieldForm(field, preferences, busy, error, onChanged, receipt, customProfile) { editing = null; close() } }
    }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("dns-preferences")) {
        item { LiteText(stringResource(R.string.dns_preferences_scope), Modifier.padding(horizontal = 16.dp), style = LiteType.Caption) }
        if (error != null) item { LiteText(error, Modifier.padding(horizontal = 16.dp), style = LiteType.Caption) }
        DnsField.entries.filter { customProfile || it.normalProfile }.forEach { field ->
            item(field.path) {
                LitePane(Modifier.fillMaxWidth().testTag("dns-field-${field.path}")) {
                    SettingsRow(stringResource(field.label()), stringResource(if (field in preferences.overridden)
                        R.string.dns_preferences_overridden else R.string.dns_preferences_inherit),
                        onClick = if (busy) null else ({ editing = field })) { LiteIcon(FullGlyphs.chevronForward) }
                }
            }
        }
    }
}

@Composable
internal fun DnsEditorOverlay(open: Boolean, content: @Composable (() -> Unit) -> Unit) {
    val overlay = LocalLiteOverlay.current
    val currentContent by rememberUpdatedState(content)
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    LaunchedEffect(open) {
        if (open) {
            val body: @Composable () -> Unit = { currentContent { if (overlay.content === owned[0]) overlay.dismiss() } }
            owned[0] = body; overlay.content = body
        } else {
            if (overlay.content === owned[0]) overlay.dismiss()
            owned[0] = null
        }
    }
    DisposableEffect(overlay) { onDispose { if (overlay.content === owned[0]) overlay.dismiss() } }
}

@Composable
private fun DnsFieldForm(field: DnsField, preferences: DnsPreferences, busy: Boolean, error: String?,
    onChanged: (DnsPreferences) -> Long, receipt: DnsSaveReceipt?, customProfile: Boolean, close: () -> Unit) {
    val original = remember(field) { preferences.value(field) }
    var selected by rememberSaveable(field) { mutableStateOf(field in preferences.overridden) }
    var bool by rememberSaveable(field) { mutableStateOf((original as? DnsValue.Bool)?.value ?: false) }
    var text by rememberSaveable(field) { mutableStateOf((original as? DnsValue.Text)?.value ?: "") }
    var entries by remember(field) { mutableStateOf((original as? DnsValue.Entries)?.value ?: emptyList()) }
    var policy by remember(field) { mutableStateOf((original as? DnsValue.Policy)?.value?.map { it.key to it.value.joinToString(", ") } ?: emptyList()) }
    var issue by remember { mutableStateOf<DnsPreferencesException?>(null) }
    var pending by remember { mutableStateOf<PendingDnsSave?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    val enabled = !busy && pending == null
    DnsSaveReadback(preferences, busy, pending, receipt, { failed -> pending = null; saveFailed = failed }, close)
    LiteModal(stringResource(field.label()), close) {
        if (error != null) LiteText(error, style = LiteType.Caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        SettingsRow(stringResource(R.string.dns_preferences_override)) {
            LiteSwitch(selected, stringResource(R.string.dns_preferences_override), { selected = it }, enabled)
        }
        when (original) {
            is DnsValue.Bool -> SettingsRow(stringResource(field.label())) {
                LiteSwitch(bool, stringResource(field.label()), { bool = it; selected = true }, enabled)
            }
            is DnsValue.Text -> if (field == DnsField.EnhancedMode) {
                LiteChoiceRow(listOf("normal", "fake-ip"), text, { it }, { text = it; selected = true }, enabled = enabled)
            } else LiteField(text, { text = it; selected = true }, stringResource(field.label()), enabled = enabled)
            is DnsValue.Entries -> DnsListFields(entries, { entries = it; selected = true }, enabled)
            is DnsValue.Policy -> DnsMapFields(policy, { policy = it; selected = true }, enabled)
        }
        DnsIssueText(issue)
        if (saveFailed) LiteText(stringResource(R.string.dns_preferences_save_failed), style = LiteType.Caption,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        DnsFormButtons(enabled, close) {
            issue = null; saveFailed = false
            try {
                val value = when (original) {
                    is DnsValue.Bool -> DnsValue.Bool(bool)
                    is DnsValue.Text -> DnsValue.Text(text.trim())
                    is DnsValue.Entries -> DnsValue.Entries(entries.map(String::trim))
                    is DnsValue.Policy -> DnsValue.Policy(dnsMapDraft(policy))
                }
                if (selected && !customProfile && field == DnsField.DefaultNameserver &&
                    (value as DnsValue.Entries).value.isEmpty()) throw DnsPreferencesException("dns.default-nameserver", DnsIssueCode.InvalidValue)
                val draft = if (selected) preferences.withValue(field, value) else preferences.inherit(field)
                if (draft == preferences) close() else { pending = PendingDnsSave(draft, onChanged(draft)) }
            } catch (failure: DnsPreferencesException) { issue = failure; pending = null }
            catch (_: Exception) { pending = null; saveFailed = true }
        }
    }
}

@Composable
internal fun DnsSaveReadback(current: DnsPreferences, busy: Boolean, pending: PendingDnsSave?, receipt: DnsSaveReceipt?,
    clearPending: (Boolean) -> Unit, close: () -> Unit) {
    LaunchedEffect(current, busy, pending, receipt) {
        when (dnsSaveDecision(current, busy, pending, receipt)) {
            DnsSaveDecision.Wait -> Unit
            DnsSaveDecision.Saved -> { clearPending(false); close() }
            DnsSaveDecision.Failed -> clearPending(true)
        }
    }
}

@Composable
internal fun DnsListFields(entries: List<String>, change: (List<String>) -> Unit, enabled: Boolean) {
    entries.forEachIndexed { index, value ->
        LiteField(value, { next -> change(entries.toMutableList().apply { set(index, next) }) },
            stringResource(R.string.dns_preferences_entry, index + 1), enabled = enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiteButton(stringResource(R.string.dns_preferences_remove), { change(entries.filterIndexed { i, _ -> i != index }) }, enabled = enabled)
            if (index > 0) LiteButton(stringResource(R.string.dns_preferences_move_up), {
                change(entries.toMutableList().apply { add(index - 1, removeAt(index)) })
            }, enabled = enabled)
            if (index < entries.lastIndex) LiteButton(stringResource(R.string.dns_preferences_move_down), {
                change(entries.toMutableList().apply { add(index + 1, removeAt(index)) })
            }, enabled = enabled)
        }
    }
    LiteButton(stringResource(R.string.dns_preferences_add), { change(entries + "") }, Modifier.testTag("dns-add-entry"), enabled = enabled)
}

@Composable
internal fun DnsMapFields(entries: List<Pair<String, String>>, change: (List<Pair<String, String>>) -> Unit, enabled: Boolean) {
    entries.forEachIndexed { index, entry ->
        LiteField(entry.first, { next -> change(entries.toMutableList().apply { set(index, next to entry.second) }) },
            stringResource(R.string.dns_preferences_domain, index + 1), enabled = enabled)
        LiteField(entry.second, { next -> change(entries.toMutableList().apply { set(index, entry.first to next) }) },
            stringResource(R.string.dns_preferences_target, index + 1), enabled = enabled)
        LiteButton(stringResource(R.string.dns_preferences_remove), { change(entries.filterIndexed { i, _ -> i != index }) }, enabled = enabled)
    }
    LiteButton(stringResource(R.string.dns_preferences_add), { change(entries + ("" to "")) }, Modifier.testTag("dns-add-entry"), enabled = enabled)
}

internal fun dnsMapDraft(entries: List<Pair<String, String>>): Map<String, List<String>> {
    val keys = entries.map { it.first.trim() }
    if (keys.distinct().size != keys.size) throw DnsPreferencesException("entries", DnsIssueCode.Duplicate)
    return entries.associateTo(linkedMapOf()) { it.first.trim() to it.second.split(Regex("[,;\\s]+")).filter(String::isNotEmpty) }
}

@Composable
internal fun DnsIssueText(issue: DnsPreferencesException?) {
    if (issue != null) LiteText(stringResource(if (issue.code == DnsIssueCode.UnsupportedCore)
        R.string.dns_preferences_unsupported else R.string.dns_preferences_invalid, issue.field),
        color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = LiteType.Caption)
}

@Composable
internal fun DnsFormButtons(enabled: Boolean, close: () -> Unit, save: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LiteButton(stringResource(R.string.dns_preferences_cancel), close, Modifier.weight(1f))
        LiteButton(stringResource(R.string.dns_preferences_save), save, Modifier.weight(1f).testTag("dns-save"), selected = true, enabled = enabled)
    }
}

private fun DnsField.label(): Int = when (this) {
    DnsField.Enable -> R.string.dns_preferences_enabled
    DnsField.Listen -> R.string.dns_preferences_listen
    DnsField.UseHosts -> R.string.dns_preferences_use_hosts
    DnsField.Ipv6 -> R.string.dns_preferences_ipv6
    DnsField.RespectRules -> R.string.dns_preferences_respect_rules
    DnsField.EnhancedMode -> R.string.dns_preferences_mode
    DnsField.FakeIpRange -> R.string.dns_preferences_range
    DnsField.FakeIpFilter -> R.string.dns_preferences_filter
    DnsField.DefaultNameserver -> R.string.dns_preferences_bootstrap
    DnsField.NameserverPolicy -> R.string.dns_preferences_policy
    DnsField.Nameserver -> R.string.dns_preferences_nameservers
    DnsField.Fallback -> R.string.dns_preferences_fallback
    DnsField.ProxyServerNameserver -> R.string.dns_preferences_proxy_servers
    DnsField.FallbackGeoip -> R.string.dns_preferences_geoip
    DnsField.FallbackGeoipCode -> R.string.dns_preferences_country
    DnsField.FallbackIpcidr -> R.string.dns_preferences_cidrs
    DnsField.FallbackDomain -> R.string.dns_preferences_domains
}
