package dev.aerove.lite.data

import java.io.File
import dev.aerove.lite.editor.EditorQuery
import dev.aerove.lite.editor.EditorSearch
import dev.aerove.lite.editor.editorInsertion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEditorBudgetTest {
    private class Source(var text: String) : ProfileEditorSource {
        override suspend fun read() = text
        override suspend fun replace(expected: String, replacement: String) { check(text == expected); text = replacement }
    }

    @Test fun localRuleOpensAndSavesBeyondTheUnrelatedConfigurationBudget() = runBlocking {
        val source = Source("#".repeat(5 * 1024 * 1024))
        val editor = ProfileEditorSession.ruleEditable("local", source)
        editor.open()
        assertNull(editor.state.value.error)
        assertEquals(source.text, editor.state.value.initialText)
        editor.contentChanged()
        assertTrue(editor.save { source.text + "\n" })
        assertEquals(5 * 1024 * 1024 + 1, source.text.length)
        val configuration = ProfileEditorSession.editable("configuration", Source(source.text))
        configuration.open()
        assertNull(configuration.state.value.initialText)
        assertEquals("Configuration exceeds $MAX_PROFILE_BYTES UTF-8 bytes", configuration.state.value.error)
    }

    @Test fun localRuleEditorUsesTheSameInclusive64MiBBoundForOpenAndSave() = runBlocking {
        val source = Source("#".repeat(MAX_RULE_PREVIEW_BYTES))
        val editor = ProfileEditorSession.ruleEditable("local", source)
        editor.open()
        assertNull(editor.state.value.error)
        editor.contentChanged()
        assertTrue(editor.save { source.text })
        editor.contentChanged()
        assertFalse(editor.save { source.text + "x" })
        assertEquals("Configuration exceeds $MAX_RULE_PREVIEW_BYTES UTF-8 bytes", editor.state.value.error)
        val rejected = ProfileEditorSession.ruleEditable("too large", Source(source.text + "x"))
        rejected.open()
        assertNull(rejected.state.value.initialText)
        assertEquals("Configuration exceeds $MAX_RULE_PREVIEW_BYTES UTF-8 bytes", rejected.state.value.error)
    }

    @Test fun productionLibraryLocalRuleUsesTheRuleEditorFactory() {
        val source = File("src/main/java/dev/aerove/lite/ui/GlobalResourceLibraryScreen.kt").readText()
        assertTrue(source.contains("else ProfileEditorSession.ruleEditable(name, object : ProfileEditorSource {"))
        val screen = File("src/main/java/dev/aerove/lite/ui/EditorScreen.kt").readText()
        assertTrue(screen.contains("maxBytes = session.maxBytes"))
        val controller = File("src/main/java/dev/aerove/lite/editor/EditorDocumentController.kt").readText()
        assertTrue(controller.contains("editorInsertion(text, start, end, insertion, maxBytes)"))
        assertTrue(controller.contains("EditorSearch.replaceAll(text, replacingQuery, replacement, maxBytes)"))
        assertTrue(controller.contains("require(text.length <= maxBytes)"))
        assertTrue(controller.contains("retained > MAX_PROFILE_BYTES * 4L"))
    }

    @Test fun insertionAndReplaceAllUseTheRuleBudgetAndKeepTheConfigurationDefault() {
        val large = "#".repeat(5 * 1024 * 1024)
        assertEquals(large, editorInsertion("", 0, 0, large, MAX_RULE_PREVIEW_BYTES))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { editorInsertion("", 0, 0, large, MAX_PROFILE_BYTES) }
        assertEquals(large, EditorSearch.replaceAll("x", EditorQuery("x"), large, MAX_RULE_PREVIEW_BYTES))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { EditorSearch.replaceAll("x", EditorQuery("x"), large) }
        assertEquals("中", editorInsertion("", 0, 0, "中", 3))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { editorInsertion("", 0, 0, "中", 2) }
    }
}
