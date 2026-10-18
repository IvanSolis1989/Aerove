import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/header_blur.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _scene = ValueKey('header-occlusion-scene');
const _style = LiquidGlassStyle(
  tint: Color(0x33FF0000),
  blur: 0,
  refraction: 0,
  bevel: 0,
  rim: 0,
);
const _top = Rect.fromLTWH(0, 0, 320, 48);
const _crossing = Rect.fromLTWH(0, 20, 320, 160);
const _outside = Rect.fromLTWH(16, 192, 100, 36);

Widget _pane(Rect rect) => Positioned.fromRect(
  rect: rect,
  child: const LiquidGlass(
    style: _style,
    clipper: ShapeBorderClipper(shape: AppShape.none),
  ),
);

Future<List<int>> _shot(WidgetTester tester, String tag) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_scene),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/header-occlusion-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
      }
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List().toList();
    } finally {
      image.dispose();
    }
  }))!;
}

List<int> _pixel(List<int> bytes, int x, int y) {
  final index = (y * 320 + x) * 4;
  return bytes.sublist(index, index + 4);
}

Future<Map<String, Object?>> _captureInput(
  WidgetTester tester,
  String tag,
) async {
  final pane = LiquidGlassHost.debugLastCapture!.panes.single;
  final atlas = pane.atlas.clone();
  final input = <String, Object?>{
    'slot': [pane.slot.left, pane.slot.top, pane.slot.right, pane.slot.bottom],
    'place': [
      pane.place.left,
      pane.place.top,
      pane.place.right,
      pane.place.bottom,
    ],
    'scale': pane.scale,
    'size': [atlas.width, atlas.height],
    'uniforms': List.of(LiquidGlassHost.debugLastPaneUniforms!),
    'ctm': List.of(LiquidGlassHost.debugLastPaneTransform!),
    'context': Map.of(LiquidGlassHost.debugLastPaneContext!),
  };
  await tester.runAsync(() async {
    try {
      final rgba = (await atlas.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
      final samples = <String, Object?>{};
      for (final point in [
        const Offset(159.5, 122.5),
        const Offset(40.5, 121.5),
      ]) {
        final pixel =
            pane.place.topLeft + (point - pane.slot.topLeft) * pane.scale;
        final x = pixel.dx.floor();
        final y = pixel.dy.floor();
        final index = (y * atlas.width + x) * 4;
        samples['${point.dx},${point.dy}'] = {
          'atlasPoint': [pixel.dx, pixel.dy],
          'rgba': rgba.sublist(index, index + 4),
        };
      }
      input['samples'] = samples;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        final png = (await atlas.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/header-input-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
        File(
          '$output/header-input-$tag.json',
        ).writeAsStringSync(jsonEncode(input));
      }
    } finally {
      atlas.dispose();
    }
  });
  return input;
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
      await ui.FragmentProgram.fromAsset(
        'shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });

  for (final quality in LiquidGlassQuality.values) {
    testWidgets(
      'transformed feather matches premultiplied frozen live oracle $quality',
      (tester) async {
        tester.view.physicalSize = const Size(320, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        addTearDown(() => LiquidGlassHost.debugFeatherMaskOneOverride = false);
        Future<void> render(
          Color color,
          int cover, {
          bool isolated = false,
        }) async {
          const marker = LiquidGlassOcclusion(
            shape: AppShape.none,
            bottomFeather: 12,
            child: SizedBox.expand(),
          );
          const pane = LiquidGlass(
            style: LiquidGlassStyle(
              tint: Color(0x33FF0000),
              blur: 0,
              refraction: 0,
              bevel: 0,
              rim: 0.6,
              rimWidth: 1,
              hairline: 0.2,
            ),
            clipper: ShapeBorderClipper(shape: AppShape.circle),
          );
          await tester.pumpWidget(
            MaterialApp(
              home: RepaintBoundary(
                key: _scene,
                child: LiquidGlassScope(
                  enabled: true,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: true,
                    wallpaperOnly: true,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        LiquidGlassColorField(
                          child: LiquidGlassColorSource(
                            child: ColoredBox(color: color),
                          ),
                        ),
                        Positioned(
                          left: 40,
                          top: 60,
                          width: 120,
                          height: 120,
                          child: isolated
                              ? const RepaintBoundary(
                                  key: ValueKey('resolved-live-pane'),
                                  child: pane,
                                )
                              : pane,
                        ),
                        if (cover == 1)
                          const Positioned.fill(
                            child: LiquidGlassOcclusion(
                              shape: AppShape.none,
                              child: SizedBox.expand(),
                            ),
                          ),
                        if (cover >= 2)
                          Positioned(
                            left: 20,
                            top: 10,
                            width: 200,
                            height: 80,
                            child: Transform.scale(
                              scale: 1.25,
                              alignment: Alignment.topLeft,
                              child: cover == 3
                                  ? const Opacity(opacity: 0.5, child: marker)
                                  : marker,
                            ),
                          ),
                        if (cover == 4)
                          const Positioned(
                            left: 60,
                            top: 114,
                            width: 20,
                            height: 6,
                            child: LiquidGlassOcclusion(
                              shape: AppShape.none,
                              child: SizedBox.expand(),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pump();
          await tester.pump();
        }

        await render(Colors.blue, 0);
        await render(Colors.blue, 1);
        await render(Colors.green, 1);
        final frozen = await _shot(tester, 'oracle-frozen-$quality');
        expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 0);
        await render(Colors.green, 2);
        final mixed = await _shot(tester, 'oracle-mixed-$quality');
        final mixedInput = await _captureInput(tester, 'mixed-$quality');
        final cost = LiquidGlassHost.debugLastFeatherCapture!;
        expect(cost.panes, 1);
        expect(cost.stripPixels, 120 * 15);
        expect(cost.surfacePixels, 120 * 15 * 3);
        LiquidGlassHost.debugFeatherMaskOneOverride = true;
        await render(Colors.green, 2);
        final maskOne = await _shot(tester, 'oracle-mask-one-$quality');
        final maskOneInput = await _captureInput(tester, 'mask-one-$quality');
        expect(maskOneInput, mixedInput);
        LiquidGlassHost.debugFeatherMaskOneOverride = false;
        await render(Colors.green, 2);
        expect(await _shot(tester, 'oracle-mixed-repeat-$quality'), mixed);
        await render(Colors.green, 4);
        final crossed = await _shot(tester, 'oracle-crossed-$quality');
        expect(
          LiquidGlassHost.debugLastFeatherCapture!.surfacePixels,
          120 * 15 * 5,
        );
        await render(Colors.green, 0);
        final live = await _shot(tester, 'oracle-live-$quality');
        await _captureInput(tester, 'live-$quality');
        await render(Colors.green, 0, isolated: true);
        await _shot(tester, 'oracle-isolated-live-$quality');
        await _captureInput(tester, 'isolated-live-$quality');
        final resolved = List<int>.of(live);
        final nativeStrip = List<int>.of(live);
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        final control = tester.renderObject<RenderRepaintBoundary>(
          find.byKey(const ValueKey('resolved-live-pane')),
        );
        await tester.runAsync(() async {
          final image = await control.toImage();
          try {
            expect(image.width, 120);
            expect(image.height, 120);
            final rgba = (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!.buffer.asUint8List();
            expect(rgba[3], 0);
            expect(rgba[(60 * 120 + 60) * 4 + 3], 255);
            if (output != null) {
              final png = (await image.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              File(
                '$output/header-native-full-$quality.png',
              ).writeAsBytesSync(png.buffer.asUint8List());
            }
            final background = _pixel(live, 5, 116);
            for (var y = 0; y < 120; y++) {
              for (var x = 0; x < 120; x++) {
                final from = (y * 120 + x) * 4;
                final to = ((y + 60) * 320 + x + 40) * 4;
                final alpha = rgba[from + 3] / 255;
                for (var channel = 0; channel < 3; channel++) {
                  resolved[to + channel] =
                      (rgba[from + channel] + background[channel] * (1 - alpha))
                          .round()
                          .clamp(0, 255);
                }
              }
            }
          } finally {
            image.dispose();
          }
          final strip = await (control.debugLayer! as OffsetLayer).toImage(
            const Rect.fromLTWH(0, 50, 120, 15),
          );
          try {
            expect(strip.width, 120);
            expect(strip.height, 15);
            final rgba = (await strip.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!.buffer.asUint8List();
            expect(rgba[(7 * 120 + 60) * 4 + 3], 255);
            final background = _pixel(live, 5, 116);
            for (var y = 0; y < 15; y++) {
              for (var x = 0; x < 120; x++) {
                final from = (y * 120 + x) * 4;
                final to = ((y + 110) * 320 + x + 40) * 4;
                final alpha = rgba[from + 3] / 255;
                for (var channel = 0; channel < 3; channel++) {
                  nativeStrip[to + channel] =
                      (rgba[from + channel] + background[channel] * (1 - alpha))
                          .round()
                          .clamp(0, 255);
                }
              }
            }
            if (output != null) {
              final png = (await strip.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              File(
                '$output/header-native-strip-$quality.png',
              ).writeAsBytesSync(png.buffer.asUint8List());
            }
          } finally {
            strip.dispose();
          }
        });
        if (output != null) {
          File(
            '$output/header-aa-endpoints-${quality.name}.json',
          ).writeAsStringSync(
            jsonEncode({
              'direct': _pixel(live, 159, 122),
              'resolved': _pixel(resolved, 159, 122),
              'nativeStrip': _pixel(nativeStrip, 159, 122),
              'frozen': _pixel(frozen, 159, 122),
              'mixed': _pixel(mixed, 159, 122),
              'maskOne': _pixel(maskOne, 159, 122),
              'otherEdge': {
                'position': [40, 121],
                'direct': _pixel(live, 40, 121),
                'resolved': _pixel(resolved, 40, 121),
                'nativeStrip': _pixel(nativeStrip, 40, 121),
                'frozen': _pixel(frozen, 40, 121),
                'mixed': _pixel(mixed, 40, 121),
                'maskOne': _pixel(maskOne, 40, 121),
              },
            }),
          );
        }
        var changedPixels = 0;
        for (var y = 110; y < 125; y++) {
          final t = (y + 0.5 - 110) / 15;
          final stop = (t * 16).floor();
          final before = stop / 16;
          final after = (stop + 1) / 16;
          final a = before * before * (3 - 2 * before);
          final b = after * after * (3 - 2 * after);
          final weight = a + (b - a) * (t * 16 - stop);
          for (var x = 0; x < 320; x++) {
            final index = (y * 320 + x) * 4;
            if ((frozen[index] - live[index]).abs() > 4) changedPixels++;
            for (var channel = 0; channel < 4; channel++) {
              expect(
                maskOne[index + channel],
                closeTo(nativeStrip[index + channel], 2),
                reason:
                    'independent native strip endpoint $x,$y channel $channel',
              );
              final expected =
                  frozen[index + channel] * (1 - weight) +
                  nativeStrip[index + channel] * weight;
              expect(
                mixed[index + channel],
                closeTo(expected, 2),
                reason: 'premultiplied circular rim $x,$y channel $channel',
              );
              final crossedExpected = x >= 60 && x < 80 && y >= 114 && y < 120
                  ? frozen[index + channel].toDouble()
                  : expected;
              expect(
                crossed[index + channel],
                closeTo(crossedExpected, 2),
                reason: 'hard modal intersection $x,$y channel $channel',
              );
            }
          }
        }
        expect(changedPixels, greaterThan(500));
        for (final row in [100, 140]) {
          final expected = row < 110 ? frozen : live;
          for (var x = 0; x < 320; x++) {
            final index = (row * 320 + x) * 4;
            for (var channel = 0; channel < 4; channel++) {
              expect(
                mixed[index + channel],
                closeTo(expected[index + channel], 2),
                reason: 'outside band circular edge $x,$row channel $channel',
              );
            }
          }
        }
        expect(
          _pixel(mixed, 5, 116),
          _pixel(live, 5, 116),
          reason: 'nothing outside the circular pane is tinted',
        );
        expect(
          _pixel(mixed, 100, 100),
          _pixel(frozen, 100, 100),
          reason: 'the whole covered region stays frozen',
        );
        expect(_pixel(mixed, 100, 140), _pixel(live, 100, 140));
        expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 0);
        await render(Colors.blue, 1);
        await render(Colors.green, 3);
        final translucent = await _shot(tester, 'opacity-fallback-$quality');
        expect(
          translucent,
          live,
          reason: 'translucent ancestors cannot freeze underlay',
        );
        expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 0);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      },
    );

    testWidgets(
      'header freezes the full fade and feathers only below it $quality',
      (tester) async {
        tester.view.physicalSize = const Size(320, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        Future<void> render({
          Color color = Colors.blue,
          bool text = true,
          double shift = 0,
        }) async {
          await tester.pumpWidget(
            MaterialApp(
              home: RepaintBoundary(
                key: _scene,
                child: LiquidGlassScope(
                  enabled: true,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: true,
                    wallpaperOnly: true,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        LiquidGlassColorField(
                          child: LiquidGlassColorSource(
                            child: ColoredBox(color: color),
                          ),
                        ),
                        Transform.translate(
                          offset: Offset(0, shift),
                          child: Stack(
                            children: [
                              _pane(_top),
                              _pane(_crossing),
                              if (text)
                                const Positioned(
                                  left: 20,
                                  top: 8,
                                  width: 120,
                                  child: GlassText(
                                    'HHHHHH',
                                    inkRole: GlassInkRole.body,
                                    style: TextStyle(
                                      fontSize: 20,
                                      color: Colors.white,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                        _pane(_outside),
                        const Positioned.fill(
                          child: IgnorePointer(
                            child: HeaderBlur(
                              fadeHeight: 120,
                              fullStrengthHeight: 48,
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
          await tester.pump();
          await tester.pump();
        }

        await render(text: false);
        final empty = await _shot(tester, 'empty-$quality');
        await render();
        final initial = await _shot(tester, 'initial-$quality');
        expect(
          tester.getRect(find.byType(LiquidGlassOcclusion)),
          const Rect.fromLTWH(0, 0, 320, 120),
        );
        expect(
          tester.getSize(find.byType(BackdropFilter)),
          const Size(320, 120),
        );
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
        expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 1);
        expect(
          LiquidGlassHost.debugLastCapture!.panes.map((pane) => pane.pane),
          containsAll([_crossing, _outside]),
        );
        expect(
          LiquidGlassHost.debugLastCapture!.panes.map((pane) => pane.pane),
          isNot(contains(_top)),
        );
        var inkDifference = 0;
        for (var y = 0; y < 46; y++) {
          for (var x = 10; x < 150; x++) {
            final index = (y * 320 + x) * 4;
            for (var channel = 0; channel < 3; channel++) {
              inkDifference +=
                  (initial[index + channel] - empty[index + channel]).abs();
            }
          }
        }
        expect(
          inkDifference,
          greaterThan(500),
          reason: 'first covered frame must retain text',
        );
        if (ui.ImageFilter.isShaderFilterSupported) {
          expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 1);
        }
        await render(color: Colors.green);
        final moving = await _shot(tester, 'moving-$quality');
        for (var channel = 0; channel < 3; channel++) {
          expect(
            _pixel(moving, 280, 8)[channel],
            closeTo(_pixel(initial, 280, 8)[channel], 2),
          );
        }
        expect(
          _pixel(moving, 160, 150),
          isNot(_pixel(initial, 160, 150)),
          reason: 'the part below the header keeps sampling the moving field',
        );
        for (var y = 32; y < 128; y++) {
          for (var channel = 0; channel < 3; channel++) {
            expect(
              (_pixel(moving, 160, y)[channel] -
                      _pixel(moving, 160, y + 1)[channel])
                  .abs(),
              lessThanOrEqualTo(18),
              reason: 'no hard cached/live seam at row $y',
            );
          }
        }
        expect(LiquidGlassHost.debugLastFeatherCapture!.panes, 1);
        expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 320 * 12);
        expect(
          LiquidGlassHost.debugLastFeatherCapture!.surfacePixels,
          320 * 12 * 3,
        );
        final stable = await _shot(tester, 'stable-$quality');
        expect(stable, moving);
        await render(color: Colors.green, shift: 132);
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
        expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 0);
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(3));
        expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 0);
        if (ui.ImageFilter.isShaderFilterSupported) {
          expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 0);
        }
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      },
    );
  }

  testWidgets(
    'none and high contrast bypass coverage while core zero covers the fade',
    (tester) async {
      for (final enabled in [false, true]) {
        for (final highContrast in [false, true]) {
          for (final core in [0.0, 48.0]) {
            await tester.pumpWidget(
              MaterialApp(
                home: MediaQuery(
                  data: MediaQueryData(highContrast: highContrast),
                  child: LiquidGlassScope(
                    enabled: enabled,
                    child: HeaderBlur(
                      fadeHeight: 120,
                      fullStrengthHeight: core,
                    ),
                  ),
                ),
              ),
            );
            expect(
              find.byType(LiquidGlassOcclusion),
              enabled && !highContrast ? findsOneWidget : findsNothing,
            );
            expect(find.byType(BackdropFilter), findsOneWidget);
          }
        }
      }
      await tester.pumpWidget(
        const MaterialApp(home: HeaderBlur(fadeHeight: 0)),
      );
      expect(find.byType(BackdropFilter), findsNothing);
    },
  );
}
