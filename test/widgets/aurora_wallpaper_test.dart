import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  test(
    'normalizes one to three opaque colors without inventing random hues',
    () {
      expect(normalizeAuroraColors([]), isNotEmpty);
      expect(normalizeAuroraColors([const Color(0x00ABCDEF)]), [
        const Color(0xFFABCDEF),
      ]);
      expect(normalizeAuroraColors(List.filled(8, Colors.red)), hasLength(3));
    },
  );

  testWidgets('rebuilding a wallpaper keeps identical pixels', (tester) async {
    final key = GlobalKey();
    Future<List<int>> render(int seed, {double scale = 1}) async {
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Center(
            child: RepaintBoundary(
              key: key,
              child: SizedBox(
                width: 160 * scale,
                height: 240 * scale,
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
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      return (await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final bytes = await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          );
          return bytes!.buffer.asUint8List().toList();
        } finally {
          image.dispose();
        }
      }))!;
    }

    final first = await render(12);
    CustomPainter? painter() => tester
        .widget<CustomPaint>(
          find.descendant(
            of: find.byType(SeededAuroraWallpaper),
            matching: find.byType(CustomPaint),
          ),
        )
        .painter;
    final originalPainter = painter();
    expect(await render(12), first);
    expect(painter(), same(originalPainter));
    await tester.pump(const Duration(seconds: 30));
    expect(tester.binding.hasScheduledFrame, false);
    final resized = await render(12, scale: 2);
    expect(painter(), same(originalPainter));
    for (var y = 12; y < 228; y += 24) {
      for (var x = 8; x < 152; x += 16) {
        final originalOffset = (y * 160 + x) * 4;
        final resizedOffset = (y * 2 * 320 + x * 2) * 4;
        for (var channel = 0; channel < 3; channel++) {
          expect(
            (first[originalOffset + channel] -
                    resized[resizedOffset + channel])
                .abs(),
            lessThanOrEqualTo(2),
          );
        }
      }
    }
    await tester.pumpWidget(const SizedBox.shrink());
    expect(await render(12), first);
    expect(await render(13), isNot(first));
    for (var i = 0; i < first.length; i += 4) {
      expect(first[i + 3], 255);
      expect(
        Color.fromARGB(
          255,
          first[i],
          first[i + 1],
          first[i + 2],
        ).computeLuminance(),
        greaterThan(0.73),
      );
    }
  });

  testWidgets(
    'renders visible selected colors in light and dark for 1–3 colors',
    (tester) async {
      const selected = [
        Color(0xFF6BD5D9),
        Color(0xFFC6ADE4),
        Color(0xFFA9DAC6),
      ];
      final key = GlobalKey();
      final metrics = <String, Object?>{};
      final output = Directory(
        Platform.environment['XCLASH_SCREENSHOT_DIR'] ?? 'build/aurora-review',
      );
      for (final brightness in Brightness.values) {
        for (var count = 1; count <= 3; count++) {
          final palette = selected.take(count).toList();
          List<int>? previous;
          for (final seed in [12, 271828, 314159]) {
            await tester.pumpWidget(
              Directionality(
                textDirection: TextDirection.ltr,
                child: Center(
                  child: RepaintBoundary(
                    key: key,
                    child: SizedBox(
                      width: 300,
                      height: 600,
                      child: SeededAuroraWallpaper(
                        colors: palette,
                        seed: seed,
                        brightness: brightness,
                      ),
                    ),
                  ),
                ),
              ),
            );
            final boundary =
                key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
            final name = 'aurora-${brightness.name}-$count-$seed';
            final bytes = (await tester.runAsync(() async {
              final image = await boundary.toImage();
              try {
                final pixels = await image.toByteData(
                  format: ui.ImageByteFormat.rawRgba,
                );
                final png = await image.toByteData(
                  format: ui.ImageByteFormat.png,
                );
                await output.create(recursive: true);
                await File('${output.path}/$name.png').writeAsBytes(
                  png!.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
                );
                return pixels!.buffer
                    .asUint8List(pixels.offsetInBytes, pixels.lengthInBytes)
                    .toList();
              } finally {
                image.dispose();
              }
            }))!;
            if (previous != null) {
              expect(
                _meanPixelDifference(previous, bytes),
                greaterThan(3),
                reason: name,
              );
            }
            previous = bytes;
            final coverage = List<int>.filled(count, 0);
            var samples = 0;
            var totalLuminance = 0.0;
            var minLuminance = 1.0;
            var maxLuminance = 0.0;
            for (var offset = 0; offset < bytes.length; offset += 64) {
              expect(bytes[offset + 3], 255);
              final color = Color.fromARGB(
                255,
                bytes[offset],
                bytes[offset + 1],
                bytes[offset + 2],
              );
              final luminance = color.computeLuminance();
              minLuminance = math.min(minLuminance, luminance);
              maxLuminance = math.max(maxLuminance, luminance);
              totalLuminance += luminance;
              samples++;
              final pixel = HSVColor.fromColor(color);
              if (pixel.saturation < 0.07) continue;
              for (var index = 0; index < palette.length; index++) {
                final delta = (pixel.hue - HSVColor.fromColor(palette[index]).hue)
                    .abs();
                if (math.min(delta, 360 - delta) < 20) coverage[index]++;
              }
            }
            final meanLuminance = totalLuminance / samples;
            final verticalVariation = _verticalVariation(bytes, 300, 600);
            final varyingRows = _varyingRows(bytes, 300, 600);
            expect(verticalVariation, greaterThan(3), reason: name);
            expect(varyingRows, greaterThan(0.75), reason: name);
            metrics[name] = {
              'verticalVariation': verticalVariation,
              'varyingRowFraction': varyingRows,
              'seed': seed,
              'syntheticPalette': palette
                  .map((color) => color.toARGB32())
                  .toList(),
              'meanLuminance': meanLuminance,
              'minLuminance': minLuminance,
              'maxLuminance': maxLuminance,
              'selectedHueCoverage': [
                for (final visible in coverage) visible / samples,
              ],
              'minimumWhiteContrast': 1.05 / (maxLuminance + 0.05),
              'minimumBlackContrast': (minLuminance + 0.05) / 0.05,
            };
            debugPrint('$name rendered pixels: ${jsonEncode(metrics[name])}');
            await tester.runAsync(
              () =>
                  File('${output.path}/aurora-pixel-metrics.json').writeAsString(
                    const JsonEncoder.withIndent('  ').convert(metrics),
                  ),
            );
            for (final visible in coverage) {
              expect(visible / samples, greaterThan(0.01), reason: name);
            }
            if (brightness == Brightness.dark) {
              expect(meanLuminance, greaterThan(0.035), reason: name);
              expect(maxLuminance, lessThan(0.165), reason: name);
            } else {
              expect(minLuminance, greaterThan(0.73), reason: name);
            }
          }
        }
      }
    },
  );

  testWidgets('glass has local blur only when the appearance is enabled', (
    tester,
  ) async {
    Future<void> pump(bool enabled) => tester.pumpWidget(
      MaterialApp(
        home: AuroraAppearance(
          enabled: enabled,
          child: const Center(
            child: AuroraBackdrop(child: SizedBox(width: 100, height: 50)),
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

double _meanPixelDifference(List<int> first, List<int> next) {
  var difference = 0;
  var samples = 0;
  for (var offset = 0; offset < first.length; offset += 64) {
    for (var channel = 0; channel < 3; channel++) {
      difference += (first[offset + channel] - next[offset + channel]).abs();
      samples++;
    }
  }
  return difference / samples;
}

double _verticalVariation(List<int> pixels, int width, int height) {
  var difference = 0;
  var samples = 0;
  for (var x = 0; x < width; x += 10) {
    final upper = ((height * 0.2).round() * width + x) * 4;
    final lower = ((height * 0.8).round() * width + x) * 4;
    for (var channel = 0; channel < 3; channel++) {
      difference += (pixels[upper + channel] - pixels[lower + channel]).abs();
      samples++;
    }
  }
  return difference / samples;
}

double _varyingRows(List<int> pixels, int width, int height) {
  var varying = 0;
  var rows = 0;
  for (var y = 0; y < height; y += 10) {
    var range = 0;
    for (var channel = 0; channel < 3; channel++) {
      var low = 255;
      var high = 0;
      for (var x = 0; x < width; x += 10) {
        final value = pixels[(y * width + x) * 4 + channel];
        low = math.min(low, value);
        high = math.max(high, value);
      }
      range += high - low;
    }
    if (range / 3 > 3) varying++;
    rows++;
  }
  return varying / rows;
}
