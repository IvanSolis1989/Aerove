import 'package:fl_clash/common/aurora.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Hct;
import 'package:material_ui/material_ui.dart';

void main() {
  const selected = [Color(0xFF6BD5D9), Color(0xFFC6ADE4), Color(0xFFA9DAC6)];

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
    expect(first.positions, hasLength(33 * 49));
    expect(first.indices, hasLength(32 * 48 * 6));
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
    test('fully saturated selections have bounded chroma ($brightness)', () {
      for (final color in const [
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF0000FF),
        Color(0xFFFF00FF),
        Color(0xFF00FFFF),
        Color(0xFFFFFF00),
      ]) {
        final tint = auroraTint(color, brightness);
        expect(
          Hct.fromInt(tint.toARGB32()).chroma,
          lessThanOrEqualTo(brightness == Brightness.dark ? 24 : 20),
        );
        final hueDelta = (HSLColor.fromColor(tint).hue -
                HSLColor.fromColor(color).hue)
            .abs();
        expect(hueDelta < 12 || hueDelta > 348, true);
        expect(
          tint.computeLuminance(),
          brightness == Brightness.dark
              ? lessThanOrEqualTo(0.161)
              : greaterThanOrEqualTo(0.739),
        );
      }
    });

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
