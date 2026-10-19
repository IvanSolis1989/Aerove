import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _capture = ValueKey('switch-capture');

Widget _app(Widget child, {bool glass = true, bool contrast = false, TextDirection direction = TextDirection.ltr, Brightness brightness = Brightness.light, Color background = Colors.blue, LiquidGlassQuality quality = LiquidGlassQuality.standard}) => MaterialApp(
  theme: ThemeData(brightness: brightness),
  home: MediaQuery(
    data: MediaQueryData(highContrast: contrast),
    child: Directionality(
      textDirection: direction,
      child: LiquidGlassScope(
        enabled: glass,
        quality: quality,
        child: RepaintBoundary(
          key: _capture,
          child: LiquidGlassHost(
            enabled: glass && !contrast,
            wallpaperOnly: true,
            child: Stack(
              fit: StackFit.expand,
              children: [
                LiquidGlassColorField(child: LiquidGlassColorSource(child: ColoredBox(color: background))),
                Material(type: MaterialType.transparency, child: Center(child: child)),
              ],
            ),
          ),
        ),
      ),
    ),
  ),
);

Finder get _panes => find.descendant(of: find.byType(GlassSwitch), matching: find.byType(LiquidGlass));
Rect _thumb(WidgetTester tester) => tester.getRect(_panes.last);

Future<({List<int> pixels, int width})> _frame(WidgetTester tester, String name) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_capture));
  if (boundary.debugNeedsPaint) {
    tester.binding.scheduleForcedFrame();
    await tester.pump();
  }
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        final backend = Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true' ? 'impeller' : 'default';
        File('$output/switch-$backend-$name.png').writeAsBytesSync(png.buffer.asUint8List());
        final panes = LiquidGlassHost.debugLastCapture?.panes;
        File('$output/switch-$backend-$name.json').writeAsStringSync(jsonEncode({
          'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
          'shaderReady': LiquidGlass.debugShaderReady,
          'paneCount': panes?.length,
          'levels': [if (panes != null) for (final pane in panes) pane.level],
          'thumb': _thumb(tester).toString(),
        }));
      }
      return (pixels: bytes.buffer.asUint8List().toList(), width: image.width);
    } finally {
      image.dispose();
    }
  }))!;
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
    }
  });

  for (final direction in TextDirection.values) {
    testWidgets('switch retains native tap drag keyboard semantics and pending value $direction', (tester) async {
      final semantics = tester.ensureSemantics();
      final focus = FocusNode();
      var value = false;
      var enabled = true;
      final changes = <bool>[];
      late StateSetter rebuild;
      try {
        await tester.pumpWidget(StatefulBuilder(builder: (_, update) {
          rebuild = update;
          return _app(GlassSwitch(child: Switch(value: value, focusNode: focus, onChanged: enabled ? changes.add : null)), direction: direction);
        }));
        await tester.pumpAndSettle();
        expect(_panes, findsNWidgets(2));
        expect(tester.getSize(find.byType(GlassSwitch)), tester.getSize(find.byType(Switch)));
        final before = _thumb(tester);
        expect(tester.getSemantics(find.byType(Switch)).getSemanticsData().hasFlag(ui.SemanticsFlag.hasToggledState), isTrue);
        await tester.tap(find.byType(Switch));
        await tester.pumpAndSettle();
        expect(changes, [true]);
        expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
        expect(_thumb(tester), before);
        final sign = direction == TextDirection.ltr ? 1.0 : -1.0;
        final gesture = await tester.startGesture(before.center);
        try {
          await tester.pump(kPressTimeout);
          expect(tester.widgetList<LiquidGlass>(_panes).every((pane) => pane.pressed), isTrue);
          await gesture.moveBy(Offset(sign * 8, 0));
          await tester.pump();
          final moved = _thumb(tester);
          expect((moved.center.dx - before.center.dx) * sign, greaterThan(0));
          expect((moved.center.dx - before.center.dx).abs(), lessThan(20));
          await gesture.moveBy(Offset(sign * 20, 0));
        } finally {
          await gesture.up();
        }
        await tester.pumpAndSettle();
        expect(changes.last, isTrue);
        expect(_thumb(tester), before);
        rebuild(() => value = true);
        await tester.pumpAndSettle();
        expect((_thumb(tester).center.dx - before.center.dx) * sign, closeTo(20, 0.01));
        expect(tester.getSemantics(find.byType(Switch)).getSemanticsData().hasFlag(ui.SemanticsFlag.isToggled), isTrue);
        focus.requestFocus();
        await tester.pump();
        final decoration = tester.widget<DecoratedBox>(find.descendant(of: find.byType(GlassSwitch), matching: find.byType(DecoratedBox)).first).decoration as ShapeDecoration;
        expect((decoration.shape as OutlinedBorder).side.width, 2);
        await tester.sendKeyEvent(LogicalKeyboardKey.space);
        await tester.pumpAndSettle();
        expect(changes.last, isFalse);
        rebuild(() => enabled = false);
        await tester.pumpAndSettle();
        final count = changes.length;
        await tester.tap(find.byType(Switch), warnIfMissed: false);
        await tester.pumpAndSettle();
        expect(changes, hasLength(count));
        expect(tester.getSemantics(find.byType(Switch)).getSemanticsData().hasFlag(ui.SemanticsFlag.isEnabled), isFalse);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        focus.dispose();
        semantics.dispose();
      }
    });
  }

  testWidgets('None and high contrast retain the exact original switch', (tester) async {
    for (final mode in [(false, false), (true, true)]) {
      const original = Switch(value: true, onChanged: null, padding: EdgeInsets.all(9));
      await tester.pumpWidget(_app(const GlassSwitch(child: original), glass: mode.$1, contrast: mode.$2));
      await tester.pumpAndSettle();
      expect(_panes, findsNothing);
      expect(identical(tester.widget<Switch>(find.byType(Switch)), original), isTrue);
      expect(tester.takeException(), isNull);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('settings rows share glass and preserve their row action', (tester) async {
    final changes = <bool>[];
    await tester.pumpWidget(_app(SizedBox(
      width: 220,
      child: ListItem.toggle(title: const GlassText('LAN'), value: false, onChanged: changes.add),
    )));
    await tester.pumpAndSettle();
    expect(find.byType(GlassSwitch), findsOneWidget);
    expect(_panes, findsNWidgets(2));
    await tester.tap(find.text('LAN'));
    await tester.pumpAndSettle();
    expect(changes, [true]);
    expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });

  testWidgets('hover reduced motion and explicit state colors stay scoped', (tester) async {
    var value = false;
    late StateSetter rebuild;
    const icon = Icon(IconData(0x41), color: Colors.orange);
    try {
      await tester.pumpWidget(StatefulBuilder(builder: (_, update) {
        rebuild = update;
        return _app(MediaQuery(
          data: const MediaQueryData(disableAnimations: true),
          child: GlassSwitch(child: Switch(
            value: value,
            onChanged: (_) {},
            padding: const EdgeInsetsDirectional.only(start: 8),
            thumbIcon: WidgetStatePropertyAll(icon),
            trackColor: const WidgetStatePropertyAll(Colors.orange),
            thumbColor: const WidgetStatePropertyAll(Colors.orange),
            trackOutlineColor: const WidgetStatePropertyAll(Colors.orange),
            trackOutlineWidth: const WidgetStatePropertyAll(1),
          )),
        ));
      }));
      await tester.pumpAndSettle();
      final before = _thumb(tester);
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      try {
        await mouse.addPointer(location: Offset.zero);
        await mouse.moveTo(tester.getCenter(find.byType(Switch)));
        await tester.pump();
        expect(tester.widget<LiquidGlass>(_panes.first).pressed, isTrue);
        await mouse.moveTo(Offset.zero);
        await tester.pump();
        expect(tester.widget<LiquidGlass>(_panes.first).pressed, isFalse);
      } finally {
        await mouse.removePointer();
      }
      rebuild(() => value = true);
      await tester.pump();
      await tester.pump();
      expect(_thumb(tester).center.dx - before.center.dx, closeTo(20, 0.01));
      for (final pane in tester.widgetList<LiquidGlass>(_panes)) {
        expect(pane.style.tint.r, Colors.orange.r);
        expect(pane.style.tint.g, Colors.orange.g);
        expect(pane.style.tint.b, Colors.orange.b);
        expect(pane.style.tint.a, lessThan(1));
      }
      final ink = tester.widget<GlassInk>(find.byType(GlassInk));
      expect(ink.source, Colors.orange);
      expect(ink.role, GlassInkRole.icon);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      for (final value in [false, true]) {
        testWidgets('track and thumb transmit moving source $brightness $quality on=$value', (tester) async {
          tester.view.physicalSize = const Size(240, 160);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          try {
            final samples = <({List<int> pixels, int width})>[];
            final prefix = '${brightness.name}-${quality.name}-$value';
            for (final color in [const Color(0xFF803020), const Color(0xFF205080)]) {
              await tester.pumpWidget(_app(GlassSwitch(child: Switch(value: value, onChanged: (_) {})), brightness: brightness, quality: quality, background: color));
              await tester.pumpAndSettle();
              await tester.runAsync(() => Future<void>.delayed(Duration.zero));
              await tester.pump();
              samples.add(await _frame(tester, '$prefix-${color.toARGB32()}'));
            }
            final thumb = _thumb(tester);
            final track = tester.getRect(_panes.first);
            for (final point in [thumb.center + const Offset(0, 6), Offset(value ? track.left + 8 : track.right - 8, track.center.dy)]) {
              final x = point.dx.floor();
              final y = point.dy.floor();
              final index = (y * samples.first.width + x) * 4;
              final red = (samples[0].pixels[index] - samples[1].pixels[index]).abs();
              final blue = (samples[0].pixels[index + 2] - samples[1].pixels[index + 2]).abs();
              expect(red + blue, greaterThan(40), reason: 'both optical surfaces must transmit the source instead of solid thumb paint');
            }
            expect(find.byType(LiquidGlassHost), findsOneWidget);
            final capture = LiquidGlassHost.debugLastCapture!;
            expect(capture.panes, hasLength(2));
            expect(capture.panes.every((pane) => pane.wallpaper && pane.level == 0), isTrue);
            if (ui.ImageFilter.isShaderFilterSupported) {
              expect(LiquidGlass.debugShaderReady, isTrue);
            }
            expect(tester.takeException(), isNull);
          } finally {
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pump();
          }
        });
      }
    }
  }
}
