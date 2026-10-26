package dev.aerove.lite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.aerove.lite.optics.AeroveGlassQuality
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.optics.AeroveInkRole
import dev.aerove.lite.optics.AeroveInkStyle
import dev.aerove.lite.MaterialTier
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberLiteMotionValue(target: Float, spec: FiniteAnimationSpec<Float>): State<Float> {
    val moving = !LocalLiteReducedMotion.current && LocalLitePageActive.current
    val value = remember { Animatable(target) }
    LaunchedEffect(target, moving, spec) {
        if (moving && value.value != target) value.animateTo(target, spec) else value.snapTo(target)
    }
    return value.asState()
}

@Composable
internal fun Modifier.liteInk(source: Color, role: AeroveInkRole): Modifier {
    val visuals = LocalLiteVisuals.current
    if (!visuals.aurora || LocalLiteHighContrast.current || source.alpha < 0.70f) return this
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    fun sameRgb(other: Color) = source.red == other.red && source.green == other.green && source.blue == other.blue
    if (sameRgb(colors.error) || sameRgb(colors.onErrorContainer)) return this
    if (role != AeroveInkRole.Icon && visuals.material == MaterialTier.Solid && sameRgb(colors.onPrimary)) return this
    val peak = maxOf(source.red, source.green, source.blue)
    val saturation = if (peak == 0f) 0f else (peak - minOf(source.red, source.green, source.blue)) / peak
    if (!sameRgb(colors.onSurface) && !sameRgb(colors.onSurfaceVariant) && saturation >= 0.20f) return this
    val backdrop = LocalLiteInkBackdrop.current ?: return this
    val contrast = if (role == AeroveInkRole.Icon) 3.2f else maxOf(4.7f, LocalLiteInkContrast.current ?: 4.7f)
    val background = if (visuals.material == MaterialTier.Solid && sameRgb(colors.onPrimary)) colors.primary else Color.Transparent
    return liteInkLayer(backdrop, AeroveInkStyle(
        source = source.opticsColor(), dark = visuals.dark, role = role, contrast = contrast,
        quality = if (visuals.material == MaterialTier.Clear) AeroveGlassQuality.Ultra else AeroveGlassQuality.Standard,
        subtle = visuals.material == MaterialTier.Solid, contrastBackground = background.opticsColor(),
    ))
}

@Composable
internal fun LiteText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LiteType.Body,
    color: Color = LocalLiteVisuals.current.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    val role = when {
        style.fontSize.value >= 22 -> AeroveInkRole.Title
        style.fontSize.value <= 13 -> AeroveInkRole.Small
        (style.fontWeight?.weight ?: 400) >= 600 -> AeroveInkRole.Section
        else -> AeroveInkRole.Body
    }
    val hasEmoji = text.codePoints().anyMatch { it in 0x1F000..0x1FAFF || it in 0x2600..0x27BF }
    Text(text, if (hasEmoji) modifier else modifier.liteInk(color, role), color = color, style = style.copy(fontFamily = style.fontFamily ?: LocalLiteFontFamily.current), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun LiteIcon(
    image: LiteGlyph,
    modifier: Modifier = Modifier,
    color: Color = LocalLiteVisuals.current.ink,
    fill: Float = 0f,
    size: Dp = 24.dp,
) {
    GlyphDrawing(image, modifier.size(size).liteInk(color, AeroveInkRole.Icon), color, fill)
}

@Composable
internal fun LiteButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: LiteGlyph? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val visuals = LocalLiteVisuals.current
    val interaction = remember { MutableInteractionSource() }
    val foreground = when {
        destructive -> androidx.compose.material3.MaterialTheme.colorScheme.error
        selected -> visuals.primary
        else -> visuals.ink
    }
    LitePane(
        modifier = modifier.alpha(if (enabled) 1f else 0.48f)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        corner = LiteCorner.Control,
        selected = selected,
        interactionSource = interaction,
    ) {
        CompositionLocalProvider(LocalLiteInkContrast provides if (visuals.material == MaterialTier.Solid) null else 9f) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) LiteIcon(icon, color = foreground, fill = 1f)
            LiteText(label, style = LiteType.Label, color = foreground)
        }
        }
    }
}

@Composable
internal fun LiteIconButton(
    label: String,
    icon: LiteGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val header = LocalLiteHeaderProgress.current
    val interaction = remember { MutableInteractionSource() }
    val size = if (header != null) 44.dp else 36.dp
    val opacity = remember { Animatable(header?.value ?: 1f) }
    val moving = !LocalLiteReducedMotion.current && LocalLitePageActive.current
    LaunchedEffect(header, moving) {
        snapshotFlow { header?.value ?: 1f }.collectLatest { target ->
            if (moving) opacity.animateTo(target, tween(200, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
            else opacity.snapTo(target)
        }
    }
    Box(
        modifier.size(size).alpha(if (enabled) 1f else 0.38f)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        LitePane(Modifier.matchParentSize().graphicsLayer { alpha = opacity.value; clip = opacity.value != 1f }, corner = LiteCorner.Full,
            glassStyle = AeroveGlassStyle.lens(LocalLiteVisuals.current.dark), interactionSource = interaction) {}
        LiteIcon(
            icon,
            Modifier.align(Alignment.Center),
            color = if (destructive) androidx.compose.material3.MaterialTheme.colorScheme.error else LocalLiteVisuals.current.ink,
            size = if (header != null) 24.dp else 20.dp,
        )
    }
}

@Composable
internal fun <T> LiteChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val interaction = remember { MutableInteractionSource() }
            LitePane(
                Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                    .alpha(if (enabled) 1f else 0.48f)
                    .selectable(option == selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                corner = LiteCorner.Control,
                selected = option == selected,
                interactionSource = interaction,
            ) {
                LiteText(
                    label(option),
                    Modifier.align(Alignment.Center).padding(horizontal = 6.dp, vertical = 12.dp),
                    style = LiteType.Label,
                    color = if (option == selected) LocalLiteVisuals.current.primary else LocalLiteVisuals.current.ink,
                )
            }
        }
    }
}

@Composable
internal fun LiteSwitch(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val visuals = LocalLiteVisuals.current
    val interaction = remember { MutableInteractionSource() }
    val finish = AeroveGlassStyle.regular(visuals.dark).copy(blur = 0f)
    val inactive = if (visuals.material == MaterialTier.Frosted) 0.13f else 0.06f
    val trackTint = (if (checked) visuals.primary else visuals.ink).copy(alpha = if (!enabled) 0.03f else if (checked) 0.18f else inactive)
    val thumbTint = visuals.surface.copy(alpha = if (enabled) 0.08f else 0.03f)
    val position = rememberLiteMotionValue(if (checked) 1f else 0f, spring())
    Box(
        Modifier.size(60.dp, 48.dp).alpha(if (enabled || visuals.material != MaterialTier.Solid) 1f else 0.48f)
            .semantics { contentDescription = label }
            .toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        LitePane(Modifier.size(52.dp, 32.dp), corner = LiteCorner.Full, selected = checked, glassStyle = finish, glassTint = trackTint,
            interactionSource = interaction,
            solidColor = if (checked) visuals.primary else androidx.compose.ui.graphics.lerp(visuals.surface, visuals.ink, 0.12f)) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
                val travel = maxWidth - 32.dp
                Row(Modifier.offset { IntOffset(((travel * position.value + 4.dp).toPx()).roundToInt(), 4.dp.roundToPx()) }) {
                    LitePane(Modifier.size(24.dp), corner = LiteCorner.Full, selected = checked, glassStyle = finish, glassTint = thumbTint,
                        interactionSource = interaction,
                        solidColor = if (checked) Color.White else visuals.secondaryInk) {
                        if (visuals.material != dev.aerove.lite.MaterialTier.Solid) LiteIcon(if (checked) FullGlyphs.check else FullGlyphs.close, Modifier.align(Alignment.Center), visuals.ink.copy(alpha = if (enabled) 1f else 0.38f), size = 14.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: LiteGlyph? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
        ).defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) LiteIcon(icon, color = LocalLiteVisuals.current.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiteText(title, style = LiteType.Body)
            if (!subtitle.isNullOrBlank()) LiteText(subtitle, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        }
        trailing()
    }
}

@Composable
internal fun LiteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LiteText(label, style = LiteType.Label, color = LocalLiteVisuals.current.secondaryInk)
        LitePane(Modifier.fillMaxWidth(), corner = LiteCorner.Control) {
            val visuals = LocalLiteVisuals.current
            BasicTextField(
                value,
                onValueChange,
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(14.dp)
                    .semantics { contentDescription = label }.liteInk(visuals.ink, AeroveInkRole.Body),
                enabled = enabled,
                singleLine = singleLine,
                textStyle = LiteType.Body.copy(color = visuals.ink, fontFamily = LocalLiteFontFamily.current),
                cursorBrush = SolidColor(visuals.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType),
            )
        }
    }
}

@Composable
internal fun EmptyContent(title: String, detail: String, icon: LiteGlyph, action: (@Composable () -> Unit)? = null) {
    LitePane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiteIcon(icon, color = LocalLiteVisuals.current.primary)
            LiteText(title, style = LiteType.Headline)
            LiteText(detail, color = LocalLiteVisuals.current.secondaryInk)
            if (action != null) action()
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    LiteText(text, modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
}

internal fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = safe.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "$safe ${units[unit]}" else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

internal fun formatTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
