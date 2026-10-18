import 'package:fl_clash/application.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'helpers/test_profiles.dart';

Widget _themeManager(Widget child) => ThemeManager(child: child);

void _androidTest(
  String description,
  Future<void> Function(WidgetTester tester) body,
) {
  testWidgets(description, (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    try {
      await body(tester);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });
}

Future<Color> _pixel(WidgetTester tester, GlobalKey key, {Offset? at}) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  late Color color;
  await tester.runAsync(() async {
    final image = await boundary.toImage();
    final data = (await image.toByteData())!;
    final x = at?.dx.toInt() ?? image.width ~/ 2;
    final y = at?.dy.toInt() ?? image.height ~/ 2;
    final index = (y * image.width + x) * 4;
    color = Color.fromARGB(
      data.getUint8(index + 3),
      data.getUint8(index),
      data.getUint8(index + 1),
      data.getUint8(index + 2),
    );
    image.dispose();
  });
  return color;
}

void main() {
  _androidTest('theme page buttons change the real Application painted theme', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(430, 932);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        appSettingProvider.overrideWithBuild(
          (_, _) => const AppSettingProps(locale: 'en'),
        ),
        themeSettingProvider.overrideWithBuild(
          (_, _) =>
              const ThemeProps(
                themeMode: ThemeMode.dark,
                auroraEnabled: false,
                liquidGlass: LiquidGlassMode.none,
              ),
        ),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    final shotKey = GlobalKey();
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: RepaintBoundary(
          key: shotKey,
          child: const Application.forTesting(
            wrapManagers: _themeManager,
            home: ThemeView(),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    for (final (label, mode, brightness) in [
      ('Light', ThemeMode.light, Brightness.light),
      ('Dark', ThemeMode.dark, Brightness.dark),
      ('Light', ThemeMode.light, Brightness.light),
    ]) {
      await tester.tap(find.text(label).first);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(container.read(themeSettingProvider).themeMode, mode);
      expect(
        tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        mode,
      );
      expect(
        Theme.of(tester.element(find.byType(ThemeView))).brightness,
        brightness,
      );
      final color = await _pixel(tester, shotKey, at: const Offset(2, 500));
      expect(
        color.computeLuminance(),
        brightness == Brightness.light ? greaterThan(0.5) : lessThan(0.1),
      );
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final wallpaper in [false, true]) {
    for (final reducedMotion in [false, true]) {
      _androidTest(
        'Application switches light/dark/system with wallpaper=$wallpaper '
        'and reducedMotion=$reducedMotion',
        (tester) async {
          tester.view.physicalSize = const Size(120, 240);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          tester.platformDispatcher.platformBrightnessTestValue =
              Brightness.dark;
          addTearDown(
            tester.platformDispatcher.clearPlatformBrightnessTestValue,
          );
          tester.platformDispatcher.accessibilityFeaturesTestValue =
              FakeAccessibilityFeatures(disableAnimations: reducedMotion);
          addTearDown(
            tester.platformDispatcher.clearAccessibilityFeaturesTestValue,
          );
          final container = ProviderContainer(
            overrides: [
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(
                  themeMode: ThemeMode.dark,
                  auroraEnabled: wallpaper,
                ),
              ),
            ],
          );
          addTearDown(container.dispose);
          final shotKey = GlobalKey();
          Brightness? leafBrightness;
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: RepaintBoundary(
                key: shotKey,
                child: Application.forTesting(
                  wrapManagers: _themeManager,
                  home: Builder(
                    builder: (context) {
                      final theme = Theme.of(context);
                      leafBrightness = theme.brightness;
                      return ColoredBox(color: theme.colorScheme.surface);
                    },
                  ),
                ),
              ),
            ),
          );

          Future<void> expectBrightness(Brightness expected) async {
            if (wallpaper && !reducedMotion) {
              await tester.pump();
              await tester.pump(const Duration(milliseconds: 500));
            } else {
              await tester.pumpAndSettle();
            }
            expect(tester.takeException(), isNull);
            expect(leafBrightness, expected);
            final material = tester.widget<MaterialApp>(
              find.byType(MaterialApp),
            );
            expect(material.theme!.brightness, Brightness.light);
            expect(material.darkTheme!.brightness, Brightness.dark);
            final color = await _pixel(tester, shotKey);
            expect(
              color.computeLuminance(),
              expected == Brightness.light ? greaterThan(0.5) : lessThan(0.1),
              reason: 'the final painted frame must match the live theme',
            );
            if (wallpaper) {
              final background = tester.widget<SeededAuroraWallpaper>(
                find.byType(SeededAuroraWallpaper),
              );
              expect(background.brightness, expected);
            }
          }

          void setMode(ThemeMode mode) {
            container
                .read(themeSettingProvider.notifier)
                .update((state) => state.copyWith(themeMode: mode));
          }

          final saved = container.read(themeSettingProvider);
          await expectBrightness(Brightness.dark);
          setMode(ThemeMode.light);
          await expectBrightness(Brightness.light);
          setMode(ThemeMode.dark);
          await expectBrightness(Brightness.dark);
          setMode(ThemeMode.light);
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 40));
          setMode(ThemeMode.dark);
          await expectBrightness(Brightness.dark);
          setMode(ThemeMode.system);
          await expectBrightness(Brightness.dark);
          tester.platformDispatcher.platformBrightnessTestValue =
              Brightness.light;
          await expectBrightness(Brightness.light);
          tester.platformDispatcher.platformBrightnessTestValue =
              Brightness.dark;
          await expectBrightness(Brightness.dark);
          expect(
            container.read(themeSettingProvider).auroraSeed,
            saved.auroraSeed,
          );
          expect(
            container.read(themeSettingProvider).auroraColors,
            saved.auroraColors,
          );
          await tester.pumpWidget(const SizedBox.shrink());
        },
      );
    }
  }
}
