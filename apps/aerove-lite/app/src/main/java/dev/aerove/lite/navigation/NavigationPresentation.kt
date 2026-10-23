package dev.aerove.lite.navigation

import kotlin.math.max

public enum class TabAnimation(public val wireName: String) { Slide("slide"), Fade("fade") }

internal const val SlideTabMillis = 300
internal const val FadeTabMillis = 220
internal fun tabDurationMillis(mode: TabAnimation) = if (mode == TabAnimation.Fade) FadeTabMillis else SlideTabMillis
internal fun floatingNavigationInset(fontScale: Float, bottomInset: Float) = 62f + 10f * (fontScale - 1f) + max(21f, bottomInset)
internal fun dockedNavigationInset(bottomInset: Float) = 80f + bottomInset

internal data class TabSceneFrame(val offset: Float, val opacity: Float)
internal fun tabSceneFrame(mode: TabAnimation, incoming: Boolean, direction: Int, progress: Float): TabSceneFrame {
    val fraction = progress.coerceIn(0f, 1f)
    return if (mode == TabAnimation.Fade) TabSceneFrame(0f, if (incoming) fraction else 1f - fraction)
    else TabSceneFrame(direction * (if (incoming) 1f - fraction else -fraction), 1f)
}

internal fun <K> retargetTab(from: K, to: K, progress: Float, order: List<K>): K = when {
    progress < 0.5f -> from
    progress > 0.5f -> to
    order.indexOf(to) > order.indexOf(from) -> to
    else -> from
}
