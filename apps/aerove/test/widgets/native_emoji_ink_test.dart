import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/ui/native_emoji_cover.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    final loader = FontLoader(desktopEmojiFontFamily)
      ..addFont(
        rootBundle.load('packages/fl_clash/assets/fonts/Twemoji.Mozilla.ttf'),
      );
    await loader.load();
    NativeEmojiCover.debugFontFamily = desktopEmojiFontFamily;
  });
  tearDownAll(() => NativeEmojiCover.debugFontFamily = null);

  testWidgets(
    'native flags center color glyph ink across changes and cache reuse',
    (tester) async {
      for (final flag in ['🇸🇬', '🇯🇵', '🇨🇭', '🇳🇵', '🇸🇬']) {
        final boundaryKey = GlobalKey();
        var ready = 0;
        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.rtl,
            child: MediaQuery(
              data: const MediaQueryData(textScaler: TextScaler.linear(2)),
              child: Center(
                child: RepaintBoundary(
                  key: boundaryKey,
                  child: SizedBox.square(
                    dimension: 240,
                    child: Center(
                      child: NotificationListener<EmojiInkReadyNotification>(
                        onNotification: (_) {
                          ready++;
                          return true;
                        },
                        child: EmojiCover(
                          flag,
                          size: 48,
                          platform: TargetPlatform.android,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        for (var attempt = 0; attempt < 500 && ready == 0; attempt++) {
          await tester.runAsync(
            () => Future<void>.delayed(const Duration(milliseconds: 20)),
          );
          await tester.pump();
        }
        expect(ready, 1);
        final boundary =
            boundaryKey.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final pixels = await tester.runAsync(() async {
          final image = await boundary.toImage();
          try {
            return (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!;
          } finally {
            image.dispose();
          }
        });
        var left = 240, top = 240, right = -1, bottom = -1;
        final colors = <int>{};
        for (var y = 0; y < 240; y++) {
          for (var x = 0; x < 240; x++) {
            final offset = (y * 240 + x) * 4;
            if (pixels!.getUint8(offset + 3) == 0) continue;
            left = x < left ? x : left;
            right = x > right ? x : right;
            top = y < top ? y : top;
            bottom = y > bottom ? y : bottom;
            colors.add(pixels.getUint32(offset));
          }
        }
        expect(
          colors.length,
          greaterThan(8),
          reason: 'Must render the loaded color font, not a box substitute',
        );
        expect((left + right + 1) / 2, closeTo(120, 0.5));
        expect((top + bottom + 1) / 2, closeTo(120, 0.5));
        expect((right - left + 1).toDouble(), greaterThanOrEqualTo(47));
        expect((bottom - top + 1).toDouble(), greaterThanOrEqualTo(47));
        await tester.pump(const Duration(seconds: 1));
        expect(ready, 1, reason: 'No scan or readiness notification per frame');
        await tester.pumpWidget(const SizedBox());
        expect(tester.takeException(), isNull);
      }
    },
  );
}
