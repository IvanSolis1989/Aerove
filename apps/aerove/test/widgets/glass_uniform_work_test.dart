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

const _size = Size(256, 192);
const _capture = ValueKey('uniform-retained-capture');
const _reference = ValueKey('uniform-fresh-capture');
const _pane = ValueKey('uniform-retained-pane');
const _style = LiquidGlassStyle(
  tint: Colors.transparent,
  blur: 2.5,
  hairline: 0.35,
  rim: 0.6,
);
const _corners = BorderRadius.only(
  topLeft: Radius.circular(8),
  topRight: Radius.circular(14),
  bottomRight: Radius.circular(20),
  bottomLeft: Radius.circular(26),
);

class _Pose {
  Size size = const Size(152, 72);
  Offset translation = Offset.zero;
  int background = 0;
  LiquidGlassShape shape = const LiquidGlassCorners(_corners);
  LiquidGlassStyle style = _style;
  LiquidGlassGlow glow = LiquidGlassGlow.none;
  LiquidGlassRim? rim;
  LiquidGlassQuality quality = LiquidGlassQuality.ultra;
  bool covered = false;
  bool pressed = false;
}

class _Backdrop extends CustomPainter {
  const _Backdrop(this.phase);

  final int phase;

  @override
  void paint(Canvas canvas, Size size) {
    const colors = [Color(0xFF486078), Color(0xFF907058), Color(0xFF507868)];
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()..color = colors[((x ~/ 8) + phase) % colors.length],
      );
    }
    canvas.drawRect(
      Rect.fromLTWH(0, 36 + phase % 9 * 3.0, size.width, 12),
      Paint()..color = const Color(0xFFB0A0C0),
    );
  }

  @override
  bool shouldRepaint(_Backdrop oldDelegate) => phase != oldDelegate.phase;
}

class _Pane extends LeafRenderObjectWidget {
  const _Pane({
    super.key,
    required this.shape,
    required this.style,
    required this.glow,
    required this.rim,
  });

  final LiquidGlassShape shape;
  final LiquidGlassStyle style;
  final LiquidGlassGlow glow;
  final LiquidGlassRim? rim;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _PaneBox(shape, style, glow, rim);

  @override
  void updateRenderObject(BuildContext context, _PaneBox renderObject) {
    renderObject
      ..shape = shape
      ..style = style
      ..glow = glow
      ..rim = rim
      ..markNeedsPaint();
  }
}

class _PaneBox extends RenderBox {
  _PaneBox(this.shape, this.style, this.glow, this.rim);

  LiquidGlassShape shape;
  LiquidGlassStyle style;
  LiquidGlassGlow glow;
  LiquidGlassRim? rim;
  late final pane = LiquidGlassPane(markNeedsPaint);

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  bool get sizedByParent => true;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  @override
  void paint(PaintingContext context, Offset offset) {
    pane.paint(
      context,
      offset & size,
      shape: shape,
      style: style,
      glow: glow,
      rim: rim,
    );
  }

  @override
  void dispose() {
    pane.dispose();
    super.dispose();
  }
}

Widget _scene(
  _Pose pose,
  Key captureKey,
  Key paneKey, {
  ScrollController? scrollController,
  Axis scrollAxis = Axis.vertical,
}) => SizedBox.fromSize(
  size: _size,
  child: RepaintBoundary(
    key: captureKey,
    child: LiquidGlassScope(
      enabled: true,
      foregroundEnabled: false,
      quality: pose.quality,
      child: LiquidGlassHost(
        enabled: true,
        child: ClipRect(
          child: Stack(
            fit: StackFit.expand,
            children: [
              CustomPaint(painter: _Backdrop(pose.background)),
              if (scrollController == null)
                Positioned.fromRect(
                  rect: (const Offset(40, 40) + pose.translation) & pose.size,
                  child: _Pane(
                    key: paneKey,
                    shape: pose.shape,
                    style: pose.style.forQuality(pose.quality),
                    glow: pose.glow,
                    rim: pose.quality == LiquidGlassQuality.ultra
                        ? pose.rim
                        : null,
                  ),
                )
              else
                ScrollConfiguration(
                  behavior: const ScrollBehavior().copyWith(
                    overscroll: false,
                    scrollbars: false,
                  ),
                  child: ListView.builder(
                    key: const ValueKey('uniform-scroll'),
                    controller: scrollController,
                    scrollDirection: scrollAxis,
                    physics: const ClampingScrollPhysics(),
                    padding: EdgeInsets.zero,
                    itemExtent: scrollAxis == Axis.vertical ? 84 : 168,
                    itemCount: 12,
                    itemBuilder: (_, index) => Align(
                      alignment: Alignment.centerLeft,
                      child: SizedBox.fromSize(
                        size: pose.size,
                        child: _Pane(
                          key: index == 1 ? paneKey : null,
                          shape: pose.shape,
                          style: pose.style.forQuality(pose.quality),
                          glow: pose.glow,
                          rim: pose.quality == LiquidGlassQuality.ultra
                              ? pose.rim
                              : null,
                        ),
                      ),
                    ),
                  ),
                ),
              Positioned(
                left: 72,
                top: 144,
                width: 112,
                height: 32,
                child: LiquidGlass(
                  style: pose.style,
                  borderRadius: _corners,
                  pressed: pose.pressed,
                ),
              ),
              if (pose.covered)
                const Positioned(
                  left: 24,
                  top: 24,
                  width: 184,
                  height: 104,
                  child: LiquidGlassOcclusion(
                    shape: AppShape.none,
                    child: ColoredBox(color: Color(0x18000000)),
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
  ),
);

Future<({Uint8List rgba, Uint8List png})> _pixels(
  WidgetTester tester,
  Key key,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(key));
  final layer = boundary.debugLayer! as OffsetLayer;
  return (await tester.runAsync(() async {
    final transform = Matrix4.identity()
      ..translateByDouble(-layer.offset.dx, -layer.offset.dy, 0, 1);
    final builder = ui.SceneBuilder()..pushTransform(transform.storage);
    final scene = layer.buildScene(builder);
    try {
      final image = await scene.toImage(
        _size.width.round(),
        _size.height.round(),
      );
      try {
        Future<Uint8List> bytes(ui.ImageByteFormat format) async {
          final data = (await image.toByteData(format: format))!;
          return Uint8List.fromList(
            data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
          );
        }

        return (
          rgba: await bytes(ui.ImageByteFormat.rawRgba),
          png: await bytes(ui.ImageByteFormat.png),
        );
      } finally {
        image.dispose();
      }
    } finally {
      scene.dispose();
    }
  }))!;
}

Map<String, int> _difference(Map<String, int> after, Map<String, int> before) =>
    {for (final key in after.keys) key: after[key]! - before[key]!};

Map<String, Object?> _inputs(_Pose pose) => {
  'size': [pose.size.width, pose.size.height],
  'translation': [pose.translation.dx, pose.translation.dy],
  'background': pose.background,
  'quality': pose.quality.name,
  'shape': pose.shape.runtimeType.toString(),
  'covered': pose.covered,
  'pressed': pose.pressed,
  'style': {
    'tint': [
      pose.style.tint.r,
      pose.style.tint.g,
      pose.style.tint.b,
      pose.style.tint.a,
    ],
    'contrast': pose.style.contrast,
    'blur': pose.style.blur,
    'haze': pose.style.haze,
    'hazeBlur': pose.style.hazeBlur,
    'refraction': pose.style.refraction,
    'bevel': pose.style.bevel,
    'saturation': pose.style.saturation,
    'brightness': pose.style.brightness,
    'rim': pose.style.rim,
    'rimWidth': pose.style.rimWidth,
    'rimTail': pose.style.rimTail,
    'rimFocus': pose.style.rimFocus,
    'rimAdded': pose.style.rimAdded,
    'endShade': pose.style.endShade,
    'dispersion': pose.style.dispersion,
    'rimBlur': pose.style.rimBlur,
    'hairline': pose.style.hairline,
    'hairlineSide': pose.style.hairlineSide,
  },
  'glow': {
    'center': [pose.glow.center.dx, pose.glow.center.dy],
    'reach': pose.glow.reach,
    'strength': pose.glow.strength,
    'wash': pose.glow.wash,
  },
  'rim': pose.rim == null
      ? null
      : {
          'side': pose.rim!.side,
          'corner': pose.rim!.corner,
          'flat': pose.rim!.flat,
          'shine': pose.rim!.shine,
        },
};

void _expectBaseline(Uint8List pixels, String relativePath) {
  final baseline = Platform.environment['XCLASH_UNIFORM_BASELINE'];
  if (baseline == null) return;
  expect(
    listEquals(pixels, File('$baseline/$relativePath').readAsBytesSync()),
    isTrue,
    reason: '$relativePath must match the saved full rawRGBA baseline',
  );
}

void main() {
  setUpAll(() {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
  });

  testWidgets(
    'pane differential uniforms preserve pixels through motion and invalidation',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(_size.width * 2, _size.height);
      addTearDown(tester.view.reset);
      final pose = _Pose();
      final rows = <Map<String, Object?>>[];
      final base = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      final directory =
          base == null
                ? Directory.systemTemp.createTempSync('pane-uniform-')
                : Directory('$base/pane-uniform')
            ..createSync(recursive: true);
      final pixelChecks = <({String state, int changed, int maximum})>[];
      final workChecks = <({String state, Map<String, int> delta})>[];
      Key freshKey = UniqueKey();

      Map<String, int> work() => tester
          .renderObject<_PaneBox>(find.byKey(_pane))
          .pane
          .debugUniformWork;

      Future<void> pumpPose({bool fresh = true}) async {
        if (fresh) freshKey = UniqueKey();
        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.ltr,
            child: Row(
              children: [
                _scene(pose, _capture, _pane),
                _scene(pose, _reference, freshKey),
              ],
            ),
          ),
        );
      }

      Future<void> save(String state, Map<String, int> before) async {
        final afterFrame = work();
        final actual = await _pixels(tester, _capture);
        final fresh = await _pixels(tester, _reference);
        var changed = 0;
        var maximum = 0;
        for (var index = 0; index < actual.rgba.length; index += 4) {
          var differs = false;
          for (var channel = 0; channel < 4; channel++) {
            final delta =
                (actual.rgba[index + channel] - fresh.rgba[index + channel])
                    .abs();
            if (delta > maximum) maximum = delta;
            differs |= delta != 0;
          }
          if (differs) changed++;
        }
        final name = state.replaceAll(RegExp('[^a-zA-Z0-9]+'), '-');
        final afterSnapshot = work();
        rows.add({
          'state': state,
          'inputs': _inputs(pose),
          'before': before,
          'afterFrame': afterFrame,
          'frameDelta': _difference(afterFrame, before),
          'afterSnapshot': afterSnapshot,
          'snapshotDelta': _difference(afterSnapshot, afterFrame),
          'changedPixels': changed,
          'maxChannelDelta': maximum,
          'skippedPanes': LiquidGlassHost.debugLastCapture?.skippedPanes,
          'occludedPanes': LiquidGlassHost.debugLastCapture?.occludedPanes,
        });
        await tester.runAsync(() async {
          for (final (suffix, data) in [
            ('retained.rgba', actual.rgba),
            ('fresh.rgba', fresh.rgba),
            ('retained.png', actual.png),
            ('fresh.png', fresh.png),
          ]) {
            await File('${directory.path}/$name-$suffix').writeAsBytes(data);
          }
          await File('${directory.path}/work.json').writeAsString(
            const JsonEncoder.withIndent('  ').convert({
              'size': [_size.width, _size.height],
              'pixelRatio': 1,
              'rows': rows,
              'motionFrames': 30,
              'note':
                  'setFloat is a Dart Float32List write; screenshots have '
                  'separate deltas. Fresh panes retain freeze history only '
                  'while covered. The lower LiquidGlass uses matching real '
                  'press animations in both scenes.',
            }),
          );
          _expectBaseline(actual.rgba, 'pane-uniform/$name-retained.rgba');
        });
        pixelChecks.add((state: state, changed: changed, maximum: maximum));
      }

      await pumpPose();
      if (!LiquidGlass.debugShaderReady) {
        await tester.runAsync(
          () => ui.FragmentProgram.fromAsset(
            'packages/fl_clash/shaders/liquid_glass.frag',
          ).timeout(const Duration(seconds: 30)),
        );
      }
      for (var frame = 0; frame < 4; frame++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(LiquidGlass.debugShaderReady, isTrue);
      await save('initial', work());
      final initial = work();
      for (var frame = 1; frame <= 30; frame++) {
        final before = work();
        pose.translation = frame <= 15
            ? Offset(frame.toDouble(), 0)
            : Offset(15, (frame - 15).toDouble());
        await pumpPose();
        await save('motion-$frame', before);
        workChecks.add((
          state: 'motion-$frame',
          delta: _difference(work(), before),
        ));
      }
      final motion = _difference(work(), initial);
      final staticBefore = work();
      await tester.pump(const Duration(milliseconds: 16));
      await save('static', staticBefore);
      final staticFrame = rows.last['frameDelta']! as Map<String, int>;

      Future<void> check(String state, {bool fresh = true}) async {
        final before = work();
        await pumpPose(fresh: fresh);
        await save(state, before);
      }

      if (Platform.environment['XCLASH_PANE_UNIFORM_MINIMAL'] != 'true') {
        pose.background = 1;
        await check('atlas-only');
        for (var frame = 0; frame < 4; frame++) {
          pose.background = frame + 4;
          await check('atlas-dynamic-$frame');
        }
        pose.translation = const Offset(-8, -6);
        await check('mapping-negative-xy');
        pose.glow = const LiquidGlassGlow(
          center: Offset(24, 20),
          reach: 32,
          strength: 0.16,
          wash: 0.05,
        );
        await check('glow-touch');
        pose.glow = const LiquidGlassGlow(
          center: Offset(104, 36),
          reach: 64,
          strength: 0.08,
        );
        await check('glow-fading');
        pose.glow = LiquidGlassGlow.none;
        await check('glow-cleared');
        pose.style = const LiquidGlassStyle(
          tint: Colors.transparent,
          blur: 2.5,
          hairline: 0.35,
          rim: 0.6,
          endShade: 0.045,
          dispersion: 0.35,
          saturation: 0.8,
          contrast: 0.12,
          rimBlur: 0.5,
          rimTail: 0.2,
          rimFocus: 0.4,
          rimAdded: 0.1,
        );
        await check('style-fields');
        pose.rim = const LiquidGlassRim(
          side: 0.25,
          corner: 0.15,
          flat: 0.09,
          shine: 0.16,
        );
        await check('rim-added');
        pose.rim = pose.rim!.scale(0.5);
        await check('rim-changed');
        pose.size = const Size(168, 60);
        pose.shape = LiquidGlassCorners(
          _corners.copyWith(topLeft: const Radius.circular(18)),
        );
        await check('size-corner-change');
        pose.rim = null;
        pose.shape = LiquidGlassOutline(
          Path()
            ..moveTo(0, 30)
            ..cubicTo(10, 0, 116, 0, 168, 22)
            ..cubicTo(158, 55, 40, 70, 0, 30)
            ..close(),
        );
        await check('outline-profile');
        pose.shape = LiquidGlassOutline(
          Path()
            ..moveTo(0, 30)
            ..cubicTo(16, -4, 130, 8, 168, 28)
            ..cubicTo(140, 64, 30, 56, 0, 30)
            ..close(),
        );
        await check('outline-profile-changed');
        pose.quality = LiquidGlassQuality.standard;
        await check('standard-drops-shader');
        pose.quality = LiquidGlassQuality.ultra;
        await check('ultra-recreates-shader');
        pose.shape = const LiquidGlassCorners(_corners);
        pose.translation = const Offset(400, 0);
        await check('offscreen');
        pose.background = 2;
        await check('offscreen-source-change');
        pose.translation = Offset.zero;
        await check('offscreen-return');
        pose.covered = true;
        await check('freeze-first-frame');
        pose.background = 3;
        await check('freeze-source-change', fresh: false);
        pose.translation = const Offset(400, 0);
        await check('freeze-offscreen', fresh: false);
        pose.translation = Offset.zero;
        await check('freeze-return', fresh: false);
        pose.covered = false;
        await check('thaw-first-frame');
        pose.pressed = true;
        for (var frame = 0; frame < 9; frame++) {
          final before = work();
          await pumpPose();
          await tester.pump(const Duration(milliseconds: 16));
          await save('press-$frame', before);
        }
        pose.pressed = false;
        for (var frame = 0; frame < 24; frame++) {
          final before = work();
          await pumpPose();
          await tester.pump(const Duration(milliseconds: 16));
          await save('release-$frame', before);
        }
        await check('press-cancelled-settled');
      }
      await tester.runAsync(() async {
        await File('${directory.path}/motion-summary.json').writeAsString(
          const JsonEncoder.withIndent('  ').convert({
            'motionFrames': 30,
            'uniformCount': initial['uniformCount'],
            'motionWorkIncludingSnapshots': motion,
            'motionRows': workChecks
                .map((row) => {'state': row.state, 'delta': row.delta})
                .toList(),
            'staticFrame': staticFrame,
            'evidence': directory.path,
          }),
        );
      });
      for (final pixels in pixelChecks) {
        expect(pixels.changed, 0, reason: pixels.state);
        expect(pixels.maximum, 0, reason: pixels.state);
      }
      for (final row in rows) {
        for (final phase in ['afterFrame', 'afterSnapshot']) {
          final held = row[phase]! as Map<String, int>;
          expect(
            held['cachedLookValues'],
            lessThanOrEqualTo(initial['uniformCount']! - 25),
            reason: '${row['state']} $phase',
          );
          expect(
            held['cachedProfileValues'],
            anyOf(0, 64),
            reason: '${row['state']} $phase',
          );
        }
      }
      if (Platform.environment['XCLASH_PANE_UNIFORM_MINIMAL'] != 'true') {
        Map<String, Object?> row(String state) =>
            rows.singleWhere((row) => row['state'] == state);
        for (final state in ['offscreen', 'offscreen-source-change']) {
          expect(row(state)['skippedPanes'], greaterThan(0), reason: state);
        }
        for (final state in [
          'freeze-first-frame',
          'freeze-source-change',
          'freeze-return',
        ]) {
          expect(row(state)['occludedPanes'], greaterThan(0), reason: state);
        }
        final standard =
            row('standard-drops-shader')['frameDelta']! as Map<String, int>;
        final ultra =
            row('ultra-recreates-shader')['frameDelta']! as Map<String, int>;
        expect(standard['floatWrites'], 0);
        expect(ultra['shaderCreations'], greaterThan(0));
        for (final state in [
          'standard-drops-shader',
          'offscreen',
          'offscreen-source-change',
        ]) {
          final held = row(state)['afterFrame']! as Map<String, int>;
          expect(held['cachedLookValues'], 0, reason: state);
          expect(held['cachedProfileValues'], 0, reason: state);
        }
        final restored =
            row('ultra-recreates-shader')['afterFrame']! as Map<String, int>;
        expect(restored['cachedLookValues'], initial['uniformCount']! - 25);
        expect(restored['cachedProfileValues'], 64);
      }
      expect(tester.takeException(), isNull);
      expect(initial['uniformCount'], greaterThan(0));
      expect(motion['floatWrites'], greaterThan(0));
      expect(motion['floatWrites'], lessThan(30 * initial['uniformCount']!));
      expect(motion['unchangedSamplerWrites'], 0);
      expect(staticFrame['floatWrites'], 0);
      expect(
        motion['unchangedFloatWrites'],
        0,
        reason:
            'Retained shaders must write only changed float values. '
            'All rawRGBA, PNG and JSON evidence has already been saved.',
      );
      expect(
        motion['lookComputations'],
        0,
        reason:
            'Stable size, corners, style, glow, profile and rim must reuse '
            'the pane look across XY motion. All rawRGBA, PNG and JSON '
            'evidence has already been saved.',
      );
      expect(
        motion['lookValuesPacked'],
        0,
        reason: 'Stable look values must not be repacked during XY motion.',
      );
      expect(
        motion['lookValuesCompared'],
        0,
        reason: 'Stable look values must not be rescanned during XY motion.',
      );
      await tester.pumpWidget(const SizedBox.shrink());
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );

  for (final axis in Axis.values) {
    testWidgets('real $axis scroll reuses its warm uniform look segment', (
      tester,
    ) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = _size;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      addTearDown(controller.dispose);
      final pose = _Pose();
      final base = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      final directory =
          base == null
                ? Directory.systemTemp.createTempSync('uniform-scroll-')
                : Directory('$base/pane-uniform-scroll-${axis.name}')
            ..createSync(recursive: true);
      final rows = <Map<String, Object?>>[];
      Map<String, int> work() => tester
          .renderObject<_PaneBox>(find.byKey(_pane))
          .pane
          .debugUniformWork;
      Widget app() => Directionality(
        textDirection: TextDirection.ltr,
        child: _scene(
          pose,
          _capture,
          _pane,
          scrollController: controller,
          scrollAxis: axis,
        ),
      );
      await tester.pumpWidget(app());
      if (!LiquidGlass.debugShaderReady) {
        await tester.runAsync(
          () => ui.FragmentProgram.fromAsset(
            'packages/fl_clash/shaders/liquid_glass.frag',
          ).timeout(const Duration(seconds: 30)),
        );
      }
      for (var frame = 0; frame < 4; frame++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(LiquidGlass.debugShaderReady, isTrue);
      final initial = work();
      Future<void> record(String tag, Map<String, int> before) async {
        final afterFrame = work();
        final capture = LiquidGlassHost.debugLastCapture!;
        final pixels = await _pixels(tester, _capture);
        rows.add({
          'state': tag,
          'inputs': {
            ..._inputs(pose),
            'axis': axis.name,
            'scrollOffset': controller.offset,
            'frameMicroseconds': 8333,
          },
          'frameDelta': _difference(afterFrame, before),
          'afterFrame': afterFrame,
          'snapshotDelta': _difference(work(), afterFrame),
          'capture': {
            'snapshots': capture.snapshots,
            'keptBands': capture.keptBands,
            'atlasPixels': capture.atlasPixels,
            'panes': capture.panes.length,
            'slots': [
              for (final pane in capture.panes)
                [
                  pane.slot.left,
                  pane.slot.top,
                  pane.slot.width,
                  pane.slot.height,
                  pane.place.left,
                  pane.place.top,
                  pane.scale,
                  pane.level,
                ],
            ],
          },
        });
        await tester.runAsync(() async {
          await File('${directory.path}/$tag.rgba').writeAsBytes(pixels.rgba);
          await File('${directory.path}/$tag.png').writeAsBytes(pixels.png);
          await File('${directory.path}/work.json').writeAsString(
            const JsonEncoder.withIndent('  ').convert({
              'size': [_size.width, _size.height],
              'pixelRatio': 1,
              'rows': rows,
            }),
          );
          _expectBaseline(
            pixels.rgba,
            'pane-uniform-scroll-${axis.name}/$tag.rgba',
          );
        });
      }

      await record('warm', initial);
      final beforeMotion = work();
      final gesture = await tester.startGesture(
        tester.getCenter(find.byKey(const ValueKey('uniform-scroll'))),
      );
      for (var frame = 0; frame < 10; frame++) {
        final before = work();
        final delta = frame < 6 ? -8.0 : 8.0;
        await gesture.moveBy(
          axis == Axis.vertical ? Offset(0, delta) : Offset(delta, 0),
        );
        if (frame >= 4) {
          pose.background = frame;
          await tester.pumpWidget(app());
        }
        await tester.pump(const Duration(microseconds: 8333));
        await record('drag-$frame', before);
      }
      final beforeCancel = work();
      await gesture.cancel();
      controller.jumpTo(controller.offset);
      await tester.pump(const Duration(microseconds: 8333));
      await record('cancel', beforeCancel);
      final motion = _difference(work(), beforeMotion);
      expect(
        rows.any(
          (row) =>
              ((row['inputs']! as Map<String, Object?>)['scrollOffset']!
                  as double) >
              0,
        ),
        isTrue,
        reason: 'The fixture must produce real scroll displacement.',
      );
      expect(motion['uniformPasses'], greaterThan(0));
      expect(motion['lookComputations'], 0);
      expect(motion['unchangedFloatWrites'], 0);
      expect(motion['unchangedSamplerWrites'], 0);
      expect(tester.takeException(), isNull);
      expect(motion['lookValuesPacked'], 0);
      expect(motion['lookValuesCompared'], 0);
      await tester.pumpWidget(const SizedBox.shrink());
    }, skip: !ui.ImageFilter.isShaderFilterSupported);
  }
}
