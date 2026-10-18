import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _scene = ValueKey('source-blur-scene');
const _size = Size(480, 480);
const _rects = [Rect.fromLTWH(100, 160, 90, 140), Rect.fromLTWH(240, 160, 100, 140)];
const _colors = [Color(0xFFB8DCF2), Color(0xFFF0BFD8), Color(0xFFD5E9D3)];

class _SourcePainter extends CustomPainter {
  const _SourcePainter(this.seed);
  final int seed;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(rect, Paint()..shader = LinearGradient(
      begin: Alignment.topLeft, end: Alignment.bottomRight,
      colors: seed.isEven ? const [Color(0xFF162B48), Color(0xFFF2ECD4)]
          : const [Color(0xFFEDD2E6), Color(0xFF204F5A)],
    ).createShader(rect));
    canvas.drawRect(Rect.fromLTWH(size.width / 2, 0, size.width / 2, size.height),
      Paint()..color = Colors.white);
  }

  @override
  bool shouldRepaint(_SourcePainter oldDelegate) => seed != oldDelegate.seed;
}

Widget _app({required LiquidGlassQuality quality, int pose = 0, int seed = 4,
  bool aurora = true, double scale = 1.05, bool nonuniform = false,
  bool perspective = false, bool edge = false, bool flatUI = false,
  bool content = false, bool wallpaperOnly = true, bool enabled = true,
  List<double> blurs = const [12, 24], LiquidGlassStyle? style}) {
  Widget source;
  if (aurora) {
    source = SeededAuroraWallpaper(colors: _colors, seed: seed,
      brightness: Brightness.light, motion: false,
      motionTime: Duration(seconds: pose * 47));
  } else {
    final matrix = Matrix4.identity()
      ..rotateZ(pose * 0.13)
      ..scaleByDouble(scale * (nonuniform ? 1.2 : 1), scale, 1, 1);
    if (perspective) matrix.setEntry(3, 0, 0.0001);
    source = ClipRect(child: OverflowBox(minWidth: 1024, maxWidth: 1024,
      minHeight: 1024, maxHeight: 1024, child: Transform(
        alignment: Alignment.center, transform: matrix,
        child: LiquidGlassColorSource(child: CustomPaint(
          painter: _SourcePainter(seed), child: const SizedBox.expand())),
      )));
  }
  return MaterialApp(home: RepaintBoundary(key: _scene,
    child: LiquidGlassScope(enabled: enabled, quality: quality,
      child: LiquidGlassHost(enabled: enabled, wallpaperOnly: wallpaperOnly,
        child: Stack(fit: StackFit.expand, children: [
          LiquidGlassColorField(child: source),
          if (flatUI || content || !wallpaperOnly) const Positioned(
            left: 205, top: 200, width: 80, height: 80,
            child: ColoredBox(color: Colors.yellow)),
          for (var index = 0; index < blurs.length; index++)
            Positioned.fromRect(rect: edge ? const Rect.fromLTWH(0, 10, 100, 100)
                : blurs.length == 2 ? _rects[index]
                : const Rect.fromLTWH(100, 160, 260, 140),
              child: flatUI ? LiquidGlassOcclusion(shape: AppShape.lg,
                child: LiquidGlass(style: style ?? LiquidGlassStyle(
                  tint: const Color(0x10FFFFFF), blur: blurs[index],
                  refraction: 0, bevel: 0, rim: 0.15, hairline: 0.2),
                  clipper: const ShapeBorderClipper(shape: AppShape.lg)))
                : LiquidGlass(style: style ?? LiquidGlassStyle(
                    tint: const Color(0x10FFFFFF), blur: blurs[index],
                    refraction: 0, bevel: 0, rim: 0.15, hairline: 0.2),
                    contentBackdrop: content,
                    clipper: const ShapeBorderClipper(shape: AppShape.lg))),
        ]),
      ),
    ),
  ));
}

Future<void> _pump(WidgetTester tester, Widget app, bool cache) async {
  LiquidGlassHost.debugSourceBlurEnabledOverride = cache;
  await tester.pumpWidget(app);
  if (ui.ImageFilter.isShaderFilterSupported && !LiquidGlass.debugShaderReady) {
    await tester.runAsync(() => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag')
      .timeout(const Duration(seconds: 30)));
  }
  tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
  await tester.pump(const Duration(milliseconds: 16));
  tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
  await tester.pump(const Duration(milliseconds: 16));
  if (ui.ImageFilter.isShaderFilterSupported) expect(LiquidGlass.debugShaderReady, isTrue);
}

Future<({Uint8List bytes, int width, int height})> _shot(WidgetTester tester, [String? tag]) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: tester.view.devicePixelRatio);
    try {
      final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (tag != null && output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('$output/source-blur-$tag.png').writeAsBytesSync(png.buffer.asUint8List());
      }
      return (bytes: bytes.buffer.asUint8List(), width: image.width, height: image.height);
    } finally { image.dispose(); }
  }))!;
}

typedef _Shot = ({Uint8List bytes, int width, int height});

({int maximum, double rms, double contrast}) _oracle(_Shot baseline, _Shot cached,
    List<Rect> regions, double dpr) {
  expect(cached.width, baseline.width);
  expect(cached.height, baseline.height);
  var maximum = 0;
  var alpha = 0;
  var squares = 0.0;
  var count = 0;
  var contrast = 0.0;
  for (final region in regions) {
    var lowA = 255.0;
    var highA = 0.0;
    var lowB = 255.0;
    var highB = 0.0;
    for (var y = math.max(0, (region.top * dpr).floor());
        y < math.min(baseline.height, (region.bottom * dpr).ceil()); y++) {
      for (var x = math.max(0, (region.left * dpr).floor());
          x < math.min(baseline.width, (region.right * dpr).ceil()); x++) {
        final offset = (y * baseline.width + x) * 4;
        final a = baseline.bytes;
        final b = cached.bytes;
        for (var channel = 0; channel < 4; channel++) {
          final error = (a[offset + channel] - b[offset + channel]).abs();
          maximum = math.max(maximum, error);
          if (channel == 3) { alpha = math.max(alpha, error); }
          else { squares += error * error; count++; }
        }
        final lumaA = a[offset] * 0.2126 + a[offset + 1] * 0.7152 + a[offset + 2] * 0.0722;
        final lumaB = b[offset] * 0.2126 + b[offset + 1] * 0.7152 + b[offset + 2] * 0.0722;
        lowA = math.min(lowA, lumaA); highA = math.max(highA, lumaA);
        lowB = math.min(lowB, lumaB); highB = math.max(highB, lumaB);
      }
    }
    final rangeA = highA - lowA;
    final rangeB = highB - lowB;
    expect((rangeA - rangeB).abs(), lessThanOrEqualTo(1), reason: 'contrast must not soften twice');
    contrast = math.max(contrast, rangeA);
  }
  final rms = math.sqrt(squares / count);
  expect(maximum, lessThanOrEqualTo(2), reason: 'whole ROI includes curved edges and rim');
  expect(alpha, lessThanOrEqualTo(1));
  expect(rms, lessThanOrEqualTo(1));
  return (maximum: maximum, rms: rms, contrast: contrast);
}

Map<String, Object> _metrics(SourceBlurCapture value) => {
  'builds': value.sourceBlurBuilds, 'hits': value.sourceBlurHits,
  'pixels': value.sourceBlurPixels, 'fallbacks': value.fallbacks,
  'evictions': value.evictions, 'generation': value.generation,
  'entries': value.entries, 'pinned': value.pinned, 'zeroSigma': value.zeroSigma,
  'reasons': value.reasons,
};

Future<void> _unmount(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
  expect(LiquidGlassHost.debugLiveSourceBlurPixels, 0);
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag')
          .timeout(const Duration(seconds: 30));
    }
  });
  tearDown(() {
    LiquidGlassHost.debugSourceBlurEnabledOverride = null;
    LiquidGlassHost.debugSourceBlurBudgetOverride = null;
  });

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('real Aurora poses share only exact source sigmas $quality', (tester) async {
      tester.view.physicalSize = _size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final poses = quality == LiquidGlassQuality.standard ? 24 : 48;
      var maximum = 0;
      var maximumRms = 0.0;
      var contrast = 0.0;
      for (var pose = 0; pose < poses; pose++) {
        await _pump(tester, _app(quality: quality, pose: pose), false);
        final before = await _shot(tester, pose == 0 ? '${quality.name}-baseline' : null);
        await _pump(tester, _app(quality: quality, pose: pose), true);
        final after = await _shot(tester, pose == poses - 1 ? '${quality.name}-cached' : null);
        final result = _oracle(before, after, [for (final rect in _rects) rect.inflate(2)], 1);
        maximum = math.max(maximum, result.maximum);
        maximumRms = math.max(maximumRms, result.rms);
        contrast = math.max(contrast, result.contrast);
        final stats = LiquidGlassHost.debugLastSourceBlurCapture!;
        expect(stats.sourceBlurBuilds, 2);
        expect(stats.entries, 2);
        expect(stats.sourceBlurPixels, lessThanOrEqualTo(8 * 1024 * 1024));
      }
      expect(contrast, greaterThan(8));
      final stats = LiquidGlassHost.debugLastSourceBlurCapture!;
      expect(stats.sourceBlurHits, greaterThan(poses));
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        File('$output/source-blur-${quality.name}-metrics.json').writeAsStringSync(jsonEncode({
          ..._metrics(stats), 'poses': poses, 'maxError': maximum,
          'maxRms': maximumRms, 'contrast': contrast,
          'shaderSupport': ui.ImageFilter.isShaderFilterSupported,
        }));
      }
      await _unmount(tester);
    });
  }

  testWidgets('budget pins current styles and source replacement frees textures', (tester) async {
    tester.view.physicalSize = _size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    LiquidGlassHost.debugSourceBlurBudgetOverride = 2 * 1024 * 1024;
    for (var frame = 0; frame < 8; frame++) {
      await _pump(tester, _app(quality: LiquidGlassQuality.ultra, aurora: false,
        pose: frame, blurs: frame.isEven ? const [12, 16, 24] : const [24, 16, 12]), true);
      final stats = LiquidGlassHost.debugLastSourceBlurCapture!;
      expect(stats.sourceBlurBuilds, 2);
      expect(stats.sourceBlurPixels, 2 * 1024 * 1024);
      expect(stats.evictions, 0);
      expect(stats.pinned, 3);
      expect(stats.reasons['budget'], greaterThan(0));
    }
    final generation = LiquidGlassHost.debugLastSourceBlurCapture!.generation;
    await _pump(tester, _app(quality: LiquidGlassQuality.ultra, aurora: false,
      seed: 5, blurs: const [12, 16, 24]), true);
    final changed = LiquidGlassHost.debugLastSourceBlurCapture!;
    expect(changed.generation, greaterThan(generation));
    expect(changed.sourceBlurBuilds, 4);
    expect(changed.sourceBlurPixels, 2 * 1024 * 1024);
    LiquidGlassHost.debugSourceBlurBudgetOverride = 0;
    await _pump(tester, _app(quality: LiquidGlassQuality.ultra, aurora: false,
      seed: 5, blurs: const [12, 16, 24]), true);
    expect(LiquidGlassHost.debugLastSourceBlurCapture!.sourceBlurPixels, 0);
    expect(LiquidGlassHost.debugLiveSourceBlurPixels, 0);
    await _unmount(tester);
  });

  testWidgets('scale and haze keep softness and one blur while sigma zero borrows source', (tester) async {
    tester.view.physicalSize = _size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    const style = LiquidGlassStyle(tint: Colors.transparent, blur: 12,
      haze: 0.25, hazeBlur: 24, refraction: -2, bevel: 2, rim: 0.2,
      rimBlur: 1, dispersion: 1.2);
    for (final scale in [1.05, 1.12]) {
      for (var pose = 0; pose < 4; pose++) {
        final app = _app(quality: LiquidGlassQuality.ultra, aurora: false,
          scale: scale, pose: pose, style: style, blurs: const [12]);
        await _pump(tester, app, false);
        final baseline = await _shot(tester);
        await _pump(tester, app, true);
        final actual = await _shot(tester, 'haze-$scale-$pose');
        _oracle(baseline, actual, [const Rect.fromLTWH(98, 158, 264, 144)], 2);
      }
    }
    expect(LiquidGlassHost.debugLastSourceBlurCapture!.sourceBlurBuilds, 4);
    await _pump(tester, _app(quality: LiquidGlassQuality.ultra, aurora: false,
      blurs: const [0]), true);
    final clear = LiquidGlassHost.debugLastSourceBlurCapture!;
    expect(clear.sourceBlurBuilds, 4);
    expect(clear.zeroSigma, greaterThan(0));
    await _unmount(tester);
  });

  testWidgets('unsupported transforms and viewport kernels use exact old pixels', (tester) async {
    tester.view.physicalSize = _size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    for (final kind in ['nonuniform', 'perspective', 'edge']) {
      final app = _app(quality: LiquidGlassQuality.ultra, aurora: false,
        nonuniform: kind == 'nonuniform', perspective: kind == 'perspective',
        edge: kind == 'edge', blurs: const [16]);
      await _pump(tester, app, false);
      final before = await _shot(tester);
      await _pump(tester, app, true);
      final after = await _shot(tester, 'fallback-$kind');
      expect(after.bytes, before.bytes);
      expect(LiquidGlassHost.debugLastSourceBlurCapture!.sourceBlurBuilds, 0);
      expect(LiquidGlassHost.debugLastSourceBlurCapture!.sourceBlurPixels, 0);
      expect(LiquidGlassHost.debugLastSourceBlurCapture!.fallbacks, greaterThan(0));
      await _unmount(tester);
    }
  });

  testWidgets('flat UI generic and content backdrops preserve the UI blur once', (tester) async {
    tester.view.physicalSize = _size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    for (final kind in ['flatUI', 'generic', 'content']) {
      final app = _app(quality: LiquidGlassQuality.ultra,
        flatUI: kind == 'flatUI', wallpaperOnly: kind != 'generic',
        content: kind == 'content', blurs: const [16]);
      await _pump(tester, app, false);
      final before = await _shot(tester);
      await _pump(tester, app, true);
      final after = await _shot(tester, 'ui-$kind');
      expect(after.bytes, before.bytes);
      final index = (240 * after.width + 245) * 4;
      expect(after.bytes[index], greaterThan(230));
      expect(after.bytes[index + 1], greaterThan(230));
      expect(after.bytes[index + 2], lessThan(40));
      expect(LiquidGlassHost.debugLastSourceBlurCapture!.sourceBlurBuilds, 0);
      await _unmount(tester);
    }
  });
}
