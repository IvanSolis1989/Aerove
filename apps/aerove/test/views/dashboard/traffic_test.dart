import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:fl_clash/views/dashboard/widgets/status_layout.dart';
import 'package:mocktail/mocktail.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/dashboard_host.dart';
import '../../helpers/test_app.dart';

Color _pixel(List<int> bytes, Offset point) {
  final index = (point.dy.floor() * 480 + point.dx.floor()) * 4;
  return Color.fromARGB(255, bytes[index], bytes[index + 1], bytes[index + 2]);
}

void main() {
  setUpAll(() async {
    registerFallbackValue(false);
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare();
    }
  });

  testWidgets('zero and unsampled speed retain real zero values', (
    tester,
  ) async {
    await tester.pumpWidget(
      TestApp(
        includeNavigatorKey: false,
        overrides: [trafficsProvider.overrideWithValue(FixedList<Traffic>(30))],
        child: const OutboundTrafficFigures(),
      ),
    );
    await tester.pumpAndSettle();
    final figures = tester.widgetList<GlassText>(find.byType(GlassText));
    expect(figures.map((text) => text.data).where(_isSpeed), [
      '0 B/s',
      '0 B/s',
    ]);
    expect(figures, hasLength(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('a scroll shows the first speed, then every other second, then '
      'the latest at once', (tester) async {
    await tester.pumpWidget(
      const TestApp(
        includeNavigatorKey: false,
        wrapInProviderScope: true,
        child: AuroraMotionActivity(
          enabled: false,
          child: OutboundTrafficFigures(),
        ),
      ),
    );
    await tester.pump();
    final container = ProviderScope.containerOf(
      tester.element(find.byType(OutboundTrafficFigures)),
    );
    String download() => tester
        .widgetList<GlassText>(find.byType(GlassText))
        .map((text) => text.data!)
        .where(_isSpeed)
        .last;
    Future<void> sample(int kilobytes) async {
      await tester.pump(const Duration(seconds: 1));
      container
          .read(trafficsProvider.notifier)
          .addTraffic(Traffic(down: kilobytes * 1024));
      await tester.pump();
    }

    await sample(1);
    expect(download(), '1.0 KB/s');
    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(OutboundTrafficFigures)),
    );
    final shown = <String>[];
    for (var kilobytes = 2; kilobytes <= 7; kilobytes++) {
      await sample(kilobytes);
      shown.add(download());
    }
    expect(shown, [
      '2.0 KB/s',
      '2.0 KB/s',
      '4.0 KB/s',
      '4.0 KB/s',
      '6.0 KB/s',
      '6.0 KB/s',
    ]);
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 349));
    expect(download(), '6.0 KB/s');
    await tester.pump(const Duration(milliseconds: 1));
    expect(download(), '7.0 KB/s');
    expect(container.read(trafficsProvider).list.length, 7);
    expect(tester.takeException(), isNull);
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('status card speed lines take their colour from the field '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(480, 220);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final capture = GlobalKey();
        final colors = auroraColorScheme(brightness);
        final fields = brightness == Brightness.dark
            ? const [Color(0xFF173940), Color(0xFF3B203B)]
            : const [Color(0xFFBDE5EC), Color(0xFFEAD0E8)];
        // The download sets the scale with five early spikes; the upload
        // then runs flat across the middle of the band above the zero line.
        final traffic = FixedList<Traffic>(
          30,
          list: [
            for (var i = 0; i < 30; i++)
              Traffic(up: 8 * 1024, down: (i < 5 ? 32 : 16) * 1024),
          ],
        );
        Future<List<int>> render({
          bool lines = true,
          bool moved = false,
        }) async {
          await tester.pumpWidget(
            TestApp(
              includeNavigatorKey: false,
              theme: ThemeData(colorScheme: colors),
              darkTheme: ThemeData(colorScheme: colors),
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              overrides: [trafficsProvider.overrideWithValue(traffic)],
              child: RepaintBoundary(
                key: capture,
                child: LiquidGlassScope(
                  enabled: true,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: true,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Positioned.fill(
                          child: LiquidGlassColorField(
                            child: CustomPaint(painter: _Field(fields, moved)),
                          ),
                        ),
                        if (lines)
                          const Positioned(
                            left: 0,
                            top: 0,
                            width: 480,
                            height: 200,
                            child: DashboardDensityScope(
                              density: DashboardDensity.mobile,
                              scale: 1,
                              child: OutboundTrafficBackground(running: true),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
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

        final field = await render(lines: false);
        final actual = await render();
        final moved = await render(moved: true);
        for (final x in [150, 360]) {
          // The download plateau lies near y 71 and the upload near y 105.
          for (final (line, from, to) in [
            ('download', 94, 108),
            ('upload', 144, 158),
          ]) {
            var row = from;
            var change = -1.0;
            for (var y = from; y <= to; y++) {
              final a = _pixel(actual, Offset(x + .5, y + .5));
              final b = _pixel(field, Offset(x + .5, y + .5));
              final delta =
                  (a.r - b.r).abs() + (a.g - b.g).abs() + (a.b - b.b).abs();
              if (delta > change) {
                change = delta;
                row = y;
              }
            }
            final point = Offset(x + .5, row + .5);
            final stroke = _pixel(actual, point);
            final background = _pixel(field, point);
            expect(stroke, isNot(background), reason: '$line at $x');
            expect(
              _pixel(moved, point),
              isNot(stroke),
              reason: '$line at $x follows the field',
            );
            if (line == 'download') {
              expect(
                _saturation(stroke),
                greaterThanOrEqualTo(_saturation(background) + 0.05),
                reason: '$line at $x is $stroke over $background',
              );
            }
          }
          // Outside the full-height chart the field remains unchanged.
          for (var y = 204; y < 220; y += 4) {
            final below = Offset(x + .5, y + .5);
            expect(
              _pixel(actual, below),
              _pixel(field, below),
              reason: '$x $y',
            );
          }
        }
        expect(tester.takeException(), isNull);
      }, skip: !ui.ImageFilter.isShaderFilterSupported);
    }
  }

  testWidgets(
    'on glass the running start bubble repaints itself, not the status card',
    (tester) async {
      await expectOnlyTheBubbleDrifts(
        await pumpDashboardHost(tester, glass: true),
      );
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );

  testWidgets('the speed lines add one ink mask no larger than their band', (
    tester,
  ) async {
    final host = await pumpDashboardHost(tester, glass: true);
    // A capture from before the card settled would measure an older band.
    LiquidGlassHost.debugLastInkCapture = null;
    await host.second();
    final capture = LiquidGlassHost.debugLastInkCapture!;
    final curveInks = find.descendant(
      of: find.byType(OutboundTrafficBackground),
      matching: find.byType(GlassInk),
    );
    expect(curveInks, findsOneWidget);
    final curve = (curveInks.evaluate().single.renderObject! as RenderBox).size;
    final layers = <LiquidGlassInkLayer>[];
    void visit(Layer layer) {
      if (layer is LiquidGlassInkLayer) layers.add(layer);
      if (layer is ContainerLayer) {
        for (
          var child = layer.firstChild;
          child != null;
          child = child.nextSibling
        ) {
          visit(child);
        }
      }
    }

    visit(tester.binding.renderViews.first.debugLayer!);
    const ratio = 3.0;
    int pixels(Size size) => (size.width * size.height * ratio * ratio).ceil();
    expect(capture.masks, layers.length);
    expect(
      capture.maskPixels,
      layers.fold(0, (sum, layer) => sum + pixels(layer.size)),
    );
    final curveLayers = [
      for (final layer in layers)
        if (layer.size == curve) layer,
    ];
    expect(curveLayers, hasLength(1), reason: 'the speed lines take one mask');
    final layout = tester.renderObject<RenderStatusCardLayout>(
      find.byType(StatusCardLayout),
    );
    final band = layout.size.height - 1;
    expect(curve.width, layout.size.width);
    expect(curve.height, closeTo(band, 0.01));
    expect(
      pixels(curveLayers.single.size),
      lessThanOrEqualTo(pixels(Size(layout.size.width, band))),
    );
  }, skip: !ui.ImageFilter.isShaderFilterSupported);

  testWidgets('on glass with Aurora the fill stays continuous behind text', (
    tester,
  ) async {
    final host = await pumpDashboardHost(tester, glass: true);
    final card = find
        .ancestor(
          of: find.byType(CurrentOutboundCard),
          matching: find.byType(RepaintBoundary),
        )
        .first;
    Future<List<int>> show(List<Traffic> samples) async {
      final traffics = host.container.read(trafficsProvider.notifier);
      for (final sample in samples) {
        traffics.addTraffic(sample);
      }
      host.container.read(totalTrafficProvider.notifier).value = const Traffic(
        up: 1,
        down: 2,
      );
      for (var frame = 0; frame < 30; frame++) {
        await tester.pump(const Duration(microseconds: 16667));
      }
      final boundary = tester.renderObject<RenderRepaintBoundary>(card);
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

    final quiet = await show([for (var i = 0; i < 30; i++) const Traffic()]);
    final high = await show([
      for (var i = 0; i < 30; i++)
        i == 29
            ? const Traffic()
            : const Traffic(up: 700 << 20, down: 1000 << 20),
    ]);
    final box = tester.renderObject<RenderBox>(card);
    final width = box.size.width.round();
    final origin = box.localToGlobal(Offset.zero);
    final texts = <Rect>[];
    for (final element
        in find
            .descendant(
              of: find.byType(CurrentOutboundCard),
              matching: find.byType(RichText),
            )
            .evaluate()) {
      final paragraph = element.renderObject! as RenderParagraph;
      final text = paragraph.text.toPlainText();
      if (text.trim().isEmpty || text.runes.any((r) => r >= 0x1F1E6)) continue;
      for (final box in paragraph.getBoxesForSelection(
        TextSelection(baseOffset: 0, extentOffset: text.length),
      )) {
        texts.add(
          MatrixUtils.transformRect(
            paragraph.getTransformTo(null),
            box.toRect(),
          ).shift(-origin),
        );
      }
    }
    texts.add(
      tester.getRect(find.byType(OutboundTrafficFigures)).shift(-origin),
    );
    var changed = 0;
    var changedInTextBounds = 0;
    for (var y = 0; y < box.size.height.round(); y++) {
      for (var x = 0; x < width; x++) {
        final index = (y * width + x) * 4;
        var same = true;
        for (var channel = 0; channel < 4; channel++) {
          if (quiet[index + channel] != high[index + channel]) same = false;
        }
        if (same) continue;
        changed++;
        final point = Offset(x + 0.5, y + 0.5);
        if (texts.any((text) => text.contains(point))) changedInTextBounds++;
      }
    }
    expect(changed, greaterThan(200));
    expect(
      changedInTextBounds,
      greaterThan(10),
      reason: 'continuous fill must not have text-shaped panels',
    );
    final masks = <ShaderMaskLayer>[];
    void visit(Layer layer) {
      if (layer is ShaderMaskLayer) masks.add(layer);
      if (layer is ContainerLayer) {
        for (
          var child = layer.firstChild;
          child != null;
          child = child.nextSibling
        ) {
          visit(child);
        }
      }
    }

    visit(tester.renderObject<RenderRepaintBoundary>(card).debugLayer!);
    expect(masks, isEmpty);
  }, skip: !ui.ImageFilter.isShaderFilterSupported);
}

bool _isSpeed(String? text) => text?.endsWith('/s') ?? false;

double _saturation(Color color) {
  final high = [color.r, color.g, color.b].reduce((a, b) => a > b ? a : b);
  final low = [color.r, color.g, color.b].reduce((a, b) => a < b ? a : b);
  return high == 0 ? 0 : (high - low) / high;
}

class _Field extends CustomPainter {
  const _Field(this.colors, this.moved);
  final List<Color> colors;
  final bool moved;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = colors[moved ? 1 : 0]);
    canvas.drawRect(
      Rect.fromLTWH(size.width / 2, 0, size.width / 2, size.height),
      Paint()..color = colors[moved ? 0 : 1],
    );
  }

  @override
  bool shouldRepaint(_Field oldDelegate) => oldDelegate.moved != moved;
}
