package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.presentation.*
import kotlin.math.roundToInt

@Composable
internal fun DisplayPreferencesSection(state: LiteState, actions: LiteActions, onFontFamily: (() -> Unit)?) {
    val enabled = state.busy.isEmpty()
    val current by rememberUpdatedState(state)
    val settings = state.settings
    LitePane(Modifier.fillMaxWidth()) {
        Column {
            if (onFontFamily != null) SettingsRow(stringResource(R.string.presentation_font_family),
                settings.fontFamily ?: stringResource(R.string.presentation_font_default), onClick = if (enabled) onFontFamily else null)
            SettingsRow(stringResource(R.string.presentation_pure_black), stringResource(R.string.presentation_pure_black_detail), trailing = {
                LiteSwitch(settings.pureBlack, stringResource(R.string.presentation_pure_black), {
                    actions.changeSettings(current.settings.copy(pureBlack = it))
                }, enabled)
            })
            SettingsRow(stringResource(R.string.presentation_text_scale), trailing = {
                LiteSwitch(settings.textScaleEnabled, stringResource(R.string.presentation_text_scale), {
                    actions.changeSettings(current.settings.copy(textScaleEnabled = it))
                }, enabled)
            })
            if (settings.textScaleEnabled) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                TextScaleControl(settings.textScale, enabled) { actions.changeSettings(current.settings.copy(textScale = it)) }
                LiteButton(stringResource(R.string.lite_reset), {
                    actions.changeSettings(current.settings.copy(textScale = 1f))
                }, enabled = enabled && settings.textScale != 1f)
            }
        }
    }
}

@Composable
private fun TextScaleControl(value: Float, enabled: Boolean, onCommit: (Float) -> Unit) {
    var draft by remember { mutableFloatStateOf(effectiveTextScale(1f, true, value)) }
    LaunchedEffect(value) { draft = effectiveTextScale(1f, true, value) }
    val latestValue by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onCommit)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = stringResource(R.string.presentation_text_scale)
    val percent = "${(draft * 100).roundToInt()}%"
    val visuals = LocalLiteVisuals.current
    val density = LocalDensity.current
    fun choose(fraction: Float) {
        val raw = MinimumTextScale + fraction.coerceIn(0f, 1f) * (MaximumTextScale - MinimumTextScale)
        draft = (raw / TextScaleStep).roundToInt() * TextScaleStep
        draft = draft.coerceIn(MinimumTextScale, MaximumTextScale)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, draft)) {
            LiteText(stringResource(R.string.presentation_text_scale_preview), style = LiteType.Label)
        }
        LiteText(percent, style = LiteType.Label)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).testTag("presentation-text-scale")
        .progressSemantics(draft, MinimumTextScale..MaximumTextScale, 11).semantics {
            contentDescription = label
            stateDescription = percent
            if (enabled) setProgress { choose((it - MinimumTextScale) / (MaximumTextScale - MinimumTextScale)); commit(draft); true }
            else disabled()
        }.focusable(enabled).pointerInput(enabled, rtl) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown()
                fun pick(x: Float) {
                    val inset = 14.dp.toPx()
                    val fraction = (x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)
                    choose(if (rtl) 1f - fraction else fraction)
                }
                pick(down.position.x)
                val finished = drag(down.id) { change -> pick(change.position.x); change.consume() }
                if (finished) commit(draft) else draft = effectiveTextScale(1f, true, latestValue)
            }
        }) {
        LitePane(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth().height(20.dp), corner = LiteCorner.Full) {
            Box(Modifier.fillMaxSize().background(visuals.primary.copy(alpha = 0.14f)))
        }
        val fraction = (draft - MinimumTextScale) / (MaximumTextScale - MinimumTextScale)
        LitePane(Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 28.dp) * fraction).size(28.dp),
            corner = LiteCorner.Full, solidColor = visuals.primary, glassTint = visuals.primary.copy(alpha = 0.18f)) {}
    }
}
