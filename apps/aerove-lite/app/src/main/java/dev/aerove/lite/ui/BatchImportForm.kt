package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.R
import dev.aerove.lite.profilesource.AgeSecret
import dev.aerove.lite.profilesource.BatchImportState
import dev.aerove.lite.profilesource.NamedProfileInput
import dev.aerove.lite.profilesource.MAX_PLAINTEXT_BYTES

/** Host dismisses only after actual saved results, keeping errors and partial successes visible. */
@Composable
internal fun BatchImportForm(state: BatchImportState, errorCode: String?, dismiss: () -> Unit,
    single: (NamedProfileInput) -> Unit, batch: (String) -> Unit, cancel: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var multiple by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { secret = "" } }
    ProfileMaintenanceSheet(stringResource(R.string.age_import_title), { cancel(); secret = ""; dismiss() }) {
        LiteButton(stringResource(R.string.age_batch_mode), { multiple = !multiple; secret = "" }, selected = multiple, enabled = !state.running)
        if (!multiple) LiteField(label, { label = it }, stringResource(R.string.age_name), enabled = !state.running)
        LiteField(input, { if (it.toByteArray().size <= MAX_PLAINTEXT_BYTES) input = it },
            stringResource(if (multiple) R.string.age_urls else R.string.age_url), singleLine = !multiple, enabled = !state.running)
        if (!multiple) AgeIdentityField(secret, { secret = it }, !state.running)
        if (errorCode != null) LiteText(stringResource(R.string.age_import_failed), style = LiteType.Caption)
        state.items.forEach { item ->
            val status = when (item.status) {
                dev.aerove.lite.profilesource.ImportItemStatus.QUEUED -> R.string.age_queued
                dev.aerove.lite.profilesource.ImportItemStatus.RUNNING -> R.string.age_running
                dev.aerove.lite.profilesource.ImportItemStatus.SUCCEEDED -> R.string.age_succeeded
                dev.aerove.lite.profilesource.ImportItemStatus.FAILED -> R.string.age_failed
                dev.aerove.lite.profilesource.ImportItemStatus.CANCELLED -> R.string.age_cancelled
            }
            LiteText(item.name + " · " + stringResource(status), style = LiteType.Caption)
        }
        LiteButton(stringResource(R.string.age_import), {
            if (multiple) batch(input) else {
                val bytes = secret.trim().toByteArray()
                val identity = if (bytes.isEmpty()) null else AgeSecret(bytes)
                bytes.fill(0); secret = ""
                single(NamedProfileInput(label, input.trim(), identity))
            }
        }, enabled = !state.running && input.isNotBlank() && label.length <= 200)
        if (state.running) LiteButton(stringResource(R.string.age_cancel), cancel)
    }
}
