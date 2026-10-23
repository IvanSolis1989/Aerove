package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.overrides.OverrideMode
import dev.aerove.lite.overrides.OverrideSnapshot
import dev.aerove.lite.overrides.ProfileOverride

@Composable
internal fun OverrideScreen(profile: ProfileOverride, snapshot: OverrideSnapshot,
    onChange: (ProfileOverride) -> Unit, onScripts: () -> Unit,
    padding: PaddingValues = PaddingValues(16.dp), enabled: Boolean = true) {
    val current by rememberUpdatedState(profile)
    val latestChange by rememberUpdatedState(onChange)
    val rules = remember(profile.addedRules) { profileOverrideRules(profile.addedRules) }
    if (profile.mode == OverrideMode.Standard) {
        OverrideRulesList(rules, { latestChange(current.copy(addedRules = it.map { rule -> rule.value })) }, padding, enabled,
            header = { item { OverrideModeHeader(profile.mode, enabled) { latestChange(current.copy(mode = it)) } } },
            footer = {
                item { LiteText(stringResource(R.string.override_global_rules), Modifier.padding(top = 20.dp, bottom = 8.dp), style = LiteType.Headline) }
                itemsIndexed(snapshot.globalRules, key = { _, rule -> "enabled:${rule.id}" }) { _, rule ->
                    SettingsRow(rule.value) { LiteSwitch(rule.id !in profile.disabledGlobalRuleIds, rule.value, { checked -> latestChange(current.copy(
                        disabledGlobalRuleIds = if (checked) current.disabledGlobalRuleIds - rule.id else current.disabledGlobalRuleIds + rule.id,
                    )) }, enabled) }
                }
                item { LiteField(profile.matchTarget.orEmpty(), { latestChange(current.copy(matchTarget = it.takeIf(String::isNotBlank))) },
                    stringResource(R.string.override_match_target), enabled = enabled) }
            })
    } else {
        LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OverrideModeHeader(profile.mode, enabled) { latestChange(current.copy(mode = it)) } }
            itemsIndexed(snapshot.scripts, key = { _, script -> script.id }) { _, script ->
                SettingsRow(script.name, onClick = { if (enabled) latestChange(current.copy(scriptId = if (current.scriptId == script.id) null else script.id)) }) {
                    if (profile.scriptId == script.id) LiteIcon(FullGlyphs.check)
                }
            }
            item { LiteButton(stringResource(R.string.override_manage_scripts), onScripts, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun OverrideModeHeader(mode: OverrideMode, enabled: Boolean, change: (OverrideMode) -> Unit) {
    Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiteText(stringResource(R.string.override_mode), style = LiteType.Headline)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiteButton(stringResource(R.string.override_standard), { change(OverrideMode.Standard) }, selected = mode == OverrideMode.Standard, enabled = enabled)
            LiteButton(stringResource(R.string.override_script), { change(OverrideMode.Script) }, selected = mode == OverrideMode.Script, enabled = enabled)
        }
    }
}
