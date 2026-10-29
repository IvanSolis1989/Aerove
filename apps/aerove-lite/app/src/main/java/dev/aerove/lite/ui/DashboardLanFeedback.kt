package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.networksettings.DashboardLanReceipt
import dev.aerove.lite.notices.NoticeLevel

/** Only the mounted card owns the pending gesture; configuration restores cannot create one. */
internal class DashboardLanFeedbackIntent {
    private var pending: Long? = null
    fun requested(token: Long) { pending = token }
    fun invalidate() { pending = null }
    fun consume(receipt: DashboardLanReceipt?, allowLan: Boolean, mixedPort: Int, active: Boolean): Boolean {
        if (!active) { invalidate(); return false }
        if (receipt == null || receipt.token != pending) return false
        invalidate()
        return allowLan && receipt.mixedPort == mixedPort && mixedPort in 1..65535
    }
}

@Composable
internal fun rememberDashboardLanEnable(state: LiteState, actions: LiteActions): () -> Unit {
    val intent = remember(actions) { DashboardLanFeedbackIntent() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageActive = LocalLitePageActive.current && LocalDiagnosticsForeground.current && LocalLiteWindowFocused.current
    val active by rememberUpdatedState(pageActive)
    val current by rememberUpdatedState(state)
    val clipboard = LocalClipboardManager.current
    val notices = LocalClientNotices.current
    val resources by rememberUpdatedState(LocalResources.current)
    DisposableEffect(intent, lifecycle, pageActive) {
        if (!pageActive) intent.invalidate()
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) intent.invalidate()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); intent.invalidate() }
    }
    LaunchedEffect(state.dashboardLanReceipt, state.settings.network.allowLan, state.settings.network.ports.mixed, pageActive) {
        val snapshot = current
        if (intent.consume(snapshot.dashboardLanReceipt, snapshot.settings.network.allowLan,
                snapshot.settings.network.ports.mixed, active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) {
            val address = dashboardLanAddress(snapshot.localAddress, snapshot.settings.network.ports.mixed, true)
            if (address == null) notices?.post(resources.getString(R.string.lite_lan_enabled_no_address), NoticeLevel.Info)
            else {
                val copied = try { clipboard.setText(AnnotatedString(address)); true } catch (_: Exception) { false }
                notices?.post(resources.getString(if (copied) R.string.lite_lan_address_copied else
                    R.string.lite_lan_address_copy_failed, address), if (copied) NoticeLevel.Success else NoticeLevel.Warning)
            }
        }
    }
    return {
        if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !current.settings.network.allowLan) {
            intent.requested(actions.enableDashboardLan())
        }
    }
}
