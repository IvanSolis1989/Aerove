import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _capture = ValueKey('ink-quality');
const _palettes = [
  Color(0xFFC7D6E6),
  Color(0xFFE6CCD6),
  Color(0xFFD1E3C7),
  Color(0xFFD9CFE8),
];

Widget _scene(
  Brightness brightness,
  LiquidGlassQuality quality, {
  bool enabled = true,
  bool highContrast = false,
}) {
  final source = brightness == Brightness.dark ? Colors.white : Colors.black;
  final active = enabled && !highContrast;
  return MaterialApp(
    theme: ThemeData(brightness: brightness),
    themeAnimationStyle: AnimationStyle.noAnimation,
    home: MediaQuery(
      data: MediaQueryData(highContrast: highContrast),
      child: RepaintBoundary(
        key: _capture,
        child: LiquidGlassScope(
          enabled: active,
          quality: quality,
          child: LiquidGlassHost(
            enabled: active,
            wallpaperOnly: true,
            child: Stack(
              fit: StackFit.expand,
              children: [
                LiquidGlassColorField(
                  child: LiquidGlassColorSource(
                    child: CustomPaint(painter: _Palette(brightness)),
                  ),
                ),
                for (var row = 0; row < 2; row++)
                  for (var column = 0; column < 4; column++)
                    Positioned(
                      left: column * 80.0 + 30,
                      top: row * 80.0 + 30,
                      width: 20,
                      height: 20,
                      child: GlassInk(
                        source: source,
                        role: row == 0 ? GlassInkRole.body : GlassInkRole.icon,
                        child: ColoredBox(color: source),
                      ),
                    ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class _Palette extends CustomPainter {
  const _Palette(this.brightness);
  final Brightness brightness;

  @override
  void paint(Canvas canvas, Size size) {
    for (final (index, color) in _palettes.indexed) {
      canvas.drawRect(
        Rect.fromLTWH(index * 80.0, 0, 80, size.height),
        Paint()
          ..color = brightness == Brightness.dark
              ? Color.fromRGBO(
                  (color.r * 255 * 0.18).round(),
                  (color.g * 255 * 0.18).round(),
                  (color.b * 255 * 0.18).round(),
                  1,
                )
              : color,
      );
    }
  }

  @override
  bool shouldRepaint(_Palette oldDelegate) =>
      brightness != oldDelegate.brightness;
}

Future<Uint8List> _shot(WidgetTester tester) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_capture),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

Color _pixel(Uint8List pixels, int x, int y) {
  final index = (y * 320 + x) * 4;
  return Color.fromARGB(
    pixels[index + 3],
    pixels[index],
    pixels[index + 1],
    pixels[index + 2],
  );
}

double _contrast(Color a, Color b) {
  final x = a.computeLuminance(), y = b.computeLuminance();
  return (math.max(x, y) + 0.05) / (math.min(x, y) + 0.05);
}

Color _previousUltra(Color background, bool dark, bool icon) {
  final rgb = [background.r, background.g, background.b];
  final high = rgb.reduce(math.max), low = rgb.reduce(math.min);
  final span = high - low;
  final target = icon
      ? 3.2
      : dark
      ? 4.7
      : 4.8;
  List<double> readable(List<double> ink) {
    Color color(List<double> values) =>
        Color.from(alpha: 1, red: values[0], green: values[1], blue: values[2]);
    if (_contrast(color(ink), background) >= target) return ink;
    final light = background.computeLuminance();
    final pole = (light + 0.05) / 0.05 > 1.05 / (light + 0.05) ? 0.0 : 1.0;
    var from = 0.0, to = 1.0;
    for (var iteration = 0; iteration < 8; iteration++) {
      final amount = (from + to) / 2;
      final test = [for (final value in ink) value + (pole - value) * amount];
      if (_contrast(color(test), background) >= target) {
        to = amount;
      } else {
        from = amount;
      }
    }
    return [for (final value in ink) value + (pole - value) * to];
  }

  var ink = rgb;
  if (span >= 0.015) {
    final saturation = math.min(
      dark ? 0.92 : 0.98,
      span / high * (dark ? 2.70 : 3.3) + (dark ? 0.175 : 0.24),
    );
    final peak = dark ? 0.95 : 0.985;
    ink = [
      for (final value in rgb)
        peak *
            (1 -
                saturation +
                saturation *
                    (dark
                        ? (value - low) / span
                        : math.pow((value - low) / span, 1.20).toDouble())),
    ];
  }
  var strength = icon ? 1.0 : 0.92;
  if (dark) {
    ink = readable(ink);
  } else {
    strength += (1 - strength) * 0.14;
  }
  ink = readable([
    for (final value in ink) (dark ? 1 : 0) * (1 - strength) + value * strength,
  ]);
  return Color.from(alpha: 1, red: ink[0], green: ink[1], blue: ink[2]);
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    await GlassInk.prepare();
  });

  for (final brightness in Brightness.values) {
    testWidgets('quality changes measured ink color ${brightness.name}', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 160);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      Future<Uint8List> render(LiquidGlassQuality quality) async {
        await tester.pumpWidget(_scene(brightness, quality));
        await tester.pumpAndSettle();
        return _shot(tester);
      }

      final standard = await render(LiquidGlassQuality.standard);
      final first = LiquidGlassHost.debugLastInkCapture!;
      final ultra = await render(LiquidGlassQuality.ultra);
      final switched = LiquidGlassHost.debugLastInkCapture!;
      expect(switched.masks, 8);
      expect(switched.filterUpdates - first.filterUpdates, 8);
      expect(switched.sourceCaptures, first.sourceCaptures);
      expect(switched.fallbackCaptures, first.fallbackCaptures);
      final report = <Map<String, Object?>>[];
      for (var row = 0; row < 2; row++) {
        var standardSaturation = 0.0, ultraSaturation = 0.0;
        var standardValue = 0.0, ultraValue = 0.0;
        var previousSaturation = 0.0, previousValue = 0.0;
        for (var column = 0; column < 4; column++) {
          final background = _pixel(ultra, column * 80 + 10, row * 80 + 40);
          final a = _pixel(standard, column * 80 + 40, row * 80 + 40);
          final b = _pixel(ultra, column * 80 + 40, row * 80 + 40);
          final previous = _previousUltra(
            background,
            brightness == Brightness.dark,
            row == 1,
          );
          final ah = HSVColor.fromColor(a), bh = HSVColor.fromColor(b);
          final ph = HSVColor.fromColor(previous);
          standardSaturation += ah.saturation;
          ultraSaturation += bh.saturation;
          standardValue += ah.value;
          ultraValue += bh.value;
          previousSaturation += ph.saturation;
          previousValue += ph.value;
          final minimum = row == 0 ? 4.5 : 3.0;
          expect(_contrast(a, background), greaterThanOrEqualTo(minimum));
          expect(_contrast(b, background), greaterThanOrEqualTo(minimum));
          expect(a.a, 1);
          expect(b.a, 1);
          if (brightness == Brightness.dark) {
            for (final delta in [
              (b.r - previous.r).abs(),
              (b.g - previous.g).abs(),
              (b.b - previous.b).abs(),
            ]) {
              expect(delta * 255, lessThanOrEqualTo(2));
            }
          }
          report.add({
            'role': row == 0 ? 'body' : 'icon',
            'background': background.toARGB32(),
            'standard': a.toARGB32(),
            'ultra': b.toARGB32(),
            'previousUltra': previous.toARGB32(),
            'standardSaturation': ah.saturation,
            'ultraSaturation': bh.saturation,
            'previousUltraSaturation': ph.saturation,
            'standardValue': ah.value,
            'ultraValue': bh.value,
            'previousUltraValue': ph.value,
            'standardContrast': _contrast(a, background),
            'ultraContrast': _contrast(b, background),
          });
        }
        expect(ultraSaturation - standardSaturation, greaterThan(0.08));
        expect(ultraValue - standardValue, greaterThan(0.02));
        if (brightness == Brightness.light) {
          expect(ultraSaturation - previousSaturation, greaterThan(0.04));
          expect(ultraValue - previousValue, greaterThan(0.02));
        }
      }
      expect(await render(LiquidGlassQuality.standard), standard);
      final back = LiquidGlassHost.debugLastInkCapture!;
      expect(back.filterUpdates - switched.filterUpdates, 8);
      expect(back.sourceCaptures, first.sourceCaptures);
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        File('$output/ink-quality-${brightness.name}.json').writeAsStringSync(
          jsonEncode({
            'samples': report,
            'sourceCaptureDelta':
                switched.sourceCaptures - first.sourceCaptures,
            'filterUpdateDelta': switched.filterUpdates - first.filterUpdates,
            'returnFilterUpdateDelta':
                back.filterUpdates - switched.filterUpdates,
          }),
        );
      }
      await tester.pumpWidget(
        _scene(brightness, LiquidGlassQuality.standard, enabled: false),
      );
      await tester.pumpAndSettle();
      final none = await _shot(tester);
      await tester.pumpWidget(
        _scene(brightness, LiquidGlassQuality.ultra, highContrast: true),
      );
      await tester.pumpAndSettle();
      expect(await _shot(tester), none);
      expect(tester.layers.whereType<LiquidGlassInkLayer>(), isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(tester.takeException(), isNull);
    }, skip: !ui.ImageFilter.isShaderFilterSupported);
  }
}
