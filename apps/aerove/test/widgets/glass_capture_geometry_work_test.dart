import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _sceneKey = ValueKey('capture-geometry-scene');
const _scrollKey = ValueKey('capture-geometry-scroll');
const _size = Size(480, 480);
const _frameTime = Duration(microseconds: 8333);

class _Pattern extends CustomPainter {
  const _Pattern();

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..shader = const LinearGradient(
          colors: [Color(0xFF163458), Color(0xFFF5D9BD)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ).createShader(rect),
    );
    for (var x = 0.0; x < size.width; x += 32) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()..color = const Color(0x8878D3E0),
      );
    }
  }

  @override
  bool shouldRepaint(_Pattern oldDelegate) => false;
}

Widget _pane({double blur = 12}) => LiquidGlass(
  style: LiquidGlassStyle(
    tint: const Color(0x10FFFFFF),
    blur: blur,
    refraction: 0,
    bevel: 0,
    rim: 0.15,
    hairline: 0.2,
  ),
  clipper: const ShapeBorderClipper(shape: AppShape.lg),
  child: const SizedBox.expand(),
);

Widget _scene({required Matrix4 matrix, required Widget child}) => MaterialApp(
  theme: ThemeData(platform: TargetPlatform.android),
  home: RepaintBoundary(
    key: _sceneKey,
    child: LiquidGlassScope(
      enabled: true,
      quality: LiquidGlassQuality.ultra,
      child: LiquidGlassHost(
        enabled: true,
        wallpaperOnly: true,
        child: Stack(
          fit: StackFit.expand,
          children: [
            LiquidGlassColorField(
              child: ClipRect(
                child: OverflowBox(
                  minWidth: 1024,
                  maxWidth: 1024,
                  minHeight: 1024,
                  maxHeight: 1024,
                  child: Transform(
                    alignment: Alignment.center,
                    transform: matrix,
                    child: const LiquidGlassColorSource(
                      child: CustomPaint(painter: _Pattern()),
                    ),
                  ),
                ),
              ),
            ),
            child,
          ],
        ),
      ),
    ),
  ),
);

Widget _list(
  Axis axis,
  ScrollController controller, {
  bool edge = false,
  double? blur,
}) => Positioned.fromRect(
  rect: edge ? Offset.zero & _size : const Rect.fromLTWH(96, 96, 288, 288),
  child: ScrollConfiguration(
    behavior: const ScrollBehavior().copyWith(
      overscroll: false,
      scrollbars: false,
    ),
    child: ListView.builder(
      key: _scrollKey,
      controller: controller,
      scrollDirection: axis,
      physics: const ClampingScrollPhysics(),
      padding: EdgeInsets.zero,
      itemExtent: 96,
      itemCount: 20,
      itemBuilder: (_, index) => Flex(
        direction: axis == Axis.vertical ? Axis.horizontal : Axis.vertical,
        children: [
          for (var column = 0; column < 3; column++)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: _pane(blur: blur ?? (index.isEven ? 12 : 24)),
              ),
            ),
        ],
      ),
    ),
  ),
);

Future<void> _pump(WidgetTester tester, Widget app) async {
  await tester.pumpWidget(app);
  if (ui.ImageFilter.isShaderFilterSupported && !LiquidGlass.debugShaderReady) {
    await tester.runAsync(
      () => ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30)),
    );
  }
  for (var frame = 0; frame < 3; frame++) {
    tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
    await tester.pump(_frameTime);
  }
  if (ui.ImageFilter.isShaderFilterSupported) {
    expect(LiquidGlass.debugShaderReady, isTrue);
  }
}

typedef _Frame = ({Map<String, int> work, Map<String, Object?> state});

Future<_Frame> _record(WidgetTester tester, String tag) async {
  final capture = LiquidGlassHost.debugLastCapture!;
  final source = LiquidGlassHost.debugLastSourceBlurCapture!;
  final work = {
    'requests': source.sourceGeometryRequests,
    'validations': source.sourceMatrixValidations,
    'inversions': source.sourceMappingInversions,
    'paneAncestorSteps': capture.paneAncestorSearchSteps,
    'opacityAncestorSteps': capture.opacityAncestorSearchSteps,
  };
  final state = <String, Object?>{
    'builds': source.sourceBlurBuilds,
    'hits': source.sourceBlurHits,
    'pixels': source.sourceBlurPixels,
    'fallbacks': source.fallbacks,
    'evictions': source.evictions,
    'generation': source.generation,
    'entries': source.entries,
    'pinned': source.pinned,
    'zeroSigma': source.zeroSigma,
    'reasons': source.reasons,
    'snapshots': capture.snapshots,
    'keptBands': capture.keptBands,
    'atlasPixels': capture.atlasPixels,
    'skippedPanes': capture.skippedPanes,
    'panes': capture.panes.length,
    'gatherVisitedLayers': capture.gatherVisitedLayers,
    'gatheredCoverage': capture.gatheredCoverage,
    'flatPanes': capture.flatPanes,
    'flatGroups': LiquidGlassHost.debugLastFlatCapture!.groups,
  };
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_sceneKey),
  );
  await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final bytes = Uint8List.fromList(data.buffer.asUint8List());
      final evidence = {
        'width': image.width,
        'height': image.height,
        'work': work,
        'state': state,
      };
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        File('$output/capture-geometry-$tag.rgba').writeAsBytesSync(bytes);
        File('$output/capture-geometry-$tag.json').writeAsStringSync(
          const JsonEncoder.withIndent('  ').convert(evidence),
        );
      }
      final baseline = Platform.environment['XCLASH_CAPTURE_GEOMETRY_BASELINE'];
      if (baseline != null) {
        final expected =
            jsonDecode(
                  File(
                    '$baseline/capture-geometry-$tag.json',
                  ).readAsStringSync(),
                )
                as Map<String, dynamic>;
        expect(image.width, expected['width'], reason: tag);
        expect(image.height, expected['height'], reason: tag);
        expect(state, expected['state'], reason: '$tag cache/state changed');
        expect(
          listEquals(
            bytes,
            File('$baseline/capture-geometry-$tag.rgba').readAsBytesSync(),
          ),
          isTrue,
          reason: '$tag full-viewport rawRGBA must be byte-identical',
        );
      }
    } finally {
      image.dispose();
    }
  });
  expect(tester.takeException(), isNull);
  return (work: work, state: state);
}

void _expectSharedSource(List<_Frame> frames) {
  expect(frames.any((frame) => frame.work['requests']! > 1), isTrue);
  expect(frames.any((frame) => frame.work['inversions']! > 0), isTrue);
  for (final frame in frames) {
    expect(frame.work['validations'], lessThanOrEqualTo(1));
    expect(frame.work['inversions'], lessThanOrEqualTo(1));
  }
}

void _expectAncestorSummary(List<_Frame> frames) {
  for (final frame in frames) {
    expect(frame.work['paneAncestorSteps'], 0);
    expect(frame.work['opacityAncestorSteps'], 0);
    expect(frame.state['gatherVisitedLayers'], greaterThan(0));
  }
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });
  setUp(() {
    LiquidGlassHost.debugSourceBlurEnabledOverride = true;
  });
  tearDown(() {
    LiquidGlassHost.debugSourceBlurEnabledOverride = null;
  });

  for (final axis in Axis.values) {
    testWidgets('real $axis scroll shares source geometry and ancestry work', (
      tester,
    ) async {
      if (!ui.ImageFilter.isShaderFilterSupported) {
        markTestSkipped('Source geometry requires the shader-filter backend.');
        return;
      }
      tester.view.physicalSize = _size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      addTearDown(controller.dispose);
      final matrix = Matrix4.diagonal3Values(1.05, 1.05, 1);
      await _pump(
        tester,
        _scene(matrix: matrix, child: _list(axis, controller)),
      );
      final frames = <_Frame>[await _record(tester, '${axis.name}-still')];
      final gesture = await tester.startGesture(
        tester.getCenter(find.byKey(_scrollKey)),
      );
      for (var frame = 0; frame < 10; frame++) {
        final delta = frame < 6 ? -12.0 : 12.0;
        await gesture.moveBy(
          axis == Axis.vertical ? Offset(0, delta) : Offset(delta, 0),
        );
        await tester.pump(_frameTime);
        frames.add(await _record(tester, '${axis.name}-drag-$frame'));
      }
      await gesture.cancel();
      controller.jumpTo(controller.offset);
      await tester.pump(_frameTime);
      frames.add(await _record(tester, '${axis.name}-cancel'));
      _expectSharedSource(frames);
      _expectAncestorSummary(frames);
    });
  }

  testWidgets('source geometry refreshes for changed affine and edge inputs', (
    tester,
  ) async {
    if (!ui.ImageFilter.isShaderFilterSupported) {
      markTestSkipped('Source geometry requires the shader-filter backend.');
      return;
    }
    tester.view.physicalSize = _size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final controller = ScrollController();
    addTearDown(controller.dispose);
    final matrix = Matrix4.identity();
    final frames = <_Frame>[];
    Future<_Frame> sample(String tag, {bool edge = false, double? blur}) async {
      await _pump(
        tester,
        _scene(
          matrix: matrix,
          child: _list(Axis.vertical, controller, edge: edge, blur: blur),
        ),
      );
      final result = await _record(tester, 'affine-$tag');
      frames.add(result);
      return result;
    }

    matrix.scaleByDouble(1.05, 1.05, 1, 1);
    await sample('scale');
    matrix.rotateZ(0.18);
    await sample('in-place-rotation');
    matrix.scaleByDouble(1.04, 1.04, 1, 1);
    await sample('in-place-scale');
    matrix.setIdentity();
    matrix.scaleByDouble(1.2, 1.05, 1, 1);
    final nonuniform = await sample('nonuniform');
    expect((nonuniform.state['reasons']! as Map)['transform'], greaterThan(0));
    matrix.setIdentity();
    matrix.setEntry(3, 0, 0.0001);
    final perspective = await sample('perspective');
    expect((perspective.state['reasons']! as Map)['source'], greaterThan(0));
    matrix.setIdentity();
    matrix.scaleByDouble(0, 1, 1, 1);
    await sample('singular');
    matrix.setIdentity();
    matrix.scaleByDouble(1.05, 1.05, 1, 1);
    final edge = await sample('edge', edge: true);
    expect((edge.state['reasons']! as Map)['edge'], greaterThan(0));
    final zero = await sample('zero', blur: 0);
    expect(zero.work['requests'], 0);
    expect(zero.work['validations'], 0);
    expect(zero.work['inversions'], 0);
    expect(zero.state['zeroSigma'], greaterThan(0));
    _expectSharedSource(frames);
  });

  testWidgets('nearest false boundary and opacity summaries restore siblings', (
    tester,
  ) async {
    tester.view.physicalSize = _size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final matrix = Matrix4.identity();
    final frames = <_Frame>[];
    await _pump(
      tester,
      _scene(
        matrix: matrix,
        child: Positioned(
          left: 96,
          top: 96,
          width: 288,
          height: 96,
          child: LiquidGlassOcclusion(
            shape: AppShape.none,
            child: Row(
              children: [
                Expanded(
                  child: LiquidGlassOcclusion(
                    shape: AppShape.none,
                    captureContent: false,
                    child: _pane(),
                  ),
                ),
                Expanded(child: _pane()),
              ],
            ),
          ),
        ),
      ),
    );
    final boundaries = await _record(tester, 'boundary-nearest-false');
    frames.add(boundaries);
    expect(boundaries.state['flatPanes'], 1);
    expect(boundaries.state['flatGroups'], 1);
    for (final reversed in [false, true]) {
      Widget marker(double opacity) => Opacity(
        opacity: opacity,
        child: const LiquidGlassOcclusion(
          shape: AppShape.none,
          mode: LiquidGlassOcclusionMode.plain,
          captureContent: false,
          child: SizedBox(width: 80, height: 80),
        ),
      );
      await _pump(
        tester,
        _scene(
          matrix: matrix,
          child: Stack(
            children: [
              Positioned(
                left: 96,
                top: 96,
                width: 192,
                height: 96,
                child: _pane(),
              ),
              Positioned(
                left: 96,
                top: 96,
                child: Row(
                  children: [
                    marker(reversed ? 1 : 0.5),
                    marker(reversed ? 0.5 : 1),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
      final opacity = await _record(tester, 'opacity-siblings-$reversed');
      frames.add(opacity);
      expect(opacity.state['gatheredCoverage'], 1);
      expect(opacity.state['flatPanes'], 0);
    }
    _expectAncestorSummary(frames);
  });
}
