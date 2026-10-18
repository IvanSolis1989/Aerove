import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _palette = [Color(0xFF65BDD2), Color(0xFFC589CA), Color(0xFF75CCA8)];

void _setupView(WidgetTester tester) {
  tester.view.physicalSize = const Size(430, 700);
  tester.view.devicePixelRatio = 1;
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    tester.view.reset();
  });
}

Color _pixel(List<int> bytes, int x, int y) {
  final i = (y * 430 + x) * 4;
  return Color.fromARGB(255, bytes[i], bytes[i + 1], bytes[i + 2]);
}

Future<List<int>> _pixels(
  WidgetTester tester,
  GlobalKey key,
  String name,
) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        File(
          '$output/ink-processing-$name.png',
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

Widget _scene(
  GlobalKey capture, {
  required Brightness brightness,
  Duration time = Duration.zero,
  int seed = 832838199,
  List<Color> palette = _palette,
  LiquidGlassMode mode = LiquidGlassMode.standard,
  bool showInk = true,
  bool crossfade = false,
  bool covered = false,
  bool replaceSource = false,
}) {
  final colors = auroraColorScheme(brightness);
  Widget wallpaper(int seed) => SeededAuroraWallpaper(
    key: replaceSource ? ValueKey(seed) : null,
    colors: palette,
    seed: seed,
    brightness: brightness,
    motionTime: time,
  );
  return ProviderScope(
    key: ValueKey(mode),
    overrides: [
      themeSettingProvider.overrideWithBuild(
        (_, _) => ThemeProps(
          auroraEnabled: mode != LiquidGlassMode.none,
          liquidGlass: mode,
        ),
      ),
    ],
    child: MaterialApp(
      theme: ThemeData(
        colorScheme: colors,
        platform: TargetPlatform.android,
        fontFamily: 'Ahem',
      ).withAppTypography,
      themeAnimationStyle: AnimationStyle.noAnimation,
      builder: (context, child) => RepaintBoundary(
        key: capture,
        child: AuroraAppSurface(motion: false, child: child!),
      ),
      home: IconTheme(
        data: const IconThemeData(opacity: 1),
        child: Stack(
          fit: StackFit.expand,
          children: [
            Positioned.fill(
              child: LiquidGlassColorField(
                child: crossfade
                    ? Stack(
                        fit: StackFit.expand,
                        children: [
                          wallpaper(seed),
                          Opacity(opacity: 0.5, child: wallpaper(seed + 1)),
                        ],
                      )
                    : wallpaper(seed),
              ),
            ),
            if (showInk)
              Positioned(
                left: 20,
                right: 20,
                top: 40,
                child: Builder(
                  builder: (context) => Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (var i = 0; i < 40; i++)
                        SizedBox(
                          width: 60,
                          height: 40,
                          child: _Sample(index: i),
                        ),
                    ],
                  ),
                ),
              ),
            if (covered)
              const Positioned.fill(
                child: LiquidGlassOcclusion(
                  shape: AppShape.none,
                  child: ColoredBox(color: Color(0x4038283D)),
                ),
              ),
          ],
        ),
      ),
    ),
  );
}

class _Sample extends StatelessWidget {
  const _Sample({required this.index});
  final int index;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final source = (index.isEven ? colors.onSurface : colors.onSurfaceVariant)
        .withValues(alpha: index < 20 ? 1 : 0.87);
    final role = GlassInkRole.values[index % GlassInkRole.values.length];
    if (role == GlassInkRole.icon) {
      return GlyphIcon(
        AppGlyphs.dashboard,
        key: ValueKey('sample-$index'),
        size: 24,
        fill: 1,
        color: source,
      );
    }
    final type = switch (role) {
      GlassInkRole.title => AppTextStyle.title2,
      GlassInkRole.section => AppTextStyle.headline,
      GlassInkRole.small => AppTextStyle.caption1,
      _ => AppTextStyle.body,
    };
    return GlassText(
      'HH',
      key: ValueKey('sample-$index'),
      inkRole: role,
      style: type.of(context).copyWith(color: source),
    );
  }
}

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });
  setUp(() => LiquidGlassInkLayer.debugProcessedFieldOverride = false);
  tearDown(() => LiquidGlassInkLayer.debugProcessedFieldOverride = null);

  for (final brightness in Brightness.values) {
    for (final mode in [LiquidGlassMode.standard, LiquidGlassMode.ultra]) {
      testWidgets('processed field preserves every role $brightness $mode', (
        tester,
      ) async {
        _setupView(tester);
        final key = GlobalKey();
        Future<void> render({
          Duration time = Duration.zero,
          LiquidGlassMode? selected,
          bool showInk = true,
        }) async {
          await tester.pumpWidget(
            _scene(
              key,
              brightness: brightness,
              mode: selected ?? mode,
              time: time,
              showInk: showInk,
            ),
          );
          await tester.pump(const Duration(milliseconds: 40));
        }

        await render(selected: LiquidGlassMode.none);
        final plain = await _pixels(
          tester,
          key,
          '${brightness.name}-${mode.name}-none',
        );
        final rectangles = [
          for (var i = 0; i < 20; i++)
            tester.getRect(find.byKey(ValueKey('sample-$i'))),
        ];
        await render();
        expect(LiquidGlassScope.of(tester.element(find.byKey(const ValueKey('sample-0')))), isTrue);
        expect(LiquidGlassHost.debugLastInkCapture!.masks, 40);
        final direct = await _pixels(
          tester,
          key,
          '${brightness.name}-${mode.name}-direct',
        );
        LiquidGlassInkLayer.debugProcessedFieldOverride = true;
        var lastBuilds = LiquidGlassInkLayer.debugLastProcessing!.builds;
        for (var i = 0; i < 4; i++) {
          await render();
          final metrics = LiquidGlassInkLayer.debugLastProcessing!;
          expect(metrics.builds - lastBuilds, lessThanOrEqualTo(1));
          lastBuilds = metrics.builds;
          expect(metrics.entries, lessThanOrEqualTo(2));
          expect(metrics.pixels, lessThanOrEqualTo(2 * 256 * 256));
        }
        final warm = LiquidGlassInkLayer.debugLastProcessing!;
        expect(warm.builds, 2);
        expect(warm.hits, greaterThan(1));
        final candidate = await _pixels(
          tester,
          key,
          '${brightness.name}-${mode.name}-cached',
        );
        var maximum = 0;
        var squared = 0.0;
        var channels = 0;
        for (var i = 0; i < direct.length; i++) {
          if (i % 4 == 3) {
            expect(candidate[i], direct[i]);
            continue;
          }
          final delta = (candidate[i] - direct[i]).abs();
          maximum = math.max(maximum, delta);
          squared += delta * delta;
          channels++;
        }
        final rms = math.sqrt(squared / channels);
        expect(maximum, lessThanOrEqualTo(6));
        expect(rms, lessThanOrEqualTo(1.5));
        await render(showInk: false);
        final under = await _pixels(
          tester,
          key,
          '${brightness.name}-${mode.name}-under',
        );
        final colors = auroraColorScheme(brightness);
        final contrasts = <Map<String, Object?>>[];
        for (var i = 0; i < 20; i++) {
          final rect = rectangles[i];
          final source = i.isEven ? colors.onSurface : colors.onSurfaceVariant;
          var supported = 0;
          var minimum = double.infinity;
          for (var y = rect.top.ceil(); y < rect.bottom.floor(); y++) {
            for (var x = rect.left.ceil(); x < rect.right.floor(); x++) {
              final ordinary = _pixel(plain, x, y);
              if ((ordinary.r - source.r).abs() > 1 / 255 ||
                  (ordinary.g - source.g).abs() > 1 / 255 ||
                  (ordinary.b - source.b).abs() > 1 / 255) {
                continue;
              }
              supported++;
              final actual = _pixel(candidate, x, y),
                  background = _pixel(under, x, y);
              final a = actual.computeLuminance(),
                  b = background.computeLuminance();
              minimum = math.min(
                minimum,
                (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05),
              );
            }
          }
          expect(
            supported,
            greaterThan(2),
            reason: 'sample $i must contain real opaque glyph pixels',
          );
          final icon =
              i % GlassInkRole.values.length == GlassInkRole.icon.index;
          expect(
            minimum,
            greaterThanOrEqualTo(icon ? 3.2 : 4.5),
            reason: 'sample $i',
          );
          contrasts.add({'sample': i, 'minimum': minimum, 'pixels': supported});
        }
        await render();
        final stable = LiquidGlassInkLayer.debugLastProcessing!;
        for (var i = 1; i <= 30; i++) {
          await render(time: Duration(milliseconds: i * 40));
        }
        final moving = LiquidGlassInkLayer.debugLastProcessing!;
        expect(moving.builds, stable.builds);
        expect(moving.entries, 2);
        expect(moving.pixels, lessThanOrEqualTo(2 * 256 * 256));
        final moved = await _pixels(
          tester,
          key,
          '${brightness.name}-${mode.name}-motion',
        );
        expect(moved, isNot(candidate));
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          File(
            '$output/ink-processing-${brightness.name}-${mode.name}.json',
          ).writeAsStringSync(
            jsonEncode({
              'maximumChannelError': maximum,
              'rms': rms,
              'builds': moving.builds,
              'hits': moving.hits,
              'misses': moving.misses,
              'pixels': moving.pixels,
              'entries': moving.entries,
              'buildCpuMicros': moving.buildCpuMicros,
              'timingMeaning':
                  'CPU command recording only; not GPU raster time',
              'staticLuminanceEstimate': {
                'directWorst': 19,
                'cachedFastWorst': 10,
                'poleBoundaryFallbackWorst': 19,
              },
              'contrast': contrasts,
            }),
          );
        }
        await tester.pumpWidget(const SizedBox());
        await tester.pump();
        expect(LiquidGlassInkLayer.debugProcessedImageCount, 0);
        expect(tester.takeException(), isNull);
      }, skip: !ui.ImageFilter.isShaderFilterSupported);
    }
  }

  testWidgets(
    'frozen ink retains its processed sampler after source disposal',
    (tester) async {
      _setupView(tester);
      LiquidGlassInkLayer.debugProcessedFieldOverride = true;
      final key = GlobalKey();
      Future<void> render({
        bool covered = false,
        bool replaceSource = false,
        int seed = 1,
      }) async {
        await tester.pumpWidget(
          _scene(
            key,
            brightness: Brightness.dark,
            covered: covered,
            replaceSource: replaceSource,
            seed: seed,
          ),
        );
        await tester.pump(const Duration(milliseconds: 40));
      }

      await tester.pumpWidget(
        _scene(
          key,
          brightness: Brightness.dark,
          mode: LiquidGlassMode.none,
          seed: 1,
        ),
      );
      await tester.pump(const Duration(milliseconds: 40));
      final ordinary = await _pixels(tester, key, 'frozen-sampler-plain');
      for (var i = 0; i < 4; i++) {
        await render();
      }
      expect(LiquidGlassInkLayer.debugProcessedImageCount, 2);
      await render(covered: true);
      final before = await _pixels(tester, key, 'frozen-sampler-before');
      expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 40);
      await render(covered: true, replaceSource: true, seed: 2);
      final after = await _pixels(
        tester,
        key,
        'frozen-sampler-source-disposed',
      );
      expect(LiquidGlassInkLayer.debugProcessedImageCount, 2);
      final body = tester.getRect(find.byKey(const ValueKey('sample-2')));
      final source = auroraColorScheme(Brightness.dark).onSurface;
      var preserved = 0;
      for (var y = body.top.ceil(); y < body.bottom.floor(); y++) {
        for (var x = body.left.ceil(); x < body.right.floor(); x++) {
          final a = _pixel(before, x, y), b = _pixel(after, x, y);
          if (_pixel(ordinary, x, y) == source) {
            expect(a, b);
            preserved++;
          }
        }
      }
      expect(preserved, greaterThan(3));
      for (var i = 0; i < 4; i++) {
        await render(replaceSource: true, seed: 2);
      }
      expect(LiquidGlassInkLayer.debugProcessedImageCount, 2);
      expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 0);
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      expect(LiquidGlassInkLayer.debugProcessedImageCount, 0);
      expect(tester.takeException(), isNull);
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );

  testWidgets('changing sources and crossfade cannot reuse stale processing', (
    tester,
  ) async {
    _setupView(tester);
    final key = GlobalKey();
    LiquidGlassInkLayer.debugProcessedFieldOverride = true;
    Future<void> render({
      int seed = 1,
      Brightness brightness = Brightness.light,
      bool crossfade = false,
      bool advance = true,
    }) async {
      await tester.pumpWidget(
        _scene(key, brightness: brightness, seed: seed, crossfade: crossfade),
      );
      if (advance) await tester.pump(const Duration(milliseconds: 40));
    }

    for (var i = 0; i < 4; i++) {
      await render();
    }
    expect(LiquidGlassInkLayer.debugLastProcessing!.builds, 2);
    await render(seed: 2, advance: false);
    expect(LiquidGlassInkLayer.debugLastProcessing!.stableFrames, 1);
    final first = await _pixels(tester, key, 'changed-source-first-frame');
    LiquidGlassInkLayer.debugProcessedFieldOverride = false;
    await render(seed: 2);
    final reference = await _pixels(tester, key, 'changed-source-direct');
    expect(first, reference);
    LiquidGlassInkLayer.debugProcessedFieldOverride = true;
    for (var i = 0; i < 4; i++) {
      await render(seed: 2, brightness: Brightness.dark);
    }
    expect(
      LiquidGlassInkLayer.debugLastProcessing!.pixels,
      lessThanOrEqualTo(2 * 256 * 256),
    );
    tester.view.physicalSize = const Size(430, 760);
    await render(seed: 2, brightness: Brightness.dark, advance: false);
    expect(LiquidGlassInkLayer.debugLastProcessing!.stableFrames, 1);
    for (var i = 0; i < 4; i++) {
      await render(seed: 10 + i, crossfade: true, advance: false);
    }
    expect(LiquidGlassInkLayer.debugLastProcessing!.stableFrames, 1);
    expect(LiquidGlassInkLayer.debugProcessedImageCount, lessThanOrEqualTo(2));
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(LiquidGlassInkLayer.debugProcessedImageCount, 0);
    expect(tester.takeException(), isNull);
  }, skip: !ui.ImageFilter.isShaderFilterSupported);
}
