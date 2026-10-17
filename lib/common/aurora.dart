import 'dart:math' as math;

import 'package:material_color_utilities/material_color_utilities.dart'
    show Hct;
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
  final dark = brightness == Brightness.dark;
  final corners = [
    _auroraTone(tints.first, dark ? 0.15 : 0.75, brightness),
    _auroraTone(
      tints.length > 1 ? tints[1] : tints.first,
      dark ? 0.11 : (tints.length == 1 ? 0.89 : 0.76),
      brightness,
    ),
    _auroraTone(
      tints.length > 2 ? tints[2] : tints.first,
      dark ? 0.13 : (tints.length == 1 ? 0.83 : 0.78),
      brightness,
    ),
    _auroraTone(tints.last, dark ? 0.085 : 0.88, brightness),
  ];
  // Opposing selected hues can cancel in RGB; a palette-relative floor keeps
  // that blend colored while leaving intentionally neutral palettes neutral.
  final minimumSaturation = math.min(
    dark ? 0.12 : 0.08,
    corners
        .map((color) => HSVColor.fromColor(color).saturation)
        .reduce(math.min) * 0.8,
  );
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
    var color = Color.lerp(
      Color.lerp(corners[0], corners[1], u),
      Color.lerp(corners[2], corners[3], u),
      v,
    )!;
    final hsv = HSVColor.fromColor(color);
    if (hsv.saturation < minimumSaturation) {
      color = hsv.withSaturation(minimumSaturation).toColor();
    }
    return _readableAuroraTint(color, base, brightness);
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
  final readable = _readableAuroraTint(tint, base, brightness);
  return _limitAuroraChroma(readable, brightness);
}

Color _auroraTone(Color color, double luminance, Brightness brightness) {
  final hsl = HSLColor.fromColor(color);
  var low = 0.0;
  var high = 1.0;
  for (var i = 0; i < 14; i++) {
    final middle = (low + high) / 2;
    if (hsl.withLightness(middle).toColor().computeLuminance() < luminance) {
      low = middle;
    } else {
      high = middle;
    }
  }
  return _limitAuroraChroma(
    hsl.withLightness((low + high) / 2).toColor(),
    brightness,
  );
}

Color _limitAuroraChroma(Color readable, Brightness brightness) {
  final base = auroraBaseColor(brightness);
  final cap = brightness == Brightness.dark ? 24.0 : 20.0;
  // HCT chroma is nonlinear under RGB interpolation; the raster's 8-bit
  // quantization also needs headroom beyond the source tint's measured chroma.
  final sourceLimit = cap - 1.5;
  final hct = Hct.fromInt(readable.toARGB32());
  if (hct.chroma <= sourceLimit) return readable;
  final neutral = Color(Hct.from(0, 0, hct.tone).toInt());
  var low = 0.0;
  var high = 1.0;
  for (var i = 0; i < 12; i++) {
    final middle = (low + high) / 2;
    final candidate = Color.lerp(neutral, readable, middle)!;
    if (Hct.fromInt(candidate.toARGB32()).chroma <= sourceLimit) {
      low = middle;
    } else {
      high = middle;
    }
  }
  return _readableAuroraTint(
    Color.lerp(neutral, readable, low)!,
    base,
    brightness,
  );
}

Color _readableAuroraTint(Color tint, Color base, Brightness brightness) {
  final dark = brightness == Brightness.dark;
  if (dark && tint.computeLuminance() < 0.08) {
    var low = 0.0;
    var high = 1.0;
    for (var i = 0; i < 12; i++) {
      final middle = (low + high) / 2;
      if (Color.lerp(tint, Colors.white, middle)!.computeLuminance() < 0.08) {
        low = middle;
      } else {
        high = middle;
      }
    }
    return Color.lerp(tint, Colors.white, high)!;
  }
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
