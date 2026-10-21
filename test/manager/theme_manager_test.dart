import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  testWidgets('wallpaper and saved glass mode remain independent', (
    tester,
  ) async {
    final container = ProviderContainer(
      overrides: [
        themeSettingProvider.overrideWithBuild(
          (_, _) => const ThemeProps(
            auroraEnabled: true,
            liquidGlass: LiquidGlassMode.none,
          ),
        ),
      ],
    );
    addTearDown(container.dispose);
    bool? enabled;
    bool? wallpaperEnabled;
    LiquidGlassQuality? quality;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: ThemeData(platform: TargetPlatform.android),
          builder: (context, child) =>
              AuroraAppSurface(motion: false, child: child!),
          home: ThemeManager(
            child: Builder(
              builder: (context) {
                enabled = LiquidGlassScope.of(context);
                wallpaperEnabled = AuroraAppearance.enabledOf(context);
                quality = LiquidGlassScope.qualityOf(context);
                return const SizedBox.shrink();
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(enabled, isFalse);
    expect(wallpaperEnabled, isTrue);
    expect(
      container.read(themeSettingProvider).liquidGlass,
      LiquidGlassMode.none,
    );
    for (final mode in LiquidGlassMode.values) {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(liquidGlass: mode));
      for (final wallpaper in [true, false, true, false]) {
        container
            .read(themeSettingProvider.notifier)
            .update((state) => state.copyWith(auroraEnabled: wallpaper));
        await tester.pumpAndSettle();
        expect(enabled, mode != LiquidGlassMode.none);
        expect(wallpaperEnabled, wallpaper);
        expect(container.read(themeSettingProvider).liquidGlass, mode);
        expect(container.read(themeSettingProvider).auroraEnabled, wallpaper);
        if (mode != LiquidGlassMode.none) {
          expect(
            quality,
            mode == LiquidGlassMode.standard
                ? LiquidGlassQuality.standard
                : LiquidGlassQuality.ultra,
          );
        }
      }
    }
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });

  testWidgets('following the system picks up a font scale changed while the '
      'app runs', (tester) async {
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    double? scaled;
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: ThemeManager(
            child: Builder(
              builder: (context) {
                scaled = MediaQuery.textScalerOf(context).scale(10);
                return const SizedBox.shrink();
              },
            ),
          ),
        ),
      ),
    );

    expect(scaled, 10);

    tester.platformDispatcher.textScaleFactorTestValue = 1.3;
    await tester.pump();

    expect(scaled, closeTo(13, 1e-9));
  });

  testWidgets('a theme setting the theme does not read keeps the same theme', (
    tester,
  ) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final themes = <ThemeData>[];
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: Consumer(
          builder: (_, ref, _) {
            ref.watch(themeSettingProvider);
            themes.add(watchAppTheme(ref, Brightness.dark));
            return const SizedBox.shrink();
          },
        ),
      ),
    );
    void update(ThemeProps Function(ThemeProps props) change) {
      final notifier = container.read(themeSettingProvider.notifier);
      notifier.value = change(notifier.value);
    }

    update((props) => props.copyWith(sidebarBlur: !props.sidebarBlur));
    await tester.pump();
    update((props) => props.copyWith(pureBlack: !props.pureBlack));
    await tester.pump();

    expect(themes, hasLength(3));
    expect(themes[1], same(themes[0]));
    expect(themes[2], isNot(same(themes[1])));
  });

  testWidgets(
    'text selection keeps the seed accent under the wallpaper',
    variant: TargetPlatformVariant.only(TargetPlatform.android),
    (tester) async {
      for (final brightness in Brightness.values) {
        for (final mode in LiquidGlassMode.values) {
          late ThemeData appTheme;
          late BuildContext page;
          await tester.pumpWidget(
            ProviderScope(
              key: ValueKey((brightness, mode)),
              overrides: [
                themeSettingProvider.overrideWithBuild(
                  (_, _) => ThemeProps(liquidGlass: mode),
                ),
              ],
              child: Consumer(
                builder: (_, ref, _) {
                  appTheme = watchAppTheme(ref, brightness);
                  return MaterialApp(
                    theme: appTheme,
                    themeAnimationStyle: AnimationStyle.noAnimation,
                    builder: (context, child) =>
                        AuroraAppSurface(motion: false, child: child!),
                    home: Builder(
                      builder: (context) {
                        page = context;
                        return const SizedBox.shrink();
                      },
                    ),
                  );
                },
              ),
            ),
          );
          final seed = appTheme.colorScheme.primary;
          expect(
            appTheme.textSelectionTheme,
            TextSelectionThemeData(
              cursorColor: seed,
              selectionColor: seed.withValues(alpha: 0.3),
              selectionHandleColor: seed,
            ),
          );
          expect(
            Theme.of(page).colorScheme.primary,
            auroraColorScheme(brightness).primary,
          );
          expect(LiquidGlassScope.of(page), mode != LiquidGlassMode.none);
          expect(TextSelectionTheme.of(page), appTheme.textSelectionTheme);
          expect(DefaultSelectionStyle.of(page).cursorColor, seed);
          expect(
            DefaultSelectionStyle.of(page).selectionColor,
            seed.withValues(alpha: 0.3),
          );
        }
      }
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
}
