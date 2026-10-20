import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/common/aurora_rotation.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/aurora_settings.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/widgets/palette.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer();
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(430, 900);
    container.listen(themeSettingProvider, (_, _) {});
  });

  tearDown(() => container.dispose());

  ThemeProps readTheme() => container.read(themeSettingProvider);

  Future<void> pumpSettings(
    WidgetTester tester, {
    Brightness brightness = Brightness.light,
  }) async {
    tester.view.physicalSize = const Size(430, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: Theme(
            data: ThemeData(brightness: brightness),
            child: const Scaffold(
              body: SingleChildScrollView(child: AuroraSettings()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final brightness in Brightness.values) {
    for (final mode in LiquidGlassMode.values) {
      testWidgets('wallpaper swatches retain their RGBA throughout the fill '
          '($brightness, $mode, DPR 3)', (tester) async {
        tester.view.physicalSize = const Size(1290, 2700);
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        const colors = [0xFFFF0000, 0xFF00FF00, 0xFF0000FF];
        container
            .read(themeSettingProvider.notifier)
            .update(
              (state) => state.copyWith(
                auroraEnabled: true,
                auroraColors: colors,
                liquidGlass: mode,
              ),
            );
        const captureKey = ValueKey('wallpaperColorSwatches');
        try {
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: TestApp(
                theme: ThemeData(
                  brightness: brightness,
                  platform: TargetPlatform.android,
                ),
                child: RepaintBoundary(
                  key: captureKey,
                  child: const AuroraAppSurface(
                    motion: false,
                    child: Scaffold(
                      body: SingleChildScrollView(child: AuroraSettings()),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final boundary = tester.renderObject<RenderRepaintBoundary>(
            find.byKey(captureKey),
          );
          final image = await tester.runAsync(
            () => boundary.toImage(pixelRatio: 3),
          );
          try {
            final data = (await tester.runAsync(
              () => image!.toByteData(format: ui.ImageByteFormat.rawRgba),
            ))!;
            final bytes = data.buffer.asUint8List();
            for (var index = 0; index < colors.length; index++) {
              final button = find.byTooltip(
                'Edit wallpaper color ${index + 1}',
              );
              expect(
                LiquidGlassScope.of(tester.element(button)),
                mode != LiquidGlassMode.none,
              );
              final swatch = tester.getRect(button);
              for (final offset in const [
                Offset.zero,
                Offset(-16, 0),
                Offset(16, 0),
                Offset(0, -16),
                Offset(0, 16),
                Offset(-12, -12),
              ]) {
                final point = (swatch.center + offset) * 3;
                final pixel =
                    (point.dy.floor() * image!.width + point.dx.floor()) * 4;
                final actual = Color.fromARGB(
                  bytes[pixel + 3],
                  bytes[pixel],
                  bytes[pixel + 1],
                  bytes[pixel + 2],
                );
                expect(
                  actual.toARGB32(),
                  colors[index],
                  reason: 'swatch $index at $offset',
                );
              }
            }
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              final png = (await tester.runAsync(
                () => image!.toByteData(format: ui.ImageByteFormat.png),
              ))!;
              final directory = Directory(output)..createSync(recursive: true);
              File(
                '${directory.path}/aurora-swatches-${brightness.name}-${mode.name}-3x.png',
              ).writeAsBytesSync(png.buffer.asUint8List());
            }
          } finally {
            image!.dispose();
          }
          await tester.tap(find.byTooltip('Edit wallpaper color 1'));
          await tester.pumpAndSettle();
          expect(find.byType(Palette), findsOneWidget);
          expect(container.read(themeSettingProvider).auroraColors, colors);
          await tester.tap(find.text('Cancel'));
          await tester.pumpAndSettle();
          expect(find.byType(Palette), findsNothing);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
        }
      });
    }
  }

  testWidgets('palette contains no duplicate mode toggle and keeps its seed', (
    tester,
  ) async {
    await pumpSettings(tester);
    final original = readTheme();
    expect(find.byType(SeededAuroraWallpaper), findsOneWidget);

    expect(find.text('Random gradient wallpaper'), findsNothing);
    expect(find.byType(Switch), findsNothing);
    container
        .read(themeSettingProvider.notifier)
        .update((state) => state.copyWith(auroraEnabled: false));
    await tester.pumpAndSettle();
    expect(readTheme().auroraEnabled, isFalse);
    expect(find.byType(SeededAuroraWallpaper), findsNothing);

    container
        .read(themeSettingProvider.notifier)
        .update((state) => state.copyWith(auroraEnabled: true));
    await tester.pumpAndSettle();
    expect(readTheme(), original);
  });

  for (final brightness in Brightness.values) {
    testWidgets('preview uses the selected palette and mode ($brightness)', (
      tester,
    ) async {
      const colors = [0xFF6BD5D9, 0xFFC6ADE4, 0xFFA9DAC6];
      final seed = readTheme().auroraSeed;
      for (var count = 1; count <= 3; count++) {
        final palette = colors.take(count).toList();
        container
            .read(themeSettingProvider.notifier)
            .update((state) => state.copyWith(auroraColors: palette));
        await pumpSettings(tester, brightness: brightness);
        final preview = tester.widget<SeededAuroraWallpaper>(
          find.byType(SeededAuroraWallpaper),
        );
        expect(preview.brightness, brightness);
        expect(preview.colors.map((color) => color.toARGB32()), palette);
        expect(preview.seed, seed);
        expect(
          tester.getSize(find.byType(SeededAuroraWallpaper)).height,
          lessThan(160),
        );
        expect(readTheme().auroraSeed, seed);
      }
    });
  }

  testWidgets('palette and composition changes preserve every glass selection', (tester) async {
    for (final mode in LiquidGlassMode.values) {
      container.read(themeSettingProvider.notifier).update((state) => state.copyWith(
        auroraEnabled: true,
        liquidGlass: mode,
      ));
      await pumpSettings(tester);
      final saved = readTheme();
      await tester.tap(find.byTooltip('Edit wallpaper color 1'));
      await tester.pumpAndSettle();
      tester.widget<Palette>(find.byType(Palette)).controller.value = const Color(0xFF338855);
      await tester.pump();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();
      final recolored = saved.copyWith(auroraColors: [0xFF338855, ...saved.auroraColors.skip(1)]);
      expect(readTheme(), recolored);
      await tester.tap(find.text('New wallpaper'));
      await tester.pumpAndSettle();
      expect(readTheme().auroraSeed, isNot(recolored.auroraSeed));
      expect(readTheme().liquidGlass, mode);
      expect(readTheme().auroraColors, recolored.auroraColors);
      expect(readTheme().auroraEnabled, isTrue);
    }
  });

  testWidgets('only a requested new wallpaper changes its seed', (
    tester,
  ) async {
    await pumpSettings(tester);
    final original = readTheme();
    await tester.pumpAndSettle();
    expect(readTheme().auroraSeed, original.auroraSeed);

    await tester.tap(find.text('New wallpaper'));
    await tester.pumpAndSettle();
    expect(readTheme().auroraSeed, isNot(original.auroraSeed));
    expect(readTheme().auroraColors, original.auroraColors);
    final now = DateTime.now();
    expect(readTheme().auroraRotationDay, auroraRotationDayOf(now));
    final seed = readTheme().auroraSeed;
    await tester.pumpAndSettle();
    expect(readTheme().auroraSeed, seed);
    expect(
      tester
          .widget<SeededAuroraWallpaper>(find.byType(SeededAuroraWallpaper))
          .seed,
      seed,
    );
  });

  testWidgets('adds a third color and hides the add action', (tester) async {
    container
        .read(themeSettingProvider.notifier)
        .update(
          (state) => state.copyWith(auroraColors: [0xFF9AAFC5, 0xFFC4BDD3]),
        );
    await pumpSettings(tester);
    final seed = readTheme().auroraSeed;
    await tester.tap(find.byTooltip('Add'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(readTheme().auroraColors, hasLength(3));
    expect(readTheme().auroraSeed, seed);
    expect(find.byTooltip('Add'), findsNothing);
  });

  testWidgets('removes a color but cannot remove the last one', (tester) async {
    container
        .read(themeSettingProvider.notifier)
        .update(
          (state) => state.copyWith(auroraColors: [0xFF9AAFC5, 0xFFC4BDD3]),
        );
    await pumpSettings(tester);
    final seed = readTheme().auroraSeed;
    await tester.tap(find.byTooltip('Edit wallpaper color 1'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();
    expect(readTheme().auroraColors, [0xFFC4BDD3]);
    expect(readTheme().auroraSeed, seed);

    await tester.tap(find.byTooltip('Edit wallpaper color 1'));
    await tester.pumpAndSettle();
    expect(find.text('Delete'), findsNothing);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
  });

  testWidgets('palette previews edits and cancel keeps the saved settings', (
    tester,
  ) async {
    await pumpSettings(tester);
    final original = readTheme();
    await tester.tap(find.byTooltip('Edit wallpaper color 1'));
    await tester.pumpAndSettle();
    await tester.tap(find.text(currentAppLocalizations.sliders));
    await tester.pumpAndSettle();
    final slider = tester.widget<Slider>(find.byType(Slider).first);
    slider.onChanged!(120);
    await tester.pump();
    final previews = tester.widgetList<SeededAuroraWallpaper>(
      find.byType(SeededAuroraWallpaper),
    );
    expect(
      previews.last.colors.first.toARGB32(),
      isNot(original.auroraColors.first),
    );
    expect(previews.last.seed, original.auroraSeed);
    expect(readTheme(), original);

    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(readTheme(), original);
  });
}
