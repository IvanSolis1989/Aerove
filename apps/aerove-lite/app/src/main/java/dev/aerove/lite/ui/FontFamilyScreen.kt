package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.presentation.SystemFontCatalog
import kotlinx.coroutines.CancellationException

@Composable
internal fun FontFamilyScreen(state: LiteState, actions: LiteActions, padding: PaddingValues,
    loadFamilies: suspend () -> List<String> = { SystemFontCatalog.load() }) {
    var retry by remember { mutableIntStateOf(0) }
    var families by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val loader by rememberUpdatedState(loadFamilies)
    LaunchedEffect(retry) {
        failed = false
        families = null
        try { families = loader() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val current by rememberUpdatedState(state)
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FontFamilyOption(null, state.settings.fontFamily == null, state.busy.isEmpty()) {
            actions.changeSettings(current.settings.copy(fontFamily = null))
        } }
        item { SectionLabel(stringResource(R.string.presentation_fonts_installed)) }
        when {
            failed -> item { LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteText(stringResource(R.string.presentation_fonts_error))
                    LiteButton(stringResource(R.string.presentation_retry), { retry++ })
                }
            } }
            families == null -> item { LiteText(stringResource(R.string.presentation_fonts_loading)) }
            families!!.isEmpty() -> item { LiteText(stringResource(R.string.presentation_fonts_empty)) }
            else -> items(families!!, key = { it }) { family ->
                FontFamilyOption(family, state.settings.fontFamily == family, state.busy.isEmpty()) {
                    actions.changeSettings(current.settings.copy(fontFamily = family))
                }
            }
        }
    }
}

@Composable
private fun FontFamilyOption(name: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val family = remember(name) {
        name?.let { FontFamily(android.graphics.Typeface.create(it, android.graphics.Typeface.NORMAL)) } ?: FontFamily.Default
    }
    LitePane(Modifier.fillMaxWidth().testTag("font-family-${name ?: "default"}"), selected = selected) {
        Column {
            SettingsRow(name ?: stringResource(R.string.presentation_font_default),
                onClick = if (enabled) onSelect else null, trailing = { if (selected) LiteIcon(FullGlyphs.check) })
            // LazyColumn resolves previews only for composed rows; fallback glyphs remain platform-owned.
            LiteText(stringResource(R.string.presentation_font_preview),
                Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), style = LiteType.Body.copy(fontFamily = family))
        }
    }
}
