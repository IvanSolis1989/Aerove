package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R

internal enum class MainTab(@StringRes val label: Int, val icon: ImageVector) {
    Home(R.string.lite_tab_home, Icons.Outlined.Dashboard),
    Proxies(R.string.lite_tab_proxies, Icons.Outlined.Hub),
    Profiles(R.string.lite_tab_profiles, Icons.Outlined.FolderOpen),
    Connections(R.string.lite_tab_connections, Icons.Outlined.SwapVert),
    Settings(R.string.lite_tab_settings, Icons.Outlined.Settings),
}

internal enum class DetailPage(@StringRes val label: Int) {
    Dns(R.string.lite_dns), Apps(R.string.lite_app_filter), Logs(R.string.lite_logs), About(R.string.lite_about),
}

@Composable
fun LiteApp(state: LiteState, actions: LiteActions) {
    LiteTheme(state.settings) {
        val wallpaper = rememberLayerBackdrop()
        val page = rememberLayerBackdrop()
        val chromeSource = rememberCombinedBackdrop(wallpaper, page)
        val phase = rememberAuroraPhase(state.settings.aurora)
        var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
        var detail by rememberSaveable { mutableStateOf<DetailPage?>(null) }
        var subscriptionEditor by rememberSaveable { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<ProfileSummary?>(null) }
        val pages = rememberSaveableStateHolder()
        val modal = subscriptionEditor || deleting != null || state.message != null
        BackHandler(detail != null && !modal) { detail = null }
        BackHandler(tab != MainTab.Home && detail == null && !modal) { tab = MainTab.Home }

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(wallpaper)) {
                LiteWallpaper(phase, Modifier.fillMaxSize())
            }
            CompositionLocalProvider(LocalLiteBackdrop provides wallpaper) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val contentModifier = Modifier.fillMaxSize().then(
                        if (modal) Modifier.clearAndSetSemantics {} else Modifier,
                    )
                    Box(contentModifier.layerBackdrop(page)) {
                        val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 100.dp, bottom = 110.dp)
                        val openDetail: (DetailPage) -> Unit = { detail = it }
                        if (detail != null) {
                            when (detail) {
                                DetailPage.Dns -> DnsScreen(state, actions, padding)
                                DetailPage.Apps -> AppFilterScreen(state, actions, padding)
                                DetailPage.Logs -> LogsScreen(state, actions, padding)
                                DetailPage.About -> AboutScreen(padding)
                                null -> Unit
                            }
                        } else pages.SaveableStateProvider(tab.name) {
                            when (tab) {
                                MainTab.Home -> OverviewScreen(state, actions, padding, { tab = MainTab.Profiles }, { openDetail(DetailPage.Logs) })
                                MainTab.Proxies -> ProxiesScreen(state, actions, padding)
                                MainTab.Profiles -> ProfilesScreen(state, actions, padding, { subscriptionEditor = true }, { deleting = it })
                                MainTab.Connections -> ConnectionsScreen(state, actions, padding)
                                MainTab.Settings -> SettingsScreen(state, actions, padding, openDetail)
                            }
                        }
                    }
                    CompositionLocalProvider(LocalLiteBackdrop provides chromeSource) {
                        val title = stringResource(detail?.label ?: tab.label)
                        Row(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp)
                                .then(if (modal) Modifier.clearAndSetSemantics {} else Modifier),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (detail != null) LiteIconButton(stringResource(R.string.lite_back), Icons.Outlined.ArrowBack, { detail = null })
                            LitePane(Modifier.weight(1f)) {
                                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                                    LiteText(stringResource(R.string.app_name), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                                    LiteText(title, style = LiteType.Headline, maxLines = 1)
                                }
                            }
                            if (detail == null && tab == MainTab.Profiles) {
                                LiteIconButton(stringResource(R.string.lite_add_subscription), Icons.Outlined.Add, { subscriptionEditor = true })
                            } else if (detail == null && tab == MainTab.Home) {
                                LiteIconButton(stringResource(R.string.lite_logs), Icons.Outlined.ReceiptLong, { detail = DetailPage.Logs })
                            }
                        }
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp)
                                .then(if (modal) Modifier.clearAndSetSemantics {} else Modifier),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            MainTab.entries.forEach { item ->
                                LitePane(
                                    Modifier.weight(1f).selectable(
                                        selected = tab == item && detail == null,
                                        role = Role.Tab,
                                    ) { tab = item; detail = null },
                                    corner = LiteCorner.Control,
                                    selected = tab == item && detail == null,
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        val foreground = if (tab == item) LocalLiteVisuals.current.primary else LocalLiteVisuals.current.secondaryInk
                                        LiteIcon(item.icon, color = foreground)
                                        LiteText(stringResource(item.label), style = LiteType.Caption, color = foreground, maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (subscriptionEditor) SubscriptionEditor(state, actions) { subscriptionEditor = false }
                        deleting?.let { profile ->
                            LiteModal(stringResource(R.string.lite_delete_profile), { deleting = null }) {
                                LiteText(stringResource(R.string.lite_delete_profile_detail, profile.name))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LiteButton(stringResource(R.string.lite_cancel), { deleting = null }, Modifier.weight(1f))
                                    LiteButton(stringResource(R.string.lite_delete), { actions.deleteProfile(profile.id); deleting = null }, Modifier.weight(1f), destructive = true)
                                }
                            }
                        }
                        state.message?.let { message ->
                            LiteModal(stringResource(R.string.lite_message), actions::dismissMessage) {
                                SelectionContainer {
                                    LiteText(message, Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                                }
                                LiteButton(stringResource(R.string.lite_dismiss), actions::dismissMessage, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiteModal(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onDismiss))
    Box(Modifier.fillMaxSize().imePadding().padding(20.dp), contentAlignment = Alignment.Center) {
        LitePane(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)
                .pointerInput(Unit) { detectTapGestures {} },
            corner = LiteCorner.Sheet,
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()).semantics { paneTitle = title },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiteText(title, Modifier.weight(1f), style = LiteType.Headline)
                    LiteIconButton(stringResource(R.string.lite_close), Icons.Outlined.Close, onDismiss)
                }
                content()
            }
        }
    }
}
