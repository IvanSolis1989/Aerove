package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.aerove.lite.LiteFeatureHost
import dev.aerove.lite.LiteState
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.ipquality.IpQuality
import dev.aerove.lite.ipquality.IpQualityFeature
import dev.aerove.lite.ipquality.IpQualitySource
import dev.aerove.lite.ipquality.IpType
import dev.aerove.lite.profiles.ProfileListFeature

@Composable
internal fun IpQualityFixture(state: LiteState,
    lookup: suspend (String) -> IpQuality = { IpQuality(it, IpQualitySource.IdentMe, IpType.Residential, organization = "Example ISP") },
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val feature = remember(scope) {
        IpQualityFeature(scope, lookup)
    }
    val overlay = remember { LiteOverlayState() }
    val host = remember(feature) { object : LiteFeatureHost {
        override val ipQuality = feature
        override val updates: dev.aerove.lite.updates.UpdateFeature get() = error("Unused display fixture updates")
        override val profileList: ProfileListFeature get() = error("Unused display fixture profiles")
        override val backup: BackupFeature get() = error("Unused display fixture backup")
        override fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession = error("Unused display fixture editor")
        override fun closeEditor() = Unit
        override fun editorSaved(profileId: String) = Unit
        override fun importProfileText(name: String, input: String) = Unit
    } }
    SideEffect {
        feature.bind(state.selectedProfile, state.phase == VpnPhase.Running, state.outboundIdentity?.address,
            state.outboundIdentity?.countryCode)
        feature.setForeground(true)
    }
    DisposableEffect(feature) { onDispose(feature::close) }
    CompositionLocalProvider(LocalLiteFeatures provides { host }, LocalLiteOverlay provides overlay, content = content)
}
