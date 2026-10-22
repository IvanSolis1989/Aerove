import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  testWidgets('wallpaper motion reuses the ink source texture', (tester) async {
    await tester.runAsync(GlassInk.prepare);
    tester.view.physicalSize = const Size(430, 932);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    Future<void> frame(Duration elapsed) async {
      await tester.pumpWidget(
        TestApp(
          includeNavigatorKey: false,
          child: LiquidGlassScope(
            enabled: true,
            quality: LiquidGlassQuality.ultra,
            child: LiquidGlassHost(
              enabled: true,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Positioned.fill(
                    child: LiquidGlassColorField(
                      child: SeededAuroraWallpaper(
                        colors: const [
                          Color(0xFF65BDD2),
                          Color(0xFFC589CA),
                          Color(0xFF75CCA8),
                        ],
                        seed: 271828,
                        brightness: Brightness.light,
                        motionTime: elapsed,
                      ),
                    ),
                  ),
                  const Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        GlassText('16.8 KB/s'),
                        GlyphIcon(AppGlyphs.dashboard),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);
    }

    await frame(Duration.zero);
    final before = LiquidGlassHost.debugLastInkCapture!;
    final supported = ui.ImageFilter.isShaderFilterSupported;
    expect(before.masks, supported ? greaterThan(0) : 0);
    expect(before.sourceCaptures, supported ? greaterThan(0) : 0);
    expect(before.fallbackCaptures, 0);
    for (var index = 1; index <= 30; index++) {
      await frame(Duration(milliseconds: index * 34));
    }
    final after = LiquidGlassHost.debugLastInkCapture!;
    debugPrint(
      'INK_MOTION_CAPTURE_GENERATIONS '
      '${before.generation} -> ${after.generation}',
    );
    expect(
      after.generation,
      before.generation,
      reason: 'A fixed wallpaper mesh only changes its sampling transform.',
    );
    expect(after.masks, before.masks);
    expect(after.sourceCaptures, before.sourceCaptures);
    expect(after.fallbackCaptures, 0);
    if (supported) {
      expect(after.filterUpdates, greaterThan(before.filterUpdates));
    } else {
      expect(after.filterUpdates, 0);
      expect(after.maskPixels, 0);
      expect(after.texturePixels, 0);
    }
    expect(find.text('16.8 KB/s'), findsOneWidget);
    expect(find.byType(GlyphIcon), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
}
