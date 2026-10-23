package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.proxies.ProxyPresentationCard
import dev.aerove.lite.proxies.ProxyPresentationIcon
import dev.aerove.lite.proxies.ProxyPresentationLayout
import dev.aerove.lite.proxies.ProxyPresentationPreferences
import dev.aerove.lite.proxies.ProxyPresentationSort
import dev.aerove.lite.proxies.ProxyPresentationType

@Composable
internal fun ProxyPreferencesScreen(value: ProxyPresentationPreferences, padding: PaddingValues, busy: Boolean = false,
    onChanged: (ProxyPresentationPreferences) -> Unit) {
    var url by rememberSaveable(value.testUrl) { mutableStateOf(value.testUrl) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ProxyPreferenceChoices(stringResource(R.string.proxy_tools_style), ProxyPresentationType.entries,
            value.type, busy, { if (it == ProxyPresentationType.list) stringResource(R.string.proxy_tools_list) else stringResource(R.string.proxy_tools_tab) }) { onChanged(value.copy(type = it)) } }
        item { ProxyPreferenceChoices(stringResource(R.string.proxy_tools_sort), ProxyPresentationSort.entries,
            value.sort, busy, { when (it) { ProxyPresentationSort.none -> stringResource(R.string.proxy_tools_default); ProxyPresentationSort.delay -> stringResource(R.string.proxy_tools_delay); ProxyPresentationSort.byName -> stringResource(R.string.proxy_tools_name) } }) { onChanged(value.copy(sort = it)) } }
        item { ProxyPreferenceChoices(stringResource(R.string.proxy_tools_layout), ProxyPresentationLayout.entries,
            value.layout, busy, { when (it) { ProxyPresentationLayout.loose -> stringResource(R.string.proxy_tools_loose); ProxyPresentationLayout.standard -> stringResource(R.string.proxy_tools_standard); ProxyPresentationLayout.tight -> stringResource(R.string.proxy_tools_tight) } }) { onChanged(value.copy(layout = it)) } }
        item { ProxyPreferenceChoices(stringResource(R.string.proxy_tools_size), ProxyPresentationCard.entries,
            value.cardType, busy, { when (it) { ProxyPresentationCard.loose -> stringResource(R.string.proxy_tools_loose); ProxyPresentationCard.standard -> stringResource(R.string.proxy_tools_standard); ProxyPresentationCard.compact -> stringResource(R.string.proxy_tools_compact) } }) { onChanged(value.copy(cardType = it)) } }
        if (value.type == ProxyPresentationType.list) item {
            ProxyPreferenceChoices(stringResource(R.string.proxy_tools_icon), ProxyPresentationIcon.entries,
                value.iconStyle, busy, { when (it) { ProxyPresentationIcon.filled -> stringResource(R.string.proxy_tools_filled); ProxyPresentationIcon.plain -> stringResource(R.string.proxy_tools_plain); ProxyPresentationIcon.hidden -> stringResource(R.string.proxy_tools_hidden) } }) { onChanged(value.copy(iconStyle = it)) }
        }
        item { LitePane(Modifier.fillMaxWidth()) { SettingsRow(stringResource(R.string.proxy_tools_hide_timeout)) {
            LiteSwitch(value.hideTimeoutProxies, stringResource(R.string.proxy_tools_hide_timeout), { onChanged(value.copy(hideTimeoutProxies = it)) }, !busy)
        } } }
        item { ProxyPreferenceChoices(stringResource(R.string.proxy_tools_timeout), listOf(500, 1000, 2000, 5000, 8000),
            value.timeoutMillis, busy, { stringResource(R.string.proxy_tools_milliseconds, it) }) { onChanged(value.copy(timeoutMillis = it)) } }
        item { LitePane(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiteField(url, { url = it; invalid = false }, stringResource(R.string.proxy_tools_test_url), enabled = !busy)
            if (invalid) LiteText(stringResource(R.string.proxy_tools_invalid_probe), style = LiteType.Caption)
            LiteButton(stringResource(R.string.proxy_tools_save), {
                val changed = runCatching { value.copy(testUrl = url.trim()).validate() }.getOrNull()
                if (changed == null) invalid = true else onChanged(changed)
            }, Modifier.fillMaxWidth(), enabled = !busy)
        } } }
    }
}

@Composable
private fun <T> ProxyPreferenceChoices(title: String, options: List<T>, selected: T, busy: Boolean,
    label: @Composable (T) -> String, onSelected: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        LitePane(Modifier.fillMaxWidth()) { Column {
            options.forEach { option -> SettingsRow(label(option), onClick = if (busy) null else ({ onSelected(option) })) {
                if (selected == option) LiteIcon(FullGlyphs.check, color = LocalLiteVisuals.current.primary)
            } }
        } }
    }
}
