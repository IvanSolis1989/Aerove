package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.LiteState
import dev.aerove.lite.LiteActions
import dev.aerove.lite.R

private val RequestsToolGlyph = LiteGlyph(listOf(glyphCircle(12, 12, 8.8),
    glyphPolyline(listOf(vertex(12, 7.2), vertex(12, 12, 0.6), vertex(15.2, 14)), role = LiteGlyphRole.Detail)))

@Composable
internal fun ToolsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues, open: (DetailPage) -> Unit) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding) {
        item { SectionLabel(stringResource(R.string.lite_more)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column {
                    ToolRow(R.string.proxy_tools_resources, ResourcesGlyph) { open(DetailPage.Resources) }
                    ToolRow(R.string.history_requests, RequestsToolGlyph) { open(DetailPage.Requests) }
                    ToolRow(R.string.history_dns, FullGlyphs.dns) { open(DetailPage.DnsQueries) }
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
                    LanguagePreferenceRow(state, actions)
                    ToolRow(R.string.lite_app_filter, FullGlyphs.appsList) { open(DetailPage.Apps) }
                    ToolRow(R.string.integration_advanced_configuration, AdvancedConfigGlyph) { open(DetailPage.Advanced) }
                    ToolRow(R.string.lite_general, FullGlyphs.settings) { open(DetailPage.General) }
                    ToolRow(R.string.global_resources_title, GlobalResourcesGlyph) { open(DetailPage.GlobalResources) }
                    ToolRow(R.string.backup_title, FullGlyphs.importFile) { open(DetailPage.Backup) }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.lite_other)) }
        item {
            LitePane(Modifier.fillMaxWidth()) { Column {
                ToolRow(R.string.maintenance_disclaimer, DisclaimerGlyph) { open(DetailPage.Disclaimer) }
                ToolRow(R.string.lite_about, FullGlyphs.info) { open(DetailPage.About) }
                if (state.settings.developerMode) ToolRow(R.string.maintenance_developer_mode, DeveloperGlyph) { open(DetailPage.Developer) }
            } }
        }
    }
}

@Composable
private fun ToolRow(label: Int, glyph: LiteGlyph, open: () -> Unit) {
    SettingsRow(stringResource(label), icon = glyph, onClick = open) { LiteIcon(FullGlyphs.chevronForward) }
}

private val AdvancedConfigGlyph = LiteGlyph(listOf(glyphPath(
    "M3.58 17.59L10.1 11.07A1.2 1.2 0 0 0 10.41 9.89" +
    "A5.4 5.4 0 0 1 16.36 3.05A0.9 0.9 0 0 1 16.87 4.58L14.04 7.41" +
    "A1.8 1.8 0 0 0 16.59 9.96L19.42 7.13A0.9 0.9 0 0 1 20.95 7.64" +
    "A5.4 5.4 0 0 1 14.11 13.59A1.2 1.2 0 0 0 12.93 13.9L6.41 20.42" +
    "A2 2 0 0 1 3.58 17.59Z")))
private val DisclaimerGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(14.93, 7.15, 1.5), vertex(11.25, 3.47, 1.5), vertex(3.47, 11.25, 1.5), vertex(7.15, 14.93, 1.5)), closed = true),
    glyphPolyline(listOf(vertex(11.04, 11.04), vertex(19.8, 19.8)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(3.2, 20.5), vertex(11.5, 20.5)), role = LiteGlyphRole.Line)))
private val DeveloperGlyph = LiteGlyph(buildList {
    add(glyphBox(5.5, 5.5, 18.5, 18.5, 2.2))
    add(glyphBox(9.2, 9.2, 14.8, 14.8, 1.2, role = LiteGlyphRole.Detail))
    listOf(8.4, 12.0, 15.6).forEach { coordinate ->
        add(glyphPolyline(listOf(vertex(coordinate, 5.5), vertex(coordinate, 2.9)), role = LiteGlyphRole.Line))
        add(glyphPolyline(listOf(vertex(coordinate, 18.5), vertex(coordinate, 21.1)), role = LiteGlyphRole.Line))
        add(glyphPolyline(listOf(vertex(5.5, coordinate), vertex(2.9, coordinate)), role = LiteGlyphRole.Line))
        add(glyphPolyline(listOf(vertex(18.5, coordinate), vertex(21.1, coordinate)), role = LiteGlyphRole.Line))
    }
})
private val AdvancedNetworkGlyph = LiteGlyph(listOf(glyphCircle(9.27, 7.99, 5),
    glyphCircle(9.27, 7.99, 1.4, role = LiteGlyphRole.Detail, solid = true),
    glyphPolyline(listOf(vertex(12.8, 11.53), vertex(19.73, 18.46, 0.8), vertex(17.19, 21.01)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(17.19, 15.91), vertex(15.07, 18.04)), role = LiteGlyphRole.Line)))
private val AdvancedScriptGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(7.5, 7.5), vertex(3, 12, 0.5), vertex(7.5, 16.5)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(16.5, 7.5), vertex(21, 12, 0.5), vertex(16.5, 16.5)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(13.8, 5), vertex(10.2, 19)), role = LiteGlyphRole.Line)))

@Composable
internal fun AdvancedConfigScreen(padding: PaddingValues, open: (DetailPage) -> Unit) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding) {
        item { LitePane(Modifier.fillMaxWidth()) { Column {
            ToolRow(R.string.network_title, AdvancedNetworkGlyph) { open(DetailPage.Network) }
            ToolRow(R.string.lite_dns, FullGlyphs.dns) { open(DetailPage.Dns) }
            ToolRow(R.string.override_global_rules, FullGlyphs.rules) { open(DetailPage.GlobalRules) }
            ToolRow(R.string.override_scripts, AdvancedScriptGlyph) { open(DetailPage.Scripts) }
        } } }
    }
}

private val GlobalResourcesGlyph = LiteGlyph(listOf(
    glyphBox(3.5, 3.5, 10.5, 10.5, 2.0), glyphBox(3.5, 13.5, 10.5, 20.5, 2.0),
    glyphBox(13.5, 13.5, 20.5, 20.5, 2.0),
    glyphPolyline(listOf(vertex(17, 4), vertex(17, 10)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(14, 7), vertex(20, 7)), role = LiteGlyphRole.Line)))

private val ResourcesGlyph = LiteGlyph(listOf(glyphBox(3, 3.5, 21, 8.5, 1.8),
    glyphBox(4.5, 10.8, 19.5, 20.5, 2.2),
    glyphPolyline(listOf(vertex(10, 14.2), vertex(14, 14.2)), role = LiteGlyphRole.Detail)))
