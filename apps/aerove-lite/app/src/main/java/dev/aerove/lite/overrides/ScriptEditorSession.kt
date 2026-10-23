package dev.aerove.lite.overrides

import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.data.ProfileEditorSource

/** Reuses the production editor's dirty/back/search/undo lifecycle, with atomic script content CAS. */
fun scriptEditorSession(repository: OverrideRepository, id: String, title: String): ProfileEditorSession =
    ProfileEditorSession.editable(title, object : ProfileEditorSource {
        override suspend fun read(): String = repository.snapshot().scripts.find { it.id == id }?.content
            ?: throw ScriptExecutionException("missing_script")

        override suspend fun replace(expected: String, replacement: String) {
            repository.replaceContent(id, expected, replacement)
        }
    })
