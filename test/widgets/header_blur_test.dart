import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/aurora.dart';
import 'package:fl_clash/ui/header_blur.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/models.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:fl_clash/ui/sheet_header.dart';
import 'package:fl_clash/ui/view_scope.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/glyph_finders.dart';

void main() {
  testWidgets('only active glass page headers use an untinted blur', (
    tester,
  ) async {
    for (final enabled in [true, false]) {
      await tester.pumpWidget(
        TestApp(
          theme: ThemeData(platform: TargetPlatform.android),
          child: LiquidGlassScope(
            enabled: enabled,
            child: const ViewScope(
              size: Size(390, 800),
              isMobile: true,
              child: CommonScaffold(title: 'Page', body: SizedBox.expand()),
            ),
          ),
        ),
      );
      await _settleShader(tester);
      expect(
        tester.widget<FloatingHeader>(find.byType(FloatingHeader)).blur,
        enabled,
      );
      expect(find.byType(HeaderBlur), enabled ? findsOneWidget : findsNothing);
    }
    await tester.pumpWidget(
      const MaterialApp(
        home: AuroraAppearance(
          enabled: true,
          child: FloatingHeaderBody(
            backgroundColor: Colors.black,
            header: SizedBox(height: 80),
            body: SizedBox.expand(),
          ),
        ),
      ),
    );
    expect(find.byType(HeaderBlur), findsNothing);
    final scrim = tester.widget<DecoratedBox>(
      find.descendant(
        of: find.byType(FloatingHeader),
        matching: find.byType(DecoratedBox),
      ),
    );
    final gradient = (scrim.decoration as BoxDecoration).gradient!;
    expect(gradient.colors.first, Colors.black.withValues(alpha: 0.9));
    expect(gradient.colors.last.a, 0);
  });

  testWidgets('glass search enters and exits without an opaque app bar', (
    tester,
  ) async {
    final searches = <String>[];
    for (final enabled in [true, false]) {
      await tester.pumpWidget(
        TestApp(
          theme: ThemeData(
            platform: TargetPlatform.android,
            brightness: Brightness.dark,
          ),
          child: LiquidGlassScope(
            enabled: enabled,
            child: ViewScope(
              size: const Size(390, 800),
              isMobile: true,
              child: CommonScaffold(
                title: 'Page',
                searchState: AppBarSearchState(onSearch: searches.add),
                body: const SizedBox.expand(),
              ),
            ),
          ),
        ),
      );
      await _settleShader(tester);
      await tester.tap(find.byGlyph(AppGlyphs.search));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsOneWidget);
      final appBar = tester.widget<AppBar>(find.byType(AppBar));
      expect(appBar.forceMaterialTransparency, enabled);
      final barTheme = Theme.of(
        tester.element(find.byType(AppBar)),
      ).appBarTheme;
      expect(barTheme.backgroundColor!.a, enabled ? 0 : 1);
      expect(find.byType(HeaderBlur), enabled ? findsOneWidget : findsNothing);
      final scaffold = tester.widget<Scaffold>(find.byType(Scaffold));
      expect(scaffold.extendBodyBehindAppBar, isTrue);
      await tester.enterText(find.byType(TextField), 'needle');
      expect(searches.last, 'needle');
      await tester.tap(find.byGlyph(AppGlyphs.arrowBack));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
      expect(find.text('Page'), findsOneWidget);
      expect(find.byType(HeaderBlur), enabled ? findsOneWidget : findsNothing);
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('blur decreases downward without darkening or tinting wallpaper', (
    tester,
  ) async {
    final key = GlobalKey();
    for (final brightness in Brightness.values) {
      for (final offset in [0.0, 24.0]) {
        Future<Uint8List> render(bool blur) async {
          await tester.pumpWidget(
            MaterialApp(
              theme: ThemeData(brightness: brightness),
              home: Center(
                child: RepaintBoundary(
                  key: key,
                  child: SizedBox.square(
                    dimension: 256,
                    child: Stack(
                      children: [
                        const Positioned.fill(
                          child: CustomPaint(painter: _Wallpaper()),
                        ),
                        if (blur)
                          Positioned(
                            top: offset,
                            left: 0,
                            right: 0,
                            child: const FloatingHeader(
                              backgroundColor: Colors.black,
                              blur: true,
                              fadeStart: 0.15,
                              overhang: 32,
                              child: SizedBox(height: 128),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          );
          await _settleShader(tester);
          final boundary =
              key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final data = (await image.toByteData())!;
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                await Directory(output).create(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                await File(
                  '$output/header-${brightness.name}-${offset.toInt()}-$blur.png',
                ).writeAsBytes(
                  png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
                );
              }
              return data.buffer.asUint8List(
                data.offsetInBytes,
                data.lengthInBytes,
              );
            } finally {
              image.dispose();
            }
          }))!;
        }

        final baseline = await render(false);
        final blurred = await render(true);
        final top = _rowStats(blurred, offset.toInt() + 16);
        final middle = _rowStats(blurred, offset.toInt() + 124);
        final bottom = _rowStats(blurred, offset.toInt() + 156);
        final sharp = _rowStats(baseline, offset.toInt() + 16);
        expect(top.variance, lessThan(sharp.variance * 0.3));
        expect(middle.variance, greaterThan(top.variance));
        expect(bottom.variance, greaterThan(middle.variance));
        expect(bottom.variance, greaterThan(sharp.variance * 0.9));
        for (var channel = 0; channel < 3; channel++) {
          expect(top.mean[channel], closeTo(sharp.mean[channel], 4));
          expect(bottom.mean[channel], closeTo(sharp.mean[channel], 1));
        }
        for (final y in [offset.toInt() + 180, 240]) {
          final actual = _rowStats(blurred, y);
          final expected = _rowStats(baseline, y);
          expect(actual.mean, expected.mean);
          expect(actual.variance, expected.variance);
        }
        expect(tester.takeException(), isNull);
        expect(tester.binding.hasScheduledFrame, isFalse);
      }
    }
  });
}

Future<void> _settleShader(WidgetTester tester) async {
  await tester.runAsync(() async {
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/header_blur.frag');
    }
  });
  await tester.pumpAndSettle();
}

({List<double> mean, double variance}) _rowStats(Uint8List pixels, int y) {
  final mean = List<double>.filled(3, 0);
  var square = 0.0;
  for (var x = 64; x < 192; x++) {
    final at = (y * 256 + x) * 4;
    for (var channel = 0; channel < 3; channel++) {
      final value = pixels[at + channel].toDouble();
      mean[channel] += value / 128;
      square += value * value / 128;
    }
  }
  return (
    mean: mean,
    variance: square - mean.fold(0.0, (sum, value) => sum + value * value),
  );
}

class _Wallpaper extends CustomPainter {
  const _Wallpaper();

  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()
          ..color = (x ~/ 8).isEven
              ? const Color(0xFFD878A0)
              : const Color(0xFF58B8D0),
      );
    }
  }

  @override
  bool shouldRepaint(_Wallpaper oldDelegate) => false;
}
