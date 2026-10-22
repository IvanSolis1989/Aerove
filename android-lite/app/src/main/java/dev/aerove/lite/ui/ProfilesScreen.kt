package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R
import java.net.URI

@Composable
internal fun ProfilesScreen(state: LiteState, actions: LiteActions, padding: PaddingValues, addSubscription: () -> Unit, deleteProfile: (ProfileSummary) -> Unit) {
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiteButton(stringResource(R.string.lite_import), actions::importFile, Modifier.weight(1f), Icons.Outlined.FileDownload, enabled = state.busy.isEmpty())
                LiteButton(stringResource(R.string.lite_add_subscription), addSubscription, Modifier.weight(1f), Icons.Outlined.Add, enabled = state.busy.isEmpty())
            }
        }
        if (state.profiles.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_profiles), stringResource(R.string.lite_no_profiles_detail), Icons.Outlined.FolderOpen)
        }
        items(state.profiles, key = { it.id }) { profile ->
            val selected = profile.id == state.selectedProfile
            LitePane(
                Modifier.fillMaxWidth().selectable(selected, enabled = state.busy.isEmpty(), role = Role.RadioButton) { actions.selectProfile(profile.id) },
                selected = selected,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LiteText(profile.name, style = LiteType.Headline)
                            LiteText(
                                profile.url?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty().ifEmpty { stringResource(R.string.lite_local_profile) },
                                style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk,
                            )
                        }
                        if (selected) LiteIcon(Icons.Outlined.Check, color = LocalLiteVisuals.current.primary)
                    }
                    LiteText(
                        if (profile.updatedAt > 0) stringResource(R.string.lite_updated_at, formatTime(profile.updatedAt)) else stringResource(R.string.lite_not_updated),
                        style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (profile.url != null) LiteButton(
                            stringResource(R.string.lite_refresh), { actions.updateProfile(profile.id) },
                            Modifier.weight(1f), Icons.Outlined.Refresh, enabled = state.busy.isEmpty(),
                        )
                        LiteIconButton(
                            stringResource(R.string.lite_delete), Icons.Outlined.DeleteOutline,
                            { deleteProfile(profile) }, enabled = state.busy.isEmpty(), destructive = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionEditor(state: LiteState, actions: LiteActions, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var awaitingNewProfile by rememberSaveable { mutableStateOf(false) }
    val originalIds = remember { state.profiles.mapTo(mutableSetOf()) { it.id } }
    LaunchedEffect(state.profiles) {
        if (awaitingNewProfile && state.profiles.any { it.id !in originalIds }) onDismiss()
    }
    val valid = name.isNotBlank() && runCatching {
        val uri = URI(url.trim())
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
    LiteModal(stringResource(R.string.lite_add_subscription), onDismiss) {
        LiteField(name, { name = it }, stringResource(R.string.lite_subscription_name), enabled = state.busy.isEmpty())
        LiteField(url, { url = it }, stringResource(R.string.lite_subscription_url), keyboardType = KeyboardType.Uri, enabled = state.busy.isEmpty())
        if (attempted && !valid) LiteText(stringResource(R.string.lite_subscription_error), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        LiteButton(
            stringResource(if (state.busy.isEmpty()) R.string.lite_add_subscription else R.string.lite_importing),
            onClick = {
                attempted = true
                if (valid) { awaitingNewProfile = true; actions.addSubscription(name.trim(), url.trim()) }
            },
            modifier = Modifier.fillMaxWidth(),
            selected = true,
            enabled = state.busy.isEmpty(),
        )
    }
}
