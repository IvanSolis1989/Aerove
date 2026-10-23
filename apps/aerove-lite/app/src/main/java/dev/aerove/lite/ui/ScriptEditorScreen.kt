package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.aerove.lite.editor.EditorLanguage
import dev.aerove.lite.editor.EditorPreferences
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.ScriptResource
import dev.aerove.lite.overrides.scriptEditorSession

@Composable
internal fun ScriptEditorScreen(repository: OverrideRepository, script: ScriptResource,
    onClose: () -> Unit, onSaved: () -> Unit, modifier: Modifier = Modifier,
    preferences: EditorPreferences = EditorPreferences(), onPreferencesChange: (EditorPreferences) -> Unit = {}) {
    val session = remember(repository, script.id) { scriptEditorSession(repository, script.id, script.name) }
    EditorScreen(session, onClose, onSaved, modifier, language = EditorLanguage.JavaScript,
        preferences = preferences, onPreferencesChange = onPreferencesChange)
}
