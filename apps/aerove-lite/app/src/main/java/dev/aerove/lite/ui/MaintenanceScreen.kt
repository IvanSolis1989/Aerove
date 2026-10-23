package dev.aerove.lite.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.maintenance.DeveloperUnlock
import dev.aerove.lite.maintenance.MaintenanceFeature
import dev.aerove.lite.maintenance.MaintenanceNotice
import dev.aerove.lite.maintenance.MaintenanceOperation
import dev.aerove.lite.maintenance.MaintenanceState

/** Wrap the real About logo, not the version label, to match Standard's unlock target. */
@Composable
internal fun MaintenanceDeveloperUnlockTarget(onUnlock: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val detector = remember { DeveloperUnlock() }
    val latest by rememberUpdatedState(onUnlock)
    Box(modifier.clickable(role = Role.Button) { if (detector.tap(SystemClock.elapsedRealtime())) latest() }) { content() }
}

@Composable
internal fun MaintenanceScreen(state: MaintenanceState, feature: MaintenanceFeature, developerMode: Boolean,
    onDeveloperMode: (Boolean) -> Unit, onMessageTest: () -> Unit, onLogsTest: () -> Unit,
    padding: PaddingValues = PaddingValues(16.dp), onCrashCore: (() -> Unit)? = null) {
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    LaunchedEffect(state.confirmation?.id) {
        if (state.confirmation != null) overlay.content = {
            current.confirmation?.let { confirmation ->
                val title = stringResource(if (confirmation.operation == MaintenanceOperation.ClearAllData)
                    R.string.maintenance_clear_data else R.string.maintenance_prune_cache)
                LiteModal(title, { feature.dismiss(); overlay.dismiss() }) {
                    if (confirmation.operation == MaintenanceOperation.ClearAllData)
                        LiteText(stringResource(R.string.maintenance_confirm_clear_all_data))
                    LiteText(stringResource(R.string.maintenance_plan_summary, confirmation.inventory.profiles,
                        confirmation.inventory.retainedFiles, formatBytes(confirmation.inventory.cacheBytes)))
                    LiteButton(title, { feature.confirm(confirmation.id); overlay.dismiss() }, destructive = confirmation.operation == MaintenanceOperation.ClearAllData)
                    LiteButton(stringResource(R.string.maintenance_cancel), { feature.dismiss(); overlay.dismiss() })
                }
            }
        }
    }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteSwitch(developerMode, stringResource(R.string.maintenance_developer_mode), onDeveloperMode, !state.busy)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.maintenance_options)) }
        item { LiteButton(stringResource(R.string.maintenance_message_test), onMessageTest, enabled = !state.busy) }
        item { LiteButton(stringResource(R.string.maintenance_logs_test), onLogsTest, enabled = !state.busy) }
        if (onCrashCore != null) item {
            LiteButton(stringResource(R.string.maintenance_crash_test), {
                overlay.content = {
                    LiteModal(stringResource(R.string.maintenance_crash_test), overlay::dismiss) {
                        LiteText(stringResource(R.string.maintenance_confirm_force_crash_core))
                        LiteButton(stringResource(R.string.maintenance_confirm), { overlay.dismiss(); onCrashCore() }, destructive = true)
                        LiteButton(stringResource(R.string.maintenance_cancel), overlay::dismiss)
                    }
                }
            }, enabled = !state.busy, destructive = true)
        }
        item { LiteButton(stringResource(R.string.maintenance_clear_data), { feature.request(MaintenanceOperation.ClearAllData) }, enabled = !state.busy, destructive = true) }
        item { LiteButton(stringResource(R.string.maintenance_prune_cache), { feature.request(MaintenanceOperation.PruneCache) }, enabled = !state.busy) }
        state.notice?.let { notice -> item {
            LiteText(stringResource(when (notice) {
                MaintenanceNotice.Completed -> R.string.maintenance_completed
                MaintenanceNotice.Changed -> R.string.maintenance_changed
                MaintenanceNotice.Failed -> R.string.maintenance_failed
            }))
        } }
    }
}
