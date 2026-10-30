import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/slider.dart';
import 'package:fl_clash/widgets/palette.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      );
    }
  });

  for (final brightness in Brightness.values) {
    for (final direction in TextDirection.values) {
      for (final onGlass in [false, true]) {
        testWidgets(
          'palette keeps its color source through glass $brightness $direction '
          '${onGlass ? 'on a pane' : 'on the page'}',
          (tester) async {
            tester.view.physicalSize = const Size(400, 600);
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.reset);
            final color = ValueNotifier(const Color(0xFF007AFF));
            addTearDown(color.dispose);
            final capture = GlobalKey();
            await tester.pumpWidget(
              LiquidGlassScope(
                enabled: true,
                child: TestApp(
                  theme: ThemeData(brightness: brightness),
                  child: Directionality(
                    textDirection: direction,
                    child: RepaintBoundary(
                      key: capture,
                      child: LiquidGlassHost(
                        enabled: true,
                        wallpaperOnly: true,
                        child: Stack(
                          children: [
                            const Positioned.fill(
                              child: LiquidGlassColorField(
                                child: LiquidGlassColorSource(
                                  child: ColoredBox(color: Color(0xFF286C85)),
                                ),
                              ),
                            ),
                            Center(
                              child: Material(
                                type: MaterialType.transparency,
                                child: SizedBox(
                                  width: 360,
                                  child: GlassSurfaceScope(
                                    enabled: onGlass,
                                    child: Palette(controller: color),
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
              ),
            );
            await tester.pumpAndSettle();
            await tester.tap(find.text('Spectrum'));
            await tester.pumpAndSettle();
            final pad = tester.getRect(find.byType(AspectRatio));
            await tester.tapAt(pad.center);
            await tester.pumpAndSettle();
            expect(HSLColor.fromColor(color.value).hue, closeTo(180, 2));
            final spectrumGlass = LiquidGlassHost.debugLastCapture!.panes;
            expect(spectrumGlass, hasLength(onGlass ? 0 : 2));
            final boundary = tester.renderObject<RenderRepaintBoundary>(
              find.byKey(capture),
            );
            await tester.runAsync(() async {
              final image = await boundary.toImage();
              try {
                final bytes = (await image.toByteData())!;
                final index =
                    (pad.center.dy.floor() * image.width +
                        pad.center.dx.floor()) *
                    4;
                final actual = Color.fromARGB(
                  bytes.getUint8(index + 3),
                  bytes.getUint8(index),
                  bytes.getUint8(index + 1),
                  bytes.getUint8(index + 2),
                );
                expect(actual, color.value);
              } finally {
                image.dispose();
              }
            });
            await tester.tap(find.text('Sliders'));
            await tester.pumpAndSettle();
            expect(tester.takeException(), isNull);
            expect(find.byType(Slider), findsNWidgets(3));
            expect(find.byType(GlassSlider), findsNWidgets(3));
            expect(
              LiquidGlassHost.debugLastCapture?.panes ?? const [],
              hasLength(onGlass ? 0 : 4),
            );
            final before = color.value;
            await tester.drag(find.byType(Slider).first, const Offset(80, 0));
            await tester.pumpAndSettle();
            expect(color.value, isNot(before));
            expect(tester.takeException(), isNull);
          },
        );
      }
    }
  }
}
