import 'dart:convert';
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
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/system_test_font.dart';

void main() {
  late ProviderContainer container;

  setUpAll(() async {
    await (FontLoader('AuroraDialogRoboto')..addFont(loadSdkTestFont())).load();
  });

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
    Size size = const Size(430, 900),
    ThemeData? theme,
    GlobalKey? captureKey,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    container.read(viewSizeProvider.notifier).value = size;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final app = TestApp(
      theme: theme,
      child: Theme(
        data: theme ?? ThemeData(brightness: brightness),
        child: const Scaffold(
          body: SingleChildScrollView(child: AuroraSettings()),
        ),
      ),
    );
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: captureKey == null
            ? app
            : RepaintBoundary(key: captureKey, child: app),
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final brightness in Brightness.values) {
    testWidgets('wallpaper preview follows the saved dark base $brightness', (
      tester,
    ) async {
      await pumpSettings(tester, brightness: brightness);
      for (final pureBlack in [true, false]) {
        container
            .read(themeSettingProvider.notifier)
            .update((state) => state.copyWith(pureBlack: pureBlack));
        await tester.pumpAndSettle();
        expect(
          tester
              .widget<SeededAuroraWallpaper>(find.byType(SeededAuroraWallpaper))
              .pureBlack,
          pureBlack,
        );
        expect(readTheme().pureBlack, pureBlack);
      }
    });
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

    expect(find.text('Living Aurora'), findsNothing);
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

  testWidgets(
    'palette and composition changes preserve every glass selection',
    (tester) async {
      for (final mode in LiquidGlassMode.values) {
        container
            .read(themeSettingProvider.notifier)
            .update(
              (state) => state.copyWith(auroraEnabled: true, liquidGlass: mode),
            );
        await pumpSettings(tester);
        final saved = readTheme();
        await tester.tap(find.byTooltip('Edit wallpaper color 1'));
        await tester.pumpAndSettle();
        tester.widget<Palette>(find.byType(Palette)).controller.value =
            const Color(0xFF338855);
        await tester.pump();
        await tester.tap(find.text('Confirm'));
        await tester.pumpAndSettle();
        final recolored = saved.copyWith(
          auroraColors: [0xFF338855, ...saved.auroraColors.skip(1)],
        );
        expect(readTheme(), recolored);
        await tester.tap(find.text('New wallpaper'));
        await tester.pumpAndSettle();
        expect(readTheme().auroraSeed, isNot(recolored.auroraSeed));
        expect(readTheme().liquidGlass, mode);
        expect(readTheme().auroraColors, recolored.auroraColors);
        expect(readTheme().auroraEnabled, isTrue);
      }
    },
  );

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

  for (final (size, scale) in [
    (const Size(428, 628), 1.0),
    (const Size(390, 480), 1.6),
  ]) {
    testWidgets('short palette keeps whole pads and actions reachable '
        '${size.height} scale $scale', (tester) async {
      tester.platformDispatcher.textScaleFactorTestValue = scale;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      container
          .read(themeSettingProvider.notifier)
          .update(
            (state) => state.copyWith(
              auroraEnabled: true,
              auroraColors: [0xFF9AAFC5, 0xFFC4BDD3, 0xFFA9DAC6],
            ),
          );
      final scene = GlobalKey();
      await pumpSettings(
        tester,
        size: size,
        theme: ThemeData(fontFamily: 'AuroraDialogRoboto'),
        captureKey: scene,
      );
      final checks = <Map<String, Object?>>[];
      final spectrumScrolls = <Map<String, Object?>>[];
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      final label = '${size.height.toInt()}-$scale';
      Future<void> capture(String name) async {
        if (output == null) return;
        await tester.runAsync(() async {
          final boundary =
              scene.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          final image = await boundary.toImage(pixelRatio: 1);
          try {
            final directory = Directory('$output/aurora-picker/$label');
            await directory.create(recursive: true);
            for (final (suffix, format) in [
              ('png', ui.ImageByteFormat.png),
              ('rgba', ui.ImageByteFormat.rawRgba),
            ]) {
              final bytes = (await image.toByteData(format: format))!;
              await File('${directory.path}/$name.$suffix').writeAsBytes(
                bytes.buffer.asUint8List(
                  bytes.offsetInBytes,
                  bytes.lengthInBytes,
                ),
              );
            }
          } finally {
            image.dispose();
          }
        });
      }

      final pad = find.descendant(
        of: find.byType(Palette),
        matching: find.byType(AspectRatio),
      );
      void recordPad(String name) {
        final rect = tester.getRect(pad);
        final scroll = find
            .ancestor(of: pad, matching: find.byType(SingleChildScrollView))
            .first;
        final viewport = tester.getRect(scroll);
        final render = tester.renderObject<RenderBox>(pad);
        final corners = [
          rect.topLeft + const Offset(6, 6),
          rect.bottomRight - const Offset(6, 6),
        ];
        final hit = corners.every(
          (point) => tester
              .hitTestOnBinding(point)
              .path
              .any((entry) => identical(entry.target, render)),
        );
        checks.add({
          'state': name,
          'pad': [rect.left, rect.top, rect.right, rect.bottom],
          'viewport': [
            viewport.left,
            viewport.top,
            viewport.right,
            viewport.bottom,
          ],
          'full': corners.every(viewport.contains) && hit,
          'cornerHits': hit,
        });
        expect(rect.width / rect.height, closeTo(1.2, 0.001));
      }

      for (final action in ['Cancel', 'Confirm', 'Delete']) {
        final before = readTheme();
        final edit = find.byTooltip('Edit wallpaper color 1');
        await tester.ensureVisible(edit);
        await tester.tap(edit);
        await tester.pumpAndSettle();
        expect(find.byType(CommonDialog), findsOneWidget);
        if (scale == 1) {
          recordPad('$action-grid-open');
          await capture('$action-grid-open');
        }
        final preview = find.descendant(
          of: find.byType(CommonDialog),
          matching: find.byType(SeededAuroraWallpaper),
        );
        final position = Scrollable.of(tester.element(pad)).position;
        final oldOffset = position.pixels;
        await tester.drag(preview, const Offset(0, -180));
        await tester.pumpAndSettle();
        expect(position.pixels, greaterThan(oldOffset));
        recordPad('$action-grid');
        await capture('$action-grid');
        final gridCenter = tester.getRect(pad).center;
        if (tester
            .hitTestOnBinding(gridCenter)
            .path
            .any(
              (entry) => identical(entry.target, tester.renderObject(pad)),
            )) {
          await tester.tapAt(gridCenter);
          await tester.pump();
        }
        await tester.ensureVisible(find.text('Spectrum'));
        await tester.tap(find.text('Spectrum'));
        await tester.pumpAndSettle();
        final spectrumViewport = tester.getRect(
          find
              .ancestor(of: pad, matching: find.byType(SingleChildScrollView))
              .first,
        );
        final spectrumBefore = tester.getRect(pad);
        final overflow = spectrumBefore.bottom - spectrumViewport.bottom;
        spectrumScrolls.add({
          'action': action,
          'padBefore': [spectrumBefore.top, spectrumBefore.bottom],
          'viewport': [spectrumViewport.top, spectrumViewport.bottom],
          'overflow': overflow,
        });
        if (overflow > 0) {
          final gesture = await tester.startGesture(
            tester.getCenter(find.text('Spectrum')),
          );
          await gesture.moveBy(const Offset(0, -24));
          await tester.pump();
          final current = tester.getRect(pad);
          final correction = current.bottom > spectrumViewport.bottom
              ? spectrumViewport.bottom - current.bottom
              : current.top < spectrumViewport.top
              ? spectrumViewport.top - current.top
              : 0.0;
          if (correction != 0) {
            await gesture.moveBy(Offset(0, correction));
            await tester.pump();
          }
          await gesture.cancel();
          await tester.pumpAndSettle();
        }
        recordPad('$action-spectrum');
        await capture('$action-spectrum');
        await tester.ensureVisible(find.text('Sliders'));
        await tester.tap(find.text('Sliders'));
        await tester.pumpAndSettle();
        final slider = find.byType(Slider).first;
        await tester.ensureVisible(slider);
        expect(slider.hitTestable(), findsOneWidget);
        final track = tester.getRect(slider);
        await tester.tapAt(track.center + Offset(track.width * 0.2, 0));
        await tester.pumpAndSettle();
        final selected = tester
            .widget<Palette>(find.byType(Palette))
            .controller
            .value
            .toARGB32();
        expect(readTheme(), before);
        await capture('$action-sliders');
        for (final label in ['Cancel', 'Confirm', 'Delete']) {
          final button = find.widgetWithText(TextButton, label);
          await tester.ensureVisible(button);
          await tester.pumpAndSettle();
          expect(button.hitTestable(), findsOneWidget, reason: label);
        }
        final button = find.widgetWithText(TextButton, action);
        await tester.ensureVisible(button);
        await tester.tap(button);
        await tester.pumpAndSettle();
        expect(find.byType(Palette), findsNothing);
        final expected = switch (action) {
          'Cancel' => before,
          'Confirm' => before.copyWith(
            auroraColors: [selected, ...before.auroraColors.skip(1)],
          ),
          _ => before.copyWith(
            auroraColors: before.auroraColors.skip(1).toList(),
          ),
        };
        expect(readTheme(), expected);
        expect(readTheme().auroraSeed, before.auroraSeed);
      }
      if (output != null) {
        await tester.runAsync(
          () => File('$output/aurora-picker/$label/work.json').writeAsString(
            jsonEncode({
              'size': [size.width, size.height],
              'textScale': scale,
              'checks': checks,
              'spectrumScrolls': spectrumScrolls,
            }),
          ),
        );
      }
      expect(tester.takeException(), isNull);
      expect(
        checks.where((check) => check['full'] != true),
        isEmpty,
        reason:
            'A picker pad must fit wholly inside the scrolled viewport and '
            'hit-test at both corners; exposing only its center is insufficient',
      );
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    });
  }

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
