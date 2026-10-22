package dev.aerove.lite.editor

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.ContextMenu
import io.github.rosemoe.sora.widget.CodeEditor

internal class NativeEditorView(context: Context) : CodeEditor(context) {
    var owner: EditorDocumentController? = null
    private var connection: InputConnection? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = super.onCreateInputConnection(outAttrs).also { connection = it }
    fun finishComposition() { connection?.finishComposingText() }
    override fun onCreateContextMenu(menu: ContextMenu) { owner?.showClipboardMenu() }
    fun undoNative() { super.undo() }
    fun redoNative() { super.redo() }
    override fun undo() { owner?.undo() ?: super.undo() }
    override fun redo() { owner?.redo() ?: super.redo() }
    override fun copyText() { owner?.copy(false) ?: super.copyText() }
    override fun copyText(shouldCopyLine: Boolean) { owner?.copy(false, shouldCopyLine) ?: super.copyText(shouldCopyLine) }
    override fun cutText() { owner?.copy(true) ?: super.cutText() }
    override fun pasteText() { owner?.paste() ?: super.pasteText() }
    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (!isEditable) return
        if (text.length > EditorDocumentController.INLINE_EDIT_LIMIT && owner != null) owner?.insertLarge(text)
        else super.commitText(text, applyAutoIndent, applySymbolCompletion)
    }
    override fun deleteText() {
        if (!isEditable) return
        if (cursor.right - cursor.left > EditorDocumentController.INLINE_EDIT_LIMIT && owner != null) owner?.insertLarge("")
        else super.deleteText()
    }
}
