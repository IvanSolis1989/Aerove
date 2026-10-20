import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/chip.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/view_scope.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _capture = ValueKey('body-blur-capture');
const _bodyRect = Rect.fromLTWH(32, 32, 120, 64);
const _headerRect = Rect.fromLTWH(192, 32, 96, 64);
const _dockRect = Rect.fromLTWH(96, 144, 128, 48);
const _optical = LiquidGlassStyle(
  tint: Colors.transparent,
  blur: 0,
  refraction: 36,
  bevel: 16,
  dispersion: 5,
  saturation: 1.7,
  brightness: 1.2,
  rim: 0.6,
  hairline: 0.6,
  rimBlur: 2,
);

class _Pattern extends CustomPainter {
  const _Pattern(this.dark, this.phase);
  final bool dark;
  final int phase;

  @override
  void paint(Canvas canvas, Size size) {
    final colors = dark
        ? [const Color(0xFF183450), const Color(0xFF784830)]
        : [const Color(0xFF4068B0), const Color(0xFFE0C880)];
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()..color = colors[((x ~/ 8) + phase) % 2],
      );
    }
  }

  @override
  bool shouldRepaint(_Pattern oldDelegate) =>
      dark != oldDelegate.dark || phase != oldDelegate.phase;
}

Widget _scene({
  bool direct = false,
  bool native = false,
  bool body = true,
  bool dark = false,
  bool enabled = true,
  bool highContrast = false,
  bool frozen = false,
  int phase = 0,
  LiquidGlassQuality quality = LiquidGlassQuality.standard,
}) => MaterialApp(
  theme: ThemeData(brightness: dark ? Brightness.dark : Brightness.light),
  home: MediaQuery(
    data: MediaQueryData(highContrast: highContrast),
    child: RepaintBoundary(
      key: _capture,
      child: LiquidGlassScope(
        enabled: enabled && !highContrast,
        quality: quality,
        child: LiquidGlassHost(
          enabled: enabled && !highContrast,
          wallpaperOnly: true,
          child: Builder(
            builder: (context) {
              Widget pane = direct
                  ? const _DirectPane()
                  : const LiquidGlass(
                      style: _optical,
                      glow: LiquidGlassGlow(
                        center: Offset(36, 24),
                        reach: 80,
                        strength: 0.6,
                      ),
                      clipper: ShapeBorderClipper(shape: AppShape.none),
                    );
              if (native) {
                pane = ClipRect(
                  child: BackdropFilter(
                    filter: ui.ImageFilter.blur(
                      sigmaX: enabled && !highContrast ? 2.5 : 0,
                      sigmaY: enabled && !highContrast ? 2.5 : 0,
                    ),
                    child: const SizedBox.expand(),
                  ),
                );
              }
              if (body) pane = LiquidGlassBody(child: pane);
              return Stack(
                fit: StackFit.expand,
                children: [
                  LiquidGlassColorField(
                    child: LiquidGlassColorSource(
                      child: CustomPaint(painter: _Pattern(dark, phase)),
                    ),
                  ),
                  Positioned.fromRect(rect: _bodyRect, child: pane),
                  Positioned.fromRect(
                    rect: _headerRect,
                    child: LiquidGlass(
                      style: LiquidGlassStyle.regular(
                        Theme.of(context).colorScheme,
                      ),
                    ),
                  ),
                  Positioned.fromRect(
                    rect: _dockRect,
                    child: LiquidGlass(
                      style: LiquidGlassStyle.lens(
                        Theme.of(context).colorScheme,
                      ),
                    ),
                  ),
                  if (frozen)
                    Positioned.fromRect(
                      rect: _bodyRect,
                      child: const LiquidGlassOcclusion(
                        shape: AppShape.none,
                        child: SizedBox.expand(),
                      ),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    ),
  ),
);

class _DirectPane extends LeafRenderObjectWidget {
  const _DirectPane();

  @override
  RenderObject createRenderObject(BuildContext context) => _DirectPaneBox();
}

class _DirectPaneBox extends RenderBox {
  late final _pane = LiquidGlassPane(markNeedsPaint);

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  bool get sizedByParent => true;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  @override
  void paint(PaintingContext context, Offset offset) => _pane.paint(
    context,
    offset & size,
    shape: const LiquidGlassCorners(AppRadius.none),
    style: _optical,
  );

  @override
  void dispose() {
    _pane.dispose();
    super.dispose();
  }
}

Future<Uint8List> _render(WidgetTester tester, Widget scene) async {
  await tester.pumpWidget(scene);
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  for (var frame = 0; frame < 4; frame++) {
    tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
    await tester.pump(const Duration(milliseconds: 16));
  }
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_capture),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

int _difference(Uint8List a, Uint8List b, Rect rect) {
  var maximum = 0;
  for (var y = rect.top.toInt(); y < rect.bottom; y++) {
    for (var x = rect.left.toInt(); x < rect.right; x++) {
      for (var channel = 0; channel < 4; channel++) {
        final index = (y * 320 + x) * 4 + channel;
        final delta = (a[index] - b[index]).abs();
        if (delta > maximum) maximum = delta;
      }
    }
  }
  return maximum;
}

Map<String, Object> _frozenDifference(Uint8List before, Uint8List after) {
  var maximum = 0;
  var worst = ((_bodyRect.top + 4).toInt() * 320 +
      (_bodyRect.left + 4).toInt()) * 4;
  for (var y = (_bodyRect.top + 4).toInt(); y < _bodyRect.bottom - 4; y++) {
    for (var x = (_bodyRect.left + 4).toInt(); x < _bodyRect.right - 4; x++) {
      for (var channel = 0; channel < 4; channel++) {
        final index = (y * 320 + x) * 4 + channel;
        final delta = (after[index] - before[index]).abs();
        if (delta > maximum) {
          maximum = delta;
          worst = index;
        }
      }
    }
  }
  final pixel = worst ~/ 4 * 4;
  return {
    'maximum': maximum,
    'x': worst ~/ 4 % 320,
    'y': worst ~/ 4 ~/ 320,
    'channel': worst % 4,
    'beforeRGBA': before.sublist(pixel, pixel + 4),
    'afterRGBA': after.sublist(pixel, pixel + 4),
  };
}

void main() {
  setUpAll(() async {
    await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
  });

  for (final dark in [false, true]) {
    for (final direct in [false, true]) {
      testWidgets(
        'Standard body matches native blur dark=$dark direct=$direct',
        (tester) async {
          tester.view.physicalSize = const Size(320, 224);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final expected = await _render(
            tester,
            _scene(native: true, dark: dark),
          );
          final actual = await _render(
            tester,
            _scene(direct: direct, dark: dark),
          );
          expect(
            _difference(actual, expected, _bodyRect.deflate(2)),
            lessThanOrEqualTo(2),
          );
          expect(LiquidGlassHost.debugLastBodyCapture, hasLength(1));
          expect(LiquidGlassHost.debugLastBodyCapture.single.blur, 2.5);
          final flat = await _render(
            tester,
            _scene(native: true, dark: dark, enabled: false),
          );
          expect(
            _difference(actual, flat, _bodyRect.deflate(8)),
            greaterThan(8),
          );
          final outside = await _render(
            tester,
            _scene(body: false, direct: direct, dark: dark),
          );
          expect(
            _difference(actual, outside, _headerRect.deflate(2)),
            lessThanOrEqualTo(2),
          );
          expect(
            _difference(actual, outside, _dockRect.deflate(2)),
            lessThanOrEqualTo(2),
          );
          final ultra = await _render(
            tester,
            _scene(
              direct: direct,
              dark: dark,
              quality: LiquidGlassQuality.ultra,
            ),
          );
          expect(LiquidGlassHost.debugLastBodyCapture, isEmpty);
          final unmarkedUltra = await _render(
            tester,
            _scene(
              body: false,
              direct: direct,
              dark: dark,
              quality: LiquidGlassQuality.ultra,
            ),
          );
          expect(_difference(ultra, unmarkedUltra, _bodyRect), 0);
          if (ui.ImageFilter.isShaderFilterSupported) {
            expect(
              _difference(ultra, actual, _bodyRect.deflate(2)),
              greaterThan(8),
            );
          }
          final restored = await _render(
            tester,
            _scene(direct: direct, dark: dark),
          );
          expect(_difference(restored, actual, _bodyRect), 0);
          for (final highContrast in [false, true]) {
            final fallback = await _render(
              tester,
              _scene(
                direct: direct,
                dark: dark,
                enabled: highContrast,
                highContrast: highContrast,
              ),
            );
            final unmarked = await _render(
              tester,
              _scene(
                body: false,
                direct: direct,
                dark: dark,
                enabled: highContrast,
                highContrast: highContrast,
              ),
            );
            expect(_difference(fallback, unmarked, _bodyRect), 0);
          }
          await tester.pumpWidget(const SizedBox.shrink());
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  testWidgets(
    'Standard body freeze retains the same blur and thaws on dismissal',
    (tester) async {
      tester.view.physicalSize = const Size(320, 224);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final live = await _render(tester, _scene());
      final frozen = await _render(tester, _scene(frozen: true));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
      final repeated = await _render(tester, _scene(frozen: true));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
      final changed = await _render(tester, _scene(frozen: true, phase: 1));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
      final settled = await _render(tester, _scene(frozen: true, phase: 1));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 1);
      final diagnostics = {
        'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
        'unchangedSource': _frozenDifference(frozen, repeated),
        'changedSource': _frozenDifference(frozen, changed),
        'settledChangedSource': _frozenDifference(changed, settled),
        'outsideChange': _difference(
          frozen,
          changed,
          const Rect.fromLTWH(8, 8, 8, 8),
        ),
        'occludedPanes': LiquidGlassHost.debugLastCapture!.occludedPanes,
      };
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && output.isNotEmpty) {
        Directory(output).createSync(recursive: true);
        File('$output/glass-body-blur-freeze.json').writeAsStringSync(
          jsonEncode(diagnostics),
        );
      }
      for (final reference in [repeated, changed, settled]) {
        expect(
          _difference(frozen, reference, _bodyRect.deflate(4)),
          lessThanOrEqualTo(2),
          reason: jsonEncode(diagnostics),
        );
      }
      expect(diagnostics['outsideChange'], greaterThan(8));
      final thawed = await _render(tester, _scene(phase: 1));
      expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
      final expected = await _render(tester, _scene(native: true, phase: 1));
      expect(
        _difference(thawed, expected, _bodyRect.deflate(2)),
        lessThanOrEqualTo(2),
      );
      expect(_difference(thawed, live, _bodyRect.deflate(8)), greaterThan(8));
      await tester.pumpWidget(const SizedBox.shrink());
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'CommonScaffold marks content and keyword chips but excludes its header',
    (tester) async {
      const bodyKey = ValueKey('marked-scaffold-body');
      await tester.pumpWidget(
        const TestApp(
          child: ViewScope(
            size: Size(800, 600),
            isMobile: false,
            child: CommonScaffold(
              title: 'Body',
              body: SizedBox(key: bodyKey),
            ),
          ),
        ),
      );
      expect(
        find.ancestor(
          of: find.byKey(bodyKey),
          matching: find.byType(LiquidGlassBody),
        ),
        findsOneWidget,
      );
      tester
          .state<CommonScaffoldState>(find.byType(CommonScaffold))
          .addKeyword('central keyword');
      await tester.pump();
      expect(find.byType(CommonChip), findsOneWidget);
      expect(
        find.ancestor(
          of: find.byType(CommonChip),
          matching: find.byType(LiquidGlassBody),
        ),
        findsOneWidget,
      );
      expect(
        find.ancestor(
          of: find.text('Body'),
          matching: find.byType(LiquidGlassBody),
        ),
        findsNothing,
      );
      expect(find.byType(LiquidGlassHost), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(tester.takeException(), isNull);
    },
  );
}
