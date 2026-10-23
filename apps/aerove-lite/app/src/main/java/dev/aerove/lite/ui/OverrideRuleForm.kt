package dev.aerove.lite.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import dev.aerove.lite.R
import dev.aerove.lite.overrides.OverrideRuleDraft
import dev.aerove.lite.overrides.RuleDraftIssue

@Composable
internal fun OverrideRuleForm(initial: String?, close: () -> Unit, save: (String) -> Unit, enabled: Boolean = true) {
    var draft by remember(initial) { mutableStateOf(initial?.let(OverrideRuleDraft::parse) ?: OverrideRuleDraft()) }
    var attempted by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    ProfileMaintenanceSheet(stringResource(if (initial == null) R.string.override_add_rule else R.string.override_edit_rule), close) {
        Box(Modifier.testTag("override-rule-action").clickable(enabled = enabled) { choosing = "action" }) { SettingsRow(stringResource(R.string.override_rule_type)) {
            LiteText(draft.action)
        } }
        LiteField(draft.content, { draft = draft.copy(content = it) }, stringResource(R.string.override_content), Modifier.testTag("override-rule-content"), enabled = enabled)
        Box(Modifier.testTag("override-rule-target").clickable(enabled = enabled) { choosing = "target" }) { SettingsRow(stringResource(R.string.override_rule_target)) {
            LiteText(if (draft.target == "MATCH") stringResource(R.string.override_match_target) else draft.target)
        } }
        if (draft.hasParams) {
            SettingsRow(stringResource(R.string.override_no_resolve), onClick = { if (enabled) draft = draft.copy(noResolve = !draft.noResolve) }) {
                LiteSwitch(draft.noResolve, stringResource(R.string.override_no_resolve), { draft = draft.copy(noResolve = it) }, enabled)
            }
            SettingsRow(stringResource(R.string.override_match_source), onClick = { if (enabled) draft = draft.copy(src = !draft.src) }) {
                LiteSwitch(draft.src, stringResource(R.string.override_match_source), { draft = draft.copy(src = it) }, enabled)
            }
        }
        if (attempted && draft.issue != null) LiteText(stringResource(when (draft.issue!!) {
            RuleDraftIssue.ContentRequired -> R.string.override_content_required
            RuleDraftIssue.TargetRequired -> R.string.override_target_required
            RuleDraftIssue.Network -> R.string.override_network_error
            RuleDraftIssue.NumberRange -> R.string.override_range_error
            RuleDraftIssue.DscpRange -> R.string.override_dscp_error
            RuleDraftIssue.InvalidPayload -> R.string.override_payload_error
        }), Modifier.testTag("override-rule-error"), style = LiteType.Caption)
        LiteButton(stringResource(R.string.override_save), { attempted = true; if (draft.issue == null) save(draft.rawValue()) },
            Modifier.testTag("override-rule-save"), enabled = enabled)
        LiteButton(stringResource(R.string.override_cancel), close, Modifier.testTag("override-rule-cancel"))
    }
    choosing?.let { field ->
        val options = if (field == "action") (listOf(draft.action) + OverrideRuleDraft.actions).distinct()
            else (listOf(draft.target) + OverrideRuleDraft.targets).filter(String::isNotEmpty).distinct()
        ProfileMaintenanceSheet(stringResource(if (field == "action") R.string.override_rule_type else R.string.override_rule_target), { choosing = null }) {
            options.forEach { value -> Box(Modifier.testTag("override-option-$value").clickable {
                draft = if (field == "action") draft.copy(action = value) else draft.copy(target = value)
                choosing = null
            }) { SettingsRow(value) { if (value == if (field == "action") draft.action else draft.target) LiteIcon(FullGlyphs.check) } } }
        }
    }
}
