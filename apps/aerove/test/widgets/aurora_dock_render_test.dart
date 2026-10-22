import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/aurora.dart' show SeededAuroraWallpaper;
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void _expectBackdropHue(List<double> channels, Color backdrop) {
  for (final channel in channels) {
    expect(channel, inInclusiveRange(2, 253));
  }
  final sampled = Color.fromARGB(
    255,
    channels[0].round(),
    channels[1].round(),
    channels[2].round(),
  );
  final original = HSVColor.fromColor(backdrop);
  final actual = HSVColor.fromColor(sampled);
  final originalChroma = original.saturation * original.value;
  final actualChroma = actual.saturation * actual.value;
  expect(actualChroma, lessThanOrEqualTo(originalChroma * 1.15));
  if (actualChroma > 0.03) {
    final difference = (original.hue - actual.hue).abs();
    expect(math.min(difference, 360 - difference), lessThanOrEqualTo(15));
  }
}

void main() {
  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('resting Aurora lens transmits the backdrop on '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(400, 180);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final key = GlobalKey();
        Future<List<double>> render(
          bool aurora,
          Color backdrop, {
          bool wallpaper = false,
          bool glass = true,
        }) async {
          await tester.pumpWidget(
            TestApp(
              theme: ThemeData(colorScheme: auroraColorScheme(brightness)),
              child: RepaintBoundary(
                key: key,
                child: AuroraAppearance(
                  enabled: aurora,
                  child: LiquidGlassScope(
                    enabled: glass,
                    quality: quality,
                    child: LiquidGlassHost(
                      enabled: true,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          if (wallpaper)
                            SeededAuroraWallpaper(
                              colors: const [
                                Color(0xFF65BDD2),
                                Color(0xFFC589CA),
                              ],
                              seed: 271828,
                              brightness: brightness,
                            )
                          else
                            ColoredBox(color: backdrop),
                          Align(
                            alignment: Alignment.bottomCenter,
                            child: NavigationDock(
                              destinations: const [
                                NavigationDockDestination(
                                  glyph: AppGlyphs.dashboard,
                                  label: 'Dashboard',
                                ),
                                NavigationDockDestination(
                                  glyph: AppGlyphs.tools,
                                  label: 'Tools',
                                ),
                              ],
                              selectedIndex: 0,
                              onSelected: (_) {},
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
          if (glass) {
            for (final pane in tester.widgetList<LiquidGlass>(
              find.byType(LiquidGlass),
            )) {
              expect(pane.style.saturation, 1);
              expect(pane.style.brightness, 1);
              expect(pane.style.contrast, 0);
            }
          }
          final lens = tester.getRect(
            find.descendant(
              of: find.byType(FloatingNavigationBar),
              matching: find.byType(PositionedDirectional),
            ),
          );
          final boundary =
              key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          final origin = boundary.localToGlobal(Offset.zero);
          final sample = Rect.fromLTWH(
            lens.left - origin.dx + 16,
            lens.center.dy - origin.dy - 3,
            4,
            6,
          );
          expect(sample.left, greaterThan(0));
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final bytes = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              final channels = [0.0, 0.0, 0.0];
              var count = 0;
              for (var y = sample.top.ceil(); y < sample.bottom.floor(); y++) {
                for (
                  var x = sample.left.ceil();
                  x < sample.right.floor();
                  x++
                ) {
                  final offset = (y * image.width + x) * 4;
                  for (var channel = 0; channel < 3; channel++) {
                    channels[channel] += bytes.getUint8(offset + channel);
                  }
                  count++;
                }
              }
              expect(count, greaterThan(0));
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                final directory = Directory(output)
                  ..createSync(recursive: true);
                final mode = aurora ? 'aurora' : 'ordinary';
                final effect = glass ? quality.name : '${quality.name}-none';
                final tint = wallpaper
                    ? 'wallpaper'
                    : backdrop.toARGB32().toRadixString(16);
                File(
                  '${directory.path}/dock-$mode-${brightness.name}-'
                  '$effect-$tint.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return [for (final value in channels) value / count];
            } finally {
              image.dispose();
            }
          }))!;
        }

        const first = Color(0xFF194F78);
        const second = Color(0xFF854652);
        final ordinaryFirst = await render(false, first, glass: false);
        final ordinarySecond = await render(false, second, glass: false);
        final clearFirst = await render(true, first);
        final clearSecond = await render(true, second);
        double difference(List<double> a, List<double> b) => List.generate(
          3,
          (index) => math.pow(a[index] - b[index], 2),
        ).reduce((a, b) => a + b).toDouble();
        expect(
          difference(clearFirst, clearSecond),
          greaterThan(difference(ordinaryFirst, ordinarySecond) * 2),
          reason:
              'The resting selection must reveal the backdrop more than '
              'the ordinary theme platter.',
        );
        final plainGlassFirst = await render(false, first);
        final plainGlassSecond = await render(false, second);
        expect(
          difference(plainGlassFirst, plainGlassSecond),
          greaterThan(difference(ordinaryFirst, ordinarySecond) * 2),
        );
        _expectBackdropHue(clearFirst, first);
        _expectBackdropHue(clearSecond, second);
        _expectBackdropHue(plainGlassFirst, first);
        _expectBackdropHue(plainGlassSecond, second);
        await render(true, first, wallpaper: true);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
