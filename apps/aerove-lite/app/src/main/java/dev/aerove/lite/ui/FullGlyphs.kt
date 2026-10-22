package dev.aerove.lite.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Geometry and roles are ported from lib/ui/icons/app_glyphs.dart.
internal object FullGlyphs {
    val shuffle = LiteGlyph(listOf(
        glyphPolyline(listOf(vertex(3, 7), vertex(7, 7, 2), vertex(15, 17, 2), vertex(20.5, 17)), role = LiteGlyphRole.Line),
        glyphPath("M3 17H5Q7 17 8.25 15.44L8.69 14.88", role = LiteGlyphRole.Line),
        glyphPath("M13.31 9.12L13.75 8.56Q15 7 17 7H20.5", role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(17.5, 4), vertex(20.5, 7, 0.4), vertex(17.5, 10)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(17.5, 14), vertex(20.5, 17, 0.4), vertex(17.5, 20)), role = LiteGlyphRole.Line),
    ))

    val target = LiteGlyph(listOf(
        glyphArc(12, 12, 8.8, PI * -0.93, PI * 0.86, role = LiteGlyphRole.Line),
        glyphArc(12, 12, 8.8, PI * 0.07, PI * 0.86, role = LiteGlyphRole.Line),
        glyphCircle(12, 12, 2),
    ))

    val rules = LiteGlyph(listOf(
        glyphBox(3, 4, 21, 20, 3),
        glyphCircle(7, 8, 1.1, role = LiteGlyphRole.Detail, solid = true),
        glyphPolyline(listOf(vertex(10.8, 8), vertex(17.2, 8)), role = LiteGlyphRole.Detail),
        glyphCircle(7, 12, 1.1, role = LiteGlyphRole.Detail, solid = true),
        glyphPolyline(listOf(vertex(10.8, 12), vertex(17.2, 12)), role = LiteGlyphRole.Detail),
        glyphCircle(7, 16, 1.1, role = LiteGlyphRole.Detail, solid = true),
        glyphPolyline(listOf(vertex(10.8, 16), vertex(17.2, 16)), role = LiteGlyphRole.Detail),
    ), mirrorInRtl = true)

    val devices = LiteGlyph(listOf(
        glyphPolyline(listOf(vertex(11.4, 14.5), vertex(4, 14.5, 2), vertex(4, 4, 2), vertex(18.8, 4, 2), vertex(18.8, 6.4)), role = LiteGlyphRole.Line),
        glyphPolyline(listOf(vertex(2.9, 18.2), vertex(11.4, 18.2)), role = LiteGlyphRole.Line),
        glyphBox(15, 10, 20.8, 20.5, 2),
    ))

    fun playPause(progress: Float): LiteGlyph {
        val fold = progress.coerceIn(0f, 1f)
        val angle = PI / 2 * progress
        fun turned(x: Float, y: Float) = vertex(12 + (x - 12) * cos(angle) - (y - 12) * sin(angle), 12 + (x - 12) * sin(angle) + (y - 12) * cos(angle), 1.2)
        if (fold == 0f) return LiteGlyph(listOf(glyphPolyline(listOf(turned(8.3f, 5.6f), turned(18.8f, 12f), turned(8.3f, 18.4f)), closed = true)))
        return LiteGlyph(playHalves.map { (half, bar) ->
            glyphPolyline(List(4) { i -> turned(half[i * 2] + (bar[i * 2] - half[i * 2]) * fold, half[i * 2 + 1] + (bar[i * 2 + 1] - half[i * 2 + 1]) * fold) }, closed = true)
        })
    }

    private val playHalves = listOf(
        floatArrayOf(8.3f, 5.6f, 18.8f, 12f, 18.8f, 12f, 8.3f, 12f) to floatArrayOf(5.9f, 6.9f, 18.1f, 6.9f, 18.1f, 9.1f, 5.9f, 9.1f),
        floatArrayOf(8.3f, 12f, 18.8f, 12f, 18.8f, 12f, 8.3f, 18.4f) to floatArrayOf(5.9f, 14.9f, 18.1f, 14.9f, 18.1f, 17.1f, 5.9f, 17.1f),
    )

    val dashboard = LiteGlyph(listOf(
      glyphBox(3.5, 3.5, 10.5, 11.5, 2),
      glyphBox(13.5, 3.5, 20.5, 9.5, 2),
      glyphBox(3.5, 14.5, 10.5, 20.5, 2),
      glyphBox(13.5, 12.5, 20.5, 20.5, 2),
    ))

    val proxies = LiteGlyph(listOf(
      glyphCircle(12, 12, 8.8),
      glyphOval(8.2, 3.2, 15.8, 20.8, role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(3.2, 12),
        vertex(20.8, 12),
      ), role = LiteGlyphRole.Detail),
    ))

    val profiles = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(5, 3, 2.5),
        vertex(13.5, 3, 1),
        vertex(19, 8.5, 1),
        vertex(19, 21, 2.5),
        vertex(5, 21, 2.5),
      ), closed = true),
      glyphPolyline(listOf(
        vertex(13.5, 3.2),
        vertex(13.5, 8.5, 1.5),
        vertex(18.8, 8.5),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(8.5, 12.5),
        vertex(15.5, 12.5),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(8.5, 16),
        vertex(13, 16),
      ), role = LiteGlyphRole.Detail),
    ))

    val connections = LiteGlyph(listOf(
      glyphCircle(12, 5.2, 2.4),
      glyphCircle(5.2, 17.6, 2.4),
      glyphCircle(18.8, 17.6, 2.4),
      glyphLink(point(12, 5.2), point(5.2, 17.6), 2.4),
      glyphLink(point(12, 5.2), point(18.8, 17.6), 2.4),
      glyphLink(point(5.2, 17.6), point(18.8, 17.6), 2.4),
    ))

    val dns = LiteGlyph(listOf(
      glyphBox(3.5, 3.5, 20.5, 10.5, 2.5),
      glyphBox(3.5, 13.5, 20.5, 20.5, 2.5),
      glyphCircle(7.2, 7, 1.1, role = LiteGlyphRole.Detail, solid = true),
      glyphCircle(7.2, 17, 1.1, role = LiteGlyphRole.Detail, solid = true),
    ))

    val logs = LiteGlyph(listOf(
      glyphBox(3, 4.5, 21, 19.5, 3.5),
      glyphPolyline(listOf(
        vertex(7.3, 9.3),
        vertex(9.9, 11.9, 0.4),
        vertex(7.3, 14.5),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(12.2, 14.5),
        vertex(16.2, 14.5),
      ), role = LiteGlyphRole.Detail),
    ))

    val tools = LiteGlyph(listOf(
      glyphBox(3, 7, 21, 20, 2.8),
      glyphPolyline(listOf(
        vertex(9, 7),
        vertex(9, 4.6, 1.4),
        vertex(15, 4.6, 1.4),
        vertex(15, 7),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(3.2, 12.6),
        vertex(9.6, 12.6),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(14.4, 12.6),
        vertex(20.8, 12.6),
      ), role = LiteGlyphRole.Detail),
      glyphBox(10.4, 11, 13.6, 14.4, 1, role = LiteGlyphRole.Detail),
    ))

    val arrowBack = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(5, 12),
        vertex(19, 12),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(11.5, 5.5),
        vertex(5, 12, 0.4),
        vertex(11.5, 18.5),
      ), role = LiteGlyphRole.Line),
    ), mirrorInRtl = true)

    val chevronForward = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(8.75, 5.5),
        vertex(15.25, 12, 0.4),
        vertex(8.75, 18.5),
      ), role = LiteGlyphRole.Line),
    ), mirrorInRtl = true)

    val chevronDown = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(5.5, 8.75),
        vertex(12, 15.25, 0.4),
        vertex(18.5, 8.75),
      ), role = LiteGlyphRole.Line),
    ))

    val chevronUp = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(5.5, 15.25),
        vertex(12, 8.75, 0.4),
        vertex(18.5, 15.25),
      ), role = LiteGlyphRole.Line),
    ))

    val arrowUp = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(12, 5),
        vertex(12, 19),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(5.5, 11.5),
        vertex(12, 5, 0.4),
        vertex(18.5, 11.5),
      ), role = LiteGlyphRole.Line),
    ))

    val arrowDown = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(12, 5),
        vertex(12, 19),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(5.5, 12.5),
        vertex(12, 19, 0.4),
        vertex(18.5, 12.5),
      ), role = LiteGlyphRole.Line),
    ))

    val refresh = LiteGlyph(listOf(
      glyphArc(
        12,
        12,
        7.78,
        PI * 0.19,
        PI * 1.666,
        role = LiteGlyphRole.Line,
      ),
      glyphPolyline(listOf(
        vertex(19, 5),
        vertex(19, 8.6, 0.4),
        vertex(15.4, 8.6),
      ), role = LiteGlyphRole.Line),
    ))

    val add = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(12, 5),
        vertex(12, 19),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(5, 12),
        vertex(19, 12),
      ), role = LiteGlyphRole.Line),
    ))

    val close = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(6, 6),
        vertex(18, 18),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(18, 6),
        vertex(6, 18),
      ), role = LiteGlyphRole.Line),
    ))

    val check = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(4.8, 12.5),
        vertex(9.6, 17.3),
        vertex(19.4, 6.7),
      ), role = LiteGlyphRole.Line),
    ))

    val info = LiteGlyph(listOf(
      glyphCircle(12, 12, 8.8),
      glyphCircle(12, 7.5, 1.4, role = LiteGlyphRole.Detail, solid = true),
      glyphPolyline(listOf(
        vertex(12, 11.8),
        vertex(12, 16.7),
      ), role = LiteGlyphRole.Detail),
    ))

    val delete = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(5.8, 6.8),
        vertex(18.2, 6.8),
        vertex(17.2, 21, 2.5),
        vertex(6.8, 21, 2.5),
      ), closed = true),
      glyphPolyline(listOf(
        vertex(10, 10.5),
        vertex(10, 17.5),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(14, 10.5),
        vertex(14, 17.5),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(3.8, 6.8),
        vertex(20.2, 6.8),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(9, 6.8),
        vertex(9, 3.2, 1.4),
        vertex(15, 3.2, 1.4),
        vertex(15, 6.8),
      ), role = LiteGlyphRole.Line),
    ))

    val copy = LiteGlyph(listOf(
      glyphBox(8.5, 7.5, 20.5, 21, 2.5),
      glyphPolyline(listOf(
        vertex(4, 16.5),
        vertex(4, 3, 2.5),
        vertex(16, 3),
      ), role = LiteGlyphRole.Line),
    ))

    val search = LiteGlyph(listOf(
      glyphCircle(10.3, 10.3, 6.9, role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(15.18, 15.18),
        vertex(20.6, 20.6),
      ), role = LiteGlyphRole.Line),
    ))

    val importFile = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(5, 3, 2.5),
        vertex(13.5, 3, 1),
        vertex(19, 8.5, 1),
        vertex(19, 21, 2.5),
        vertex(5, 21, 2.5),
      ), closed = true),
      glyphPolyline(listOf(
        vertex(13.5, 3.2),
        vertex(13.5, 8.5, 1.5),
        vertex(18.8, 8.5),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(12, 17.6),
        vertex(12, 11.4),
      ), role = LiteGlyphRole.Detail),
      glyphPolyline(listOf(
        vertex(9, 14.4),
        vertex(12, 11.4, 0.4),
        vertex(15, 14.4),
      ), role = LiteGlyphRole.Detail),
    ))

    val settings = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(9.87, 5.44, 0.6),
        vertex(10.09, 3, 0.9),
        vertex(13.91, 3, 0.9),
        vertex(14.13, 5.44, 0.6),
        vertex(16.62, 6.87, 0.6),
        vertex(18.84, 5.84, 0.9),
        vertex(20.75, 9.16, 0.9),
        vertex(18.75, 10.57, 0.6),
        vertex(18.75, 13.43, 0.6),
        vertex(20.75, 14.84, 0.9),
        vertex(18.84, 18.16, 0.9),
        vertex(16.62, 17.13, 0.6),
        vertex(14.13, 18.56, 0.6),
        vertex(13.91, 21, 0.9),
        vertex(10.09, 21, 0.9),
        vertex(9.87, 18.56, 0.6),
        vertex(7.38, 17.13, 0.6),
        vertex(5.16, 18.16, 0.9),
        vertex(3.25, 14.84, 0.9),
        vertex(5.25, 13.43, 0.6),
        vertex(5.25, 10.57, 0.6),
        vertex(3.25, 9.16, 0.9),
        vertex(5.16, 5.84, 0.9),
        vertex(7.38, 6.87, 0.6),
      ), closed = true),
      glyphCircle(12, 12, 3, role = LiteGlyphRole.Detail),
    ))

    val paintbrush = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(9.1, 10.51, 1.2),
        vertex(13.49, 14.9, 1.2),
        vertex(9.95, 18.43, 3.6),
        vertex(4.08, 20.2, 1),
        vertex(5.57, 14.05, 3.6),
      ), closed = true),
      glyphPolyline(listOf(
        vertex(12.64, 8.96, 0.6),
        vertex(15.04, 11.36, 0.6),
        vertex(21.4, 5, 1.7),
        vertex(19, 2.6, 1.7),
      ), closed = true),
    ))

    val vpn = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(12, 2.9, 1.5),
        vertex(19.6, 5.5, 2),
        vertex(19.6, 12.5, 5),
        vertex(12, 21.1, 2.5),
        vertex(4.4, 12.5, 5),
        vertex(4.4, 5.5, 2),
      ), closed = true),
      glyphCircle(12, 10.2, 1.6, role = LiteGlyphRole.Detail, solid = true),
      glyphPolyline(listOf(
        vertex(12, 11),
        vertex(12, 14.6),
      ), role = LiteGlyphRole.Detail),
    ))

    val status = LiteGlyph(listOf(
      glyphBox(3, 4.2, 21, 19.8, 4.5),
      glyphPolyline(listOf(
        vertex(6.4, 12.3),
        vertex(8.8, 12.3, 0.5),
        vertex(10.4, 8.2, 0.5),
        vertex(13.4, 16.4, 0.5),
        vertex(15, 12.3, 0.5),
        vertex(17.6, 12.3),
      ), role = LiteGlyphRole.Detail),
    ))

    val split = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(4.5, 4),
        vertex(12, 11.5, 2.5),
        vertex(12, 20.5),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(19.5, 4),
        vertex(12, 11.5, 2.5),
        vertex(12, 20.5),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(4.5, 9.5),
        vertex(4.5, 4, 0.5),
        vertex(10, 4),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(14, 4),
        vertex(19.5, 4, 0.5),
        vertex(19.5, 9.5),
      ), role = LiteGlyphRole.Line),
    ))

    val speed = LiteGlyph(listOf(
      glyphPath(
        "M6.759 19.057 Q5.259 19.057 4.396 17.83 A8.8 8.8 0 1 1 19.604 17.83 " + "Q18.741 19.057 17.241 19.057 Z",
      ),
      glyphCircle(12, 13.4, 1.4, role = LiteGlyphRole.Detail, solid = true),
      glyphPolyline(listOf(
        vertex(12, 13.4),
        vertex(15.47, 9.26),
      ), role = LiteGlyphRole.Detail),
    ))

    val appsList = LiteGlyph(listOf(
      glyphBox(3.5, 3.3, 6.9, 6.7, 1),
      glyphBox(3.5, 10.3, 6.9, 13.7, 1),
      glyphBox(3.5, 17.3, 6.9, 20.7, 1),
      glyphPolyline(listOf(
        vertex(10.5, 5),
        vertex(20.5, 5),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(10.5, 12),
        vertex(20.5, 12),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(10.5, 19),
        vertex(20.5, 19),
      ), role = LiteGlyphRole.Line),
    ))

    val layers = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(12, 3, 1.5),
        vertex(20.8, 7.6, 1.2),
        vertex(12, 12.2, 1.5),
        vertex(3.2, 7.6, 1.2),
      ), closed = true),
      glyphPolyline(listOf(
        vertex(3.2, 12),
        vertex(12, 16.6, 1.5),
        vertex(20.8, 12),
      ), role = LiteGlyphRole.Line),
      glyphPolyline(listOf(
        vertex(3.2, 16.4),
        vertex(12, 21, 1.5),
        vertex(20.8, 16.4),
      ), role = LiteGlyphRole.Line),
    ))

    val verified = LiteGlyph(listOf(
      glyphPolyline(listOf(
        vertex(12, 2.9, 1.5),
        vertex(19.6, 5.5, 2),
        vertex(19.6, 12.5, 5),
        vertex(12, 21.1, 2.5),
        vertex(4.4, 12.5, 5),
        vertex(4.4, 5.5, 2),
      ), closed = true),
      glyphPolyline(listOf(
        vertex(8.7, 11.8),
        vertex(11, 14.1),
        vertex(15.3, 9.6),
      ), role = LiteGlyphRole.Detail),
    ))

}
