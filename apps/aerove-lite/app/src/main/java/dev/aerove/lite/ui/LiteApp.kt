package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteActions
import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R
import dev.aerove.lite.editor.EditorFontSize
import dev.aerove.lite.editor.EditorPreferences
import kotlin.math.abs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal enum class MainTab(@StringRes val label: Int, val icon: LiteGlyph) {
    Home(R.string.lite_tab_home, FullGlyphs.dashboard),
    Proxies(R.string.lite_tab_proxies, FullGlyphs.proxies),
    Profiles(R.string.lite_tab_profiles, FullGlyphs.profiles),
    Tools(R.string.lite_tab_tools, FullGlyphs.tools),
}

internal enum class DetailPage(@StringRes val label: Int) {
    Theme(R.string.lite_theme), General(R.string.lite_general), Connections(R.string.lite_tab_connections),
    Dns(R.string.lite_dns), Apps(R.string.lite_app_filter), Logs(R.string.lite_logs), About(R.string.lite_about),
    Backup(R.string.backup_title),
    Network(R.string.network_title), FontFamily(R.string.presentation_font_family),
}

private val PageScrollSaver = mapSaver<MutableMap<String, LazyListState>>(
    save = { pages -> pages.mapValues { (_, state) -> arrayListOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) } },
    restore = { pages -> pages.mapValues { (_, value) ->
        val saved = value as List<*>
        LazyListState(saved[0] as Int, saved[1] as Int)
    }.toMutableMap() },
)

@Composable
fun LiteApp(state: LiteState, actions: LiteActions) {
    LiteTheme(state.settings) {
        val wallpaper = rememberLayerBackdrop()
        val page = rememberLayerBackdrop()
        val chromeSource = rememberCombinedBackdrop(wallpaper, page)
        val density = LocalDensity.current
        val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val tabs = MainTab.entries.filter { it != MainTab.Proxies || state.groups.isNotEmpty() }
        var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
        val activeTab = tab.takeIf { it in tabs } ?: MainTab.Home
        var detail by rememberSaveable { mutableStateOf<DetailPage?>(null) }
        var subscriptionEditor by rememberSaveable { mutableStateOf(false) }
        var dashboardEditing by rememberSaveable { mutableStateOf(false) }
        var importInput by rememberSaveable { mutableStateOf("") }
        var editorProfile by rememberSaveable { mutableStateOf<String?>(null) }
        var editorTitle by rememberSaveable { mutableStateOf("") }
        var editorReadOnly by rememberSaveable { mutableStateOf(false) }
        val features = LocalLiteFeatures.current
        val scanner = remember { MutableTransitionState(false) }
        var pendingScan by remember { mutableStateOf<String?>(null) }
        val scanMounted = scanner.currentState || scanner.targetState
        var deleting by remember { mutableStateOf<ProfileSummary?>(null) }
        val appearanceOverlay = remember { LiteOverlayState() }
        val covered = subscriptionEditor || deleting != null || state.message != null || appearanceOverlay.content != null || state.updatePromptVisible
        val modal = covered || scanMounted || editorProfile != null || dashboardEditing
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val canDeliverScan by rememberUpdatedState(resumed && !covered)
        LaunchedEffect(scanner.isIdle, scanner.currentState, scanner.targetState, resumed, covered) {
            if (!scanner.isIdle || scanner.currentState || scanner.targetState || !canDeliverScan) return@LaunchedEffect
            withFrameNanos {}
            if (!scanner.isIdle || scanner.currentState || scanner.targetState || !canDeliverScan) return@LaunchedEffect
            val value = pendingScan ?: return@LaunchedEffect
            pendingScan = null
            importInput = value
            subscriptionEditor = true
        }
        DisposableEffect(detail, modal, lifecycle, actions) {
            if (detail == DetailPage.Connections && !modal) {
                var declared: Boolean? = null
                fun declare(active: Boolean) {
                    if (declared != active) {
                        declared = active
                        actions.setConnectionTelemetryActive(active)
                    }
                }
                val observer = LifecycleEventObserver { _, _ -> declare(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
                lifecycle.addObserver(observer)
                declare(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                onDispose { lifecycle.removeObserver(observer); declare(false) }
            } else onDispose {}
        }
        LaunchedEffect(state.settings.openLogs, detail) {
            if (!state.settings.openLogs && detail == DetailPage.Logs) detail = null
        }
        var dockCollapsed by remember(activeTab, detail) { mutableStateOf(false) }
        val pages = rememberSaveableStateHolder()
        val scrolls = rememberSaveable(saver = PageScrollSaver) { mutableMapOf<String, LazyListState>() }
        val pageKey = if (dashboardEditing) "dashboard-editor" else editorProfile?.let { "editor:$it:$editorReadOnly" } ?: detail?.name ?: activeTab.name
        val motionActivity = rememberAuroraMotionActivity(pageKey)
        val effectiveAurora = LocalLiteVisuals.current.aurora
        val phase = rememberAuroraPhase(effectiveAurora && !covered && !scanMounted, activity = motionActivity)
        val scroll = scrolls.getOrPut(pageKey) { LazyListState() }
        val dockScroll = remember(scroll, density.density) {
            object : NestedScrollConnection {
                var direction = 0
                var travel = 0f
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0) {
                        dockCollapsed = false
                        travel = 0f
                    } else if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                        val next = if (consumed.y < 0) 1 else -1
                        if (direction != next) { direction = next; travel = 0f }
                        travel += abs(consumed.y)
                        if (travel >= 12 * density.density) dockCollapsed = direction > 0
                    }
                    return Offset.Zero
                }
            }
        }
        val closeDetail = { detail = if (detail == DetailPage.FontFamily) DetailPage.Theme else null }
        BackHandler(detail != null && !modal, onBack = closeDetail)
        BackHandler(activeTab != MainTab.Home && detail == null && !modal) { tab = MainTab.Home }
        Box(Modifier.fillMaxSize().auroraMotionActivity(motionActivity, effectiveAurora)) {
            Box(Modifier.fillMaxSize().layerBackdrop(wallpaper)) {
                LiteWallpaper(phase, Modifier.fillMaxSize(), state.settings.auroraColors, state.settings.auroraSeed)
            }
            CompositionLocalProvider(LocalLiteBackdrop provides wallpaper, LocalLiteInkBackdrop provides wallpaper, LocalLiteListState provides scroll, LocalAuroraPhase provides phase, LocalLiteOverlay provides appearanceOverlay) {
                val cardContent: @Composable (DashboardWidget) -> Unit = { widget ->
                    DashboardCard(widget, state, actions, { tab = MainTab.Profiles },
                        { detail = DetailPage.Connections }, { tab = MainTab.Proxies },
                        serviceStatusContent = { ServiceStatusPanel(state, actions) },
                        memoryInfoContent = { MemoryInfoPanel(state, actions) })
                }
                CompositionLocalProvider(LocalDiagnosticsForeground provides
                    (resumed && !subscriptionEditor && deleting == null && state.message == null && !state.updatePromptVisible && !scanMounted && editorProfile == null && !dashboardEditing)) {
                val editing = editorProfile
                if (dashboardEditing) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        DashboardEditor(state.settings, actions::changeSettings, { dashboardEditing = false }, cardContent = cardContent)
                    }
                } else if (editing != null) {
                    val session = remember(editing, editorTitle, editorReadOnly) { features().editor(editing, editorTitle, editorReadOnly) }
                    val close = { features().closeEditor(); editorProfile = null }
                    val preferences = EditorPreferences(
                        EditorFontSize.entries.first { it.sp.toInt() == state.settings.editorFontSize },
                        state.settings.editorLineWrap,
                    )
                    EditorScreen(session, close, { features().editorSaved(editing); close() },
                        preferences = preferences, onPreferencesChange = { value ->
                            actions.changeSettings(state.settings.copy(editorFontSize = value.fontSize.sp.toInt(), editorLineWrap = value.lineWrap))
                        })
                } else {
                val contentModifier = Modifier.fillMaxSize().nestedScroll(dockScroll).then(if (modal) Modifier.clearAndSetSemantics {} else Modifier)
                Box(contentModifier.layerBackdrop(page)) {
                    val dockInset = if (detail == null) 62.dp + maxOf(21.dp, bottomInset) else bottomInset
                    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + LiteMetrics.PageToolbar, bottom = 20.dp + dockInset)
                    pages.SaveableStateProvider(pageKey) {
                        when (detail) {
                            DetailPage.Theme -> SettingsScreen(state, actions, padding, onFontFamily = { detail = DetailPage.FontFamily })
                            DetailPage.FontFamily -> FontFamilyScreen(state, actions, padding)
                            DetailPage.General -> GeneralScreen(state, actions, padding)
                            DetailPage.Connections -> ConnectionsScreen(state, actions, padding)
                            DetailPage.Dns -> DnsScreen(state, actions, padding)
                            DetailPage.Apps -> AppFilterScreen(state, actions, padding)
                            DetailPage.Logs -> LogsScreen(state, actions, padding)
                            DetailPage.About -> AboutScreen(padding)
                            DetailPage.Backup -> BackupScreen(features().backup, padding)
                            DetailPage.Network -> NetworkSettingsScreen(state.settings.network, "settings" in state.busy, padding,
                                { actions.changeSettings(state.settings.copy(network = it)) }, error = state.networkError,
                                restartRequired = state.networkRestartRequired, onRestart = actions::connect,
                                onRetry = if (state.phase == dev.aerove.lite.VpnPhase.Running) actions::retryNetworkSettings else null)
                            null -> when (activeTab) {
                                MainTab.Home -> OverviewScreen(state.settings, padding, cardContent)
                                MainTab.Proxies -> ProxiesScreen(state, actions, padding)
                                MainTab.Profiles -> ProfilesScreen(state, actions, padding,
                                    { importInput = ""; subscriptionEditor = true }, { deleting = it },
                                    { profile, preview -> editorTitle = profile.name; editorReadOnly = preview; editorProfile = profile.id })
                                MainTab.Tools -> ToolsScreen(state, actions, padding) { detail = it }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalLiteBackdrop provides chromeSource) {
                    LitePageHeader(
                        title = stringResource(detail?.label ?: activeTab.label), topInset = topInset, scroll = scroll,
                        modifier = Modifier.align(Alignment.TopCenter).then(if (modal) Modifier.clearAndSetSemantics {} else Modifier),
                        back = if (detail != null) closeDetail else null,
                        backLabel = stringResource(R.string.lite_back),
                    ) {
                        if (detail == null && activeTab == MainTab.Home) DashboardEditAction { dashboardEditing = true }
                        if (detail == null && activeTab == MainTab.Profiles) {
                            LiteButton(stringResource(R.string.integration_scan_profile), {
                                if (!scanMounted && pendingScan == null) scanner.targetState = true
                            })
                            LiteIconButton(stringResource(R.string.lite_add_subscription), FullGlyphs.add, { importInput = ""; subscriptionEditor = true })
                        }
                    }
                    if (detail == null) LiteNavigationDock(
                        items = tabs, selected = activeTab, collapsed = dockCollapsed, bottomInset = bottomInset,
                        onSelected = { tab = it; detail = null },
                        modifier = Modifier.align(Alignment.BottomCenter).then(if (modal) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    if (subscriptionEditor) SubscriptionEditor(state, actions, { subscriptionEditor = false }, importInput,
                        { name, input -> features().importProfileText(name, input) })
                    deleting?.let { profile ->
                        LiteModal(stringResource(R.string.lite_delete_profile), { deleting = null }) {
                            LiteText(stringResource(R.string.lite_delete_profile_detail, profile.name))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LiteButton(stringResource(R.string.lite_cancel), { deleting = null }, Modifier.weight(1f))
                                LiteButton(stringResource(R.string.lite_delete), { actions.deleteProfile(profile.id); deleting = null }, Modifier.weight(1f), destructive = true)
                            }
                        }
                    }
                    appearanceOverlay.content?.invoke()
                }
                }
                }
                AnimatedVisibility(scanner, enter = fadeIn(tween(if (LocalLiteReducedMotion.current) 0 else 220)),
                    exit = fadeOut(tween(if (LocalLiteReducedMotion.current) 0 else 180))) {
                    ScanScreen(onResult = { pendingScan = it; scanner.targetState = false },
                        onClose = { pendingScan = null; scanner.targetState = false },
                        routeSettled = scanner.isIdle && scanner.currentState && scanner.targetState && !covered)
                }
                CompositionLocalProvider(LocalLiteBackdrop provides if (scanMounted) wallpaper else chromeSource) {
                    state.message?.let { message ->
                        LiteModal(stringResource(R.string.lite_message), actions::dismissMessage) {
                            SelectionContainer { LiteText(message, Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) }
                            LiteButton(stringResource(R.string.lite_dismiss), actions::dismissMessage, Modifier.fillMaxWidth())
                        }
                    }
                    if (state.updatePromptVisible) UpdateHost(features().updates)
                }
            }
        }
    }
}

@Composable
internal fun LiteModal(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    val scrim = rememberLayerBackdrop()
    val modalSource = rememberCombinedBackdrop(LocalLiteBackdrop.current, scrim)
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().layerBackdrop(scrim).background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onDismiss))
    CompositionLocalProvider(LocalLiteBackdrop provides modalSource) {
    Box(Modifier.fillMaxSize().imePadding().padding(20.dp), contentAlignment = Alignment.Center) {
        LitePane(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)
                .pointerInput(Unit) { detectTapGestures {} },
            corner = LiteCorner.Alert,
            solidColor = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright,
            glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.alert((if (visuals.dark) colors.surfaceContainerHighest else colors.surfaceBright).copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.alert(visuals.dark),
            shadows = if (visuals.material == dev.aerove.lite.MaterialTier.Solid) emptyList() else fullMenuShadows(visuals.dark),
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()).semantics { paneTitle = title },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiteText(title, Modifier.weight(1f), style = LiteType.Headline)
                    LiteIconButton(stringResource(R.string.lite_close), FullGlyphs.close, onDismiss)
                }
                content()
            }
        }
    }
    }
}
