package dev.aerove.lite.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import dev.aerove.lite.data.MAX_PROFILE_BYTES
import dev.aerove.lite.data.checkEditorSize
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.component.Magnifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorDocumentState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val version: Long = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selected: Boolean = false,
    val line: Int = 1,
    val column: Int = 1,
    val matches: Int = 0,
    val match: Int = 0,
    val suggestions: List<EditorSuggestion> = emptyList(),
    val loupe: EditorLoupePosition? = null,
    val clipboardMenu: Boolean = false,
    val error: String? = null,
)

internal class EditorDocumentController(
    private val context: Context,
    private val scope: CoroutineScope,
    val readOnly: Boolean,
    private val changed: () -> Unit,
    private val save: () -> Unit,
    private val find: (Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(EditorDocumentState())
    val state = mutableState.asStateFlow()
    private var view: NativeEditorView? = null
    private var assets: EditorAssets? = null
    private var document: Content? = null
    private var suppress = false
    private var released = false
    private var locked = false
    private var language = EditorLanguage.Yaml
    private var darkTheme: Boolean? = null
    private var completionJob: Job? = null
    private var query = EditorQuery()
    private var appliedPreferences: EditorPreferences? = null
    private val undoDocuments = ArrayDeque<Content>()
    private val redoDocuments = ArrayDeque<Content>()
    private var nativeRedosBeforeBulk = 0

    suspend fun open(text: String, language: EditorLanguage, dark: Boolean) {
        this.language = language
        val prepared = EditorAssets.load(context, language, dark)
        try {
            val content = document ?: withContext(Dispatchers.Default) { Content(text) }
            if (released) { prepared.close(); return }
            val previous = assets
            assets = prepared
            darkTheme = dark
            document = content
            view?.let { configure(it); show(content) }
            if (view == null) previous?.language?.analyzeManager?.destroy()
            previous?.close()
            mutableState.value = state.value.copy(ready = true)
        } catch (failure: Throwable) { prepared.close(); throw failure }
    }

    suspend fun updateTheme(dark: Boolean) {
        if (darkTheme == dark || !state.value.ready || released) return
        val next = EditorAssets.load(context, language, dark)
        if (released) { next.language.destroy(); next.close(); return }
        val previous = assets
        assets = next
        darkTheme = dark
        view?.let(::configure)
        if (view == null) previous?.language?.destroy()
        previous?.close()
    }

    fun createView(): NativeEditorView = NativeEditorView(context).also { editor ->
        check(view == null)
        view = editor
        editor.owner = this
        editor.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        editor.isLineNumberEnabled = true
        editor.setPinLineNumber(true)
        editor.tabWidth = 2
        editor.getComponent(EditorAutoCompletion::class.java).isEnabled = false
        editor.getComponent(EditorTextActionWindow::class.java).isEnabled = false
        editor.replaceComponent(Magnifier::class.java, EditorMagnifier(editor) { position ->
            mutableState.value = state.value.copy(loupe = position)
        })
        editor.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            if (!suppress && event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                if (!event.isCausedByUndoManager) { redoDocuments.clear(); nativeRedosBeforeBulk = 0 }
                onChanged()
            }
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> syncState() }
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> syncState() }
        editor.subscribeEvent(EditorKeyEvent::class.java) { event, _ ->
            if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
                val action: (() -> Unit)? = when (event.keyCode) {
                    KeyEvent.KEYCODE_S -> save
                    KeyEvent.KEYCODE_F -> ({ find(false) })
                    KeyEvent.KEYCODE_H -> ({ find(!readOnly) })
                    KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) ::redo else ::undo
                    KeyEvent.KEYCODE_Y -> ::redo
                    KeyEvent.KEYCODE_C -> ({ copy(false) })
                    KeyEvent.KEYCODE_X -> ({ copy(true) })
                    KeyEvent.KEYCODE_V -> ::paste
                    else -> null
                }
                if (action != null) { event.intercept(); event.setResult(true); if (!locked && !state.value.busy) action() }
            }
        }
        configure(editor)
        document?.let(::show)
    }

    private fun configure(editor: NativeEditorView) {
        assets?.let { editor.setEditorLanguage(it.language); editor.colorScheme = it.colors; editor.typefaceText = it.typeface }
        editor.isEditable = !readOnly && !locked && !state.value.busy
    }

    fun style(palette: EditorPalette, preferences: EditorPreferences) {
        view?.let { editor ->
            palette.apply(editor.colorScheme)
            if (appliedPreferences?.fontSize != preferences.fontSize) editor.setTextSize(preferences.fontSize.sp)
            if (editor.isWordwrap != preferences.lineWrap) editor.setWordwrap(preferences.lineWrap)
            editor.typefaceLineNumber = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            appliedPreferences = preferences
        }
    }

    fun lock(value: Boolean) {
        locked = value
        if (value) view?.finishComposition()
        view?.isEditable = !value && !readOnly && !state.value.busy
    }

    suspend fun readText(): String = withContext(Dispatchers.Default) { checkNotNull(document).toString() }

    private fun show(content: Content) {
        document = content
        suppress = true
        try { view?.setText(content, true, null) } finally { suppress = false }
        syncState()
    }

    private fun onChanged() {
        mutableState.value = state.value.copy(version = state.value.version + 1, error = null, suggestions = emptyList())
        changed()
        syncState()
        scheduleCompletion()
    }

    private fun syncState() {
        val editor = view ?: return
        val searcher = editor.searcher
        val count = if (searcher.hasQuery()) searcher.matchedPositionCount else 0
        val match = if (searcher.hasQuery()) searcher.currentMatchedPositionIndex + 1 else 0
        mutableState.value = state.value.copy(
            canUndo = !readOnly && (editor.canUndo() || undoDocuments.isNotEmpty()),
            canRedo = !readOnly && (editor.canRedo() || redoDocuments.isNotEmpty()),
            selected = editor.cursor.isSelected,
            line = editor.cursor.rightLine + 1, column = editor.cursor.rightColumn + 1,
            matches = count, match = match.coerceAtLeast(0),
        )
    }

    fun undo() {
        if (readOnly || locked || state.value.busy) return
        val editor = view ?: return
        editor.finishComposition()
        if (editor.canUndo()) {
            editor.undoNative()
            if (redoDocuments.isNotEmpty()) nativeRedosBeforeBulk++
        } else if (undoDocuments.isNotEmpty()) {
            redoDocuments.addLast(checkNotNull(document))
            show(undoDocuments.removeLast())
            nativeRedosBeforeBulk = 0
            onChanged()
        }
        syncState()
    }

    fun redo() {
        if (readOnly || locked || state.value.busy) return
        val editor = view ?: return
        if (nativeRedosBeforeBulk > 0 && editor.canRedo()) {
            editor.redoNative(); nativeRedosBeforeBulk--
        } else if (redoDocuments.isNotEmpty()) {
            undoDocuments.addLast(checkNotNull(document))
            show(redoDocuments.removeLast())
            onChanged()
        } else if (editor.canRedo()) editor.redoNative()
        syncState()
    }

    fun search(next: EditorQuery) {
        query = next
        val editor = view ?: return
        runCatching {
            require(next.text.length <= 1024) { "Search is too long" }
            if (next.text.isEmpty()) editor.searcher.stopSearch()
            else editor.searcher.search(next.text, EditorSearcher.SearchOptions(
                if (next.regex) EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION else EditorSearcher.SearchOptions.TYPE_NORMAL,
                !next.matchCase, RegexBackrefGrammar('$', '\\'),
            ))
            mutableState.value = state.value.copy(error = null, suggestions = emptyList())
        }.onFailure { editor.searcher.stopSearch(); error(it) }
    }

    fun nextMatch(previous: Boolean = false) {
        val searcher = view?.searcher ?: return
        if (!searcher.hasQuery()) return
        if (previous) searcher.gotoPrevious() else searcher.gotoNext()
    }

    fun replaceCurrent(replacement: String) {
        if (readOnly || locked || state.value.busy) return
        val editor = view ?: return
        runCatching { if (editor.searcher.hasQuery()) editor.searcher.replaceCurrentMatch(replacement) }.onFailure(::error)
    }

    fun replaceAll(replacement: String) {
        if (query.text.isEmpty()) return
        val replacingQuery = query
        bulk { text -> EditorSearch.replaceAll(text, replacingQuery, replacement) }
    }

    fun insertLarge(insertion: CharSequence) {
        val cursor = view?.cursor ?: return
        val start = cursor.left
        val end = cursor.right
        bulk { text ->
            require(insertion.length <= MAX_PROFILE_BYTES) { "Insertion exceeds the configuration limit" }
            (text.substring(0, start) + insertion + text.substring(end)).also(::checkEditorSize)
        }
    }

    private fun bulk(transform: (String) -> String) {
        if (readOnly || locked || state.value.busy || !state.value.ready) return
        view?.finishComposition()
        val original = checkNotNull(document)
        val caretLine = view?.cursor?.leftLine ?: 0
        val caretColumn = view?.cursor?.leftColumn ?: 0
        mutableState.value = state.value.copy(busy = true, suggestions = emptyList(), error = null)
        view?.isEditable = false
        scope.launch {
            try {
                val next = withContext(Dispatchers.Default) {
                    Content(transform(original.toString())).also { content ->
                        val line = minOf(caretLine, content.lineCount - 1)
                        content.cursor.set(line, minOf(caretColumn, content.getColumnCount(line)))
                    }
                }
                if (!released) {
                    undoDocuments.addLast(original)
                    redoDocuments.clear()
                    nativeRedosBeforeBulk = 0
                    var retained = undoDocuments.sumOf { it.length.toLong() }
                    while (undoDocuments.size > 8 || retained > MAX_PROFILE_BYTES * 4L) retained -= undoDocuments.removeFirst().length
                    show(next)
                    onChanged()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure) }
            finally {
                mutableState.value = state.value.copy(busy = false)
                view?.isEditable = !readOnly && !locked
                syncState()
            }
        }
    }

    fun selectAll() { view?.selectAll() }
    fun showClipboardMenu() { mutableState.value = state.value.copy(clipboardMenu = true) }
    fun dismissClipboardMenu() {
        view?.let { it.setSelection(it.cursor.rightLine, it.cursor.rightColumn) }
        mutableState.value = state.value.copy(clipboardMenu = false)
    }

    fun drawMagnified(canvas: android.graphics.Canvas, width: Float, height: Float, x: Float, y: Float) {
        val saved = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width, height)
            canvas.translate(width / 2 - x * 1.25f, height / 2 - y * 1.25f)
            canvas.scale(1.25f, 1.25f)
            view?.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }

    fun copy(cut: Boolean, wholeLine: Boolean = true) {
        val editor = view ?: return
        if (locked || state.value.busy || cut && readOnly) return
        val cursor = editor.cursor
        val selectedLeft = cursor.left
        val selectedRight = cursor.right
        val source = document ?: return
        val start = if (!cursor.isSelected && wholeLine) cursor.left - cursor.leftColumn else cursor.left
        val end = if (!cursor.isSelected && wholeLine) minOf(source.length,
            start + source.getColumnCount(cursor.leftLine) + source.getLine(cursor.leftLine).lineSeparator.length) else cursor.right
        val version = state.value.version
        scope.launch {
            try {
                val selected = withContext(Dispatchers.Default) { source.substring(start, end) }
                if (released || state.value.version != version || locked || state.value.busy) return@launch
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", selected))
                if (cut && editor.cursor.left == selectedLeft && editor.cursor.right == selectedRight) {
                    if (end - start > INLINE_EDIT_LIMIT) bulk { it.removeRange(start, end) }
                    else { source.delete(start, end) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure) }
        }
    }

    fun paste() {
        if (readOnly || locked || state.value.busy) return
        runCatching {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text ?: return
            require(text.length <= MAX_PROFILE_BYTES) { "Clipboard text is too large" }
            if (text.length > INLINE_EDIT_LIMIT) insertLarge(text) else view?.commitText(text, false, false)
        }.onFailure(::error)
    }

    private fun scheduleCompletion() {
        completionJob?.cancel()
        if (readOnly || query.text.isNotEmpty()) return
        val editor = view ?: return
        val version = state.value.version
        val line = editor.cursor.rightLine
        val column = editor.cursor.rightColumn
        val source = document ?: return
        completionJob = scope.launch {
            delay(180)
            try {
                val result = withContext(Dispatchers.Default) { assets?.completion?.suggestions(source.toString(), line, column, language).orEmpty() }
                if (!released && state.value.version == version && editor.cursor.rightLine == line && editor.cursor.rightColumn == column) {
                    mutableState.value = state.value.copy(suggestions = result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure) }
        }
    }

    fun complete(suggestion: EditorSuggestion) {
        if (readOnly || locked || state.value.busy || suggestion !in state.value.suggestions) return
        val text = document ?: return
        text.replace(suggestion.start, suggestion.end, suggestion.insertion)
        mutableState.value = state.value.copy(suggestions = emptyList())
    }

    fun dismissSuggestions() { mutableState.value = state.value.copy(suggestions = emptyList()) }
    fun error(failure: Throwable) { mutableState.value = state.value.copy(error = failure.message ?: failure.javaClass.simpleName) }
    fun dismissError() { mutableState.value = state.value.copy(error = null) }

    fun release() {
        released = true
        completionJob?.cancel()
        val attached = view != null
        view?.let { it.searcher.stopSearch(); it.owner = null; it.release() }
        view = null
        if (!attached) assets?.language?.destroy()
        assets?.close()
        assets = null
        document = null
        undoDocuments.clear()
        redoDocuments.clear()
    }

    companion object { const val INLINE_EDIT_LIMIT = 8192 }
}
