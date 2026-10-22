package dev.aerove.lite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aerove.lite.MaterialTier
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Modifier.liteInk(): Modifier {
    val visuals = LocalLiteVisuals.current
    if (!visuals.aurora || visuals.material == MaterialTier.Solid) return this
    val backdrop = LocalLiteBackdrop.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }.drawWithCache {
        val bounds = Rect(Offset.Zero, size)
        val overlay = Paint().apply { alpha = if (visuals.dark) 0.22f else 0.18f }
        val mask = Paint().apply { blendMode = BlendMode.DstIn }
        onDrawWithContent {
            drawContent()
            val position = coordinates
            if (position != null && position.isAttached) {
                val canvas = drawContext.canvas
                canvas.saveLayer(bounds, overlay)
                with(backdrop) { drawBackdrop(this@onDrawWithContent, position) }
                canvas.saveLayer(bounds, mask)
                drawContent()
                canvas.restore()
                canvas.restore()
            }
        }
    }
}

@Composable
internal fun LiteText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LiteType.Body,
    color: Color = LocalLiteVisuals.current.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text, modifier.liteInk(), color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun LiteIcon(
    image: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = LocalLiteVisuals.current.ink,
) {
    Icon(image, contentDescription = null, modifier = modifier.size(22.dp).liteInk(), tint = color)
}

@Composable
internal fun LiteButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val visuals = LocalLiteVisuals.current
    val foreground = when {
        destructive -> androidx.compose.material3.MaterialTheme.colorScheme.error
        selected -> visuals.primary
        else -> visuals.ink
    }
    LitePane(
        modifier = modifier.alpha(if (enabled) 1f else 0.48f)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        corner = LiteCorner.Control,
        selected = selected,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) LiteIcon(icon, color = foreground)
            LiteText(label, style = LiteType.Label, color = foreground)
        }
    }
}

@Composable
internal fun LiteIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    LitePane(
        modifier.size(48.dp).alpha(if (enabled) 1f else 0.48f)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        corner = LiteCorner.Control,
    ) {
        LiteIcon(
            icon,
            Modifier.align(Alignment.Center),
            color = if (destructive) androidx.compose.material3.MaterialTheme.colorScheme.error else LocalLiteVisuals.current.ink,
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
            LitePane(
                Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                    .alpha(if (enabled) 1f else 0.48f)
                    .selectable(option == selected, enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                corner = LiteCorner.Control,
                selected = option == selected,
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
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "switchPosition")
    Box(
        Modifier.size(60.dp, 48.dp).alpha(if (enabled) 1f else 0.48f)
            .semantics { contentDescription = label }
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        LitePane(Modifier.size(56.dp, 32.dp), corner = LiteCorner.Full, selected = checked) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
                val offset = (maxWidth - 32.dp) * position + 3.dp
                Row(Modifier.padding(start = offset, top = 3.dp)) {
                    LitePane(Modifier.size(26.dp), corner = LiteCorner.Full, selected = checked) {
                        if (checked) LiteIcon(Icons.Outlined.Check, Modifier.size(16.dp).align(Alignment.Center), LocalLiteVisuals.current.primary)
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
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
        ).padding(horizontal = 16.dp, vertical = 12.dp),
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
                    .semantics { contentDescription = label }.liteInk(),
                enabled = enabled,
                singleLine = singleLine,
                textStyle = LiteType.Body.copy(color = visuals.ink),
                cursorBrush = SolidColor(visuals.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType),
            )
        }
    }
}

@Composable
internal fun EmptyContent(title: String, detail: String, icon: ImageVector, action: (@Composable () -> Unit)? = null) {
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
    LiteText(text, modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), style = LiteType.Label, color = LocalLiteVisuals.current.secondaryInk)
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
