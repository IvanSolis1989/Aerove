package dev.aerove.lite.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.updates.*

@Composable
internal fun UpdateHost(feature: UpdateFeature) {
    val state by feature.state.collectAsState()
    var launchedId by rememberSaveable { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        feature.platformResult(launchedId); launchedId = 0L
    }
    LaunchedEffect(feature, state.installRequest?.id) {
        val request = state.installRequest ?: return@LaunchedEffect
        if (feature.platformLaunched(request.id)) try {
            launchedId = request.id
            val intent = if (request.kind == UpdateInstallKind.UnknownSources) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse(request.uri))
            } else Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(request.uri), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launcher.launch(intent)
        } catch (_: RuntimeException) { feature.platformLaunchFailed(request.id); launchedId = 0L }
    }
    if (state.visible) UpdatePanel(state, feature)
}

@Composable
internal fun UpdatePanel(state: UpdateState, feature: UpdateFeature) {
    LiteModal(stringResource(R.string.update_check), { if (state.busy) feature.cancel() else feature.dismiss() }) {
        Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.release?.let { release ->
                LiteText(release.tag, style = LiteType.Title)
                LiteText(release.notes.ifBlank { stringResource(R.string.update_notes_unavailable) }, style = LiteType.Body)
            }
            LiteText(stringResource(when (state.phase) {
                UpdatePhase.Checking -> R.string.update_checking
                UpdatePhase.Current -> R.string.update_current
                UpdatePhase.Downloading -> R.string.update_downloading
                UpdatePhase.Ready -> R.string.update_ready
                UpdatePhase.Installing -> R.string.update_installing
                UpdatePhase.Failed -> R.string.update_failed
                else -> R.string.update_available
            }))
            if (state.phase == UpdatePhase.Downloading && state.totalBytes > 0) {
                LiteText("${state.downloadedBytes * 100 / state.totalBytes}%", style = LiteType.Footnote)
            }
            state.error?.let { error -> LiteText(stringResource(when (error) {
                "permission" -> R.string.update_permission
                "installer" -> R.string.update_installer_failed
                else -> R.string.update_failed
            })) }
            if (state.phase == UpdatePhase.Available || state.phase == UpdatePhase.Failed && state.release != null) {
                LiteButton(stringResource(R.string.update_download), feature::download, Modifier.fillMaxWidth())
            }
            if (state.phase == UpdatePhase.Ready) LiteButton(stringResource(R.string.update_install), feature::requestInstall, Modifier.fillMaxWidth())
            if (state.automaticPrompt && !state.busy) LiteButton(stringResource(R.string.update_no_remind), feature::disableAutomatic, Modifier.fillMaxWidth())
            LiteButton(stringResource(if (state.busy) R.string.lite_cancel else R.string.update_close),
                { if (state.busy) feature.cancel() else feature.dismiss() }, Modifier.fillMaxWidth())
        }
    }
}
