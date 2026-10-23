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
        override val groupIcons: dev.aerove.lite.images.GroupIconLoader get() = error("Unused IP quality fixture group icons")
        override val resourceManagement: dev.aerove.lite.resources.ResourceManagementSession get() = error("Unused IP quality fixture resource management")
        override val profileProperties: dev.aerove.lite.integration.ProfilePropertiesAccess get() = error("Unused IP quality fixture properties")
        override val profileImports: dev.aerove.lite.integration.ProfileImportWorkspace get() = error("Unused IP quality fixture imports")
        override val globalResources: dev.aerove.lite.integration.GlobalResourceWorkspace get() = error("Unused IP quality fixture global resources")
        override val resources: dev.aerove.lite.integration.ResourcePageWorkspace get() = error("Unused IP quality fixture resources")
        override val profileDetails: dev.aerove.lite.integration.ProfileDetailWorkspace get() = error("Unused IP quality fixture profile details")
        override val overrides: dev.aerove.lite.integration.OverrideWorkspace get() = error("Unused display fixture override workspace")
        override val proxyPresentation: dev.aerove.lite.integration.ProxyPresentationWorkspace get() = error("Unused display fixture proxy presentation")
        override val appAccess: dev.aerove.lite.appaccess.AppAccessFeature get() = error("Unused display fixture application access")
        override fun closeAppAccess() = Unit
        override val maintenance: dev.aerove.lite.maintenance.MaintenanceFeature get() = error("Unused display fixture maintenance")
        override fun developerLogsTest(): Unit = error("Unused display fixture developer logs action")
        override fun developerMessageTest(): Unit = error("Unused display fixture developer message action")
        override val observability: dev.aerove.lite.observability.ObservabilityFeature get() = error("Unused display fixture observability")
        override suspend fun createProfileBuilder(name: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession = error("Unused display fixture profile builder creation")
        override fun profileBuilder(profileId: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession = error("Unused display fixture profile builder")
        override suspend fun profileBuilderResources(): dev.aerove.lite.profilebuilder.BuilderResources = error("Unused display fixture profile builder resources")
        override fun closeProfileBuilder(): Unit = error("Unused display fixture close profile builder")
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
