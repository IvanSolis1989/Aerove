package dev.aerove.lite.ui

// Geometry from Full lib/ui/icons/app_glyphs.dart.
internal object EditorGlyphs {
    val undo = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(5, 9),
      vertex(19, 9, 4.8),
      vertex(19, 18.6, 4.8),
      vertex(9, 18.6),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(8.8, 5.2),
      vertex(5, 9, 0.4),
      vertex(8.8, 12.8),
    ), role = LiteGlyphRole.Line),
  ), mirrorInRtl = true)

    val redo = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(19, 9),
      vertex(5, 9, 4.8),
      vertex(5, 18.6, 4.8),
      vertex(15, 18.6),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(15.2, 5.2),
      vertex(19, 9, 0.4),
      vertex(15.2, 12.8),
    ), role = LiteGlyphRole.Line),
  ), mirrorInRtl = true)

    val more = LiteGlyph(listOf(
    glyphCircle(12, 5.5, 1.4, role = LiteGlyphRole.Line, solid = true),
    glyphCircle(12, 12, 1.4, role = LiteGlyphRole.Line, solid = true),
    glyphCircle(12, 18.5, 1.4, role = LiteGlyphRole.Line, solid = true),
  ))

    val wrap = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(4, 6),
      vertex(20, 6),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(4, 12),
      vertex(20, 12, 3),
      vertex(20, 18, 3),
      vertex(11, 18),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(13.8, 15.2),
      vertex(11, 18, 0.4),
      vertex(13.8, 20.8),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(4, 18),
      vertex(6.5, 18),
    ), role = LiteGlyphRole.Line),
  ), mirrorInRtl = true)

    val cut = LiteGlyph(listOf(
    glyphCircle(7, 17.5, 3),
    glyphCircle(17, 17.5, 3),
    glyphPolyline(listOf(
      vertex(9.64, 13.73),
      vertex(16.8, 3.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(14.36, 13.73),
      vertex(7.2, 3.5),
    ), role = LiteGlyphRole.Line),
  ))

    val paste = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(4.5, 5.2, 2.5),
      vertex(8.5, 5.2),
      vertex(8.5, 2.9, 1),
      vertex(15.5, 2.9, 1),
      vertex(15.5, 5.2),
      vertex(19.5, 5.2, 2.5),
      vertex(19.5, 21, 2.5),
      vertex(4.5, 21, 2.5),
    ), closed = true),
    glyphPolyline(listOf(
      vertex(8.5, 5.2),
      vertex(8.5, 7.3, 1),
      vertex(15.5, 7.3, 1),
      vertex(15.5, 5.2),
    ), role = LiteGlyphRole.Detail),
  ))

    val selectAll = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(3.5, 7),
      vertex(3.5, 3.5, 2.5),
      vertex(7, 3.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(20.5, 7),
      vertex(20.5, 3.5, 2.5),
      vertex(17, 3.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(20.5, 17),
      vertex(20.5, 20.5, 2.5),
      vertex(17, 20.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(3.5, 17),
      vertex(3.5, 20.5, 2.5),
      vertex(7, 20.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(10.6, 3.5),
      vertex(13.4, 3.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(20.5, 10.6),
      vertex(20.5, 13.4),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(13.4, 20.5),
      vertex(10.6, 20.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(3.5, 13.4),
      vertex(3.5, 10.6),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(8, 12.3),
      vertex(10.8, 15.1, 0.4),
      vertex(16, 9.2),
    ), role = LiteGlyphRole.Line),
  ))

    val matchCase = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(2.4, 18.5),
      vertex(7.4, 5.5, 0.6),
      vertex(12.4, 18.5),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(4.13, 14),
      vertex(10.67, 14),
    ), role = LiteGlyphRole.Line),
    glyphCircle(18.1, 15.1, 3.4, role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(21.5, 11.7),
      vertex(21.5, 18.5),
    ), role = LiteGlyphRole.Line),
  ))

    val regex = LiteGlyph(listOf(
    glyphCircle(5.5, 17.5, 0.9, role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(15.5, 4),
      vertex(15.5, 13),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(11.6, 6.25),
      vertex(19.4, 10.75),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(11.6, 10.75),
      vertex(19.4, 6.25),
    ), role = LiteGlyphRole.Line),
  ))

    val textSize = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(3.2, 4.4),
      vertex(14, 4.4),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(8.6, 4.4),
      vertex(8.6, 19.6),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(14.8, 10.8),
      vertex(21, 10.8),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(17.9, 10.8),
      vertex(17.9, 19.6),
    ), role = LiteGlyphRole.Line),
  ))

    val swap = LiteGlyph(listOf(
    glyphPolyline(listOf(
      vertex(3.5, 8),
      vertex(20.5, 8),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(17.5, 5),
      vertex(20.5, 8, 0.4),
      vertex(17.5, 11),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(20.5, 16),
      vertex(3.5, 16),
    ), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(
      vertex(6.5, 13),
      vertex(3.5, 16, 0.4),
      vertex(6.5, 19),
    ), role = LiteGlyphRole.Line),
  ))
}
