package dev.aerove.lite.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.backup.BackupActions
import dev.aerove.lite.backup.BackupConfirmation
import dev.aerove.lite.backup.BackupConfirmationKind
import dev.aerove.lite.backup.BackupConnection
import dev.aerove.lite.backup.BackupDocumentKind
import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.backup.BackupNotice
import dev.aerove.lite.backup.BackupNoticeCode
import dev.aerove.lite.backup.BackupProgress
import dev.aerove.lite.backup.BackupUiState
import dev.aerove.lite.backup.BackupWebDavUi
import dev.aerove.lite.data.BackupRestoreScope
import dev.aerove.lite.data.BackupRestoreStrategy

@Composable
fun BackupScreen(feature: BackupFeature, padding: PaddingValues) {
    val state by feature.state.collectAsState()
    var createRequest by rememberSaveable { mutableLongStateOf(0L) }
    var openRequest by rememberSaveable { mutableLongStateOf(0L) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        feature.documentResult(createRequest, uri)
        createRequest = 0L
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        feature.documentResult(openRequest, uri)
        openRequest = 0L
    }
    LaunchedEffect(feature) { feature.open() }
    LaunchedEffect(feature, state.document?.id) {
        state.document?.let { request ->
            if (feature.documentLaunched(request.id)) try {
                if (request.kind == BackupDocumentKind.Create) {
                    createRequest = request.id
                    createDocument.launch(request.suggestedName)
                } else {
                    openRequest = request.id
                    openDocument.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            } catch (_: RuntimeException) { feature.documentLaunchFailed(request.id) }
        }
    }

    var configure by rememberSaveable { mutableStateOf(false) }
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    val owned = remember(feature, overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    LaunchedEffect(state.notice?.id) {
        if (state.notice?.code == BackupNoticeCode.Configured) configure = false
    }
    LaunchedEffect(feature, configure, state.confirmation?.id) {
        val confirmation = state.confirmation
        val content: (@Composable () -> Unit)? = when {
            configure -> ({
                val dismiss = { if (!current.busy) configure = false }
                LiteModal(stringResource(R.string.backup_webdav_settings), dismiss) {
                    BackupWebDavEditor(current.webDav, current.busy, current.notice?.takeIf { it.error }, current.progress,
                        feature, dismiss)
                }
            })
            confirmation != null -> ({
                BackupConfirmationDialog(confirmation, feature)
            })
            else -> null
        }
        if (content != null) { owned[0] = content; overlay.content = content }
        else { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    }
    DisposableEffect(feature, overlay) {
        onDispose { if (overlay.content === owned[0]) overlay.dismiss() }
    }
    BackupScreenContent(state, feature, padding) { configure = true }
}

@Composable
internal fun BackupScreenContent(state: BackupUiState, actions: BackupActions, padding: PaddingValues, configure: () -> Unit) {
    val ready = state.loaded && !state.busy && state.notice?.code != BackupNoticeCode.RefreshAfterRestoreFailed
    val webDav = state.webDav
    LazyColumn(
        Modifier.fillMaxSize().testTag("backup-content"), state = LocalLiteListState.current,
        contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("intro") {
            BackupCard {
                LiteText(stringResource(R.string.backup_title), style = LiteType.Headline)
                LiteText(stringResource(R.string.backup_contents), color = LocalLiteVisuals.current.secondaryInk)
                LiteText(stringResource(R.string.backup_private_archive), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
            }
        }
        state.progress?.let { progress -> item("progress") { BackupCard { BackupProgressRow(progress, actions::cancel) } } }
        state.notice?.let { notice -> item("notice") { BackupCard { BackupNoticeRow(notice, actions) } } }
        item("scope") {
            SectionLabel(stringResource(R.string.backup_restore_options))
            BackupCard {
                LiteText(stringResource(R.string.backup_scope), style = LiteType.Label)
                LiteChoiceRow(BackupRestoreScope.entries, state.options.scope,
                    { stringResource(if (it == BackupRestoreScope.All) R.string.backup_all else R.string.backup_profiles_only) },
                    { actions.changeOptions(state.options.copy(scope = it)) }, enabled = ready)
                LiteText(stringResource(if (state.options.scope == BackupRestoreScope.All) R.string.backup_all_detail else R.string.backup_profiles_detail),
                    style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                LiteText(stringResource(R.string.backup_strategy), style = LiteType.Label)
                LiteChoiceRow(BackupRestoreStrategy.entries, state.options.strategy,
                    { stringResource(if (it == BackupRestoreStrategy.Compatible) R.string.backup_compatible else R.string.backup_override) },
                    { actions.changeOptions(state.options.copy(strategy = it)) }, enabled = ready)
                LiteText(stringResource(if (state.options.strategy == BackupRestoreStrategy.Compatible) R.string.backup_compatible_detail else R.string.backup_override_detail),
                    style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
            }
        }
        item("local") {
            SectionLabel(stringResource(R.string.backup_local))
            BackupCard {
                LiteButton(stringResource(R.string.backup_create_local), actions::requestLocalBackup, Modifier.fillMaxWidth(), icon = FullGlyphs.arrowUp, enabled = ready)
                LiteButton(stringResource(R.string.backup_restore_local), actions::requestLocalRestore, Modifier.fillMaxWidth(), icon = FullGlyphs.importFile, enabled = ready)
            }
        }
        item("webdav") {
            SectionLabel(stringResource(R.string.backup_webdav))
            BackupCard {
                if (webDav == null) LiteText(stringResource(R.string.backup_webdav_unbound), color = LocalLiteVisuals.current.secondaryInk)
                else {
                    LiteText(webDav.address, style = LiteType.Body, maxLines = 3)
                    LiteText(if (webDav.anonymous) stringResource(R.string.backup_anonymous) else stringResource(R.string.backup_account_value, webDav.user),
                        style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                    LiteText(stringResource(R.string.backup_remote_name, webDav.fileName), style = LiteType.Label)
                    LiteText(stringResource(when (state.connection) {
                        BackupConnection.NotChecked -> R.string.backup_not_checked
                        BackupConnection.Connected -> R.string.backup_connected
                        BackupConnection.Failed -> R.string.backup_connection_failed
                    }), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                }
                LiteButton(stringResource(R.string.backup_webdav_settings), configure, Modifier.fillMaxWidth(), icon = FullGlyphs.settings, enabled = ready)
                if (webDav != null) {
                    LiteButton(stringResource(R.string.backup_check), actions::checkConnection, Modifier.fillMaxWidth(), enabled = ready)
                    LiteButton(stringResource(R.string.backup_upload), actions::requestRemoteBackup, Modifier.fillMaxWidth(), icon = FullGlyphs.arrowUp, enabled = ready)
                    LiteButton(stringResource(R.string.backup_restore_remote), { actions.requestRemoteRestore(webDav.fileName) }, Modifier.fillMaxWidth(), icon = FullGlyphs.arrowDown, enabled = ready)
                    LiteButton(stringResource(R.string.backup_list), actions::listRemote, Modifier.fillMaxWidth(), icon = FullGlyphs.refresh, enabled = ready)
                    LiteButton(stringResource(R.string.backup_unbind), actions::requestUnbind, Modifier.fillMaxWidth(), destructive = true, enabled = ready)
                }
            }
        }
        if (state.listed && state.remoteFiles.isEmpty()) item("empty") {
            BackupCard { LiteText(stringResource(R.string.backup_remote_empty), color = LocalLiteVisuals.current.secondaryInk) }
        }
        items(state.remoteFiles, key = { "remote:${it.name}" }) { file ->
            BackupCard {
                LiteText(file.name, style = LiteType.Label)
                file.bytes?.let { LiteText(formatBytes(it), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk) }
                LiteButton(stringResource(if (webDav?.fileName == file.name) R.string.backup_selected else R.string.backup_use_file),
                    { actions.selectRemoteFile(file.name) }, Modifier.fillMaxWidth(), selected = webDav?.fileName == file.name, enabled = ready && webDav?.fileName != file.name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteButton(stringResource(R.string.backup_restore), { actions.requestRemoteRestore(file.name) }, Modifier.weight(1f), enabled = ready)
                    LiteButton(stringResource(R.string.backup_delete), { actions.requestRemoteDelete(file.name) }, Modifier.weight(1f), destructive = true, enabled = ready)
                }
            }
        }
    }
}

@Composable
private fun BackupCard(content: @Composable () -> Unit) {
    LitePane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun BackupProgressRow(progress: BackupProgress, cancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), color = LocalLiteVisuals.current.primary, strokeWidth = 2.dp)
            LiteText(stringResource(progress.label()), Modifier.weight(1f), style = LiteType.Callout)
        }
        if (progress.cancellable) LiteButton(stringResource(R.string.backup_cancel), cancel)
    }
}

@Composable
private fun BackupNoticeRow(notice: BackupNotice, actions: BackupActions) {
    LiteText(stringResource(notice.code.label()), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (notice.error) MaterialTheme.colorScheme.error else LocalLiteVisuals.current.ink)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notice.retryable) LiteButton(stringResource(R.string.backup_retry), actions::retry)
        if (notice.code != BackupNoticeCode.RefreshAfterRestoreFailed) LiteButton(stringResource(R.string.backup_dismiss), actions::dismissNotice)
    }
}

@Composable
internal fun BackupConfirmationDialog(confirmation: BackupConfirmation, actions: BackupActions) {
    val restore = confirmation.kind == BackupConfirmationKind.RestoreLocal || confirmation.kind == BackupConfirmationKind.RestoreRemote
    val title = when (confirmation.kind) {
        BackupConfirmationKind.RestoreLocal, BackupConfirmationKind.RestoreRemote -> R.string.backup_confirm_restore
        BackupConfirmationKind.UploadRemote -> R.string.backup_confirm_upload
        BackupConfirmationKind.DeleteRemote -> R.string.backup_confirm_delete
        BackupConfirmationKind.UnbindWebDav -> R.string.backup_confirm_unbind
    }
    LiteModal(stringResource(title), { actions.cancelConfirmation(confirmation.id) }) {
        confirmation.fileName?.let { LiteText(it, style = LiteType.Label) }
        LiteText(stringResource(when (confirmation.kind) {
            BackupConfirmationKind.RestoreLocal, BackupConfirmationKind.RestoreRemote -> R.string.backup_restore_warning
            BackupConfirmationKind.UploadRemote -> R.string.backup_upload_warning
            BackupConfirmationKind.DeleteRemote -> R.string.backup_delete_warning
            BackupConfirmationKind.UnbindWebDav -> R.string.backup_unbind_warning
        }))
        if (restore) {
            LiteText(stringResource(if (confirmation.options.scope == BackupRestoreScope.All) R.string.backup_all_detail else R.string.backup_profiles_detail),
                color = LocalLiteVisuals.current.secondaryInk)
            LiteText(stringResource(if (confirmation.options.strategy == BackupRestoreStrategy.Compatible) R.string.backup_compatible_detail else R.string.backup_override_detail),
                color = LocalLiteVisuals.current.secondaryInk)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiteButton(stringResource(R.string.backup_cancel), { actions.cancelConfirmation(confirmation.id) }, Modifier.weight(1f))
            LiteButton(stringResource(R.string.backup_confirm), { actions.confirm(confirmation.id) }, Modifier.weight(1f),
                destructive = !restore || confirmation.options.strategy == BackupRestoreStrategy.Override)
        }
    }
}

@Composable
internal fun BackupWebDavEditor(config: BackupWebDavUi?, busy: Boolean, notice: BackupNotice?, progress: BackupProgress?, actions: BackupActions, dismiss: () -> Unit) {
    var address by remember { mutableStateOf(config?.address.orEmpty()) }
    var user by remember { mutableStateOf(config?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf(config?.fileName ?: "aerove-lite.zip") }
    var anonymous by remember { mutableStateOf(config?.anonymous ?: false) }
    val keepPassword = !anonymous && config?.hasPassword == true && address.trim() == config.address && user == config.user
    LiteText(stringResource(R.string.backup_webdav_detail), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
    LiteField(address, { address = it }, stringResource(R.string.backup_address), keyboardType = KeyboardType.Uri, enabled = !busy)
    SettingsRow(stringResource(R.string.backup_anonymous)) {
        LiteSwitch(anonymous, stringResource(R.string.backup_anonymous), { anonymous = it; password = "" }, enabled = !busy)
    }
    if (!anonymous) {
        LiteField(user, { user = it }, stringResource(R.string.backup_account), enabled = !busy)
        BackupPasswordField(password, { password = it }, !busy)
        if (keepPassword) LiteText(stringResource(R.string.backup_keep_password), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
    }
    LiteField(fileName, { fileName = it }, stringResource(R.string.backup_filename), enabled = !busy)
    progress?.let { BackupProgressRow(it, actions::cancel) }
    notice?.let { LiteText(stringResource(it.code.label()), color = MaterialTheme.colorScheme.error) }
    if (notice?.retryable == true) LiteButton(stringResource(R.string.backup_retry), actions::retry, enabled = !busy)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiteButton(stringResource(R.string.backup_cancel), dismiss, Modifier.weight(1f), enabled = !busy)
        LiteButton(stringResource(R.string.backup_save), {
            actions.saveWebDav(address, user, if (keepPassword && password.isEmpty()) null else password, fileName, anonymous)
            password = ""
        }, Modifier.weight(1f), enabled = !busy && address.isNotBlank() && fileName.isNotBlank())
    }
}

@Composable
private fun BackupPasswordField(value: String, change: (String) -> Unit, enabled: Boolean) {
    val label = stringResource(R.string.backup_password)
    val visuals = LocalLiteVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LiteText(label, style = LiteType.Label, color = visuals.secondaryInk)
        LitePane(Modifier.fillMaxWidth(), corner = LiteCorner.Control) {
            BasicTextField(value, change,
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(14.dp).semantics { contentDescription = label },
                enabled = enabled, singleLine = true, textStyle = LiteType.Body.copy(color = visuals.ink),
                cursorBrush = SolidColor(visuals.primary), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
        }
    }
}

private fun BackupProgress.label(): Int = when (this) {
    BackupProgress.Loading -> R.string.backup_loading
    BackupProgress.SavingOptions, BackupProgress.SavingWebDav -> R.string.backup_saving
    BackupProgress.CheckingConnection -> R.string.backup_checking
    BackupProgress.Listing -> R.string.backup_listing
    BackupProgress.Packaging -> R.string.backup_packaging
    BackupProgress.WritingDocument -> R.string.backup_writing
    BackupProgress.ReadingDocument -> R.string.backup_reading
    BackupProgress.Uploading -> R.string.backup_uploading
    BackupProgress.Downloading -> R.string.backup_downloading
    BackupProgress.Restoring -> R.string.backup_restoring
    BackupProgress.Refreshing -> R.string.backup_refreshing
    BackupProgress.Deleting -> R.string.backup_deleting
}

private fun BackupNoticeCode.label(): Int = when (this) {
    BackupNoticeCode.Saved -> R.string.backup_saved
    BackupNoticeCode.Uploaded -> R.string.backup_uploaded
    BackupNoticeCode.Restored -> R.string.backup_restored
    BackupNoticeCode.Deleted -> R.string.backup_deleted
    BackupNoticeCode.Configured -> R.string.backup_configured
    BackupNoticeCode.Unbound -> R.string.backup_unbound
    BackupNoticeCode.Cancelled -> R.string.backup_cancelled
    BackupNoticeCode.LoadFailed -> R.string.backup_load_error
    BackupNoticeCode.OptionsFailed -> R.string.backup_options_error
    BackupNoticeCode.WebDavSaveFailed -> R.string.backup_webdav_save_error
    BackupNoticeCode.InvalidWebDav -> R.string.backup_config_error
    BackupNoticeCode.PasswordRequired -> R.string.backup_password_required
    BackupNoticeCode.WebDavCredentials -> R.string.backup_credentials_error
    BackupNoticeCode.RemoteMissing -> R.string.backup_remote_missing
    BackupNoticeCode.RemoteConflict -> R.string.backup_remote_conflict
    BackupNoticeCode.RemoteFailed -> R.string.backup_remote_error
    BackupNoticeCode.DocumentPermission -> R.string.backup_permission_error
    BackupNoticeCode.ReadFailed -> R.string.backup_read_error
    BackupNoticeCode.WriteFailed -> R.string.backup_write_error
    BackupNoticeCode.InvalidArchive -> R.string.backup_archive_error
    BackupNoticeCode.VpnAuthorizationRequired -> R.string.backup_vpn_authorization
    BackupNoticeCode.RefreshAfterRestoreFailed -> R.string.backup_refresh_error
    BackupNoticeCode.PartialDocument -> R.string.backup_partial_document
}
