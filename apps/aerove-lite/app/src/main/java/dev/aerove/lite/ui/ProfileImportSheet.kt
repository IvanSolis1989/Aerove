package dev.aerove.lite.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.R
import dev.aerove.lite.integration.ProfileImportWorkspace
import dev.aerove.lite.profilesource.*

@Composable
internal fun ProfileImportSheet(workspace: ProfileImportWorkspace, initialInput: String, dismiss: () -> Unit) {
    val presentation by workspace.state.collectAsState()
    val batch by workspace.batchState.collectAsState()
    var name by remember { mutableStateOf(presentation.name) }
    var input by remember { mutableStateOf(initialInput.ifBlank { presentation.input }) }
    var key by remember { mutableStateOf("") }
    var multiple by remember { mutableStateOf(false) }
    val rows = if (batch.generation == presentation.batchGeneration) batch.items else emptyList()
    val busy = presentation.preparing || (batch.generation == presentation.batchGeneration && batch.running)
    val close = { workspace.dismiss(); dismiss() }
    DisposableEffect(workspace) { onDispose { key = "" } }
    LaunchedEffect(presentation.input, presentation.name) {
        if (presentation.input.isNotEmpty()) {
            if (presentation.input != input.trim()) { input = presentation.input; key = "" }
            name = presentation.name
        }
    }
    LaunchedEffect(rows, busy, presentation.issues) {
        if (!busy && rows.isNotEmpty() && rows.all { it.status == ImportItemStatus.SUCCEEDED } && presentation.issues.isEmpty()) close()
    }
    ProfileMaintenanceSheet(stringResource(R.string.age_import_title), close) {
        LiteButton(stringResource(R.string.age_batch_mode), { multiple = !multiple; key = "" },
            Modifier.testTag("profile-import-batch"), selected = multiple, enabled = !busy)
        if (!multiple) LiteField(name, { name = it }, stringResource(R.string.age_name), enabled = !busy)
        LiteField(input, { if (it.toByteArray().size <= MAX_PLAINTEXT_BYTES) input = it },
            stringResource(if (multiple) R.string.age_urls else R.string.age_url),
            Modifier.testTag("profile-import-input"), singleLine = !multiple, enabled = !busy)
        if (!multiple) AgeIdentityField(key, { key = it }, !busy)
        if (presentation.errorCode != null) LiteText(stringResource(R.string.age_import_failed), Modifier.testTag("profile-import-error"))
        presentation.issues.forEach { issue ->
            LiteText(stringResource(R.string.integration_import_line_error, issue.line), style = LiteType.Caption)
            if (!issue.field.isNullOrBlank() && !issue.reason.isNullOrBlank()) LiteText("${issue.field}: ${issue.reason}", style = LiteType.Caption)
        }
        if (presentation.skipped > 0) LiteText(stringResource(R.string.integration_import_skipped, presentation.skipped), style = LiteType.Caption)
        rows.forEach { item ->
            val label = when (item.status) {
                ImportItemStatus.QUEUED -> R.string.age_queued
                ImportItemStatus.RUNNING -> R.string.age_running
                ImportItemStatus.SUCCEEDED -> R.string.age_succeeded
                ImportItemStatus.FAILED -> if (item.errorCode in setOf("secret_required", "wrong_key", "invalid_identity", "decrypt_failed")) R.string.age_decrypt_failed else R.string.age_failed
                ImportItemStatus.CANCELLED -> R.string.age_cancelled
            }
            LiteText(item.name + " · " + stringResource(label), Modifier.testTag("profile-import-result-${item.id}"), style = LiteType.Caption)
            presentation.itemIssues[item.id].orEmpty().forEach { issue -> LiteText("${issue.path}: ${issue.reason}", style = LiteType.Caption) }
        }
        LiteButton(stringResource(if (busy) R.string.age_cancel else R.string.age_import), {
            if (busy) workspace.cancel()
            else if (multiple) workspace.batch(input)
            else {
                val bytes = key.trim().toByteArray(Charsets.UTF_8)
                val secret = try { if (bytes.isEmpty()) null else AgeSecret(bytes) } finally { bytes.fill(0) }
                workspace.single(NamedProfileInput(name, input.trim(), secret))
            }
        }, Modifier.fillMaxWidth().testTag("profile-import-submit"), enabled = busy || input.isNotBlank())
    }
}
