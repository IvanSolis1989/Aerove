package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.editor.EditorFontSize
import dev.aerove.lite.editor.EditorPreferences
import dev.aerove.lite.integration.ProfileDetailWorkspace
import dev.aerove.lite.providers.*
import dev.aerove.lite.resources.ResourceSubscriptionInfo
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileDetailSheet(state: LiteState, actions: LiteActions, workspace: ProfileDetailWorkspace,
    close: () -> Unit, previewRuntime: () -> Unit, active: Boolean = true) {
    val profile = state.profiles.firstOrNull { it.id == state.selectedProfile }
    val detail by workspace.state.collectAsState()
    val providers = detail.providers
    val overlay = LocalLiteOverlay.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    var launchedId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = launchedId; launchedId = null
        if (id != null) workspace.documentResult(id, uri?.let { selected -> suspend { checkNotNull(context.contentResolver.openInputStream(selected)) } })
    }
    val upload = detail.upload
    LaunchedEffect(upload?.id, upload?.dispatched, resumed, active) {
        if (upload != null && !upload.dispatched && resumed && active && workspace.documentLaunched(upload.id)) {
            launchedId = upload.id
            try { launcher.launch(arrayOf("*/*")) }
            catch (_: Exception) { launchedId = null; workspace.documentResult(upload.id, null) }
        }
    }
    var declared by remember(workspace, profile?.id) { mutableStateOf(false) }
    LaunchedEffect(workspace, profile?.id, active) { if (profile == null) close() else if (active && !declared) { declared = true; workspace.visible(true) } }
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val reduced = LocalLiteReducedMotion.current || !resumed || !active
    val progress by animateFloatAsState(if (entered && !closing) 1f else 0f, tween(if (reduced) 0 else 260), label = "profileDetailSheet")
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(closing) { if (closing) { if (!reduced) delay(260); workspace.visible(false); close() } }
    val document = detail.document
    BackHandler(document == null && overlay.content == null) { closing = true }
    val scrim = rememberLayerBackdrop()
    val source = rememberCombinedBackdrop(LocalLiteBackdrop.current, scrim)
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().then(if (!active) Modifier.clearAndSetSemantics {} else Modifier)) {
        val sheetHeight = maxHeight * 0.9f
        Box(Modifier.fillMaxSize().layerBackdrop(scrim).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * progress))
            .clickable(enabled = active && !closing && document == null) { closing = true })
        CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLitePageActive provides (active && !closing && overlay.content == null),
            LocalLiteReducedMotion provides reduced) {
            LitePane(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetHeight)
                .graphicsLayer { translationY = (1 - progress) * size.height }.pointerInput(Unit) { detectTapGestures {} }
                .testTag("profile-detail-sheet"), corner = LiteCorner.Sheet) {
                if (document != null) EditorScreen(document.session, workspace::closeDocument, workspace::closeDocument,
                    modifier = Modifier.testTag("provider-editor"),
                    preferences = EditorPreferences(EditorFontSize.entries.first { it.sp.toInt() == state.settings.editorFontSize }, state.settings.editorLineWrap),
                    onPreferencesChange = { actions.changeSettings(state.settings.copy(editorFontSize = it.fontSize.sp.toInt(), editorLineWrap = it.lineWrap)) })
                else Column(Modifier.navigationBarsPadding()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiteIconButton(stringResource(R.string.lite_close), FullGlyphs.close, { closing = true })
                        LiteText(profile?.name.orEmpty(), Modifier.weight(1f), style = LiteType.Title)
                        if (profile?.url != null) LiteIconButton(stringResource(R.string.proxy_tools_sync), FullGlyphs.refresh,
                            { actions.updateProfile(profile.id) }, enabled = active && profile.id !in state.updatingProfileIds)
                        LiteIconButton(stringResource(R.string.integration_runtime_preview), ProviderEyeGlyph, previewRuntime, enabled = active)
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            val counts = detail.counts
                            val actual = providers
                            val providerReady = detail.providersReady && "*" !in detail.busy
                            val stats = listOf(R.string.builder_groups to counts?.groups,
                                R.string.builder_proxies to counts?.takeIf { providerReady }?.let { it.proxies + actual.filter { row -> row.kind == ProviderKind.Proxy }.sumOf { row -> row.proxies.size } },
                                R.string.builder_rules to counts?.rules, R.string.proxy_tools_providers to counts?.takeIf { providerReady }?.let { actual.size })
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                stats.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { (label, value) -> LitePane(Modifier.weight(1f)) {
                                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            LiteText(value?.toString() ?: "-", style = LiteType.Title)
                                            LiteText(stringResource(label), style = LiteType.Footnote)
                                        }
                                    } }
                                } }
                            }
                        }
                        profile?.let { selected ->
                            ResourceSubscriptionInfo.parse(selected.subscriptionUserInfo)?.let { info -> item {
                                SectionLabel(stringResource(R.string.profile_subscription_info))
                                LitePane { Column(Modifier.padding(16.dp)) {
                                    LiteText(stringResource(R.string.proxy_tools_subscription_bytes, info.used, info.total))
                                    info.expireSeconds?.let { LiteText(formatTime(it * 1000), style = LiteType.Caption) }
                                } }
                            } }
                            item { LitePane { Column {
                                SettingsRow(stringResource(R.string.integration_profile_last_updated), formatTime(selected.lastSuccessfulUpdateAt ?: selected.updatedAt))
                                if (!selected.custom) SettingsRow(stringResource(R.string.override_mode), detail.mode?.let {
                                    stringResource(if (it == dev.aerove.lite.overrides.OverrideMode.Script) R.string.override_script else R.string.override_standard)
                                } ?: "-")
                            } } }
                        }
                        if (detail.failed) item {
                            LiteText(detail.error ?: stringResource(R.string.editor_error), color = MaterialTheme.colorScheme.error)
                            LiteButton(stringResource(R.string.builder_retry), workspace::refresh)
                        }
                        if (detail.failures.isNotEmpty()) item { LiteText(detail.failures.joinToString("\n"), color = MaterialTheme.colorScheme.error) }
                        if (providers.isNotEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(stringResource(R.string.proxy_tools_providers), Modifier.weight(1f))
                            LiteIconButton(stringResource(R.string.proxy_tools_sync), FullGlyphs.refresh, { workspace.updateAll() },
                                enabled = active && detail.busy.isEmpty())
                        } }
                        items(providers, key = { "${it.kind}/${it.name}" }) { provider ->
                            var anchor by remember(provider.name) { mutableStateOf(Rect.Zero) }
                            val busy = "${provider.kind}/${provider.name}" in detail.busy || "*" in detail.busy
                            val resource = detail.resources.singleOrNull { it.key == "$.${if (provider.kind == ProviderKind.Proxy) "proxy-providers" else "rule-providers"}.${provider.name}" }
                            val usage = resource?.subscriptionInfo
                            val text = resource?.format in setOf("yaml", "text")
                            val mrs = resource?.format == "mrs" || provider.format == "mrs"
                            fun dismiss() = overlay.dismiss()
                            fun contextMenu() {
                                val content: @Composable () -> Unit = {
                                    OverrideContextMenu(anchor, provider.name, ::dismiss) {
                                        if (text && resource?.replaceable == true) LiteButton(stringResource(R.string.proxy_tools_edit), { dismiss(); workspace.open(provider, true) }, icon = ProviderEditGlyph)
                                        if (mrs) LiteButton(stringResource(R.string.lite_preview), { dismiss(); workspace.open(provider, false) }, icon = ProviderEyeGlyph)
                                        if (resource?.replaceable == true) LiteButton(stringResource(R.string.proxy_tools_upload), { dismiss(); workspace.requestUpload(provider) }, icon = ProviderUploadGlyph)
                                        if (provider.vehicleType.equals("HTTP", true) || detail.resources.any { it.key == "$.rule-providers.${provider.name}" && it.url != null })
                                            LiteButton(stringResource(R.string.proxy_tools_sync), { dismiss(); workspace.update(provider) }, icon = FullGlyphs.refresh)
                                        if (usage != null) LiteButton(stringResource(R.string.profile_subscription_info), {
                                            dismiss()
                                            overlay.content = { ProfileMaintenanceSheet(stringResource(R.string.profile_subscription_info), overlay::dismiss) {
                                                SettingsRow(stringResource(R.string.profile_usage_used), formatBytes(usage.used))
                                                SettingsRow(stringResource(R.string.profile_usage_total), formatBytes(usage.total))
                                                SettingsRow(stringResource(R.string.profile_expire), usage.expireSeconds?.let { formatTime(it * 1000) }
                                                    ?: stringResource(R.string.profile_never_expires))
                                            } }
                                        }, icon = ProviderDataGlyph)
                                    }
                                }
                                overlay.content = content
                            }
                            LitePane(Modifier.fillMaxWidth().onGloballyPositioned { anchor = it.boundsInRoot() }
                                .then(if (text || mrs) Modifier.combinedClickable(enabled = active && !busy, onClick = { workspace.open(provider, false) }, onLongClick = ::contextMenu)
                                    else if (active && !busy) Modifier.pointerInput(provider.name) { detectTapGestures(onLongPress = { contextMenu() }) }
                                        .semantics { onLongClick { contextMenu(); true } } else Modifier)
                                .testTag("provider-${provider.kind}-${provider.name}")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LiteText(provider.name, style = LiteType.Headline)
                                    if (usage != null) {
                                        LiteText(stringResource(R.string.proxy_tools_subscription_bytes, usage.used, usage.total), style = LiteType.Caption)
                                        val primary = LocalLiteVisuals.current.primary
                                        Box(Modifier.fillMaxWidth().height(4.dp).clip(LiteSuperellipse(LiteCorner.Full)).background(primary.copy(alpha = 0.15f))) {
                                            Box(Modifier.fillMaxHeight().fillMaxWidth(usage.usage).background(primary))
                                        }
                                    } else LiteText(if (provider.kind == ProviderKind.Proxy) stringResource(R.string.proxy_tools_proxy_count, provider.proxies.size)
                                        else provider.ruleCount?.let { stringResource(R.string.proxy_tools_rule_count, it) } ?: stringResource(R.string.proxy_tools_unknown), style = LiteType.Caption)
                                    val updated = provider.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }?.takeIf { it > 0 }
                                    if (busy) {
                                        if (reduced) androidx.compose.material3.CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(24.dp), color = LocalLiteVisuals.current.primary, strokeWidth = 2.dp)
                                        else androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = LocalLiteVisuals.current.primary, strokeWidth = 2.dp)
                                    } else LiteText(updated?.let(::formatTime) ?: stringResource(R.string.proxy_tools_unknown), style = LiteType.Caption)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ProviderEditGlyph = LiteGlyph(listOf(glyphPolyline(listOf(vertex(4,20,1),vertex(8.75,18.75,1),vertex(20.25,7.25,1.5),vertex(16.75,3.75,1.5),vertex(5.25,15.25,1)),closed=true),
    glyphPolyline(listOf(vertex(13.25,7.25),vertex(16.75,10.75)),role=LiteGlyphRole.Detail)))
private val ProviderEyeGlyph = LiteGlyph(listOf(glyphPath("M3.01 11.32A10.08 10.08 0 0 1 20.99 11.32A1.5 1.5 0 0 1 20.99 12.68A10.08 10.08 0 0 1 3.01 12.68A1.5 1.5 0 0 1 3.01 11.32Z"),glyphCircle(12,12,2.8,role=LiteGlyphRole.Detail)))
private val ProviderUploadGlyph = LiteGlyph(listOf(glyphPolyline(listOf(vertex(4.5,16.4),vertex(4.5,20,2.2),vertex(19.5,20,2.2),vertex(19.5,16.4)),role=LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(12,15.4),vertex(12,3.5)),role=LiteGlyphRole.Line),glyphPolyline(listOf(vertex(8.2,7.3),vertex(12,3.5,0.4),vertex(15.8,7.3)),role=LiteGlyphRole.Line)))

private val ProviderDataGlyph = LiteGlyph(listOf(
    glyphPath("M10 14 H15.8 Q17 14 16.897 15.194 A7 7 0 1 1 8.806 7.103 Q10 7 10 8.2 Z"),
    glyphPath("M13.6 9.4 V4.6 Q13.6 3.4 14.794 3.503 A7 7 0 0 1 20.497 9.206 Q20.6 10.4 19.4 10.4 H14.6 Q13.6 10.4 13.6 9.4 Z")))
