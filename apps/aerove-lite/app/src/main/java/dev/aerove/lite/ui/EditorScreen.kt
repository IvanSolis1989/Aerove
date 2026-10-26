package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.data.EditorBackDecision
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.editor.EditorDocumentController
import dev.aerove.lite.editor.EditorDocumentState
import dev.aerove.lite.editor.EditorFontSize
import dev.aerove.lite.editor.EditorLanguage
import dev.aerove.lite.editor.EditorLoupePosition
import dev.aerove.lite.editor.EditorPalette
import dev.aerove.lite.editor.EditorPreferences
import dev.aerove.lite.editor.EditorQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun EditorScreen(
    session: ProfileEditorSession,
    onClose: () -> Unit,
    onSaved: () -> Unit = onClose,
    modifier: Modifier = Modifier,
    language: EditorLanguage = EditorLanguage.Yaml,
    preferences: EditorPreferences = EditorPreferences(),
    onPreferencesChange: (EditorPreferences) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestClose by rememberUpdatedState(onClose)
    val latestSaved by rememberUpdatedState(onSaved)
    var searching by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var queryText by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var replacement by rememberSaveable { mutableStateOf("") }
    var chosenPreferences by remember(session) { mutableStateOf(preferences) }
    var retry by remember { mutableIntStateOf(0) }
    var saveInFlight by remember(session) { mutableStateOf(false) }
    val sessionState by session.state.collectAsState()
    val saveCallback = remember { arrayOf<() -> Unit>({}) }
    val document = remember(session) {
        EditorDocumentController(context.applicationContext, scope, session.readOnly, session::contentChanged,
            save = { saveCallback[0]() }, find = { replace -> searching = true; replacing = replace && !session.readOnly }, maxBytes = session.maxBytes)
    }
    val documentState by document.state.collectAsState()
    val visuals = LocalLiteVisuals.current
    val palette = EditorPalette(visuals.dark,
        (if (visuals.material == MaterialTier.Solid) visuals.page else Color.Transparent).toArgb(),
        visuals.ink.toArgb(), visuals.secondaryInk.toArgb(), visuals.primary.toArgb())
    val blocked = sessionState.saving || documentState.busy || saveInFlight
    val ready = documentState.ready && !blocked
    val query = EditorQuery(queryText, matchCase, regex)
    val leave: () -> Unit = {
        if (!blocked) when (session.backDecision()) {
            EditorBackDecision.Close -> latestClose()
            EditorBackDecision.ConfirmChanges -> confirmLeave = true
            EditorBackDecision.Busy -> Unit
        }
    }

    val requestSave: () -> Unit = {
        if (!saveInFlight && !session.state.value.saving && !document.state.value.busy && documentState.ready && !session.readOnly) {
            saveInFlight = true
            document.lock(true)
            scope.launch {
                try {
                    if (session.save(document::readText)) { confirmLeave = false; latestSaved() }
                } finally { saveInFlight = false; document.lock(false) }
            }
        }
    }
    saveCallback[0] = requestSave
    LaunchedEffect(session) { session.open() }
    LaunchedEffect(preferences) { chosenPreferences = preferences }
    LaunchedEffect(sessionState.initialText, language, retry) {
        sessionState.initialText?.takeIf { !documentState.ready }?.let { text ->
            try { document.open(text, language, visuals.dark) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { document.error(failure) }
        }
    }
    LaunchedEffect(documentState.version, documentState.ready) {
        if (documentState.ready) { delay(250); session.reconcileDirty(document::readText) }
    }
    LaunchedEffect(visuals.dark, documentState.ready) {
        if (documentState.ready) {
            try { document.updateTheme(visuals.dark) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { document.error(failure) }
        }
    }
    LaunchedEffect(searching, query) {
        delay(120)
        document.search(if (searching) query else EditorQuery())
    }
    DisposableEffect(document) { onDispose { document.release() } }
    BackHandler(onBack = leave)

    Box(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            LitePane(Modifier.fillMaxWidth().statusBarsPadding(), corner = LiteCorner.None) {
                Row(Modifier.fillMaxWidth().height(LiteMetrics.PageToolbar).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteIconButton(stringResource(R.string.editor_back), FullGlyphs.arrowBack, leave, enabled = !blocked)
                    LiteText(session.title, Modifier.weight(1f), style = LiteType.Headline, maxLines = 1)
                    LiteIconButton(stringResource(R.string.editor_search), FullGlyphs.search, { searching = true }, enabled = ready)
                    if (!session.readOnly) LiteIconButton(stringResource(R.string.editor_save), FullGlyphs.check, requestSave,
                        enabled = ready && sessionState.dirty)
                    LiteIconButton(stringResource(R.string.editor_options), EditorGlyphs.more, { showMenu = true }, enabled = ready)
                }
            }
            if (searching) EditorFindPanel(query, { next -> queryText = next.text; matchCase = next.matchCase; regex = next.regex },
                replacement, { replacement = it }, replacing && !session.readOnly, session.readOnly, documentState, document,
                onToggleReplace = { replacing = !replacing }, onClose = { searching = false }, enabled = ready)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (documentState.ready) AndroidView(
                    factory = { document.createView() },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = session.title },
                    update = { document.style(palette, chosenPreferences); document.lock(blocked) },
                )
                else if (sessionState.error == null && documentState.error == null) {
                    LiteText(stringResource(R.string.editor_loading), Modifier.align(Alignment.Center), style = LiteType.Callout)
                }
                if ((documentState.selected || documentState.clipboardMenu) && ready && !searching) {
                    EditorClipboardPanel(document, session.readOnly, Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
                EditorTextLoupe(document, documentState.loupe)
                if (documentState.suggestions.isNotEmpty() && ready && !searching) {
                    LitePane(Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max = 420.dp).fillMaxWidth(), corner = LiteCorner.Control) {
                        LazyColumn(Modifier.heightIn(max = 200.dp), contentPadding = PaddingValues(6.dp)) {
                            items(documentState.suggestions) { suggestion ->
                                Row(Modifier.fillMaxWidth().clickable { document.complete(suggestion) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LiteText(suggestion.label, Modifier.weight(1f), style = LiteType.Callout)
                                    LiteText(suggestion.detail, style = LiteType.Caption, color = visuals.secondaryInk)
                                }
                            }
                        }
                    }
                }
            }
            if (blocked) LiteText(stringResource(if (sessionState.saving) R.string.editor_saving else R.string.editor_working),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = LiteType.Caption)
        }
        if (showMenu) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 96), onDismissRequest = { showMenu = false }, properties = PopupProperties(focusable = true)) {
            LiteOwnedPopupFocus()
            LitePane(Modifier.padding(12.dp).widthIn(min = 260.dp, max = 360.dp), corner = LiteCorner.Control) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorMenuRow(stringResource(R.string.editor_undo), EditorGlyphs.undo, documentState.canUndo) { document.undo(); showMenu = false }
                    EditorMenuRow(stringResource(R.string.editor_redo), EditorGlyphs.redo, documentState.canRedo) { document.redo(); showMenu = false }
                    EditorMenuRow(stringResource(R.string.editor_copy), FullGlyphs.copy) { document.copy(false); showMenu = false }
                    if (!session.readOnly) EditorMenuRow(stringResource(R.string.editor_paste), EditorGlyphs.paste) { document.paste(); showMenu = false }
                    EditorMenuRow(stringResource(R.string.editor_select_all), EditorGlyphs.selectAll) { document.selectAll(); showMenu = false }
                    LiteText(stringResource(R.string.editor_font_size), Modifier.padding(10.dp), style = LiteType.Label)
                    EditorFontSize.entries.forEach { size ->
                        val next = chosenPreferences.copy(fontSize = size)
                        LiteButton(stringResource(when (size) {
                            EditorFontSize.Standard -> R.string.editor_font_standard
                            EditorFontSize.Large -> R.string.editor_font_large
                            EditorFontSize.ExtraLarge -> R.string.editor_font_extra_large
                        }), { chosenPreferences = next; onPreferencesChange(next); showMenu = false }, Modifier.fillMaxWidth(), selected = chosenPreferences.fontSize == size)
                    }
                    LiteSwitch(chosenPreferences.lineWrap, stringResource(R.string.editor_line_wrap), { value ->
                        chosenPreferences = chosenPreferences.copy(lineWrap = value)
                        onPreferencesChange(chosenPreferences)
                    })
                }
            }
        }
        if (confirmLeave) LiteModal(session.title, { confirmLeave = false }) {
            LiteText(stringResource(R.string.editor_unsaved_changes))
            LiteButton(stringResource(R.string.editor_save), requestSave, Modifier.fillMaxWidth(), selected = true, enabled = !blocked)
            LiteButton(stringResource(R.string.editor_discard), { confirmLeave = false; latestClose() }, Modifier.fillMaxWidth(), destructive = true, enabled = !blocked)
            LiteButton(stringResource(R.string.editor_keep_editing), { confirmLeave = false }, Modifier.fillMaxWidth(), enabled = !blocked)
        }
        val error = sessionState.error ?: documentState.error
        if (error != null) LiteModal(stringResource(R.string.editor_error), { session.dismissError(); document.dismissError() }) {
            LiteText(error)
            if (!documentState.ready) LiteButton(stringResource(R.string.editor_retry), {
                session.dismissError(); document.dismissError()
                if (sessionState.initialText == null) scope.launch { session.open() } else retry++
            }, Modifier.fillMaxWidth(), selected = true)
            LiteButton(stringResource(R.string.editor_close), { session.dismissError(); document.dismissError() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EditorMenuRow(label: String, icon: LiteGlyph, enabled: Boolean = true, onClick: () -> Unit) {
    LiteButton(label, onClick, Modifier.fillMaxWidth(), icon = icon, enabled = enabled)
}

@Composable
private fun EditorClipboardPanel(document: EditorDocumentController, readOnly: Boolean, modifier: Modifier) {
    LitePane(modifier, corner = LiteCorner.Control) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!readOnly) LiteIconButton(stringResource(R.string.editor_cut), EditorGlyphs.cut, { document.copy(true) })
            LiteIconButton(stringResource(R.string.editor_copy), FullGlyphs.copy, { document.copy(false) })
            if (!readOnly) LiteIconButton(stringResource(R.string.editor_paste), EditorGlyphs.paste, document::paste)
            LiteIconButton(stringResource(R.string.editor_select_all), EditorGlyphs.selectAll, document::selectAll)
            LiteIconButton(stringResource(R.string.editor_close), FullGlyphs.close, document::dismissClipboardMenu)
        }
    }
}

@Composable
private fun EditorTextLoupe(document: EditorDocumentController, position: EditorLoupePosition?) {
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    var lastPosition by remember { mutableStateOf(EditorLoupePosition(0, 0)) }
    if (position != null) lastPosition = position
    val width = with(density) { 80.dp.toPx() }
    val height = with(density) { 47.5.dp.toPx() }
    val edge = with(density) { 10.dp.toPx() }
    val gap = with(density) { 26.dp.toPx() }
    val shape = rememberLiteShape(LiteCorner.Full)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val max = with(density) { maxWidth.toPx() }
        val target = IntOffset((lastPosition.x - width / 2).coerceIn(edge, maxOf(edge, max - width - edge)).toInt(),
            (lastPosition.y - height - gap).coerceAtLeast(0f).toInt())
        val offset by animateIntOffsetAsState(target, tween(if (LocalLiteReducedMotion.current) 0 else 45), label = "editorLoupeFollow")
        AnimatedVisibility(position != null, Modifier.offset { offset }, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Canvas(Modifier.size(80.dp, 47.5.dp).clip(shape).border(2.dp, visuals.primary, shape)) {
                drawRect(visuals.page)
                drawIntoCanvas { document.drawMagnified(it.nativeCanvas, size.width, size.height, lastPosition.x.toFloat(), lastPosition.y.toFloat()) }
            }
        }
    }
}

@Composable
private fun EditorFindPanel(
    query: EditorQuery, onQuery: (EditorQuery) -> Unit,
    replacement: String, onReplacement: (String) -> Unit,
    replacing: Boolean, readOnly: Boolean, state: EditorDocumentState, document: EditorDocumentController,
    onToggleReplace: () -> Unit, onClose: () -> Unit, enabled: Boolean,
) {
    LitePane(Modifier.fillMaxWidth(), corner = LiteCorner.None) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LiteField(query.text, { if (it.length <= 1024) onQuery(query.copy(text = it)) }, stringResource(R.string.editor_search), enabled = enabled)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiteText(stringResource(R.string.editor_matches, state.match, state.matches), style = LiteType.Caption)
                EditorToggle(stringResource(R.string.editor_match_case), EditorGlyphs.matchCase, query.matchCase, enabled) { onQuery(query.copy(matchCase = !query.matchCase)) }
                EditorToggle(stringResource(R.string.editor_regex), EditorGlyphs.regex, query.regex, enabled) { onQuery(query.copy(regex = !query.regex)) }
                LiteIconButton(stringResource(R.string.editor_previous_match), FullGlyphs.chevronUp, { document.nextMatch(true) }, enabled = enabled && state.matches > 0)
                LiteIconButton(stringResource(R.string.editor_next_match), FullGlyphs.chevronDown, { document.nextMatch() }, enabled = enabled && state.matches > 0)
                if (!readOnly) EditorToggle(stringResource(R.string.editor_replace), EditorGlyphs.swap, replacing, enabled, onToggleReplace)
                LiteIconButton(stringResource(R.string.editor_close), FullGlyphs.close, onClose)
            }
            if (replacing) {
                LiteField(replacement, { if (it.length <= 8192) onReplacement(it) }, stringResource(R.string.editor_replace), enabled = enabled)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteButton(stringResource(R.string.editor_replace), { document.replaceCurrent(replacement) }, Modifier.weight(1f), enabled = enabled && state.matches > 0)
                    LiteButton(stringResource(R.string.editor_replace_all), { document.replaceAll(replacement) }, Modifier.weight(1f), enabled = enabled && state.matches > 0)
                }
            }
        }
    }
}

@Composable
private fun EditorToggle(label: String, icon: LiteGlyph, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    LitePane(corner = LiteCorner.Full, selected = selected, modifier = Modifier.semantics { this.selected = selected }) {
        LiteIconButton(label, icon, onClick, enabled = enabled)
    }
}
