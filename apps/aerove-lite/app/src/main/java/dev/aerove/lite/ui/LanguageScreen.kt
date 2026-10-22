package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.localization.LocaleChoice
import dev.aerove.lite.localization.canonicalLocale
import dev.aerove.lite.localization.localeChoice
import kotlinx.coroutines.flow.first
import dev.aerove.lite.optics.AeroveGlassStyle

@Composable
internal fun localeLabel(tag: String?): String = stringResource(when (localeChoice(tag)) {
    null -> R.string.locale_default
    LocaleChoice.English -> R.string.locale_en
    LocaleChoice.Spanish -> R.string.locale_es
    LocaleChoice.French -> R.string.locale_fr
    LocaleChoice.Japanese -> R.string.locale_ja
    LocaleChoice.Korean -> R.string.locale_ko
    LocaleChoice.Russian -> R.string.locale_ru
    LocaleChoice.SimplifiedChinese -> R.string.locale_zh_cn
})

// Standard ui/list.dart _OptionsValueButton uses the exact AppGlyphs.picker geometry.
private val LanguagePickerGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(7.5, 9.5), vertex(12, 5, 0.4), vertex(16.5, 9.5)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(7.5, 14.5), vertex(12, 19, 0.4), vertex(16.5, 14.5)), role = LiteGlyphRole.Line),
))

@Composable
internal fun LanguagePreferenceRow(state: LiteState, actions: LiteActions) {
    val current by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val overlay = LocalLiteOverlay.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val enabled = state.busy.isEmpty()
    fun open() {
        if (current.busy.isNotEmpty() || anchor == Rect.Zero || overlay.content != null) return
        val source = anchor
        overlay.content = {
            LanguageMenu(current, source, overlay::dismiss) { tag ->
                // Close before persistence can update Android locales and recreate the activity.
                overlay.dismiss()
                val latest = current.settings
                if (canonicalLocale(latest.locale) != tag) currentActions.changeSettings(latest.copy(locale = tag))
            }
        }
    }
    Box(Modifier.fillMaxWidth().testTag("language-preference-row")
        .clickable(enabled = enabled, role = Role.Button, onClick = ::open)) {
        SettingsRow(stringResource(R.string.locale_language), icon = FullGlyphs.language, trailing = {
            Row(Modifier.testTag("language-preference-value").onGloballyPositioned { anchor = it.boundsInWindow() }
                .clickable(enabled = enabled, role = Role.Button, onClick = ::open)
                .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LiteText(localeLabel(state.settings.locale), Modifier.widthIn(max = 160.dp),
                    style = LiteType.Body, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
                LiteIcon(LanguagePickerGlyph, color = LocalLiteVisuals.current.secondaryInk)
            }
        })
    }
}

@Composable
private fun LanguageMenu(state: LiteState, anchor: Rect, dismiss: () -> Unit, select: (String?) -> Unit) {
    BackHandler(onBack = dismiss)
    val choices = listOf<LocaleChoice?>(null) + LocaleChoice.entries
    val labels = choices.map { localeLabel(it?.tag) }
    val selected = canonicalLocale(state.settings.locale)
    val title = stringResource(R.string.locale_language)
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = MaterialTheme.colorScheme
    val style = LiteType.Label.copy(fontWeight = FontWeight.Normal, fontFamily = LocalLiteFontFamily.current)
    val measurer = rememberTextMeasurer()
    val labelWidth = labels.maxOf { measurer.measure(AnnotatedString(it), style = style).size.width }
    val rowExtent = 24.dp + (20 * density.fontScale).dp
    val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    val scroll = rememberScrollState()
    LaunchedEffect(selected, rowExtent) {
        val index = choices.indexOfFirst { it?.tag == selected }.coerceAtLeast(0)
        val extent = with(density) { rowExtent.toPx() }
        if (index > 0) snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
        scroll.scrollTo(((index - 3).coerceAtLeast(0) * extent).toInt())
    }
    // CommonPopupRoute has a transparent modal barrier, not the sheet/dialog scrim.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().testTag("language-menu-dismiss").clickable(onClick = dismiss))
        val width = minOf(maxWidth - 32.dp, maxOf(160.dp, minOf(280.dp, with(density) { labelWidth.toDp() } + 64.dp)))
        val height = minOf(rowExtent * 7.5f + 16.dp, (maxHeight - safeTop - safeBottom - 32.dp).coerceAtLeast(0.dp))
        val left = (with(density) { anchor.right.toDp() } - width)
            .coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val top = (with(density) { anchor.top.toDp() } - 8.dp)
            .coerceIn(safeTop + 16.dp, maxOf(safeTop + 16.dp, maxHeight - safeBottom - height - 16.dp))
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.absoluteOffset(left, top).width(width).testTag("language-menu")
            .semantics { paneTitle = title }.pointerInput(Unit) { detectTapGestures {} }, corner = LiteCorner.Card,
            solidColor = fill, glassStyle = AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.heightIn(max = height).verticalScroll(scroll).padding(8.dp)) {
                choices.forEachIndexed { index, choice ->
                    val chosen = choice?.tag == selected
                    Row(Modifier.fillMaxWidth().testTag("locale-option-${choice?.tag ?: "system"}")
                        .semantics { this.selected = chosen }
                        .clickable(enabled = state.busy.isEmpty(), role = Role.Button, onClick = { select(choice?.tag) })
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        LiteText(labels[index], Modifier.weight(1f), style = style)
                        if (chosen) {
                            Spacer(Modifier.width(8.dp))
                            LiteIcon(FullGlyphs.check, size = 16.dp)
                        }
                    }
                }
            }
        }
    }
}
