package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION
import dev.aerove.lite.maintenance.Restatement
import dev.aerove.lite.maintenance.restatement

/** Read-only from settings; startup consent is persisted only after the second confirmation. */
@Composable
internal fun DisclaimerScreen(requireConsent: Boolean, onAccepted: (Int) -> Unit, onDeclined: () -> Unit,
    padding: PaddingValues = PaddingValues(16.dp), remoteInput: Boolean = false) {
    val overlay = LocalLiteOverlay.current
    val accepted by rememberUpdatedState(onAccepted)
    val declined by rememberUpdatedState(onDeclined)
    if (requireConsent) BackHandler { overlay.dismiss(); declined() }
    val terms = listOf(
        R.string.maintenance_disclaimer_software_title to R.string.maintenance_disclaimer_software_content,
        R.string.maintenance_disclaimer_usage_title to R.string.maintenance_disclaimer_usage_content,
        R.string.maintenance_disclaimer_responsibility_title to R.string.maintenance_disclaimer_responsibility_content,
        R.string.maintenance_disclaimer_third_party_title to R.string.maintenance_disclaimer_third_party_content,
        R.string.maintenance_disclaimer_warranty_title to R.string.maintenance_disclaimer_warranty_content,
        R.string.maintenance_disclaimer_liability_title to R.string.maintenance_disclaimer_liability_content,
        R.string.maintenance_disclaimer_privacy_title to R.string.maintenance_disclaimer_privacy_content,
        R.string.maintenance_disclaimer_license_title to R.string.maintenance_disclaimer_license_content,
        R.string.maintenance_disclaimer_changes_title to R.string.maintenance_disclaimer_changes_content,
        R.string.maintenance_disclaimer_accept_title to R.string.maintenance_disclaimer_accept_content,
    )
    Column {
        LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { LitePane(Modifier.fillMaxWidth()) { LiteText(stringResource(R.string.maintenance_disclaimer_desc), Modifier.padding(20.dp)) } }
            terms.forEachIndexed { index, (title, content) -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText("${index + 1}. ${stringResource(title)}", style = LiteType.Headline)
                    LiteText(stringResource(content))
                    if (index == 0) LiteText(stringResource(R.string.maintenance_disclaimer_no_service_statement))
                }
            } }
        }
        if (requireConsent) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiteButton(stringResource(R.string.maintenance_exit), { declined() }, Modifier.weight(1f))
            LiteButton(stringResource(R.string.maintenance_agree), {
                overlay.content = {
                    DisclaimerRestatementDialog(remoteInput = remoteInput, onCancel = overlay::dismiss, onConfirm = {
                        overlay.dismiss()
                        accepted(CURRENT_DISCLAIMER_VERSION)
                    })
                }
            }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DisclaimerRestatementDialog(remoteInput: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue()) }
    var acknowledged by remember { mutableStateOf(false) }
    val statement = stringResource(R.string.maintenance_disclaimer_no_service_statement)
    val status = restatement(input.text, statement, input.composition?.start ?: -1, input.composition?.end ?: -1)
    val label = stringResource(R.string.maintenance_disclaimer_restate_hint)
    // Standard's second alert does not accept outside dismissal; Cancel remains explicit.
    LiteModal(stringResource(if (remoteInput) R.string.maintenance_disclaimer_acknowledge_title else R.string.maintenance_disclaimer_restate_title), {}) {
        LiteText(stringResource(if (remoteInput) R.string.maintenance_disclaimer_acknowledge_tip else R.string.maintenance_disclaimer_restate_tip))
        LiteText(statement)
        if (remoteInput) LiteSwitch(acknowledged, stringResource(R.string.maintenance_disclaimer_acknowledge_label), { acknowledged = it })
        else LitePane(Modifier.fillMaxWidth(), corner = LiteCorner.Control) {
            BasicTextField(input, { input = it }, Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = label },
                textStyle = LiteType.Body.copy(color = LocalLiteVisuals.current.ink, fontFamily = LocalLiteFontFamily.current), cursorBrush = SolidColor(LocalLiteVisuals.current.primary))
        }
        if (!remoteInput && status == Restatement.Diverged) LiteText(stringResource(R.string.maintenance_disclaimer_restate_mismatch))
        LiteButton(stringResource(R.string.maintenance_confirm), onConfirm, enabled = if (remoteInput) acknowledged else status == Restatement.Complete)
        LiteButton(stringResource(R.string.maintenance_cancel), onCancel)
    }
}
