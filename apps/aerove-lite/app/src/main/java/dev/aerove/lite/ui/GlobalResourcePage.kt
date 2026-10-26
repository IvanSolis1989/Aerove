package dev.aerove.lite.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.aerove.lite.R
import dev.aerove.lite.images.GroupIconLoader
import dev.aerove.lite.integration.GlobalResourceWorkspace
import dev.aerove.lite.profilebuilder.GlobalLibraryFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class GlobalFileRequest(val id: String = UUID.randomUUID().toString()) {
    val result = CompletableDeferred<Uri?>()
    var dispatched = false
}

@Composable
internal fun GlobalResourcePage(workspace: GlobalResourceWorkspace, icons: GroupIconLoader,
    padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    var request by remember(workspace) { mutableStateOf<GlobalFileRequest?>(null) }
    var launched by remember(workspace) { mutableStateOf<GlobalFileRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val original = launched
        launched = null
        original?.result?.complete(uri)
    }
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        if (!current.dispatched) {
            current.dispatched = true
            launched = current
            try { launcher.launch(arrayOf("*/*")) }
            catch (failure: Exception) { launched = null; current.result.completeExceptionally(failure) }
        }
    }
    DisposableEffect(workspace) {
        onDispose { request?.result?.cancel(); launched?.result?.cancel(); workspace.session.cancel() }
    }
    var retry by remember(workspace) { mutableIntStateOf(0) }
    val ready by produceState<Boolean?>(null, workspace, retry) { value = workspace.prepare() }
    if (ready == true) GlobalResourceLibraryScreen(workspace.session, padding, onBack, {}, readFile = {
        check(request == null && launched == null)
        val current = GlobalFileRequest()
        request = current
        try {
            val uri = current.result.await()
            if (uri == null) null else withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()
                GlobalLibraryFile(name) {
                    withContext(Dispatchers.IO) { resolver.openInputStream(uri) ?: error("Cannot open selected resource") }
                }
            }
        } finally {
            current.result.cancel()
            if (request === current) request = null
        }
    }, rules = workspace.rules, proxyLinks = workspace.proxyLinks, iconLoader = icons)
    else Column(Modifier.padding(padding)) {
        LiteText(stringResource(if (ready == false) R.string.editor_error else R.string.editor_loading))
        if (ready == false) LiteButton(stringResource(R.string.builder_retry), { retry++ })
    }
}
