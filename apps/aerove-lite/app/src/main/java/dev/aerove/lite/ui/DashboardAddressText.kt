package dev.aerove.lite.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocaleList
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import dev.aerove.lite.optics.AeroveInkRole
import kotlin.math.ceil

internal data class DashboardAddressPlan(val shown: String, val scale: Float, val wrap: Boolean)

/** Mirrors Standard's DashboardFitText: scale down first, then break complete addresses at separators. */
internal fun dashboardAddressPlan(address: String, naturalWidth: Float, availableWidth: Int,
    scaledFontSize: Float): DashboardAddressPlan {
    val floor = minOf(1f, 11f / scaledFontSize)
    val room = availableWidth.coerceAtLeast(0)
    if (naturalWidth * floor <= room) {
        return DashboardAddressPlan(address, if (naturalWidth <= room) 1f else room / naturalWidth, false)
    }
    val shown = buildString {
        address.forEach { character ->
            append(character)
            if (character == '.' || character == ':') append('\u200B')
        }
    }
    return DashboardAddressPlan(shown, floor, true)
}

@Composable
internal fun DashboardAddressText(address: String, modifier: Modifier, style: TextStyle, color: Color) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val resolvedStyle = style.copy(color = color, fontFamily = style.fontFamily ?: LocalLiteFontFamily.current,
        localeList = style.localeList ?: LocalLocaleList.current)
    val natural = remember(address, resolvedStyle, measurer) {
        measurer.measure(address, style = resolvedStyle, softWrap = false, maxLines = 1, overflow = TextOverflow.Visible)
    }
    val leading = with(density) { ceil(style.lineHeight.toPx()).toInt() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val room = with(density) { maxWidth.roundToPx() }
        val plan = dashboardAddressPlan(address, natural.size.width.toFloat(), room,
            with(density) { style.fontSize.toPx() / density.density })
        val shownStyle = if (plan.wrap) resolvedStyle.copy(fontSize = style.fontSize * plan.scale,
            lineHeight = style.lineHeight * plan.scale) else resolvedStyle
        Layout(content = {
            BasicText(plan.shown, Modifier.testTag("dashboard-status-ip-raw").liteInk(color, AeroveInkRole.Body)
                .semantics { text = AnnotatedString(address) }, style = shownStyle,
                softWrap = plan.wrap, maxLines = if (plan.wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Visible)
        }) { children, constraints ->
            val child = children.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = if (plan.wrap) constraints.maxWidth else Constraints.Infinity))
            val scale = if (plan.wrap) 1f else plan.scale
            val height = constraints.constrainHeight(maxOf(leading, ceil(child.height * scale).toInt()))
            val width = constraints.constrainWidth(ceil(child.width * scale).toInt())
            layout(width, height) {
                child.placeWithLayer(0, ((height - child.height * scale) / 2f).toInt()) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}
