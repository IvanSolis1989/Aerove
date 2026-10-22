package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R

@Composable
internal fun colorStyleLabel(style: ColorStyle): String = stringResource(when (style) {
    ColorStyle.Vivid -> R.string.lite_style_vivid
    ColorStyle.Tinted -> R.string.lite_style_tinted
    ColorStyle.Soft -> R.string.lite_style_soft
    ColorStyle.Monochrome -> R.string.lite_style_monochrome
})

@Composable
internal fun ColorStyleSetting(state: LiteState, actions: LiteActions) {
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    val title = stringResource(R.string.lite_color_style)
    LitePane(Modifier.fillMaxWidth().clickable(role = Role.Button) {
        overlay.content = {
            LiteModal(title, overlay::dismiss) {
                ColorStyle.entries.forEach { style ->
                    LiteButton(colorStyleLabel(style), {
                        if (!current.settings.aurora) actions.changeSettings(current.settings.copy(colorStyle = style))
                        overlay.dismiss()
                    }, Modifier.fillMaxWidth(), selected = current.settings.colorStyle == style)
                }
            }
        }
    }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LiteText(title, Modifier.weight(1f))
            LiteText(colorStyleLabel(state.settings.colorStyle), style = LiteType.Callout, color = LocalLiteVisuals.current.secondaryInk)
        }
    }
}

@Composable
internal fun AuroraSettings(state: LiteState, actions: LiteActions) {
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    val colors = state.settings.auroraColors
    fun edit(index: Int) {
        if (!current.settings.aurora || index > colors.size || (index == colors.size && colors.size >= 3)) return
        val seed = current.settings.auroraSeed
        overlay.content = {
            AuroraColorEditor(colors, seed, index, overlay::dismiss) { selected ->
                if (current.settings.aurora) {
                    val updated = current.settings.auroraColors.toMutableList()
                    if (selected == null) {
                        if (updated.size > 1 && index in updated.indices) updated.removeAt(index)
                    } else if (index in updated.indices) updated[index] = selected
                    else if (index == updated.size && updated.size < 3) updated.add(selected)
                    actions.changeSettings(current.settings.copy(auroraColors = updated))
                }
                overlay.dismiss()
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.lite_aurora_colors))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            colors.forEachIndexed { index, value ->
                val label = stringResource(R.string.lite_aurora_edit_color, index + 1)
                Box(Modifier.size(48.dp).clip(rememberLiteShape(LiteCorner.Small)).background(Color(value.toInt()))
                    .semantics { contentDescription = label }
                    .clickable(role = Role.Button) { edit(index) })
            }
            if (colors.size < 3) LiteIconButton(stringResource(R.string.lite_add_color), FullGlyphs.add, { edit(colors.size) }, Modifier.size(48.dp))
        }
        AuroraPreview(colors, state.settings.auroraSeed)
        LiteButton(stringResource(R.string.lite_aurora_regenerate), {
            if (current.settings.aurora) actions.shuffleAurora()
        }, Modifier.align(Alignment.End), FullGlyphs.shuffle, enabled = state.settings.aurora)
    }
}

@Composable
private fun AuroraPreview(colors: List<Long>, seed: Long) {
    val label = stringResource(R.string.lite_preview)
    AuroraWallpaper(LocalAuroraPhase.current, colors, seed, LocalLiteVisuals.current.dark,
        Modifier.fillMaxWidth().aspectRatio(2.6f).clip(rememberLiteShape(LiteCorner.Block)).semantics { contentDescription = label })
}

@Composable
private fun AuroraColorEditor(
    colors: List<Long>, seed: Long, index: Int, dismiss: () -> Unit, confirm: (Long?) -> Unit,
) {
    var draft by remember { mutableStateOf(colors.getOrElse(index) { 0xffb9ccd1L }.toInt()) }
    LiteModal(stringResource(R.string.lite_aurora_colors), dismiss) {
        val preview = colors.toMutableList().apply {
            if (index in indices) this[index] = draft.toLong() and 0xffffffffL else add(draft.toLong() and 0xffffffffL)
        }
        AuroraPreview(preview, seed)
        FullColorPalette(draft) { draft = it }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (index in colors.indices && colors.size > 1) LiteButton(stringResource(R.string.lite_delete), { confirm(null) }, Modifier.weight(1f), destructive = true)
            LiteButton(stringResource(R.string.lite_cancel), dismiss, Modifier.weight(1f))
            LiteButton(stringResource(R.string.lite_confirm), { confirm(draft.toLong() and 0xffffffffL) }, Modifier.weight(1f))
        }
    }
}
