import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _size = Size(320, 224);
const _pixelRatio = 3.0;
const _corners = BorderRadius.only(
  topLeft: Radius.circular(8),
  topRight: Radius.circular(14),
  bottomRight: Radius.circular(20),
  bottomLeft: Radius.circular(26),
);

typedef _Pose = ({
  Size size,
  BorderRadius corners,
  Offset translation,
  int background,
  LiquidGlassGlow glow,
  double endShade,
});

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
  }

  @override
  bool shouldRepaint(_Backdrop oldDelegate) => phase != oldDelegate.phase;
}

class _Pane extends LeafRenderObjectWidget {
  const _Pane({
    super.key,
    required this.corners,
    required this.glow,
    required this.endShade,
  });

  final BorderRadius corners;
  final LiquidGlassGlow glow;
  final double endShade;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _PaneBox(corners, glow, endShade);

  @override
  void updateRenderObject(BuildContext context, _PaneBox renderObject) {
    renderObject
      ..corners = corners
      ..glow = glow
      ..endShade = endShade
      ..markNeedsPaint();
  }
}

class _PaneBox extends RenderBox {
  _PaneBox(this.corners, this.glow, this.endShade);

  BorderRadius corners;
  LiquidGlassGlow glow;
  double endShade;
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
      shape: LiquidGlassCorners(corners),
      style: LiquidGlassStyle(
        tint: Colors.transparent,
        blur: 2.5,
        hairline: 0.35,
        rim: 0.6,
        endShade: endShade,
      ),
      glow: glow,
    );
  }

  @override
  void dispose() {
    pane.dispose();
    super.dispose();
  }
}

Widget _scene(_Pose pose, Key captureKey, Key paneKey) => SizedBox.fromSize(
  size: _size,
  child: RepaintBoundary(
    key: captureKey,
    child: LiquidGlassScope(
      enabled: true,
      foregroundEnabled: false,
      quality: LiquidGlassQuality.ultra,
      child: LiquidGlassHost(
        enabled: true,
        child: Stack(
          fit: StackFit.expand,
          children: [
            CustomPaint(painter: _Backdrop(pose.background)),
            Positioned.fromRect(
              rect: (const Offset(40, 55) + pose.translation) & pose.size,
              child: _Pane(
                key: paneKey,
                corners: pose.corners,
                glow: pose.glow,
                endShade: pose.endShade,
              ),
            ),
          ],
        ),
      ),
    ),
  ),
);

Future<Uint8List> _pixels(WidgetTester tester, Key key) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(key));
  final layer = boundary.debugLayer! as OffsetLayer;
  return (await tester.runAsync(() async {
    final transform = Matrix4.diagonal3Values(_pixelRatio, _pixelRatio, 1)
      ..translateByDouble(-layer.offset.dx, -layer.offset.dy, 0, 1);
    final builder = ui.SceneBuilder()..pushTransform(transform.storage);
    final scene = layer.buildScene(builder);
    try {
      final image = await scene.toImage(
        (_size.width * _pixelRatio).round(),
        (_size.height * _pixelRatio).round(),
      );
      try {
        final bytes = (await image.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!;
        return Uint8List.fromList(
          bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
        );
      } finally {
        image.dispose();
      }
    } finally {
      scene.dispose();
    }
  }))!;
}

void main() {
  setUpAll(() {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
  });

  testWidgets(
    'corner geometry follows size and corners while scroll, atlas and glow '
    'match a fresh pane',
    (tester) async {
      tester.view.devicePixelRatio = _pixelRatio;
      tester.view.physicalSize =
          Size(_size.width * 2, _size.height) * _pixelRatio;
      addTearDown(tester.view.reset);
      final unused = LiquidGlassPane(() {});
      expect(unused.debugCornerCurveComputations, 0);
      unused.dispose();
      const capture = ValueKey('persistent-capture');
      const reference = ValueKey('fresh-capture');
      const persistent = ValueKey('persistent-pane');
      var pose = (
        size: const Size(180, 80),
        corners: _corners,
        translation: Offset.zero,
        background: 0,
        glow: LiquidGlassGlow.none,
        endShade: 0.0,
      );
      final counts = <({String state, int actual, int expected})>[];

      void update({
        Size? size,
        BorderRadius? corners,
        Offset? translation,
        int? background,
        LiquidGlassGlow? glow,
        double? endShade,
      }) {
        pose = (
          size: size ?? pose.size,
          corners: corners ?? pose.corners,
          translation: translation ?? pose.translation,
          background: background ?? pose.background,
          glow: glow ?? pose.glow,
          endShade: endShade ?? pose.endShade,
        );
      }

      Future<void> pumpPose() async {
        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.ltr,
            child: Row(
              children: [
                _scene(pose, capture, persistent),
                _scene(pose, reference, UniqueKey()),
              ],
            ),
          ),
        );
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
      }

      int computations() => tester
          .renderObject<_PaneBox>(find.byKey(persistent))
          .pane
          .debugCornerCurveComputations;

      Future<Uint8List> check(String state, int count) async {
        final actual = await _pixels(tester, capture);
        final fresh = await _pixels(tester, reference);
        final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (directory != null) {
          final name = state.replaceAll(RegExp('[^a-zA-Z0-9]+'), '-');
          await tester.runAsync(() async {
            await Directory(directory).create(recursive: true);
            await File(
              '$directory/geometry-$name-retained.rgba',
            ).writeAsBytes(actual);
            await File(
              '$directory/geometry-$name-fresh.rgba',
            ).writeAsBytes(fresh);
          });
        }
        expect(actual, orderedEquals(fresh), reason: state);
        counts.add((state: state, actual: computations(), expected: count));
        expect(tester.takeException(), isNull);
        return actual;
      }

      await pumpPose();
      await check('initial asymmetric corners', 1);
      update(size: const Size(220, 80));
      await pumpPose();
      await check('width changes', 2);
      update(size: const Size(220, 40));
      await pumpPose();
      await check('height changes and clamps the largest corner', 3);
      update(corners: _corners.copyWith(topLeft: const Radius.circular(12)));
      await pumpPose();
      await check('one corner changes', 4);
      for (var frame = 1; frame <= 12; frame++) {
        update(translation: Offset(0, frame.toDouble()));
        await pumpPose();
        counts.add((
          state: 'scroll frame $frame',
          actual: computations(),
          expected: 4,
        ));
      }
      final beforeAtlas = await check('translated pane', 4);
      update(background: 1);
      await pumpPose();
      final withoutGlow = await check('new atlas with unchanged geometry', 4);
      expect(withoutGlow, isNot(orderedEquals(beforeAtlas)));
      update(
        glow: const LiquidGlassGlow(
          center: Offset(25, 15),
          reach: 20,
          strength: 0.12,
        ),
      );
      await pumpPose();
      final glowing = await check('nonzero glow', 4);
      expect(glowing, isNot(orderedEquals(withoutGlow)));
      update(
        glow: const LiquidGlassGlow(
          center: Offset(90, 20),
          reach: 20,
          strength: 0.12,
        ),
      );
      await pumpPose();
      final movedGlow = await check('glow center changes', 4);
      expect(movedGlow, isNot(orderedEquals(glowing)));
      update(glow: LiquidGlassGlow.none);
      await pumpPose();
      expect(await check('glow clears', 4), orderedEquals(withoutGlow));
      update(endShade: 0.045);
      await pumpPose();
      final shaded = await check('nonzero end shade', 4);
      expect(shaded, isNot(orderedEquals(withoutGlow)));
      update(endShade: 0);
      await pumpPose();
      expect(await check('end shade clears', 4), orderedEquals(withoutGlow));
      for (final count in counts) {
        expect(count.actual, count.expected, reason: count.state);
      }
      await tester.pumpWidget(const SizedBox.shrink());
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );
}
