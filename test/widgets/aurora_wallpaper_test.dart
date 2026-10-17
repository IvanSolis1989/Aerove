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
        List<int>? previous;
        for (var count = 1; count <= 3; count++) {
          final palette = selected.take(count).toList();
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
                      seed: 271828,
                      brightness: brightness,
                    ),
                  ),
                ),
              ),
            ),
          );
          final boundary =
              key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          final name = 'aurora-${brightness.name}-$count';
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
          if (previous != null) expect(bytes, isNot(previous));
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
          metrics[name] = {
            'seed': 271828,
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
