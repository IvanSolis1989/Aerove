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
  tint: Color(0x33FF0000), blur: 0, refraction: 0, bevel: 0, rim: 0,
);
const _top = Rect.fromLTWH(0, 0, 320, 48);
const _crossing = Rect.fromLTWH(0, 20, 320, 160);
const _outside = Rect.fromLTWH(16, 192, 100, 36);

Widget _pane(Rect rect) => Positioned.fromRect(rect: rect,
  child: const LiquidGlass(style: _style,
    clipper: ShapeBorderClipper(shape: AppShape.none)));

Future<List<int>> _shot(WidgetTester tester, String tag) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('$output/header-occlusion-$tag.png').writeAsBytesSync(png.buffer.asUint8List());
      }
      return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
          .buffer.asUint8List().toList();
    } finally {
      image.dispose();
    }
  }))!;
}

List<int> _pixel(List<int> bytes, int x, int y) {
  final index = (y * 320 + x) * 4;
  return bytes.sublist(index, index + 4);
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag')
          .timeout(const Duration(seconds: 30));
    }
  });

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('transformed feather matches premultiplied frozen live oracle $quality', (tester) async {
      tester.view.physicalSize = const Size(320, 240);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      Future<void> render(Color color, int cover) async {
        const marker = LiquidGlassOcclusion(shape: AppShape.none,
          bottomFeather: 12, child: SizedBox.expand());
        await tester.pumpWidget(MaterialApp(home: RepaintBoundary(key: _scene,
          child: LiquidGlassScope(enabled: true, quality: quality,
            child: LiquidGlassHost(enabled: true, wallpaperOnly: true,
              child: Stack(fit: StackFit.expand, children: [
                LiquidGlassColorField(child: LiquidGlassColorSource(
                  child: ColoredBox(color: color))),
                const Positioned(left: 40, top: 60, width: 120, height: 120,
                  child: LiquidGlass(style: LiquidGlassStyle(
                    tint: Color(0x33FF0000), blur: 0, refraction: 0,
                    bevel: 0, rim: 0.6, rimWidth: 1, hairline: 0.2),
                    clipper: ShapeBorderClipper(shape: AppShape.circle))),
                if (cover == 1) const Positioned.fill(child: LiquidGlassOcclusion(
                  shape: AppShape.none, child: SizedBox.expand())),
                if (cover >= 2) Positioned(left: 20, top: 10, width: 200, height: 80,
                  child: Transform.scale(scale: 1.25, alignment: Alignment.topLeft,
                    child: cover == 3 ? const Opacity(opacity: 0.5, child: marker) : marker)),
                if (cover == 4) const Positioned(left: 60, top: 114, width: 20, height: 6,
                  child: LiquidGlassOcclusion(shape: AppShape.none, child: SizedBox.expand())),
              ]),
            ),
          ),
        )));
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
      final cost = LiquidGlassHost.debugLastFeatherCapture!;
      expect(cost.panes, 1);
      expect(cost.stripPixels, 120 * 15);
      expect(cost.surfacePixels, 120 * 15 * 3);
      await render(Colors.green, 4);
      final crossed = await _shot(tester, 'oracle-crossed-$quality');
      expect(LiquidGlassHost.debugLastFeatherCapture!.surfacePixels, 120 * 15 * 5);
      await render(Colors.green, 0);
      final live = await _shot(tester, 'oracle-live-$quality');
      var changedPixels = 0;
      for (var y = 110; y < 125; y++) {
        final t = (y + 0.5 - 110) / 15;
        final weight = t * t * (3 - 2 * t);
        for (var x = 0; x < 320; x++) {
          final index = (y * 320 + x) * 4;
          if ((frozen[index] - live[index]).abs() > 4) changedPixels++;
          for (var channel = 0; channel < 4; channel++) {
            final expected = frozen[index + channel] * (1 - weight) +
                live[index + channel] * weight;
            expect(mixed[index + channel], closeTo(expected, 2),
              reason: 'premultiplied circular rim $x,$y channel $channel');
            final crossedExpected = x >= 60 && x < 80 && y >= 114 && y < 120
                ? frozen[index + channel].toDouble() : expected;
            expect(crossed[index + channel], closeTo(crossedExpected, 2),
              reason: 'hard modal intersection $x,$y channel $channel');
          }
        }
      }
      expect(changedPixels, greaterThan(500));
      for (final row in [100, 140]) {
        final expected = row < 110 ? frozen : live;
        for (var x = 0; x < 320; x++) {
          final index = (row * 320 + x) * 4;
          for (var channel = 0; channel < 4; channel++) {
            expect(mixed[index + channel], closeTo(expected[index + channel], 2),
              reason: 'outside band circular edge $x,$row channel $channel');
          }
        }
      }
      expect(_pixel(mixed, 5, 116), _pixel(live, 5, 116),
        reason: 'nothing outside the circular pane is tinted');
      expect(_pixel(mixed, 100, 100), _pixel(frozen, 100, 100),
        reason: 'the whole covered region stays frozen');
      expect(_pixel(mixed, 100, 140), _pixel(live, 100, 140));
      expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 0);
      await render(Colors.blue, 1);
      await render(Colors.green, 3);
      final translucent = await _shot(tester, 'opacity-fallback-$quality');
      expect(translucent, live, reason: 'translucent ancestors cannot freeze underlay');
      expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 0);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    });

    testWidgets('header freezes the full fade and feathers only below it $quality', (tester) async {
      tester.view.physicalSize = const Size(320, 240);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      Future<void> render({Color color = Colors.blue, bool text = true,
        double shift = 0}) async {
        await tester.pumpWidget(MaterialApp(home: RepaintBoundary(key: _scene,
          child: LiquidGlassScope(enabled: true, quality: quality,
            child: LiquidGlassHost(enabled: true, wallpaperOnly: true,
              child: Stack(fit: StackFit.expand, children: [
                LiquidGlassColorField(child: LiquidGlassColorSource(
                  child: ColoredBox(color: color))),
                Transform.translate(offset: Offset(0, shift), child: Stack(children: [
                  _pane(_top), _pane(_crossing),
                  if (text) const Positioned(left: 20, top: 8, width: 120,
                    child: GlassText('HHHHHH', inkRole: GlassInkRole.body,
                      style: TextStyle(fontSize: 20, color: Colors.white))),
                ])),
                _pane(_outside),
                const Positioned.fill(child: IgnorePointer(child: HeaderBlur(
                  fadeHeight: 120, fullStrengthHeight: 48))),
              ]),
            ),
          ),
        )));
        await tester.pump();
        await tester.pump();
      }
      await render(text: false);
      final empty = await _shot(tester, 'empty-$quality');
      await render();
      final initial = await _shot(tester, 'initial-$quality');
      expect(tester.getRect(find.byType(LiquidGlassOcclusion)), const Rect.fromLTWH(0, 0, 320, 120));
      expect(tester.getSize(find.byType(BackdropFilter)), const Size(320, 120));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
      expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 1);
      expect(LiquidGlassHost.debugLastCapture!.panes.map((pane) => pane.pane),
        containsAll([_crossing, _outside]));
      expect(LiquidGlassHost.debugLastCapture!.panes.map((pane) => pane.pane),
        isNot(contains(_top)));
      var inkDifference = 0;
      for (var y = 0; y < 46; y++) {
        for (var x = 10; x < 150; x++) {
          final index = (y * 320 + x) * 4;
          for (var channel = 0; channel < 3; channel++) {
            inkDifference += (initial[index + channel] - empty[index + channel]).abs();
          }
        }
      }
      expect(inkDifference, greaterThan(500), reason: 'first covered frame must retain text');
      if (ui.ImageFilter.isShaderFilterSupported) {
        expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 1);
      }
      await render(color: Colors.green);
      final moving = await _shot(tester, 'moving-$quality');
      for (var channel = 0; channel < 3; channel++) {
        expect(_pixel(moving, 280, 8)[channel], closeTo(_pixel(initial, 280, 8)[channel], 2));
      }
      expect(_pixel(moving, 160, 150), isNot(_pixel(initial, 160, 150)),
        reason: 'the part below the header keeps sampling the moving field');
      for (var y = 32; y < 128; y++) {
        for (var channel = 0; channel < 3; channel++) {
          expect((_pixel(moving, 160, y)[channel] - _pixel(moving, 160, y + 1)[channel]).abs(),
            lessThanOrEqualTo(18), reason: 'no hard cached/live seam at row $y');
        }
      }
      expect(LiquidGlassHost.debugLastFeatherCapture!.panes, 1);
      expect(LiquidGlassHost.debugLastFeatherCapture!.stripPixels, 320 * 12);
      expect(LiquidGlassHost.debugLastFeatherCapture!.surfacePixels, 320 * 12 * 3);
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
    });
  }

  testWidgets('none and high contrast bypass coverage while core zero covers the fade', (tester) async {
    for (final enabled in [false, true]) {
      for (final highContrast in [false, true]) {
        for (final core in [0.0, 48.0]) {
          await tester.pumpWidget(MaterialApp(home: MediaQuery(
            data: MediaQueryData(highContrast: highContrast),
            child: LiquidGlassScope(enabled: enabled,
              child: HeaderBlur(fadeHeight: 120, fullStrengthHeight: core)),
          )));
          expect(find.byType(LiquidGlassOcclusion),
            enabled && !highContrast ? findsOneWidget : findsNothing);
          expect(find.byType(BackdropFilter), findsOneWidget);
        }
      }
    }
    await tester.pumpWidget(const MaterialApp(home: HeaderBlur(fadeHeight: 0)));
    expect(find.byType(BackdropFilter), findsNothing);
  });
}
