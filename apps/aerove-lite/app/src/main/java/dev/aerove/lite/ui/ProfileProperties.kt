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

private data class ProfileMetadataDraft(val name: String, val url: String?, val autoUpdate: Boolean, val interval: Long) {
    fun matches(profile: ProfileSummary) = name == profile.name && url == profile.url &&
        autoUpdate == profile.autoUpdate && interval == profile.updateIntervalMillis
}

internal fun formatProfileUsed(usage: ProfileSubscriptionUsage): String =
    if (usage.used.bitLength() < 63) formatBytes(usage.used.toLong()) else "${usage.used} B"

@Composable
internal fun ProfileProperties(state: LiteState, actions: LiteActions, id: String, close: () -> Unit) {
    val profile = state.profiles.firstOrNull { it.id == id }
    if (profile == null) { LaunchedEffect(id) { close() }; return }
    var name by rememberSaveable(id) { mutableStateOf(profile.name) }
    var url by rememberSaveable(id) { mutableStateOf(profile.url.orEmpty()) }
    var auto by rememberSaveable(id) { mutableStateOf(profile.autoUpdate) }
    var minutes by rememberSaveable(id) { mutableStateOf((profile.updateIntervalMillis / 60_000).toString()) }
    var attempted by rememberSaveable(id) { mutableStateOf(false) }
    var submitted by remember(id) { mutableStateOf<ProfileMetadataDraft?>(null) }
    var observedBusy by remember(id) { mutableStateOf(false) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    val busy = "profile:$id" in state.busy
    val interval = profileIntervalMillis(minutes)
    val validUrl = runCatching { URI(url.trim()).let { it.scheme.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false)
    val issue = when {
        name.isBlank() -> R.string.profile_name_required
        profile.url != null && !validUrl -> R.string.profile_url_invalid
        profile.url != null && auto && interval == null -> R.string.profile_interval_invalid
        else -> null
    }
    LaunchedEffect(profile, busy, state.message, submitted) {
        val wanted = submitted ?: return@LaunchedEffect
        if (busy) observedBusy = true
        if (wanted.matches(profile) && !busy) { submitted = null; close() }
        else if (!busy && (observedBusy || state.message != null)) {
            failure = state.message
            submitted = null
            observedBusy = false
        }
    }
    ProfileMaintenanceSheet(stringResource(R.string.profile_properties), close) {
        LiteField(name, { name = it; failure = null }, stringResource(R.string.profile_name), Modifier.testTag("profile-property-name"), enabled = !busy && submitted == null)
        if (profile.url != null) {
            LiteField(url, { url = it; failure = null }, stringResource(R.string.lite_subscription_url), Modifier.testTag("profile-property-url"), keyboardType = KeyboardType.Uri, enabled = !busy && submitted == null)
            SettingsRow(stringResource(R.string.profile_auto_update), trailing = {
                LiteSwitch(auto, stringResource(R.string.profile_auto_update), { auto = it }, enabled = !busy && submitted == null)
            })
            if (auto) LiteField(minutes, { minutes = it; failure = null }, stringResource(R.string.profile_update_minutes),
                Modifier.testTag("profile-property-interval"), keyboardType = KeyboardType.Number, enabled = !busy && submitted == null)
        }
        if (attempted && issue != null) LiteText(stringResource(issue), color = MaterialTheme.colorScheme.error)
        failure?.let { LiteText(it, color = MaterialTheme.colorScheme.error) }
        LiteButton(stringResource(R.string.profile_save), {
            attempted = true
            if (issue == null) {
                val draft = ProfileMetadataDraft(name.trim(), if (profile.url == null) null else url.trim(), auto,
                    interval ?: profile.updateIntervalMillis)
                if (draft.matches(profile)) close()
                else {
                    failure = null
                    observedBusy = false
                    submitted = draft
                    actions.updateProfileMetadata(id, draft.name, draft.url, draft.autoUpdate, draft.interval)
                }
            }
        }, Modifier.fillMaxWidth().testTag("profile-property-save"), selected = true, enabled = !busy && submitted == null)
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
