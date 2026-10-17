import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  for (final quality in LiquidGlassQuality.values) {
    testWidgets('scrolling cached glass cards preserves the backdrop ($quality)', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(400, 500);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      addTearDown(controller.dispose);
      final captureKey = GlobalKey();
      const gridKey = ValueKey('glass-grid');
      const trackedKey = ValueKey('glass-card-8');
      await tester.pumpWidget(
        MaterialApp(
          home: RepaintBoundary(
            key: captureKey,
            child: LiquidGlassScope(
              enabled: true,
              quality: quality,
              child: LiquidGlassHost(
                enabled: true,
                child: Stack(
                  children: [
                    const Positioned.fill(
                      child: ColoredBox(color: Color(0xFF0040FF)),
                    ),
                    GridView.builder(
                      key: gridKey,
                      controller: controller,
                      padding: EdgeInsets.zero,
                      addRepaintBoundaries: true,
                      itemCount: 60,
                      gridDelegate:
                          const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 2,
                            mainAxisExtent: 84,
                            mainAxisSpacing: 12,
                            crossAxisSpacing: 12,
                          ),
                      itemBuilder: (_, index) => CommonCard(
                        key: ValueKey('glass-card-$index'),
                        onPressed: () {},
                        child: const SizedBox.expand(),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      Future<Color> patch(String phase, {Key key = trackedKey}) async {
        final point = tester.getCenter(find.byKey(key));
        final boundary = tester.renderObject<RenderRepaintBoundary>(
          find.byKey(captureKey),
        );
        final local = boundary.globalToLocal(point);
        final image = boundary.toImageSync();
        try {
          return (await tester.runAsync(() async {
            final bytes = (await image.toByteData())!;
            final at = (local.dy.floor() * image.width + local.dx.floor()) * 4;
            final color = Color.fromARGB(
              bytes.getUint8(at + 3),
              bytes.getUint8(at),
              bytes.getUint8(at + 1),
              bytes.getUint8(at + 2),
            );
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null && !phase.startsWith('frame-')) {
              final png = (await image.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              await Directory(output).create(recursive: true);
              await File(
                '$output/xclash-glass-scroll-${quality.name}-$phase.png',
              ).writeAsBytes(png.buffer.asUint8List());
            }
            return color;
          }))!;
        } finally {
          image.dispose();
        }
      }

      final still = await patch('still');
      expect(still.b - still.r, greaterThan(0.1));
      void expectSameBackdrop(Color color) {
        for (final (actual, expected) in [
          (color.r, still.r),
          (color.g, still.g),
          (color.b, still.b),
        ]) {
          expect(actual, closeTo(expected, 2 / 255));
        }
      }

      final gesture = await tester.startGesture(
        tester.getCenter(find.byKey(gridKey)),
      );
      for (var frame = 0; frame < 8; frame++) {
        await gesture.moveBy(const Offset(0, -24));
        await tester.pump(const Duration(milliseconds: 16));
        expectSameBackdrop(await patch('frame-$frame'));
      }
      expectSameBackdrop(await patch('dragging'));
      expectSameBackdrop(
        await patch('entering', key: const ValueKey('glass-card-12')),
      );
      await gesture.up();
      controller.jumpTo(controller.offset);
      await tester.pumpAndSettle();
      expectSameBackdrop(await patch('settled'));
      expect(find.byType(LiquidGlassHost), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
