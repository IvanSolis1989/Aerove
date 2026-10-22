package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.LiteState
import dev.aerove.lite.R

@Composable
internal fun ToolsScreen(state: LiteState, padding: PaddingValues, open: (DetailPage) -> Unit) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding) {
        item { SectionLabel(stringResource(R.string.lite_more)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column {
                    ToolRow(R.string.lite_tab_connections, FullGlyphs.connections) { open(DetailPage.Connections) }
                    if (state.settings.openLogs) ToolRow(R.string.lite_logs, FullGlyphs.logs) { open(DetailPage.Logs) }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.lite_tab_settings)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column {
                    ToolRow(R.string.lite_theme, FullGlyphs.paintbrush) { open(DetailPage.Theme) }
                    ToolRow(R.string.lite_app_filter, FullGlyphs.appsList) { open(DetailPage.Apps) }
                    SettingsRow(stringResource(R.string.lite_dns), if (state.settings.overrideDns) state.settings.dnsServers.joinToString(", ") else stringResource(R.string.lite_dns_follow_profile), FullGlyphs.dns, { open(DetailPage.Dns) }) {
                        LiteIcon(FullGlyphs.chevronForward)
                    }
                    ToolRow(R.string.lite_general, FullGlyphs.settings) { open(DetailPage.General) }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.lite_other)) }
        item {
            LitePane(Modifier.fillMaxWidth()) { ToolRow(R.string.lite_about, FullGlyphs.info) { open(DetailPage.About) } }
        }
    }
}

@Composable
private fun ToolRow(label: Int, glyph: LiteGlyph, open: () -> Unit) {
    SettingsRow(stringResource(label), icon = glyph, onClick = open) { LiteIcon(FullGlyphs.chevronForward) }
}
