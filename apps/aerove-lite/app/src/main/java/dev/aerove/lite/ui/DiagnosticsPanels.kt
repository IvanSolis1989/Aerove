package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.graphics.PathParser
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.DiagnosticsState
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.ServiceCheckView
import dev.aerove.lite.data.ServiceTarget
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.ui.colorutilities.TonalPalette
import kotlinx.coroutines.delay

internal val LocalDiagnosticsForeground = staticCompositionLocalOf { true }

@Composable
internal fun ServiceStatusPanel(state: LiteState, actions: LiteActions, showServices: Boolean = true,
    titleResource: Int = R.string.diag_service_status, footer: @Composable () -> Unit = {}) {
    val current by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val detail = rememberDiagnosticsDetail()
    var visible by remember { mutableStateOf(false) }
    val foreground = LocalDiagnosticsForeground.current
    val overlay = LocalLiteOverlay.current
    val view = LocalView.current
    val watched = if (showServices && foreground && (detail.opened || (visible && overlay.content == null)))
        state.diagnostics.services.let { if (detail.opened) it else it.take(4) }.map { it.id }.toSet() else emptySet()
    ServiceTelemetryDemand(actions, watched)
    val title = stringResource(titleResource)
    DiagnosticsCard(Modifier.testTag("diagnostics-services").onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }.clickable(enabled = showServices, role = Role.Button) {
        detail.open { close ->
            ServiceDetail(current, currentActions, close)
        }
    }) {
        DiagnosticsHeader(FullGlyphs.verified, title, showDisclosure = showServices, trailing = {
            if (showServices) Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceVerdict(state.diagnostics.services.take(4))
                ServiceRefresh(state, actions)
            }
        })
        if (showServices) {
        Spacer(Modifier.height(12.dp))
        val shown = state.diagnostics.services.take(4)
        if (shown.isEmpty()) {
            DiagnosticsEmpty(
                state.diagnostics.serviceLoading,
                stringResource(if (state.phase == VpnPhase.Running) R.string.diag_service_pending else R.string.diag_service_connect),
                Modifier.testTag("diagnostics-services-empty"),
            )
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { service -> ServiceCell(service, Modifier.weight(1f)) }
                }
            }
        }
        if (state.diagnostics.serviceLoading && shown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            DiagnosticsLoading()
        }
        DiagnosticsError(state.diagnostics.serviceError)
        }
        footer()
    }
}

@Composable
internal fun MemoryInfoPanel(state: LiteState, actions: LiteActions) {
    val current by rememberUpdatedState(state)
    val detail = rememberDiagnosticsDetail()
    var visible by remember { mutableStateOf(false) }
    val view = LocalView.current
    val foreground = LocalDiagnosticsForeground.current
    val overlay = LocalLiteOverlay.current
    MemoryTelemetryDemand(actions, foreground && (detail.opened || (visible && overlay.content == null)))
    val title = stringResource(R.string.diag_memory)
    DiagnosticsCard(Modifier.testTag("diagnostics-memory").onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }.clickable(role = Role.Button) {
        detail.open { close ->
            DiagnosticsSheet(title, close) { MemoryDetails(current.diagnostics) }
        }
    }) {
        DiagnosticsHeader(DiagnosticsGlyphs.memory, title)
        Spacer(Modifier.height(12.dp))
        MemorySummary(state.diagnostics)
    }
}

@Composable
private fun ServiceTelemetryDemand(actions: LiteActions, targets: Set<String>) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, actions, targets) {
        var declared = emptySet<String>()
        fun update() {
            val wanted = if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) targets else emptySet()
            if (declared != wanted) { declared = wanted; actions.setServiceTelemetryTargets(wanted) }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); if (declared.isNotEmpty()) actions.setServiceTelemetryTargets(emptySet()) }
    }
}

@Composable
private fun MemoryTelemetryDemand(actions: LiteActions, visible: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, actions, visible) {
        var requested = false
        fun update() {
            val active = visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (active != requested) {
                requested = active
                actions.setMemoryTelemetryActive(active)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            if (requested) actions.setMemoryTelemetryActive(false)
        }
    }
}

@Composable
private fun DiagnosticsCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val visuals = LocalLiteVisuals.current
    LitePane(modifier.fillMaxWidth(), corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
    }
}

@Composable
private fun DiagnosticsHeader(icon: LiteGlyph, title: String, showDisclosure: Boolean = true, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiteIcon(icon, color = LocalLiteVisuals.current.secondaryInk, size = 18.dp)
        LiteText(title, Modifier.weight(1f), style = LiteType.Label, maxLines = 1)
        trailing()
        if (showDisclosure) LiteIcon(FullGlyphs.chevronForward, Modifier.testTag("diagnostics-disclosure"), color = LocalLiteVisuals.current.secondaryInk, size = 18.dp)
    }
}

private data class DiagnosticTone(val ink: Color, val fill: Color, val onFill: Color)

@Composable
private fun serviceTone(status: String): DiagnosticTone {
    val dark = LocalLiteVisuals.current.dark
    val colors = MaterialTheme.colorScheme
    return remember(status, dark, colors) {
        when (status) {
            "available" -> diagnosticPalette(0xFF4CAF50.toInt(), dark)
            "restricted", "disallowed-isp", "blocked", "unsupported-region", "originals-only", "coming-soon" ->
                diagnosticPalette(0xFFFF9800.toInt(), dark)
            "unavailable", "timeout", "failed" -> DiagnosticTone(colors.error, colors.errorContainer, colors.onErrorContainer)
            else -> DiagnosticTone(colors.onSurfaceVariant, colors.fullBlock(dark), colors.onSurfaceVariant)
        }
    }
}

private fun diagnosticPalette(argb: Int, dark: Boolean): DiagnosticTone {
    val palette = TonalPalette.fromInt(argb)
    return DiagnosticTone(Color(palette.tone(if (dark) 80 else 40)),
        Color(palette.tone(if (dark) 30 else 90)), Color(palette.tone(if (dark) 90 else 30)))
}

@Composable
private fun serviceLabel(status: String): String = when (status) {
    "pending" -> stringResource(R.string.diag_service_pending)
    "available" -> stringResource(R.string.diag_service_available)
    "restricted" -> stringResource(R.string.diag_service_restricted)
    "unavailable" -> stringResource(R.string.diag_service_unavailable)
    "disallowed-isp" -> stringResource(R.string.diag_service_disallowed_isp)
    "blocked" -> stringResource(R.string.diag_service_blocked)
    "unsupported-region" -> stringResource(R.string.diag_service_unsupported_region)
    "originals-only" -> stringResource(R.string.diag_service_originals_only)
    "coming-soon" -> stringResource(R.string.diag_service_coming_soon)
    "timeout" -> stringResource(R.string.diag_service_timeout)
    "failed" -> stringResource(R.string.diag_service_failed)
    else -> status.ifBlank { stringResource(R.string.diag_service_pending) }
}

@Composable
private fun ServiceVerdict(services: List<ServiceCheckView>) {
    if (services.isEmpty()) return
    if (services.any { it.status == "pending" }) return
    val issues = services.count { it.status != "available" }
    val label = when (issues) {
        0 -> stringResource(R.string.diag_service_all_available)
        1 -> stringResource(R.string.diag_service_one_issue)
        else -> stringResource(R.string.diag_service_issues, issues)
    }
    ServicePill(label, serviceTone(if (issues == 0) "available" else "restricted"))
}

@Composable
private fun ServicePill(label: String, tone: DiagnosticTone) {
    LitePane(corner = LiteCorner.Full, solidColor = tone.fill, glassTint = tone.ink.copy(alpha = 0.12f)) {
        Box(Modifier.height(24.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            LiteText(label, style = LiteType.Caption.copy(fontWeight = FontWeight.SemiBold), color = tone.onFill, maxLines = 1)
        }
    }
}

@Composable
private fun ServiceCell(service: ServiceCheckView, modifier: Modifier) {
    val status = serviceLabel(service.status)
    val label = if (service.status == "available" && service.delayMillis != null) stringResource(R.string.diag_service_delay, service.delayMillis) else status
    val metadata = servicePresentation(service.id)
    val tone = serviceTone(service.status)
    LitePane(modifier.heightIn(min = 50.dp).testTag("dashboard-service-${service.id}").semantics(mergeDescendants = true) {
        stateDescription = status
        liveRegion = LiveRegionMode.Polite
    }, corner = LiteCorner.Control, solidColor = MaterialTheme.colorScheme.fullBlock(LocalLiteVisuals.current.dark)) {
        Row(Modifier.fillMaxSize().padding(start = 7.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceBadge(metadata, tone.ink, 36.dp, 9.dp)
            Column(Modifier.weight(1f)) {
                LiteText(metadata.label, style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                LiteText(label, style = LiteType.Caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = if (service.status == "available") LocalLiteVisuals.current.secondaryInk else tone.ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ServiceBadge(service: ServicePresentation, dot: Color, size: Dp, corner: Dp) {
    Box(Modifier.size(size)) {
        LitePane(Modifier.matchParentSize().drawWithCache {
            val radius = maxOf(this.size.width / 10, 4.dp.toPx())
            val ring = maxOf(this.size.width / 16, 2.dp.toPx())
            val center = Offset(this.size.width - radius, this.size.height - radius)
            val hole = Path().apply { addOval(Rect(center.x - radius - ring, center.y - radius - ring,
                center.x + radius + ring, center.y + radius + ring)) }
            onDrawWithContent { clipPath(hole, ClipOp.Difference) { this@onDrawWithContent.drawContent() } }
        }, corner = corner) {
            LiteIcon(service.icon, Modifier.align(Alignment.Center), LocalLiteVisuals.current.secondaryInk, fill = 1f, size = size * 0.56f)
        }
        Canvas(Modifier.matchParentSize()) {
            val radius = maxOf(this.size.width / 10, 4.dp.toPx())
            drawCircle(dot, radius, Offset(this.size.width - radius, this.size.height - radius))
        }
    }
}

@Composable
private fun ServiceRefresh(state: LiteState, actions: LiteActions) {
    if (state.diagnostics.serviceLoading) DiagnosticsLoading()
    else LiteIconButton(stringResource(R.string.diag_service_check_all), FullGlyphs.refresh, actions::refreshServiceChecks,
        Modifier.testTag("diagnostics-service-refresh"), enabled = state.phase == VpnPhase.Running)
}

@Composable
private fun ServiceResults(state: LiteState, actions: LiteActions) {
    val diagnostics = state.diagnostics
    DiagnosticsError(diagnostics.serviceError)
    if (diagnostics.services.isEmpty()) {
        DiagnosticsEmpty(diagnostics.serviceLoading, stringResource(R.string.diag_service_pending))
        return
    }
    LitePane(Modifier.fillMaxWidth()) {
        Column {
            diagnostics.services.forEach { service ->
                val metadata = servicePresentation(service.id)
                val tone = serviceTone(service.status)
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ServiceBadge(metadata, tone.ink, 40.dp, 12.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiteText(metadata.label, Modifier.weight(1f), maxLines = 1)
                            ServicePill(serviceLabel(service.status), tone)
                        }
                        val route = listOfNotNull(service.region?.takeIf { it.isNotBlank() },
                            service.chains.firstOrNull()).joinToString(" · ")
                        LiteText(route.ifEmpty { "—" }, style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
                        service.detail?.takeIf { it.isNotBlank() }?.let { LiteText(it, style = LiteType.Footnote, color = tone.ink) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            service.delayMillis?.let { LiteText(stringResource(R.string.diag_service_delay, it),
                                style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")) }
                            if (service.checkedAt > 0) LiteText(stringResource(R.string.diag_service_checked_at, formatTime(service.checkedAt)),
                                style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                        }
                    }
                    if (service.id in diagnostics.refreshingServices) DiagnosticsLoading()
                    else LiteIconButton(stringResource(R.string.diag_service_check, metadata.label), FullGlyphs.refresh,
                        { actions.refreshServiceCheck(service.id) }, enabled = state.phase == VpnPhase.Running)
                }
            }
        }
    }
}

@Composable
private fun ServiceDetail(state: LiteState, actions: LiteActions, close: () -> Unit) {
    var managing by remember { mutableStateOf(false) }
    key(managing) {
    DiagnosticsSheet(stringResource(if (managing) R.string.diag_service_manage else R.string.diag_service_status),
        if (managing) ({ managing = false }) else close, toolbar = {
            if (!managing) {
                ServiceRefresh(state, actions)
                LiteIconButton(stringResource(R.string.diag_service_manage), FullGlyphs.settings, { managing = true })
            }
        }) {
        if (managing) {
            BackHandler { managing = false }
            ServiceManagement(state, actions)
        } else ServiceResults(state, actions)
    }
    }
}

@Composable
private fun ServiceManagement(state: LiteState, actions: LiteActions) {
    val current by rememberUpdatedState(state.settings)
    val known = ServiceTarget.entries.map { it.id }
    val order = (state.settings.serviceOrder + known).distinct().filter { it in known }
    val enabled = order.filter { it !in state.settings.disabledServices }
    val shown = enabled.take(4).toSet()
    val density = LocalDensity.current
    val step = with(density) { 64.dp.toPx() }
    fun move(id: String, delta: Int) {
        val latest = (current.serviceOrder + known).distinct().filter { it in known }.toMutableList()
        val from = latest.indexOf(id)
        val to = (from + delta).coerceIn(0, latest.lastIndex)
        if (from >= 0 && from != to) {
            latest.add(to, latest.removeAt(from))
            actions.changeSettings(current.copy(serviceOrder = latest))
        }
    }
    order.forEachIndexed { index, id ->
        val metadata = servicePresentation(id)
        val active = id in enabled
        val locked = active && enabled.size == 1
        val up = stringResource(R.string.diag_service_move_up)
        val down = stringResource(R.string.diag_service_move_down)
        var distance by remember(id) { mutableStateOf(0f) }
        LitePane(Modifier.fillMaxWidth().testTag("service-manage-$id").semantics {
            customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction(up) { move(id, -1); true })
                if (index < order.lastIndex) add(CustomAccessibilityAction(down) { move(id, 1); true })
            }
        }.pointerInput(id, step) {
            detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
                onDragEnd = { distance = 0f }, onDrag = { change, amount ->
                    change.consume(); distance += amount.y
                    if (distance >= step) { move(id, 1); distance -= step }
                    else if (distance <= -step) { move(id, -1); distance += step }
                })
        }) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceBadge(metadata, LocalLiteVisuals.current.secondaryInk, 40.dp, 12.dp)
                Column(Modifier.weight(1f)) {
                    LiteText(metadata.label)
                    if (id in shown) LiteText(stringResource(R.string.diag_service_shown), style = LiteType.Footnote,
                        color = LocalLiteVisuals.current.secondaryInk)
                }
                LiteSwitch(active, metadata.label, { value ->
                    val disabled = current.disabledServices.toMutableSet()
                    if (value) disabled.remove(id) else disabled.add(id)
                    actions.changeSettings(current.copy(disabledServices = disabled))
                }, enabled = !locked)
            }
        }
    }
}

@Composable
private fun MemorySummary(diagnostics: DiagnosticsState) {
    val bytes = diagnostics.memoryInUseBytes?.takeIf { it >= 0 }
    val figure = bytes?.let(::formatBytes) ?: "—"
    val split = figure.lastIndexOf(' ')
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LiteText(if (split > 0) figure.substring(0, split) else figure, Modifier.testTag("diagnostics-memory-value"),
            style = LiteType.Title1.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        if (split > 0) LiteText(figure.substring(split + 1), Modifier.padding(bottom = 4.dp),
            style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
        if (diagnostics.memoryLoading) DiagnosticsLoading()
    }
    Spacer(Modifier.height(14.dp))
    diagnostics.memoryScope?.takeIf { it.isNotBlank() }?.let {
        val label = if (it == "process-rss") stringResource(R.string.diag_memory_process_rss) else it
        LiteText(label, Modifier.testTag("diagnostics-memory-scope"), style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
    }
    if (bytes == null && !diagnostics.memoryLoading && diagnostics.memoryError.isNullOrBlank()) {
        LiteText(stringResource(R.string.diag_memory_no_sample), style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
    }
    DiagnosticsError(diagnostics.memoryError)
}

@Composable
private fun MemoryDetails(diagnostics: DiagnosticsState) {
    MemorySummary(diagnostics)
    val used = diagnostics.memoryInUseBytes?.takeIf { it >= 0 }
    val limit = diagnostics.memoryLimitBytes?.takeIf { it > 0 }
    if (used != null || limit != null) LitePane(Modifier.fillMaxWidth()) {
        Column {
            used?.let { DiagnosticMetric(stringResource(R.string.diag_memory_in_use), formatBytes(it)) }
            limit?.let { DiagnosticMetric(stringResource(R.string.diag_memory_limit), formatBytes(it)) }
        }
    }
}

@Composable
private fun DiagnosticMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LiteText(label, Modifier.weight(1f))
        LiteText(value, style = LiteType.Callout.copy(fontFeatureSettings = "tnum"), color = LocalLiteVisuals.current.secondaryInk)
    }
}

@Composable
private fun DiagnosticsEmpty(loading: Boolean, label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        if (loading) DiagnosticsLoading()
        else LiteText(label, style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
    }
}

@Composable
private fun DiagnosticsLoading() {
    val label = stringResource(R.string.diag_loading)
    CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = label }, strokeWidth = 2.dp,
        color = LocalLiteVisuals.current.secondaryInk)
}

@Composable
private fun DiagnosticsError(error: String?) {
    error?.takeIf { it.isNotBlank() }?.let {
        LiteText(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = LiteType.Footnote, color = MaterialTheme.colorScheme.error)
    }
}

private class DiagnosticsDetail(private val overlay: LiteOverlayState) {
    private var owned: (@Composable () -> Unit)? = null
    val opened: Boolean get() = owned != null && overlay.content === owned
    fun open(content: @Composable (close: () -> Unit) -> Unit) {
        owned = { content(::close) }
        overlay.content = owned
    }
    fun close() {
        if (overlay.content === owned) overlay.dismiss()
        owned = null
    }
}

@Composable
private fun rememberDiagnosticsDetail(): DiagnosticsDetail {
    val overlay = LocalLiteOverlay.current
    val detail = remember(overlay) { DiagnosticsDetail(overlay) }
    DisposableEffect(detail) { onDispose(detail::close) }
    return detail
}

@Composable
private fun DiagnosticsSheet(title: String, close: () -> Unit, toolbar: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val reduced = LocalLiteReducedMotion.current
    val progress by animateFloatAsState(if (entered && !closing) 1f else 0f,
        tween(if (reduced) 0 else 260), label = "diagnosticsSheet")
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(closing) { if (closing) { if (!reduced) delay(260); close() } }
    BackHandler { closing = true }
    val scrim = rememberLayerBackdrop()
    val source = rememberCombinedBackdrop(LocalLiteBackdrop.current, scrim)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(scrim).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * progress))
            .clickable(enabled = !closing) { closing = true })
        CompositionLocalProvider(LocalLiteBackdrop provides source) {
            LitePane(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * 0.9f)
                .graphicsLayer { translationY = (1 - progress) * size.height }
                .pointerInput(Unit) { detectTapGestures {} }.semantics { paneTitle = title }, corner = LiteCorner.Sheet) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(LiteMetrics.PageToolbar).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiteIconButton(stringResource(R.string.diag_close), FullGlyphs.close, { closing = true })
                        LiteText(title, Modifier.weight(1f), style = LiteType.Title)
                        toolbar()
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        }
    }
}

private object DiagnosticsGlyphs {
    val memory = LiteGlyph(listOf(
        glyphBox(3.5, 7.2, 20.5, 16.8, 2.5),
        glyphPath("M8 7.2V3.8M12 7.2V3.8M16 7.2V3.8M8 16.8V20.2M12 16.8V20.2M16 16.8V20.2", role = LiteGlyphRole.Line),
    ))
}

private data class ServicePresentation(val label: String, val icon: LiteGlyph)

private fun servicePresentation(id: String): ServicePresentation = ServicePresentations.entries[id] ?: ServicePresentation(id, FullGlyphs.verified)

private fun serviceStrokePath(data: String): LiteGlyphPart {
    val outline = android.graphics.Path()
    val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    paint.getFillPath(requireNotNull(PathParser.createPathFromPathData(data)), outline)
    return LiteGlyphPart(outline.asComposePath(), LiteGlyphRole.Body, solid = true)
}

// Paths retain the Standard edition service assets; metadata never supplies check results.
internal const val DiscordServicePath = "M20.317 4.3698a19.7913 19.7913 0 0 0 -4.8851-1.5152.0741.0741 0 0 0 -.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 0 0 -.0785-.037 19.7363 19.7363 0 0 0 -4.8852 1.515.0699.0699 0 0 0 -.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 0 0 .0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 0 0 .0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 0 0 -.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 0 1 -.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 0 1 .0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 0 1 .0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 0 1 -.0066.1276 12.2986 12.2986 0 0 1 -1.873.8914.0766.0766 0 0 0 -.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 0 0 .0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 0 0 .0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 0 0 -.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"

private object ServicePresentations {
    val entries = mapOf(
        "google" to ServicePresentation("Google", LiteGlyph(listOf(
            glyphPath("M12.48 10.92v3.28h7.84c-.24 1.84-.853 3.187-1.787 4.133-1.147 1.147-2.933 2.4-6.053 2.4-4.827 0-8.6-3.893-8.6-8.72s3.773-8.72 8.6-8.72c2.6 0 4.507 1.027 5.907 2.347l2.307-2.307C18.747 1.44 16.133 0 12.48 0 5.867 0 .307 5.387.307 12s5.56 12 12.173 12c3.573 0 6.267-1.173 8.373-3.36 2.16-2.16 2.84-5.213 2.84-7.667 0-.76-.053-1.467-.173-2.053H12.48z", solid = true),
        ))),
        "github" to ServicePresentation("GitHub", LiteGlyph(listOf(
            glyphPath("M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12", solid = true),
        ))),
        "youtube" to ServicePresentation("YouTube", LiteGlyph(listOf(
            glyphPath("M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z", solid = true),
        ))),
        "chatgpt" to ServicePresentation("ChatGPT", LiteGlyph(listOf(
            glyphPath("M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z", solid = true),
        ))),
        "claude" to ServicePresentation("Claude", LiteGlyph(listOf(
            serviceStrokePath("m12 2-.2 6.8M17 3.4l-3 6.2M21 7l-6.3 3.4M22 12l-7 .2M20.6 17l-6.2-3M17 21l-3.4-6.3M12 22l-.2-7M7 20.6l3-6.2M3 17l6.3-3.4M2 12l7-.2M3.4 7l6.2 3M7 3l3.4 6.3"),
            glyphPath("m12 8 3 2 1 3-3 3-3-1-2-3 1-3z", solid = true),
        ))),
        "gemini" to ServicePresentation("Gemini", LiteGlyph(listOf(
            glyphPath("M11.04 19.32Q12 21.51 12 24q0-2.49.93-4.68.96-2.19 2.58-3.81t3.81-2.55Q21.51 12 24 12q-2.49 0-4.68-.93a12.3 12.3 0 0 1-3.81-2.58 12.3 12.3 0 0 1-2.58-3.81Q12 2.49 12 0q0 2.49-.96 4.68-.93 2.19-2.55 3.81a12.3 12.3 0 0 1-3.81 2.58Q2.49 12 0 12q2.49 0 4.68.96 2.19.93 3.81 2.55t2.55 3.81", solid = true),
        ))),
        "netflix" to ServicePresentation("Netflix", LiteGlyph(listOf(
            glyphPath("M5.398 0v.006c3.028 8.556 5.37 15.175 8.348 23.596 2.344.058 4.85.398 4.854.398-2.8-7.924-5.923-16.747-8.487-24zm8.489 0v9.63L18.6 22.951c-.043-7.86-.004-15.913.002-22.95zM5.398 1.05V24c1.873-.225 2.81-.312 4.715-.398v-9.22z", solid = true),
        ))),
        "disney-plus" to ServicePresentation("Disney+", LiteGlyph(listOf(
            glyphPath("M3.6 4.2h10.8a2.4 2.4 0 0 1 2.4 2.4v10.8a2.4 2.4 0 0 1-2.4 2.4H3.6a2.4 2.4 0 0 1-2.4-2.4V6.6a2.4 2.4 0 0 1 2.4-2.4Zm3 4.2v7.2l6-3.6-6-3.6ZM19.2 8.4h1.8v2.7h2.7v1.8h-2.7v2.7h-1.8v-2.7h-2.7v-1.8h2.7V8.4Z", solid = true),
        ))),
        "prime-video" to ServicePresentation("Prime Video", LiteGlyph(listOf(
            glyphPath("M0 9.508c0-.043.01-.073.028-.09.018-.017.047-.025.086-.025h.329c.07 0 .112.034.127.101l.032.119c.091-.088.202-.159.33-.21a1.04 1.04 0 0 1 .396-.079c.294 0 .528.109.7.326.171.217.257.51.257.88 0 .254-.042.475-.127.665-.086.19-.201.335-.347.437a.85.85 0 0 1-.502.154c-.125 0-.243-.02-.355-.06a.857.857 0 0 1-.288-.164v1.003c0 .043-.008.073-.025.09-.017.016-.046.025-.09.025H.115c-.04 0-.068-.009-.086-.025-.019-.017-.028-.047-.028-.09zm1.113.32a.868.868 0 0 0-.447.124v1.206a.834.834 0 0 0 .447.124c.17 0 .296-.058.376-.174.081-.117.121-.3.121-.55 0-.254-.04-.439-.118-.555-.08-.116-.206-.174-.379-.174zm2.248-.087c.121-.134.236-.23.344-.286a.733.733 0 0 1 .345-.085h.063c.043 0 .073.009.092.025.018.017.027.047.027.09v.385c0 .04-.008.068-.025.087-.017.018-.046.027-.089.027a.923.923 0 0 1-.082-.004 1.369 1.369 0 0 0-.383.025c-.1.02-.186.045-.256.076v1.54c0 .04-.008.069-.025.087-.016.018-.046.028-.089.028h-.437c-.04 0-.069-.01-.087-.028-.018-.018-.028-.047-.028-.087V9.508c0-.043.01-.073.028-.09.018-.017.047-.025.087-.025h.328c.07 0 .112.034.128.1zm1.526-.71a.396.396 0 0 1-.278-.096.338.338 0 0 1-.105-.262c0-.11.035-.197.105-.26a.395.395 0 0 1 .278-.097c.116 0 .208.032.278.096.07.064.105.151.105.261a.34.34 0 0 1-.105.262.396.396 0 0 1-.278.096zm-.333.477c0-.043.01-.073.027-.09.019-.017.048-.025.087-.025h.438c.043 0 .072.008.089.025s.025.047.025.09v2.113c0 .04-.008.069-.025.087-.017.018-.046.028-.09.028h-.437c-.04 0-.068-.01-.087-.028-.018-.018-.027-.047-.027-.087zm1.837.11c.161-.107.306-.183.435-.227.13-.045.263-.067.4-.067.273 0 .466.098.579.294.155-.104.3-.18.438-.225.137-.046.278-.069.424-.069.213 0 .377.06.495.179.117.12.175.286.175.5v1.618c0 .04-.008.069-.025.087-.017.019-.046.027-.089.027h-.438c-.04 0-.068-.008-.086-.027-.018-.018-.028-.047-.028-.087V10.15c0-.208-.092-.312-.278-.312-.164 0-.33.04-.497.119v1.664c0 .04-.008.069-.025.087-.017.019-.046.027-.09.027h-.437c-.04 0-.068-.008-.086-.027-.019-.018-.028-.047-.028-.087V10.15c0-.208-.093-.312-.278-.312-.17 0-.337.04-.502.123v1.66c0 .04-.008.069-.025.087-.017.019-.046.027-.089.027h-.438c-.039 0-.068-.008-.086-.027-.018-.018-.027-.047-.027-.087V9.508c0-.043.009-.073.027-.09.018-.017.047-.025.086-.025h.329c.07 0 .112.034.128.101zm4.387 1.16a1.81 1.81 0 0 1-.451-.05c.018.204.08.35.185.44.105.088.263.132.476.132.085 0 .168-.005.249-.016a3.08 3.08 0 0 0 .362-.078.143.143 0 0 1 .023-.002c.052 0 .078.035.078.105v.211c0 .049-.007.083-.02.103a.169.169 0 0 1-.08.053 1.953 1.953 0 0 1-.708.128c-.377 0-.666-.103-.868-.312-.203-.207-.304-.505-.304-.893 0-.398.104-.71.31-.935.207-.227.494-.34.862-.34.283 0 .504.069.664.206a.69.69 0 0 1 .24.55c0 .23-.087.403-.258.52-.172.119-.425.177-.76.177zm.064-.99c-.292 0-.46.18-.506.54.122.025.257.037.406.037.155 0 .267-.024.337-.071.07-.047.105-.12.105-.218 0-.193-.114-.289-.342-.289zm2.948 1.946a.21.21 0 0 1-.075-.011.119.119 0 0 1-.05-.037.274.274 0 0 1-.038-.071l-.777-2.04a1.863 1.863 0 0 1-.023-.063.162.162 0 0 1-.009-.05c0-.047.03-.07.091-.07h.454c.049 0 .084.01.107.028.023.018.04.049.052.092l.468 1.622.477-1.622a.175.175 0 0 1 .052-.092c.023-.018.058-.027.107-.027h.44c.061 0 .091.022.091.068a.16.16 0 0 1-.009.05l-.022.065-.777 2.039a.274.274 0 0 1-.039.07.122.122 0 0 1-.047.038.207.207 0 0 1-.078.01zm2.02-2.703a.393.393 0 0 1-.277-.097.338.338 0 0 1-.105-.26c0-.11.035-.198.105-.262a.393.393 0 0 1 .277-.096c.115 0 .207.032.277.096.07.064.104.151.104.261 0 .11-.034.197-.104.261a.393.393 0 0 1-.277.097zm-.218 2.703c-.04 0-.068-.01-.086-.028-.019-.018-.028-.047-.028-.087V9.507c0-.043.01-.072.028-.09.018-.016.047-.024.086-.024h.436c.042 0 .072.008.089.025.016.017.024.046.024.09v2.111c0 .04-.008.07-.024.087-.017.019-.047.028-.09.028zm1.948.05a.869.869 0 0 1-.513-.153.97.97 0 0 1-.334-.426 1.6 1.6 0 0 1-.116-.63c0-.38.09-.682.268-.91a.856.856 0 0 1 .709-.341.98.98 0 0 1 .622.206V8.458c0-.043.01-.073.027-.09.018-.016.047-.025.087-.025h.436c.042 0 .071.009.088.025.017.017.025.047.025.09v3.161c0 .04-.008.07-.025.087-.017.019-.046.028-.088.028h-.364a.135.135 0 0 1-.084-.023.137.137 0 0 1-.043-.078l-.027-.105a.958.958 0 0 1-.668.256zm.218-.504a.762.762 0 0 0 .418-.128v-1.21a.872.872 0 0 0-.45-.114c-.16 0-.28.06-.358.18-.08.121-.118.304-.118.548 0 .245.041.426.124.546.084.119.212.178.384.178zm2.588-.51c-.169 0-.315-.016-.44-.05.018.201.078.345.18.432.103.087.257.13.465.13.083 0 .164-.005.242-.016a2.997 2.997 0 0 0 .354-.076.135.135 0 0 1 .022-.002c.05 0 .075.035.075.103v.207c0 .048-.007.082-.02.101a.165.165 0 0 1-.077.052 1.895 1.895 0 0 1-.69.126c-.367 0-.65-.102-.846-.306-.197-.204-.296-.496-.296-.876 0-.39.1-.695.302-.917.202-.222.482-.333.84-.333.276 0 .492.068.647.203a.678.678 0 0 1 .234.539c0 .225-.084.395-.251.51-.168.115-.415.173-.74.173zm.063-.97c-.285 0-.45.176-.494.53.119.024.25.036.396.036.15 0 .26-.024.329-.07.068-.046.102-.117.102-.213 0-.19-.111-.284-.333-.284zm2.442 2.003c-.36 0-.642-.11-.845-.328-.203-.218-.304-.523-.304-.914 0-.388.101-.691.304-.91.203-.218.485-.327.845-.327s.642.109.845.327c.203.219.304.522.304.91 0 .39-.101.696-.304.914-.203.218-.485.328-.845.328zm0-.514c.318 0 .477-.242.477-.728 0-.483-.16-.724-.477-.724-.318 0-.477.241-.477.724 0 .486.16.728.477.728zm-6.844 1.886c.405-.306.944-.408 1.39-.408.418 0 .756.09.828.185.15.2-.039 1.584-.775 2.244-.112.102-.22.047-.17-.087.166-.442.536-1.436.36-1.677-.175-.242-1.158-.115-1.6-.058-.068.008-.107-.02-.112-.061v-.023c.004-.036.03-.078.079-.115zm-10.184-.172a.105.105 0 0 1 .106-.091c.027 0 .057.009.089.028a11.778 11.778 0 0 0 6.194 1.772c1.52 0 3.19-.34 4.726-1.043.232-.105.426.164.2.346-1.371 1.09-3.359 1.67-5.07 1.67-2.397 0-4.557-.956-6.191-2.547a.173.173 0 0 1-.054-.097Z", solid = true),
        ))),
        "spotify" to ServicePresentation("Spotify", LiteGlyph(listOf(
            glyphPath("M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z", solid = true),
        ))),
        "tiktok" to ServicePresentation("TikTok", LiteGlyph(listOf(
            glyphPath("M12.525.02c1.31-.02 2.61-.01 3.91-.02.08 1.53.63 3.09 1.75 4.17 1.12 1.11 2.7 1.62 4.24 1.79v4.03c-1.44-.05-2.89-.35-4.2-.97-.57-.26-1.1-.59-1.62-.93-.01 2.92.01 5.84-.02 8.75-.08 1.4-.54 2.79-1.35 3.94-1.31 1.92-3.58 3.17-5.91 3.21-1.43.08-2.86-.31-4.08-1.03-2.02-1.19-3.44-3.37-3.65-5.71-.02-.5-.03-1-.01-1.49.18-1.9 1.12-3.72 2.58-4.96 1.66-1.44 3.98-2.13 6.15-1.72.02 1.48-.04 2.96-.04 4.44-.99-.32-2.15-.23-3.02.37-.63.41-1.11 1.04-1.36 1.75-.21.51-.15 1.07-.14 1.61.24 1.64 1.82 3.02 3.5 2.87 1.12-.01 2.19-.66 2.77-1.61.19-.33.4-.67.41-1.06.1-1.79.06-3.57.07-5.36.01-4.03-.01-8.05.02-12.07z", solid = true),
        ))),
        "discord" to ServicePresentation("Discord", LiteGlyph(listOf(
            glyphPath(DiscordServicePath, solid = true),
        ))),
        "bilibili" to ServicePresentation("bilibili", LiteGlyph(listOf(
            glyphPath("M17.813 4.653h.854c1.51.054 2.769.578 3.773 1.574 1.004.995 1.524 2.249 1.56 3.76v7.36c-.036 1.51-.556 2.769-1.56 3.773s-2.262 1.524-3.773 1.56H5.333c-1.51-.036-2.769-.556-3.773-1.56S.036 18.858 0 17.347v-7.36c.036-1.511.556-2.765 1.56-3.76 1.004-.996 2.262-1.52 3.773-1.574h.774l-1.174-1.12a1.234 1.234 0 0 1-.373-.906c0-.356.124-.658.373-.907l.027-.027c.267-.249.573-.373.92-.373.347 0 .653.124.92.373L9.653 4.44c.071.071.134.142.187.213h4.267a.836.836 0 0 1 .16-.213l2.853-2.747c.267-.249.573-.373.92-.373.347 0 .662.151.929.4.267.249.391.551.391.907 0 .355-.124.657-.373.906zM5.333 7.24c-.746.018-1.373.276-1.88.773-.506.498-.769 1.13-.786 1.894v7.52c.017.764.28 1.395.786 1.893.507.498 1.134.756 1.88.773h13.334c.746-.017 1.373-.275 1.88-.773.506-.498.769-1.129.786-1.893v-7.52c-.017-.765-.28-1.396-.786-1.894-.507-.497-1.134-.755-1.88-.773zM8 11.107c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c0-.373.129-.689.386-.947.258-.257.574-.386.947-.386zm8 0c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c.017-.391.15-.711.4-.96.249-.249.56-.373.933-.373Z", solid = true),
        ))),
    )
}
