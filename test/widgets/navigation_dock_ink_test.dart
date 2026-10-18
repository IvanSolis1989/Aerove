import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/navigation_dock.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _scene = ValueKey('dockInkScene');
const _emptyGlyph = Glyph([]);
const _labels = ['Home', 'Profiles with a very long label', 'Tools'];
const _glyphs = [AppGlyphs.dashboard, AppGlyphs.profiles, AppGlyphs.tools];

class _Fixture extends StatefulWidget {
  const _Fixture({super.key, this.glass = true});

  final bool glass;

  @override
  State<_Fixture> createState() => _FixtureState();
}

class _FixtureState extends State<_Fixture> {
  int selected = 0;
  bool hideGlyphs = false;

  void select(int value) => setState(() => selected = value);

  void hide(bool value) => setState(() => hideGlyphs = value);

  @override
  Widget build(BuildContext context) {
    return TestApp(
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      child: RepaintBoundary(
        key: _scene,
        child: LiquidGlassScope(
          enabled: widget.glass,
          quality: LiquidGlassQuality.ultra,
          child: LiquidGlassHost(
            enabled: widget.glass,
            child: Stack(
              fit: StackFit.expand,
              children: [
                const Positioned.fill(
                  child: LiquidGlassColorField(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [Color(0xFFDBEAF2), Color(0xFFF1DACE)],
                        ),
                      ),
                    ),
                  ),
                ),
                Align(
                  alignment: Alignment.bottomCenter,
                  child: NavigationDock(
                    destinations: [
                      for (var index = 0; index < _labels.length; index++)
                        NavigationDockDestination(
                          glyph: hideGlyphs ? _emptyGlyph : _glyphs[index],
                          label: _labels[index],
                        ),
                    ],
                    selectedIndex: selected,
                    onSelected: select,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

Finder _glyph(int index) => find.byWidgetPredicate(
  (widget) => widget is GlyphIcon && identical(widget.glyph, _glyphs[index]),
);

Future<Uint8List> _pixels(WidgetTester tester) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      return Uint8List.fromList(bytes.buffer.asUint8List());
    } finally {
      image.dispose();
    }
  }))!;
}

List<LiquidGlassInkLayer> _glyphLayers(WidgetTester tester) {
  final layers = <LiquidGlassInkLayer>[];
  void visit(ContainerLayer parent) {
    for (Layer? layer = parent.firstChild; layer != null; layer = layer.nextSibling) {
      if (layer is LiquidGlassInkLayer && layer.size == const Size(28, 28)) {
        layers.add(layer);
      }
      if (layer is ContainerLayer) visit(layer);
    }
  }

  visit(tester.binding.renderViews.first.debugLayer!);
  return layers;
}

Future<void> _pump(WidgetTester tester, GlobalKey<_FixtureState> key, {
  bool glass = true,
}) async {
  tester.view.physicalSize = const Size(400, 220);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_Fixture(key: key, glass: glass));
  await tester.pumpAndSettle();
}

Future<int> _checkPartialGlyphs(
  WidgetTester tester,
  _FixtureState state,
  int frame,
) async {
  final lens = tester.getRect(find.descendant(
    of: find.byType(FloatingNavigationBar),
    matching: find.byType(PositionedDirectional),
  ));
  final regions = <Rect>[];
  for (var index = 0; index < _glyphs.length; index++) {
    final rect = tester.getRect(_glyph(index).first).deflate(6);
    if ((lens.left - rect.center.dx).abs() < 4 ||
        (lens.right - rect.center.dx).abs() < 4) {
      regions.add(rect);
    }
  }
  if (regions.isEmpty) return 0;
  final painted = await _pixels(tester);
  state.hide(true);
  await tester.pump();
  final empty = await _pixels(tester);
  state.hide(false);
  await tester.pump();
  for (final rect in regions) {
    var inside = 0;
    var outside = 0;
    for (var y = rect.top.ceil(); y < rect.bottom.floor(); y++) {
      for (var x = rect.left.ceil(); x < rect.right.floor(); x++) {
        final point = Offset(x + 0.5, y + 0.5);
        if ((point.dx - lens.left).abs() < 2 ||
            (point.dx - lens.right).abs() < 2) continue;
        final offset = (y * 400 + x) * 4;
        var delta = 0;
        for (var channel = 0; channel < 3; channel++) {
          delta += (painted[offset + channel] - empty[offset + channel]).abs();
        }
        if (delta > 24) {
          if (lens.contains(point)) {
            inside++;
          } else {
            outside++;
          }
        }
      }
    }
    expect(inside, greaterThan(2), reason: 'inside glyph missing at frame $frame');
    expect(outside, greaterThan(2), reason: 'outside glyph missing at frame $frame');
  }
  return regions.length;
}

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  testWidgets('glass resting glyph copies have independent ink layers', (tester) async {
    final key = GlobalKey<_FixtureState>();
    await _pump(tester, key);
    final layers = _glyphLayers(tester);
    expect(layers, hasLength(4));
    expect(layers.toSet(), hasLength(4));
    for (var index = 0; index < _glyphs.length; index++) {
      expect(_glyph(index), findsNWidgets(2));
    }
    expect(tester.takeException(), isNull);
  }, skip: !ui.ImageFilter.isShaderFilterSupported);

  for (final pointer in [false, true]) {
    testWidgets('Ultra glyph pixels survive ${pointer ? 'tap lift and settle' : 'external selection travel'}', (tester) async {
      final key = GlobalKey<_FixtureState>();
      await _pump(tester, key);
      var checked = 0;
      final target = tester.getCenter(_glyph(1).first);
      final gesture = pointer ? await tester.startGesture(target) : null;
      if (!pointer) key.currentState!.select(2);
      for (var frame = 0; frame < 96; frame++) {
        if (frame == 4 && gesture != null) await gesture.up();
        await tester.pump(const Duration(milliseconds: 8));
        checked += await _checkPartialGlyphs(tester, key.currentState!, frame);
      }
      expect(checked, greaterThan(0), reason: 'lens must cross actual glyph pixels');
      await tester.pumpAndSettle();
      expect(key.currentState!.selected, pointer ? 1 : 2);
      expect(_glyphLayers(tester).toSet(), hasLength(4));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    }, skip: !ui.ImageFilter.isShaderFilterSupported);
  }

  testWidgets('None keeps one semantic destination and keyboard activation', (tester) async {
    final semantics = tester.ensureSemantics();
    try {
      final key = GlobalKey<_FixtureState>();
      await _pump(tester, key, glass: false);
      expect(_glyphLayers(tester), isEmpty);
      for (var index = 0; index < _labels.length; index++) {
        expect(find.bySemanticsLabel(_labels[index]), findsOneWidget);
        expect(_glyph(index), findsOneWidget);
      }
      Actions.invoke(tester.element(_glyph(2)), const ActivateIntent());
      await tester.pumpAndSettle();
      expect(key.currentState!.selected, 2);
      expect(_glyphLayers(tester), isEmpty);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      semantics.dispose();
    }
  });
}
