package dev.aerove.lite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.aerove.lite.presentation.SystemFontCatalog
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.aerove.lite.presentation.effectiveTextScale
import dev.aerove.lite.presentation.presentationOpaque
import dev.aerove.lite.presentation.effectiveAurora
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
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
internal val LocalLiteFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
internal val LocalLiteHighContrast = staticCompositionLocalOf { false }

@Composable
internal fun rememberSystemThemeColor(dark: Boolean): Long {
    val resources = LocalResources.current
    val theme = LocalContext.current.theme
    val configuration = LocalConfiguration.current
    return remember(resources, theme, configuration, dark) {
        runCatching {
            resources.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600, theme).toLong() and 0xffffffffL
        }.getOrDefault(FullDefaultThemeColor)
    }
}

@Composable
internal fun LiteTheme(settings: LiteSettings, content: @Composable () -> Unit) {
    val dark = when (settings.colorMode) {
        ColorMode.System -> isSystemInDarkTheme()
        ColorMode.Light -> false
        ColorMode.Dark -> true
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val highContrast = remember(resolver) { mutableStateOf(android.provider.Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0) }
    val opaque = presentationOpaque(settings.pureBlack, dark, highContrast.value)
    val aurora = effectiveAurora(settings.aurora, settings.pureBlack, dark, highContrast.value)
    val systemColor = rememberSystemThemeColor(dark)
    val seed = if (settings.followSystemColor) systemColor else settings.themeColor
    val colors = remember(seed, dark, aurora, settings.colorStyle, settings.pureBlack) {
        fullColorScheme(seed.toInt(), dark, aurora, settings.colorStyle, settings.pureBlack)
    }
    val configuration = LocalConfiguration.current
    val fontConfiguration = remember(configuration) { configuration.toString() }
    val fontLifecycle = LocalLifecycleOwner.current
    val eligibleFonts by produceState<List<String>>(emptyList(), fontConfiguration, fontLifecycle, settings.fontFamily) {
        if (settings.fontFamily == null) value = emptyList()
        else fontLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = SystemFontCatalog.load(fontConfiguration)
        }
    }
    val effectiveFamily = SystemFontCatalog.resolve(settings.fontFamily, eligibleFonts)
    val family = remember(effectiveFamily) {
        effectiveFamily?.let {
            FontFamily(android.graphics.Typeface.create(it, android.graphics.Typeface.NORMAL))
        } ?: FontFamily.Default
    }
    val density = LocalDensity.current
    val scaledDensity = Density(density.density, effectiveTextScale(density.fontScale, settings.textScaleEnabled, settings.textScale))
    val reducedMotion = rememberLiteReducedMotion()
    val visuals = LiteVisuals(if (opaque) MaterialTier.Solid else settings.material, aurora, dark, colors.primary, colors.fullPage(dark), colors.fullCard(dark), colors.onSurface, colors.onSurfaceVariant)
    val view = LocalView.current
    val activity = remember(view) { view.context.activityOrNull() }
    DisposableEffect(activity, view, visuals.dark) {
        activity?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = true
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !visuals.dark
                isAppearanceLightNavigationBars = !visuals.dark
            }
        }
        onDispose {}
    }
    DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                highContrast.value = android.provider.Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0
            }
        }
        resolver.registerContentObserver(android.provider.Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity, LocalLiteFontFamily provides family, LocalLiteVisuals provides visuals, LocalLiteHighContrast provides highContrast.value, LocalLiteReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography(
                bodyLarge = LiteType.Body.copy(fontFamily = family),
                bodyMedium = LiteType.Label.copy(fontFamily = family),
                labelLarge = LiteType.Label.copy(fontFamily = family),
                headlineLarge = LiteType.Title.copy(fontFamily = family),
                titleLarge = LiteType.Headline.copy(fontFamily = family),
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
    // The owner declares mounted, visible glass descendants; clear it when that demand ends.
    exportForGlassDescendants: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val visuals = LocalLiteVisuals.current
    val source = LocalLiteBackdrop.current
    val exportBackground = visuals.material != MaterialTier.Solid || exportForGlassDescendants
    val exported = if (exportBackground) rememberLayerBackdrop() else null
    val nestedSource = if (exported != null) rememberCombinedBackdrop(source, exported) else source
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
            MaterialTier.Solid -> Modifier.drawBehind { drawRect(solidColor ?: tint) }
            MaterialTier.Frosted -> Modifier.liteFrostBackdrop(source, style)
            MaterialTier.Clear -> Modifier.liteGlassBackdrop(source, shape, style, glow, resolvedRim, explicitGlow)
        }
        // A sibling plane exports before foreground children draw, so nested sampling is acyclic on the first frame.
        Box(Modifier.matchParentSize().then(if (exported != null) Modifier.layerBackdrop(exported) else Modifier).clip(shape).then(background))
        Box(if (clipContent) Modifier.clip(shape) else Modifier, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalLiteBackdrop provides nestedSource) { content() }
        }
    }
}
