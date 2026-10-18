import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/navigation_dock.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
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
    this.labels = _labels,
    this.scaler = TextScaler.noScaling,
    this.fontFamily = 'DockMetrics',
  });

  final bool glass;
  final LiquidGlassQuality quality;
  final TextDirection direction;
  final List<String> labels;
  final TextScaler scaler;
  final String? fontFamily;

  @override
  State<_Fixture> createState() => _FixtureState();
}

class _FixtureState extends State<_Fixture> {
  bool collapsed = false;
  bool reduced = false;
  bool ticking = true;
  int selected = 0;
  int builds = 0;
  late List<String> labels = widget.labels;
  late TextScaler scaler = widget.scaler;
  late TextDirection direction = widget.direction;
  late String? fontFamily = widget.fontFamily;

  void inputs({
    List<String>? labels,
    TextScaler? scaler,
    TextDirection? direction,
    String? fontFamily,
  }) => setState(() {
    if (labels != null) this.labels = labels;
    if (scaler != null) this.scaler = scaler;
    if (direction != null) this.direction = direction;
    if (fontFamily != null) this.fontFamily = fontFamily;
  });

  void collapse(bool value) => setState(() => collapsed = value);

  void reduceMotion() => setState(() => reduced = true);

  void tick(bool value) => setState(() => ticking = value);

  @override
  Widget build(BuildContext context) {
    builds++;
    return TestApp(
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        fontFamily: fontFamily,
      ),
      child: Directionality(
        textDirection: direction,
        child: MediaQuery(
          data: MediaQueryData.fromView(
            View.of(context),
          ).copyWith(disableAnimations: reduced, textScaler: scaler),
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
                            for (var index = 0; index < labels.length; index++)
                              NavigationDockDestination(
                                glyph: _glyphs[index],
                                label: labels[index],
                              ),
                          ],
                          selectedIndex: selected,
                          onSelected: (index) =>
                              setState(() => selected = index),
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

Rect _bar(WidgetTester tester) =>
    tester.getRect(find.byType(FloatingNavigationBar));

Rect _lens(WidgetTester tester) => tester.getRect(
  find.descendant(
    of: find.byType(FloatingNavigationBar),
    matching: find.byType(PositionedDirectional),
  ),
);

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

({Size label, double fontSize, double iconY}) _metrics(
  WidgetTester tester,
  String text,
) {
  final label = find.text(text).first;
  return (
    label: tester.getRect(label).size,
    fontSize: tester.widget<Text>(label).style!.fontSize!,
    iconY: tester.getCenter(_glyph(0).first).dy - _bar(tester).top,
  );
}

Future<void> _snapshot(WidgetTester tester, String name) async {
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output == null) return;
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_scene),
  );
  await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
      final directory = Directory(output)..createSync(recursive: true);
      File(
        '${directory.path}/dock-collapse-$name.png',
      ).writeAsBytesSync(png.buffer.asUint8List());
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
  await tester.pumpWidget(
    _Fixture(key: key, glass: glass, quality: quality, direction: direction),
  );
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(() async {
    final font = FontLoader('DockMetrics')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  for (final input in ['labels', 'font', 'scaler', 'direction']) {
    testWidgets('label metrics follow changed $input on the same dock state', (
      tester,
    ) async {
      final initial = input == 'font'
          ? const ['iiiiiiiiiiii', 'WW', 'Tools']
          : const ['Home', 'Profiles', 'Tools'];
      final next = input == 'labels' ? _labels : initial;
      final scaler = input == 'scaler'
          ? const TextScaler.linear(1.4)
          : TextScaler.noScaling;
      final direction = input == 'direction'
          ? TextDirection.rtl
          : TextDirection.ltr;
      final family = input == 'font' ? 'DockMetrics' : 'Ahem';
      final key = GlobalKey<_FixtureState>();
      try {
        await _pump(tester, key, glass: false);
        key.currentState!.inputs(labels: initial, fontFamily: 'Ahem');
        await tester.pumpAndSettle();
        final state = tester.state(find.byType(FloatingNavigationBar));
        final before = _metrics(tester, initial.first);
        key.currentState!.inputs(
          labels: List.of(next),
          scaler: scaler,
          direction: direction,
          fontFamily: family,
        );
        await tester.pumpAndSettle();
        expect(tester.state(find.byType(FloatingNavigationBar)), same(state));
        final updated = _metrics(tester, next.first);
        if (input != 'direction') {
          expect(updated, isNot(before));
        }
        expect(
          tester.getCenter(_glyph(0).first).dx <
              tester.getCenter(_glyph(2).first).dx,
          direction == TextDirection.ltr,
        );
        await tester.pumpWidget(
          _Fixture(
            key: GlobalKey<_FixtureState>(),
            glass: false,
            quality: LiquidGlassQuality.standard,
            labels: List.of(next),
            scaler: scaler,
            direction: direction,
            fontFamily: family,
          ),
        );
        await tester.pumpAndSettle();
        final fresh = _metrics(tester, next.first);
        expect(updated.label.width, closeTo(fresh.label.width, 1e-6));
        expect(updated.label.height, closeTo(fresh.label.height, 1e-6));
        expect(updated.fontSize, fresh.fontSize);
        expect(updated.iconY, closeTo(fresh.iconY, 1e-6));
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
      }
    });
  }

  for (final (glass, quality, name) in [
    (false, LiquidGlassQuality.standard, 'none'),
    (true, LiquidGlassQuality.standard, 'standard'),
    (true, LiquidGlassQuality.ultra, 'ultra'),
  ]) {
    testWidgets('$name collapse keeps icons, anchor and inset, and reverses', (
      tester,
    ) async {
      final semantics = tester.ensureSemantics();
      try {
        final key = GlobalKey<_FixtureState>();
        await _pump(tester, key, glass: glass, quality: quality);
        final expanded = _bar(tester);
        final expandedLens = _lens(tester);
        final reserved = tester.getSize(find.byType(NavigationDock));
        final expandedIconY = tester.getCenter(_glyph(1).first).dy;
        final expandedLabel = tester.getRect(find.text(_labels.first).first);
        final labelFont = tester
            .widget<Text>(find.text(_labels.first).first)
            .style!
            .fontSize;
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
        expect(
          middleLabel.width,
          closeTo(expandedLabel.width * visibility, 1e-6),
        );
        expect(
          middleLabel.height,
          closeTo(expandedLabel.height * visibility, 1e-6),
        );
        expect(
          tester.widget<Text>(find.text(_labels.first).first).style!.fontSize,
          labelFont,
        );
        expect(
          find.descendant(
            of: find.byType(NavigationDock),
            matching: find.byType(ClipRect),
          ),
          findsNothing,
        );
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
        expect(
          tester.getCenter(_glyph(1).first).dy,
          greaterThan(expandedIconY),
        );
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
      testWidgets('$name compact tap and drag keep $direction destinations', (
        tester,
      ) async {
        final key = GlobalKey<_FixtureState>();
        try {
          await _pump(
            tester,
            key,
            glass: glass,
            quality: quality,
            direction: direction,
          );
          key.currentState!.collapse(true);
          await tester.pumpAndSettle();
          final first = tester.getCenter(_glyph(0).first);
          final last = tester.getCenter(_glyph(2).first);
          expect(first.dx < last.dx, direction == TextDirection.ltr);
          await tester.tapAt(tester.getCenter(_glyph(1).first));
          await tester.pumpAndSettle();
          expect(key.currentState!.selected, 1);
          final gesture = await tester.startGesture(
            tester.getCenter(_glyph(0).first),
          );
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

  testWidgets(
    'reduced motion snaps and muted collapse resumes without ticking',
    (tester) async {
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
    },
  );
}
