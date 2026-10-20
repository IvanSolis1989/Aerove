import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    final loader = FontLoader(FontFamily.twEmoji.value)
      ..addFont(rootBundle.load('assets/fonts/Twemoji.Mozilla.ttf'));
    await loader.load();
  });

  for (final flag in ['🇺🇸', '🇯🇵', '🇬🇧', '🇭🇰', '🇨🇭']) {
    testWidgets('$flag covers the bubble a badge clips it to', (tester) async {
      const size = 100.0;
      final key = GlobalKey();
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Center(
            child: RepaintBoundary(
              key: key,
              child: EmojiCover(flag, size: size),
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
          final alpha = pixels!.getUint8((y * size.toInt() + x) * 4 + 3);
          expect(alpha, greaterThan(150), reason: '$flag leaves ($x, $y) bare');
        }
      }
    });
  }
}
