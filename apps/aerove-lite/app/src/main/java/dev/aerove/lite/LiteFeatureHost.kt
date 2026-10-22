package dev.aerove.lite

import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.ProfileEditorSession

interface LiteFeatureHost {
    val backup: BackupFeature
    fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession
    fun closeEditor()
    fun editorSaved(profileId: String)
    fun importProfileText(name: String, input: String)
}
