import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/typography.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    final font = FontLoader('GeometryLatin')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    final probe = TextPainter(
      text: const TextSpan(
        text: 'HH',
        style: TextStyle(fontFamily: 'GeometryLatin', fontSize: 20),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    expect(
      probe.width,
      lessThan(32),
      reason: 'GeometryLatin must resolve to the loaded font rather than Ahem',
    );
    probe.dispose();
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  testWidgets(
    'ink preserves glyphs beyond its logical mask in scene and replay',
    (tester) async {
      tester.view.physicalSize = const Size(280, 180);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      const background = Color(0xFF16353F);
      const frame = Rect.fromLTWH(60, 70, 36, 16);
      final capture = GlobalKey();
      Future<List<int>> render(
        String name, {
        bool enabled = true,
        bool replay = false,
        bool clipped = false,
        bool legacy = false,
      }) async {
        Widget glyph = const GlassInk(
          source: Colors.white,
          role: GlassInkRole.body,
          child: _OverhangingGlyph(),
        );
        if (legacy) {
          glyph = ShaderMask(
            shaderCallback: (bounds) => const LinearGradient(
              colors: [Colors.white, Colors.white],
            ).createShader(bounds),
            blendMode: BlendMode.srcIn,
            child: const _OverhangingGlyph(),
          );
        }
        if (clipped) glyph = ClipRect(child: glyph);
        await tester.pumpWidget(
          MaterialApp(
            theme: ThemeData(brightness: Brightness.dark),
            home: LiquidGlassScope(
              enabled: enabled,
              child: LiquidGlassHost(
                enabled: true,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    const Positioned.fill(
                      child: LiquidGlassColorField(
                        child: LiquidGlassColorSource(
                          child: ColoredBox(color: background),
                        ),
                      ),
                    ),
                    Positioned.fill(
                      child: RepaintBoundary(
                        key: capture,
                        child: Stack(
                          clipBehavior: Clip.none,
                          children: [
                            Positioned.fromRect(rect: frame, child: glyph),
                            if (replay)
                              const Positioned.fill(
                                child: LiquidGlass(
                                  style: LiquidGlassStyle(
                                    tint: Colors.transparent,
                                    blur: 0,
                                    refraction: 0,
                                    bevel: 0,
                                    rim: 0,
                                  ),
                                  clipper: ShapeBorderClipper(
                                    shape: AppShape.none,
                                  ),
                                  child: SizedBox.expand(),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ],
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
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              Directory(output).createSync(recursive: true);
              final png = await image.toByteData(
                format: ui.ImageByteFormat.png,
              );
              File(
                '$output/ink-overhang-$name.png',
              ).writeAsBytesSync(png!.buffer.asUint8List());
            }
            return (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!.buffer.asUint8List().toList();
          } finally {
            image.dispose();
          }
        }))!;
      }

      final plain = await render('plain', enabled: false);
      final legacy = await render('legacy-src-in', legacy: true);
      final active = await render('scene');
      final replayed = await render('replay', replay: true);
      expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
      expect(LiquidGlassHost.debugLastCapture!.panes.single.wallpaper, isFalse);
      final clipped = await render('clipped', clipped: true);
      var outside = 0, vertical = 0, changedInside = 0, replayedOutside = 0;
      var maximumReplayError = 0;
      var legacyMissing = 0;
      for (var y = 0; y < 180; y++) {
        for (var x = 0; x < 280; x++) {
          final index = (y * 280 + x) * 4;
          expect(
            active[index + 3],
            closeTo(plain[index + 3], 1),
            reason: 'Scene must preserve original glyph alpha at $x,$y',
          );
          final inside = frame.contains(Offset(x + 0.5, y + 0.5));
          if (!inside) expect(clipped[index + 3], 0);
          if (plain[index + 3] != 255) continue;
          if (!inside) {
            outside++;
            if (legacy[index + 3] < 128) legacyMissing++;
            if (y < frame.top || y >= frame.bottom) vertical++;
            if ((replayed[index] - (background.r * 255).round()).abs() > 20 ||
                (replayed[index + 1] - (background.g * 255).round()).abs() >
                    20 ||
                (replayed[index + 2] - (background.b * 255).round()).abs() >
                    20) {
              replayedOutside++;
            }
          } else if (active[index] != plain[index] ||
              active[index + 1] != plain[index + 1] ||
              active[index + 2] != plain[index + 2]) {
            changedInside++;
          }
          for (var channel = 0; channel < 3; channel++) {
            maximumReplayError = math.max(
              maximumReplayError,
              (active[index + channel] - replayed[index + channel]).abs(),
            );
          }
        }
      }
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        File('$output/ink-overhang-support.json').writeAsStringSync(
          jsonEncode({
            'outsideOpaqueGlyphPixels': outside,
            'legacyMissingOpaqueGlyphPixels': legacyMissing,
            'verticalOpaqueGlyphPixels': vertical,
            'replayedOutsideGlyphPixels': replayedOutside,
            'changedInsideGlyphPixels': changedInside,
            'maximumReplayChannelError': maximumReplayError,
          }),
        );
      }
      expect(outside, greaterThan(20));
      expect(
        legacyMissing,
        0,
        reason:
            'The native srcIn reference must retain its actual glyph coverage',
      );
      expect(vertical, greaterThan(20));
      expect(replayedOutside, outside);
      expect(changedInside, greaterThan(20));
      expect(maximumReplayError, lessThanOrEqualTo(2));
      expect(tester.takeException(), isNull);
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );

  testWidgets('covered ink freezes its appearance instead of removing text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final capture = GlobalKey();
    Future<void> render({
      bool cover = false,
      bool partial = false,
      bool freeze = true,
      double opacity = 1,
      Color color = const Color(0xFF16353F),
    }) async {
      const overlay = ColoredBox(color: Color(0x4038283D));
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData(brightness: Brightness.dark, fontFamily: 'Ahem'),
          themeAnimationStyle: AnimationStyle.noAnimation,
          home: RepaintBoundary(
            key: capture,
            child: LiquidGlassScope(
              enabled: true,
              child: LiquidGlassHost(
                enabled: true,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Positioned.fill(
                      child: LiquidGlassColorField(
                        child: LiquidGlassColorSource(
                          child: ColoredBox(color: color),
                        ),
                      ),
                    ),
                    const Positioned(
                      left: 24,
                      top: 64,
                      width: 120,
                      child: GlassText(
                        'HHHH',
                        inkRole: GlassInkRole.body,
                        style: TextStyle(fontSize: 28, color: Colors.white),
                      ),
                    ),
                    if (cover)
                      Positioned(
                        left: 0,
                        top: 0,
                        width: partial ? 60 : 360,
                        height: 240,
                        child: Opacity(
                          opacity: opacity,
                          child: freeze
                              ? const LiquidGlassOcclusion(
                                  shape: AppShape.none,
                                  child: overlay,
                                )
                              : overlay,
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
      await tester.pump();
    }

    List<LiquidGlassInkLayer> layers() {
      final result = <LiquidGlassInkLayer>[];
      void visit(ContainerLayer parent) {
        for (
          Layer? layer = parent.firstChild;
          layer != null;
          layer = layer.nextSibling
        ) {
          if (layer is LiquidGlassInkLayer) result.add(layer);
          if (layer is ContainerLayer) visit(layer);
        }
      }

      visit(tester.binding.renderViews.first.debugLayer!);
      return result;
    }

    Future<List<int>> snapshot(String name) async {
      final boundary =
          capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      return (await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            Directory(output).createSync(recursive: true);
            final png = await image.toByteData(format: ui.ImageByteFormat.png);
            File(
              '$output/ink-occlusion-$name.png',
            ).writeAsBytesSync(png!.buffer.asUint8List());
          }
          return (await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          ))!.buffer.asUint8List().toList();
        } finally {
          image.dispose();
        }
      }))!;
    }

    await render(cover: true, freeze: false);
    final expected = await snapshot('reference');
    expect(LiquidGlassHost.debugLastInkCapture!.masks, 1);
    await render(cover: true);
    final frozen = await snapshot('frozen');
    expect(layers().single.inkFrozen, isTrue);
    expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 1);
    expect(LiquidGlassHost.debugLastInkCapture!.masks, 1);
    var maximumError = 0;
    var point = 0;
    var brightest = -1;
    for (var y = 64; y < 98; y++) {
      for (var x = 26; x < 130; x++) {
        final index = (y * 360 + x) * 4;
        final brightness =
            expected[index] + expected[index + 1] + expected[index + 2];
        if (brightness > brightest) {
          brightest = brightness;
          point = index;
        }
        for (var channel = 0; channel < 3; channel++) {
          maximumError = math.max(
            maximumError,
            (expected[index + channel] - frozen[index + channel]).abs(),
          );
        }
      }
    }
    expect(maximumError, lessThanOrEqualTo(2));
    final hidden = LiquidGlassHost.debugLastInkCapture!;
    await render(cover: true, color: const Color(0xFF38283D));
    final changed = LiquidGlassHost.debugLastInkCapture!;
    final whileCovered = await snapshot('field-changed');
    expect(
      whileCovered.sublist(point, point + 3),
      frozen.sublist(point, point + 3),
    );
    expect(whileCovered.sublist(0, 3), isNot(frozen.sublist(0, 3)));
    expect(changed.sourceCaptures, hidden.sourceCaptures);
    expect(changed.filterUpdates, hidden.filterUpdates);
    await render(cover: true, partial: true);
    expect(layers().single.inkFrozen, isFalse);
    expect(LiquidGlassHost.debugLastInkCapture!.masks, 1);
    await render(cover: true, opacity: 0.5);
    expect(layers().single.inkFrozen, isFalse);
    await render(color: const Color(0xFF38283D));
    final revealed = await snapshot('revealed');
    expect(layers().single.inkFrozen, isFalse);
    expect(
      revealed.sublist(point, point + 3),
      isNot(frozen.sublist(point, point + 3)),
    );
    expect(
      LiquidGlassHost.debugLastInkCapture!.filterUpdates,
      greaterThan(hidden.filterUpdates),
    );
    await tester.pumpWidget(const SizedBox());
    await render(cover: true);
    final firstCovered = await snapshot('first-frame-covered');
    for (var channel = 0; channel < 3; channel++) {
      expect(
        firstCovered[point + channel],
        closeTo(expected[point + channel], 2),
      );
    }
    expect(tester.takeException(), isNull);
  }, skip: !ui.ImageFilter.isShaderFilterSupported);

  for (final brightness in Brightness.values) {
    for (final direction in TextDirection.values) {
      for (final scale in [1.0, 2.0]) {
        testWidgets(
          'real text paint envelope $brightness $direction $scale',
          (tester) => _realTextEnvelope(tester, brightness, direction, scale),
          skip: !ui.ImageFilter.isShaderFilterSupported,
        );
      }
    }
  }

  for (final brightness in Brightness.values) {
    for (final dpr in [1.0, 2.0, 3.0]) {
      for (final direction in TextDirection.values) {
        testWidgets('ink local geometry $brightness $dpr $direction', (
          tester,
        ) async {
          tester.view.physicalSize = Size(360 * dpr, 240 * dpr);
          tester.view.devicePixelRatio = dpr;
          addTearDown(tester.view.reset);
          final key = GlobalKey();
          final colors = auroraColorScheme(brightness);
          final background = brightness == Brightness.light
              ? const [Color(0xFFBDE5EC), Color(0xFFF0C9E9), Color(0xFFC1E9CE)]
              : const [Color(0xFF16353F), Color(0xFF38283D), Color(0xFF173A29)];
          Future<List<int>> render(
            double width, {
            bool overflow = false,
            bool enabled = true,
            double shift = 0,
            bool shadow = false,
            bool clipped = false,
            bool hidden = false,
          }) async {
            final theme = ThemeData(
              colorScheme: colors,
              platform: TargetPlatform.android,
              fontFamily: 'Ahem',
            );
            Widget label = GlassText(
              'HHHH',
              inkRole: GlassInkRole.body,
              softWrap: false,
              overflow: overflow ? TextOverflow.visible : TextOverflow.clip,
              textAlign: TextAlign.left,
              style: TextStyle(
                fontSize: 28,
                fontFamily: 'Ahem',
                color: colors.onSurface,
                shadows: shadow
                    ? const [
                        Shadow(
                          color: Colors.red,
                          offset: Offset(0, 6),
                          blurRadius: 2,
                        ),
                      ]
                    : null,
              ),
            );
            label = LiquidGlassScope(enabled: enabled, child: label);
            if (clipped) label = ClipRect(child: label);
            if (hidden) label = const SizedBox(height: 40);
            await tester.pumpWidget(
              ProviderScope(
                overrides: [
                  themeSettingProvider.overrideWithBuild(
                    (_, _) => const ThemeProps(
                      auroraEnabled: true,
                      liquidGlass: LiquidGlassMode.standard,
                    ),
                  ),
                ],
                child: MaterialApp(
                  theme: theme,
                  darkTheme: theme,
                  themeMode: brightness == Brightness.dark
                      ? ThemeMode.dark
                      : ThemeMode.light,
                  themeAnimationStyle: AnimationStyle.noAnimation,
                  builder: (context, child) => RepaintBoundary(
                    key: key,
                    child: AuroraAppSurface(motion: false, child: child!),
                  ),
                  home: Directionality(
                    textDirection: direction,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Positioned.fill(
                          child: LiquidGlassColorField(
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                gradient: LinearGradient(
                                  begin: Alignment(-1 + shift, 0),
                                  colors: background,
                                ),
                              ),
                            ),
                          ),
                        ),
                        Positioned(
                          left: 24,
                          top: 64,
                          width: width,
                          child: label,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
            await tester.pump();
            await tester.pump();
            final boundary =
                key.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary;
            return (await tester.runAsync(() async {
              final image = await boundary.toImage(pixelRatio: dpr);
              try {
                final bytes = (await image.toByteData(
                  format: ui.ImageByteFormat.rawRgba,
                ))!.buffer.asUint8List();
                final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
                if (output != null &&
                    width == 300 &&
                    !shadow &&
                    !hidden &&
                    !clipped &&
                    shift == 0) {
                  Directory(output).createSync(recursive: true);
                  final png = await image.toByteData(
                    format: ui.ImageByteFormat.png,
                  );
                  File(
                    '$output/ink-geometry-${brightness.name}-$dpr-${direction.name}.png',
                  ).writeAsBytesSync(png!.buffer.asUint8List());
                }
                return bytes.toList();
              } finally {
                image.dispose();
              }
            }))!;
          }

          Color pixel(List<int> data, int x, int y) {
            final i =
                ((y * dpr).floor() * (360 * dpr).round() + (x * dpr).floor()) *
                4;
            return Color.fromARGB(255, data[i], data[i + 1], data[i + 2]);
          }

          final plain = await render(300, enabled: false);
          final tight = await render(120);
          final expanded = await render(300);
          final visible = await render(18, overflow: true);
          final moved = await render(300, shift: 0.4);
          final shadowed = await render(300, shadow: true);
          final clipped = await render(18, overflow: true, clipped: true);
          final empty = await render(300, hidden: true);
          var changed = 0;
          var moving = 0;
          var opaquePoints = 0;
          var maxError = 0;
          var shadowPixels = 0;
          var clippedPixels = 0;
          var minimumContrast = double.infinity;
          final samples = <Map<String, Object?>>[];
          for (var y = 64; y < 110; y++) {
            for (var x = 26; x < 130; x++) {
              final control = pixel(plain, x, y);
              if ((control.r - colors.onSurface.r).abs() > 0.01 ||
                  (control.g - colors.onSurface.g).abs() > 0.01 ||
                  (control.b - colors.onSurface.b).abs() > 0.01) {
                continue;
              }
              opaquePoints++;
              if (x > 44) {
                expect(pixel(clipped, x, y), pixel(empty, x, y));
                clippedPixels++;
              }
              final a = pixel(tight, x, y);
              final b = pixel(expanded, x, y);
              final c = pixel(visible, x, y);
              final n = pixel(moved, x, y);
              final under = pixel(empty, x, y);
              final first = a.computeLuminance();
              final second = under.computeLuminance();
              minimumContrast = math.min(
                minimumContrast,
                (math.max(first, second) + 0.05) /
                    (math.min(first, second) + 0.05),
              );
              for (final pair in [(a, b), (a, c)]) {
                maxError = math.max(
                  maxError,
                  [
                    (pair.$1.r - pair.$2.r).abs(),
                    (pair.$1.g - pair.$2.g).abs(),
                    (pair.$1.b - pair.$2.b).abs(),
                  ].reduce(math.max).multiply255(),
                );
              }
              if (a != control) changed++;
              if (a != n) moving++;
              if (samples.length < 4) {
                samples.add({
                  'point': [x, y],
                  'source': control.toARGB32(),
                  'under': under.toARGB32(),
                  'tight': a.toARGB32(),
                  'expanded': b.toARGB32(),
                  'overflow': c.toARGB32(),
                  'motion': n.toARGB32(),
                });
              }
            }
          }
          for (var y = 95; y < 115; y++) {
            for (var x = 24; x < 140; x++) {
              final a = pixel(shadowed, x, y);
              if (a.r > a.g * 1.5 && a.r > a.b * 1.5) shadowPixels++;
            }
          }
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            File(
              '$output/ink-geometry-${brightness.name}-$dpr-${direction.name}.json',
            ).writeAsStringSync(
              jsonEncode({
                'maxError': maxError,
                'opaquePoints': opaquePoints,
                'coloredPoints': changed,
                'movingPoints': moving,
                'shadowPixels': shadowPixels,
                'clippedPixels': clippedPixels,
                'minimumContrast': minimumContrast,
                'samples': samples,
              }),
            );
          }
          expect(opaquePoints, greaterThan(100));
          expect(maxError, lessThanOrEqualTo(3));
          expect(changed, greaterThan(100));
          expect(moving, greaterThan(100));
          expect(shadowPixels, greaterThan(10));
          expect(clippedPixels, greaterThan(50));
          expect(minimumContrast, greaterThanOrEqualTo(4.5));
          expect(tester.takeException(), isNull);
        }, skip: !ui.ImageFilter.isShaderFilterSupported);
      }
    }
  }
}

extension on double {
  int multiply255() => (this * 255).round();
}

Future<void> _realTextEnvelope(
  WidgetTester tester,
  Brightness brightness,
  TextDirection direction,
  double scale,
) async {
  tester.view.physicalSize = const Size(520, 520);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final capture = GlobalKey();
  final scheme = auroraColorScheme(brightness);
  final background = brightness == Brightness.light
      ? const Color(0xFFBDE5EC)
      : const Color(0xFF16353F);
  Future<List<int>> render({
    bool enabled = false,
    bool empty = false,
    bool native = false,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(
          colorScheme: scheme,
          fontFamily: 'GeometryLatin',
        ).withAppTypography,
        themeAnimationStyle: AnimationStyle.noAnimation,
        home: RepaintBoundary(
          key: capture,
          child: LiquidGlassScope(
            enabled: enabled,
            child: LiquidGlassHost(
              enabled: enabled,
              child: Directionality(
                textDirection: direction,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Positioned.fill(
                      child: LiquidGlassColorField(
                        child: ColoredBox(color: background),
                      ),
                    ),
                    if (!empty)
                      Positioned(
                        left: 24,
                        top: 24,
                        width: 190,
                        child: Builder(
                          builder: (context) {
                            Widget label(
                              String text, {
                              bool organization = false,
                              bool rich = false,
                            }) {
                              final style =
                                  (organization
                                          ? AppTextStyle.subheadline
                                          : AppTextStyle.footnote)
                                      .of(context, emphasized: true)
                                      .copyWith(
                                        color: scheme.onSurface,
                                        fontFamily: 'GeometryLatin',
                                        fontFamilyFallback: const [
                                          'AbsentGeometryFont',
                                        ],
                                      );
                              final span = TextSpan(
                                style: style,
                                children: [
                                  const TextSpan(text: 'Google '),
                                  TextSpan(
                                    text: 'Ágj Άλφα a\u0301j\u0301',
                                    style: style.copyWith(
                                      fontStyle: FontStyle.italic,
                                    ),
                                  ),
                                ],
                              );
                              final Widget textWidget;
                              if (rich) {
                                textWidget = enabled
                                    ? GlassText.rich(
                                        span,
                                        style: style,
                                        inkRole: GlassInkRole.body,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        textScaler: TextScaler.linear(scale),
                                      )
                                    : Text.rich(
                                        span,
                                        style: style,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        textScaler: TextScaler.linear(scale),
                                      );
                              } else {
                                textWidget = enabled
                                    ? GlassText(
                                        text,
                                        style: style,
                                        inkRole: GlassInkRole.body,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        textScaler: TextScaler.linear(scale),
                                      )
                                    : Text(
                                        text,
                                        style: style,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        textScaler: TextScaler.linear(scale),
                                      );
                              }
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: native
                                    ? ShaderMask(
                                        shaderCallback: (bounds) =>
                                            LinearGradient(
                                              colors: [
                                                scheme.onSurface,
                                                scheme.onSurface,
                                              ],
                                            ).createShader(bounds),
                                        blendMode: BlendMode.srcATop,
                                        child: textWidget,
                                      )
                                    : textWidget,
                              );
                            }

                            return Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                label(
                                  'PT.TELKOMINDONESIA INTERNATIONAL',
                                  organization: true,
                                ),
                                for (final name in [
                                  'Google',
                                  'GitHub',
                                  'YouTube',
                                  'ChatGPT',
                                ])
                                  label(name),
                                label(
                                  'Ágj ÅÉ y\u0301a\u0301j\u0301',
                                  organization: true,
                                ),
                                label('', rich: true),
                              ],
                            );
                          },
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
    final boundary =
        capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    return (await tester.runAsync(() async {
      final image = await boundary.toImage();
      try {
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null && !empty) {
          Directory(output).createSync(recursive: true);
          final png = await image.toByteData(format: ui.ImageByteFormat.png);
          File(
            '$output/ink-real-envelope-${brightness.name}-${direction.name}-$scale-${native ? 'native' : enabled}.png',
          ).writeAsBytesSync(png!.buffer.asUint8List());
        }
        return (await image.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!.buffer.asUint8List().toList();
      } finally {
        image.dispose();
      }
    }))!;
  }

  final empty = await render(empty: true);
  final ordinary = await render();
  final native = await render(native: true);
  final ink = await render(enabled: true);
  var supported = 0;
  var missing = 0;
  var nativeMissing = 0;
  var additionalMissing = 0;
  final nativeMissingPoints = <List<int>>[];
  final additionalMissingPoints = <List<int>>[];
  var opaque = 0;
  var colored = 0;
  var minimumContrast = double.infinity;
  final missingPoints = <List<int>>[];
  for (var index = 0; index < ordinary.length; index += 4) {
    final original = Color.fromARGB(
      255,
      ordinary[index],
      ordinary[index + 1],
      ordinary[index + 2],
    );
    final actual = Color.fromARGB(
      255,
      ink[index],
      ink[index + 1],
      ink[index + 2],
    );
    final under = Color.fromARGB(
      255,
      empty[index],
      empty[index + 1],
      empty[index + 2],
    );
    double distance(Color a, Color b) => math.max(
      (a.r - b.r).abs(),
      math.max((a.g - b.g).abs(), (a.b - b.b).abs()),
    );
    final nativePixel = Color.fromARGB(
      255,
      native[index],
      native[index + 1],
      native[index + 2],
    );
    final point = [(index ~/ 4) % 520, index ~/ (4 * 520)];
    if (distance(nativePixel, under) > 0.18 && distance(actual, under) < 0.05) {
      additionalMissing++;
      additionalMissingPoints.add(point);
    }
    if (distance(original, under) > 0.18) {
      supported++;
      if (distance(nativePixel, under) < 0.05) {
        nativeMissing++;
        nativeMissingPoints.add(point);
      }
      if (distance(actual, under) < 0.05) {
        missing++;
        missingPoints.add(point);
      }
    }
    if (distance(original, scheme.onSurface) <= 1 / 255) {
      opaque++;
      final a = actual.computeLuminance();
      final b = under.computeLuminance();
      minimumContrast = math.min(
        minimumContrast,
        (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05),
      );
      if (HSVColor.fromColor(actual).saturation >
          HSVColor.fromColor(original).saturation + 0.10) {
        colored++;
      }
    }
  }
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output != null) {
    File(
      '$output/ink-real-envelope-${brightness.name}-${direction.name}-$scale.json',
    ).writeAsStringSync(
      jsonEncode({
        'supportOracle':
            'same native ShaderMask; plain Text AA differences are retained separately',
        'supported': supported,
        'missing': missing,
        'nativeShaderMissing': nativeMissing,
        'nativeShaderMissingPoints': nativeMissingPoints,
        'additionalInkMissing': additionalMissing,
        'additionalInkMissingPoints': additionalMissingPoints,
        'missingPoints': missingPoints,
        'opaque': opaque,
        'colored': colored,
        'minimumContrast': minimumContrast,
      }),
    );
  }
  expect(supported, greaterThan(100));
  expect(
    additionalMissing,
    0,
    reason:
        'Ink must retain every strongly painted native ShaderMask glyph pixel',
  );
  expect(opaque, greaterThan(50));
  expect(colored, greaterThan(25));
  expect(minimumContrast, greaterThanOrEqualTo(4.5));
  expect(tester.takeException(), isNull);
}

class _OverhangingGlyph extends LeafRenderObjectWidget {
  const _OverhangingGlyph();

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _OverhangingGlyphRender();
}

class _OverhangingGlyphRender extends RenderBox {
  @override
  bool get alwaysNeedsCompositing => true;

  final _text = TextPainter(
    text: const TextSpan(
      text: 'HH',
      style: TextStyle(
        fontFamily: 'GeometryLatin',
        fontSize: 32,
        height: 1,
        color: Colors.white,
      ),
    ),
    textDirection: TextDirection.ltr,
  )..layout();

  @override
  void performLayout() => size = constraints.constrain(const Size(36, 16));

  @override
  void paint(PaintingContext context, Offset offset) {
    final bounds = Rect.fromLTWH(offset.dx - 16, offset.dy - 16, 96, 64);
    final recorder = ui.PictureRecorder();
    _text.paint(Canvas(recorder, bounds), offset + const Offset(-12, -12));
    context.addLayer(PictureLayer(bounds)..picture = recorder.endRecording());
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }
}
