import 'dart:math' as math;

import 'package:fl_clash/common/aurora.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart'
    show Hct;
import 'package:material_ui/material_ui.dart';

void main() {
  const selected = [Color(0xFF6BD5D9), Color(0xFFC6ADE4), Color(0xFFA9DAC6)];

  test('empty input uses the fresh cyan purple green palette', () {
    expect(normalizeAuroraColors([]), const [
      Color(0xFF65BDD2),
      Color(0xFFC589CA),
      Color(0xFF75CCA8),
    ]);
    const saved = [Color(0xFF9AAFC5), Color(0xFFC4BDD3)];
    expect(normalizeAuroraColors(saved), saved);
  });

  test(
    'shared source softening retains hue and value in both brightness paths',
    () {
      expect(normalizeAuroraColors(selected), selected);
      for (final color in const [
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF0000FF),
      ]) {
        final original = HSVColor.fromColor(color);
        final shared = normalizeAuroraColors([color]).single;
        final source = HSVColor.fromColor(shared);
        expect(source.hue, closeTo(original.hue, 1));
        expect(source.value, closeTo(original.value, 1 / 255));
        expect(source.saturation, closeTo(0.60, 1 / 255));
        for (final brightness in Brightness.values) {
          expect(auroraTint(color, brightness), auroraTint(shared, brightness));
        }
      }
    },
  );

  test('ten random seeds have no injected dark or neutral mesh regions', () {
    final random = math.Random(20261017);
    for (var sample = 0; sample < 10; sample++) {
      final seed = random.nextInt(1 << 32);
      for (var count = 1; count <= 3; count++) {
        final sourceValues = selected
            .take(count)
            .map((color) => HSVColor.fromColor(color).value);
        final minimumValue = sourceValues.reduce(math.min);
        final maximumValue = sourceValues.reduce(math.max);
        final mesh = generateAuroraMesh(
          seed,
          colors: selected.take(count).toList(),
          brightness: Brightness.dark,
        );
        for (final color in mesh.colors) {
          final value = HSVColor.fromColor(color).value;
          expect(value, greaterThanOrEqualTo(minimumValue * 0.32 - 1 / 255));
          expect(value, lessThanOrEqualTo(maximumValue * 0.50 + 1 / 255));
          expect(value, lessThan(0.51));
          expect(HSVColor.fromColor(color).saturation, greaterThan(0.07));
          expect(color, isNot(auroraBaseColor(Brightness.dark)));
        }
      }
    }
  });

  test(
    'pure RGB meshes dim the shared artistic source without further desaturation',
    () {
      for (final color in const [
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF0000FF),
      ]) {
        final source = HSVColor.fromColor(color).withSaturation(0.60);
        final mesh = generateAuroraMesh(
          832838199,
          colors: [color],
          brightness: Brightness.dark,
        );
        for (final pixel in mesh.colors) {
          final hsv = HSVColor.fromColor(pixel);
          final delta = (hsv.hue - source.hue).abs();
          expect(math.min(delta, 360 - delta), lessThan(3));
          expect(hsv.saturation, closeTo(source.saturation, 0.012));
          expect(hsv.value, lessThan(0.51));
          expect(hsv.value, lessThan(source.value));
          expect(
            hsv.value,
            greaterThanOrEqualTo(source.value * 0.32 - 1 / 255),
          );
        }
      }
    },
  );

  test(
    'dark single-color mapping changes value while retaining hue and saturation',
    () {
      final expected = HSVColor.fromColor(selected.first);
      final mesh = generateAuroraMesh(
        832838199,
        colors: selected.take(1).toList(),
        brightness: Brightness.dark,
      );
      final values = <double>[];
      for (final color in mesh.colors) {
        final hsv = HSVColor.fromColor(color);
        final delta = (hsv.hue - expected.hue).abs();
        expect(math.min(delta, 360 - delta), lessThan(3));
        expect(hsv.saturation, closeTo(expected.saturation, 0.012));
        values.add(hsv.value);
      }
      expect(
        values.reduce(math.max) - values.reduce(math.min),
        greaterThan(0.08),
      );
    },
  );

  test(
    'three hues remain continuous when one selection crosses 180 degrees',
    () {
      AuroraMesh render(double thirdHue) => generateAuroraMesh(
        832838199,
        colors: [
          HSVColor.fromAHSV(1, 0, 0.8, 0.9).toColor(),
          HSVColor.fromAHSV(1, 170, 0.8, 0.9).toColor(),
          HSVColor.fromAHSV(1, thirdHue, 0.8, 0.9).toColor(),
        ],
        brightness: Brightness.dark,
      );
      for (final (beforeHue, afterHue) in [(179.9, 180.1), (179.5, 180.5)]) {
        final before = render(beforeHue);
        final after = render(afterHue);
        for (var index = 0; index < before.colors.length; index++) {
          final previous = HSVColor.fromColor(before.colors[index]);
          final next = HSVColor.fromColor(after.colors[index]);
          final delta = (previous.hue - next.hue).abs();
          expect(math.min(delta, 360 - delta), lessThan(4));
          expect(previous.saturation, closeTo(0.60, 0.012));
          expect(next.saturation, closeTo(0.60, 0.012));
        }
      }
    },
  );

  test(
    'dark hue interpolation crosses zero without a gray or opposite-hue seam',
    () {
      final mesh = generateAuroraMesh(
        624302977,
        colors: [
          HSVColor.fromAHSV(1, 350, 0.5, 0.8).toColor(),
          HSVColor.fromAHSV(1, 10, 0.5, 0.8).toColor(),
        ],
        brightness: Brightness.dark,
      );
      for (final color in mesh.colors) {
        final hsv = HSVColor.fromColor(color);
        expect(math.min(hsv.hue, 360 - hsv.hue), lessThan(12));
        expect(hsv.saturation, greaterThan(0.24));
      }
    },
  );

  test('saved seeds produce bounded, reproducible mesh fields', () {
    final first = generateAuroraMesh(
      271828,
      colors: selected,
      brightness: Brightness.light,
    );
    final repeated = generateAuroraMesh(
      271828,
      colors: selected,
      brightness: Brightness.light,
    );
    final changed = generateAuroraMesh(
      314159,
      colors: selected,
      brightness: Brightness.light,
    );
    expect(first.positions, repeated.positions);
    expect(first.colors, repeated.colors);
    expect(first.colors, isNot(changed.colors));
    expect(first.colors, hasLength(first.positions.length));
    expect(first.positions, hasLength(65 * 97));
    expect(first.indices, hasLength(64 * 96 * 6));
    for (final position in first.positions) {
      expect(position.dx, inInclusiveRange(0, 1));
      expect(position.dy, inInclusiveRange(0, 1));
    }
    for (final index in first.indices) {
      expect(index, inInclusiveRange(0, first.positions.length - 1));
    }
  });

  test('the mesh reuses its fixed tessellation across seeds and palettes', () {
    final first = generateAuroraMesh(
      12,
      colors: selected,
      brightness: Brightness.light,
    );
    final next = generateAuroraMesh(
      13,
      colors: selected.take(1).toList(),
      brightness: Brightness.dark,
    );
    expect(identical(first.positions, next.positions), true);
    expect(identical(first.indices, next.indices), true);
  });

  for (final brightness in Brightness.values) {
    test(
      'fully saturated selections soften once before brightness mapping ($brightness)',
      () {
        for (final color in const [
          Color(0xFFFF0000),
          Color(0xFF00FF00),
          Color(0xFF0000FF),
          Color(0xFFFF00FF),
          Color(0xFF00FFFF),
          Color(0xFFFFFF00),
        ]) {
          final tint = auroraTint(color, brightness);
          if (brightness == Brightness.light) {
            expect(Hct.fromInt(tint.toARGB32()).chroma, lessThanOrEqualTo(30));
          } else {
            expect(HSVColor.fromColor(tint).saturation, closeTo(0.60, 0.012));
            final original = HSVColor.fromColor(color);
            final darkValue = HSVColor.fromColor(tint).value;
            expect(darkValue, closeTo(original.value * 0.42, 1 / 255));
            expect(darkValue, lessThan(original.value));
            expect(darkValue, lessThan(0.51));
          }
          final hueDelta =
              (HSLColor.fromColor(tint).hue - HSLColor.fromColor(color).hue)
                  .abs();
          expect(hueDelta < 12 || hueDelta > 348, true);
          expect(
            tint.computeLuminance(),
            brightness == Brightness.dark
                ? lessThanOrEqualTo(0.161)
                : greaterThanOrEqualTo(0.739),
          );
        }
      },
    );

    test(
      'tints preserve selected hue and readable luminance ($brightness)',
      () {
        for (final color in selected) {
          final tint = auroraTint(color, brightness);
          final hue = HSLColor.fromColor(color).hue;
          final tintHue = HSLColor.fromColor(tint).hue;
          final delta = (hue - tintHue).abs();
          expect(delta < 12 || delta > 348, true);
          expect(HSLColor.fromColor(tint).saturation, greaterThan(0.08));
          expect(tint.a, 1);
          expect(
            tint.computeLuminance(),
            brightness == Brightness.dark
                ? lessThanOrEqualTo(0.161)
                : greaterThanOrEqualTo(0.739),
          );
        }
      },
    );

    test('selected chroma differences survive softening ($brightness)', () {
      final vivid = HSVColor.fromColor(
        auroraTint(const Color(0xFF6BD5D9), brightness),
      );
      final muted = HSVColor.fromColor(
        auroraTint(const Color(0xFFB8D4D6), brightness),
      );
      expect(vivid.saturation, greaterThan(muted.saturation * 1.5));
    });

    test(
      'extreme colors remain within the contrast envelope ($brightness)',
      () {
        for (final color in [
          Colors.white,
          Colors.black,
          Colors.red,
          Colors.green,
          Colors.blue,
        ]) {
          final tint = auroraTint(color, brightness);
          expect(
            tint.computeLuminance(),
            brightness == Brightness.dark
                ? lessThanOrEqualTo(0.161)
                : greaterThanOrEqualTo(0.739),
          );
        }
      },
    );
  }
}
