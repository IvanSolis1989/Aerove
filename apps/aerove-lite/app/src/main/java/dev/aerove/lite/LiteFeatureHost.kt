package dev.aerove.lite

import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.profiles.ProfileListFeature
import dev.aerove.lite.ipquality.IpQualityFeature
import dev.aerove.lite.updates.UpdateFeature

interface LiteFeatureHost {
    val backup: BackupFeature
    val profileList: ProfileListFeature
    val profileProperties: dev.aerove.lite.integration.ProfilePropertiesAccess
    val profileDetails: dev.aerove.lite.integration.ProfileDetailWorkspace
    val resources: dev.aerove.lite.integration.ResourcePageWorkspace
    val globalResources: dev.aerove.lite.integration.GlobalResourceWorkspace
    val profileImports: dev.aerove.lite.integration.ProfileImportWorkspace
    val ipQuality: IpQualityFeature
    val updates: UpdateFeature
    val observability: dev.aerove.lite.observability.ObservabilityFeature
    val groupIcons: dev.aerove.lite.images.GroupIconLoader
    val resourceManagement: dev.aerove.lite.resources.ResourceManagementSession
    val proxyPresentation: dev.aerove.lite.integration.ProxyPresentationWorkspace
    val overrides: dev.aerove.lite.integration.OverrideWorkspace
    val appAccess: dev.aerove.lite.appaccess.AppAccessFeature
    fun closeAppAccess()
    val maintenance: dev.aerove.lite.maintenance.MaintenanceFeature
    fun developerLogsTest()
    fun developerMessageTest()
    suspend fun createProfileBuilder(name: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession
    fun profileBuilder(profileId: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession
    suspend fun profileBuilderResources(): dev.aerove.lite.profilebuilder.BuilderResources
    fun closeProfileBuilder()
    fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession
    fun closeEditor()
    fun editorSaved(profileId: String)
    fun importProfileText(name: String, input: String)
}
