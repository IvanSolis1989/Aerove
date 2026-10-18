import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

Color _pixel(List<int> bytes, int width, Offset point) {
  final index = (point.dy.floor() * width + point.dx.floor()) * 4;
  return Color.fromARGB(255, bytes[index], bytes[index + 1], bytes[index + 2]);
}

List<double> _channels(Color color) => [color.r, color.g, color.b];

double _contrast(Color a, Color b) {
  final first = a.computeLuminance();
  final second = b.computeLuminance();
  return (math.max(first, second) + 0.05) / (math.min(first, second) + 0.05);
}

class _MountProbe extends StatefulWidget {
  const _MountProbe({required this.onMount});

  final VoidCallback onMount;

  @override
  State<_MountProbe> createState() => _MountProbeState();
}

class _MountProbeState extends State<_MountProbe> {
  @override
  void initState() {
    super.initState();
    widget.onMount();
  }

  @override
  Widget build(BuildContext context) => const GlassText('Mount');
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    final font = FontLoader('ForegroundPreview')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    final emoji = FontLoader('InkEmoji')
      ..addFont(rootBundle.load('assets/fonts/Twemoji.Mozilla.ttf'));
    await emoji.load();
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
    }
  });
  test('ink layer supports OffsetLayer invalidation', () {
    final layer = LiquidGlassInkLayer();
    expect(() {
      layer.configure(
        offset: const Offset(24, 32),
        size: const Size(40, 40),
        strength: 1,
        source: Colors.black,
        contrast: 4.7,
        dark: false,
        program: null,
      );
    }, returnsNormally);
    expect(layer.offset, const Offset(24, 32));
    layer.dispose();
  });

  testWidgets('foreground toggling keeps its subtree mounted', (tester) async {
    var mounts = 0;
    for (final enabled in [false, true, false]) {
      await tester.pumpWidget(
        TestApp(
          child: LiquidGlassScope(
            enabled: enabled,
            child: _MountProbe(onMount: () => mounts++),
          ),
        ),
      );
      await tester.pump();
      expect(mounts, 1);
    }
  });

  testWidgets('explicit glyph disabled alpha stays unchanged', (tester) async {
    const disabled = Color.fromRGBO(255, 0, 0, 0.38);
    await tester.pumpWidget(
      const TestApp(
        child: LiquidGlassScope(
          enabled: true,
          child: GlyphIcon(AppGlyphs.tools, color: disabled),
        ),
      ),
    );
    final paint = tester.widget<CustomPaint>(
      find.descendant(
        of: find.byType(GlyphIcon),
        matching: find.byType(CustomPaint),
      ),
    );
    expect((paint.painter! as GlyphPainter).color, disabled);
  });

  testWidgets('error foreground roles preserve their RGB and alpha', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      final original = ThemeData(colorScheme: auroraColorScheme(brightness));
      final colors = original.colorScheme;
      for (final color in [colors.error, colors.onErrorContainer]) {
        await tester.pumpWidget(
          TestApp(
            includeNavigatorKey: false,
            theme: original,
            darkTheme: original,
            themeMode: brightness == Brightness.dark
                ? ThemeMode.dark
                : ThemeMode.light,
            child: LiquidGlassScope(
              enabled: true,
              child: GlyphIcon(AppGlyphs.tools, color: color),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final context = tester.element(find.byType(GlyphIcon));
        expect(Theme.of(context).brightness, brightness);
        expect(Theme.of(context).colorScheme.error, colors.error);
        expect(GlassForeground.colorsInk(context, color), isFalse);
        final paint = tester.widget<CustomPaint>(
          find.descendant(
            of: find.byType(GlyphIcon),
            matching: find.byType(CustomPaint),
          ),
        );
        expect((paint.painter! as GlyphPainter).color, color);
      }
    }
  });

  testWidgets('actual app typography preserves source alpha for ink shading', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      for (final sourceAlpha in [1.0, 0.47, 0.38]) {
        final colors = auroraColorScheme(brightness);
        final source = colors.onSurface.withValues(alpha: sourceAlpha);
        final base = ThemeData(colorScheme: colors);
        final input = base
            .copyWith(
              textTheme: base.textTheme.apply(
                bodyColor: source,
                displayColor: source,
              ),
              iconTheme: IconThemeData(color: source),
            )
            .withAppTypography;
        final roles = [
          (AppTextStyle.pageTitle, 1.0),
          (AppTextStyle.headline, 1.0),
          (AppTextStyle.body, 1.0),
          (AppTextStyle.footnote, 1.0),
          (AppTextStyle.caption1, 1.0),
        ];
        await tester.pumpWidget(
          ProviderScope(
            key: UniqueKey(),
            overrides: [
              themeSettingProvider.overrideWithBuild(
                (_, _) => const ThemeProps(
                  auroraEnabled: false,
                  liquidGlass: LiquidGlassMode.ultra,
                ),
              ),
            ],
            child: MaterialApp(
              theme: input,
              darkTheme: input,
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              builder: (_, child) =>
                  AuroraAppSurface(motion: false, child: child!),
              home: Scaffold(
                body: Builder(
                  builder: (context) => Column(
                    children: [
                      for (final (role, _) in roles)
                        Text(role.name, style: role.of(context)),
                      const GlyphIcon(AppGlyphs.tools),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        for (final (role, limit) in roles) {
          final rich = tester.widget<RichText>(
            find.descendant(
              of: find.text(role.name),
              matching: find.byType(RichText),
            ),
          );
          final actual = rich.text.style!.color!;
          expect(actual.a, math.min(sourceAlpha, limit));
          expect(_channels(actual), _channels(source));
        }
        final paint = tester.widget<CustomPaint>(
          find.descendant(
            of: find.byType(GlyphIcon),
            matching: find.byType(CustomPaint),
          ),
        );
        expect(
          (paint.painter! as GlyphPainter).color.a,
          math.min(sourceAlpha, 1.0),
        );
      }
    }
  });

  testWidgets('inherited IconTheme opacity preserves the original semantics', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      final colors = auroraColorScheme(brightness);
      final base = ThemeData(
        colorScheme: colors,
        iconTheme: IconThemeData(
          color: colors.onSurface.withValues(alpha: 0.87),
        ),
      );
      for (final useScheme in [false, true]) {
        for (final opacity in [0.54, 0.38]) {
          Future<Color> render(bool glass) async {
            final theme = base;
            final color = useScheme
                ? theme.colorScheme.onSurface
                : theme.iconTheme.color!;
            await tester.pumpWidget(
              TestApp(
                includeNavigatorKey: false,
                theme: theme,
                darkTheme: theme,
                themeMode: brightness == Brightness.dark
                    ? ThemeMode.dark
                    : ThemeMode.light,
                child: LiquidGlassScope(
                  enabled: glass,
                  child: IconTheme.merge(
                    data: IconThemeData(color: color, opacity: opacity),
                    child: const GlyphIcon(AppGlyphs.tools),
                  ),
                ),
              ),
            );
            await tester.pumpAndSettle();
            final paint = tester.widget<CustomPaint>(
              find.descendant(
                of: find.byType(GlyphIcon),
                matching: find.byType(CustomPaint),
              ),
            );
            return (paint.painter! as GlyphPainter).color;
          }

          final original = await render(false);
          final capped = await render(true);
          expect(original.a, lessThanOrEqualTo(opacity));
          expect(capped, original);
        }
      }
    }
  });

  testWidgets('the app gates foregrounds with glass and opaque fallbacks', (
    tester,
  ) async {
    for (final (mode, highContrast, pureBlack, expected) in [
      (LiquidGlassMode.none, false, false, false),
      (LiquidGlassMode.standard, false, false, true),
      (LiquidGlassMode.ultra, false, false, true),
      (LiquidGlassMode.ultra, true, false, false),
      (LiquidGlassMode.ultra, false, true, false),
    ]) {
      final inputTheme = ThemeData(
        colorScheme: auroraColorScheme(Brightness.dark),
      );
      await tester.pumpWidget(
        ProviderScope(
          key: UniqueKey(),
          overrides: [
            themeSettingProvider.overrideWithBuild(
              (_, _) => ThemeProps(
                auroraEnabled: false,
                liquidGlass: mode,
                pureBlack: pureBlack,
              ),
            ),
          ],
          child: MaterialApp(
            theme: inputTheme,
            darkTheme: inputTheme,
            themeMode: ThemeMode.dark,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context).copyWith(highContrast: highContrast),
              child: AuroraAppSurface(motion: false, child: child!),
            ),
            home: const Scaffold(body: Text('Foreground')),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final context = tester.element(find.text('Foreground'));
      final container = ProviderScope.containerOf(context);
      final settings = container.read(themeSettingProvider);
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        final directory = Directory(output)..createSync(recursive: true);
        final path =
            '${directory.path}/foreground-gate-${mode.name}-'
            '$highContrast-$pureBlack.json';
        File(path).writeAsStringSync(
          jsonEncode({
            'expected': expected,
            'glass': LiquidGlassScope.of(context),
            'brightness': Theme.of(context).brightness.name,
            'highContrast': MediaQuery.highContrastOf(context),
            'settings': {
              'gradient': settings.auroraEnabled,
              'glass': settings.liquidGlass.name,
              'pureBlack': settings.pureBlack,
            },
          }),
        );
      }
      expect(settings.auroraEnabled, isFalse);
      expect(settings.liquidGlass, mode);
      expect(settings.pureBlack, pureBlack);
      expect(Theme.of(context).brightness, Brightness.dark);
      expect(MediaQuery.highContrastOf(context), highContrast);
      expect(
        LiquidGlassScope.of(context),
        expected,
        reason: '$mode highContrast=$highContrast pureBlack=$pureBlack',
      );
      expect(Theme.of(context).colorScheme.onSurface.a, 1.0);
      final disabled = Colors.red.withValues(alpha: 0.38);
      expect(GlassForeground.colorsInk(context, disabled), isFalse);
    }
  });

  testWidgets('actual glass FilledButton keeps a readable surface foreground', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      for (final mode in [LiquidGlassMode.standard, LiquidGlassMode.ultra]) {
        final colors = ColorScheme.fromSeed(
          seedColor: const Color(0xFFFF2D55),
          brightness: brightness,
        );
        final backdrop = brightness == Brightness.light
            ? const Color(0xFFF0E8F1)
            : const Color(0xFF38283D);
        final inputTheme =
            ThemeData(
              platform: TargetPlatform.android,
              colorScheme: colors,
              scaffoldBackgroundColor: backdrop,
              fontFamily: 'Ahem',
              filledButtonTheme: FilledButtonThemeData(
                style: ButtonStyle(
                  foregroundColor: WidgetStatePropertyAll(colors.onPrimary),
                  padding: const WidgetStatePropertyAll(EdgeInsets.all(20)),
                ),
              ),
            ).withAppShapes.withAppSurfaces.copyWith(
              scaffoldBackgroundColor: backdrop,
            );
        final capture = GlobalKey();
        await tester.pumpWidget(
          ProviderScope(
            key: UniqueKey(),
            overrides: [
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(auroraEnabled: false, liquidGlass: mode),
              ),
            ],
            child: MaterialApp(
              theme: inputTheme,
              darkTheme: inputTheme,
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              builder: (context, child) => RepaintBoundary(
                key: capture,
                child: AuroraAppSurface(motion: false, child: child!),
              ),
              home: Scaffold(
                body: Center(
                  child: FilledButton(
                    onPressed: () {},
                    child: const Text('BUTTON'),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final label = find.text('BUTTON');
        final context = tester.element(label);
        final container = ProviderScope.containerOf(context);
        final settings = container.read(themeSettingProvider);
        final actualTheme = Theme.of(context);
        final platformBrightness = MediaQuery.platformBrightnessOf(context);
        final foreground = DefaultTextStyle.of(context).style.color!;
        final style = actualTheme.filledButtonTheme.style!;
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          final directory = Directory(output)..createSync(recursive: true);
          List<double>? rgba(Color? color) =>
              color == null ? null : [color.r, color.g, color.b, color.a];
          final path =
              '${directory.path}/foreground-filled-'
              '${brightness.name}-${mode.name}-context.json';
          File(path).writeAsStringSync(
            jsonEncode({
              'expectedBrightness': brightness.name,
              'inputBrightness': inputTheme.brightness.name,
              'actualBrightness': actualTheme.brightness.name,
              'platformBrightness': platformBrightness.name,
              'expectedForeground': rgba(
                colors.onSurface.withValues(alpha: 1.0),
              ),
              'actualOnSurface': rgba(actualTheme.colorScheme.onSurface),
              'actualOnPrimary': rgba(actualTheme.colorScheme.onPrimary),
              'actualOnPrimaryContainer': rgba(
                actualTheme.colorScheme.onPrimaryContainer,
              ),
              'themeForeground': rgba(style.foregroundColor?.resolve({})),
              'actualForeground': rgba(foreground),
              'inputBackground': rgba(inputTheme.scaffoldBackgroundColor),
              'glass': LiquidGlassScope.of(context),
              'settings': {
                'gradient': settings.auroraEnabled,
                'glass': settings.liquidGlass.name,
                'pureBlack': settings.pureBlack,
              },
            }),
          );
        }
        expect(settings.auroraEnabled, isFalse);
        expect(settings.liquidGlass, mode);
        expect(actualTheme.brightness, brightness);
        expect(LiquidGlassScope.of(context), isTrue);
        expect(foreground, colors.onSurface.withValues(alpha: 1.0));
        expect(style.backgroundColor!.resolve({}), Colors.transparent);
        expect(style.padding!.resolve({}), const EdgeInsets.all(20));
        final boundary =
            capture.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final text = tester.getRect(label);
        final point =
            Offset(text.left + text.width / 12, text.center.dy) -
            boundary.localToGlobal(Offset.zero);
        await tester.runAsync(() async {
          final image = await boundary.toImage();
          try {
            final bytes = (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!;
            final actual = _pixel(
              bytes.buffer.asUint8List(),
              image.width,
              point,
            );
            final expected = Color.alphaBlend(foreground, backdrop);
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              final directory = Directory(output)..createSync(recursive: true);
              final prefix =
                  '${directory.path}/foreground-filled-'
                  '${brightness.name}-${mode.name}';
              File('$prefix.json').writeAsStringSync(
                jsonEncode({
                  'source': _channels(foreground),
                  'under': _channels(backdrop),
                  'actual': _channels(actual),
                  'expected': _channels(expected),
                  'point': [point.dx, point.dy],
                  'contrast': _contrast(actual, backdrop),
                }),
              );
              final png = (await image.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              File('$prefix.png').writeAsBytesSync(png.buffer.asUint8List());
            }
            for (var channel = 0; channel < 3; channel++) {
              expect(
                _channels(actual)[channel] * 255,
                closeTo(_channels(expected)[channel] * 255, 2),
              );
            }
            expect(_contrast(actual, backdrop), greaterThanOrEqualTo(4.5));
          } finally {
            image.dispose();
          }
        });
      }
    }
  });

  testWidgets('ink keeps emoji images error disabled and high contrast pixels', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 260);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final stages = <String>[];
    Future<T?> realAsync<T>(String stage, Future<T> Function() action) async {
      void record(String state) {
        stages.add('$stage:$state');
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          Directory(output).createSync(recursive: true);
          File(
            '$output/foreground-protected-lifecycle.json',
          ).writeAsStringSync(jsonEncode(stages));
        }
      }

      record('start');
      final result = await tester.runAsync(
        () => action().timeout(
          const Duration(seconds: 30),
          onTimeout: () => throw TimeoutException('protected ink $stage'),
        ),
      );
      record('done');
      return result;
    }

    final asset = (await realAsync(
      'asset',
      () => rootBundle.load('assets/images/icon.png'),
    ))!.buffer.asUint8List();
    final imageProvider = MemoryImage(asset);
    await tester.pumpWidget(const SizedBox.shrink());
    final imageContext = tester.element(find.byType(SizedBox));
    await realAsync(
      'precache',
      () => precacheImage(imageProvider, imageContext),
    );
    for (final brightness in Brightness.values) {
      final source = brightness == Brightness.dark
          ? Colors.white
          : Colors.black;
      final colors = auroraColorScheme(brightness);
      final background = brightness == Brightness.dark
          ? const Color(0xFF173940)
          : const Color(0xFFBDE5EC);
      final capture = GlobalKey();
      Future<({List<int> bytes, int width, Map<String, Rect> bounds})> render(
        bool enabled,
        bool highContrast,
      ) async {
        await tester.pumpWidget(
          MaterialApp(
            theme: ThemeData(colorScheme: colors, fontFamily: 'Ahem'),
            darkTheme: ThemeData(colorScheme: colors, fontFamily: 'Ahem'),
            themeMode: brightness == Brightness.dark
                ? ThemeMode.dark
                : ThemeMode.light,
            themeAnimationStyle: AnimationStyle.noAnimation,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context).copyWith(highContrast: highContrast),
              child: RepaintBoundary(
                key: capture,
                child: LiquidGlassScope(
                  enabled: enabled,
                  child: LiquidGlassHost(
                    enabled: enabled,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Positioned.fill(
                          child: LiquidGlassColorField(
                            child: ColoredBox(color: background),
                          ),
                        ),
                        child!,
                      ],
                    ),
                  ),
                ),
              ),
            ),
            home: Scaffold(
              backgroundColor: Colors.transparent,
              body: Stack(
                children: [
                  Positioned(
                    left: 10,
                    top: 10,
                    child: GlassText(
                      'H',
                      style: TextStyle(
                        fontFamily: 'Ahem',
                        fontSize: 40,
                        color: source,
                      ),
                    ),
                  ),
                  const Positioned(
                    left: 100,
                    top: 10,
                    child: GlassText(
                      '😀',
                      key: ValueKey('protectedEmoji'),
                      style: TextStyle(fontFamily: 'InkEmoji', fontSize: 40),
                    ),
                  ),
                  Positioned(
                    left: 220,
                    top: 10,
                    child: Image(
                      image: imageProvider,
                      key: const ValueKey('protectedImage'),
                      width: 48,
                      height: 48,
                    ),
                  ),
                  Positioned(
                    left: 100,
                    top: 120,
                    child: GlassText(
                      'E',
                      key: const ValueKey('protectedError'),
                      style: TextStyle(
                        fontFamily: 'Ahem',
                        fontSize: 40,
                        color: colors.error,
                      ),
                    ),
                  ),
                  Positioned(
                    left: 220,
                    top: 120,
                    child: GlyphIcon(
                      AppGlyphs.tools,
                      key: const ValueKey('protectedDisabled'),
                      size: 48,
                      color: source.withValues(alpha: .38),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final boundary =
            capture.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final origin = boundary.localToGlobal(Offset.zero);
        final bounds = {
          for (final name in [
            'protectedEmoji',
            'protectedImage',
            'protectedError',
            'protectedDisabled',
          ])
            name: tester.getRect(find.byKey(ValueKey(name))).shift(-origin),
        };
        return (await realAsync(
          'readback-$brightness-$enabled-$highContrast',
          () async {
            final image = await boundary.toImage();
            try {
              final data = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                final directory = Directory(output)
                  ..createSync(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '${directory.path}/foreground-protected-${brightness.name}-$enabled-$highContrast.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return (
                bytes: data.buffer.asUint8List(),
                width: image.width,
                bounds: bounds,
              );
            } finally {
              image.dispose();
            }
          },
        ))!;
      }

      final original = await render(false, false);
      for (final highContrast in [false, true]) {
        final actual = await render(true, highContrast);
        for (final name in original.bounds.keys) {
          final rect = original.bounds[name]!;
          expect(actual.bounds[name], rect);
          for (var y = rect.top.ceil(); y < rect.bottom.floor(); y++) {
            for (var x = rect.left.ceil(); x < rect.right.floor(); x++) {
              final point = Offset(x + .5, y + .5);
              expect(
                _pixel(actual.bytes, actual.width, point),
                _pixel(original.bytes, original.width, point),
                reason: '$name $point',
              );
            }
          }
        }
        if (ui.ImageFilter.isShaderFilterSupported) {
          expect(
            LiquidGlassHost.debugLastInkCapture!.masks,
            highContrast ? 0 : 1,
          );
          final neutral = _pixel(
            actual.bytes,
            actual.width,
            const Offset(25, 25),
          );
          final old = _pixel(
            original.bytes,
            original.width,
            const Offset(25, 25),
          );
          expect(neutral == old, highContrast);
          if (!highContrast) {
            expect(
              LiquidGlassHost.debugLastInkCapture!.texturePixels,
              lessThanOrEqualTo(1024 * 1024),
            );
            expect(
              LiquidGlassHost.debugLastInkCapture!.maskPixels,
              lessThan(400 * 260 ~/ 2),
            );
          }
        }
      }
      expect(tester.takeException(), isNull);
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('ink follows local background and motion '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(400, 500);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        await tester.runAsync(GlassInk.prepare);
        final capture = GlobalKey();
        final colors = auroraColorScheme(brightness);
        final source = brightness == Brightness.dark
            ? Colors.white
            : Colors.black;
        final backdrops = brightness == Brightness.dark
            ? const [Color(0xFF173940), Color(0xFF3B203B)]
            : const [Color(0xFFBDE5EC), Color(0xFFEAD0E8)];
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        final samples = <Map<String, Object?>>[];
        Future<({List<int> bytes, int width, Map<String, Rect> bounds})> render(
          String tag, {
          bool enabled = true,
          bool ink = true,
          bool swapped = false,
          Duration? motionTime,
        }) async {
          final visible = ink ? source : Colors.transparent;
          final scheme = ink
              ? colors
              : colors.copyWith(
                  primary: Colors.transparent,
                  onSurface: Colors.transparent,
                  onSurfaceVariant: Colors.transparent,
                );
          await tester.pumpWidget(
            MaterialApp(
              theme: ThemeData(colorScheme: scheme, fontFamily: 'Ahem'),
              darkTheme: ThemeData(colorScheme: scheme, fontFamily: 'Ahem'),
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              themeAnimationStyle: AnimationStyle.noAnimation,
              builder: (context, child) => wrapAppScopes(
                context,
                RepaintBoundary(
                  key: capture,
                  child: LiquidGlassScope(
                    enabled: enabled,
                    quality: quality,
                    child: LiquidGlassHost(
                      enabled: enabled,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          Positioned.fill(
                            child: LiquidGlassColorField(
                              child: motionTime == null
                                  ? CustomPaint(
                                      painter: _InkFields(backdrops, swapped),
                                    )
                                  : SeededAuroraWallpaper(
                                      colors: const [
                                        Color(0xFF65BDD2),
                                        Color(0xFFC589CA),
                                        Color(0xFF75CCA8),
                                      ],
                                      seed: 271828,
                                      brightness: brightness,
                                      motion: false,
                                      motionTime: motionTime,
                                    ),
                            ),
                          ),
                          child!,
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              home: Scaffold(
                backgroundColor: Colors.transparent,
                body: Builder(
                  builder: (context) => Stack(
                    children: [
                      for (final (name, left, top, role) in [
                        ('bodyLeft', 24.0, 24.0, GlassInkRole.body),
                        ('bodyRight', 244.0, 24.0, GlassInkRole.body),
                        ('titleLeft', 24.0, 220.0, GlassInkRole.title),
                        ('titleRight', 244.0, 220.0, GlassInkRole.title),
                      ])
                        Positioned(
                          left: left,
                          top: top,
                          child: GlassText.rich(
                            TextSpan(
                              text: 'H',
                              style: TextStyle(color: visible),
                            ),
                            key: ValueKey(name),
                            inkRole: role,
                            style: AppTextStyle.display
                                .of(context)
                                .copyWith(fontFamily: 'Ahem', color: visible),
                          ),
                        ),
                      for (final (name, left) in [
                        ('glyphLeft', 24.0),
                        ('glyphRight', 244.0),
                      ])
                        Positioned(
                          left: left,
                          top: 120,
                          child: GlyphIcon(
                            AppGlyphs.dashboard,
                            key: ValueKey(name),
                            size: 48,
                            fill: 1,
                            color: visible,
                          ),
                        ),
                      Align(
                        alignment: Alignment.bottomCenter,
                        child: NavigationDock(
                          destinations: const [
                            NavigationDockDestination(
                              glyph: AppGlyphs.dashboard,
                              label: 'Home',
                            ),
                            NavigationDockDestination(
                              glyph: AppGlyphs.tools,
                              label: 'Tools',
                            ),
                          ],
                          selectedIndex: 0,
                          onSelected: (_) {},
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
          final origin = boundary.localToGlobal(Offset.zero);
          final bounds = <String, Rect>{
            for (final name in [
              'bodyLeft',
              'bodyRight',
              'glyphLeft',
              'glyphRight',
              'titleLeft',
              'titleRight',
            ])
              name: tester.getRect(find.byKey(ValueKey(name))).shift(-origin),
          };
          final dock = find.byType(NavigationDock);
          final labels = find.descendant(of: dock, matching: find.text('Home'));
          final glyphs = find.descendant(
            of: dock,
            matching: find.byWidgetPredicate(
              (widget) =>
                  widget is GlyphIcon && widget.glyph == AppGlyphs.dashboard,
            ),
          );
          for (final (name, finder) in [
            ('dock', labels),
            ('dockGlyph', glyphs),
          ]) {
            final rect = tester.getRect(finder.first);
            for (var i = 1; i < finder.evaluate().length; i++) {
              expect(tester.getRect(finder.at(i)), rect);
            }
            bounds[name] = rect.shift(-origin);
          }
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final bytes = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              if (output != null) {
                final directory = Directory(output)
                  ..createSync(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '${directory.path}/foreground-ink-${brightness.name}-${quality.name}-$tag.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return (
                bytes: bytes.buffer.asUint8List(),
                width: image.width,
                bounds: bounds,
              );
            } finally {
              image.dispose();
            }
          }))!;
        }

        final control = await render('opaque-control', enabled: false);
        final points = <String, Offset>{};
        for (final name in control.bounds.keys) {
          final expected = name.startsWith('dock') ? colors.primary : source;
          final rect = control.bounds[name]!.deflate(2);
          for (
            var y = rect.top.ceil();
            y < rect.bottom && !points.containsKey(name);
            y++
          ) {
            for (var x = rect.left.ceil(); x < rect.right; x++) {
              final point = Offset(x + .5, y + .5);
              final solid =
                  [
                    for (var dy = -1; dy <= 1; dy++)
                      for (var dx = -1; dx <= 1; dx++)
                        _pixel(
                          control.bytes,
                          control.width,
                          point + Offset(dx.toDouble(), dy.toDouble()),
                        ),
                  ].every(
                    (color) => List.generate(
                      3,
                      (i) =>
                          (_channels(color)[i] - _channels(expected)[i]).abs() <
                          1.5 / 255,
                    ).every((x) => x),
                  );
              if (solid) {
                points[name] = point;
                break;
              }
            }
          }
          expect(
            points.containsKey(name),
            isTrue,
            reason: '$name solid opaque interior',
          );
        }
        final frames = <Map<String, Color>>[];
        for (final swapped in [false, true]) {
          final under = await render(
            'under-$swapped',
            ink: false,
            swapped: swapped,
          );
          final actual = await render('actual-$swapped', swapped: swapped);
          final measured = <String, Color>{};
          for (final name in points.keys) {
            expect(actual.bounds[name], control.bounds[name]);
            final point = points[name]!;
            final background = _pixel(under.bytes, under.width, point);
            final painted = _pixel(actual.bytes, actual.width, point);
            final hsv = HSVColor.fromColor(painted);
            final base = HSVColor.fromColor(background);
            HSVColor? previousHsv;
            final previousSamples = brightness == Brightness.dark
                ? const {
                    'bodyLeft': Color(0xFF20D5FA),
                    'glyphLeft': Color(0xFF0CD1FA),
                    'dock': Color(0xFF3BCFED),
                  }
                : const {
                    'bodyLeft': Color(0xFF1C606C),
                    'glyphLeft': Color(0xFF278595),
                  };
            final previous = previousSamples[name];
            if (!swapped && previous != null) {
              // Actual solid glyph pixels from CI 39102679488 on this fixture.
              previousHsv = HSVColor.fromColor(previous);
              samples.add({
                'role': name,
                'referenceRun': '39102679488',
                'reference': _channels(previous),
                'actual': _channels(painted),
                'valueDelta': hsv.value - previousHsv.value,
                'saturationDelta': hsv.saturation - previousHsv.saturation,
              });
            }
            final gap = (hsv.hue - base.hue).abs();
            final hueGap = math.min(gap, 360 - gap);
            samples.add({
              'role': name,
              'swapped': swapped,
              'point': [point.dx, point.dy],
              'under': _channels(background),
              'actual': _channels(painted),
              'hueGap': hueGap,
              'saturation': hsv.saturation,
              'underSaturation': base.saturation,
              'contrast': _contrast(painted, background),
            });
            if (output != null) {
              File(
                '$output/foreground-ink-${brightness.name}-${quality.name}.json',
              ).writeAsStringSync(
                const JsonEncoder.withIndent('  ').convert(samples),
              );
            }
            if (previousHsv != null) {
              if (brightness == Brightness.dark) {
                expect(
                  hsv.value - previousHsv.value,
                  inExclusiveRange(-0.06, -0.008),
                );
                expect(
                  hsv.saturation - previousHsv.saturation,
                  inExclusiveRange(-0.06, -0.008),
                );
              } else {
                expect(hsv.value, greaterThan(previousHsv.value + 0.005));
                expect(
                  hsv.saturation,
                  greaterThan(previousHsv.saturation + 0.03),
                );
              }
            }
            expect(hueGap, lessThan(20), reason: name);
            if (!name.startsWith('title')) {
              final reducedDarkDock =
                  brightness == Brightness.dark && name == 'dock';
              final minimumBoost = reducedDarkDock ? 0.0 : 0.10;
              expect(
                hsv.saturation,
                greaterThan(base.saturation + minimumBoost),
                reason: name,
              );
            }
            expect(
              _contrast(painted, background),
              greaterThanOrEqualTo(
                name.contains('glyph') || name == 'dockGlyph' ? 3 : 4.5,
              ),
              reason: name,
            );
            measured[name] = painted;
          }
          frames.add(measured);
        }
        for (final name in points.keys) {
          expect(
            frames[0][name],
            isNot(frames[1][name]),
            reason: '$name follows current background',
          );
        }
        final before = await render('motion-before', motionTime: Duration.zero);
        final after = await render(
          'motion-after',
          motionTime: const Duration(seconds: 700),
        );
        var moved = 0;
        for (final name in [
          'bodyLeft',
          'bodyRight',
          'glyphLeft',
          'glyphRight',
        ]) {
          final point = points[name]!;
          if (_pixel(before.bytes, before.width, point) !=
              _pixel(after.bytes, after.width, point)) {
            moved++;
          }
        }
        expect(moved, greaterThanOrEqualTo(2));
        final generation = LiquidGlassHost.debugLastInkCapture!.generation;
        await tester.pump();
        final stable = await render(
          'motion-stable',
          motionTime: const Duration(seconds: 700),
        );
        expect(stable.bytes, after.bytes);
        expect(LiquidGlassHost.debugLastInkCapture!.generation, generation);
        expect(LiquidGlassHost.debugLastInkCapture!.masks, greaterThan(1));
        expect(tester.takeException(), isNull);
      }, skip: !ui.ImageFilter.isShaderFilterSupported);
    }
  }

  for (final brightness in Brightness.values) {
    for (final mode in [LiquidGlassMode.standard, LiquidGlassMode.ultra]) {
      testWidgets('real font foreground comparison $brightness $mode', (
        tester,
      ) async {
        tester.view.physicalSize = const Size(430, 700);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final capture = GlobalKey();
        final colors = auroraColorScheme(brightness);
        final backdrop = brightness == Brightness.light
            ? const [Color(0xFFE6F2F5), Color(0xFFF0E8F1)]
            : const [Color(0xFF16353F), Color(0xFF38283D)];
        final input = ThemeData(
          colorScheme: colors,
          fontFamily: 'ForegroundPreview',
        ).withAppTypography;
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(auroraEnabled: false, liquidGlass: mode),
              ),
            ],
            child: MaterialApp(
              theme: input,
              darkTheme: input,
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              builder: (context, child) => wrapAppScopes(
                context,
                RepaintBoundary(
                  key: capture,
                  child: AuroraAppSurface(motion: false, child: child!),
                ),
              ),
              home: Scaffold(
                body: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: backdrop),
                  ),
                  child: Builder(
                    builder: (context) {
                      final style = AppTextStyle.caption1.of(context);
                      final legacy = colors.onSurface.withValues(alpha: 0.86);
                      return Padding(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            GlassText('Previous 86% reference', style: style),
                            const SizedBox(height: 12),
                            RichText(
                              text: TextSpan(
                                text: 'Readable small labels 123',
                                style: style.copyWith(color: legacy),
                              ),
                            ),
                            const SizedBox(height: 12),
                            CustomPaint(
                              size: const Size.square(32),
                              painter: GlyphPainter(
                                glyph: AppGlyphs.dashboard,
                                fill: 1,
                                color: legacy,
                              ),
                            ),
                            const SizedBox(height: 28),
                            GlassText(
                              'Title: subtle color',
                              style: AppTextStyle.pageTitle.of(context),
                            ),
                            GlassText(
                              'Section: medium color',
                              style: AppTextStyle.headline.of(context),
                            ),
                            GlassText(
                              'Body: vivid color',
                              style: AppTextStyle.body.of(context),
                            ),
                            const SizedBox(height: 12),
                            GlassText(
                              'Small: readable labels 123',
                              style: style,
                            ),
                            const SizedBox(height: 12),
                            const GlyphIcon(
                              AppGlyphs.dashboard,
                              fill: 1,
                              size: 32,
                            ),
                            const Spacer(),
                            NavigationDock(
                              destinations: const [
                                NavigationDockDestination(
                                  glyph: AppGlyphs.dashboard,
                                  label: 'Home',
                                ),
                                NavigationDockDestination(
                                  glyph: AppGlyphs.tools,
                                  label: 'Tools',
                                ),
                              ],
                              selectedIndex: 0,
                              onSelected: (_) {},
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final label = tester.element(find.text('Title: subtle color'));
        expect(Theme.of(label).colorScheme.onSurface.a, 1.0);
        expect(LiquidGlassScope.of(label), isTrue);
        expect(tester.takeException(), isNull);
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final png = (await image.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              final directory = Directory(output)..createSync(recursive: true);
              File(
                '${directory.path}/foreground-real-font-'
                '${brightness.name}-${mode.name}.png',
              ).writeAsBytesSync(png.buffer.asUint8List());
            } finally {
              image.dispose();
            }
          });
        }
      });
    }
  }
}

class _InkFields extends CustomPainter {
  const _InkFields(this.colors, this.swapped);
  final List<Color> colors;
  final bool swapped;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
      Offset.zero & size,
      Paint()..color = colors[swapped ? 1 : 0],
    );
    canvas.drawRect(
      Rect.fromLTWH(size.width / 2, 0, size.width / 2, size.height),
      Paint()..color = colors[swapped ? 0 : 1],
    );
  }

  @override
  bool shouldRepaint(_InkFields old) =>
      old.swapped != swapped || old.colors != colors;
}
