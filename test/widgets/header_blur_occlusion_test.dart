import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/header_blur.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/widgets/aurora.dart';
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

Future<List<int>> _shot(WidgetTester tester, String tag, {double pixelRatio = 1}) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_scene),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: pixelRatio);
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
    testWidgets('modal freeze takes priority over header plain $quality', (tester) async {
      tester.view.physicalSize = const Size(320, 240);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      Future<List<int>> render(Color color, bool header, {double height = 200, bool modalFeather = false, double modalBottom = 124}) async {
        await tester.pumpWidget(MaterialApp(home: RepaintBoundary(key: _scene,
          child: LiquidGlassScope(enabled: true, quality: quality,
            child: LiquidGlassHost(enabled: true, wallpaperOnly: true,
              child: Stack(fit: StackFit.expand, children: [
                LiquidGlassColorField(child: LiquidGlassColorSource(child: ColoredBox(color: color))),
                _pane(Rect.fromLTWH(0, 0, 320, height)),
                Positioned(left: 0, top: 0, width: 160, height: modalFeather ? modalBottom : 200,
                  child: LiquidGlassOcclusion(shape: AppShape.none, bottomFeather: modalFeather ? 12 : 0,
                    child: const SizedBox.expand())),
                if (header) const Positioned.fill(child: HeaderBlur(fadeHeight: 120, fullStrengthHeight: 48)),
              ]),
            ),
          ),
        )));
        await tester.pump();
        await tester.pump();
        return _shot(tester, 'modal-header-$quality-${color.toARGB32().toRadixString(16)}-$header-$height-$modalFeather-$modalBottom');
      }
      final frozen = await render(Colors.blue, false);
      final mixed = await render(Colors.green, true);
      for (var channel = 0; channel < 4; channel++) {
        expect(_pixel(mixed, 80, 40)[channel], closeTo(_pixel(frozen, 80, 40)[channel], 2));
        expect(_pixel(mixed, 240, 40)[channel], closeTo(_pixel(mixed, 240, 170)[channel], 2));
      }
      expect(_pixel(mixed, 240, 40), isNot(_pixel(frozen, 240, 40)));
      expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
      expect(LiquidGlassHost.debugLastCapture!.panes.single.slot.contains(const Offset(240, 40)), isFalse);
      final full = await render(Colors.green, true, height: 80);
      expect(LiquidGlassHost.debugLastCapture!.panes, isEmpty);
      final recordings = LiquidGlassHost.debugLastCapture!.coveredRecordings;
      expect(recordings, greaterThan(0));
      for (var pass = 0; pass < 3; pass++) {
        await tester.pump(const Duration(milliseconds: 16));
        expect(await _shot(tester, 'mixed-covered-stable-$quality-$pass'), full);
        expect(LiquidGlassHost.debugLastCapture!.coveredRecordings, recordings,
          reason: 'same mixed coverage must reuse its recorded Picture');
      }
      await render(Colors.red, false, height: 80);
      final reentered = await render(Colors.red, true, height: 80);
      expect(LiquidGlassHost.debugLastCapture!.coveredRecordings, greaterThan(recordings));
      expect(reentered, isNot(full));
      await render(Colors.blue, true, modalFeather: true);
      for (final color in [Colors.green, Colors.red]) {
        final overlap = await render(color, true, modalFeather: true);
        final expected = color == Colors.green ? [112, 140, 64, 255] : [255, 0, 0, 255];
        for (final y in [126, 128, 134]) {
          for (var channel = 0; channel < 4; channel++) {
            expect(_pixel(overlap, 240, y)[channel], closeTo(expected[channel], 2),
              reason: 'header-only pixels cannot read the modal frozen frame $color $y');
          }
        }
        expect(_pixel(overlap, 80, 126)[2], greaterThan(expected[2] + 40),
          reason: 'the actual modal feather must keep its cached contribution');
      }
      await render(Colors.blue, true, height: 110, modalFeather: true, modalBottom: 100);
      final coveredOverlap = await render(Colors.red, true, height: 110, modalFeather: true, modalBottom: 100);
      expect(LiquidGlassHost.debugLastCapture!.panes, isEmpty);
      expect(_pixel(coveredOverlap, 120, 104)[2], greaterThan(80));
      expect(_pixel(coveredOverlap, 240, 104)[0], greaterThan(250));
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    });

    testWidgets('DPR3 header reveals moving Aurora across tabs and scroll positions $quality', (tester) async {
      tester.view.physicalSize = const Size(960, 720);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      Future<List<int>> render(double top, int tab, int seconds, {bool reference = false, bool header = true}) async {
        await tester.pumpWidget(MaterialApp(
          home: RepaintBoundary(key: _scene,
            child: LiquidGlassScope(enabled: true, quality: quality,
              child: LiquidGlassHost(enabled: true, wallpaperOnly: true,
                child: Stack(fit: StackFit.expand, children: [
                  LiquidGlassColorField(child: SeededAuroraWallpaper(
                    colors: const [Color(0xFF32C59A), Color(0xFF3648C7), Color(0xFFD33479)],
                    seed: 271828, brightness: Brightness.dark,
                    motionTime: Duration(seconds: seconds),
                  )),
                  Positioned(key: ValueKey('tab-$tab'), left: 16, top: top, width: 288, height: 80,
                    child: reference
                        ? const ClipRSuperellipse(borderRadius: AppRadius.xl,
                            child: ColoredBox(color: Color(0x33FF0000)))
                        : const LiquidGlass(style: _style,
                            clipper: ShapeBorderClipper(shape: AppShape.xl)),
                  ),
                  Positioned(left: 42, top: top + 26,
                    child: GlassText('VISIBLE TAB $tab', style: const TextStyle(color: Colors.white, fontSize: 16))),
                  if (header) const Positioned.fill(child: HeaderBlur(fadeHeight: 120, fullStrengthHeight: 48)),
                ]),
              ),
            ),
          ),
        ));
        final pixels = await _shot(tester, 'aurora-dpr3-$quality-$top-$tab-$seconds-$reference-$header', pixelRatio: 3);
        expect(pixels, hasLength(960 * 720 * 4));
        return pixels;
      }
      var elapsed = 0;
      var motionDifference = 0;
      List<int>? previous;
      for (final top in [12.0, 90.0, 150.0]) {
        for (final tab in [0, 1, 0]) {
          final actual = await render(top, tab, elapsed);
          final capture = LiquidGlassHost.debugLastCapture!;
          expect(LiquidGlassHost.debugLastFlatCapture!.groups, 0);
          if (top == 12) {
            expect(capture.panes, isEmpty,
              reason: 'fully header-covered panes must skip the atlas');
          }
          final reference = await render(top, tab, elapsed, reference: true);
          var maximum = 0;
          var worst = '';
          for (var y = 0; y < 300; y++) {
            for (var x = 0; x < 960; x++) {
              final index = (y * 960 + x) * 4;
              for (var channel = 0; channel < 4; channel++) {
                final delta = (actual[index + channel] - reference[index + channel]).abs();
                if (delta > maximum) {
                  maximum = delta;
                  worst = '$x,$y channel $channel';
                }
              }
            }
          }
          expect(maximum, lessThanOrEqualTo(2),
            reason: 'no stale header band $top tab$tab t$elapsed $worst');
          for (var y = 100; y < 135; y++) {
            final a = ((y * 3) * 960 + 240 * 3) * 4;
            final b = (((y + 1) * 3) * 960 + 240 * 3) * 4;
            for (var channel = 0; channel < 3; channel++) {
              expect((actual[a + channel] - actual[b + channel]).abs(), lessThanOrEqualTo(18),
                reason: 'DPR3 real header seam $top $y channel $channel');
            }
          }
          final index = (200 * 3 * 960 + 300 * 3) * 4;
          if (previous != null) {
            for (var channel = 0; channel < 3; channel++) {
              motionDifference += (actual[index + channel] - previous[index + channel]).abs();
            }
          }
          previous = actual;
          elapsed += 4;
        }
      }
      expect(motionDifference, greaterThan(5));
      final revealed = await render(12, 1, elapsed, header: false);
      expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
      expect(revealed, isNot(previous));
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    });

    testWidgets('frozen contours retain native DPR3 coverage $quality', (tester) async {
      tester.view.physicalSize = const Size(960, 720);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      Future<List<int>> render(double top, {bool cover = false, bool reference = false}) async {
        await tester.pumpWidget(MaterialApp(
          home: RepaintBoundary(
            key: _scene,
            child: LiquidGlassScope(
              enabled: true,
              quality: quality,
              child: LiquidGlassHost(
                enabled: true,
                wallpaperOnly: true,
                child: Stack(fit: StackFit.expand, children: [
                  const LiquidGlassColorField(child: LiquidGlassColorSource(
                    child: ColoredBox(color: Colors.black),
                  )),
                  Positioned(left: 16, top: top, width: 288, height: 120,
                    child: reference
                        ? const ClipRSuperellipse(
                            borderRadius: AppRadius.xl,
                            child: ColoredBox(color: Color(0xFF808080)),
                          )
                        : const LiquidGlass(
                            style: LiquidGlassStyle(
                              tint: Color(0x80FFFFFF),
                              blur: 0,
                              refraction: 0,
                              bevel: 0,
                              rim: 0,
                            ),
                            clipper: ShapeBorderClipper(shape: AppShape.xl),
                          ),
                  ),
                  if (cover) const Positioned(left: 0, top: 0, width: 320, height: 110,
                    child: LiquidGlassOcclusion(
                      shape: AppShape.none,
                      bottomFeather: 12,
                      child: SizedBox.expand(),
                    ),
                  ),
                ]),
              ),
            ),
          ),
        ));
        await tester.pump();
        await tester.pump();
        return (await tester.runAsync(() async {
          final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
          final image = await boundary.toImage(pixelRatio: 3);
          try {
            expect(image.width, 960);
            expect(image.height, 720);
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              Directory(output).createSync(recursive: true);
              final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
              File('$output/header-dpr3-$quality-$top-$cover-$reference.png')
                  .writeAsBytesSync(png.buffer.asUint8List());
            }
            return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
                .buffer.asUint8List().toList();
          } finally {
            image.dispose();
          }
        }))!;
      }
      for (final top in [86.0, 74.0, 62.0]) {
        await render(top);
        final frozen = await render(top, cover: true);
        final reference = await render(top, reference: true);
        var maximum = 0;
        var outsideLeak = 0;
        var interiorMissing = 0;
        Map<String, int>? worst;
        for (var y = (top * 3).floor(); y < 330; y++) {
          for (var x = 0; x < 960; x++) {
            final index = (y * 960 + x) * 4;
            if (reference[index] == 0 && frozen[index] > 2) outsideLeak++;
            if (reference[index] >= 126 && frozen[index] < 124) interiorMissing++;
            for (var channel = 0; channel < 4; channel++) {
              final delta = (frozen[index + channel] - reference[index + channel]).abs();
              if (delta > maximum) {
                maximum = delta;
                worst = {'x': x, 'y': y, 'channel': channel,
                  'actual': frozen[index + channel], 'expected': reference[index + channel]};
              }
            }
          }
        }
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          File('$output/header-frozen-contour-dpr3-$quality-$top.json').writeAsStringSync(jsonEncode({
            'maximum': maximum, 'worst': worst, 'outsideLeak': outsideLeak, 'interiorMissing': interiorMissing,
          }));
        }
        expect(maximum, lessThanOrEqualTo(2), reason: 'native frozen contour $top $worst');
        expect(outsideLeak, 0);
        expect(interiorMissing, 0);
      }
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    });

    testWidgets(
      'transformed feather matches premultiplied frozen live oracle $quality',
      (tester) async {
        tester.view.physicalSize = const Size(320, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
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
        final cost = LiquidGlassHost.debugLastFeatherCapture!;
        expect(cost.panes, 1);
        expect(cost.stripPixels, 120 * 15);
        expect(cost.surfacePixels, 120 * 15 * 3);
        await render(Colors.green, 4);
        final crossed = await _shot(tester, 'oracle-crossed-$quality');
        expect(
          LiquidGlassHost.debugLastFeatherCapture!.surfacePixels,
          120 * 15 * 5,
        );
        await render(Colors.green, 0);
        final live = await _shot(tester, 'oracle-live-$quality');
        await render(Colors.green, 0, isolated: true);
        await _shot(tester, 'oracle-isolated-live-$quality');
        final resolved = List<int>.of(live);
        final nativeStrip = List<int>.of(live);
        final nativeBelow = List<int>.of(live);
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
          // Native AA is resolved within each clipped target, not cropped afterward.
          for (final crop in [
            (
              rect: const Rect.fromLTWH(0, 50, 120, 15),
              pixels: nativeStrip,
              name: 'strip',
            ),
            (
              rect: const Rect.fromLTWH(0, 65, 120, 55),
              pixels: nativeBelow,
              name: 'below',
            ),
          ]) {
            final image = await (control.debugLayer! as OffsetLayer).toImage(
              crop.rect,
            );
            try {
              expect(image.width, crop.rect.width);
              expect(image.height, crop.rect.height);
              final rgba = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!.buffer.asUint8List();
              final background = _pixel(live, 5, 116);
              for (var y = 0; y < image.height; y++) {
                for (var x = 0; x < 120; x++) {
                  final from = (y * 120 + x) * 4;
                  final to =
                      ((y + 60 + crop.rect.top.toInt()) * 320 + x + 40) * 4;
                  final alpha = rgba[from + 3] / 255;
                  final dx = x + 0.5 - 60;
                  final dy = y + crop.rect.top + 0.5 - 60;
                  final interior = dx * dx + dy * dy < 58 * 58;
                  if (interior) expect(rgba[from + 3], 255);
                  if (dx * dx + dy * dy > 61 * 61) expect(rgba[from + 3], 0);
                  for (var channel = 0; channel < 3; channel++) {
                    crop.pixels[to + channel] =
                        (rgba[from + channel] +
                                background[channel] * (1 - alpha))
                            .round()
                            .clamp(0, 255);
                    if (interior) {
                      expect(
                        crop.pixels[to + channel],
                        closeTo(live[to + channel], 2),
                        reason: 'native target preserves opaque interior $x,$y',
                      );
                    }
                  }
                }
              }
              if (output != null) {
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '$output/header-native-${crop.name}-$quality.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
            } finally {
              image.dispose();
            }
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
              'belowEdge': {
                'position': [43, 140],
                'direct': _pixel(live, 43, 140),
                'resolved': _pixel(resolved, 43, 140),
                'nativeBelow': _pixel(nativeBelow, 43, 140),
                'mixed': _pixel(mixed, 43, 140),
              },
              'otherEdge': {
                'position': [40, 121],
                'direct': _pixel(live, 40, 121),
                'resolved': _pixel(resolved, 40, 121),
                'nativeStrip': _pixel(nativeStrip, 40, 121),
                'frozen': _pixel(frozen, 40, 121),
                'mixed': _pixel(mixed, 40, 121),
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
        for (final row in [100, ...List.generate(55, (index) => 125 + index)]) {
          final expected = row < 110 ? frozen : nativeBelow;
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
      'header keeps the full fade plain and feathers only below it $quality',
      (tester) async {
        tester.view.physicalSize = const Size(320, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        Future<void> render({
          Color color = Colors.blue,
          bool text = true,
          double shift = 0,
          bool nativePlain = false,
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
                              nativePlain
                                  ? Positioned.fromRect(rect: _top, child: const ClipRSuperellipse(
                                      borderRadius: AppRadius.full,
                                      child: ColoredBox(color: Color(0x33FF0000))))
                                  : _pane(_top),
                              nativePlain
                                  ? Positioned.fromRect(rect: _crossing, child: const ClipRSuperellipse(
                                      borderRadius: AppRadius.full,
                                      child: ColoredBox(color: Color(0x33FF0000))))
                                  : _pane(_crossing),
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
        final capture = LiquidGlassHost.debugLastCapture!;
        final feather = LiquidGlassHost.debugLastFeatherCapture!;
        await render(color: Colors.green, nativePlain: true);
        final plainReference = await _shot(tester, 'native-plain-$quality');
        for (var channel = 0; channel < 3; channel++) {
          expect(
            _pixel(moving, 280, 8)[channel],
            closeTo(_pixel(plainReference, 280, 8)[channel], 2),
          );
        }
        expect(_pixel(moving, 280, 8), isNot(_pixel(initial, 280, 8)),
          reason: 'header tint must reveal the live wallpaper instead of old RGB');
        expect(capture.panes, hasLength(2));
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
        expect(feather.panes, 1);
        expect(feather.stripPixels, 320 * 12);
        expect(
          feather.surfacePixels,
          320 * 12 * 3,
        );
        await render(color: Colors.green);
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
