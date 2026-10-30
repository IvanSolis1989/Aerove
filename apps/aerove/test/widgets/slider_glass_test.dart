import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/slider.dart';
import 'package:fl_clash/ui/theme.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      );
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('one thumb pane and a flat track show the backdrop in '
          '$brightness $quality', (tester) async {
        tester.view.physicalSize = const Size(320, 160);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        for (final enabled in [false, true]) {
          for (final direction in TextDirection.values) {
            final samples = <List<int>>[];
            for (final background in [Colors.blue, Colors.red]) {
              final capture = GlobalKey();
              await tester.pumpWidget(
                MaterialApp(
                  theme: ThemeData(brightness: brightness),
                  home: Directionality(
                    textDirection: direction,
                    child: RepaintBoundary(
                      key: capture,
                      child: LiquidGlassScope(
                        enabled: true,
                        quality: quality,
                        child: LiquidGlassHost(
                          enabled: true,
                          wallpaperOnly: true,
                          child: Stack(
                            children: [
                              Positioned.fill(
                                child: LiquidGlassColorField(
                                  child: LiquidGlassColorSource(
                                    child: ColoredBox(color: background),
                                  ),
                                ),
                              ),
                              Center(
                                child: Material(
                                  type: MaterialType.transparency,
                                  child: SizedBox(
                                    width: 280,
                                    child: Builder(
                                      builder: (context) => SliderTheme(
                                        data: SliderDefaultsM3(context),
                                        child: GlassSlider(
                                          child: Slider(
                                            value: 0.35,
                                            onChanged: enabled ? (_) {} : null,
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              );
              await tester.pumpAndSettle();
              expect(tester.takeException(), isNull);
              final boundary = tester.renderObject<RenderRepaintBoundary>(
                find.byKey(capture),
              );
              await tester.runAsync(() async {
                final image = await boundary.toImage();
                try {
                  final rgba = (await image.toByteData())!;
                  final thumb = LiquidGlassHost.debugLastCapture!.panes.single;
                  final slider = tester.getRect(find.byType(Slider));
                  final ltr = direction == TextDirection.ltr;
                  samples.add([
                    for (final point in [
                      thumb.pane.center,
                      Offset(
                        ltr ? slider.right - 40 : slider.left + 40,
                        thumb.pane.center.dy,
                      ),
                    ])
                      for (var channel = 0; channel < 3; channel++)
                        rgba.getUint8(
                          (point.dy.floor() * image.width + point.dx.floor()) *
                                  4 +
                              channel,
                        ),
                  ]);
                  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
                  if (output != null) {
                    Directory(output).createSync(recursive: true);
                    final png = (await image.toByteData(
                      format: ui.ImageByteFormat.png,
                    ))!;
                    File(
                      '$output/slider-${brightness.name}-${quality.name}-$enabled-${direction.name}-${background.toARGB32()}.png',
                    ).writeAsBytesSync(png.buffer.asUint8List());
                  }
                } finally {
                  image.dispose();
                }
              });
              expect(tester.takeException(), isNull);
            }
            for (var point = 0; point < 2; point++) {
              expect(
                (samples[0][point * 3] - samples[1][point * 3]).abs(),
                greaterThan(30),
                reason: 'the thumb pane and the flat track show the field',
              );
            }
          }
        }
      });
    }
  }

  testWidgets(
    'native slider keeps drag, keyboard, disabled and fallback contracts',
    (tester) async {
      var value = 0.3;
      var changes = 0;
      var ends = 0;
      final focus = FocusNode();
      addTearDown(focus.dispose);
      Future<void> render({
        bool enabled = true,
        bool glass = true,
        bool highContrast = false,
      }) async {
        await tester.pumpWidget(
          MaterialApp(
            home: MediaQuery(
              data: MediaQueryData(highContrast: highContrast),
              child: LiquidGlassScope(
                enabled: glass,
                child: Material(
                  child: StatefulBuilder(
                    builder: (context, update) => Center(
                      child: SizedBox(
                        width: 280,
                        child: SliderTheme(
                          data: SliderDefaultsM3(context),
                          child: GlassSlider(
                            child: Slider(
                              value: value,
                              divisions: 10,
                              focusNode: focus,
                              semanticFormatterCallback: (next) =>
                                  '${(next * 100).round()} percent',
                              onChanged: enabled
                                  ? (next) => update(() {
                                      value = next;
                                      changes++;
                                    })
                                  : null,
                              onChangeEnd: (_) => ends++,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      final semantics = tester.ensureSemantics();
      try {
        await render(glass: false);
        final native = _sliderSemantics(tester);
        expect(native, hasLength(1));
        expect(native.single.value, '30 percent');
        await render();
        final glass = _sliderSemantics(tester);
        expect(glass, hasLength(1));
        expect(glass.single.value, native.single.value);
        expect(glass.single.hasAction(ui.SemanticsAction.increase), isTrue);
        expect(glass.single.hasAction(ui.SemanticsAction.decrease), isTrue);
      } finally {
        semantics.dispose();
      }
      await tester.drag(find.byType(Slider), const Offset(90, 0));
      await tester.pumpAndSettle();
      expect(value, greaterThan(0.3));
      expect(ends, 1);
      focus.requestFocus();
      await tester.pump();
      final beforeKey = value;
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pump();
      expect(value, lessThan(beforeKey));
      final beforeDisabled = changes;
      await render(enabled: false);
      await tester.drag(find.byType(Slider), const Offset(-90, 0));
      await tester.pumpAndSettle();
      expect(changes, beforeDisabled);
      for (final highContrast in [false, true]) {
        await render(glass: highContrast, highContrast: highContrast);
        final theme = SliderTheme.of(tester.element(find.byType(Slider)));
        expect(theme.trackShape, isA<GappedSliderTrackShape>());
        expect(theme.thumbShape, isA<HandleThumbShape>());
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'glass keeps the native thumb travel, hides the ticks and holds its '
    'handle under a press',
    (tester) async {
      tester.view.physicalSize = const Size(320, 160);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      Future<void> render(
        bool glass,
        _SliderGeometry geometry,
        double value,
        TextDirection direction,
      ) async {
        await tester.pumpWidget(
          MaterialApp(
            home: Directionality(
              textDirection: direction,
              child: LiquidGlassScope(
                enabled: glass,
                child: LiquidGlassHost(
                  enabled: true,
                  child: Material(
                    child: Center(
                      child: SizedBox(
                        width: 280,
                        child: Builder(
                          builder: (context) {
                            final defaults = SliderDefaultsM3(context);
                            final slider = Slider(
                              padding: EdgeInsets.zero,
                              value: value,
                              divisions: 10,
                              onChanged: (_) {},
                            );
                            return SliderTheme(
                              data: defaults.copyWith(
                                trackShape: _TrackGeometryProbe(
                                  defaults.trackShape!,
                                  geometry,
                                ),
                                thumbShape: _ThumbGeometryProbe(
                                  defaults.thumbShape!,
                                  geometry,
                                ),
                                tickMarkShape: _TickGeometryProbe(
                                  defaults.tickMarkShape!,
                                  geometry,
                                ),
                              ),
                              child: glass
                                  ? GlassSlider(child: slider)
                                  : slider,
                            );
                          },
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      for (final direction in TextDirection.values) {
        for (final value in [0.0, 0.5, 1.0]) {
          final native = _SliderGeometry();
          await render(false, native, value, direction);
          final glass = _SliderGeometry();
          await render(true, glass, value, direction);
          final thumb = LiquidGlassHost.debugLastCapture!.panes.single.pane;
          expect(thumb.center, native.thumb!.center);
          expect(thumb.size, const Size(12, 32));
          expect(native.ticks, isNotEmpty);
          expect(glass.ticks, isEmpty);
          final theme = SliderTheme.of(tester.element(find.byType(Slider)));
          expect(theme.trackShape!.isRounded, isTrue);
        }
        await render(true, _SliderGeometry(), 0.5, direction);
        final rest = LiquidGlassHost.debugLastCapture!.panes.single.pane;
        final glassPress = await tester.startGesture(rest.center);
        await tester.pumpAndSettle();
        expect(LiquidGlassHost.debugLastCapture!.panes.single.pane, rest);
        await glassPress.up();
        await tester.pumpAndSettle();
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('an empty range has the same disabled glass as a null callback', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 160);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final capture = GlobalKey();
    var changes = 0;
    final frames = <List<int>>[];
    for (final empty in [false, true]) {
      await tester.pumpWidget(
        MaterialApp(
          home: RepaintBoundary(
            key: capture,
            child: LiquidGlassScope(
              enabled: true,
              child: LiquidGlassHost(
                enabled: true,
                child: Material(
                  child: Center(
                    child: SizedBox(
                      width: 280,
                      child: Builder(
                        builder: (context) => SliderTheme(
                          data: SliderDefaultsM3(context),
                          child: GlassSlider(
                            child: Slider(
                              min: empty ? 1 : 0,
                              max: 1,
                              value: empty ? 1 : 0,
                              onChanged: empty ? (_) => changes++ : null,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.drag(find.byType(Slider), const Offset(80, 0));
      await tester.pumpAndSettle();
      final boundary = tester.renderObject<RenderRepaintBoundary>(
        find.byKey(capture),
      );
      await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          frames.add((await image.toByteData())!.buffer.asUint8List().toList());
        } finally {
          image.dispose();
        }
      });
    }
    expect(changes, 0);
    expect(frames[0], frames[1]);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'the secondary run follows the value direction and clears when removed',
    (tester) async {
      tester.view.physicalSize = const Size(320, 160);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final capture = GlobalKey();
      for (final direction in TextDirection.values) {
        for (final secondary in [0.75, null]) {
          await tester.pumpWidget(
            MaterialApp(
              home: Directionality(
                textDirection: direction,
                child: RepaintBoundary(
                  key: capture,
                  child: LiquidGlassScope(
                    enabled: true,
                    child: LiquidGlassHost(
                      enabled: true,
                      child: Material(
                        child: Center(
                          child: SizedBox(
                            width: 280,
                            child: Builder(
                              builder: (context) => SliderTheme(
                                data: SliderDefaultsM3(context),
                                child: GlassSlider(
                                  child: Slider(
                                    value: 0.25,
                                    secondaryTrackValue: secondary,
                                    activeColor: Colors.green,
                                    inactiveColor: Colors.orange,
                                    secondaryActiveColor: Colors.blue,
                                    onChanged: (_) {},
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final thumb = LiquidGlassHost.debugLastCapture!.panes.single.pane;
          final ahead = direction == TextDirection.ltr ? 1.0 : -1.0;
          final boundary = tester.renderObject<RenderRepaintBoundary>(
            find.byKey(capture),
          );
          final pixels = await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final rgba = (await image.toByteData())!;
              Color at(double dx) {
                final index =
                    (thumb.center.dy.floor() * image.width +
                        (thumb.center.dx + dx * ahead).floor()) *
                    4;
                return Color.fromARGB(
                  255,
                  rgba.getUint8(index),
                  rgba.getUint8(index + 1),
                  rgba.getUint8(index + 2),
                );
              }

              return (secondary: at(40), inactive: at(150));
            } finally {
              image.dispose();
            }
          });
          final blue = pixels!.secondary.b - pixels.secondary.r;
          expect(blue, secondary == null ? lessThan(0) : greaterThan(0));
          expect(pixels.inactive.r, greaterThan(pixels.inactive.b));
          expect(tester.takeException(), isNull);
        }
      }
    },
  );
}

List<SemanticsData> _sliderSemantics(WidgetTester tester) {
  final result = <SemanticsData>[];
  void visit(SemanticsNode node) {
    final data = node.getSemanticsData();
    if (data.hasFlag(ui.SemanticsFlag.isSlider)) result.add(data);
    node.visitChildren((child) {
      visit(child);
      return true;
    });
  }

  visit(tester.binding.pipelineOwner.semanticsOwner!.rootSemanticsNode!);
  return result;
}

class _SliderGeometry {
  Rect? thumb;
  Offset paintOffset = Offset.zero;
  final ticks = <Offset>{};

  Offset globalPoint(RenderBox parent, Offset point) =>
      parent.localToGlobal(point - paintOffset);
}

class _TrackGeometryProbe extends SliderTrackShape {
  const _TrackGeometryProbe(this.delegate, this.geometry);

  final SliderTrackShape delegate;
  final _SliderGeometry geometry;

  @override
  bool get isRounded => delegate.isRounded;

  @override
  Rect getPreferredRect({
    required RenderBox parentBox,
    Offset offset = Offset.zero,
    required SliderThemeData sliderTheme,
    bool isEnabled = false,
    bool isDiscrete = false,
  }) {
    geometry.paintOffset = offset;
    return delegate.getPreferredRect(
      parentBox: parentBox,
      offset: offset,
      sliderTheme: sliderTheme,
      isEnabled: isEnabled,
      isDiscrete: isDiscrete,
    );
  }

  @override
  void paint(
    PaintingContext context,
    Offset offset, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required TextDirection textDirection,
    required Offset thumbCenter,
    Offset? secondaryOffset,
    bool isDiscrete = false,
    bool isEnabled = false,
  }) {
    geometry.paintOffset = offset;
    delegate.paint(
      context,
      offset,
      parentBox: parentBox,
      sliderTheme: sliderTheme,
      enableAnimation: enableAnimation,
      textDirection: textDirection,
      thumbCenter: thumbCenter,
      secondaryOffset: secondaryOffset,
      isDiscrete: isDiscrete,
      isEnabled: isEnabled,
    );
  }
}

class _ThumbGeometryProbe extends SliderComponentShape {
  const _ThumbGeometryProbe(this.delegate, this.geometry);

  final SliderComponentShape delegate;
  final _SliderGeometry geometry;

  @override
  Size getPreferredSize(bool isEnabled, bool isDiscrete) =>
      delegate.getPreferredSize(isEnabled, isDiscrete);

  @override
  void paint(
    PaintingContext context,
    Offset center, {
    required Animation<double> activationAnimation,
    required Animation<double> enableAnimation,
    required bool isDiscrete,
    required TextPainter labelPainter,
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required TextDirection textDirection,
    required double value,
    required double textScaleFactor,
    required Size sizeWithOverflow,
  }) {
    final size = sliderTheme.thumbSize!.resolve(<WidgetState>{})!;
    geometry.thumb = Rect.fromCenter(
      center: geometry.globalPoint(parentBox, center),
      width: size.width,
      height: size.height,
    );
    delegate.paint(
      context,
      center,
      activationAnimation: activationAnimation,
      enableAnimation: enableAnimation,
      isDiscrete: isDiscrete,
      labelPainter: labelPainter,
      parentBox: parentBox,
      sliderTheme: sliderTheme,
      textDirection: textDirection,
      value: value,
      textScaleFactor: textScaleFactor,
      sizeWithOverflow: sizeWithOverflow,
    );
  }
}

class _TickGeometryProbe extends SliderTickMarkShape {
  const _TickGeometryProbe(this.delegate, this.geometry);

  final SliderTickMarkShape delegate;
  final _SliderGeometry geometry;

  @override
  Size getPreferredSize({
    required SliderThemeData sliderTheme,
    required bool isEnabled,
  }) =>
      delegate.getPreferredSize(sliderTheme: sliderTheme, isEnabled: isEnabled);

  @override
  void paint(
    PaintingContext context,
    Offset center, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required Offset thumbCenter,
    required bool isEnabled,
    required TextDirection textDirection,
  }) {
    geometry.ticks.add(geometry.globalPoint(parentBox, center));
    delegate.paint(
      context,
      center,
      parentBox: parentBox,
      sliderTheme: sliderTheme,
      enableAnimation: enableAnimation,
      thumbCenter: thumbCenter,
      isEnabled: isEnabled,
      textDirection: textDirection,
    );
  }
}
