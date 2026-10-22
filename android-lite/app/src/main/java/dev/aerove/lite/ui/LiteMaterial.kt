package dev.aerove.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal object LiteCorner {
    val Small = 8.dp
    val Control = 16.dp
    val Card = 24.dp
    val Sheet = 28.dp
    val Full = 1000.dp
}

internal object LiteType {
    val Title = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val Headline = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val Label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val Metric = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
}

internal data class LiteVisuals(
    val material: MaterialTier,
    val aurora: Boolean,
    val dark: Boolean,
    val primary: Color,
    val page: Color,
    val surface: Color,
    val ink: Color,
    val secondaryInk: Color,
)

internal val LocalLiteVisuals = staticCompositionLocalOf<LiteVisuals> { error("LiteTheme is missing") }
internal val LocalLiteBackdrop = staticCompositionLocalOf<Backdrop> { error("Backdrop source is missing") }

@Composable
internal fun LiteTheme(settings: LiteSettings, content: @Composable () -> Unit) {
    val dark = when (settings.colorMode) {
        ColorMode.System -> isSystemInDarkTheme()
        ColorMode.Light -> false
        ColorMode.Dark -> true
    }
    val seed = Color(settings.themeColor.toInt())
    val primary = if (dark) lerp(seed, Color.White, 0.46f) else seed
    val page = if (dark) Color(0xFF101116) else Color(0xFFF4F4F8)
    val surface = if (dark) Color(0xFF23242B) else Color.White
    val ink = if (dark) Color(0xFFF4F2F8) else Color(0xFF1B1B22)
    val secondary = if (dark) Color(0xFFB8B7C2) else Color(0xFF656570)
    val colors = if (dark) darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFF211B30),
        surface = surface,
        onSurface = ink,
        onSurfaceVariant = secondary,
        background = page,
        onBackground = ink,
    ) else lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        surface = surface,
        onSurface = ink,
        onSurfaceVariant = secondary,
        background = page,
        onBackground = ink,
    )
    val visuals = LiteVisuals(settings.material, settings.aurora, dark, primary, page, surface, ink, secondary)
    CompositionLocalProvider(LocalLiteVisuals provides visuals) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography(
                bodyLarge = LiteType.Body,
                bodyMedium = LiteType.Label,
                labelLarge = LiteType.Label,
                headlineLarge = LiteType.Title,
                titleLarge = LiteType.Headline,
            ),
            content = content,
        )
    }
}

internal class LiteSuperellipse(private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { corner.toPx() }.coerceIn(0f, size.minDimension / 2f)
        val path = Path()
        if (radius == 0f) {
            path.addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
        } else {
            path.moveTo(radius, 0f)
            path.lineTo(size.width - radius, 0f)
            corner(path, size.width - radius, radius, radius, -PI / 2, 0.0)
            path.lineTo(size.width, size.height - radius)
            corner(path, size.width - radius, size.height - radius, radius, 0.0, PI / 2)
            path.lineTo(radius, size.height)
            corner(path, radius, size.height - radius, radius, PI / 2, PI)
            path.lineTo(0f, radius)
            corner(path, radius, radius, radius, PI, PI * 1.5)
            path.close()
        }
        return Outline.Generic(path)
    }

    private fun corner(path: Path, x: Float, y: Float, radius: Float, from: Double, to: Double) {
        for (step in 1..32) {
            val angle = from + (to - from) * step / 32
            val c = cos(angle)
            val s = sin(angle)
            path.lineTo(
                x + radius * (abs(c).pow(0.5) * if (c < 0) -1 else 1).toFloat(),
                y + radius * (abs(s).pow(0.5) * if (s < 0) -1 else 1).toFloat(),
            )
        }
    }
}

@Composable
internal fun rememberLiteShape(corner: Dp = LiteCorner.Card): Shape = remember(corner) { LiteSuperellipse(corner) }

@Composable
internal fun rememberAuroraPhase(enabled: Boolean): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(0f, enabled, lifecycle) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var start = 0L
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (start == 0L) start = now
                    value = ((now - start) % 90_000_000_000L) / 90_000_000_000f
                }
            }
        }
    }
}

@Composable
internal fun LiteWallpaper(phase: State<Float>, modifier: Modifier = Modifier) {
    val visuals = LocalLiteVisuals.current
    val colors = remember(visuals.dark) {
        if (visuals.dark) listOf(Color(0xFF433B83), Color(0xFF134B57), Color(0xFF59315E))
        else listOf(Color(0xFFB6AEF0), Color(0xFF9DD6DE), Color(0xFFF1BDDF))
    }
    Canvas(modifier) {
        drawRect(visuals.page)
        if (visuals.aurora) {
            val angle = phase.value * 2f * PI.toFloat()
            val centers = listOf(
                Offset(size.width * (0.22f + 0.15f * sin(angle)), size.height * 0.25f),
                Offset(size.width * 0.85f, size.height * (0.43f + 0.15f * cos(angle))),
                Offset(size.width * (0.38f + 0.18f * cos(angle)), size.height * 0.9f),
            )
            for (i in colors.indices) drawRect(
                Brush.radialGradient(
                    listOf(colors[i].copy(alpha = 0.85f), colors[i].copy(alpha = 0f)),
                    center = centers[i],
                    radius = size.maxDimension * 0.8f,
                ),
            )
        }
    }
}

@Composable
internal fun LitePane(
    modifier: Modifier = Modifier,
    corner: Dp = LiteCorner.Card,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val visuals = LocalLiteVisuals.current
    val source = LocalLiteBackdrop.current
    val exported = rememberLayerBackdrop()
    val nestedSource = rememberCombinedBackdrop(source, exported)
    val shape = rememberLiteShape(corner)
    val tint = if (selected) lerp(visuals.surface, visuals.primary, 0.13f) else visuals.surface
    Box(modifier.clip(shape)) {
        val background = when (visuals.material) {
            MaterialTier.Solid -> Modifier.drawPlainBackdrop(
                backdrop = source,
                shape = { shape },
                effects = {},
                exportedBackdrop = exported,
                onDrawBackdrop = {},
                onDrawSurface = { drawRect(tint) },
            )
            MaterialTier.Frosted -> Modifier.drawPlainBackdrop(
                backdrop = source,
                shape = { shape },
                effects = { blur(22.dp.toPx()) },
                exportedBackdrop = exported,
                onDrawSurface = { drawRect(tint.copy(alpha = if (visuals.dark) 0.68f else 0.78f)) },
            )
            MaterialTier.Clear -> Modifier.drawPlainBackdrop(
                backdrop = source,
                shape = { shape },
                effects = {
                    val radius = min(corner.toPx(), size.minDimension / 2f)
                    val margin = 20.dp.toPx()
                    padding = margin
                    runtimeShaderEffect("AeroveSuperellipse", SuperellipseRefraction, "content") {
                        setFloatUniform("size", size.width, size.height)
                        setFloatUniform("radius", radius)
                        setFloatUniform("margin", margin)
                        setFloatUniform("depth", 8.dp.toPx())
                        setFloatUniform("edgeWidth", min(16.dp.toPx(), size.minDimension * 0.36f))
                    }
                },
                exportedBackdrop = exported,
                onDrawSurface = { drawRect(tint.copy(alpha = if (selected) 0.16f else 0.07f)) },
            )
        }
        // A sibling plane exports before foreground children draw, so nested sampling is acyclic on the first frame.
        Box(Modifier.matchParentSize().then(background))
        CompositionLocalProvider(LocalLiteBackdrop provides nestedSource) { content() }
    }
}

// Backdrop's built-in lens uses a circular-corner SDF; this effect shares the n=4 contour used by LiteSuperellipse.
private const val SuperellipseRefraction = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float margin;
uniform float depth;
uniform float edgeWidth;

half4 main(float2 coord) {
    float2 p = coord - float2(margin);
    float2 centered = p - size * 0.5;
    float2 q = abs(centered) - (size * 0.5 - float2(radius));
    float2 v = max(q, 0.0);
    float r = max(radius, 0.001);
    float k = pow(pow(v.x, 4.0) + pow(v.y, 4.0), 0.25);
    float distance = k + min(max(q.x, q.y), 0.0) - r;
    float2 normal;
    if (k > 0.001) {
        normal = normalize(sign(centered) * v * v * v + float2(0.00001));
    } else {
        normal = sign(centered) * (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    }
    float t = clamp(1.0 + distance / max(edgeWidth, 0.001), 0.0, 1.0);
    float2 shift = -normal * depth * t * t;
    float2 limit = size + float2(margin * 2.0 - 0.5);
    half4 red = content.eval(clamp(coord + shift * 1.08, float2(0.5), limit));
    half4 green = content.eval(clamp(coord + shift, float2(0.5), limit));
    half4 blue = content.eval(clamp(coord + shift * 0.92, float2(0.5), limit));
    half alpha = green.a;
    half3 rgb = min(half3(red.r, green.g, blue.b), half3(alpha));
    return half4(rgb, alpha) * half(1.0 - smoothstep(-0.8, 0.2, distance));
}
"""
