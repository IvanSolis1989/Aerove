import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _capture = ValueKey('glass-finish-quality');
const _size = Size(320, 224);
const _pixelRatio = 3.0;
const _surfaces = [
  (name: 'header', rect: Rect.fromLTWH(40, 20, 120, 48), body: false),
  (name: 'menu', rect: Rect.fromLTWH(40, 108, 200, 72), body: true),
];

class _Backdrop extends CustomPainter {
  const _Backdrop(this.dark);

  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final colors = dark
        ? [const Color(0xFF607080), const Color(0xFF806850)]
        : [const Color(0xFFD6E0E8), const Color(0xFFE8D8C0)];
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()..color = colors[(x ~/ 8) % 2],
      );
    }
  }

  @override
  bool shouldRepaint(_Backdrop oldDelegate) => dark != oldDelegate.dark;
}

class _DirectPane extends LeafRenderObjectWidget {
  const _DirectPane({required this.style, required this.rim});

  final LiquidGlassStyle style;
  final LiquidGlassRim? rim;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _DirectPaneBox(style, rim);

  @override
  void updateRenderObject(BuildContext context, _DirectPaneBox renderObject) {
    renderObject
      ..style = style
      ..rim = rim
      ..markNeedsPaint();
  }
}

class _DirectPaneBox extends RenderBox {
  _DirectPaneBox(this.style, this.rim);

  LiquidGlassStyle style;
  LiquidGlassRim? rim;
  int paintCount = 0;
  late final _pane = LiquidGlassPane(markNeedsPaint);

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  bool get sizedByParent => true;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  @override
  void paint(PaintingContext context, Offset offset) {
    paintCount++;
    final rect = offset & size;
    _pane.paint(
      context,
      rect,
      shape: const LiquidGlassCorners(AppRadius.md),
      style: style,
      rim: rim,
    );
    _pane.paintRim(context, rect, AppCorner.md, rim);
  }

  @override
  void dispose() {
    _pane.dispose();
    super.dispose();
  }
}

Widget _scene({
  required ValueNotifier<LiquidGlassQuality> quality,
  required bool dark,
  required bool direct,
  required bool withRim,
  required bool replay,
}) {
  final scheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: dark ? Brightness.dark : Brightness.light,
  );
  final rim = withRim ? LiquidGlassRim.menu(scheme) : null;
  Widget surface(bool body) {
    final style = body
        ? LiquidGlassStyle.menu(scheme)
        : LiquidGlassStyle.regular(scheme);
    final pane = direct
        ? _DirectPane(style: style, rim: rim)
        : LiquidGlass(
            style: style,
            rim: rim,
            clipper: const ShapeBorderClipper(shape: AppShape.md),
          );
    return RepaintBoundary(child: body ? LiquidGlassBody(child: pane) : pane);
  }

  return MaterialApp(
    theme: ThemeData(colorScheme: scheme),
    home: RepaintBoundary(
      key: _capture,
      child: ValueListenableBuilder<LiquidGlassQuality>(
        valueListenable: quality,
        builder: (context, quality, child) => LiquidGlassScope(
          enabled: true,
          foregroundEnabled: false,
          quality: quality,
          child: LiquidGlassHost(
            enabled: true,
            wallpaperOnly: true,
            child: child!,
          ),
        ),
        child: Stack(
          fit: StackFit.expand,
          children: [
            LiquidGlassColorField(
              child: LiquidGlassColorSource(
                child: CustomPaint(painter: _Backdrop(dark)),
              ),
            ),
            for (final surfaceInfo in _surfaces)
              Positioned.fromRect(
                rect: surfaceInfo.rect,
                child: surface(surfaceInfo.body),
              ),
            if (replay)
              Positioned.fill(
                child: LiquidGlass(
                  contentBackdrop: true,
                  style: const LiquidGlassStyle(
                    tint: Colors.transparent,
                    blur: 0,
                    refraction: 0,
                    bevel: 0,
                    rim: 0,
                  ),
                  clipper: const ShapeBorderClipper(shape: AppShape.none),
                ),
              ),
          ],
        ),
      ),
    ),
  );
}

Future<Uint8List> _pixels(WidgetTester tester) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_capture),
  );
  return (await tester.runAsync(
    () async => _imagePixels(await boundary.toImage(pixelRatio: _pixelRatio)),
  ))!;
}

Future<Uint8List> _imagePixels(ui.Image image) async {
  try {
    expect(image.width, (_size.width * _pixelRatio).round());
    expect(image.height, (_size.height * _pixelRatio).round());
    final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
    return Uint8List.fromList(
      bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
    );
  } finally {
    image.dispose();
  }
}

// RenderRepaintBoundary.toImage composes again; capture this exact scene instead.
Future<Uint8List> _composePixels(WidgetTester tester) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_capture),
  );
  final layer = boundary.debugLayer! as OffsetLayer;
  return (await tester.runAsync(() async {
    final transform = Matrix4.diagonal3Values(_pixelRatio, _pixelRatio, 1)
      ..translateByDouble(-layer.offset.dx, -layer.offset.dy, 0, 1);
    final builder = ui.SceneBuilder()..pushTransform(transform.storage);
    final scene = layer.buildScene(builder);
    try {
      return await _imagePixels(
        await scene.toImage(
          (_size.width * _pixelRatio).round(),
          (_size.height * _pixelRatio).round(),
        ),
      );
    } finally {
      scene.dispose();
    }
  }))!;
}

Future<void> _settle(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  for (var frame = 0; frame < 4; frame++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

({int maximum, int changedPixels}) _difference(
  Uint8List actual,
  Uint8List reference,
  Rect pane, {
  bool outsideOnly = false,
}) {
  final region = pane.inflate(2);
  final width = (_size.width * _pixelRatio).round();
  final left = (region.left * _pixelRatio).floor();
  final right = (region.right * _pixelRatio).ceil();
  final top = (region.top * _pixelRatio).floor();
  final bottom = (region.bottom * _pixelRatio).ceil();
  var maximum = 0;
  var changedPixels = 0;
  for (var y = top; y < bottom; y++) {
    for (var x = left; x < right; x++) {
      final point = Offset((x + 0.5) / _pixelRatio, (y + 0.5) / _pixelRatio);
      if (outsideOnly && pane.contains(point)) continue;
      var pixelDelta = 0;
      for (var channel = 0; channel < 4; channel++) {
        final index = (y * width + x) * 4 + channel;
        final delta = (actual[index] - reference[index]).abs();
        if (delta > maximum) maximum = delta;
        if (delta > pixelDelta) pixelDelta = delta;
      }
      if (pixelDelta > 8) changedPixels++;
    }
  }
  return (maximum: maximum, changedPixels: changedPixels);
}

void main() {
  setUpAll(() async {
    await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
  });

  for (final dark in [false, true]) {
    for (final direct in [false, true]) {
      for (final replay in [false, true]) {
        testWidgets(
          'finish rims follow quality on post-pump and settled snapshots '
          'dark=$dark direct=$direct replay=$replay',
          (tester) async {
            tester.view.devicePixelRatio = _pixelRatio;
            tester.view.physicalSize = _size * _pixelRatio;
            addTearDown(tester.view.reset);
            final quality = ValueNotifier(LiquidGlassQuality.standard);
            addTearDown(quality.dispose);
            Widget scene({required bool withRim}) => _scene(
              quality: quality,
              dark: dark,
              direct: direct,
              withRim: withRim,
              replay: replay,
            );

            final references = <LiquidGlassQuality, Uint8List>{};
            await tester.pumpWidget(scene(withRim: false));
            await _settle(tester);
            for (final finish in LiquidGlassQuality.values) {
              quality.value = finish;
              await tester.pump();
              await _settle(tester);
              references[finish] = await _pixels(tester);
            }
            await tester.pumpWidget(const SizedBox.shrink());
            quality.value = LiquidGlassQuality.standard;
            await tester.pumpWidget(scene(withRim: true));
            await _settle(tester);

            void check(Uint8List actual, String phase) {
              final finish = quality.value;
              for (final surface in _surfaces) {
                final delta = _difference(
                  actual,
                  references[finish]!,
                  surface.rect,
                  outsideOnly: finish == LiquidGlassQuality.ultra,
                );
                final reason = '${surface.name} $finish $phase: $delta';
                if (finish == LiquidGlassQuality.standard) {
                  expect(delta.maximum, lessThanOrEqualTo(1), reason: reason);
                } else {
                  expect(delta.maximum, greaterThan(8), reason: reason);
                  expect(delta.changedPixels, greaterThan(20), reason: reason);
                }
              }
            }

            check(await _pixels(tester), 'initial');
            for (final finish in [
              LiquidGlassQuality.ultra,
              LiquidGlassQuality.standard,
              LiquidGlassQuality.ultra,
              LiquidGlassQuality.standard,
            ]) {
              quality.value = finish;
              await tester.pump();
              check(await _pixels(tester), 'post-pump snapshot');
              await _settle(tester);
              check(await _pixels(tester), 'settled snapshot');
            }
            await tester.pumpWidget(const SizedBox.shrink());
            expect(tester.takeException(), isNull);
          },
        );
      }
    }

    testWidgets(
      'a disabled rim never reuses its ultra engine layer on retained '
      'compositions dark=$dark',
      (tester) async {
        tester.view.devicePixelRatio = _pixelRatio;
        tester.view.physicalSize = _size * _pixelRatio;
        addTearDown(tester.view.reset);
        final quality = ValueNotifier(LiquidGlassQuality.standard);
        addTearDown(quality.dispose);
        Widget scene({required bool withRim}) => _scene(
          quality: quality,
          dark: dark,
          direct: true,
          withRim: withRim,
          replay: false,
        );

        await tester.pumpWidget(scene(withRim: false));
        await _settle(tester);
        final standardReference = await _pixels(tester);
        quality.value = LiquidGlassQuality.ultra;
        await tester.pump();
        await _settle(tester);
        final ultraReference = await _pixels(tester);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpWidget(scene(withRim: true));
        await _settle(tester);
        final panes = [
          for (final element in find.byType(_DirectPane).evaluate())
            element.renderObject! as _DirectPaneBox,
        ];
        expect(panes, hasLength(_surfaces.length));
        final paintCounts = [for (final pane in panes) pane.paintCount];

        for (var cycle = 0; cycle < 2; cycle++) {
          quality.value = LiquidGlassQuality.ultra;
          await tester.pump(Duration.zero, EnginePhase.paint);
          final ultra = await _composePixels(tester);
          for (final surface in _surfaces) {
            final delta = _difference(
              ultra,
              ultraReference,
              surface.rect,
              outsideOnly: true,
            );
            expect(
              delta.changedPixels,
              greaterThan(20),
              reason: '${surface.name} cycle=$cycle must first cache a rim',
            );
          }

          quality.value = LiquidGlassQuality.standard;
          await tester.pump(Duration.zero, EnginePhase.paint);
          for (var composition = 0; composition < 3; composition++) {
            final actual = await _composePixels(tester);
            for (final surface in _surfaces) {
              final delta = _difference(
                actual,
                standardReference,
                surface.rect,
              );
              expect(
                delta.maximum,
                lessThanOrEqualTo(1),
                reason:
                    '${surface.name} cycle=$cycle '
                    '${composition == 0 ? 'first' : 'retained $composition'} '
                    'composition must stay rimless: $delta',
              );
            }
          }
          expect(
            [for (final pane in panes) pane.paintCount],
            paintCounts,
            reason: 'Quality changes must exercise the same retained pictures',
          );
        }
        await tester.pumpWidget(const SizedBox.shrink());
        expect(tester.takeException(), isNull);
      },
    );
  }
}
