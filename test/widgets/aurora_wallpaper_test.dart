import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart'
    show Hct;
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
            (first[originalOffset + channel] - resized[resizedOffset + channel])
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
      const saturated = [
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF0000FF),
      ];
      const palettes = {'soft': selected, 'saturated': saturated};
      const regressionSeeds = [12, 271828, 314159];
      const masterSeed = 20261017;
      final random = math.Random(masterSeed);
      final randomSeeds = <int>[];
      while (randomSeeds.length < 10) {
        final seed = random.nextInt(1 << 32);
        if (!regressionSeeds.contains(seed) && !randomSeeds.contains(seed)) {
          randomSeeds.add(seed);
        }
      }
      final seeds = [...regressionSeeds, ...randomSeeds];
      final key = GlobalKey();
      final metrics = <String, Object?>{};
      final failures = <String>[];
      final output = Directory(
        Platform.environment['XCLASH_SCREENSHOT_DIR'] ?? 'build/aurora-review',
      );
      final manifest = {
        'schemaVersion': 1,
        'masterSeed': masterSeed,
        'generator': 'dart:math Random(masterSeed).nextInt(1 << 32)',
        'dartVersion': Platform.version,
        'regressionSeeds': regressionSeeds,
        'randomSeeds': randomSeeds,
        'expectedImageCount': seeds.length * 6 + randomSeeds.length * 2,
        'imageWidth': 300,
        'imageHeight': 600,
        'hardGates': {
          'opaqueAlpha': 255,
          'minimumSelectedHueCoverageExclusive': 0.01,
          'hueToleranceDegreesExclusive': 20,
          'minimumHueSampleSaturation': 0.07,
          'minimumConsecutiveSeedMeanRgbDifferenceExclusive': 3,
          'minimumVerticalMeanRgbDifferenceExclusive': 3,
          'minimumVaryingRowFractionExclusive': 0.75,
          'darkMinimumMeanLuminanceExclusive': 0.035,
          'darkMinimumSampledLuminanceInclusive': 0.07,
          'darkMaximumNearBlackFractionInclusive': 0,
          'darkMaximumNeutralFractionExclusive': 0.01,
          'neutralSaturationThreshold': 0.07,
          'darkMaximumLuminanceExclusive': 0.165,
          'lightMinimumLuminanceExclusive': 0.73,
          'darkMaximumSampledHctChromaInclusive': 24,
          'lightMaximumSampledHctChromaInclusive': 20,
        },
        'visualReview': {
          'status': 'requires independent review of actual rendered PNGs',
          'softMaximumP95AdjacentRgbStep': 2,
          'softMaximumLuminanceStandardDeviation': 0.10,
          'criteria': [
            'broad soft color transitions without visible mesh edges',
            'visible chosen colors throughout, with no injected black or gray corners',
            'no rigid stripes, harsh hotspots, or distracting detail',
            'soft metric flags require inspection, not automatic approval',
          ],
          'randomSeedReviewGroups': [
            for (final seed in randomSeeds)
              {
                'seed': seed,
                'files': [
                  for (final brightness in Brightness.values)
                    for (var count = 1; count <= 3; count++)
                      'aurora-${brightness.name}-$count-$seed.png',
                  for (final brightness in Brightness.values)
                    'aurora-saturated-${brightness.name}-3-$seed.png',
                ],
              },
          ],
        },
        'images': [
          for (final brightness in Brightness.values)
            for (var count = 1; count <= 3; count++)
              for (final seed in seeds)
                {
                  'file': 'aurora-${brightness.name}-$count-$seed.png',
                  'seed': seed,
                  'sample': randomSeeds.contains(seed)
                      ? 'random'
                      : 'regression',
                  'brightness': brightness.name,
                  'colorCount': count,
                  'selectedColors': selected
                      .take(count)
                      .map((color) => color.toARGB32())
                      .toList(),
                },
          for (final brightness in Brightness.values)
            for (final seed in randomSeeds)
              {
                'file': 'aurora-saturated-${brightness.name}-3-$seed.png',
                'seed': seed,
                'sample': 'random',
                'palette': 'saturated',
                'brightness': brightness.name,
                'colorCount': 3,
                'selectedColors': saturated
                    .map((color) => color.toARGB32())
                    .toList(),
              },
        ],
      };
      debugPrint('aurora random seeds: ${jsonEncode(randomSeeds)}');
      await tester.runAsync(() async {
        await output.create(recursive: true);
        await File(
          '${output.path}/aurora-review-manifest.json',
        ).writeAsString(const JsonEncoder.withIndent('  ').convert(manifest));
      });
      for (final paletteCase in palettes.entries) {
        for (final brightness in Brightness.values) {
          for (final count in paletteCase.key == 'soft' ? [1, 2, 3] : [3]) {
            final palette = paletteCase.value.take(count).toList();
            List<int>? previous;
            for (final seed
                in paletteCase.key == 'soft' ? seeds : randomSeeds) {
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
                  key.currentContext!.findRenderObject()!
                      as RenderRepaintBoundary;
              final prefix = paletteCase.key == 'soft'
                  ? 'aurora'
                  : 'aurora-saturated';
              final name = '$prefix-${brightness.name}-$count-$seed';
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
                    png!.buffer.asUint8List(
                      png.offsetInBytes,
                      png.lengthInBytes,
                    ),
                  );
                  return pixels!.buffer
                      .asUint8List(pixels.offsetInBytes, pixels.lengthInBytes)
                      .toList();
                } finally {
                  image.dispose();
                }
              }))!;
              final seedDifference = previous == null
                  ? null
                  : _meanPixelDifference(previous, bytes);
              previous = bytes;
              final coverage = List<int>.filled(count, 0);
              var samples = 0;
              var opaque = true;
              var totalLuminance = 0.0;
              var squaredLuminance = 0.0;
              var minLuminance = 1.0;
              var maxLuminance = 0.0;
              var maxChroma = 0.0;
              var nearBlackSamples = 0;
              var neutralSamples = 0;
              for (var offset = 0; offset < bytes.length; offset += 64) {
                opaque = opaque && bytes[offset + 3] == 255;
                final color = Color.fromARGB(
                  255,
                  bytes[offset],
                  bytes[offset + 1],
                  bytes[offset + 2],
                );
                final luminance = color.computeLuminance();
                if (luminance < 0.07) nearBlackSamples++;
                if (offset % 1024 == 0) {
                  maxChroma = math.max(
                    maxChroma,
                    Hct.fromInt(color.toARGB32()).chroma,
                  );
                }
                minLuminance = math.min(minLuminance, luminance);
                maxLuminance = math.max(maxLuminance, luminance);
                totalLuminance += luminance;
                squaredLuminance += luminance * luminance;
                samples++;
                final pixel = HSVColor.fromColor(color);
                if (pixel.saturation < 0.07) neutralSamples++;
                if (pixel.saturation < 0.07) continue;
                for (var index = 0; index < palette.length; index++) {
                  final delta =
                      (pixel.hue - HSVColor.fromColor(palette[index]).hue)
                          .abs();
                  if (math.min(delta, 360 - delta) < 20) coverage[index]++;
                }
              }
              final meanLuminance = totalLuminance / samples;
              final verticalVariation = _verticalVariation(bytes, 300, 600);
              final varyingRows = _varyingRows(bytes, 300, 600);
              final luminanceDeviation = math.sqrt(
                math.max(
                  0,
                  squaredLuminance / samples - meanLuminance * meanLuminance,
                ),
              );
              final adjacentStep = _p95AdjacentRgbStep(bytes, 300, 600);
              final failedChecks = <String>[
                if (!opaque) 'opaque alpha must equal 255',
                if (maxChroma > (brightness == Brightness.dark ? 24 : 20))
                  'sampled HCT chroma must remain inside the brightness cap',
                if (seedDifference != null && seedDifference <= 3)
                  'consecutive seed mean RGB difference must exceed 3',
                if (verticalVariation <= 3)
                  'vertical mean RGB difference must exceed 3',
                if (varyingRows <= 0.75)
                  'varying row fraction must exceed 0.75',
                for (var index = 0; index < coverage.length; index++)
                  if (coverage[index] / samples <= 0.01)
                    'selected color $index hue coverage must exceed 0.01',
                if (brightness == Brightness.dark) ...[
                  if (nearBlackSamples != 0)
                    'dark sampled luminance must stay at or above 0.07',
                  if (neutralSamples / samples >= 0.01)
                    'dark neutral area must stay below one percent',
                  if (meanLuminance <= 0.035)
                    'dark mean luminance must exceed 0.035',
                  if (maxLuminance >= 0.165)
                    'dark maximum luminance must be below 0.165',
                ] else if (minLuminance <= 0.73)
                  'light minimum luminance must exceed 0.73',
              ];
              failures.addAll(failedChecks.map((failure) => '$name: $failure'));
              metrics[name] = {
                'file': '$name.png',
                'palette': paletteCase.key,
                'maximumSampledHctChroma': maxChroma,
                'nearBlackFraction': nearBlackSamples / samples,
                'neutralFraction': neutralSamples / samples,
                'sample': randomSeeds.contains(seed) ? 'random' : 'regression',
                'passedHardGates': failedChecks.isEmpty,
                'failedChecks': failedChecks,
                'consecutiveSeedMeanRgbDifference': seedDifference,
                'verticalVariation': verticalVariation,
                'varyingRowFraction': varyingRows,
                'p95AdjacentRgbStep': adjacentStep,
                'luminanceStandardDeviation': luminanceDeviation,
                'softReviewFlags': [
                  if (adjacentStep > 2) 'adjacent RGB step exceeds 2',
                  if (luminanceDeviation > 0.10)
                    'luminance standard deviation exceeds 0.10',
                ],
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
                () => File('${output.path}/aurora-pixel-metrics.json')
                    .writeAsString(
                      const JsonEncoder.withIndent('  ').convert(metrics),
                    ),
              );
            }
          }
        }
      }
      manifest['renderedImageCount'] = metrics.length;
      manifest['passedHardGates'] = failures.isEmpty;
      manifest['failedChecks'] = failures;
      await tester.runAsync(
        () => File(
          '${output.path}/aurora-review-manifest.json',
        ).writeAsString(const JsonEncoder.withIndent('  ').convert(manifest)),
      );
      expect(metrics, hasLength(98));
      expect(failures, isEmpty, reason: failures.join('\n'));
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

double _p95AdjacentRgbStep(List<int> pixels, int width, int height) {
  final steps = <double>[];
  for (var y = 0; y < height - 1; y += 10) {
    for (var x = 0; x < width - 1; x += 10) {
      final offset = (y * width + x) * 4;
      for (final neighbor in [offset + 4, offset + width * 4]) {
        var difference = 0;
        for (var channel = 0; channel < 3; channel++) {
          difference += (pixels[offset + channel] - pixels[neighbor + channel])
              .abs();
        }
        steps.add(difference / 3);
      }
    }
  }
  steps.sort();
  return steps[((steps.length - 1) * 0.95).round()];
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
