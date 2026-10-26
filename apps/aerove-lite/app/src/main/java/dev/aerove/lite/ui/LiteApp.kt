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
import androidx.compose.foundation.layout.height
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.CubicBezierEasing
import dev.aerove.lite.navigation.floatingNavigationInset
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
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
    Advanced(R.string.integration_advanced_configuration), Hosts(R.string.dns_preferences_hosts),
    Extend(R.string.integration_extend), Scripts(R.string.override_scripts), GlobalRules(R.string.override_global_rules),
    Requests(R.string.history_requests), DnsQueries(R.string.history_dns),
    Dns(R.string.lite_dns), Apps(R.string.lite_app_filter), Logs(R.string.lite_logs), About(R.string.lite_about),
    Resources(R.string.proxy_tools_resources), Backup(R.string.backup_title), GlobalResources(R.string.global_resources_title),
    Network(R.string.network_title), FontFamily(R.string.presentation_font_family), UserAgents(R.string.requests_user_agent),
    ProxyPreferences(R.string.lite_tab_proxies),
    OnDemand(R.string.background_on_demand),
    Disclaimer(R.string.maintenance_disclaimer), Developer(R.string.maintenance_developer_mode),
}

private val PageScrollSaver = mapSaver<MutableMap<String, LazyListState>>(
    save = { pages -> pages.mapValues { (_, state) -> arrayListOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) } },
    restore = { pages -> pages.mapValues { (_, value) ->
        val saved = value as List<*>
        LazyListState(saved[0] as Int, saved[1] as Int)
    }.toMutableMap() },
)

@Composable
fun LiteApp(state: LiteState, actions: LiteActions, display: dev.aerove.lite.integration.LiteDisplayStreams? = null) {
    LiteWindowFocusHost { windowFocused ->
    LiteTheme(state.settings) {
        val wallpaper = rememberLayerBackdrop()
        val page = rememberLayerBackdrop()
        val chromeSource = rememberCombinedBackdrop(wallpaper, page)
        val density = LocalDensity.current
        val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val hasGroups = state.groups.isNotEmpty()
        val tabs = remember(hasGroups) { MainTab.entries.filter { it != MainTab.Proxies || hasGroups } }
        var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
        val activeTab = tab.takeIf { it in tabs } ?: MainTab.Home
        LaunchedEffect(tabs, tab) { if (tab !in tabs) tab = MainTab.Home }
        var proxyQuery by rememberSaveable { mutableStateOf("") }
        var proxySearchExpanded by rememberSaveable { mutableStateOf(false) }
        var proxyLocateRequest by rememberSaveable { mutableStateOf(0) }
        var detail by rememberSaveable { mutableStateOf<DetailPage?>(null) }
        var subscriptionEditor by rememberSaveable { mutableStateOf(false) }
        var profileDetailOpen by rememberSaveable { mutableStateOf(false) }
        var dashboardEditing by rememberSaveable { mutableStateOf(false) }
        var importInput by rememberSaveable { mutableStateOf("") }
        var builderProfile by rememberSaveable { mutableStateOf<String?>(null) }
        var overrideProfile by rememberSaveable { mutableStateOf<String?>(null) }
        var scriptEditing by rememberSaveable { mutableStateOf<String?>(null) }
        var scriptsParent by rememberSaveable { mutableStateOf(DetailPage.Advanced.name) }
        var creatingProfile by remember { mutableStateOf(false) }
        var appAccessBack by remember { mutableStateOf<(() -> Unit)?>(null) }
        val registerAppAccessBack = remember { { handler: (() -> Unit)? -> appAccessBack = handler } }
        val rootScope = rememberCoroutineScope()
        val builderName = stringResource(R.string.builder_title)
        val operationError = stringResource(R.string.editor_error)
        var editorProfile by rememberSaveable { mutableStateOf<String?>(null) }
        var editorTitle by rememberSaveable { mutableStateOf("") }
        var editorReadOnly by rememberSaveable { mutableStateOf(false) }
        val features = LocalLiteFeatures.current
        fun openSubscription(input: String = "") {
            features().profileImports.dismiss()
            importInput = input
            subscriptionEditor = true
        }
        val scanner = remember { MutableTransitionState(false) }
        var pendingScan by remember { mutableStateOf<String?>(null) }
        val scanMounted = scanner.currentState || scanner.targetState
        var deleting by remember { mutableStateOf<ProfileSummary?>(null) }
        val appearanceOverlay = remember { LiteOverlayState() }
        val requiresConsent = state.initialized && state.settings.acceptedDisclaimerVersion < dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION
        val otherCovered = requiresConsent || subscriptionEditor || state.profileImportVisible || state.profileImportKeyPrompt || deleting != null || state.message != null || appearanceOverlay.content != null || state.updatePromptVisible || state.externalProfile != null
        val covered = profileDetailOpen || otherCovered
        val modal = covered || scanMounted || editorProfile != null || dashboardEditing || builderProfile != null || scriptEditing != null
        val overrideWorkspace = if (detail in setOf(DetailPage.Extend, DetailPage.Scripts, DetailPage.GlobalRules) || scriptEditing != null) features().overrides else null
        DisposableEffect(overrideWorkspace) {
            overrideWorkspace?.setEditorActive(true)
            overrideWorkspace?.refresh()
            onDispose { overrideWorkspace?.setEditorActive(false) }
        }
        fun createCustomProfile() {
            if (creatingProfile) return
            creatingProfile = true
            rootScope.launch {
                try { builderProfile = features().createProfileBuilder(builderName).profileId }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { appearanceOverlay.content = { LiteModal(operationError, appearanceOverlay::dismiss) { LiteText(operationError) } } }
                finally { creatingProfile = false }
            }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val providerActive = resumed && windowFocused && !otherCovered && !scanMounted && editorProfile == null && !dashboardEditing && builderProfile == null && scriptEditing == null
        DisposableEffect(providerActive, actions) {
            actions.setProviderTelemetryActive(providerActive)
            onDispose { actions.setProviderTelemetryActive(false) }
        }
        val canDeliverScan by rememberUpdatedState(resumed && !covered)
        LaunchedEffect(scanner.isIdle, scanner.currentState, scanner.targetState, resumed, covered) {
            if (!scanner.isIdle || scanner.currentState || scanner.targetState || !canDeliverScan) return@LaunchedEffect
            withFrameNanos {}
            if (!scanner.isIdle || scanner.currentState || scanner.targetState || !canDeliverScan) return@LaunchedEffect
            val value = pendingScan ?: return@LaunchedEffect
            pendingScan = null
            openSubscription(value)
        }
        DisposableEffect(detail, modal, lifecycle, actions, windowFocused) {
            if (detail == DetailPage.Connections && !modal && windowFocused) {
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
        val pageKey = scriptEditing?.let { "script:$it" } ?: builderProfile?.let { "builder:$it" } ?: if (dashboardEditing) "dashboard-editor" else editorProfile?.let { "editor:$it:$editorReadOnly" } ?: detail?.name ?: activeTab.name
        val motionActivity = rememberAuroraMotionActivity(pageKey)
        val effectiveAurora = LocalLiteVisuals.current.aurora
        val opaqueScan = scanner.isIdle && scanner.currentState && scanner.targetState
        val auroraTime = rememberAuroraTime(effectiveAurora && liteWallpaperVisible(windowFocused, opaqueScan, requiresConsent), activity = motionActivity)
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
        val closeDetail: () -> Unit = {
            if (detail == DetailPage.Apps && appAccessBack != null) appAccessBack?.invoke()
            else if (detail == DetailPage.GlobalResources && features().globalResources.session.back()) Unit
            else detail = when (detail) { DetailPage.FontFamily -> DetailPage.Theme; DetailPage.UserAgents, DetailPage.Hosts, DetailPage.OnDemand -> DetailPage.General; DetailPage.Dns, DetailPage.Network, DetailPage.GlobalRules -> DetailPage.Advanced; DetailPage.Scripts -> DetailPage.valueOf(scriptsParent); else -> null }
        }
        BackHandler(detail != null && !modal, onBack = closeDetail)
        Box(Modifier.fillMaxSize().auroraMotionActivity(motionActivity, resumed)) {
            Box(Modifier.fillMaxSize().layerBackdrop(wallpaper)) {
                LiteWallpaper(auroraTime, Modifier.fillMaxSize(), state.settings.auroraColors, state.settings.auroraSeed)
            }
            CompositionLocalProvider(LocalLiteDisplayStreams provides display, LocalLiteMotionActivity provides motionActivity, LocalLiteBackdrop provides wallpaper, LocalLiteInkBackdrop provides wallpaper, LocalLiteListState provides scroll, LocalLiteOverlay provides appearanceOverlay) {
                val cardContent: @Composable (DashboardWidget) -> Unit = { widget ->
                    DashboardCard(widget, state, actions, { tab = MainTab.Profiles },
                        { detail = DetailPage.Connections }, { tab = MainTab.Proxies },
                        serviceStatusContent = { DiagnosticsLeaf(state) { ServiceStatusPanel(it, actions) } },
                        memoryInfoContent = { DiagnosticsLeaf(state) { MemoryInfoPanel(it, actions) } }, openProfileDetails = {
                            val selected = state.profiles.firstOrNull { it.id == state.selectedProfile }
                            if (selected?.custom == true) builderProfile = selected.id else if (selected != null) profileDetailOpen = true
                        }, openRequests = { detail = DetailPage.Requests }, openDns = { detail = DetailPage.DnsQueries })
                }
                CompositionLocalProvider(LocalDiagnosticsForeground provides
                    (resumed && windowFocused && !covered && !scanMounted && editorProfile == null && builderProfile == null && scriptEditing == null && !dashboardEditing)) {
                val navigationVisible = detail == null && editorProfile == null && builderProfile == null && scriptEditing == null && !dashboardEditing
                val floating = state.settings.floatingNavigationBar
                val navigationEnabled = navigationVisible && resumed && !modal
                val visibilityMillis = if (LocalLiteReducedMotion.current || !resumed || modal) 0 else 300
                val visibilityEnter = CubicBezierEasing(0f, 0f, 0f, 1f)
                val visibilityExit = CubicBezierEasing(0f, 0f, 0.7f, 1f)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalLitePageActive provides (resumed && windowFocused && !covered && !scanMounted),
                            LocalLiteReducedMotion provides (LocalLiteReducedMotion.current || !resumed || !windowFocused || covered || scanMounted)) {
                        Box(Modifier.fillMaxSize().then(if (LocalLiteVisuals.current.material == dev.aerove.lite.MaterialTier.Solid) Modifier else Modifier.layerBackdrop(page))) {
                        LiteTabTransitionHost(tabs, activeTab, state.settings.tabAnimation,
                            active = resumed && windowFocused && !modal, visible = navigationVisible,
                            pageActive = navigationVisible && windowFocused && !covered && !scanMounted) { scene, logicalActive ->
                            val proxyWorkspace = if (scene == MainTab.Proxies) remember { features().proxyPresentation } else null
                            val sceneScroll = scrolls.getOrPut(scene.name) { LazyListState() }
                            val sceneBody = rememberLayerBackdrop()
                            val sceneSource = rememberCombinedBackdrop(wallpaper, sceneBody)
                            val inset = if (floating) floatingNavigationInset(density.fontScale, bottomInset.value).dp else 0.dp
                            val padding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + LiteMetrics.PageToolbar, bottom = 20.dp + inset)
                            CompositionLocalProvider(LocalLiteListState provides sceneScroll) {
                                Box(Modifier.fillMaxSize().then(if (logicalActive && floating) Modifier.nestedScroll(dockScroll) else Modifier).then(if (LocalLiteVisuals.current.material == dev.aerove.lite.MaterialTier.Solid) Modifier else Modifier.layerBackdrop(sceneBody))) {
                                    pages.SaveableStateProvider(scene.name) {
                                        when (scene) {
                                            MainTab.Home -> OverviewScreen(state.settings, padding, cardContent)
                                            MainTab.Proxies -> ProxiesScreen(state, actions, padding, proxyWorkspace!!, proxyQuery, proxyLocateRequest)
                                            MainTab.Profiles -> ProfilesScreen(state, actions, padding,
                                                { openSubscription() }, { deleting = it },
                                                { profile, preview -> editorTitle = profile.name; editorReadOnly = preview; editorProfile = profile.id },
                                                buildProfile = { builderProfile = it.id }, createProfile = { createCustomProfile() },
                                                extendProfile = { overrideProfile = it.id; detail = DetailPage.Extend })
                                            MainTab.Tools -> ToolsScreen(state, actions, padding) { detail = it }
                                        }
                                    }
                                }
                                CompositionLocalProvider(LocalLiteBackdrop provides sceneSource) {
                                    val proxyState = proxyWorkspace?.state?.collectAsState()?.value
                                    val proxyEnabled = logicalActive && resumed && !modal && proxyState?.ready == true
                                    val tabStyle = state.settings.proxyPresentation.type == dev.aerove.lite.proxies.ProxyPresentationType.tab
                                    val testCurrent: @Composable () -> Unit = {
                                        if (tabStyle) LiteIconButton(stringResource(R.string.lite_test_group), FullGlyphs.speed,
                                            { proxyState?.currentGroup?.let { proxyWorkspace?.testGroup(it, proxyQuery) } },
                                            enabled = proxyEnabled && proxyState?.currentGroup != null)
                                    }
                                    val searchToolbar: (@Composable () -> Unit)? = if (scene == MainTab.Proxies && proxySearchExpanded && logicalActive) ({
                                        ProxyPresentationSearchBar(proxyQuery, { proxyQuery = it }, { proxySearchExpanded = it },
                                            enabled = proxyEnabled, actions = testCurrent)
                                    }) else null
                                    LitePageHeader(stringResource(scene.label), topInset, sceneScroll,
                                        modifier = Modifier.align(Alignment.TopCenter), backLabel = stringResource(R.string.lite_back),
                                        toolbarContent = searchToolbar) {
                                        if (scene == MainTab.Proxies) {
                                            ProxyPresentationSearchAction(proxyQuery, { proxyQuery = it }, proxyEnabled) { proxySearchExpanded = it }
                                            ProxyPresentationOptionsButton(state.settings.proxyPresentation, logicalActive && resumed && !modal) {
                                                actions.changeSettings(state.settings.copy(proxyPresentation = it))
                                            }
                                            if (tabStyle) {
                                                ProxyPresentationLocateAction(proxyEnabled) { proxyLocateRequest++ }
                                                testCurrent()
                                            }
                                        }
                                        if (scene == MainTab.Home) DashboardEditAction { dashboardEditing = true }
                                        if (scene == MainTab.Profiles) {
                                            LiteButton(stringResource(R.string.integration_scan_profile), {
                                                if (!scanMounted && pendingScan == null) scanner.targetState = true
                                            })
                                            LiteIconButton(stringResource(R.string.lite_add_subscription), FullGlyphs.add,
                                                { openSubscription() })
                                        }
                                    }
                                }
                            }
                        }
                        if (dashboardEditing) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                DashboardEditor(state.settings, actions::changeSettings, { dashboardEditing = false }, cardContent = cardContent)
                            }
                        } else if (builderProfile != null) {
                            val id = builderProfile!!
                            val session = remember(id) { features().profileBuilder(id) }
                            var retry by remember(id) { mutableStateOf(0) }
                            val resources by produceState<Result<dev.aerove.lite.profilebuilder.BuilderResources>?>(null, session, retry) {
                                value = try { Result.success(features().profileBuilderResources()) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (failure: Exception) { Result.failure(failure) }
                            }
                            val close = { features().closeProfileBuilder(); builderProfile = null }
                            BackHandler(resources?.isSuccess != true, onBack = close)
                            val padding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + LiteMetrics.PageToolbar, bottom = bottomInset + 20.dp)
                            val body = rememberLayerBackdrop()
                            val headerSource = rememberCombinedBackdrop(wallpaper, body)
                            Box(Modifier.fillMaxSize().then(if (LocalLiteVisuals.current.material == dev.aerove.lite.MaterialTier.Solid) Modifier else Modifier.layerBackdrop(body))) {
                                val result = resources
                                if (result?.isSuccess == true) ProfileBuilderScreen(session, padding, close,
                                    onSaved = { session.savedProfileId?.let { features().editorSaved(it) } }, resources = result.getOrThrow())
                                else Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    result?.exceptionOrNull()?.let { LiteText(it.message ?: stringResource(R.string.editor_error)) }
                                    LiteButton(stringResource(R.string.builder_retry), { retry++ })
                                    LiteButton(stringResource(R.string.builder_close), close)
                                }
                            }
                            CompositionLocalProvider(LocalLiteBackdrop provides headerSource) {
                                LitePageHeader(stringResource(R.string.builder_title), topInset, scroll,
                                    modifier = Modifier.align(Alignment.TopCenter), backLabel = stringResource(R.string.lite_back)) {}
                            }
                        } else if (scriptEditing != null) {
                            val workspace = checkNotNull(overrideWorkspace)
                            val cState by workspace.state.collectAsState()
                            val script = cState.snapshot?.scripts?.firstOrNull { it.id == scriptEditing }
                            BackHandler(script == null) { scriptEditing = null }
                            if (script != null) {
                                val preferences = EditorPreferences(EditorFontSize.entries.first { it.sp.toInt() == state.settings.editorFontSize }, state.settings.editorLineWrap)
                                ScriptEditorScreen(workspace.repository, script, { scriptEditing = null },
                                    { workspace.scriptSaved(); scriptEditing = null }, preferences = preferences,
                                    onPreferencesChange = { value -> actions.changeSettings(state.settings.copy(editorFontSize = value.fontSize.sp.toInt(), editorLineWrap = value.lineWrap)) })
                            } else Column(Modifier.padding(top = topInset + LiteMetrics.PageToolbar)) {
                                LiteText(stringResource(if (cState.snapshot == null) R.string.editor_loading else R.string.editor_error))
                                LiteButton(stringResource(R.string.builder_close), { scriptEditing = null })
                            }
                        } else if (editorProfile != null) {
                            val editing = editorProfile!!
                            val session = remember(editing, editorTitle, editorReadOnly) { features().editor(editing, editorTitle, editorReadOnly) }
                            val close = { features().closeEditor(); editorProfile = null }
                            val preferences = EditorPreferences(EditorFontSize.entries.first { it.sp.toInt() == state.settings.editorFontSize }, state.settings.editorLineWrap)
                            EditorScreen(session, close, { features().editorSaved(editing); close() }, preferences = preferences,
                                onPreferencesChange = { value -> actions.changeSettings(state.settings.copy(editorFontSize = value.fontSize.sp.toInt(), editorLineWrap = value.lineWrap)) })
                        } else if (detail != null) {
                            val body = rememberLayerBackdrop()
                            val headerSource = rememberCombinedBackdrop(wallpaper, body)
                            val padding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + LiteMetrics.PageToolbar, bottom = 20.dp + bottomInset)
                            Box(Modifier.fillMaxSize().then(if (modal) Modifier.clearAndSetSemantics {} else Modifier).then(if (LocalLiteVisuals.current.material == dev.aerove.lite.MaterialTier.Solid) Modifier else Modifier.layerBackdrop(body))) {
                                pages.SaveableStateProvider(pageKey) {
                                    when (detail) {
                                        DetailPage.Theme -> SettingsScreen(state, actions, padding, onFontFamily = { detail = DetailPage.FontFamily })
                                        DetailPage.FontFamily -> FontFamilyScreen(state, actions, padding)
                                        DetailPage.General -> GeneralScreen(state, actions, padding, onUserAgents = { detail = DetailPage.UserAgents }, onHosts = { detail = DetailPage.Hosts }, onOnDemand = { detail = DetailPage.OnDemand })
                                        DetailPage.OnDemand -> BackgroundPolicyScreen(state.settings.backgroundPolicy,
                                            { actions.changeSettings(state.settings.copy(backgroundPolicy = it, autoStart = it.autoRun)) },
                                            state.locationGranted, state.batteryIgnored, actions::requestOnDemandLocation, actions::requestBatteryExemption,
                                            padding, state.busy.isEmpty())
                                        DetailPage.Hosts -> HostsPreferencesScreen(state.settings.dnsPreferences, "settings" in state.busy, padding,
                                            { actions.changeSettings(state.settings.copy(dnsPreferences = it)) })
                                        DetailPage.Advanced -> AdvancedConfigScreen(padding) { destination ->
                                            if (destination == DetailPage.Scripts) scriptsParent = DetailPage.Advanced.name
                                            detail = destination
                                        }
                                        DetailPage.Extend, DetailPage.Scripts, DetailPage.GlobalRules -> {
                                            val workspace = checkNotNull(overrideWorkspace)
                                            val cState by workspace.state.collectAsState()
                                            val library by workspace.scripts.state.collectAsState()
                                            val snapshot = cState.snapshot
                                            if (snapshot == null) Column(Modifier.padding(padding)) {
                                                LiteText(stringResource(if (cState.failed) R.string.editor_error else R.string.editor_loading))
                                                LiteButton(stringResource(R.string.builder_retry), { workspace.refresh() })
                                            } else when (detail) {
                                                DetailPage.Extend -> {
                                                    val id = overrideProfile
                                                    if (id != null && state.profiles.any { it.id == id && !it.custom }) OverrideScreen(
                                                        snapshot.profiles.firstOrNull { it.profileId == id } ?: dev.aerove.lite.overrides.ProfileOverride(id), snapshot,
                                                        { workspace.putProfile(it) }, { scriptsParent = DetailPage.Extend.name; detail = DetailPage.Scripts }, padding, cState.busy.isEmpty())
                                                    else LiteText(stringResource(R.string.editor_error), Modifier.padding(padding))
                                                }
                                                DetailPage.GlobalRules -> OverrideGlobalRulesScreen(snapshot.globalRules, { workspace.putGlobalRules(it) }, padding, cState.busy.isEmpty())
                                                DetailPage.Scripts -> ScriptLibraryScreen(library.copy(busyIds = library.busyIds + cState.busy,
                                                    errorCode = if (cState.failed) "update_failed" else library.errorCode),
                                                    { workspace.putScript(it) }, { scriptEditing = it.id }, { workspace.deleteScript(it) },
                                                    { id, index -> workspace.moveScript(id, index) }, { workspace.updateScript(it) }, { workspace.cancelScript(it) },
                                                    { name, content -> workspace.importScript(name, content) }, padding)
                                                else -> Unit
                                            }
                                        }
                                        DetailPage.UserAgents -> UserAgentPreferencesScreen(state.settings.requests, { actions.changeSettings(state.settings.copy(requests = it)) }, padding, state.busy.isEmpty())
                                        DetailPage.ProxyPreferences -> ProxyPreferencesScreen(state.settings.proxyPresentation, padding, "settings" in state.busy, { actions.changeSettings(state.settings.copy(proxyPresentation = it)) })
                                        DetailPage.Connections -> ConnectionsLeaf(state) { ConnectionsScreen(it, actions, padding) }
                                        DetailPage.Dns -> DnsPreferencesScreen(state.settings.dnsPreferences, "settings" in state.busy, padding,
                                            { actions.changeSettings(state.settings.copy(dnsPreferences = it)) })
                                        DetailPage.Apps -> {
                                            val owner = remember(features) { features().appAccess }
                                            val accessState by owner.state.collectAsState()
                                            DisposableEffect(owner) { onDispose { features().closeAppAccess() } }
                                            AppAccessScreen(owner, accessState, padding, { detail = null }, actions::authorizeApplications,
                                                registerAppAccessBack, active = LocalLitePageActive.current)
                                        }
                                        DetailPage.Logs -> LogsScreen(features().observability, padding)
                                        DetailPage.Requests -> RequestsScreen(features().observability, padding, state.settings.historyTables) { change -> actions.changeHistoryTable(change) }
                                        DetailPage.DnsQueries -> DnsQueriesScreen(features().observability, padding, state.settings.historyTables) { change -> actions.changeHistoryTable(change) }
                                        DetailPage.About -> AboutScreen(padding) { actions.changeSettings(state.settings.copy(developerMode = true)) }
                                        DetailPage.Disclaimer -> DisclaimerScreen(false, {}, {}, padding)
                                        DetailPage.Developer -> {
                                            val owner = features().maintenance
                                            val maintenanceState by owner.state.collectAsState()
                                            MaintenanceScreen(maintenanceState, owner, state.settings.developerMode,
                                                { actions.changeSettings(state.settings.copy(developerMode = it)) },
                                                { features().developerMessageTest() }, { features().developerLogsTest() }, padding)
                                        }
                                        DetailPage.Resources -> ResourcePage(state, actions, features().resources, padding)
                                        DetailPage.GlobalResources -> GlobalResourcePage(features().globalResources, features().groupIcons, padding, { detail = null })
                                        DetailPage.Backup -> BackupScreen(features().backup, padding)
                                        DetailPage.Network -> NetworkSettingsScreen(state.settings.network, "settings" in state.busy, padding,
                                            { actions.changeSettings(state.settings.copy(network = it)) }, error = state.networkError,
                                            restartRequired = state.networkRestartRequired, onRestart = actions::connect,
                                            onRetry = if (state.phase == dev.aerove.lite.VpnPhase.Running) actions::retryNetworkSettings else null)
                                        null -> Unit
                                    }
                                }
                            }
                            CompositionLocalProvider(LocalLiteBackdrop provides headerSource) {
                                LitePageHeader(stringResource(if (detail == DetailPage.GlobalResources) globalResourceTitle(features().globalResources.session.state.collectAsState().value.category) else detail!!.label), topInset, scroll,
                                    modifier = Modifier.align(Alignment.TopCenter).then(if (modal) Modifier.clearAndSetSemantics {} else Modifier),
                                    back = closeDetail, backLabel = stringResource(R.string.lite_back)) {}
                            }
                        }
                        }
                        }
                        CompositionLocalProvider(LocalLiteBackdrop provides chromeSource) {
                            androidx.compose.animation.AnimatedVisibility(navigationVisible && floating, Modifier.align(Alignment.BottomCenter),
                                enter = fadeIn(tween(visibilityMillis, easing = visibilityEnter)) + slideInVertically(tween(visibilityMillis, easing = visibilityEnter)) { it },
                                exit = fadeOut(tween(visibilityMillis, easing = visibilityExit)) + slideOutVertically(tween(visibilityMillis, easing = visibilityExit)) { it }) {
                                LiteNavigationDock(tabs, activeTab, dockCollapsed, bottomInset, { tab = it }, enabled = navigationEnabled && floating)
                            }
                        }
                    }
                    AnimatedVisibility(navigationVisible && !floating,
                        enter = expandVertically(tween(visibilityMillis, easing = visibilityEnter), expandFrom = Alignment.Bottom) + fadeIn(tween(visibilityMillis, easing = visibilityEnter)),
                        exit = shrinkVertically(tween(visibilityMillis, easing = visibilityExit), shrinkTowards = Alignment.Bottom) + fadeOut(tween(visibilityMillis, easing = visibilityExit))) {
                        LiteDockedNavigationBar(tabs, activeTab, bottomInset, { tab = it }, enabled = navigationEnabled && !floating)
                    }
                }
                CompositionLocalProvider(LocalLiteBackdrop provides chromeSource) {
                    if (profileDetailOpen && editorProfile == null) ProfileDetailSheet(state, actions, features().profileDetails,
                        { features().profileDetails.visible(false); profileDetailOpen = false }, {
                            state.profiles.firstOrNull { it.id == state.selectedProfile }?.let {
                                features().profileDetails.visible(false)
                                editorTitle = it.name; editorReadOnly = true; editorProfile = it.id
                            }
                        }, active = !otherCovered && !scanMounted)
                    if (subscriptionEditor || state.profileImportVisible) ProfileImportSheet(features().profileImports, importInput,
                        { subscriptionEditor = false; importInput = "" })
                    if (state.profileImportKeyPrompt) {
                        val imports = features().profileImports
                        val importState by imports.state.collectAsState()
                        AgeSecretKeyPrompt(importState.fileBusy, importState.errorCode, imports::dismiss, imports::submitFileKey)
                    }
                    deleting?.let { profile ->
                        LiteModal(stringResource(R.string.lite_delete_profile), { deleting = null }) {
                            LiteText(stringResource(R.string.lite_delete_profile_detail, profile.name))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LiteButton(stringResource(R.string.lite_cancel), { deleting = null }, Modifier.weight(1f))
                                LiteButton(stringResource(R.string.lite_delete), { actions.deleteProfile(profile.id); deleting = null }, Modifier.weight(1f), destructive = true)
                            }
                        }
                    }
                    if (requiresConsent) Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                        DisclaimerScreen(true, { actions.changeSettings(state.settings.copy(acceptedDisclaimerVersion = it)) }, actions::declineDisclaimer)
                    }
                    appearanceOverlay.content?.invoke()
                }
                }
                AnimatedVisibility(scanner, enter = fadeIn(tween(if (LocalLiteReducedMotion.current) 0 else 220)),
                    exit = fadeOut(tween(if (LocalLiteReducedMotion.current) 0 else 180))) {
                    ScanScreen(onResult = { pendingScan = it; scanner.targetState = false },
                        onClose = { pendingScan = null; scanner.targetState = false },
                        routeSettled = scanner.isIdle && scanner.currentState && scanner.targetState && !covered)
                }
                CompositionLocalProvider(LocalLiteBackdrop provides if (scanMounted) wallpaper else chromeSource) {
                    if (!requiresConsent && state.externalProfile != null && state.message == null && !state.updatePromptVisible && !scanMounted &&
                        appearanceOverlay.content == null && !subscriptionEditor && deleting == null) {
                        LiteModal(stringResource(R.string.integration_add_profile), actions::dismissExternalProfile) {
                            val url = state.externalProfile
                            val message = stringResource(R.string.integration_external_profile_confirm, url)
                            val start = message.indexOf(url)
                            val annotated = androidx.compose.ui.text.buildAnnotatedString {
                                append(message)
                                if (start >= 0) addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), start, start + url.length)
                            }
                            androidx.compose.foundation.text.BasicText(annotated, style = LiteType.Body.copy(
                                color = LocalLiteVisuals.current.ink, fontFamily = LocalLiteFontFamily.current))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LiteButton(stringResource(R.string.lite_cancel), actions::dismissExternalProfile, Modifier.weight(1f))
                                LiteButton(stringResource(R.string.lite_import), actions::acceptExternalProfile, Modifier.weight(1f), selected = true, enabled = state.busy.isEmpty())
                            }
                        }
                    }
                    state.message?.let { message ->
                        LiteModal(stringResource(R.string.lite_message), actions::dismissMessage) {
                            SelectionContainer { LiteText(message, Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) }
                            LiteButton(stringResource(R.string.lite_dismiss), actions::dismissMessage, Modifier.fillMaxWidth())
                        }
                    }
                    if (state.updatePromptVisible) UpdateHost(features().updates)
                    GlobalNetworkRestartNotice(state.networkRestartRequired, actions::connect, covered = modal || !resumed)
                    LocalClientNotices.current?.let { ClientNoticesHost(it, active = resumed, covered = modal || scanMounted) }
                }
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
