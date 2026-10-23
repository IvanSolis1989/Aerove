package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.controllerprefs.ExternalControllerPreferences

@Composable
internal fun ExternalControllerSettings(value: ExternalControllerPreferences, padding: PaddingValues,
    busy: Boolean = false, error: Boolean = false, onChanged: (ExternalControllerPreferences) -> Unit) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExternalControllerSection(value, busy, error, onChanged) }
    }
}

@Composable
internal fun ExternalControllerSection(value: ExternalControllerPreferences, busy: Boolean = false,
    error: Boolean = false, onChanged: (ExternalControllerPreferences) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val currentBusy by rememberUpdatedState(busy)
    val currentError by rememberUpdatedState(error)
    val change by rememberUpdatedState(onChanged)
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    LaunchedEffect(editing) {
        if (editing) {
            val content: @Composable () -> Unit = {
                ExternalControllerSecretForm(current, currentBusy, currentError, change) { editing = false }
            }
            owned[0] = content
            overlay.content = content
        } else { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    }
    DisposableEffect(overlay) { onDispose { if (overlay.content === owned[0]) overlay.dismiss() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LitePane(Modifier.fillMaxWidth()) { Column {
            SettingsRow(stringResource(R.string.controller_external)) {
                LiteSwitch(value.enabled, stringResource(R.string.controller_external),
                    { onChanged(value.withEnabled(it)) }, !busy)
            }
            if (value.enabled) SettingsRow(stringResource(R.string.controller_secret), value.secret.ifEmpty { null },
                onClick = if (busy) null else ({ editing = true })) { LiteIcon(FullGlyphs.chevronForward) }
        } }
        if (error) LiteText(stringResource(R.string.controller_save_failed), style = LiteType.Caption)
    }
}

@Composable
private fun ExternalControllerSecretForm(value: ExternalControllerPreferences, busy: Boolean, error: Boolean,
    onChanged: (ExternalControllerPreferences) -> Unit, close: () -> Unit) {
    // Keep an unsaved credential out of saved-instance state and diagnostic text.
    var secret by remember { mutableStateOf(value.secret) }
    var attempted by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ExternalControllerPreferences?>(null) }
    var observedBusy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(value, busy, error, pending) {
        val wanted = pending ?: return@LaunchedEffect
        if (busy) observedBusy = true
        if (!busy && value == wanted) { pending = null; close() }
        else if (!busy && (observedBusy || error)) { pending = null; failed = true }
    }
    val draft = runCatching { value.withSecret(secret) }.getOrNull()
    LiteModal(stringResource(R.string.controller_secret), close) {
        LiteField(secret, { secret = it.take(ExternalControllerPreferences.MAX_SECRET_LENGTH); failed = false },
            stringResource(R.string.controller_secret), enabled = !busy && pending == null)
        if (attempted && draft == null) LiteText(stringResource(R.string.controller_invalid_secret), style = LiteType.Caption)
        if (failed) LiteText(stringResource(R.string.controller_save_failed), style = LiteType.Caption)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiteButton(stringResource(R.string.controller_cancel), close, Modifier.weight(1f))
            LiteButton(stringResource(R.string.controller_save), {
                attempted = true
                if (draft != null) {
                    if (draft == value) close() else {
                        pending = draft; observedBusy = false; failed = false
                        try { onChanged(draft) } catch (_: Exception) { pending = null; failed = true }
                    }
                }
            }, Modifier.weight(1f), selected = true, enabled = !busy && pending == null)
        }
    }
}
