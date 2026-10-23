package dev.aerove.lite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// Flutter's ThreePointCubic easeInOutCubicEmphasized, including its actual midpoint.
private val NavigationIndicatorEasing = Easing { t ->
    val x = 0.166666f; val y = 0.4f
    if (t < x) CubicBezierEasing(0.05f / x, 0f, 0.133333f / x, 0.06f / y).transform(t / x) * y
    else CubicBezierEasing((0.208333f - x) / (1 - x), (0.82f - y) / (1 - y),
        (0.25f - x) / (1 - x), 1f).transform((t - x) / (1 - x)) * (1 - y) + y
}

@Composable
private fun navigationFraction(selected: Boolean, moving: Boolean, millis: Int, full: Boolean = false): State<Float> {
    val fraction = remember { Animatable(if (selected) 1f else 0f) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(selected, moving) {
        val target = if (selected) 1f else 0f
        if (!initialized || !moving) { initialized = true; fraction.snapTo(target) }
        else {
            if (full) fraction.snapTo(1f - target)
            fraction.animateTo(target, tween(if (full) millis else (millis * abs(target - fraction.value)).roundToInt(), easing = LinearEasing))
        }
    }
    return fraction.asState()
}

@Composable
internal fun LiteDockedNavigationBar(items: List<MainTab>, selected: MainTab, bottomInset: Dp,
    onSelected: (MainTab) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val background = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
    val indicator = if (visuals.dark) colors.surfaceContainerHighest else colors.surfaceContainer
    val moving = enabled && !LocalLiteReducedMotion.current
    Column(modifier.focusProperties { canFocus = enabled }.then(if (enabled) Modifier else Modifier.clearAndSetSemantics {}).fillMaxWidth().testTag("docked-navigation").background(background).padding(bottom = bottomInset)) {
        Row(Modifier.fillMaxWidth().height(80.dp).selectableGroup()) {
            items.forEach { item -> key(item) {
                val chosen = selected == item
                val selection by navigationFraction(chosen, moving, 500)
                val indicatorAlpha by navigationFraction(chosen, moving, 100, full = true)
                val glyph by navigationFraction(chosen, moving, 200)
                Column(Modifier.weight(1f).fillMaxHeight().selectable(chosen, enabled = enabled, role = Role.Tab,
                    onClick = { if (!chosen) onSelected(item) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(64.dp, 32.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.matchParentSize().graphicsLayer {
                            scaleX = if (selection == 0f) 0f else 0.4f + 0.6f * NavigationIndicatorEasing.transform(selection)
                            alpha = indicatorAlpha
                        }.background(indicator, rememberLiteShape(LiteCorner.Full)))
                        LiteIcon(item.icon, color = lerp(visuals.secondaryInk, visuals.primary, selection), fill = glyph, size = 24.dp)
                    }
                    LiteText(stringResource(item.label), Modifier.padding(top = 4.dp),
                        style = LiteType.Caption.copy(fontWeight = FontWeight.SemiBold),
                        color = lerp(visuals.secondaryInk, visuals.ink, selection), maxLines = 1)
                }
            } }
        }
    }
}
