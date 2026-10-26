"""Builds CjkSquare.ttf: one full-em square for every Han, kana, Hangul and
CJK punctuation code point, so widget tests lay CJK text out at the advance
CJK fonts give it on machines without one installed.

Run with fontTools 4.x: python make_cjk_square.py
"""
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import newTable
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable

RANGES = [
    (0x1100, 0x11FF),
    (0x3000, 0x30FF),
    (0x3130, 0x318F),
    (0x31F0, 0x31FF),
    (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF),
    (0xAC00, 0xD7AF),
    (0xF900, 0xFAFF),
    (0xFF01, 0xFF9F),
]
EM = 1000
# 2026-10-26 00:00:00 UTC in seconds since 1904-01-01, so builds match.
STAMP = 3_844_627_200


def square():
    pen = TTGlyphPen(None)
    pen.moveTo((60, -80))
    pen.lineTo((60, 800))
    pen.lineTo((940, 800))
    pen.lineTo((940, -80))
    pen.closePath()
    return pen.glyph()


def build(path: Path) -> None:
    builder = FontBuilder(EM, isTTF=True)
    builder.setupGlyphOrder(['.notdef', 'square'])
    # Format 13 maps whole ranges to the one glyph in a few bytes.
    subtable = CmapSubtable.newSubtable(13)
    subtable.platformID, subtable.platEncID, subtable.language = 3, 10, 0
    subtable.cmap = {
        code: 'square' for start, end in RANGES for code in range(start, end + 1)
    }
    cmap = newTable('cmap')
    cmap.tableVersion = 0
    cmap.tables = [subtable]
    builder.font['cmap'] = cmap
    builder.setupGlyf({'.notdef': TTGlyphPen(None).glyph(), 'square': square()})
    builder.setupHorizontalMetrics({'.notdef': (EM, 0), 'square': (EM, 60)})
    builder.setupHorizontalHeader(ascent=880, descent=-120)
    builder.setupNameTable({
        'familyName': 'Aerove CJK Square',
        'styleName': 'Regular',
        'copyright': 'Test fixture of the Aerove project; no outlines from any '
                     'other font.',
    })
    builder.setupOS2(
        sTypoAscender=880,
        sTypoDescender=-120,
        sTypoLineGap=0,
        usWinAscent=880,
        usWinDescent=120,
    )
    builder.setupPost()
    builder.updateHead(created=STAMP, modified=STAMP)
    builder.save(str(path))


if __name__ == '__main__':
    build(Path(__file__).with_name('CjkSquare.ttf'))
