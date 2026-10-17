import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
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
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/glyph_finders.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(
        ui.ImageFilter.isShaderFilterSupported,
        isTrue,
        reason: 'The Impeller job must not silently use Skia.',
      );
    }
    final font = FontLoader('HeaderProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
  });
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
      await tester.pumpAndSettle();
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
      await tester.pumpAndSettle();
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

  testWidgets('native blur fades to exact transparency at the bar midpoint', (
    tester,
  ) async {
    for (final dpr in [1.0, 3.0]) {
      tester.view.devicePixelRatio = dpr;
      tester.view.physicalSize = Size(320 * dpr, 320 * dpr);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      for (final offset in [
        Offset.zero,
        const Offset(17, 24),
        const Offset(17 + 2 / 3, 24 + 1 / 3),
      ]) {
        for (final brightness in Brightness.values) {
          final probe = _Probe(tester, dpr, offset, brightness);
          final baseline = await probe.render(_Layer.none);
          final gaussian = await probe.render(_Layer.gaussian);
          final repeated = await probe.render(_Layer.gaussian);
          final actual = await probe.render(_Layer.header);
          final expected = Uint8List.fromList(baseline);
          final error = Uint8List(baseline.length);
          var noise = 0;
          for (var i = 0; i < gaussian.length; i++) {
            noise = math.max(noise, (gaussian[i] - repeated[i]).abs());
          }
          var maxError = 0;
          var changedTop = 0;
          var bottomMismatches = 0;
          final rowError = List<int>.filled(probe.width, 0);
          for (var y = 0; y < probe.width; y++) {
            final localY = (y + 0.5) / dpr - offset.dy;
            final t = (localY / 64).clamp(0.0, 1.0);
            final alpha = localY < 0 ? 0.0 : 1 - t * t * (3 - 2 * t);
            for (var x = 0; x < probe.width; x++) {
              final localX = (x + 0.5) / dpr - offset.dx;
              final inside = localX >= 0 && localX < 224;
              for (var c = 0; c < 4; c++) {
                final i = (y * probe.width + x) * 4 + c;
                if (inside && localY >= 0 && localY < 64) {
                  expected[i] =
                      (baseline[i] * (1 - alpha) + gaussian[i] * alpha).round();
                }
                final delta = (actual[i] - expected[i]).abs();
                error[i] = c == 3 ? 255 : math.min(255, delta * 16);
                if (localY >= 64 && actual[i] != baseline[i]) {
                  bottomMismatches++;
                }
                if (inside &&
                    localY >= 0 &&
                    localY < 8 &&
                    actual[i] != baseline[i]) {
                  changedTop++;
                }
                // Fractional clip coverage is separate from the alpha oracle.
                if (localX >= 1 && localX < 223 && localY >= 1 && localY < 64) {
                  maxError = math.max(maxError, delta);
                  rowError[y] = math.max(rowError[y], delta);
                }
              }
            }
          }
          // Two 8-bit blend roundings plus smoothstep's 16-segment error:
          // max |f''| * (1/16)^2 / 8 * 255 < 1 channel unit.
          final quantizationBound = (2 + 6 * 255 / (8 * 16 * 16)).ceil();
          await probe.export(
            {
              'baseline': baseline,
              'fullGaussian': gaussian,
              'masked': actual,
              'expected': expected,
              'error-x16': error,
            },
            {
              'shaderFilterSupported': ui.ImageFilter.isShaderFilterSupported,
              'requireImpeller':
                  Platform.environment['XCLASH_REQUIRE_IMPELLER'],
              'backendEvidence':
                  'Flutter offscreen; not Android GPU validation',
              'dpr': dpr,
              'offset': [offset.dx, offset.dy],
              'brightness': brightness.name,
              'referenceRepeatNoise': noise,
              'quantizationBound': quantizationBound,
              'maxError': maxError,
              'rowError': rowError,
              'bottomMismatches': bottomMismatches,
            },
          );
          expect(changedTop, greaterThan(0));
          expect(
            bottomMismatches,
            0,
            reason: 'The entire lower half and overhang must equal baseline.',
          );
          expect(
            maxError,
            lessThanOrEqualTo(noise + quantizationBound),
            reason: 'Gaussian alpha mixing must not create texture ghosts.',
          );
          expect(tester.takeException(), isNull);
        }
      }
    }
  });

  testWidgets(
    'solid backdrops keep their color and header controls stay sharp',
    (tester) async {
      for (final color in [
        const Color(0xFF808080),
        const Color(0xFFE73019),
        const Color(0xFF19CBE7),
      ]) {
        final probe = _Probe(
          tester,
          1,
          const Offset(17, 24),
          Brightness.light,
          color: color,
        );
        final baseline = await probe.render(_Layer.none);
        final gaussian = await probe.render(_Layer.gaussian);
        final repeated = await probe.render(_Layer.gaussian);
        final actual = await probe.render(_Layer.header);
        final error = Uint8List(actual.length);
        final histogram = <String, int>{};
        final channelMax = List<int>.filled(3, 0);
        final channelSignedSum = List<int>.filled(3, 0);
        var noise = 0;
        var gaussianDeviation = 0;
        var maxDeviation = 0;
        var bottomMismatches = 0;
        var alphaMismatches = 0;
        for (var i = 0; i < actual.length; i++) {
          noise = math.max(noise, (gaussian[i] - repeated[i]).abs());
          gaussianDeviation = math.max(
            gaussianDeviation,
            (gaussian[i] - baseline[i]).abs(),
          );
          final delta = actual[i] - baseline[i];
          final channel = i % 4;
          error[i] = channel == 3 ? 255 : math.min(255, delta.abs() * 64);
          maxDeviation = math.max(maxDeviation, delta.abs());
          if (channel < 3) {
            channelMax[channel] = math.max(channelMax[channel], delta.abs());
            channelSignedSum[channel] += delta;
            histogram['$delta'] = (histogram['$delta'] ?? 0) + 1;
          } else if (delta != 0) {
            alphaMismatches++;
          }
          if (i ~/ (probe.width * 4) >= 88 && delta != 0) {
            bottomMismatches++;
          }
        }
        // Premultiplication and srcOver each round to the nearest 8-bit value;
        // their combined error is at most one channel unit on a solid field.
        final bound = noise + gaussianDeviation + 1;
        await probe.export(
          {
            'baseline': baseline,
            'fullGaussian': gaussian,
            'masked': actual,
            'error-x64': error,
          },
          {
            'kind': 'solid-color',
            'color': color.toARGB32().toRadixString(16),
            'shaderFilterSupported': ui.ImageFilter.isShaderFilterSupported,
            'requireImpeller': Platform.environment['XCLASH_REQUIRE_IMPELLER'],
            'backendEvidence': 'Flutter offscreen; not Android GPU validation',
            'dpr': probe.dpr,
            'offset': [probe.offset.dx, probe.offset.dy],
            'referenceRepeatNoise': noise,
            'gaussianDeviation': gaussianDeviation,
            'roundingBound': bound,
            'maxDeviation': maxDeviation,
            'channelMaxDeviation': channelMax,
            'channelSignedSum': channelSignedSum,
            'signedHistogram': histogram,
            'bottomMismatches': bottomMismatches,
            'alphaMismatches': alphaMismatches,
          },
        );
        expect(gaussianDeviation, lessThanOrEqualTo(noise + 1),
            reason: 'A normalized Gaussian preserves a constant field.');
        expect(
          maxDeviation,
          lessThanOrEqualTo(bound),
          reason: 'Solid color may differ only by native 8-bit blend rounding.',
        );
        expect(bottomMismatches, 0);
        expect(alphaMismatches, 0);
      }
      var taps = 0;
      final probe = _Probe(tester, 1, const Offset(17, 24), Brightness.dark);
      Widget foreground() => Row(
        children: [
          const Text(
            'Header title',
            style: TextStyle(fontFamily: 'HeaderProbe', color: Colors.white),
          ),
          GestureDetector(
            key: const ValueKey('header-action'),
            behavior: HitTestBehavior.opaque,
            onTap: () => taps++,
            child: const SizedBox(
              width: 40,
              height: 40,
              child: Text(
                'GO',
                style: TextStyle(
                  fontFamily: 'HeaderProbe',
                  color: Colors.white,
                ),
              ),
            ),
          ),
        ],
      );
      final baseline = await probe.render(
        _Layer.none,
        foreground: foreground(),
      );
      final actual = await probe.render(
        _Layer.header,
        foreground: foreground(),
      );
      await tester.tap(find.byKey(const ValueKey('header-action')));
      expect(taps, 1);
      // Fully opaque foreground pixels must survive the backdrop filter.
      var foregroundPixels = 0;
      for (var i = 0; i < baseline.length; i += 4) {
        if (baseline[i] == 255 &&
            baseline[i + 1] == 255 &&
            baseline[i + 2] == 255) {
          foregroundPixels++;
          expect(actual.sublist(i, i + 4), baseline.sublist(i, i + 4));
        }
      }
      expect(foregroundPixels, greaterThan(0));
      expect(tester.takeException(), isNull);
    },
  );
}

enum _Layer { none, gaussian, header }

class _Probe {
  _Probe(this.tester, this.dpr, this.offset, this.brightness, {this.color});

  final WidgetTester tester;
  final double dpr;
  final Offset offset;
  final Brightness brightness;
  final Color? color;
  final key = GlobalKey();
  int get width => (256 * dpr).round();

  Future<Uint8List> render(_Layer layer, {Widget? foreground}) async {
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
                  Positioned.fill(
                    child: color == null
                        ? CustomPaint(painter: _Wallpaper(brightness))
                        : ColoredBox(color: color!),
                  ),
                  Positioned(
                    top: offset.dy,
                    left: offset.dx,
                    width: 224,
                    child: layer == _Layer.header
                        ? FloatingHeader(
                            backgroundColor: Colors.black,
                            blur: true,
                            overhang: 32,
                            child: SizedBox(height: 128, child: foreground),
                          )
                        : Stack(
                            children: [
                              if (layer == _Layer.gaussian)
                                SizedBox(
                                  height: 64,
                                  child: ClipRect(
                                    child: BackdropFilter(
                                      filter: ui.ImageFilter.blur(
                                        sigmaX: 12,
                                        sigmaY: 12,
                                        tileMode: TileMode.clamp,
                                      ),
                                      blendMode: BlendMode.srcOver,
                                      child: const SizedBox.expand(),
                                    ),
                                  ),
                                ),
                              SizedBox(height: 128, child: foreground),
                            ],
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
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    return (await tester.runAsync(() async {
      final image = await boundary.toImage(pixelRatio: dpr);
      try {
        final data = (await image.toByteData())!;
        return Uint8List.fromList(
          data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        );
      } finally {
        image.dispose();
      }
    }))!;
  }

  Future<void> export(
    Map<String, Uint8List> pictures,
    Map<String, Object?> facts,
  ) async {
    final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (output == null) return;
    await tester.runAsync(() async {
      final name =
          'header-${brightness.name}-dpr$dpr-${offset.dx}-${offset.dy}'
          '${color == null ? '' : '-solid-${color!.toARGB32().toRadixString(16)}'}';
      await Directory(output).create(recursive: true);
      for (final entry in pictures.entries) {
        final buffer = await ui.ImmutableBuffer.fromUint8List(entry.value);
        final descriptor = ui.ImageDescriptor.raw(
          buffer,
          width: width,
          height: width,
          pixelFormat: ui.PixelFormat.rgba8888,
        );
        final codec = await descriptor.instantiateCodec();
        final frame = await codec.getNextFrame();
        try {
          final png = (await frame.image.toByteData(
            format: ui.ImageByteFormat.png,
          ))!;
          await File('$output/$name-${entry.key}.png').writeAsBytes(
            png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
          );
        } finally {
          frame.image.dispose();
          codec.dispose();
          descriptor.dispose();
          buffer.dispose();
        }
      }
      await File('$output/$name.json').writeAsString(jsonEncode(facts));
    });
  }
}

class _Wallpaper extends CustomPainter {
  const _Wallpaper(this.brightness);
  final Brightness brightness;

  @override
  void paint(Canvas canvas, Size size) {
    for (var y = 0.0; y < size.height; y += 8) {
      for (var x = 0.0; x < size.width; x += 8) {
        canvas.drawRect(
          Rect.fromLTWH(x, y, 8, 8),
          Paint()
            ..color = (x ~/ 8 + y ~/ 8).isEven
                ? const Color(0xFFD878A0)
                : const Color(0xFF58B8D0),
        );
      }
    }
    for (var y = 12.0; y < size.height; y += 32) {
      final text = TextPainter(
        textDirection: TextDirection.ltr,
        text: TextSpan(
          text: 'Text 012 Ghost',
          style: TextStyle(
            fontFamily: 'HeaderProbe',
            fontSize: 12,
            color: brightness == Brightness.dark ? Colors.white : Colors.black,
          ),
        ),
      );
      text.layout();
      text.paint(canvas, Offset(8, y));
      text.dispose();
    }
    for (var i = 0; i < 5; i++) {
      canvas.drawRect(
        Rect.fromLTWH(28.0 + i * 37, 18.0 + i * 7, 1, 1),
        Paint()..color = [Colors.red, Colors.green, Colors.blue][i % 3],
      );
    }
  }

  @override
  bool shouldRepaint(_Wallpaper oldDelegate) =>
      brightness != oldDelegate.brightness;
}
