package dev.aerove.lite.editor

import android.content.Context
import android.view.ContextThemeWrapper
import java.io.File
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light_NoActionBar)

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(controller: EditorDocumentController, predicate: (EditorDocumentState) -> Boolean) = runBlocking {
        withTimeout(10_000) { controller.state.first(predicate) }
    }

    private fun editable(view: NativeEditorView, action: () -> Unit) = runBlocking {
        lateinit var ready: kotlinx.coroutines.flow.StateFlow<Boolean>
        main { ready = view.inputReady }
        withTimeout(10_000) { ready.first { it } }
        main { assertTrue(view.isEditable); action() }
    }

    @Test fun allPinnedGrammarsAndFullSchemaLoadWithoutStartingTheCore() = runBlocking<Unit> {
        for (language in EditorLanguage.entries) {
            val loaded = EditorAssets.load(context, language, dark = false)
            try {
                assertNotNull(loaded.language.analyzeManager)
                assertNotNull(loaded.typeface)
                assertTrue(loaded.completion.suggestions("mixed-p", 0, 7, EditorLanguage.Yaml).any { it.label == "mixed-port" })
            } finally { loaded.language.analyzeManager.destroy(); loaded.language.destroy(); loaded.close() }
        }
    }

    @Test fun nativeTypingSearchReplaceAndUndoRedoOperateOnTheRealDocument() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: EditorDocumentController
        lateinit var view: NativeEditorView
        main { controller = EditorDocumentController(context, scope, false, {}, {}, {}) }
        try {
            controller.open("mode: rule\n", EditorLanguage.Yaml, false)
            main {
                view = controller.createView()
            }
            editable(view) {
                view.setSelection(0, 10)
                view.commitText(" # test", false, false)
                assertEquals("mode: rule # test\n", view.text.toString())
                controller.undo()
                assertEquals("mode: rule\n", view.text.toString())
                controller.redo()
                assertEquals("mode: rule # test\n", view.text.toString())
                controller.search(EditorQuery("rule"))
            }
            await(controller) { it.matches == 1 }
            main { controller.nextMatch(); controller.replaceCurrent("direct") }
            assertEquals("mode: direct # test\n", controller.readText())
        } finally { main { controller.release() }; scope.cancel() }
    }

    @Test fun largeBulkEditKeepsHistoryAndBothSourceBuffersSurviveRoundTrip() = runBlocking<Unit> {
        val original = "# padded configuration\n".repeat(10_000) + "mode: rule\n"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: EditorDocumentController
        lateinit var view: NativeEditorView
        main { controller = EditorDocumentController(context, scope, false, {}, {}, {}) }
        try {
            controller.open(original, EditorLanguage.Yaml, true)
            main {
                view = controller.createView()
                controller.search(EditorQuery("padded"))
            }
            await(controller) { it.matches == 10_000 }
            main { controller.replaceAll("changed") }
            await(controller) { !it.busy && it.version > 0 }
            assertEquals(original.replace("padded", "changed"), controller.readText())
            editable(view) { controller.undo() }
            assertEquals(original, controller.readText())
            editable(view) { controller.redo() }
            assertEquals(original.replace("padded", "changed"), controller.readText())
            editable(view) { view.setSelection(0, 0); view.commitText("# one\n", false, false); controller.undo(); controller.undo() }
            assertEquals(original, controller.readText())
            editable(view) {
                controller.redo()
                assertFalse(controller.state.value.canUndo)
                assertFalse(controller.state.value.canRedo)
                controller.redo()
                assertEquals(original.replace("padded", "changed"), view.text.toString())
            }
            editable(view) { controller.redo() }
            assertEquals("# one\n" + original.replace("padded", "changed"), controller.readText())
        } finally { main { controller.release() }; scope.cancel() }
    }

    @Test fun previewHasSelectionAndSearchWhileEveryMutationRemainsBlocked() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: EditorDocumentController
        main { controller = EditorDocumentController(context, scope, true, { error("Preview changed") }, {}, {}) }
        try {
            controller.open("mode: rule\n", EditorLanguage.Yaml, false)
            main {
                val view = controller.createView()
                assertFalse(view.isEditable)
                controller.selectAll()
                assertTrue(view.cursor.isSelected)
                controller.search(EditorQuery("rule"))
                controller.replaceCurrent("direct")
                controller.replaceAll("direct")
                controller.insertLarge("mode: direct")
                view.commitText("mode: direct", false, false)
                view.deleteText()
                controller.undo()
            }
            await(controller) { it.matches == 1 }
            assertEquals("mode: rule\n", controller.readText())
        } finally { main { controller.release() }; scope.cancel() }
    }

    @Test fun fileInputStrictlyDecodesUtf8AndRejectsOversizedDocuments() = runBlocking<Unit> {
        val file = File(context.cacheDir, "editor-${UUID.randomUUID()}.yaml")
        try {
            file.writeBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "name: 中文\n".toByteArray())
            assertEquals("name: 中文\n", EditorInput.readFile(file))
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            try { EditorInput.readFile(file); fail("Malformed UTF-8 was accepted") } catch (_: java.nio.charset.CharacterCodingException) { }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
            try { EditorInput.readFile(file); fail("Oversized file was accepted") } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
}
