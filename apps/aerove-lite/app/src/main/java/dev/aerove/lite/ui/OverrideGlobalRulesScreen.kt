package dev.aerove.lite.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.aerove.lite.overrides.OverrideRule

@Composable
internal fun OverrideGlobalRulesScreen(rules: List<OverrideRule>, onChange: (List<OverrideRule>) -> Unit,
    padding: PaddingValues = PaddingValues(16.dp), enabled: Boolean = true) {
    OverrideRulesList(rules, onChange, padding, enabled, searchable = true)
}
