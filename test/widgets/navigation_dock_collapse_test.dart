import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/navigation_dock.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _scene = ValueKey('collapseScene');
const _labels = ['Home', 'Profiles with a very long label', 'Tools'];
const _glyphs = [AppGlyphs.dashboard, AppGlyphs.profiles, AppGlyphs.tools];

class _Fixture extends StatefulWidget {
  const _Fixture({
    super.key,
    required this.glass,
    required this.quality,
    this.direction = TextDirection.ltr,
  });

  final bool glass;
  final LiquidGlassQuality quality;
  final TextDirection direction;

  @override
  State<_Fixture> createState() => _FixtureState();
}

class _FixtureState extends State<_Fixture> {
  bool collapsed = false;
  bool reduced = false;
  bool ticking = true;
  int selected = 0;
  int builds = 0;

  void collapse(bool value) => setState(() => collapsed = value);

  void reduceMotion() => setState(() => reduced = true);

  void tick(bool value) => setState(() => ticking = value);

  @override
  Widget build(BuildContext context) {
    builds++;
    return TestApp(
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue)),
      child: Directionality(
        textDirection: widget.direction,
        child: MediaQuery(
          data: MediaQueryData.fromView(View.of(context)).copyWith(
            disableAnimations: reduced,
          ),
          child: TickerMode(
            enabled: ticking,
            child: RepaintBoundary(
              key: _scene,
              child: LiquidGlassScope(
                enabled: widget.glass,
                quality: widget.quality,
                child: LiquidGlassHost(
                  enabled: widget.glass,
                  wallpaperOnly: true,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      const Positioned.fill(
                        child: LiquidGlassColorField(
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                colors: [Color(0xFFDAEAF3), Color(0xFFF2D9C8)],
                              ),
                            ),
                          ),
                        ),
                      ),
                      Align(
                        alignment: Alignment.bottomCenter,
                        child: NavigationDock(
                          collapsed: collapsed,
                          destinations: [
                            for (var index = 0; index < _labels.length; index++)
                              NavigationDockDestination(
                                glyph: _glyphs[index],
                                label: _labels[index],
                              ),
                          ],
                          selectedIndex: selected,
                          onSelected: (index) => setState(() => selected = index),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
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

Rect _bar(WidgetTester tester) => tester.getRect(find.byType(FloatingNavigationBar));

Rect _lens(WidgetTester tester) => tester.getRect(find.descendant(
  of: find.byType(FloatingNavigationBar),
  matching: find.byType(PositionedDirectional),
));

double _labelVisibility(WidgetTester tester) {
  final label = find.text(_labels.first).first;
  var alpha = tester.widget<Text>(label).style!.color!.a;
  tester.element(label).visitAncestorElements((element) {
    if (element.widget case Opacity(:final opacity)) alpha *= opacity;
    return element.widget is! NavigationDock;
  });
  return alpha;
}

void _fixedGlyphs(WidgetTester tester) {
  for (var index = 0; index < _glyphs.length; index++) {
    expect(tester.getSize(_glyph(index).first), const Size.square(28));
    final world = tester.getRect(_glyph(index).first).size;
    expect(world.width, closeTo(28, 1e-6));
    expect(world.height, closeTo(28, 1e-6));
  }
}

Future<void> _snapshot(WidgetTester tester, String name) async {
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output == null) return;
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
  await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
      final directory = Directory(output)..createSync(recursive: true);
      File('${directory.path}/dock-collapse-$name.png').writeAsBytesSync(
        png.buffer.asUint8List(),
      );
    } finally {
      image.dispose();
    }
  });
}

Future<void> _pump(
  WidgetTester tester,
  GlobalKey<_FixtureState> key, {
  required bool glass,
  LiquidGlassQuality quality = LiquidGlassQuality.standard,
  TextDirection direction = TextDirection.ltr,
}) async {
  tester.view.physicalSize = const Size(400, 220);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_Fixture(
    key: key,
    glass: glass,
    quality: quality,
    direction: direction,
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  for (final (glass, quality, name) in [
    (false, LiquidGlassQuality.standard, 'none'),
    (true, LiquidGlassQuality.standard, 'standard'),
    (true, LiquidGlassQuality.ultra, 'ultra'),
  ]) {
    testWidgets('$name collapse keeps icons, anchor and inset, and reverses', (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        final key = GlobalKey<_FixtureState>();
        await _pump(tester, key, glass: glass, quality: quality);
        final expanded = _bar(tester);
        final expandedLens = _lens(tester);
        final reserved = tester.getSize(find.byType(NavigationDock));
        final expandedIconY = tester.getCenter(_glyph(1).first).dy;
        final expandedLabel = tester.getRect(find.text(_labels.first).first);
        final labelFont = tester.widget<Text>(find.text(_labels.first).first).style!.fontSize;
        final panes = LiquidGlassHost.debugLastCapture?.panes.length;
        expect(expanded.size, const Size(274, 62));
        expect(expandedLens.size, const Size(94, 54));
        expect(_labelVisibility(tester), 1);
        _fixedGlyphs(tester);
        await _snapshot(tester, '$name-expanded');

        key.currentState!.collapse(true);
        await tester.pump();
        final builds = key.currentState!.builds;
        await tester.pump(const Duration(milliseconds: 150));
        final middle = _bar(tester);
        expect(middle.width, inExclusiveRange(184, 274));
        expect(middle.height, inExclusiveRange(56, 62));
        expect(_labelVisibility(tester), inExclusiveRange(0, 1));
        final visibility = _labelVisibility(tester);
        final middleLabel = tester.getRect(find.text(_labels.first).first);
        expect(middleLabel.width, closeTo(expandedLabel.width * visibility, 1e-6));
        expect(middleLabel.height, closeTo(expandedLabel.height * visibility, 1e-6));
        expect(tester.widget<Text>(find.text(_labels.first).first).style!.fontSize, labelFont);
        expect(find.descendant(
          of: find.byType(NavigationDock),
          matching: find.byType(ClipRect),
        ), findsNothing);
        expect(_lens(tester).width, inExclusiveRange(64, 94));
        expect(middle.bottom, expanded.bottom);
        expect(middle.center.dx, expanded.center.dx);
        expect(key.currentState!.builds, builds);
        _fixedGlyphs(tester);
        await _snapshot(tester, '$name-middle');

        key.currentState!.collapse(false);
        await tester.pump();
        expect(_bar(tester), middle);
        await tester.pump(const Duration(milliseconds: 75));
        expect(_bar(tester).width, greaterThan(middle.width));
        await tester.pumpAndSettle();
        expect(_bar(tester), expanded);

        key.currentState!.collapse(true);
        await tester.pumpAndSettle();
        final compact = _bar(tester);
        expect(compact.size, const Size(184, 56));
        expect(compact.bottom, expanded.bottom);
        expect(compact.center.dx, expanded.center.dx);
        expect(_lens(tester).size, const Size(64, 48));
        expect(tester.getSize(find.byType(NavigationDock)), reserved);
        expect(tester.getCenter(_glyph(1).first).dy, greaterThan(expandedIconY));
        expect(_labelVisibility(tester), 0);
        if (glass) {
          expect(LiquidGlassHost.debugLastCapture!.panes.length, panes);
        }
        _fixedGlyphs(tester);
        for (final label in _labels) {
          final target = find.bySemanticsLabel(label);
          expect(target, findsOneWidget);
          expect(tester.getSize(target).width, greaterThanOrEqualTo(48));
          expect(tester.getSize(target).height, greaterThanOrEqualTo(48));
        }
        await _snapshot(tester, '$name-compact');
        expect(tester.takeException(), isNull);
        key.currentState!.collapse(false);
        await tester.pumpAndSettle();
        expect(_bar(tester), expanded);
        _fixedGlyphs(tester);
      } finally {
        semantics.dispose();
        await tester.pumpWidget(const SizedBox.shrink());
      }
    });

    for (final direction in TextDirection.values) {
      testWidgets('$name compact tap and drag keep $direction destinations', (tester) async {
        final key = GlobalKey<_FixtureState>();
        try {
          await _pump(tester, key, glass: glass, quality: quality, direction: direction);
          key.currentState!.collapse(true);
          await tester.pumpAndSettle();
          final first = tester.getCenter(_glyph(0).first);
          final last = tester.getCenter(_glyph(2).first);
          expect(first.dx < last.dx, direction == TextDirection.ltr);
          await tester.tapAt(tester.getCenter(_glyph(1).first));
          await tester.pumpAndSettle();
          expect(key.currentState!.selected, 1);
          final gesture = await tester.startGesture(tester.getCenter(_glyph(0).first));
          await tester.pump(const Duration(milliseconds: 60));
          await gesture.moveTo(tester.getCenter(_glyph(1).first));
          await tester.pump(const Duration(milliseconds: 100));
          await gesture.moveTo(tester.getCenter(_glyph(2).first));
          await tester.pump(const Duration(milliseconds: 100));
          await gesture.up();
          await tester.pumpAndSettle();
          expect(key.currentState!.selected, 2);
          expect(_bar(tester).size, const Size(184, 56));
          _fixedGlyphs(tester);
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
        }
      });
    }
  }

  testWidgets('reduced motion snaps and muted collapse resumes without ticking', (tester) async {
    final key = GlobalKey<_FixtureState>();
    try {
      await _pump(tester, key, glass: false);
      key.currentState!.tick(false);
      key.currentState!.collapse(true);
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      expect(tester.binding.hasScheduledFrame, isFalse);
      key.currentState!.tick(true);
      await tester.pumpAndSettle();
      expect(_bar(tester).size, const Size(184, 56));
      key.currentState!.reduceMotion();
      key.currentState!.collapse(false);
      await tester.pump();
      expect(_bar(tester).size, const Size(274, 62));
      await tester.pump();
      expect(tester.binding.hasScheduledFrame, isFalse);
      _fixedGlyphs(tester);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });
}
