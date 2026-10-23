package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.presentation.SystemFontCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun FontFamilyScreen(state: LiteState, actions: LiteActions, padding: PaddingValues,
    loadFamilies: (suspend () -> List<String>)? = null) {
    var retry by remember { mutableIntStateOf(0) }
    var families by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current.toString()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loader by rememberUpdatedState(loadFamilies ?: { SystemFontCatalog.load(configuration) })
    LaunchedEffect(retry, configuration, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            failed = false
            try { families = loader() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            awaitCancellation()
        }
    }
    val current by rememberUpdatedState(state)
    val effective = SystemFontCatalog.resolve(state.settings.fontFamily, families.orEmpty())
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FontFamilyOption(null, effective == null, state.busy.isEmpty()) {
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
                FontFamilyOption(family, effective == family, state.busy.isEmpty()) {
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
