import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

typedef AuroraMesh = ({
  List<Offset> positions,
  List<Color> colors,
  List<int> indices,
});

final _auroraPositions = List<Offset>.unmodifiable([
  for (var row = 0; row <= 48; row++)
    for (var column = 0; column <= 32; column++) Offset(column / 32, row / 48),
]);

final _auroraIndices = List<int>.unmodifiable([
  for (var row = 0; row < 48; row++)
    for (var column = 0; column < 32; column++) ...[
      row * 33 + column,
      row * 33 + column + 1,
      (row + 1) * 33 + column,
      row * 33 + column + 1,
      (row + 1) * 33 + column + 1,
      (row + 1) * 33 + column,
    ],
]);

AuroraMesh generateAuroraMesh(
  int seed, {
  required List<Color> colors,
  required Brightness brightness,
}) {
  var state = seed & 0xFFFFFFFF;
  if (state == 0) state = 0x6D2B79F5;
  double next() {
    state = (state ^ (state << 13)) & 0xFFFFFFFF;
    state = (state ^ (state >> 17)) & 0xFFFFFFFF;
    state = (state ^ (state << 5)) & 0xFFFFFFFF;
    return state / 0x100000000;
  }

  // Frozen domain warping follows mesh_gradient's fluid-field approach, without
  // its separately licensed noise function: github.com/folksable/mesh_gradient.
  final angle = next() * math.pi * 2;
  final cosine = math.cos(angle);
  final sine = math.sin(angle);
  final phaseX = next() * math.pi * 2;
  final phaseY = next() * math.pi * 2;
  final frequency = 3.2 + next() * 2.0;
  final amplitude = 0.24 + next() * 0.16;
  final centerX = 0.46 + next() * 0.08;
  final centerY = 0.46 + next() * 0.08;
  final base = auroraBaseColor(brightness);
  final tints = [
    for (final color in normalizeAuroraColors(colors))
      auroraTint(color, brightness),
  ];
  final corners = [
    base,
    tints.first,
    tints.length > 1 ? tints[1] : Color.lerp(base, tints.first, 0.28)!,
    tints.length > 2 ? tints[2] : tints.last,
  ];
  for (var i = corners.length - 1; i > 0; i--) {
    final other = (next() * (i + 1)).floor();
    final color = corners[i];
    corners[i] = corners[other];
    corners[other] = color;
  }
  double smooth(double value) {
    final t = (value / 0.58 + 0.5).clamp(0.0, 1.0);
    return t * t * (3 - 2 * t);
  }

  Color sample(Offset position) {
    final dx = position.dx - centerX;
    final dy = position.dy - centerY;
    var x = dx * cosine - dy * sine;
    var y = dx * sine + dy * cosine;
    final falloff = (1 - (dx * dx + dy * dy) * 4).clamp(0.0, 1.0);
    final warp = amplitude * falloff * falloff;
    x += math.sin(y * frequency + phaseX) * warp;
    y += math.sin(x * frequency * 1.25 + phaseY) * warp;
    final u = smooth(x);
    final v = smooth(y);
    return _readableAuroraTint(
      Color.lerp(
        Color.lerp(corners[0], corners[1], u),
        Color.lerp(corners[2], corners[3], u),
        v,
      )!,
      base,
      brightness,
    );
  }

  return (
    positions: _auroraPositions,
    colors: List.unmodifiable(_auroraPositions.map(sample)),
    indices: _auroraIndices,
  );
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
  final tint = Color.lerp(base, color, dark ? 0.78 : 0.58)!;
  return _readableAuroraTint(tint, base, brightness);
}

Color _readableAuroraTint(Color tint, Color base, Brightness brightness) {
  final dark = brightness == Brightness.dark;
  bool readable(Color tint) =>
      dark ? tint.computeLuminance() <= 0.16 : tint.computeLuminance() >= 0.74;
  if (readable(tint)) return tint;
  var low = 0.0;
  var high = 1.0;
  for (var i = 0; i < 12; i++) {
    final middle = (low + high) / 2;
    if (readable(Color.lerp(base, tint, middle)!)) {
      low = middle;
    } else {
      high = middle;
    }
  }
  return Color.lerp(base, tint, low)!;
}
