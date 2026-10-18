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
  for (var row = 0; row <= 96; row++)
    for (var column = 0; column <= 64; column++) Offset(column / 64, row / 96),
]);

final _auroraIndices = List<int>.unmodifiable([
  for (var row = 0; row < 96; row++)
    for (var column = 0; column < 64; column++) ...[
      row * 65 + column,
      row * 65 + column + 1,
      (row + 1) * 65 + column,
      row * 65 + column + 1,
      (row + 1) * 65 + column + 1,
      (row + 1) * 65 + column,
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
  final palette = normalizeAuroraColors(colors);
  final dark = brightness == Brightness.dark;
  final sourceHsv = [for (final color in palette) HSVColor.fromColor(color)];
  final sourceIndices = [
    0,
    palette.length > 1 ? 1 : 0,
    palette.length > 2 ? 2 : 0,
    palette.length - 1,
  ];
  final unwrappedHues = _unwrapAuroraHues(sourceHsv);
  final darkCorners = [
    for (var index = 0; index < 4; index++)
      (
        hue: unwrappedHues[sourceIndices[index]],
        saturation: sourceHsv[sourceIndices[index]].saturation,
        value:
            sourceHsv[sourceIndices[index]].value *
            [0.32, 0.46, 0.50, 0.38][index],
      ),
  ];
  final tints = dark
      ? <Color>[]
      : [for (final color in palette) auroraTint(color, brightness)];
  final corners = dark
      ? <Color>[]
      : [
          _auroraTone(tints.first, tints.length == 1 ? 0.74 : 0.75),
          _auroraTone(
            tints.length > 1 ? tints[1] : tints.first,
            tints.length == 1 ? 0.95 : 0.76,
          ),
          _auroraTone(
            tints.length > 2 ? tints[2] : tints.first,
            tints.length == 1 ? 0.81 : 0.78,
          ),
          _auroraTone(tints.last, 0.88),
        ];
  final minimumSaturation = dark
      ? 0.0
      : math.min(
          0.08,
          corners
                  .map((color) => HSVColor.fromColor(color).saturation)
                  .reduce(math.min) *
              0.8,
        );
  final order = [0, 1, 2, 3];
  for (var i = order.length - 1; i > 0; i--) {
    final other = (next() * (i + 1)).floor();
    final index = order[i];
    order[i] = order[other];
    order[other] = index;
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
    double mix(double a, double b, double c, double d) =>
        (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    if (dark) {
      final a = darkCorners[order[0]];
      final b = darkCorners[order[1]];
      final c = darkCorners[order[2]];
      final d = darkCorners[order[3]];
      return HSVColor.fromAHSV(
        1,
        mix(a.hue, b.hue, c.hue, d.hue) % 360,
        mix(a.saturation, b.saturation, c.saturation, d.saturation),
        mix(a.value, b.value, c.value, d.value),
      ).toColor();
    }
    var color = Color.lerp(
      Color.lerp(corners[order[0]], corners[order[1]], u),
      Color.lerp(corners[order[2]], corners[order[3]], u),
      v,
    )!;
    final hsv = HSVColor.fromColor(color);
    if (hsv.saturation < minimumSaturation) {
      color = hsv.withSaturation(minimumSaturation).toColor();
    }
    final light = HSVColor.fromColor(color);
    color = light
        .withSaturation((light.saturation * 1.10).clamp(0.0, 1.0))
        .toColor();
    return _readableLightAuroraTint(color, base);
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
    for (final color in palette) _softAuroraSource(color),
  ]);
}

Color _softAuroraSource(Color color) {
  final opaque = color.withValues(alpha: 1);
  final hsv = HSVColor.fromColor(opaque);
  return hsv.saturation > 0.60 ? hsv.withSaturation(0.60).toColor() : opaque;
}

Color auroraBaseColor(Brightness brightness) => brightness == Brightness.dark
    ? const Color(0xFF111419)
    : const Color(0xFFF7F8FA);

Color auroraTint(Color color, Brightness brightness) {
  final source = _softAuroraSource(color);
  if (brightness == Brightness.dark) {
    final hsv = HSVColor.fromColor(source);
    return hsv.withValue(hsv.value * 0.42).toColor();
  }
  final base = auroraBaseColor(brightness);
  final tint = Color.lerp(base, source, 0.58)!;
  final readable = _readableLightAuroraTint(tint, base);
  return _limitLightAuroraChroma(readable);
}

List<double> _unwrapAuroraHues(List<HSVColor> palette) {
  final hues = [
    for (final color in palette)
      if (color.saturation > 0) color.hue,
  ]..sort();
  if (hues.isEmpty) return List.filled(palette.length, 0);
  var start = hues.first;
  var largestGap = -1.0;
  for (var index = 0; index < hues.length; index++) {
    final next = (index + 1) % hues.length;
    final gap = hues[next] + (next == 0 ? 360 : 0) - hues[index];
    if (gap > largestGap) {
      largestGap = gap;
      start = hues[next];
    }
  }
  return [
    for (final color in palette)
      color.saturation == 0 ? start : start + (color.hue - start) % 360,
  ];
}

Color _auroraTone(Color color, double luminance) {
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
  return _limitLightAuroraChroma(hsl.withLightness((low + high) / 2).toColor());
}

Color _limitLightAuroraChroma(Color readable) {
  final base = auroraBaseColor(Brightness.light);
  // HCT chroma is nonlinear under RGB interpolation; the raster's 8-bit
  // quantization also needs headroom beyond the source tint's measured chroma.
  const sourceLimit = 18.5;
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
  return _readableLightAuroraTint(Color.lerp(neutral, readable, low)!, base);
}

Color _readableLightAuroraTint(Color tint, Color base) {
  bool readable(Color tint) => tint.computeLuminance() >= 0.74;
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
