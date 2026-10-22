package dev.aerove.lite.editor

import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier

data class EditorLoupePosition(val x: Int, val y: Int)

internal class EditorMagnifier(editor: CodeEditor, private val update: (EditorLoupePosition?) -> Unit) : Magnifier(editor) {
    private var position: EditorLoupePosition? = null
    override fun show(x: Int, y: Int) {
        if (!isEnabled) return
        position = EditorLoupePosition(x, y)
        update(position)
    }
    override fun isShowing(): Boolean = position != null
    override fun dismiss() { position = null; update(null) }
    override fun updateDisplay() { position?.let(update) }
}
