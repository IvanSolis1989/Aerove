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
import 'package:fl_clash/ui/snap_sheet.dart';
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
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
    }
  });
  testWidgets('sheet optical header appears only over scrolled content', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 320);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final controller = ScrollController();
    final key = GlobalKey();
    Future<Uint8List> shot(String name) async {
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      return (await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            final png = (await image.toByteData(
              format: ui.ImageByteFormat.png,
            ))!;
            File(
              '$output/$name.png',
            ).writeAsBytesSync(png.buffer.asUint8List());
          }
          return (await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          ))!.buffer.asUint8List();
        } finally {
          image.dispose();
        }
      }))!;
    }

    try {
      await tester.pumpWidget(
        MaterialApp(
          home: RepaintBoundary(
            key: key,
            child: LiquidGlassScope(
              enabled: true,
              child: LiquidGlassHost(
                enabled: true,
                wallpaperOnly: true,
                child: Stack(
                  children: [
                    const Positioned.fill(
                      child: LiquidGlassColorField(
                        child: LiquidGlassColorSource(
                          child: CustomPaint(
                            painter: _Wallpaper(Brightness.light),
                          ),
                        ),
                      ),
                    ),
                    Positioned(
                      left: 24,
                      top: 72,
                      width: 272,
                      height: 224,
                      child: FloatingHeaderBody(
                        backgroundColor: Colors.white,
                        header: const SizedBox(height: 64),
                        body: SingleChildScrollView(
                          controller: controller,
                          child: const SizedBox(height: 1000),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.byType(HeaderBlur), findsNothing);
      final initial = await shot('sheet-header-opening');
      controller.jumpTo(64);
      await tester.pump();
      if (ui.ImageFilter.isShaderFilterSupported) {
        await tester.runAsync(() async {
          await ui.FragmentProgram.fromAsset(
            'shaders/liquid_glass.frag',
          ).timeout(const Duration(seconds: 30));
        });
      }
      await tester.pump();
      if (ui.ImageFilter.isShaderFilterSupported) {
        expect(LiquidGlass.debugShaderReady, isTrue);
      }
      expect(find.byType(HeaderBlur), findsOneWidget);
      final header = find.byType(HeaderBlur);
      final pane = find.descendant(
        of: header,
        matching: find.byType(LiquidGlass),
      );
      expect(pane, findsOneWidget);
      expect(
        find.descendant(of: header, matching: find.byType(BackdropFilter)),
        findsNothing,
      );
      expect(tester.getRect(pane), const Rect.fromLTWH(24, 72, 272, 64));
      final optical = await shot('sheet-header-scrolled');
      var changed = 0;
      var outside = 0;
      for (var y = 0; y < 320; y++) {
        for (var x = 0; x < 320; x++) {
          final inside = x >= 24 && x < 296 && y >= 72 && y < 136;
          for (var channel = 0; channel < 4; channel++) {
            final index = (y * 320 + x) * 4 + channel;
            final delta = (initial[index] - optical[index]).abs();
            if (inside && delta > 2) changed++;
            if (!inside) outside = math.max(outside, delta);
          }
        }
      }
      if (ui.ImageFilter.isShaderFilterSupported)
        expect(changed, greaterThan(100));
      expect(outside, 0);
      controller.jumpTo(0);
      await tester.pump();
      await tester.pump();
      expect(find.byType(HeaderBlur), findsNothing);
      expect(await shot('sheet-header-top-restored'), initial);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      controller.dispose();
    }
  });
  testWidgets('sheet header keeps None and high contrast native fallbacks', (
    tester,
  ) async {
    for (final enabled in [false, true]) {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: const MediaQueryData(highContrast: true),
            child: LiquidGlassScope(
              enabled: enabled,
              child: const FloatingHeaderBody(
                backgroundColor: Colors.black,
                header: SizedBox(height: 64),
                body: SingleChildScrollView(child: SizedBox(height: 1000)),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.byType(HeaderBlur), enabled ? findsOneWidget : findsNothing);
      expect(
        find.byType(BackdropFilter),
        enabled ? findsOneWidget : findsNothing,
      );
      expect(find.byType(LiquidGlass), findsNothing);
      expect(
        tester
            .widget<FloatingHeader>(find.byType(FloatingHeader))
            .showBackground,
        isTrue,
      );
    }
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
  testWidgets('header coalesces scroll changes dispatched during layout', (
    tester,
  ) async {
    final offsets = ValueNotifier<List<double>>([80, 0, 80]);
    var headerBuilds = 0;
    try {
      await tester.pumpWidget(
        MaterialApp(
          home: LiquidGlassScope(
            enabled: true,
            child: FloatingHeaderBody(
              backgroundColor: Colors.black,
              header: Builder(
                builder: (_) {
                  headerBuilds++;
                  return const SizedBox(height: 64);
                },
              ),
              body: ValueListenableBuilder<List<double>>(
                valueListenable: offsets,
                builder: (_, values, _) => LayoutBuilder(
                  builder: (context, constraints) {
                    for (final offset in values) {
                      ScrollStartNotification(
                        metrics: FixedScrollMetrics(
                          minScrollExtent: 0,
                          maxScrollExtent: 1000,
                          pixels: offset,
                          viewportDimension: constraints.maxHeight,
                          axisDirection: AxisDirection.down,
                          devicePixelRatio: 1,
                        ),
                        context: context,
                      ).dispatch(context);
                    }
                    return const SizedBox.expand();
                  },
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);
      expect(tester.widget<FloatingHeader>(find.byType(FloatingHeader)).showBackground, isTrue);
      final settledBuilds = headerBuilds;
      offsets.value = [120, 160, 200];
      await tester.pump();
      await tester.pump();
      expect(headerBuilds, settledBuilds);
      expect(tester.takeException(), isNull);
      offsets.value = [0, 80, 0];
      await tester.pump();
      await tester.pump();
      expect(tester.widget<FloatingHeader>(find.byType(FloatingHeader)).showBackground, isFalse);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      offsets.dispose();
    }
  });
  testWidgets('real snap header restores scroll and survives viewport relayout', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final navigator = GlobalKey<NavigatorState>();
    late ScrollController sheetController;
    try {
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigator,
          theme: ThemeData(platform: TargetPlatform.android),
          builder: (_, child) => LiquidGlassScope(
            enabled: true,
            child: LiquidGlassHost(enabled: true, child: child!),
          ),
          home: const SizedBox.expand(),
        ),
      );
      final closed = navigator.currentState!.push<void>(
        SnapSheetRoute<void>(
          barrierLabel: 'Close',
          initialScrollOffset: 80,
          builder: (_, controller) {
            sheetController = controller!;
            return FloatingHeaderBody(
              backgroundColor: Colors.white,
              header: const SizedBox(height: 64),
              body: ListView.builder(
                controller: controller,
                itemCount: 40,
                itemExtent: 64,
                itemBuilder: (_, index) => Text('Row $index'),
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(sheetController.offset, greaterThan(0));
      expect(tester.widget<FloatingHeader>(find.byType(FloatingHeader)).showBackground, isTrue);
      tester.view.physicalSize = const Size(390, 620);
      await tester.pump();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.drag(find.byType(ListView), const Offset(0, -1000));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      sheetController.jumpTo(0);
      await tester.pump();
      await tester.pump();
      expect(tester.widget<FloatingHeader>(find.byType(FloatingHeader)).showBackground, isFalse);
      navigator.currentState!.pop();
      await tester.pumpAndSettle();
      await closed;
      expect(find.byType(FloatingHeaderBody), findsNothing);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });
  testWidgets('optical header samples lower text once without lower glass', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final key = GlobalKey();
    Future<Uint8List> render(bool text) async {
      await tester.pumpWidget(
        MaterialApp(
          home: RepaintBoundary(
            key: key,
            child: LiquidGlassScope(
              enabled: true,
              child: LiquidGlassHost(
                enabled: true,
                wallpaperOnly: true,
                child: Stack(
                  children: [
                    const Positioned.fill(
                      child: LiquidGlassColorField(
                        child: LiquidGlassColorSource(
                          child: ColoredBox(color: Color(0xFF204060)),
                        ),
                      ),
                    ),
                    if (text)
                      const Positioned(
                        left: 32,
                        top: 8,
                        child: Text(
                          'READABLE',
                          style: TextStyle(
                            fontFamily: 'HeaderProbe',
                            fontSize: 28,
                            color: Colors.white,
                          ),
                        ),
                      ),
                    const Positioned.fill(
                      child: HeaderBlur(fadeHeight: 64, liquidGlass: true),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      return (await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            final png = (await image.toByteData(
              format: ui.ImageByteFormat.png,
            ))!;
            File(
              '$output/sheet-header-lower-text-$text.png',
            ).writeAsBytesSync(png.buffer.asUint8List());
          }
          return (await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          ))!.buffer.asUint8List();
        } finally {
          image.dispose();
        }
      }))!;
    }

    final empty = await render(false);
    final text = await render(true);
    expect(LiquidGlassHost.debugLastFlatCapture!.groups, 1);
    expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
    expect(find.byType(LiquidGlassHost), findsOneWidget);
    expect(find.byType(BackdropFilter), findsNothing);
    var contribution = 0;
    for (var y = 0; y < 32; y++) {
      for (var x = 16; x < 280; x++) {
        for (var channel = 0; channel < 3; channel++) {
          final index = (y * 320 + x) * 4 + channel;
          contribution += (text[index] - empty[index]).abs();
        }
      }
    }
    expect(contribution, greaterThan(1000));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
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

  testWidgets('blur coverage follows the complete header and its overhang', (
    tester,
  ) async {
    for (final height in [72.0, 104.0, 128.0]) {
      for (final overhang in [0.0, 16.0]) {
        await tester.pumpWidget(
          MaterialApp(
            home: Center(
              child: FloatingHeader(
                backgroundColor: const Color(0x20204080),
                blur: true,
                overhang: overhang,
                child: SizedBox(width: 224, height: height),
              ),
            ),
          ),
        );
        final blur = tester.widget<HeaderBlur>(find.byType(HeaderBlur));
        expect(blur.fadeHeight, height + overhang);
        expect(blur.fullStrengthHeight, height * 0.35);
        expect(tester.getSize(find.byType(FloatingHeader)).height, height);
      }
    }
  });

  testWidgets(
    'native blur holds over the title and fades through the bar foot',
    (tester) async {
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
            final rowAlpha = List<double?>.filled(probe.width, null);
            for (var y = 0; y < probe.width; y++) {
              final localY = (y + 0.5) / dpr - offset.dy;
              final t = ((localY - 44.8) / 115.2).clamp(0.0, 1.0);
              final alpha = localY < 0 ? 0.0 : 1 - t * t * (3 - 2 * t);
              var covariance = 0.0;
              var contrast = 0.0;
              for (var x = 0; x < probe.width; x++) {
                final localX = (x + 0.5) / dpr - offset.dx;
                final inside = localX >= 0 && localX < 224;
                for (var c = 0; c < 4; c++) {
                  final i = (y * probe.width + x) * 4 + c;
                  if (inside && localY >= 0 && localY < 160) {
                    expected[i] =
                        (baseline[i] * (1 - alpha) + gaussian[i] * alpha)
                            .round();
                  }
                  final delta = (actual[i] - expected[i]).abs();
                  error[i] = c == 3 ? 255 : math.min(255, delta * 16);
                  if (localY >= 160 && actual[i] != baseline[i]) {
                    bottomMismatches++;
                  }
                  if (inside &&
                      localY >= 0 &&
                      localY < 8 &&
                      actual[i] != baseline[i]) {
                    changedTop++;
                  }
                  // Fractional clip coverage is separate from the alpha oracle.
                  if (localX >= 1 &&
                      localX < 223 &&
                      localY >= 1 &&
                      localY < 160) {
                    maxError = math.max(maxError, delta);
                    rowError[y] = math.max(rowError[y], delta);
                    if (c < 3) {
                      final blurred = gaussian[i] - baseline[i];
                      covariance += (actual[i] - baseline[i]) * blurred;
                      contrast += blurred * blurred;
                    }
                  }
                }
              }
              if (contrast > 1000) rowAlpha[y] = covariance / contrast;
            }
            final sampledAlpha = <double>[];
            for (final localY in [16.0, 40.0, 64.0, 96.0, 128.0, 152.0]) {
              final row = ((localY + offset.dy) * dpr).floor();
              final measured = rowAlpha[row];
              expect(measured, isNotNull);
              final center = (row + 0.5) / dpr - offset.dy;
              final t = ((center - 44.8) / 115.2).clamp(0.0, 1.0);
              final expectedAlpha = 1 - t * t * (3 - 2 * t);
              final actualAlpha = measured!;
              expect(actualAlpha, closeTo(expectedAlpha, 0.03));
              sampledAlpha.add(actualAlpha);
            }
            expect(sampledAlpha[2], greaterThan(0.9));
            expect(sampledAlpha[3], inInclusiveRange(0.54, 0.61));
            expect(sampledAlpha[4], inInclusiveRange(0.16, 0.23));
            expect(sampledAlpha[5], lessThan(0.03));
            for (var index = 2; index < sampledAlpha.length; index++) {
              expect(sampledAlpha[index], lessThan(sampledAlpha[index - 1]));
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
                'rowAlpha': rowAlpha,
                'sampledAlpha': sampledAlpha,
                'fullStrengthHeight': 44.8,
                'fadeEndHeight': 160,
                'bottomMismatches': bottomMismatches,
              },
            );
            expect(changedTop, greaterThan(0));
            expect(
              bottomMismatches,
              0,
              reason:
                  'Below the extended bar foot must equal baseline exactly.',
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
    },
  );

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
          if (i ~/ (probe.width * 4) >= 184 && delta != 0) {
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
        expect(
          gaussianDeviation,
          lessThanOrEqualTo(noise + 1),
          reason: 'A normalized Gaussian preserves a constant field.',
        );
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
      Widget foreground() => DefaultTextStyle(
        style: const TextStyle(
          inherit: false,
          fontFamily: 'HeaderProbe',
          fontSize: 14,
          height: 1.3,
          color: Colors.white,
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        child: Align(
          alignment: Alignment.topCenter,
          child: SizedBox(
            height: 40,
            child: Row(
              children: [
                const Expanded(child: Text('Header title')),
                GestureDetector(
                  key: const ValueKey('header-action'),
                  behavior: HitTestBehavior.opaque,
                  onTap: () => taps++,
                  child: const SizedBox(
                    width: 40,
                    height: 40,
                    child: Center(child: Text('GO')),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      final foregroundMask = await _Probe(
        tester,
        1,
        const Offset(17, 24),
        Brightness.dark,
        color: Colors.black,
      ).render(_Layer.none, foreground: foreground());
      final baseline = await probe.render(
        _Layer.none,
        foreground: foreground(),
      );
      final actual = await probe.render(
        _Layer.header,
        foreground: foreground(),
      );
      expect(tester.takeException(), isNull);
      final action = tester.getRect(
        find.byKey(const ValueKey('header-action')),
      );
      final capture = tester.getRect(find.byKey(probe.key));
      expect(capture.contains(action.topLeft), isTrue);
      expect(capture.contains(action.bottomRight), isTrue);
      await tester.tap(find.byKey(const ValueKey('header-action')));
      expect(taps, 1);
      // Fully opaque foreground pixels must survive the backdrop filter.
      var foregroundPixels = 0;
      for (var i = 0; i < baseline.length; i += 4) {
        if (foregroundMask[i] == 255 &&
            foregroundMask[i + 1] == 255 &&
            foregroundMask[i + 2] == 255) {
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
                                  height: 160,
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
    expect(tester.takeException(), isNull);
    final boundary =
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    expect(boundary.size, const Size(256, 256));
    return (await tester.runAsync(() async {
      final image = await boundary.toImage(pixelRatio: dpr);
      try {
        expect(image.width, width);
        expect(image.height, width);
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
