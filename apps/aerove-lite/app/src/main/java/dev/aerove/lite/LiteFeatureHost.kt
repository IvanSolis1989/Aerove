package dev.aerove.lite

import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.profiles.ProfileListFeature
import dev.aerove.lite.ipquality.IpQualityFeature
import dev.aerove.lite.updates.UpdateFeature

interface LiteFeatureHost {
    val backup: BackupFeature
    val profileList: ProfileListFeature
    val ipQuality: IpQualityFeature
    val updates: UpdateFeature
    fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession
    fun closeEditor()
    fun editorSaved(profileId: String)
    fun importProfileText(name: String, input: String)
}
