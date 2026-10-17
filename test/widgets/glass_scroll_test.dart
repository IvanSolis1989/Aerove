import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart' show StretchEffect;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(
        ui.ImageFilter.isShaderFilterSupported,
        isTrue,
        reason: 'The Impeller job must not silently use Skia.',
      );
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await Future.wait([
        ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
        ui.FragmentProgram.fromAsset('shaders/stretch_effect.frag'),
      ]);
    }
  });
  for (final quality in LiquidGlassQuality.values) {
    for (final bottom in [false, true]) {
      testWidgets(
        '${bottom ? 'bottom' : 'top'} stretch keeps glass and its rim ($quality)',
        (tester) async {
          tester.view.physicalSize = const Size(400, 500);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final controller = ScrollController();
          addTearDown(controller.dispose);
          final captureKey = GlobalKey();
          const gridKey = ValueKey('stretch-grid');
          final paneKey = ValueKey('stretch-pane-${bottom ? 54 : 4}');
          final theme = ThemeData(platform: TargetPlatform.android);
          await tester.pumpWidget(
            MaterialApp(
              theme: theme,
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
                          child: CustomPaint(painter: _StripeBackdrop()),
                        ),
                        ScrollConfiguration(
                          behavior: const ScrollBehavior().copyWith(
                            overscroll: false,
                            scrollbars: false,
                          ),
                          child: StretchingOverscrollIndicator(
                            axisDirection: AxisDirection.down,
                            child: GridView.builder(
                              key: gridKey,
                              controller: controller,
                              physics: const ClampingScrollPhysics(),
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
                              itemBuilder: (_, index) => LiquidGlass(
                                key: ValueKey('stretch-pane-$index'),
                                style: LiquidGlassStyle.regular(
                                  theme.colorScheme,
                                ),
                                clipper: const ShapeBorderClipper(
                                  shape: AppShape.lg,
                                ),
                                child: const SizedBox.expand(),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          );
          if (ui.ImageFilter.isShaderFilterSupported) {
            await tester.runAsync(() async {
              await Future.wait([
                ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
                ui.FragmentProgram.fromAsset('shaders/stretch_effect.frag'),
              ]);
            });
          }
          await tester.pumpAndSettle();
          if (bottom) {
            controller.jumpTo(controller.position.maxScrollExtent);
            await tester.pumpAndSettle();
          }

          Future<void> expectGlass(String phase) async {
            final boundary = tester.renderObject<RenderRepaintBoundary>(
              find.byKey(captureKey),
            );
            final rect = tester.getRect(find.byKey(paneKey));
            final image = boundary.toImageSync();
            try {
              await tester.runAsync(() async {
                final bytes = (await image.toByteData())!;
                Color pixel(int x, int y) {
                  final at = (y * image.width + x) * 4;
                  return Color.fromARGB(
                    bytes.getUint8(at + 3),
                    bytes.getUint8(at),
                    bytes.getUint8(at + 1),
                    bytes.getUint8(at + 2),
                  );
                }

                final center = boundary.globalToLocal(rect.center);
                final inside = pixel(center.dx.floor(), center.dy.floor());
                expect(inside.r, greaterThan(0.1), reason: '$phase red blur');
                expect(inside.b, greaterThan(0.1), reason: '$phase blue blur');
                var rim = 0.0;
                final x = center.dx.floor();
                final top = (rect.top - 32)
                    .floor()
                    .clamp(0, image.height - 1)
                    .toInt();
                final end = (rect.bottom + 32)
                    .ceil()
                    .clamp(0, image.height - 1)
                    .toInt();
                for (var y = top; y <= end; y++) {
                  final green = pixel(x, y).g;
                  if (green > rim) rim = green;
                }
                expect(rim, greaterThan(0.015), reason: '$phase visible rim');
                final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
                if (output != null && !phase.startsWith('frame-')) {
                  final png = (await image.toByteData(
                    format: ui.ImageByteFormat.png,
                  ))!;
                  await Directory(output).create(recursive: true);
                  await File(
                    '$output/xclash-glass-stretch-${quality.name}-${bottom ? 'bottom' : 'top'}-$phase.png',
                  ).writeAsBytes(png.buffer.asUint8List());
                }
              });
            } finally {
              image.dispose();
            }
          }

          double strength() => tester
              .widget<StretchEffect>(find.byType(StretchEffect))
              .stretchStrength
              .abs();
          await expectGlass('still');
          final gesture = await tester.startGesture(
            tester.getCenter(find.byKey(gridKey)),
          );
          for (var frame = 0; frame < 6; frame++) {
            await gesture.moveBy(Offset(0, bottom ? -24 : 24));
            await tester.pump(const Duration(milliseconds: 16));
            if (frame > 0) {
              expect(strength(), greaterThan(0));
              await expectGlass('frame-pull-$frame');
            }
          }
          await expectGlass('pulled');
          if (ui.ImageFilter.isShaderFilterSupported) {
            expect(
              tester.widgetList<ImageFiltered>(
                find.byType(ImageFiltered),
              ).where((filter) => filter.enabled),
              isNotEmpty,
            );
          }
          await gesture.up();
          var bounceFrames = 0;
          for (var frame = 0; frame < 12; frame++) {
            await tester.pump(const Duration(milliseconds: 16));
            if (strength() > 0) {
              bounceFrames++;
              await expectGlass('frame-bounce-$frame');
            }
          }
          expect(bounceFrames, greaterThan(2));
          await expectGlass('rebounding');
          await tester.pumpAndSettle();
          expect(strength(), 0);
          await expectGlass('settled');
          expect(find.byType(LiquidGlassHost), findsOneWidget);
          expect(tester.takeException(), isNull);
        },
      );
    }
    testWidgets(
      'scrolling cached glass cards preserves the backdrop ($quality)',
      (tester) async {
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
              final at =
                  (local.dy.floor() * image.width + local.dx.floor()) * 4;
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
      },
    );
  }
}

class _StripeBackdrop extends CustomPainter {
  const _StripeBackdrop();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint();
    for (var x = 0; x < size.width; x += 8) {
      paint.color = (x ~/ 8).isEven
          ? const Color(0xFFFF0000)
          : const Color(0xFF0000FF);
      canvas.drawRect(Rect.fromLTWH(x.toDouble(), 0, 8, size.height), paint);
    }
  }

  @override
  bool shouldRepaint(_StripeBackdrop oldDelegate) => false;
}
