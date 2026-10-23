package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

private data class ProfileMetadataDraft(val name: String, val url: String?, val autoUpdate: Boolean, val interval: Long) {
    fun matches(profile: ProfileSummary) = name == profile.name && url == profile.url &&
        autoUpdate == profile.autoUpdate && interval == profile.updateIntervalMillis
}

internal fun formatProfileUsed(usage: ProfileSubscriptionUsage): String =
    if (usage.used.bitLength() < 63) formatBytes(usage.used.toLong()) else "${usage.used} B"

@Composable
internal fun ProfileProperties(state: LiteState, actions: LiteActions, id: String, close: () -> Unit,
    access: dev.aerove.lite.integration.ProfilePropertiesAccess = LocalLiteFeatures.current().profileProperties) {
    val profile = state.profiles.firstOrNull { it.id == id }
    if (profile == null) { LaunchedEffect(id) { close() }; return }
    var loaded by remember(id, access) { mutableStateOf<dev.aerove.lite.integration.PrivateProfileProperties?>(null) }
    var name by remember(id) { mutableStateOf(profile.name) }
    var url by remember(id) { mutableStateOf(profile.url.orEmpty()) }
    var auto by remember(id) { mutableStateOf(profile.autoUpdate) }
    var minutes by remember(id) { mutableStateOf((profile.updateIntervalMillis / 60_000).toString()) }
    var key by remember(id) { mutableStateOf("") }
    var loading by remember(id) { mutableStateOf(true) }
    var saving by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempted by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, access) {
        try {
            val value = access.load(id)
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                loaded = value
                name = value.document.profile.name
                url = value.document.profile.url.orEmpty()
                auto = value.document.profile.autoUpdate
                minutes = (value.document.profile.updateIntervalMillis / 60_000).toString()
                value.secret?.copyBytes()?.let { bytes ->
                    try { key = bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
                }
            } catch (failure: Throwable) { value.close(); throw failure }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    DisposableEffect(id, access) { onDispose { loaded?.close(); loaded = null; key = "" } }
    val busy = loading || saving || "profile:$id" in state.busy
    val interval = profileIntervalMillis(minutes)
    val remote = loaded?.document?.profile?.url != null
    val validUrl = runCatching { URI(url.trim()).let { it.scheme.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false)
    val issue = when {
        name.isBlank() -> R.string.profile_name_required
        remote && !validUrl -> R.string.profile_url_invalid
        remote && auto && interval == null -> R.string.profile_interval_invalid
        else -> null
    }
    ProfileMaintenanceSheet(stringResource(R.string.profile_properties), close) {
        LiteField(name, { name = it; failed = false }, stringResource(R.string.profile_name), Modifier.testTag("profile-property-name"), enabled = !busy)
        if (remote) {
            LiteField(url, { url = it; failed = false }, stringResource(R.string.lite_subscription_url), Modifier.testTag("profile-property-url"), keyboardType = KeyboardType.Uri, enabled = !busy)
            SettingsRow(stringResource(R.string.profile_auto_update), trailing = {
                LiteSwitch(auto, stringResource(R.string.profile_auto_update), { auto = it }, enabled = !busy)
            })
            if (auto) LiteField(minutes, { minutes = it; failed = false }, stringResource(R.string.profile_update_minutes),
                Modifier.testTag("profile-property-interval"), keyboardType = KeyboardType.Number, enabled = !busy)
        }
        AgeIdentityField(key, { key = it; failed = false }, enabled = !busy)
        if (attempted && issue != null) LiteText(stringResource(issue), color = MaterialTheme.colorScheme.error)
        if (failed) LiteText(stringResource(R.string.editor_error), Modifier.testTag("profile-property-error"), color = MaterialTheme.colorScheme.error)
        LiteButton(stringResource(R.string.profile_save), {
            attempted = true
            val original = loaded ?: return@LiteButton
            if (issue == null) {
                val bytes = key.trim().toByteArray(Charsets.UTF_8)
                val before = original.secret?.copyBytes()
                val replace = try { !bytes.contentEquals(before ?: byteArrayOf()) } finally { before?.fill(0) }
                val secret = try { if (bytes.isEmpty()) null else dev.aerove.lite.profilesource.AgeSecret(bytes) } finally { bytes.fill(0) }
                val draft = ProfileMetadataDraft(name.trim(), if (remote) url.trim() else null, auto,
                    interval ?: original.document.profile.updateIntervalMillis)
                if (!replace && draft.matches(original.document.profile)) { secret?.close(); close() }
                else {
                    saving = true
                    failed = false
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try {
                            access.save(original.document, draft.name, draft.url, draft.autoUpdate, draft.interval, secret, replace)
                            close()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { secret?.close(); saving = false }
                    }
                }
            }
        }, Modifier.fillMaxWidth().testTag("profile-property-save"), selected = true, enabled = !busy && loaded != null)
    }
}

@Composable
internal fun ProfileSubscriptionDetails(state: LiteState, id: String, close: () -> Unit) {
    val profile = state.profiles.firstOrNull { it.id == id }
    if (profile == null) { LaunchedEffect(id) { close() }; return }
    val usage = remember(profile.subscriptionUserInfo) { parseProfileSubscriptionUsage(profile.subscriptionUserInfo) }
    ProfileMaintenanceSheet(stringResource(R.string.profile_subscription_info), close) {
        if (usage == null) LiteText(stringResource(R.string.profile_usage_unavailable))
        else {
            SettingsRow(stringResource(R.string.profile_usage_used), formatProfileUsed(usage))
            SettingsRow(stringResource(R.string.profile_usage_total), formatBytes(usage.total))
            SettingsRow(stringResource(R.string.profile_expire), profileExpiration(usage))
        }
    }
}

@Composable
internal fun profileExpiration(usage: ProfileSubscriptionUsage): String = when {
    usage.expireSeconds == 0L -> stringResource(R.string.profile_never_expires)
    usage.expireMillis != null -> formatTime(requireNotNull(usage.expireMillis))
    else -> stringResource(R.string.profile_expiry_unavailable)
}

@Composable
internal fun ProfileMaintenanceSheet(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val reduced = LocalLiteReducedMotion.current
    val progress by animateFloatAsState(if (entered && !closing) 1f else 0f, tween(if (reduced) 0 else 260), label = "profileSheet")
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(closing) { if (closing) { if (!reduced) delay(260); close() } }
    BackHandler { closing = true }
    val scrim = rememberLayerBackdrop()
    val source = rememberCombinedBackdrop(LocalLiteBackdrop.current, scrim)
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().layerBackdrop(scrim).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * progress))
            .clickable(enabled = !closing) { closing = true })
        CompositionLocalProvider(LocalLiteBackdrop provides source) {
            LitePane(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.9f)
                .graphicsLayer { translationY = (1 - progress) * size.height }.pointerInput(Unit) { detectTapGestures {} }
                .semantics { paneTitle = title }, corner = LiteCorner.Sheet) {
                Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiteIconButton(stringResource(R.string.lite_close), FullGlyphs.close, { closing = true })
                        LiteText(title, Modifier.weight(1f), style = LiteType.Title)
                    }
                    content()
                }
            }
        }
    }
}
