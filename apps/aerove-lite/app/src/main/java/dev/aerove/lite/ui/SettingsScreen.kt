package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.ColorMode
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.InstalledApp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    val enabled = state.busy.isEmpty()
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionLabel(stringResource(R.string.lite_appearance)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(R.string.lite_material), style = LiteType.Headline)
                    LiteChoiceRow(
                        MaterialTier.entries, state.settings.material, { materialLabel(it) },
                        { actions.changeSettings(state.settings.copy(material = it)) }, enabled = enabled,
                    )
                    LiteText(stringResource(R.string.lite_color_mode), style = LiteType.Label)
                    LiteChoiceRow(
                        ColorMode.entries, state.settings.colorMode,
                        { stringResource(when (it) { ColorMode.System -> R.string.lite_color_system; ColorMode.Light -> R.string.lite_color_light; ColorMode.Dark -> R.string.lite_color_dark }) },
                        { actions.changeSettings(state.settings.copy(colorMode = it)) }, enabled = enabled,
                    )
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(R.string.lite_accent), style = LiteType.Headline)
                    LiteChoiceRow(listOf(false, true), state.settings.aurora,
                        { stringResource(if (it) R.string.lite_aurora else R.string.lite_normal_theme) },
                        { actions.changeSettings(state.settings.copy(aurora = it)) }, enabled = enabled)
                }
            }
        }
        if (state.settings.aurora) item { AuroraSettings(state, actions) }
        else {
            item { ThemePresetControls(state, actions, enabled) }
            item { ColorStyleSetting(state, actions) }
        }
    }
}

private fun LiteSettings.themeSelection() = ThemePresetSelection(themeColors,
    if (followSystemColor) null else themeColor, colorStyle)

private fun LiteSettings.withThemeSelection(value: ThemePresetSelection) = copy(
    themeColors = value.colors, themeColor = value.selected ?: themeColor,
    followSystemColor = value.selected == null, colorStyle = value.style,
)

@Composable
private fun ThemePresetControls(state: LiteState, actions: LiteActions, enabled: Boolean) {
    val overlay = LocalLiteOverlay.current
    val current by rememberUpdatedState(state)
    val selection = state.settings.themeSelection()
    val visuals = LocalLiteVisuals.current
    val systemColor = rememberSystemThemeColor(visuals.dark)
    fun save(value: ThemePresetSelection) {
        if (!current.settings.aurora && value != current.settings.themeSelection())
            actions.changeSettings(current.settings.withThemeSelection(value))
    }
    fun edit(original: Long?) {
        val initial = original ?: current.settings.themeSelection().selected ?: systemColor
        overlay.content = {
            ThemeColorSheet(initial, current.settings.colorStyle, overlay::dismiss) { color ->
                overlay.dismiss()
                val latest = current.settings.themeSelection()
                save(if (original == null) latest.add(color) else latest.edit(original, color))
            }
        }
    }
    fun confirmDelete(color: Long) {
        overlay.content = {
            LiteModal(stringResource(R.string.lite_theme_delete_confirmation), overlay::dismiss) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteButton(stringResource(R.string.lite_cancel), overlay::dismiss, Modifier.weight(1f))
                    LiteButton(stringResource(R.string.lite_delete), {
                        save(current.settings.themeSelection().delete(color)); overlay.dismiss()
                    }, Modifier.weight(1f), destructive = true)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selection != ThemePresetSelection()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LiteIconButton(stringResource(R.string.lite_reset), ThemeEditorGlyphs.reset, {
                overlay.content = {
                    LiteModal(stringResource(R.string.lite_theme_reset_confirmation), overlay::dismiss) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiteButton(stringResource(R.string.lite_cancel), overlay::dismiss, Modifier.weight(1f))
                            LiteButton(stringResource(R.string.lite_reset), { save(ThemePresetSelection()); overlay.dismiss() }, Modifier.weight(1f), destructive = true)
                        }
                    }
                }
            }, enabled = enabled)
        }
        LitePane(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp)) {
                val columns = maxOf(1, ((maxWidth.value + 6) / 50).toInt())
                val gap = if (columns > 1) ((maxWidth.value - columns * 44) / (columns - 1)).toInt().dp else 0.dp
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (color in listOf<Long?>(null) + selection.colors) {
                        var anchor by remember(color) { mutableStateOf(Rect.Zero) }
                        ThemeColorSwatch(color ?: systemColor, color == null, selection.selected == color, enabled,
                            Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
                            onSelect = { save(current.settings.themeSelection().select(color)) },
                            onLongClick = if (color == null) null else ({
                                overlay.content = { ThemeColorMenu(anchor, overlay::dismiss, { edit(color) }, { confirmDelete(color) }) }
                            }))
                    }
                    LiteIconButton(stringResource(R.string.lite_add_color), FullGlyphs.add, { edit(null) }, Modifier.size(44.dp), enabled)
                }
            }
        }
    }
}

@Composable
private fun ThemeColorSwatch(
    color: Long, system: Boolean, selected: Boolean, enabled: Boolean, modifier: Modifier,
    onSelect: () -> Unit, onLongClick: (() -> Unit)?,
) {
    val shape = rememberLiteShape(LiteCorner.Full)
    val fill = Color(color.toInt())
    val label = if (system) stringResource(R.string.lite_follow_system) else "#${themeColorHex(color)}"
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val duration = if (LocalLiteReducedMotion.current) 0 else 200
    val ring by animateFloatAsState(if (selected || focused) 1f else 0f, tween(duration, easing = LinearEasing), label = "themeSwatchRing")
    val mark by animateFloatAsState(if (selected) 1f else 0f, tween(duration, easing = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)), label = "themeSwatchCheck")
    val ringColor = if (selected) fill else LocalLiteVisuals.current.primary
    val check = if ((fill.luminance() + 0.05f) * (fill.luminance() + 0.05f) > 0.15f) Color.Black else Color.White
    Box(modifier.size(44.dp).testTag(if (system) "theme-system-color" else "theme-color-${themeColorHex(color)}")
        .semantics { this.selected = selected; contentDescription = label }
        .clip(shape).combinedClickable(interactionSource = interaction, indication = null,
            enabled = enabled, role = Role.Button, onClick = onSelect, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().border(2.5.dp, ringColor.copy(alpha = ring), shape))
        Box(Modifier.size(32.dp).background(fill, shape))
        LiteIcon(FullGlyphs.check, Modifier.graphicsLayer { scaleX = mark; scaleY = mark }, check, fill = 1f, size = 18.dp)
        if (system) LitePane(Modifier.align(Alignment.BottomEnd).size(20.dp), corner = LiteCorner.Full) {
            LiteIcon(ThemeEditorGlyphs.eyedropper, Modifier.align(Alignment.Center), size = 12.dp)
        }
    }
}

@Composable
private fun ThemeColorMenu(anchor: Rect, dismiss: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    BackHandler(onBack = dismiss)
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.32f)).clickable(onClick = dismiss)) {
        val width = minOf(260.dp, maxWidth - 32.dp)
        val left = with(density) { anchor.left.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val preferred = with(density) { anchor.bottom.toDp() } + 8.dp
        val top = preferred.coerceIn(16.dp, maxOf(16.dp, maxHeight - 128.dp))
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.offset(left, top).width(width).pointerInput(Unit) { detectTapGestures {} }, corner = LiteCorner.Control,
            solidColor = fill, glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column {
                SettingsRow(stringResource(R.string.lite_edit), icon = ThemeEditorGlyphs.edit, onClick = edit)
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = delete).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteIcon(FullGlyphs.delete, color = colors.error)
                    LiteText(stringResource(R.string.lite_delete), color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun ThemeColorSheet(initial: Long, style: ColorStyle, dismiss: () -> Unit, confirm: (Long) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var hex by remember { mutableStateOf(themeColorHex(initial)) }
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Long?>(null) }
    val reduced = LocalLiteReducedMotion.current
    fun finish(value: Long?) { if (!closing) { result = value; closing = true } }
    val progress by animateFloatAsState(if (entered && !closing) 1f else 0f, tween(if (reduced) 0 else 260), label = "customColorSheet")
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(closing) {
        if (closing) { if (!reduced) delay(260); val color = result; if (color == null) dismiss() else confirm(color) }
    }
    BackHandler { finish(null) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * progress))
            .clickable(enabled = !closing) { finish(null) })
        LitePane(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * 0.9f)
            .graphicsLayer { translationY = (1 - progress) * size.height }.pointerInput(Unit) { detectTapGestures {} },
            corner = LiteCorner.Sheet) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().height(LiteMetrics.PageToolbar).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiteIconButton(stringResource(R.string.lite_cancel), FullGlyphs.close, { finish(null) }, enabled = !closing)
                    LiteText(stringResource(R.string.lite_custom_color), Modifier.weight(1f), style = LiteType.Title)
                    LiteIconButton(stringResource(R.string.lite_confirm), FullGlyphs.check, { finish(draft) }, enabled = !closing)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LitePane(Modifier.fillMaxWidth(), solidColor = androidx.compose.material3.MaterialTheme.colorScheme.fullBlock(LocalLiteVisuals.current.dark)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ThemeColorPreview(draft, style, false)
                            ThemeColorPreview(draft, style, true)
                            ThemeHexField(hex, { text ->
                                hex = text.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(6).uppercase()
                                parseThemeColorHex(hex)?.let { draft = it }
                            }, Modifier.weight(1f), enabled = !closing)
                        }
                    }
                    FullColorPalette(draft.toInt()) { value -> draft = value.toLong() and 0xffffffffL; hex = themeColorHex(draft) }
                }
            }
        }
    }
}

@Composable
private fun ThemeHexField(value: String, onChanged: (String) -> Unit, modifier: Modifier, enabled: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val visuals = LocalLiteVisuals.current
    val shape = rememberLiteShape(LiteCorner.Control)
    val label = stringResource(R.string.lite_hex)
    LitePane(modifier.then(if (focused) Modifier.border(2.dp, visuals.primary, shape) else Modifier), corner = LiteCorner.Control,
        solidColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LiteText("#", color = visuals.secondaryInk)
            BasicTextField(value, onChanged, Modifier.weight(1f).semantics { contentDescription = label }, enabled = enabled,
                singleLine = true, textStyle = LiteType.Body.copy(color = visuals.ink, fontFeatureSettings = "tnum"),
                cursorBrush = SolidColor(visuals.primary), interactionSource = interaction,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false))
        }
    }
}

@Composable
private fun ThemeColorPreview(seed: Long, style: ColorStyle, dark: Boolean) {
    val colors = remember(seed, style, dark) { fullColorScheme(seed.toInt(), dark, false, style) }
    val shape = rememberLiteShape(LiteCorner.Control)
    val visuals = LocalLiteVisuals.current
    val source = rememberLayerBackdrop()
    val miniature = visuals.copy(aurora = false, dark = dark, primary = colors.primary, page = colors.fullPage(dark),
        surface = colors.fullCard(dark), ink = colors.onSurface, secondaryInk = colors.onSurfaceVariant)
    val glass = dev.aerove.lite.optics.AeroveGlassStyle.pill(dark).let {
        val scale = 72f / 400
        it.copy(blur = it.blur * scale, hazeBlur = it.hazeBlur * scale, refraction = it.refraction * scale,
            bevel = it.bevel * scale, rimWidth = it.rimWidth * scale)
    }
    Box(Modifier.size(72.dp, 96.dp).clip(shape)) {
        Box(Modifier.matchParentSize().layerBackdrop(source).drawWithCache {
            val unit = size.width / 20
            val margin = unit * 1.5f
            val width = 17.0
            val previewDensity = Density(size.width / 96)
            val paints = mutableListOf<ThemePreviewPaint>()
            fun path(rect: Rect, corner: Float): Path =
                (LiteSuperellipse(corner.dp).createOutline(rect.size, LayoutDirection.Ltr, previewDensity) as Outline.Generic).path.apply { translate(rect.topLeft) }
            fun rect(x: Double, y: Double, w: Double, h: Double) = Rect(margin + x.toFloat() * unit, margin + y.toFloat() * unit,
                margin + (x + w).toFloat() * unit, margin + (y + h).toFloat() * unit)
            fun fit(extent: Double): Float = listOf(0f, 4f, 8f, 16f, 20f, 24f, 28f).last { it <= 4.8 * extent / 3 }
            fun block(rect: Rect, color: Color, extent: Double = 5.0) { paints += ThemePreviewPaint(path(rect, fit(extent)), color) }
            fun pill(x: Double, y: Double, w: Double, h: Double, color: Color) { paints += ThemePreviewPaint(path(rect(x,y,w,h), 1000f), color) }
            fun dot(x: Double, y: Double, radius: Double, color: Color) = pill(x-radius,y-radius,radius*2,radius*2,color)
            val card = colors.fullCard(dark)
            val inset = colors.surfaceContainerHighest
            val text = colors.onSurface.copy(alpha = 0.72f)
            val line = colors.onSurfaceVariant.copy(alpha = 0.4f)
            pill(0.0,0.45,7.0,1.1,text)
            dot(width-1,1.0,1.0,card); dot(width-1,1.0,0.3,line)
            var y = 2.8
            val status = rect(0.0,y,width,6.2)
            val statusPath = path(status,fit(5.0))
            block(status,card)
            val buttonX = width-3
            val buttonY = y+3.1
            paints += ThemePreviewPaint(Path().apply { addOval(rect(buttonX-4.6,buttonY-4.6,9.2,9.2)) }, colors.primary.copy(alpha = 0.1f), statusPath)
            dot(buttonX,buttonY,1.8,colors.primary)
            for (dx in listOf(-0.675,0.325)) pill(buttonX+dx,buttonY-0.6,0.35,1.2,colors.onPrimary)
            pill(1.0,y+0.9,3.0,0.7,line); dot(1.9,y+3,0.9,inset)
            pill(3.5,y+2.3,5.0,0.9,text); pill(3.5,y+3.5,2.4,0.7,inset)
            pill(1.0,y+4.8,2.4,0.7,line); pill(4.6,y+4.8,3.6,0.7,line)
            y += 6.8
            block(rect(0.0,y,width,5.8),card); pill(1.0,y+0.9,4.5,0.7,line)
            val tileWidth = (width-2.6)/2
            repeat(2) { index ->
                val x = 1+index*(tileWidth+0.6)
                val tile = rect(x,y+2.1,tileWidth,2.9)
                val tilePath = path(tile,fit(2.9))
                block(tile,inset,2.9); pill(x+0.6,y+2.65,2.4,0.7,line)
                val graph = Path()
                repeat(17) { i ->
                    val px = tile.left + tile.width * i / 16
                    val py = tile.top + tile.height * 0.68f + (kotlin.math.sin(i*0.9+index*1.7)*unit*0.35).toFloat()
                    if (i == 0) graph.moveTo(px,py) else graph.lineTo(px,py)
                }
                val color = colors.primary.copy(alpha = if (index == 0) 0.5f else 1f)
                paints += ThemePreviewPaint(Path().apply { addPath(graph); lineTo(tile.right,tile.bottom); lineTo(tile.left,tile.bottom); close() }, color.copy(alpha = color.alpha*0.2f), tilePath)
                paints += ThemePreviewPaint(graph,color,tilePath,unit*0.15f)
            }
            y += 6.4
            block(rect(0.0,y,width,4.4),card); pill(1.0,y+0.9,4.0,0.7,line)
            val segment = (width-2.6)/3
            repeat(3) { index ->
                val x = 1+index*(segment+0.3)
                pill(x,y+2.1,segment,1.6,if (index == 0) colors.primary else inset)
                dot(x+segment/2,y+2.9,0.3,if (index == 0) colors.onPrimary else line)
            }
            y += 5.0
            block(rect(0.0,y,width,3.0),card); dot(1.9,y+1.5,0.9,colors.primary)
            pill(3.4,y+1.1,2.4,0.8,line); pill(width-3.6,y+0.8,2.6,1.4,colors.primary)
            dot(width-1.7,y+1.5,0.55,colors.onPrimary)
            y += 3.6
            repeat(3) { index -> block(rect(0.0,y,width,4.5),card); pill(1.0,y+0.9,3.5+index%2,0.7,line); y += 5.1 }
            onDrawBehind {
                drawRect(colors.fullPage(dark))
                clipRect(margin,margin,size.width-margin,size.height) {
                    paints.forEach { paint ->
                        fun draw() { if (paint.stroke > 0) drawPath(paint.path,paint.color,style = Stroke(paint.stroke)) else drawPath(paint.path,paint.color) }
                        val clip = paint.clip
                        if (clip == null) draw() else clipPath(clip) { draw() }
                    }
                }
            }
        })
        CompositionLocalProvider(LocalLiteVisuals provides miniature, LocalLiteBackdrop provides source) {
            LitePane(Modifier.offset(5.4.dp,79.08.dp).size(61.2.dp,11.52.dp), corner = LiteCorner.Full,
                solidColor = if (dark) colors.fullBlock(true) else colors.fullCard(false), glassStyle = glass,
                shadows = listOf(LiteShadow(0.12f,1.08f,(4.8f*0.57735f+0.5f)*0.75f))) {
                Box(Modifier.fillMaxSize().drawWithCache {
                    val unit = size.width / 17
                    val cell = size.width / 4
                    val fills = mutableListOf<Pair<Path,Color>>()
                    fun fill(rect: Rect, color: Color) {
                        fills += (LiteSuperellipse(LiteCorner.Full).createOutline(rect.size,LayoutDirection.Ltr,this) as Outline.Generic).path.apply { translate(rect.topLeft) } to color
                    }
                    repeat(4) { index ->
                        val center = Offset(cell*(index+0.5f),size.height/2)
                        val ink = if (index == 0) colors.primary else colors.onSurfaceVariant
                        if (index == 0) {
                            val indicator = if (visuals.material == MaterialTier.Solid) {
                                if (dark) colors.surfaceContainerHighest else colors.fullBlock(false)
                            } else colors.onSurface.copy(alpha = if (dark) 0.14f else 0.08f)
                            fill(Rect(center.x-(cell-unit*0.5f)/2,unit*0.25f,center.x+(cell-unit*0.5f)/2,size.height-unit*0.25f),indicator)
                        }
                        val icon = center.copy(y = center.y-unit*0.4f)
                        fill(Rect(icon-Offset(unit*0.45f,unit*0.45f),icon+Offset(unit*0.45f,unit*0.45f)),ink)
                        fill(Rect(center.x-unit*0.85f,center.y+unit*0.625f,center.x+unit*0.85f,center.y+unit*0.975f),if (index == 0) ink else ink.copy(alpha = 0.6f))
                    }
                    onDrawBehind { fills.forEach { (path,color) -> drawPath(path,color) } }
                })
            }
        }
    }
}

private data class ThemePreviewPaint(val path: Path, val color: Color, val clip: Path? = null, val stroke: Float = 0f)

private object ThemeEditorGlyphs {
    val reset = LiteGlyph(listOf(
        glyphArc(12, 13.77, 7.07, kotlin.math.PI * 1.15, kotlin.math.PI * -1.65, role = LiteGlyphRole.Line),
        glyphPath("M12 6.7H9.9", role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(13.7, 10.5), vertex(9.9, 6.7, 0.4), vertex(13.7, 2.9)), role = LiteGlyphRole.Line),
    ))
    val edit = LiteGlyph(listOf(
        glyphPolyline(listOf(vertex(4, 20, 1), vertex(8.75, 18.75, 1), vertex(20.25, 7.25, 1.5), vertex(16.75, 3.75, 1.5), vertex(5.25, 15.25, 1)), closed = true),
        glyphPath("M13.25 7.25L16.75 10.75", role = LiteGlyphRole.Detail),
    ))
    val eyedropper = LiteGlyph(listOf(glyphPolyline(listOf(
        vertex(4.79, 19.21, 0.9), vertex(8.46, 18.08, 1.2), vertex(14.4, 12.14, 0.5), vertex(16.24, 13.98, 1),
        vertex(18.79, 11.43, 1), vertex(17.73, 10.37, 0.5), vertex(21.4, 6.7, 2.9), vertex(17.3, 2.6, 2.9),
        vertex(13.63, 6.27, 0.5), vertex(12.57, 5.21, 1), vertex(10.02, 7.76, 1), vertex(11.86, 9.6, 0.5), vertex(5.92, 15.54, 1.2),
    ), closed = true)))
}

@Composable
internal fun GeneralScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_detail)) {
                    LiteSwitch(state.settings.autoCheckUpdate, stringResource(R.string.update_auto), {
                        actions.changeSettings(state.settings.copy(autoCheckUpdate = it))
                    }, state.busy.isEmpty())
                }
            }
        }
        item { SectionLabel(stringResource(R.string.lite_background)) }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_auto_start), stringResource(R.string.lite_auto_start_detail)) {
                    LiteSwitch(state.settings.autoStart, stringResource(R.string.lite_auto_start), { actions.changeSettings(state.settings.copy(autoStart = it)) }, state.busy.isEmpty())
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_open_logs), stringResource(R.string.lite_open_logs_detail)) {
                    LiteSwitch(state.settings.openLogs, stringResource(R.string.lite_open_logs), { actions.changeSettings(state.settings.copy(openLogs = it)) }, state.busy.isEmpty())
                }
            }
        }
    }
}

@Composable
internal fun DnsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var servers by rememberSaveable(state.settings.dnsServers) { mutableStateOf(state.settings.dnsServers.joinToString("\n")) }
    var customDns by rememberSaveable(state.settings.overrideDns) { mutableStateOf(state.settings.overrideDns) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val entries = servers.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_dns_custom), if (customDns) null else stringResource(R.string.lite_dns_follow_profile)) {
                    LiteSwitch(customDns, stringResource(R.string.lite_dns_custom), {
                        attempted = false
                        if (!it && state.settings.overrideDns) actions.changeSettings(state.settings.copy(overrideDns = false))
                        else customDns = it
                    }, state.busy.isEmpty())
                }
            }
        }
        if (customDns) {
            item {
                LitePane(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiteField(servers, { servers = it }, stringResource(R.string.lite_dns_servers), singleLine = false, enabled = state.busy.isEmpty())
                        if (attempted && entries.isEmpty()) LiteText(stringResource(R.string.lite_dns_empty), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        LiteButton(
                            stringResource(R.string.lite_save),
                            {
                                attempted = true
                                if (entries.isNotEmpty()) actions.changeSettings(state.settings.copy(overrideDns = true, dnsServers = entries))
                            }, Modifier.fillMaxWidth(), selected = true, enabled = state.busy.isEmpty() && (!state.settings.overrideDns || entries != state.settings.dnsServers),
                        )
                    }
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.lite_ipv6), stringResource(R.string.lite_ipv6_detail)) {
                    LiteSwitch(state.settings.ipv6, stringResource(R.string.lite_ipv6), { actions.changeSettings(state.settings.copy(ipv6 = it)) }, state.busy.isEmpty())
                }
            }
        }
        item { LiteText(stringResource(R.string.lite_settings_saved_notice), Modifier.padding(4.dp), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk) }
    }
}

@Composable
internal fun AppFilterScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var draftMode by rememberSaveable(state.settings.appFilterMode) { mutableStateOf(state.settings.appFilterMode) }
    var draftPackages by rememberSaveable(state.settings.packages) { mutableStateOf(state.settings.packages.toList()) }
    val dirty = draftMode != state.settings.appFilterMode || draftPackages.toSet() != state.settings.packages
    val valid = draftMode != AppFilterMode.Include || draftPackages.isNotEmpty()
    LaunchedEffect(Unit) { actions.loadInstalledApps() }
    val matching by produceState<List<InstalledApp>>(emptyList(), state.installedApps, query) {
        value = withContext(Dispatchers.Default) {
            state.installedApps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
        }
    }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteChoiceRow(
                        AppFilterMode.entries, draftMode, { appFilterLabel(it) },
                        { draftMode = it }, enabled = state.busy.isEmpty(),
                    )
                    LiteText(
                        stringResource(when (draftMode) { AppFilterMode.All -> R.string.lite_apps_all_detail; AppFilterMode.Include -> R.string.lite_apps_include_detail; AppFilterMode.Exclude -> R.string.lite_apps_exclude_detail }),
                        style = LiteType.Label, color = LocalLiteVisuals.current.secondaryInk,
                    )
                    if (!valid) LiteText(stringResource(R.string.lite_apps_include_empty), style = LiteType.Caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiteButton(stringResource(R.string.lite_cancel), {
                            draftMode = state.settings.appFilterMode
                            draftPackages = state.settings.packages.toList()
                        }, Modifier.weight(1f), enabled = dirty && state.busy.isEmpty())
                        LiteButton(stringResource(R.string.lite_apply), {
                            actions.changeSettings(state.settings.copy(appFilterMode = draftMode, packages = draftPackages.toSet()))
                        }, Modifier.weight(1f), selected = true, enabled = dirty && valid && state.busy.isEmpty())
                    }
                }
            }
        }
        if (draftMode != AppFilterMode.All) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteField(query, { query = it }, stringResource(R.string.lite_search), Modifier.weight(1f))
                    LiteIconButton(stringResource(R.string.lite_refresh), FullGlyphs.refresh, actions::loadInstalledApps, enabled = state.busy.isEmpty())
                }
            }
            item { LiteText(stringResource(R.string.lite_apps_selected, draftPackages.size), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk) }
            if (matching.isEmpty()) item {
                LiteText(stringResource(if (state.busy.isNotEmpty()) R.string.lite_busy else R.string.lite_apps_empty), Modifier.padding(16.dp))
            }
            items(matching, key = { it.packageName }) { app ->
                val checked = app.packageName in draftPackages
                LitePane(
                    Modifier.fillMaxWidth().toggleable(checked, enabled = state.busy.isEmpty(), role = Role.Checkbox) {
                        draftPackages = if (it) (draftPackages + app.packageName).distinct() else draftPackages - app.packageName
                    },
                    selected = checked,
                ) {
                    SettingsRow(app.label, app.packageName) {
                        if (checked) LiteIcon(FullGlyphs.check, color = LocalLiteVisuals.current.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun materialLabel(tier: MaterialTier): String = stringResource(when (tier) {
    MaterialTier.Solid -> R.string.lite_solid
    MaterialTier.Frosted -> R.string.lite_frosted
    MaterialTier.Clear -> R.string.lite_clear
})

@Composable
private fun appFilterLabel(mode: AppFilterMode): String = stringResource(when (mode) {
    AppFilterMode.All -> R.string.lite_apps_all
    AppFilterMode.Include -> R.string.lite_apps_include
    AppFilterMode.Exclude -> R.string.lite_apps_exclude
})
