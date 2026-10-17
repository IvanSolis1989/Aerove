import 'dart:ui' as ui;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  test('cloud geometry is reproducible and bounded for a saved seed', () {
    final clouds = generateAuroraClouds(271828);
    expect(clouds, generateAuroraClouds(271828));
    expect(clouds, isNot(generateAuroraClouds(271829)));
    expect(clouds, hasLength(8));
    for (final cloud in clouds) {
      expect(cloud.x, inInclusiveRange(-0.1, 1.1));
      expect(cloud.y, inInclusiveRange(0.1, 1.15));
      expect(cloud.radiusX, inInclusiveRange(0.3, 0.65));
      expect(cloud.radiusY, inInclusiveRange(0.2, 0.45));
    }
  });

  test('normalizes one to three opaque colors without inventing random hues', () {
    expect(normalizeAuroraColors([]), isNotEmpty);
    expect(normalizeAuroraColors([const Color(0x00ABCDEF)]), [
      const Color(0xFFABCDEF),
    ]);
    expect(normalizeAuroraColors(List.filled(8, Colors.red)), hasLength(3));
  });

  testWidgets('rebuilding a wallpaper keeps identical pixels', (tester) async {
    final key = GlobalKey();
    Future<List<int>> render(int seed) async {
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Center(
            child: RepaintBoundary(
              key: key,
              child: SizedBox(
                width: 160,
                height: 240,
                child: SeededAuroraWallpaper(
                  colors: const [Color(0xFF95AEC9), Color(0xFFC6B8CF)],
                  seed: seed,
                  brightness: Brightness.light,
                ),
              ),
            ),
          ),
        ),
      );
      final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      return (await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final bytes = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
          return bytes!.buffer.asUint8List().toList();
        } finally {
          image.dispose();
        }
      }))!;
    }

    final first = await render(12);
    expect(await render(12), first);
    expect(await render(13), isNot(first));
    for (var i = 0; i < first.length; i += 4) {
      expect(first[i + 3], 255);
      expect(first[i], greaterThan(215));
      expect(first[i + 1], greaterThan(215));
      expect(first[i + 2], greaterThan(215));
    }
  });

  testWidgets('glass has local blur only when the appearance is enabled', (tester) async {
    Future<void> pump(bool enabled) => tester.pumpWidget(
      MaterialApp(
        home: AuroraAppearance(
          enabled: enabled,
          child: const Center(
            child: AuroraBackdrop(
              child: SizedBox(width: 100, height: 50),
            ),
          ),
        ),
      ),
    );
    await pump(false);
    expect(find.byType(BackdropFilter), findsNothing);
    await pump(true);
    expect(find.byType(BackdropFilter), findsOneWidget);
  });
}
