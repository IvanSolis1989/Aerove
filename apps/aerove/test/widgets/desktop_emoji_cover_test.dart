import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    final loader = FontLoader(desktopEmojiFontFamily)
      ..addFont(rootBundle.load('assets/fonts/Twemoji.Mozilla.ttf'));
    await loader.load();
  });

  for (final flag in [
    '🇸🇬',
    '🇺🇸',
    '🇯🇵',
    '🇬🇧',
    '🇭🇰',
    '🇧🇪',
    '🇨🇭',
    '🇻🇦',
  ]) {
    testWidgets('$flag covers the bubble a badge clips it to', (tester) async {
      const size = 100.0;
      final key = GlobalKey();
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Center(
            child: RepaintBoundary(
              key: key,
              child: EmojiCover(
                flag,
                size: size,
                platform: TargetPlatform.windows,
              ),
            ),
          ),
        ),
      );
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final pixels = await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
        } finally {
          image.dispose();
        }
      });
      const center = Offset(size / 2, size / 2);
      for (var y = 0; y < size; y++) {
        for (var x = 0; x < size; x++) {
          if ((Offset(x + 0.5, y + 0.5) - center).distance > size * 0.475) {
            continue;
          }
          // Where two of the flag's colors meet, a seam dips to about half.
          final alpha = pixels!.getUint8((y * size.toInt() + x) * 4 + 3);
          expect(alpha, greaterThan(96), reason: '$flag leaves ($x, $y) bare');
        }
      }
    });
  }

  for (final (flag, bounds) in [
    ('🇸🇬', const Rect.fromLTRB(38, -83, 1238, 783)),
    ('🇯🇵', const Rect.fromLTRB(38, -83, 1238, 783)),
    ('🇺🇸', const Rect.fromLTRB(38, -83, 1238, 783)),
    ('🇭🇰', const Rect.fromLTRB(38, -83, 1238, 783)),
    ('🇧🇪', const Rect.fromLTRB(138, -83, 1138, 783)),
    ('🇨🇭', const Rect.fromLTRB(204, -83, 1071, 783)),
    ('🇻🇦', const Rect.fromLTRB(204, -83, 1071, 783)),
    ('🇳🇵', const Rect.fromLTRB(304, -83, 964, 783)),
    ('🇸🇲', const Rect.fromLTRB(38, -83, 1238, 782)),
  ]) {
    for (final size in [40.0, 48.0]) {
      testWidgets('$flag centers its complete picture in a $size dp badge', (
        tester,
      ) async {
        const pixelRatio = 8.0;
        final key = GlobalKey();
        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.rtl,
            child: MediaQuery(
              data: const MediaQueryData(textScaler: TextScaler.linear(2)),
              child: DefaultTextStyle(
                style: const TextStyle(
                  fontSize: 32,
                  height: 1.8,
                  letterSpacing: 4,
                  fontWeight: FontWeight.bold,
                ),
                child: Center(
                  child: RepaintBoundary(
                    key: key,
                    child: SizedBox.square(
                      dimension: size * 2,
                      child: Center(
                        child: SizedBox.square(
                          dimension: size,
                          child: EmojiCover(
                            flag,
                            size: size * 0.92,
                            platform: TargetPlatform.windows,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        final paragraph = tester.renderObject<RenderParagraph>(
          find.descendant(
            of: find.byType(EmojiCover),
            matching: find.byType(RichText),
          ),
        );
        final text = tester.widget<EmojiText>(find.byType(EmojiText));
        final fontSize = text.style!.fontSize!;
        final baseline = paragraph.getDryBaseline(
          paragraph.constraints,
          TextBaseline.alphabetic,
        )!;
        final pictureCenter = paragraph.localToGlobal(
          Offset(
            bounds.center.dx * fontSize / 1024,
            baseline - bounds.center.dy * fontSize / 1024,
          ),
        );
        final badgeCenter = tester.getCenter(find.byType(EmojiCover));
        final geometry =
            '$flag: constraints=${paragraph.constraints}, '
            'paragraphSize=${paragraph.size}, baseline=$baseline, '
            'fontSize=$fontSize, picture=$pictureCenter, badge=$badgeCenter';
        expect(
          pictureCenter.dx,
          closeTo(badgeCenter.dx, 1e-4),
          reason: geometry,
        );
        expect(
          pictureCenter.dy,
          closeTo(badgeCenter.dy, 1e-4),
          reason: geometry,
        );
        expect(
          baseline,
          closeTo(baseline.roundToDouble(), 1e-4),
          reason: geometry,
        );

        final boundary =
            key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        final pixels = await tester.runAsync(() async {
          final image = await boundary.toImage(pixelRatio: pixelRatio);
          try {
            final data = await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            );
            return data!;
          } finally {
            image.dispose();
          }
        });
        final edge = (size * 2 * pixelRatio).round();
        var left = edge;
        var top = edge;
        var right = 0;
        var bottom = 0;
        for (var y = 0; y < edge; y++) {
          for (var x = 0; x < edge; x++) {
            if (pixels!.getUint8((y * edge + x) * 4 + 3) < 128) continue;
            if (x < left) left = x;
            if (y < top) top = y;
            if (x + 1 > right) right = x + 1;
            if (y + 1 > bottom) bottom = y + 1;
          }
        }
        final picture = Rect.fromLTRB(
          left / pixelRatio,
          top / pixelRatio,
          right / pixelRatio,
          bottom / pixelRatio,
        );
        final raster = '$geometry, rasterBounds=$picture';
        expect(
          picture.center.dx,
          closeTo(size, 1 / pixelRatio),
          reason: raster,
        );
        expect(
          picture.center.dy,
          closeTo(size, 1 / pixelRatio),
          reason: raster,
        );
        expect(
          picture.shortestSide,
          closeTo(size * 0.92, 2 / pixelRatio),
          reason: '$raster; each antialiased edge may snap by one pixel',
        );
        expect(tester.takeException(), isNull);
      });
    }
  }
}
