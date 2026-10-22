package dev.aerove.lite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import kotlinx.coroutines.isActive
import dev.aerove.lite.optics.AeroveGlassQuality
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.optics.AeroveSuperellipseShape

internal object LiteCorner {
    val None = 0.dp
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Control = 16.dp
    val Block = 20.dp
    val DashboardBlock = 22.dp
    val Card = 24.dp
    val Sheet = 28.dp
    val Alert = 34.dp
    val Dashboard = 34.dp
    val Full = 1000.dp
}

internal object LiteType {
    val Title = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val Headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val Body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val Callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.sp)
    val Label = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val Footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val Title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    val Title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    val Metric = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    val Dock = TextStyle(fontSize = 10.sp, lineHeight = 14.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
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
internal val LocalLiteInkBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalLiteHighContrast = staticCompositionLocalOf { false }

@Composable
internal fun LiteTheme(settings: LiteSettings, content: @Composable () -> Unit) {
    val dark = when (settings.colorMode) {
        ColorMode.System -> isSystemInDarkTheme()
        ColorMode.Light -> false
        ColorMode.Dark -> true
    }
    val colors = remember(settings.themeColor, dark, settings.aurora) { fullColorScheme(settings.themeColor.toInt(), dark, settings.aurora) }
    val visuals = LiteVisuals(settings.material, settings.aurora, dark, colors.primary, colors.fullPage(dark), colors.fullCard(dark), colors.onSurface, colors.onSurfaceVariant)
    val view = LocalView.current
    val activity = remember(view) { view.context.activityOrNull() }
    SideEffect {
        activity?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !visuals.dark
                isAppearanceLightNavigationBars = !visuals.dark
            }
        }
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val highContrast = remember(resolver) { mutableStateOf(android.provider.Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0) }
    DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                highContrast.value = android.provider.Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0
            }
        }
        resolver.registerContentObserver(android.provider.Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalLiteVisuals provides visuals, LocalLiteHighContrast provides highContrast.value) {
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

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

internal typealias LiteSuperellipse = AeroveSuperellipseShape

@Composable
internal fun rememberLiteShape(corner: Dp = LiteCorner.Card): AeroveSuperellipseShape = remember(corner) { LiteSuperellipse(corner) }

@Composable
internal fun rememberAuroraPhase(enabled: Boolean, restartKey: Any? = null): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(0f, enabled, lifecycle, restartKey) {
        value = 0f
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val base = value
            var start = 0L
            var published = -1L
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (start == 0L) start = now
                    if (now - published >= 1_000_000_000L / 15 || published < 0) {
                        value = base + (now - start) / 90_000_000_000f
                        published = now
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiteWallpaper(phase: State<Float>, modifier: Modifier = Modifier) {
    val visuals = LocalLiteVisuals.current
    Box(if (visuals.aurora) modifier.background(if (visuals.dark) Color(0xff111419) else Color(0xfff7f8fa)).fullAuroraWallpaper(phase, visuals.dark) else modifier.background(visuals.page))
}

@Composable
internal fun LitePane(
    modifier: Modifier = Modifier,
    corner: Dp = LiteCorner.Card,
    selected: Boolean = false,
    clipContent: Boolean = true,
    customShape: Shape? = null,
    solidColor: Color? = null,
    glassTint: Color? = null,
    glassStyle: AeroveGlassStyle? = null,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    rim: LiteGlassRim? = null,
    shadows: List<LiteShadow> = emptyList(),
    explicitGlow: dev.aerove.lite.optics.AeroveGlassGlow? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val visuals = LocalLiteVisuals.current
    val source = LocalLiteBackdrop.current
    val exported = rememberLayerBackdrop()
    val nestedSource = rememberCombinedBackdrop(source, exported)
    val shape = customShape ?: rememberLiteShape(corner)
    val bufferShape = rememberLiteShape(LiteCorner.None)
    val glow = rememberLitePressGlow(interactionSource)
    val resolvedRim = rim.takeIf { visuals.material == MaterialTier.Clear }
    val tint = if (selected) lerp(visuals.surface, visuals.primary, 0.13f) else visuals.surface
    val style = (glassStyle ?: AeroveGlassStyle.regular(visuals.dark)).let { base ->
        val fill = glassTint ?: if (glassStyle != null) base.tint.composeColor() else if (selected) visuals.primary.copy(alpha = 0.2f)
        else if (visuals.material == MaterialTier.Frosted) {
            if (visuals.dark) visuals.ink.copy(alpha = 0.06f) else visuals.surface.copy(alpha = 0.24f)
        } else Color.Transparent
        base.copy(tint = fill.opticsColor()).forQuality(
            if (visuals.material == MaterialTier.Clear) AeroveGlassQuality.Ultra else AeroveGlassQuality.Standard,
        )
    }
    Box(modifier.trackLiteGlow(glow, interactionSource != null).liteOutsideShadows(shape, shadows)
        .then(if (resolvedRim != null && shape is AeroveSuperellipseShape) Modifier.liteRimLine(shape, resolvedRim) else Modifier), propagateMinConstraints = true) {
        val background = when (visuals.material) {
            MaterialTier.Solid -> Modifier.drawPlainBackdrop(
                backdrop = source,
                shape = { bufferShape },
                effects = {},
                onDrawBackdrop = {},
                onDrawSurface = { drawRect(solidColor ?: tint) },
            )
            MaterialTier.Frosted -> Modifier.liteFrostBackdrop(source, style)
            MaterialTier.Clear -> Modifier.liteGlassBackdrop(source, shape, style, glow, resolvedRim, explicitGlow)
        }
        // A sibling plane exports before foreground children draw, so nested sampling is acyclic on the first frame.
        Box(Modifier.matchParentSize().layerBackdrop(exported).clip(shape).then(background))
        Box(if (clipContent) Modifier.clip(shape) else Modifier, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalLiteBackdrop provides nestedSource) { content() }
        }
    }
}
