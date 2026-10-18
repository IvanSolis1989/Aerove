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
  Widget build(BuildContext context) => const Text('Mount');
}

void main() {
  setUpAll(() async {
    final font = FontLoader('ForegroundPreview')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
  });
  test('foreground caps are idempotent and preserve RGB and lower alpha', () {
    for (final color in [Colors.white, Colors.black, Colors.red]) {
      final capped = GlassForeground.cap(color);
      expect(_channels(capped), _channels(color));
      expect(capped.a, GlassForeground.opacity);
      expect(GlassForeground.cap(capped), capped);
      final disabled = color.withValues(alpha: 0.38);
      expect(GlassForeground.cap(disabled), disabled);
    }
    final original = ThemeData();
    final transformed = GlassForeground.theme(original);
    expect(transformed.colorScheme.primary, original.colorScheme.primary);
    expect(transformed.colorScheme.error, original.colorScheme.error);
    expect(transformed.colorScheme.surface, original.colorScheme.surface);
    expect(transformed.disabledColor, original.disabledColor);
  });

  testWidgets('foreground toggling keeps its subtree mounted', (tester) async {
    var mounts = 0;
    for (final enabled in [false, true, false]) {
      await tester.pumpWidget(
        TestApp(
          child: LiquidGlassScope(
            enabled: enabled,
            child: GlassForegroundText(
              child: _MountProbe(onMount: () => mounts++),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(mounts, 1);
    }
  });

  testWidgets('explicit glyph and button disabled alpha stays unchanged', (
    tester,
  ) async {
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
    final theme = GlassForeground.theme(
      ThemeData(
        textButtonTheme: TextButtonThemeData(
          style: ButtonStyle(
            foregroundColor: WidgetStateProperty.resolveWith(
              (states) =>
                  states.contains(WidgetState.disabled) ? disabled : Colors.red,
            ),
          ),
        ),
      ),
    );
    expect(
      theme.textButtonTheme.style!.foregroundColor!.resolve({
        WidgetState.disabled,
      }),
      disabled,
    );
    expect(theme.textButtonTheme.style!.foregroundColor!.resolve({})!.a, 0.86);
  });

  testWidgets('error foreground roles preserve their RGB and alpha', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      final original = ThemeData(colorScheme: auroraColorScheme(brightness));
      final colors = original.colorScheme;
      final transformed = GlassForeground.theme(original);
      expect(transformed.colorScheme.error, colors.error);
      expect(transformed.colorScheme.onError, colors.onError);
      expect(transformed.colorScheme.onErrorContainer, colors.onErrorContainer);
      expect(transformed.colorScheme.primary, colors.primary);
      for (final color in [colors.error, colors.onErrorContainer]) {
        await tester.pumpWidget(
          TestApp(
            includeNavigatorKey: false,
            theme: transformed,
            child: LiquidGlassScope(
              enabled: true,
              child: GlyphIcon(AppGlyphs.tools, color: color),
            ),
          ),
        );
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

  testWidgets('inherited IconTheme opacity preserves the original semantics', (
    tester,
  ) async {
    for (final brightness in Brightness.values) {
      final base = ThemeData(colorScheme: auroraColorScheme(brightness));
      for (final useScheme in [false, true]) {
        for (final opacity in [0.54, 0.38]) {
          Future<Color> render(bool glass) async {
            final theme = glass ? GlassForeground.theme(base) : base;
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
      expect(
        Theme.of(context).colorScheme.onSurface.a,
        expected ? GlassForeground.opacity : 1,
      );
      final disabled = Colors.red.withValues(alpha: 0.38);
      expect(GlassForeground.colorOf(context, disabled), disabled);
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
                colors.onSurface.withValues(alpha: 0.86),
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
        expect(foreground, colors.onSurface.withValues(alpha: 0.86));
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

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('text glyph and dock reveal fourteen percent on '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(400, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final key = GlobalKey();
        final colors = auroraColorScheme(brightness);
        final base = ThemeData(colorScheme: colors).withAppTypography;
        final diagnostics = <String, Object?>{
          'brightness': brightness.name,
          'quality': quality.name,
          'points': <String, Object?>{},
          'samples': <Map<String, Object?>>[],
        };
        void saveDiagnostics() {
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output == null) return;
          final directory = Directory(output)..createSync(recursive: true);
          File(
            '${directory.path}/foreground-${brightness.name}-'
            '${quality.name}-samples.json',
          ).writeAsStringSync(
            const JsonEncoder.withIndent('  ').convert(diagnostics),
          );
        }

        Future<({List<int> bytes, int width, Map<String, Rect> bounds})> render(
          Color backdrop, {
          bool glass = true,
          bool ink = true,
        }) async {
          final theme = glass ? GlassForeground.theme(base) : base;
          final visibleColors = ink
              ? theme.colorScheme
              : theme.colorScheme.copyWith(
                  onSurface: Colors.transparent,
                  onSurfaceVariant: Colors.transparent,
                  primary: Colors.transparent,
                );
          await tester.pumpWidget(
            TestApp(
              theme: theme.copyWith(colorScheme: visibleColors),
              child: RepaintBoundary(
                key: key,
                child: LiquidGlassScope(
                  enabled: glass,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: glass,
                    child: ColoredBox(
                      color: backdrop,
                      child: Stack(
                        children: [
                          Positioned(
                            left: 24,
                            top: 20,
                            child: Builder(
                              builder: (context) => Text(
                                'H',
                                style: AppTextStyle.display
                                    .of(context)
                                    .copyWith(
                                      fontFamily: 'Ahem',
                                      color: Theme.of(
                                        context,
                                      ).colorScheme.onSurface,
                                    ),
                              ),
                            ),
                          ),
                          Positioned(
                            left: 150,
                            top: 20,
                            child: GlyphIcon(
                              AppGlyphs.dashboard,
                              key: const ValueKey('foregroundGlyph'),
                              fill: 1,
                              size: 48,
                              color: ink
                                  ? colors.onSurface
                                  : Colors.transparent,
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
              ),
            ),
          );
          await tester.pumpAndSettle();
          final boundary =
              key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          final origin = boundary.localToGlobal(Offset.zero);
          Rect boundsOfCopies(Finder finder) {
            final matches = finder.evaluate().toList();
            expect(matches, isNotEmpty);
            final first = tester.getRect(finder.first);
            for (var i = 1; i < matches.length; i++) {
              expect(
                tester.getRect(finder.at(i)),
                first,
                reason: 'Resting lens copies must share layout geometry',
              );
            }
            return first.shift(-origin);
          }

          final bounds = {
            'text': boundsOfCopies(find.text('H')),
            'glyph': tester
                .getRect(find.byKey(const ValueKey('foregroundGlyph')))
                .shift(-origin),
            'dock': boundsOfCopies(
              find.descendant(
                of: find.byType(NavigationDock),
                matching: find.text('Home'),
              ),
            ),
            'dockGlyph': boundsOfCopies(
              find.descendant(
                of: find.byType(NavigationDock),
                matching: find.byWidgetPredicate(
                  (widget) =>
                      widget is GlyphIcon &&
                      widget.glyph == AppGlyphs.dashboard,
                ),
              ),
            ),
          };
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final rgba = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                final directory = Directory(output)
                  ..createSync(recursive: true);
                File(
                  '${directory.path}/foreground-${brightness.name}-'
                  '${quality.name}-${backdrop.toARGB32()}-$glass-$ink.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return (
                bytes: rgba.buffer.asUint8List(),
                width: image.width,
                bounds: bounds,
              );
            } finally {
              image.dispose();
            }
          }))!;
        }

        final backdrops = brightness == Brightness.light
            ? const [Color(0xFFE6F2F5), Color(0xFFF0E8F1)]
            : const [Color(0xFF16353F), Color(0xFF38283D)];
        final opaque = await render(backdrops.first, glass: false);
        final points = <String, Offset>{};
        for (final name in ['text', 'glyph', 'dock', 'dockGlyph']) {
          final foreground = name.startsWith('dock')
              ? colors.primary
              : colors.onSurface;
          final expected = _channels(foreground);
          final bounds = opaque.bounds[name]!.deflate(2);
          for (var y = bounds.top.ceil(); y < bounds.bottom; y++) {
            for (var x = bounds.left.ceil(); x < bounds.right; x++) {
              final point = Offset(x + 0.5, y + 0.5);
              final interior =
                  [
                    for (var dy = -1; dy <= 1; dy++)
                      for (var dx = -1; dx <= 1; dx++)
                        _channels(
                          _pixel(
                            opaque.bytes,
                            opaque.width,
                            point + Offset(dx.toDouble(), dy.toDouble()),
                          ),
                        ),
                  ].every(
                    (actual) => List.generate(
                      3,
                      (i) => (actual[i] - expected[i]).abs(),
                    ).every((difference) => difference < 1.5 / 255),
                  );
              if (interior) {
                points[name] = point;
                break;
              }
            }
            if (points.containsKey(name)) break;
          }
          (diagnostics['points'] as Map<String, Object?>)[name] = {
            'bounds': [bounds.left, bounds.top, bounds.right, bounds.bottom],
            'point': points[name] == null
                ? null
                : [points[name]!.dx, points[name]!.dy],
          };
          saveDiagnostics();
          expect(
            points.containsKey(name),
            isTrue,
            reason: '$name needs a solid 3x3 opaque interior',
          );
        }
        for (final backdrop in backdrops) {
          final under = await render(backdrop, ink: false);
          final actual = await render(backdrop);
          for (final name in points.keys) {
            expect(under.bounds[name], opaque.bounds[name]);
            expect(actual.bounds[name], opaque.bounds[name]);
            final point = points[name]!;
            final behind = _pixel(under.bytes, under.width, point);
            final painted = _pixel(actual.bytes, actual.width, point);
            final foreground = name.startsWith('dock')
                ? colors.primary
                : colors.onSurface;
            final result = _channels(painted);
            final source = _channels(foreground);
            final background = _channels(behind);
            final predicted = List.generate(
              3,
              (i) => (source[i] * 0.86 + background[i] * 0.14) * 255,
            );
            (diagnostics['samples'] as List<Map<String, Object?>>).add({
              'role': name,
              'backdrop': backdrop.toARGB32(),
              'point': [point.dx, point.dy],
              'source': [for (final value in source) value * 255],
              'under': [for (final value in background) value * 255],
              'actual': [for (final value in result) value * 255],
              'expected': predicted,
              'previousExpected': List.generate(
                3,
                (i) => (source[i] * 0.94 + background[i] * 0.06) * 255,
              ),
              'error': List.generate(3, (i) => result[i] * 255 - predicted[i]),
              'contrast': _contrast(painted, behind),
            });
            saveDiagnostics();
            for (var i = 0; i < 3; i++) {
              expect(
                result[i] * 255,
                closeTo((source[i] * 0.86 + background[i] * 0.14) * 255, 2),
              );
            }
            expect(
              _contrast(painted, behind),
              greaterThanOrEqualTo(
                name == 'glyph' || name == 'dockGlyph' ? 3 : 4.5,
              ),
            );
          }
        }
        expect(tester.takeException(), isNull);
      });
    }
  }

  for (final brightness in Brightness.values) {
    for (final mode in [LiquidGlassMode.standard, LiquidGlassMode.ultra]) {
      testWidgets('real font foreground comparison $brightness $mode', (
        tester,
      ) async {
        tester.view.physicalSize = const Size(430, 500);
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
                      final legacy = colors.onSurface.withValues(alpha: 0.94);
                      return Padding(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Previous 94% reference', style: style),
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
                            Text('Current 86% actual', style: style),
                            const SizedBox(height: 12),
                            Text('Readable small labels 123', style: style),
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
        final label = tester.element(find.text('Current 86% actual'));
        expect(Theme.of(label).colorScheme.onSurface.a, 0.86);
        expect(LiquidGlassScope.of(label), isTrue);
        expect(tester.takeException(), isNull);
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          final boundary = capture.currentContext!.findRenderObject()!
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
