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

List<AuroraCloud> generateAuroraClouds(int seed) {
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
        x: -0.1 + next() * 1.2,
        y: 0.1 + next() * 1.05,
        radiusX: 0.3 + next() * 0.35,
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
