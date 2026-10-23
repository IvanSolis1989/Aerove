package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import dev.aerove.lite.MaterialTier
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R
import java.net.URI

@Composable
internal fun ProfilesScreen(state: LiteState, actions: LiteActions, padding: PaddingValues, addSubscription: () -> Unit,
    deleteProfile: (ProfileSummary) -> Unit, editProfile: (ProfileSummary, Boolean) -> Unit,
    buildProfile: ((ProfileSummary) -> Unit)? = null, createProfile: (() -> Unit)? = null,
    extendProfile: ((ProfileSummary) -> Unit)? = null) {
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    val listUi = rememberProfileListUi(state)
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiteButton(stringResource(R.string.lite_import), actions::importFile, Modifier.weight(1f), FullGlyphs.importFile, enabled = state.busy.isEmpty())
                LiteButton(stringResource(R.string.lite_add_subscription), addSubscription, Modifier.weight(1f), FullGlyphs.add, enabled = state.busy.isEmpty())
            }
        }
        if (createProfile != null) item("create-custom-profile") {
            LiteButton(stringResource(R.string.builder_title), createProfile, Modifier.fillMaxWidth().testTag("profile-create-custom"), FullGlyphs.add, enabled = state.busy.isEmpty())
        }
        if (state.profiles.isNotEmpty()) item("profile-list-actions") { ProfileListToolbar(listUi, state) }
        if (state.profiles.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_profiles), stringResource(R.string.lite_no_profiles_detail), FullGlyphs.profiles)
        }
        items(state.profiles, key = { it.id }) { profile ->
            val selected = profile.id == state.selectedProfile
            val updating = profile.id in state.updatingProfileIds
            val usage = remember(profile.subscriptionUserInfo) { parseProfileSubscriptionUsage(profile.subscriptionUserInfo) }
            var bounds by remember(profile.id) { mutableStateOf(Rect.Zero) }
            LitePane(
                Modifier.fillMaxWidth().testTag("profile-${profile.id}").onGloballyPositioned { bounds = it.boundsInWindow() }
                    .semantics { this.selected = selected; role = Role.RadioButton }
                    .combinedClickable(enabled = state.busy.isEmpty() && !listUi.state.busy, onClick = { actions.selectProfile(profile.id) }, onLongClick = {
                        overlay.content = { ProfileContextMenu(bounds, profile, overlay::dismiss,
                            { editProfile(profile, false) }, { editProfile(profile, true) },
                            { actions.updateProfile(profile.id) }, { deleteProfile(profile) },
                            { overlay.content = { ProfileProperties(current, actions, profile.id, overlay::dismiss) } },
                            { overlay.content = { ProfileSubscriptionDetails(current, profile.id, overlay::dismiss) } },
                            { listUi.copy(profile) }, { listUi.export(profile.id) }, { listUi.replace(profile) },
                            buildProfile?.let { build -> { build(profile) } },
                            if (!profile.custom) extendProfile?.let { extend -> { extend(profile) } } else null,
                            if (profile.url != null) ({ val id = profile.id; overlay.content = { ProfileShareQr(current.profiles.firstOrNull { it.id == id }, overlay::dismiss) } }) else null) }
                    }),
                selected = selected,
            ) {
                ProfileUsageBackground(usage, Modifier.matchParentSize())
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LiteText(profile.name, style = LiteType.Headline)
                            LiteText(
                                profile.url?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty().ifEmpty { stringResource(R.string.lite_local_profile) },
                                style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk,
                            )
                        }
                        if (selected) LiteIcon(FullGlyphs.check, color = LocalLiteVisuals.current.primary)
                    }
                    LiteText(
                        if (profile.updatedAt > 0) stringResource(R.string.lite_updated_at, formatTime(profile.updatedAt)) else stringResource(R.string.lite_not_updated),
                        style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk,
                    )
                    if (usage != null && usage.total > 0) {
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            overlay.content = { ProfileSubscriptionDetails(current, profile.id, overlay::dismiss) }
                        }.testTag("profile-usage-${profile.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LiteText("${formatProfileUsed(usage)} / ${formatBytes(usage.total)}", style = LiteType.Footnote)
                            LiteText(profileExpiration(usage), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                            usage.progress?.let { progress ->
                                val ink = LocalLiteVisuals.current.primary
                                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                    drawRect(ink.copy(alpha = 0.12f))
                                    drawRect(ink, size = size.copy(width = size.width * progress))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (updating) LiteButton(stringResource(R.string.profile_cancel_update), { actions.cancelProfileUpdate(profile.id) },
                            Modifier.weight(1f).testTag("profile-cancel-${profile.id}"), FullGlyphs.close)
                        else if (profile.url != null) LiteButton(stringResource(R.string.lite_refresh), { actions.updateProfile(profile.id) },
                            Modifier.weight(1f), FullGlyphs.refresh, enabled = state.busy.isEmpty())
                        LiteIconButton(
                            stringResource(R.string.lite_delete), FullGlyphs.delete,
                            { deleteProfile(profile) }, enabled = state.busy.isEmpty(), destructive = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionEditor(state: LiteState, actions: LiteActions, onDismiss: () -> Unit,
    initialInput: String = "", onImport: ((String, String) -> Unit)? = null) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(initialInput) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var awaitingNewProfile by rememberSaveable { mutableStateOf(false) }
    val originalIds = remember { state.profiles.mapTo(mutableSetOf()) { it.id } }
    LaunchedEffect(state.profiles) {
        if (awaitingNewProfile && state.profiles.any { it.id !in originalIds }) onDismiss()
    }
    val valid = if (onImport != null) url.isNotBlank() else name.isNotBlank() && runCatching {
        val uri = URI(url.trim())
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
    LiteModal(stringResource(R.string.lite_add_subscription), onDismiss) {
        LiteField(name, { name = it }, stringResource(R.string.lite_subscription_name), enabled = state.busy.isEmpty())
        LiteField(url, { url = it }, stringResource(if (onImport == null) R.string.lite_subscription_url else R.string.integration_input), keyboardType = KeyboardType.Uri, enabled = state.busy.isEmpty())
        if (attempted && !valid) LiteText(stringResource(if (onImport == null) R.string.lite_subscription_error else R.string.integration_input_error), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        LiteButton(
            stringResource(if (state.busy.isEmpty()) R.string.lite_add_subscription else R.string.lite_importing),
            onClick = {
                attempted = true
                if (valid) {
                    awaitingNewProfile = true
                    if (onImport == null) actions.addSubscription(name.trim(), url.trim()) else onImport(name.trim(), url.trim())
                }
            },
            modifier = Modifier.fillMaxWidth(),
            selected = true,
            enabled = state.busy.isEmpty(),
        )
    }
}

@Composable
private fun ProfileContextMenu(anchor: Rect, profile: ProfileSummary, dismiss: () -> Unit,
    edit: () -> Unit, preview: () -> Unit, update: () -> Unit, delete: () -> Unit,
    properties: () -> Unit, usage: () -> Unit, copy: () -> Unit, export: () -> Unit, replace: () -> Unit,
    builder: (() -> Unit)? = null, extend: (() -> Unit)? = null, shareQr: (() -> Unit)? = null) {
    BackHandler(onBack = dismiss)
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.32f)).clickable(onClick = dismiss)) {
        val width = minOf(300.dp, maxWidth - 32.dp)
        val left = with(density) { anchor.left.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val top = with(density) { anchor.top.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxHeight - 540.dp))
        val availableHeight = maxHeight - top - 16.dp
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.offset(left, top).width(width).pointerInput(Unit) { detectTapGestures {} }, corner = LiteCorner.Control,
            solidColor = fill, glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.heightIn(max = availableHeight).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                SettingsRow(stringResource(R.string.profile_properties), onClick = { dismiss(); properties() })
                if (profile.subscriptionUserInfo != null) SettingsRow(stringResource(R.string.profile_subscription_info), onClick = { dismiss(); usage() })
                if (extend != null) SettingsRow(stringResource(R.string.integration_extend), onClick = { dismiss(); extend() })
                if (builder != null) SettingsRow(stringResource(R.string.builder_title), onClick = { dismiss(); builder() })
                SettingsRow(stringResource(R.string.integration_source_editor), onClick = { dismiss(); edit() })
                SettingsRow(stringResource(R.string.integration_runtime_preview), onClick = { dismiss(); preview() })
                if (profile.url != null) SettingsRow(stringResource(R.string.lite_refresh), onClick = { dismiss(); update() })
                if (shareQr != null) SettingsRow(stringResource(R.string.profile_qr_menu), onClick = { dismiss(); shareQr() })
                if (profile.url != null) SettingsRow(stringResource(R.string.profile_list_copy_url), onClick = { dismiss(); copy() })
                SettingsRow(stringResource(R.string.profile_list_export), onClick = { dismiss(); export() })
                SettingsRow(stringResource(R.string.profile_list_replace), onClick = { dismiss(); replace() })
                SettingsRow(stringResource(R.string.lite_delete), onClick = { dismiss(); delete() })
            }
        }
    }
}

@Composable
private fun ProfileUsageBackground(usage: ProfileSubscriptionUsage?, modifier: Modifier) {
    val share = usage?.share ?: return
    val remaining = (1 - share).coerceIn(0.0, 1.0).toFloat()
    val visuals = LocalLiteVisuals.current
    val ink = if (share >= 0.9) androidx.compose.ui.graphics.Color(0xFFFF9800) else visuals.primary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalLiteReducedMotion.current
    val overlay = LocalLiteOverlay.current
    var visible by remember { mutableStateOf(false) }
    val view = LocalView.current
    val foreground = LocalDiagnosticsForeground.current
    val phase = rememberAuroraPhase(share > 0 && share < 1 && visuals.material != MaterialTier.Solid && visible && foreground && overlay.content == null)
    Canvas(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }) {
        if (remaining <= 0f) return@Canvas
        val glass = visuals.material != MaterialTier.Solid
        val inset = if (glass) min(5.dp.toPx(), size.minDimension / 4) else 0f
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        val level = width * remaining
        val moving = if (reduced || !glass) 0.0 else phase.value * 90 * 1.3
        val depth = if (remaining >= 1) 0f else min(4.dp.toPx(), min(level, width - level) / 3) * (1 + sin(moving).toFloat() * 0.05f)
        val contact = level + depth
        val shoulder = level + depth * 0.55f
        val plane = level - depth * (0.0955f / 0.864f)
        fun x(value: Float) = inset + if (rtl) width - value else value
        val edge = Path().apply {
            moveTo(x(contact), inset)
            cubicTo(x(shoulder), inset, x(plane), inset + height * 0.08f, x(plane), inset + height * 0.22f)
            cubicTo(x(plane), inset + height * 0.36f, x(plane), inset + height * 0.64f, x(plane), inset + height * 0.78f)
            cubicTo(x(plane), inset + height * 0.92f, x(shoulder), inset + height, x(contact), inset + height)
        }
        val fill = Path().apply { addPath(edge); lineTo(x(0f), inset + height); lineTo(x(0f), inset); close() }
        val alpha = if (glass) {
            if (share >= 0.9) { if (visuals.dark) 0.18f else 0.16f } else if (visuals.dark) 0.075f else 0.08f
        } else if (visuals.dark) 0.25f else 0.17f
        drawPath(fill, ink.copy(alpha = alpha))
        if (glass && depth > 0) drawPath(edge, androidx.compose.ui.graphics.Color.White.copy(alpha = if (visuals.dark) 0.16f else 0.2f), style = Stroke(0.65.dp.toPx()))
    }
}
