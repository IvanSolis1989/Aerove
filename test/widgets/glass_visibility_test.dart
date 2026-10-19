import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/typography.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _sceneKey = ValueKey('glass-visibility-scene');
const _style = LiquidGlassStyle(
  tint: Color(0x18FF0000),
  blur: 3,
  refraction: 4,
  bevel: 4,
  rim: 0,
);

Widget _surface() => const LiquidGlass(
  style: _style,
  clipper: ShapeBorderClipper(shape: AppShape.none),
  child: Center(
    child: GlassText('HH20', style: TextStyle(color: Colors.white)),
  ),
);

Widget _scene({
  int outside = 0,
  Offset nestedOffset = const Offset(0, 100),
  bool rounded = false,
  bool modal = false,
  Rect modalRect = const Rect.fromLTWH(0, 0, 150, 100),
  bool nested = true,
  bool glass = true,
  bool highContrast = false,
  int phase = 0,
  double angle = 0,
  bool filtered = false,
  bool transparent = false,
  bool edgeInk = false,
}) => MaterialApp(
  builder: (context, child) => MediaQuery(
    data: MediaQuery.of(context).copyWith(highContrast: highContrast),
    child: DefaultTextStyle(
      style: AppTextStyle.body.of(context),
      child: child!,
    ),
  ),
  home: RepaintBoundary(
    key: _sceneKey,
    child: LiquidGlassScope(
      enabled: glass && !highContrast,
      child: LiquidGlassHost(
        enabled: glass && !highContrast,
        wallpaperOnly: true,
        child: Stack(
          fit: StackFit.expand,
          clipBehavior: Clip.none,
          children: [
            LiquidGlassColorField(
              child: LiquidGlassColorSource(
                child: ColoredBox(
                  color: phase.isEven
                      ? const Color(0xFF204060)
                      : const Color(0xFF604020),
                ),
              ),
            ),
            Positioned(
              left: 20,
              top: 20,
              width: 120,
              height: 64,
              child: _surface(),
            ),
            for (var index = 0; index < outside; index++)
              Positioned(
                left: 400,
                top: index * 4,
                width: 96,
                height: 48,
                child: _surface(),
              ),
            if (nested)
              Positioned(
                left: 160,
                top: 20,
                width: 120,
                height: 80,
                child: Transform.rotate(
                  angle: angle,
                  child: Builder(
                    builder: (_) {
                      Widget content = Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Positioned(
                            left: nestedOffset.dx,
                            top: nestedOffset.dy,
                            width: 100,
                            height: 56,
                            child: edgeInk
                                ? const GlassInk(
                                    source: Colors.white,
                                    role: GlassInkRole.body,
                                    outset: 24,
                                    child: CustomPaint(
                                      painter: _OverflowMark(),
                                    ),
                                  )
                                : _surface(),
                          ),
                        ],
                      );
                      content = _RetainedOpacity(
                        alpha: transparent ? 0 : 255,
                        child: content,
                      );
                      if (filtered) {
                        content = ImageFiltered(
                          imageFilter: ui.ImageFilter.blur(
                            sigmaX: 4,
                            sigmaY: 4,
                          ),
                          child: content,
                        );
                      }
                      return rounded
                          ? ClipRSuperellipse(
                              borderRadius: AppRadius.xl,
                              child: content,
                            )
                          : ClipRect(child: content);
                    },
                  ),
                ),
              ),
            if (modal)
              Positioned.fromRect(
                rect: modalRect,
                child: const LiquidGlassOcclusion(
                  shape: AppShape.none,
                  child: SizedBox.expand(),
                ),
              ),
          ],
        ),
      ),
    ),
  ),
);

Future<void> _render(
  WidgetTester tester,
  Widget scene, {
  bool cull = true,
}) async {
  LiquidGlassHost.debugDisableVisibilityCulling = !cull;
  await tester.pumpWidget(scene);
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  for (var index = 0; index < 3; index++) {
    tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
    await tester.pump(const Duration(milliseconds: 16));
  }
}

Future<Uint8List> _pixels(WidgetTester tester) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_sceneKey),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage(
      pixelRatio: tester.view.devicePixelRatio,
    );
    try {
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

void _samePixels(Uint8List actual, Uint8List expected) {
  expect(actual.length, expected.length);
  var maximum = 0;
  for (var index = 0; index < actual.length; index++) {
    final delta = (actual[index] - expected[index]).abs();
    if (delta > maximum) maximum = delta;
  }
  expect(maximum, lessThanOrEqualTo(2));
}

void _record(String name, Map<String, Object?> data) {
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output == null) return;
  Directory(output).createSync(recursive: true);
  File(
    '$output/glass-visibility-$name.json',
  ).writeAsStringSync(jsonEncode(data));
}

List<Map<String, Object?>> _inkState(WidgetTester tester) => [
  for (final layer in tester.layers.whereType<LiquidGlassInkLayer>())
    {
      'offset': layer.offset.toString(),
      'paintBounds': layer.paintBounds.toString(),
      'size': layer.size.toString(),
      'frozen': layer.inkFrozen,
      'field': identityHashCode(layer.debugField?.$1),
    },
];

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    await GlassInk.prepare();
  });

  setUp(() => LiquidGlassHost.debugDisableVisibilityCulling = false);
  tearDown(() => LiquidGlassHost.debugDisableVisibilityCulling = false);

  testWidgets('retained outside masks skip moving source publications', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _render(tester, _scene(outside: 200, nested: false), cull: false);
    final reference = await _pixels(tester);
    final baseline = LiquidGlassHost.debugLastInkCapture!;
    await _render(tester, _scene(outside: 200, nested: false));
    _samePixels(await _pixels(tester), reference);
    final before = LiquidGlassHost.debugLastInkCapture!;
    expect(before.masks, ui.ImageFilter.isShaderFilterSupported ? 201 : 0);
    expect(
      before.skippedMasks,
      ui.ImageFilter.isShaderFilterSupported ? 200 : 0,
    );
    expect(LiquidGlassHost.debugLastCapture!.skippedPanes, 200);
    expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
    await _render(tester, _scene(outside: 200, nested: false, phase: 1));
    final moving = await _pixels(tester);
    final after = LiquidGlassHost.debugLastInkCapture!;
    if (ui.ImageFilter.isShaderFilterSupported) {
      expect(after.filterUpdates - before.filterUpdates, 1);
    }
    expect(moving, isNot(reference));
    await _render(
      tester,
      _scene(outside: 200, nested: false, phase: 1),
      cull: false,
    );
    _samePixels(await _pixels(tester), moving);
    _record('retained', {
      'masks': after.masks,
      'baselineSkipped': baseline.skippedMasks,
      'skipped': after.skippedMasks,
      'visibleFilterUpdates': after.filterUpdates - before.filterUpdates,
      'unculledFilterUpdates':
          LiquidGlassHost.debugLastInkCapture!.filterUpdates -
          after.filterUpdates,
    });
    if (ui.ImageFilter.isShaderFilterSupported) {
      expect(
        LiquidGlassHost.debugLastInkCapture!.filterUpdates -
            after.filterUpdates,
        200,
      );
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final rounded in [false, true]) {
    testWidgets(
      'nested clip admits partial and returning glass rounded=$rounded',
      (tester) async {
        tester.view.physicalSize = const Size(320, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        await _render(tester, _scene(rounded: rounded), cull: false);
        final hiddenReference = await _pixels(tester);
        final fullPixels = LiquidGlassHost.debugLastCapture!.atlasPixels;
        await _render(tester, _scene(rounded: rounded));
        _samePixels(await _pixels(tester), hiddenReference);
        final hidden = LiquidGlassHost.debugLastCapture!;
        expect(hidden.skippedPanes, 1);
        expect(hidden.panes, hasLength(1));
        expect(hidden.atlasPixels, lessThan(fullPixels));
        expect(
          LiquidGlassHost.debugLastInkCapture!.skippedMasks,
          ui.ImageFilter.isShaderFilterSupported ? 1 : 0,
        );
        for (final offset in [
          const Offset(0, -20),
          const Offset(0, 8),
          const Offset(0, 100),
          const Offset(0, 8),
        ]) {
          final scene = _scene(
            rounded: rounded,
            nestedOffset: offset,
            phase: 1,
            angle: 0.12,
          );
          await _render(tester, scene, cull: false);
          final expected = await _pixels(tester);
          await _render(tester, scene);
          _samePixels(await _pixels(tester), expected);
          if (offset.dy < 80) {
            expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(2));
            expect(LiquidGlassHost.debugLastInkCapture!.skippedMasks, 0);
          }
        }
        _record('nested-$rounded', {
          'unculledAtlasPixels': fullPixels,
          'culledAtlasPixels': hidden.atlasPixels,
          'skipped': hidden.skippedPanes,
        });
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets('modal freezing survives visibility skips and source changes', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _render(tester, _scene(modal: true));
    final frozen = await _pixels(tester);
    final frozenState = _inkState(tester);
    final masks = LiquidGlassHost.debugLastInkCapture!.frozenMasks;
    expect(masks, ui.ImageFilter.isShaderFilterSupported ? 1 : 0);
    expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
    await _render(tester, _scene(modal: true, phase: 1));
    final moved = await _pixels(tester);
    _record('freeze-source', {
      'beforeInk': frozenState,
      'afterInk': _inkState(tester),
      'beforeBody': frozen.sublist(
        (30 * 320 + 30) * 4,
        (30 * 320 + 30) * 4 + 4,
      ),
      'afterBody': moved.sublist((30 * 320 + 30) * 4, (30 * 320 + 30) * 4 + 4),
    });
    for (var y = 24; y < 72; y++) {
      for (var x = 24; x < 136; x++) {
        final index = (y * 320 + x) * 4;
        expect(
          moved.sublist(index, index + 4),
          frozen.sublist(index, index + 4),
        );
      }
    }
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, masks);
    await _render(tester, _scene(phase: 1));
    expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 0);
    expect(await _pixels(tester), isNot(frozen));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'zero opacity skips retained layers and image filters remain conservative',
    (tester) async {
      tester.view.physicalSize = const Size(960, 720);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      for (final scene in [
        _scene(nestedOffset: const Offset(0, 8), transparent: true),
        _scene(nestedOffset: const Offset(0, 82), filtered: true),
      ]) {
        await _render(tester, scene, cull: false);
        final reference = await _pixels(tester);
        await _render(tester, scene);
        _samePixels(await _pixels(tester), reference);
      }
      expect(
        LiquidGlassHost.debugLastCapture!.skippedPanes,
        0,
        reason: 'an ancestor filter may expand a pane into its clip',
      );
      await _render(
        tester,
        _scene(nestedOffset: const Offset(0, 8), transparent: true),
      );
      expect(LiquidGlassHost.debugLastCapture!.skippedPanes, 1);
      await _render(tester, _scene(nestedOffset: const Offset(0, 8)));
      expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(2));
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('None and high contrast keep their original pixels', (
    tester,
  ) async {
    for (final scene in [_scene(glass: false), _scene(highContrast: true)]) {
      await _render(tester, scene, cull: false);
      final reference = await _pixels(tester);
      await _render(tester, scene);
      _samePixels(await _pixels(tester), reference);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a frozen target returns from its clip before thawing', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    const cover = Rect.fromLTWH(150, 0, 160, 120);
    const visible = Offset(0, 8);
    final frozenMasks = ui.ImageFilter.isShaderFilterSupported ? 1 : 0;
    await _render(tester, _scene(nestedOffset: visible));
    await _render(
      tester,
      _scene(nestedOffset: visible, modal: true, modalRect: cover),
    );
    final frozen = await _pixels(tester);
    final frozenState = _inkState(tester);
    expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, frozenMasks);
    await _render(tester, _scene(modal: true, modalRect: cover));
    expect(LiquidGlassHost.debugLastCapture!.skippedPanes, 1);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, frozenMasks);
    await _render(tester, _scene(modal: true, modalRect: cover, phase: 1));
    expect(LiquidGlassHost.debugLastCapture!.skippedPanes, 1);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, frozenMasks);
    await _render(
      tester,
      _scene(nestedOffset: visible, modal: true, modalRect: cover, phase: 1),
    );
    final returned = await _pixels(tester);
    expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, frozenMasks);
    var maximum = 0;
    var worst = 0;
    for (var y = 32; y < 80; y++) {
      for (var x = 164; x < 256; x++) {
        final index = (y * 320 + x) * 4;
        for (var channel = 0; channel < 4; channel++) {
          final delta = (returned[index + channel] - frozen[index + channel])
              .abs();
          if (delta > maximum) {
            maximum = delta;
            worst = index + channel;
          }
        }
      }
    }
    _record('frozen-reentry-before-assert', {
      'beforeInk': frozenState,
      'afterInk': _inkState(tester),
      'maximum': maximum,
      'x': worst ~/ 4 % 320,
      'y': worst ~/ 4 ~/ 320,
      'beforeBody': frozen.sublist(
        (40 * 320 + 170) * 4,
        (40 * 320 + 170) * 4 + 4,
      ),
      'afterBody': returned.sublist(
        (40 * 320 + 170) * 4,
        (40 * 320 + 170) * 4 + 4,
      ),
    });
    expect(
      maximum,
      lessThanOrEqualTo(2),
      reason: 'the returned target must retain its frozen appearance',
    );
    await _render(tester, _scene(nestedOffset: visible, phase: 1));
    final thawed = await _pixels(tester);
    expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 0);
    final sample = (40 * 320 + 170) * 4;
    expect((thawed[sample] - frozen[sample]).abs(), greaterThan(20));
    await _render(tester, _scene(nestedOffset: visible, phase: 1), cull: false);
    _samePixels(await _pixels(tester), thawed);
    _record('frozen-reentry', {
      'maximumFrozenChannelDifference': maximum,
      'tolerance': 2,
      'thawedSampleDifference': (thawed[sample] - frozen[sample]).abs(),
    });
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('negative Ink paint overhang remains active at a clip edge', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final scene = _scene(nestedOffset: const Offset(128, 8), edgeInk: true);
    await _render(tester, scene, cull: false);
    final reference = await _pixels(tester);
    expect(
      reference[(36 * 320 + 277) * 4],
      greaterThan(100),
      reason: 'the overhang must actually paint inside the clip',
    );
    await _render(tester, scene);
    _samePixels(await _pixels(tester), reference);
    expect(LiquidGlassHost.debugLastInkCapture!.skippedMasks, 0);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class _OverflowMark extends CustomPainter {
  const _OverflowMark();

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
      const Rect.fromLTWH(-12, 4, 8, 12),
      Paint()..color = Colors.white,
    );
  }

  @override
  bool shouldRepaint(_OverflowMark oldDelegate) => false;
}

class _RetainedOpacity extends SingleChildRenderObjectWidget {
  const _RetainedOpacity({required this.alpha, required super.child});

  final int alpha;

  @override
  RenderObject createRenderObject(BuildContext context) => _OpacityBox(alpha);

  @override
  void updateRenderObject(BuildContext context, _OpacityBox renderObject) {
    renderObject
      ..alpha = alpha
      ..markNeedsPaint();
  }
}

class _OpacityBox extends RenderProxyBox {
  _OpacityBox(this.alpha);

  int alpha;
  final _layer = LayerHandle<OpacityLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer.layer ??= OpacityLayer();
    layer
      ..alpha = alpha
      ..offset = offset;
    context.pushLayer(layer, super.paint, Offset.zero);
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}
