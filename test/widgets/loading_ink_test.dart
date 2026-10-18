import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/loading.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
  });

  testWidgets(
    'busy action uses icon ink and preserves fallback and state colors',
    (tester) async {
      final semantics = tester.ensureSemantics();
      addTearDown(semantics.dispose);
      final colors = ColorScheme.fromSeed(seedColor: Colors.blue);
      for (final enabled in [false, true]) {
        for (final highContrast in [false, true]) {
          for (final explicit in [
            null,
            colors.error,
            Colors.green,
            Colors.orange,
          ]) {
            await tester.pumpWidget(
              MaterialApp(
                theme: ThemeData(colorScheme: colors),
                home: MediaQuery(
                  data: MediaQueryData(highContrast: highContrast),
                  child: LiquidGlassScope(
                    enabled: enabled,
                    child: Center(
                      child: CommonCircleLoading(
                        color: explicit,
                        semanticLabel: 'Updating',
                      ),
                    ),
                  ),
                ),
              ),
            );
            final ink = tester.widget<GlassInk>(find.byType(GlassInk));
            expect(ink.role, GlassInkRole.icon);
            expect(
              ink.source,
              explicit ??
                  (enabled && !highContrast
                      ? colors.onSurface
                      : colors.primary),
            );
            expect(find.bySemanticsLabel('Updating'), findsOneWidget);
          }
        }
      }
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('busy action follows moving background ${quality.name}', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(160, 100);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.runAsync(GlassInk.prepare);
      final busy = ValueNotifier(false);
      final leading = ValueNotifier(160.0);
      final capture = GlobalKey();
      final spinner = GlobalKey();
      final colors = ColorScheme.fromSeed(seedColor: Colors.blue);
      const blue = Color(0xFFBDE5EC);
      const pink = Color(0xFFEAD0E8);
      Future<void> mount({
        bool enabled = true,
        bool highContrast = false,
        Color? color,
      }) async {
        await tester.pumpWidget(
          MaterialApp(
            theme: ThemeData(colorScheme: colors),
            home: MediaQuery(
              data: MediaQueryData(highContrast: highContrast),
              child: RepaintBoundary(
                key: capture,
                child: LiquidGlassScope(
                  enabled: enabled,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: enabled,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        LiquidGlassColorField(
                          child: ValueListenableBuilder<double>(
                            valueListenable: leading,
                            builder: (_, value, _) => CustomPaint(
                              painter: _MovingField(value, blue, pink),
                            ),
                          ),
                        ),
                        Center(
                          child: TickerMode(
                            enabled: false,
                            child: ValueListenableBuilder<bool>(
                              valueListenable: busy,
                              builder: (_, value, _) => IconButton(
                                tooltip: 'Refresh',
                                onPressed: () => busy.value = true,
                                icon: value
                                    ? CommonCircleLoading(
                                        key: spinner,
                                        size: 48,
                                        color: color,
                                        semanticLabel: 'Updating',
                                      )
                                    : const GlyphIcon(AppGlyphs.refresh),
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
        await tester.pump();
        await tester.pump();
      }

      Future<({List<int> bytes, int width})> frame(String tag) async {
        final boundary =
            capture.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        return (await tester.runAsync(() async {
          final image = await boundary.toImage();
          try {
            final data = (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!;
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              Directory(output).createSync(recursive: true);
              final png = (await image.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              File(
                '$output/loading-ink-${quality.name}-$tag.png',
              ).writeAsBytesSync(png.buffer.asUint8List());
            }
            return (bytes: data.buffer.asUint8List(), width: image.width);
          } finally {
            image.dispose();
          }
        }))!;
      }

      try {
        await mount(enabled: false);
        expect(find.byType(CommonCircleLoading), findsNothing);
        await tester.tap(find.byTooltip('Refresh'));
        await tester.pump();
        expect(find.byType(CommonCircleLoading), findsOneWidget);
        final bounds = tester.getRect(find.byKey(spinner));
        final point = bounds.topLeft + const Offset(21, 6);
        final original = await frame('none');
        expect(_pixel(original, point), colors.primary);
        await mount(highContrast: true);
        expect(_pixel(await frame('high-contrast'), point), colors.primary);
        for (final state in [colors.error, Colors.green, Colors.orange]) {
          await mount(color: state);
          expect(
            _pixel(await frame('state-${state.toARGB32()}'), point),
            state,
          );
        }
        await mount();
        final samples = <Color>[];
        for (final offset in [160.0, 80.0, 0.0]) {
          leading.value = offset;
          await tester.pump();
          await tester.pump();
          expect(tester.getRect(find.byKey(spinner)), bounds);
          final pixel = _pixel(await frame('moving-$offset'), point);
          final background = point.dx < offset ? blue : pink;
          final hue = HSVColor.fromColor(pixel);
          final baseHue = HSVColor.fromColor(background);
          final difference = (hue.hue - baseHue.hue).abs();
          expect(math.min(difference, 360 - difference), lessThan(20));
          expect(hue.saturation, greaterThan(baseHue.saturation + 0.10));
          final light = pixel.computeLuminance();
          final under = background.computeLuminance();
          expect(
            (math.max(light, under) + 0.05) / (math.min(light, under) + 0.05),
            greaterThanOrEqualTo(3),
          );
          samples.add(pixel);
        }
        expect(samples.first, isNot(samples.last));
        expect(LiquidGlassHost.debugLastInkCapture!.masks, 1);
        expect(find.byType(LiquidGlassHost), findsOneWidget);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        busy.dispose();
        leading.dispose();
      }
    }, skip: !ui.ImageFilter.isShaderFilterSupported);
  }
}

Color _pixel(({List<int> bytes, int width}) image, Offset point) {
  final index = (point.dy.floor() * image.width + point.dx.floor()) * 4;
  return Color.fromARGB(
    image.bytes[index + 3],
    image.bytes[index],
    image.bytes[index + 1],
    image.bytes[index + 2],
  );
}

class _MovingField extends CustomPainter {
  const _MovingField(this.leading, this.first, this.second);

  final double leading;
  final Color first;
  final Color second;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = first);
    canvas.drawRect(
      Rect.fromLTWH(leading, 0, size.width, size.height),
      Paint()..color = second,
    );
  }

  @override
  bool shouldRepaint(_MovingField oldDelegate) =>
      leading != oldDelegate.leading ||
      first != oldDelegate.first ||
      second != oldDelegate.second;
}
