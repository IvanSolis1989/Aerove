package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.R
import dev.aerove.lite.overrides.OverridePreset

@Composable
internal fun OverridePresetSheet(dismiss: () -> Unit, add: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<OverridePreset>()) }
    LiteModal(stringResource(R.string.override_presets), dismiss) {
        OverridePreset.entries.forEach { preset ->
            LiteSwitch(preset in selected, stringResource(when (preset) {
                OverridePreset.BlockQuic -> R.string.override_preset_quic
                OverridePreset.BlockStun -> R.string.override_preset_stun
                OverridePreset.BlockDot -> R.string.override_preset_dot
                OverridePreset.BlockLoopback -> R.string.override_preset_loopback
                OverridePreset.LanDirect -> R.string.override_preset_lan
                OverridePreset.SystemDirect -> R.string.override_preset_system
                OverridePreset.BittorrentDirect -> R.string.override_preset_bittorrent
            }), { checked -> selected = if (checked) selected + preset else selected - preset })
            preset.rules.forEach { LiteText(it, style = LiteType.Caption, maxLines = 1) }
        }
        LiteButton(stringResource(R.string.override_add_rule), { add(OverridePreset.entries.filter { it in selected }.flatMap { it.rules }); dismiss() }, enabled = selected.isNotEmpty())
    }
}
