import 'package:fl_clash/common/aurora.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  const selected = [Color(0xFF6BD5D9), Color(0xFFC6ADE4), Color(0xFFA9DAC6)];

  for (final brightness in Brightness.values) {
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
