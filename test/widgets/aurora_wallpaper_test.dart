import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart'
    show Hct;
import 'package:material_ui/material_ui.dart';

const _baselineRun = '39042612930';
const _baselineDarkPixelDigest =
    '0d7cabb636d7980464c387da3bc9a3d6a3a067a930f8a3460ef80a29b9eb35fb';
const _baselineLightMeanSaturation = 0.0895237717889811;

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

  testWidgets('renders visible selected colors in light and dark for 1–3 colors', (
    tester,
  ) async {
    const selected = [Color(0xFF6BD5D9), Color(0xFFC6ADE4), Color(0xFFA9DAC6)];
    const saturated = [Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF)];
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
    final darkPixelHashes = <String>[];
    final lightMeanSaturations = <double>[];
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
      'meshColumns': 65,
      'meshRows': 97,
      'sharedSourceSaturationCeiling': 0.60,
      'sourceSofteningBrightnessDependent': false,
      'lightFinalSaturationMultiplier': 1.10,
      'pixelBaseline': {
        'githubActionsRun': _baselineRun,
        'source': 'xclash-previews artifact, 300 by 600 opaque RGBA images',
        'darkDigestEncoding':
            'SHA256 of sorted image-stem:raw-RGBA-SHA256 entries joined by newline',
        'darkImageCount': 49,
        'darkPixelDigest': _baselineDarkPixelDigest,
        'lightImageCount': 49,
        'lightMeanSampledSaturation': _baselineLightMeanSaturation,
      },
      'hardGates': {
        'opaqueAlpha': 255,
        'minimumSelectedHueCoverageExclusive': 0.01,
        'hueToleranceDegreesExclusive': 20,
        'minimumHueSampleSaturation': 0.07,
        'minimumConsecutiveSeedMeanRgbDifferenceExclusive': 3,
        'minimumVerticalMeanRgbDifferenceExclusive': 3,
        'minimumVaryingRowFractionExclusive': 0.75,
        'darkMaximumSampledValueExclusive': 0.51,
        'darkMaximumSourceValueMultiplier': 0.50,
        'darkMinimumSourceValueMultiplier': 0.30,
        'channelQuantizationTolerance': 1 / 255,
        'nearBlackValueThreshold': 0.20,
        'nearBlackMinimumSourceValue': 0.70,
        'darkMaximumNearBlackFractionInclusive': 0,
        'darkMaximumNeutralFractionExclusive': 0.01,
        'neutralSaturationThreshold': 0.07,
        'darkMaximumLuminanceExclusive': 0.165,
        'lightMinimumLuminanceExclusive': 0.73,
        'lightMaximumSampledHctChromaInclusive': 20,
      },
      'visualReview': {
        'darkSourcePolicy':
            'both modes first apply the shared artistic source saturation ceiling 0.60; dark retains that source hue and saturation and only attenuates value',
        'retiredDarkMaximumHctChroma': 24,
        'darkChromaGateRetirementReason':
            'the former dark-only chroma ceiling was an inferred anti-glare proxy; extreme saturation is now softened once in the shared source palette for both modes, while dark changes only value',
        'darkChromaReviewPolicy':
            'record actual sampled HCT chroma for visual review; enforce shared-source hue/saturation retention, value attenuation, zero near-black area, and no neutral holes instead of dark-specific desaturation',
        'darkValueAttenuationFactors': [0.32, 0.46, 0.50, 0.38],
        'darkValueMappingIterations': 0,
        'darkToneMappingSemantics':
            'preserve the shared artistic source HSV hue and saturation; interpolate its original value multiplied by fixed attenuation factors, with no equal-luminance compensation',
        'retiredEqualLuminanceGates': {
          'minimumSampledLuminance': 0.035,
          'maximumMappedLuminance': 0.085,
          'nearBlackLuminanceThreshold': 0.02,
        },
        'equalLuminanceRetirementReason':
            'fixed luminance targets raised pure blue toward HSV value 1 because its maximum luminance is only 0.0722; dark now attenuates every selected value, and low-luminance colored blue is not classified as a black hole',
        'retiredDarkLuminanceFloor': 0.07,
        'darkLuminanceFloorChangeReason':
            'user clarified dark means low-brightness color, not a desaturated gray background; the earlier floor was an inferred anti-hole proxy',
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
                'sample': randomSeeds.contains(seed) ? 'random' : 'regression',
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
          final sourceValues = [
            for (final color in palette) HSVColor.fromColor(color).value,
          ];
          final minimumSourceValue = sourceValues.reduce(math.min);
          final maximumSourceValue = sourceValues.reduce(math.max);
          List<int>? previous;
          for (final seed in paletteCase.key == 'soft' ? seeds : randomSeeds) {
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
                  png!.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
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
            var minimumSaturation = 1.0;
            var maximumSaturation = 0.0;
            var totalSaturation = 0.0;
            var minimumValue = 1.0;
            var maximumValue = 0.0;
            for (var offset = 0; offset < bytes.length; offset += 64) {
              opaque = opaque && bytes[offset + 3] == 255;
              final color = Color.fromARGB(
                255,
                bytes[offset],
                bytes[offset + 1],
                bytes[offset + 2],
              );
              final luminance = color.computeLuminance();
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
              minimumValue = math.min(minimumValue, pixel.value);
              maximumValue = math.max(maximumValue, pixel.value);
              if (pixel.value < 0.20) nearBlackSamples++;
              minimumSaturation = math.min(minimumSaturation, pixel.saturation);
              maximumSaturation = math.max(maximumSaturation, pixel.saturation);
              totalSaturation += pixel.saturation;
              if (pixel.saturation < 0.07) neutralSamples++;
              if (pixel.saturation < 0.07) continue;
              for (var index = 0; index < palette.length; index++) {
                final delta =
                    (pixel.hue - HSVColor.fromColor(palette[index]).hue).abs();
                if (math.min(delta, 360 - delta) < 20) coverage[index]++;
              }
            }
            final meanLuminance = totalLuminance / samples;
            final pixelDigest = sha256.convert(bytes).toString();
            final meanSaturation = totalSaturation / samples;
            if (brightness == Brightness.dark) {
              darkPixelHashes.add('$name:$pixelDigest');
            } else {
              lightMeanSaturations.add(meanSaturation);
            }
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
              if (brightness == Brightness.light && maxChroma > 20)
                'light sampled HCT chroma must remain at or below 20',
              if (seedDifference != null && seedDifference <= 3)
                'consecutive seed mean RGB difference must exceed 3',
              if (verticalVariation <= 3)
                'vertical mean RGB difference must exceed 3',
              if (varyingRows <= 0.75) 'varying row fraction must exceed 0.75',
              for (var index = 0; index < coverage.length; index++)
                if (coverage[index] / samples <= 0.01)
                  'selected color $index hue coverage must exceed 0.01',
              if (brightness == Brightness.dark) ...[
                if (minimumSourceValue >= 0.70 && nearBlackSamples != 0)
                  'dark near-black area below HSV value 0.20 must be zero for normal-value sources',
                if (minimumValue < minimumSourceValue * 0.30 - 1 / 255)
                  'dark value must retain its source-relative lower bound',
                if (maximumValue >= 0.51 ||
                    maximumValue > maximumSourceValue * 0.50 + 1 / 255)
                  'dark value must stay below 0.51 and the attenuated source ceiling',
                if (neutralSamples / samples >= 0.01)
                  'dark neutral area must stay below one percent',
                if (maxLuminance >= 0.165)
                  'dark maximum luminance must be below 0.165',
              ] else if (minLuminance <= 0.73)
                'light minimum luminance must exceed 0.73',
            ];
            failures.addAll(failedChecks.map((failure) => '$name: $failure'));
            metrics[name] = {
              'file': '$name.png',
              'rawRgbaSha256': pixelDigest,
              'palette': paletteCase.key,
              'maximumSampledHctChroma': maxChroma,
              'minimumSampledValue': minimumValue,
              'maximumSampledValue': maximumValue,
              'selectedValues': sourceValues,
              'sourceRelativeMinimumValue': minimumSourceValue * 0.30 - 1 / 255,
              'sourceRelativeMaximumValue': maximumSourceValue * 0.50 + 1 / 255,
              'minimumSampledSaturation': minimumSaturation,
              'maximumSampledSaturation': maximumSaturation,
              'meanSampledSaturation': meanSaturation,
              'selectedSaturations': [
                for (final selected in palette)
                  HSVColor.fromColor(selected).saturation,
              ],
              'sharedSourceSaturations': [
                for (final source in normalizeAuroraColors(palette))
                  HSVColor.fromColor(source).saturation,
              ],
              'sharedSourceColors': [
                for (final source in normalizeAuroraColors(palette))
                  source.toARGB32(),
              ],
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
    darkPixelHashes.sort();
    final darkDigest = sha256
        .convert(utf8.encode(darkPixelHashes.join('\n')))
        .toString();
    final meanLightSaturation =
        lightMeanSaturations.reduce((a, b) => a + b) /
        lightMeanSaturations.length;
    final saturationRatio = meanLightSaturation / _baselineLightMeanSaturation;
    if (darkDigest != _baselineDarkPixelDigest) {
      failures.add('dark RGBA pixels must match baseline run $_baselineRun');
    }
    if (saturationRatio <= 1.06 || saturationRatio > 1.11) {
      failures.add('light mean saturation must increase by 6 to 11 percent');
    }
    manifest['brightnessComparison'] = {
      'darkPixelDigest': darkDigest,
      'darkPixelsUnchanged': darkDigest == _baselineDarkPixelDigest,
      'lightMeanSampledSaturation': meanLightSaturation,
      'lightSaturationRatio': saturationRatio,
    };
    manifest['renderedImageCount'] = metrics.length;
    manifest['passedHardGates'] = failures.isEmpty;
    manifest['failedChecks'] = failures;
    await tester.runAsync(
      () => File(
        '${output.path}/aurora-review-manifest.json',
      ).writeAsString(const JsonEncoder.withIndent('  ').convert(manifest)),
    );
    expect(metrics, hasLength(98));
    expect(darkPixelHashes, hasLength(49));
    expect(lightMeanSaturations, hasLength(49));
    expect(failures, isEmpty, reason: failures.join('\n'));
  });

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
