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

Widget _app(
  Widget child, {
  bool glass = true,
  bool contrast = false,
  TextDirection direction = TextDirection.ltr,
  Brightness brightness = Brightness.light,
  Color background = Colors.blue,
  LiquidGlassQuality quality = LiquidGlassQuality.standard,
}) => MaterialApp(
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
                LiquidGlassColorField(
                  child: LiquidGlassColorSource(
                    child: ColoredBox(color: background),
                  ),
                ),
                Material(
                  type: MaterialType.transparency,
                  child: Center(child: child),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  ),
);

Finder get _panes => find.descendant(
  of: find.byWidgetPredicate(
    (widget) => widget is CommonSwitch || widget is GlassSwitch,
  ),
  matching: find.byType(LiquidGlass),
);
Rect _thumb(WidgetTester tester) => tester.getRect(_panes.last);

Finder get _track => _panes.first;

Color? _trackFill(WidgetTester tester) =>
    tester.widget<LiquidGlass>(_track).style.tint;

Future<({List<int> pixels, int width})> _frame(
  WidgetTester tester,
  String name,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_capture),
  );
  if (boundary.debugNeedsPaint) {
    tester.binding.scheduleForcedFrame();
    await tester.pump();
  }
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final bytes = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        final backend =
            Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true'
            ? 'impeller'
            : 'default';
        File(
          '$output/switch-$backend-$name.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
        final panes = LiquidGlassHost.debugLastCapture?.panes;
        File('$output/switch-$backend-$name.json').writeAsStringSync(
          jsonEncode({
            'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
            'shaderReady': LiquidGlass.debugShaderReady,
            'paneCount': panes?.length,
            'levels': [
              if (panes != null)
                for (final pane in panes) pane.level,
            ],
            'thumb': _thumb(tester).toString(),
          }),
        );
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
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      );
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
    }
  });

  for (final direction in TextDirection.values) {
    testWidgets(
      'CommonSwitch keeps tap drag keyboard semantics and pending value $direction',
      (tester) async {
        final semantics = tester.ensureSemantics();
        var value = false;
        var enabled = true;
        final changes = <bool>[];
        late StateSetter rebuild;
        final control = find.byType(CommonSwitch);
        try {
          await tester.pumpWidget(
            StatefulBuilder(
              builder: (_, update) {
                rebuild = update;
                return _app(
                  CommonSwitch(
                    value: value,
                    onChanged: enabled ? changes.add : null,
                  ),
                  direction: direction,
                );
              },
            ),
          );
          await tester.pumpAndSettle();
          expect(_panes, findsNWidgets(2));
          expect(tester.getSize(control).width, 60);
          expect(tester.getSize(control).height, greaterThanOrEqualTo(40));
          final before = _thumb(tester);
          expect(
            tester
                .getSemantics(control)
                .getSemanticsData()
                .flagsCollection
                .isToggled,
            ui.Tristate.isFalse,
          );
          await tester.tap(control);
          await tester.pumpAndSettle();
          expect(changes, [true]);
          expect(tester.widget<CommonSwitch>(control).value, isFalse);
          expect(_thumb(tester), before);

          final sign = direction == TextDirection.ltr ? 1.0 : -1.0;
          final gesture = await tester.startGesture(before.center);
          try {
            await tester.pump(kPressTimeout);
            await tester.pump(const Duration(milliseconds: 200));
            expect(tester.widget<LiquidGlass>(_panes.first).pressed, isTrue);
            expect(_thumb(tester).height, greaterThan(before.height));
            await gesture.moveBy(Offset(sign * 24, 0));
            await tester.pump();
            await gesture.moveBy(Offset(sign * 4, 0));
            await tester.pump();
            final moved = (_thumb(tester).center.dx - before.center.dx) * sign;
            expect(moved, greaterThan(0));
            expect(moved, lessThan(20));
            await gesture.moveBy(Offset(sign * 20, 0));
          } finally {
            await gesture.up();
          }
          await tester.pumpAndSettle();
          expect(changes, [true, true]);
          expect(_thumb(tester), before);
          rebuild(() => value = true);
          await tester.pumpAndSettle();
          expect(
            (_thumb(tester).center.dx - before.center.dx) * sign,
            closeTo(20, 0.01),
          );
          expect(
            tester
                .getSemantics(control)
                .getSemanticsData()
                .flagsCollection
                .isToggled,
            ui.Tristate.isTrue,
          );

          await tester.sendKeyEvent(LogicalKeyboardKey.tab);
          await tester.pumpAndSettle();
          final ring = tester.widget<DecoratedBox>(
            find.descendant(
              of: control,
              matching: find.byWidgetPredicate(
                (widget) =>
                    widget is DecoratedBox &&
                    widget.decoration is ShapeDecoration &&
                    ((widget.decoration as ShapeDecoration).shape
                                as OutlinedBorder)
                            .side
                            .width ==
                        2,
              ),
            ),
          );
          final side =
              ((ring.decoration as ShapeDecoration).shape as OutlinedBorder)
                  .side;
          expect(
            side.color,
            Theme.of(tester.element(control)).colorScheme.primary,
          );
          await tester.sendKeyEvent(LogicalKeyboardKey.space);
          await tester.pumpAndSettle();
          expect(changes.last, isFalse);

          rebuild(() => enabled = false);
          await tester.pumpAndSettle();
          final count = changes.length;
          await tester.tap(control, warnIfMissed: false);
          await tester.pumpAndSettle();
          expect(changes, hasLength(count));
          expect(
            tester
                .getSemantics(control)
                .getSemanticsData()
                .flagsCollection
                .isEnabled,
            ui.Tristate.isFalse,
          );
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
          semantics.dispose();
        }
      },
    );

    testWidgets(
      'legacy wrapper retains native tap drag keyboard semantics and pending value $direction',
      (tester) async {
        final semantics = tester.ensureSemantics();
        final focus = FocusNode();
        var value = false;
        var enabled = true;
        final changes = <bool>[];
        late StateSetter rebuild;
        try {
          await tester.pumpWidget(
            StatefulBuilder(
              builder: (_, update) {
                rebuild = update;
                return _app(
                  GlassSwitch(
                    child: Switch(
                      value: value,
                      focusNode: focus,
                      onChanged: enabled ? changes.add : null,
                    ),
                  ),
                  direction: direction,
                );
              },
            ),
          );
          await tester.pumpAndSettle();
          expect(_panes, findsNWidgets(2));
          expect(
            tester.getSize(find.byType(GlassSwitch)),
            tester.getSize(find.byType(Switch)),
          );
          final before = _thumb(tester);
          expect(
            tester
                .getSemantics(find.byType(Switch))
                .getSemanticsData()
                .hasFlag(ui.SemanticsFlag.hasToggledState),
            isTrue,
          );
          await tester.tap(find.byType(Switch));
          await tester.pumpAndSettle();
          expect(changes, [true]);
          expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
          expect(_thumb(tester), before);
          final sign = direction == TextDirection.ltr ? 1.0 : -1.0;
          final gesture = await tester.startGesture(before.center);
          try {
            await tester.pump(kPressTimeout);
            expect(
              tester
                  .widgetList<LiquidGlass>(_panes)
                  .every((pane) => pane.pressed),
              isTrue,
            );
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
          expect(
            (_thumb(tester).center.dx - before.center.dx) * sign,
            closeTo(20, 0.01),
          );
          expect(
            tester
                .getSemantics(find.byType(Switch))
                .getSemanticsData()
                .hasFlag(ui.SemanticsFlag.isToggled),
            isTrue,
          );
          focus.requestFocus();
          await tester.pumpAndSettle();
          expect(focus.hasPrimaryFocus, isTrue);
          final decoration =
              tester
                      .widget<DecoratedBox>(
                        find
                            .descendant(
                              of: find.byType(GlassSwitch),
                              matching: find.byType(DecoratedBox),
                            )
                            .first,
                      )
                      .decoration
                  as ShapeDecoration;
          expect((decoration.shape as OutlinedBorder).side.width, 2);
          expect(
            (decoration.shape as OutlinedBorder).side.color,
            Theme.of(tester.element(find.byType(Switch))).colorScheme.primary,
          );
          await tester.sendKeyEvent(LogicalKeyboardKey.space);
          await tester.pumpAndSettle();
          expect(changes.last, isFalse);
          rebuild(() => enabled = false);
          await tester.pumpAndSettle();
          final count = changes.length;
          await tester.tap(find.byType(Switch), warnIfMissed: false);
          await tester.pumpAndSettle();
          expect(changes, hasLength(count));
          expect(
            tester
                .getSemantics(find.byType(Switch))
                .getSemanticsData()
                .hasFlag(ui.SemanticsFlag.isEnabled),
            isFalse,
          );
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
          focus.dispose();
          semantics.dispose();
        }
      },
    );
  }

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('a switch on a glass card keeps its ${quality.name} finish', (
      tester,
    ) async {
      await tester.pumpWidget(
        _app(
          LiquidGlassBody(
            child: GlassSurfaceScope(
              child: CommonSwitch(value: false, onChanged: (_) {}),
            ),
          ),
          quality: quality,
          brightness: Brightness.dark,
        ),
      );
      await tester.pumpAndSettle();
      expect(_panes, findsNWidgets(2));
      for (final pane in tester.widgetList<LiquidGlass>(_panes)) {
        final style = pane.style.forQuality(quality);
        expect(style.tint.a, lessThan(0.25));
        if (quality == LiquidGlassQuality.ultra) {
          expect(style.refraction, greaterThan(0));
          expect(style.bevel, greaterThan(0));
          expect(style.blur, 0);
        } else {
          expect(style.refraction, 0);
          expect(style.bevel, 0);
          expect(style.blur, greaterThanOrEqualTo(2.5));
        }
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  testWidgets('None and high contrast retain the exact original switch', (
    tester,
  ) async {
    for (final mode in [(false, false), (true, true)]) {
      const original = Switch(
        value: true,
        onChanged: null,
        padding: EdgeInsets.all(9),
      );
      await tester.pumpWidget(
        _app(
          const GlassSwitch(child: original),
          glass: mode.$1,
          contrast: mode.$2,
        ),
      );
      await tester.pumpAndSettle();
      expect(_panes, findsNothing);
      expect(
        identical(tester.widget<Switch>(find.byType(Switch)), original),
        isTrue,
      );
      expect(tester.takeException(), isNull);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('CommonSwitch keeps opaque parts in solid and high contrast', (
    tester,
  ) async {
    for (final mode in [(false, false), (true, true)]) {
      await tester.pumpWidget(
        _app(
          CommonSwitch(value: true, onChanged: (_) {}),
          glass: mode.$1,
          contrast: mode.$2,
        ),
      );
      await tester.pumpAndSettle();
      expect(_panes, findsNothing);
      final parts = tester.widgetList<DecoratedBox>(
        find.descendant(
          of: find.byType(CommonSwitch),
          matching: find.byType(DecoratedBox),
        ),
      );
      expect(parts, hasLength(2));
      for (final part in parts) {
        expect((part.decoration as ShapeDecoration).color!.a, 1);
      }
      expect(tester.takeException(), isNull);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a row on the page keeps the glass switch and its row action', (
    tester,
  ) async {
    final changes = <bool>[];
    await tester.pumpWidget(
      _app(
        SizedBox(
          width: 220,
          child: ListItem.toggle(
            title: const GlassText('LAN'),
            value: false,
            onChanged: changes.add,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(CommonSwitch), findsOneWidget);
    expect(_panes, findsNWidgets(2));
    await tester.tap(find.text('LAN'));
    await tester.pumpAndSettle();
    expect(changes, [true]);
    expect(
      tester.widget<CommonSwitch>(find.byType(CommonSwitch)).value,
      isFalse,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });

  testWidgets(
    'legacy wrapper hover reduced motion and explicit state colors stay scoped',
    (tester) async {
      var value = false;
      late StateSetter rebuild;
      const icon = Icon(IconData(0x41), color: Colors.orange);
      try {
        await tester.pumpWidget(
          StatefulBuilder(
            builder: (_, update) {
              rebuild = update;
              return _app(
                MediaQuery(
                  data: const MediaQueryData(disableAnimations: true),
                  child: GlassSwitch(
                    child: Switch(
                      value: value,
                      onChanged: (_) {},
                      padding: const EdgeInsetsDirectional.only(start: 8),
                      thumbIcon: const WidgetStatePropertyAll(icon),
                      trackColor: const WidgetStatePropertyAll(Colors.orange),
                      thumbColor: const WidgetStatePropertyAll(Colors.orange),
                      trackOutlineColor: const WidgetStatePropertyAll(
                        Colors.orange,
                      ),
                      trackOutlineWidth: const WidgetStatePropertyAll(1),
                    ),
                  ),
                ),
              );
            },
          ),
        );
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
        final pane = tester.widget<LiquidGlass>(_panes.last);
        final fill = _trackFill(tester)!;
        for (final tint in [pane.style.tint, fill]) {
          expect(tint.r, Colors.orange.r);
          expect(tint.g, Colors.orange.g);
          expect(tint.b, Colors.orange.b);
          expect(tint.a, lessThan(1));
        }
        final ink = tester.widget<GlassInk>(find.byType(GlassInk));
        expect(ink.source, Colors.orange);
        expect(ink.role, GlassInkRole.icon);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      }
    },
  );

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      for (final value in [false, true]) {
        testWidgets(
          'track and thumb glass transmit moving source $brightness $quality on=$value',
          (tester) async {
            tester.view.physicalSize = const Size(240, 160);
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.reset);
            try {
              final samples = <({List<int> pixels, int width})>[];
              final prefix = '${brightness.name}-${quality.name}-$value';
              for (final color in [
                const Color(0xFF803020),
                const Color(0xFF205080),
              ]) {
                await tester.pumpWidget(
                  _app(
                    CommonSwitch(value: value, onChanged: (_) {}),
                    brightness: brightness,
                    quality: quality,
                    background: color,
                  ),
                );
                await tester.pumpAndSettle();
                await tester.runAsync(
                  () => Future<void>.delayed(Duration.zero),
                );
                await tester.pump();
                samples.add(
                  await _frame(tester, '$prefix-${color.toARGB32()}'),
                );
              }
              final thumb = _thumb(tester);
              final track = tester.getRect(_track);
              final colors = Theme.of(
                tester.element(find.byType(CommonSwitch)),
              ).colorScheme;
              expect(
                _trackFill(tester),
                value
                    ? colors.primary.withValues(alpha: 0.18)
                    : colors.onSurface.withValues(
                        alpha: quality == LiquidGlassQuality.standard
                            ? 0.13
                            : 0.06,
                      ),
              );
              for (final point in [
                thumb.center + const Offset(0, 6),
                Offset(
                  value ? track.left + 8 : track.right - 8,
                  track.center.dy,
                ),
              ]) {
                final x = point.dx.floor();
                final y = point.dy.floor();
                final index = (y * samples.first.width + x) * 4;
                final red =
                    (samples[0].pixels[index] - samples[1].pixels[index]).abs();
                final blue =
                    (samples[0].pixels[index + 2] -
                            samples[1].pixels[index + 2])
                        .abs();
                expect(
                  red + blue,
                  greaterThan(40),
                  reason:
                      'the thumb and track must both let the source through',
                );
              }
              expect(find.byType(LiquidGlassHost), findsOneWidget);
              final capture = LiquidGlassHost.debugLastCapture!;
              expect(capture.panes, hasLength(2));
              expect(capture.panes.every((pane) => pane.wallpaper), isTrue);
              if (ui.ImageFilter.isShaderFilterSupported) {
                expect(LiquidGlass.debugShaderReady, isTrue);
              }
              expect(tester.takeException(), isNull);
            } finally {
              await tester.pumpWidget(const SizedBox.shrink());
              await tester.pump();
            }
          },
        );
      }
    }
  }
}
