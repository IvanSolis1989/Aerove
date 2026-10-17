import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

typedef AuroraCloud = ({
  double x,
  double y,
  double radiusX,
  double radiusY,
  double angle,
  double strength,
});

List<AuroraCloud> generateAuroraClouds(int seed, {int colorCount = 1}) {
  final count = colorCount.clamp(1, 3);
  var state = seed & 0xFFFFFFFF;
  if (state == 0) state = 0x6D2B79F5;
  double next() {
    state = (state ^ (state << 13)) & 0xFFFFFFFF;
    state = (state ^ (state >> 17)) & 0xFFFFFFFF;
    state = (state ^ (state << 5)) & 0xFFFFFFFF;
    return state / 0x100000000;
  }

  return List.unmodifiable([
    for (var i = 0; i < 8; i++)
      (
        x: count == 1
            ? -0.1 + next() * 1.2
            : (i % count + 0.15 + (-0.1 + next() * 1.2) * 0.7) / count,
        y: 0.1 + next() * 1.05,
        radiusX: (0.3 + next() * 0.35) / math.sqrt(count),
        radiusY: 0.2 + next() * 0.25,
        angle: (next() - 0.5) * math.pi,
        strength: 0.35 + next() * 0.3,
      ),
  ]);
}

List<Color> normalizeAuroraColors(List<Color> colors) {
  final palette = colors.isEmpty
      ? const [Color(0xFF9AAFC5), Color(0xFFC4BDD3)]
      : colors.take(3);
  return List.unmodifiable([
    for (final color in palette) color.withValues(alpha: 1),
  ]);
}

Color auroraBaseColor(Brightness brightness) => brightness == Brightness.dark
    ? const Color(0xFF111419)
    : const Color(0xFFF7F8FA);

Color auroraTint(Color color, Brightness brightness) {
  final base = auroraBaseColor(brightness);
  final dark = brightness == Brightness.dark;
  var low = 0.0;
  var high = dark ? 0.78 : 0.58;
  bool readable(Color tint) => dark
      ? tint.computeLuminance() <= 0.16
      : tint.computeLuminance() >= 0.74;
  final tint = Color.lerp(base, color, high)!;
  if (readable(tint)) return tint;
  for (var i = 0; i < 12; i++) {
    final middle = (low + high) / 2;
    if (readable(Color.lerp(base, color, middle)!)) {
      low = middle;
    } else {
      high = middle;
    }
  }
  return Color.lerp(base, color, low)!;
}
