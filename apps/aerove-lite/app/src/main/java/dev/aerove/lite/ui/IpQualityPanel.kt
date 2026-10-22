package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import dev.aerove.lite.R
import dev.aerove.lite.ipquality.IpQualityFeature
import dev.aerove.lite.ipquality.IpQualityLevel
import dev.aerove.lite.ipquality.IpQualitySourceStatus
import dev.aerove.lite.ipquality.IpQualityState
import dev.aerove.lite.ipquality.IpType

@Composable
internal fun IpQualityStatus(feature: IpQualityFeature, content: @Composable (IpQualityState, () -> Unit) -> Unit) {
    val state by feature.state.collectAsState()
    val overlay = LocalLiteOverlay.current
    val foreground = LocalDiagnosticsForeground.current
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    var owned by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val detailOpen = owned != null && overlay.content === owned
    val demand = foreground && (detailOpen || (visible && overlay.content == null))
    DisposableEffect(feature, demand) {
        val lease = if (demand) feature.observe() else null
        onDispose { lease?.close() }
    }
    DisposableEffect(feature, overlay) { onDispose { if (overlay.content === owned) overlay.dismiss() } }
    Column(Modifier.fillMaxWidth().onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }) {
        content(state) {
            val sheet: @Composable () -> Unit = { IpQualityPanel(feature) { overlay.dismiss(); owned = null } }
            owned = sheet
            overlay.content = sheet
        }
    }
}

@Composable
internal fun IpQualityChips(state: IpQualityState, open: () -> Unit) {
    FlowRow(Modifier.testTag("ip-quality-summary").clickable(role = Role.Button, onClick = open).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val quality = state.quality
        QualityChip(stringResource(R.string.quality_short))
        if (quality != null) {
            QualityChip(stringResource(typeLabel(quality.type)))
            QualityChip(stringResource(levelLabel(quality.level)), quality.level)
        } else LiteText(stringResource(if (state.loading) R.string.quality_loading else if (state.failed)
            R.string.quality_failed else R.string.quality_unknown), style = LiteType.Caption,
            color = if (state.failures.isNotEmpty()) MaterialTheme.colorScheme.error else LocalLiteVisuals.current.secondaryInk)
    }
}

@Composable
internal fun IpQualityPanel(feature: IpQualityFeature, close: () -> Unit) {
    val state by feature.state.collectAsState()
    val foreground = LocalDiagnosticsForeground.current
    DisposableEffect(feature, foreground) {
        val lease = if (foreground) feature.observe() else null
        onDispose { lease?.close() }
    }
    ProfileMaintenanceSheet(stringResource(R.string.quality_title), close) {
        LiteText(stringResource(R.string.quality_detection_tip), style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
        LiteButton(stringResource(R.string.quality_retry), feature::retry, icon = FullGlyphs.refresh,
            enabled = !state.loading && !state.provisional && state.ip != null)
        if (state.loading) CircularProgressIndicator(color = LocalLiteVisuals.current.primary)
        if (state.provisional) LiteText(stringResource(R.string.quality_provisional), style = LiteType.Footnote)
        LitePane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QualityRow(stringResource(R.string.quality_ip), if (state.hidden) "*****" else state.ip ?: "—", state.ip)
                val quality = state.quality
                QualityRow(stringResource(R.string.quality_level), quality?.let { stringResource(levelLabel(it.level)) } ?: "—")
                QualityRow(stringResource(R.string.quality_type), quality?.let { stringResource(typeLabel(it.type)) }
                    ?: stringResource(if (state.failed) R.string.quality_failed else R.string.quality_unknown))
                val flags = buildList {
                    if (quality?.isTor == true) add(stringResource(R.string.quality_tor))
                    if (quality?.isAbuser == true) add(stringResource(R.string.quality_abuser))
                    if (quality?.isVpn == true) add(stringResource(R.string.quality_vpn))
                    if (quality?.isProxy == true) add(stringResource(R.string.quality_proxy))
                }
                QualityRow(stringResource(R.string.quality_flags), flags.joinToString(" · ").ifEmpty { stringResource(R.string.quality_none) })
                QualityRow(stringResource(R.string.quality_organization), quality?.organization ?: "—", quality?.organization)
                QualityRow(stringResource(R.string.quality_asn), quality?.asn?.let { "AS$it" } ?: "—", quality?.asn?.let { "AS$it" })
                QualityRow(stringResource(R.string.quality_source), quality?.source?.label ?: "—")
            }
        }
        if (state.failed) {
            LiteText(stringResource(R.string.quality_failed), style = LiteType.Label, color = MaterialTheme.colorScheme.error)
            LiteText(stringResource(R.string.quality_cooldown), style = LiteType.Footnote)
            state.failures.forEach { failure -> QualityRow(failure.source.label, stringResource(failureLabel(failure.status))) }
        }
        LiteSwitch(state.hidden, stringResource(R.string.quality_hide_ip), feature::setHidden)
        if (state.persistenceError) LiteText(stringResource(R.string.quality_save_failed), style = LiteType.Footnote,
            color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun QualityChip(label: String, level: IpQualityLevel? = null) {
    val ink = if (level == null || level == IpQualityLevel.Normal) LocalLiteVisuals.current.secondaryInk else qualityInk(level)
    LitePane(corner = LiteCorner.Full, solidColor = ink.copy(alpha = 0.08f)) {
        LiteText(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = LiteType.Caption, color = ink, maxLines = 1)
    }
}

@Composable internal fun qualityInk(level: IpQualityLevel?) = when (level) {
        IpQualityLevel.Good -> if (LocalLiteVisuals.current.dark) androidx.compose.ui.graphics.Color(0xFF86EB81) else androidx.compose.ui.graphics.Color(0xFF125919)
        IpQualityLevel.Risky -> MaterialTheme.colorScheme.error
        else -> LocalLiteVisuals.current.ink
}

@Composable private fun QualityRow(label: String, value: String, copy: String? = null) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().then(if (copy != null) Modifier.clickable(role = Role.Button,
        onClickLabel = stringResource(R.string.quality_copy)) { clipboard.setText(AnnotatedString(copy)) } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LiteText(label, Modifier.weight(1f), style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
        LiteText(value, Modifier.weight(1.4f), style = LiteType.Label)
    }
}

private fun typeLabel(type: IpType) = when (type) {
    IpType.Residential -> R.string.quality_residential; IpType.Mobile -> R.string.quality_mobile
    IpType.Business -> R.string.quality_business; IpType.Hosting -> R.string.quality_hosting
}
private fun levelLabel(level: IpQualityLevel) = when (level) {
    IpQualityLevel.Good -> R.string.quality_good; IpQualityLevel.Normal -> R.string.quality_normal; IpQualityLevel.Risky -> R.string.quality_risky
}
private fun failureLabel(status: IpQualitySourceStatus) = when (status) {
    IpQualitySourceStatus.NoType -> R.string.quality_source_no_type; IpQualitySourceStatus.Timeout -> R.string.quality_source_timeout
    IpQualitySourceStatus.RateLimited -> R.string.quality_source_rate_limited; IpQualitySourceStatus.Failed -> R.string.quality_source_failed
    IpQualitySourceStatus.IpMismatch -> R.string.quality_source_mismatch
}
