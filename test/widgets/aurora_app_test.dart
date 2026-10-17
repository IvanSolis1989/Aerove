import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/context.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/ui/card.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

void main() {
  Future<void> pumpSurface(
    WidgetTester tester, {
    ThemeProps props = const ThemeProps(),
    TargetPlatform platform = TargetPlatform.android,
    Brightness brightness = Brightness.light,
    bool highContrast = false,
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        key: UniqueKey(),
        overrides: [themeSettingProvider.overrideWithBuild((_, _) => props)],
        child: MaterialApp(
          theme: ThemeData(
            platform: platform,
            colorScheme: ColorScheme.fromSeed(
              seedColor: Color(props.primaryColor ?? 0xFF007AFF),
              brightness: brightness,
            ),
          ),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(highContrast: highContrast),
            child: AuroraAppSurface(child: child!),
          ),
          home: const Scaffold(
            body: Center(child: CommonCard(child: Text('XClash'))),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('Android uses one wallpaper and cards do not blur each row', (
    tester,
  ) async {
    await pumpSurface(tester);
    expect(find.byType(SeededAuroraWallpaper), findsOneWidget);
    expect(find.byType(BackdropFilter), findsNothing);
    final context = tester.element(find.byType(CommonCard));
    expect(AuroraAppearance.enabledOf(context), isTrue);
    expect(
      AuroraAppearance.surfaceColorOf(context, Colors.white).a,
      lessThan(0.5),
    );
  });

  testWidgets(
    'wallpaper removes the saved accent from light and dark controls',
    (tester) async {
      const pink = Color(0xFFFF2D55);
      for (final brightness in Brightness.values) {
        await pumpSurface(
          tester,
          brightness: brightness,
          props: const ThemeProps(
            primaryColor: 0xFFFF2D55,
            liquidGlass: LiquidGlassMode.standard,
          ),
        );
        final context = tester.element(find.byType(CommonCard));
        final theme = Theme.of(context);
        expect(theme.colorScheme.primary, isNot(pink));
        expect(
          theme.colorScheme.primary.r,
          closeTo(theme.colorScheme.primary.g, 0.025),
        );
        expect(theme.colorScheme.onSurface.a, 0.94);
        expect(
          theme.colorScheme.onSurface.withValues(alpha: 1),
          auroraColorScheme(brightness).onSurface,
        );
        final button = tester.widget<FilledButton>(
          find.descendant(
            of: find.byType(CommonCard),
            matching: find.byType(FilledButton),
          ),
        );
        expect(button.style!.backgroundColor!.resolve({}), Colors.transparent);
        expect(theme.filledButtonTheme.style!.backgroundBuilder, isNotNull);
        expect(find.byType(LiquidGlassHost), findsOneWidget);
        expect(
          find.ancestor(
            of: find.byType(SeededAuroraWallpaper),
            matching: find.byType(LiquidGlassHost),
          ),
          findsOneWidget,
        );
      }
    },
  );

  testWidgets('wallpaper enables effective glass without rewriting the mode', (
    tester,
  ) async {
    await pumpSurface(
      tester,
      props: const ThemeProps(liquidGlass: LiquidGlassMode.none),
    );
    final context = tester.element(find.byType(CommonCard));
    expect(AuroraAppearance.enabledOf(context), isTrue);
    expect(LiquidGlassScope.of(context), isTrue);
    expect(LiquidGlassScope.qualityOf(context), LiquidGlassQuality.ultra);
    expect(
      tester.widget<LiquidGlassHost>(find.byType(LiquidGlassHost)).enabled,
      isTrue,
    );
    expect(
      Theme.of(context).filledButtonTheme.style!.backgroundBuilder,
      isNotNull,
    );
  });

  testWidgets('system brightness reaches the app and saved wallpaper', (
    tester,
  ) async {
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    final container = ProviderContainer();
    addTearDown(container.dispose);
    const probeKey = ValueKey('theme-brightness');
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: Consumer(
          builder: (context, ref, _) => MaterialApp(
            themeMode: ref.watch(themeSettingProvider).themeMode,
            theme: ThemeData(
              platform: TargetPlatform.android,
              brightness: Brightness.light,
            ),
            darkTheme: ThemeData(
              platform: TargetPlatform.android,
              brightness: Brightness.dark,
            ),
            builder: (context, child) => AuroraAppSurface(child: child!),
            home: const Scaffold(body: SizedBox(key: probeKey)),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    void expectBrightness(Brightness brightness) {
      expect(
        Theme.of(tester.element(find.byKey(probeKey))).brightness,
        brightness,
      );
      final wallpaper = tester.widget<SeededAuroraWallpaper>(
        find.byType(SeededAuroraWallpaper),
      );
      expect(wallpaper.brightness, brightness);
      expect(wallpaper.seed, 271828);
    }

    expectBrightness(Brightness.light);
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    await tester.pumpAndSettle();
    expectBrightness(Brightness.dark);
    for (final (mode, systemBrightness, expected) in [
      (ThemeMode.light, Brightness.dark, Brightness.light),
      (ThemeMode.dark, Brightness.light, Brightness.dark),
      (ThemeMode.system, Brightness.light, Brightness.light),
    ]) {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(themeMode: mode));
      tester.platformDispatcher.platformBrightnessTestValue = systemBrightness;
      await tester.pumpAndSettle();
      expectBrightness(expected);
    }
  });

  testWidgets(
    'disabled, high contrast, pure black and desktop keep opaque surfaces',
    (tester) async {
      await pumpSurface(tester, props: const ThemeProps(auroraEnabled: false));
      expect(find.byType(SeededAuroraWallpaper), findsNothing);
      await pumpSurface(tester, highContrast: true);
      expect(find.byType(SeededAuroraWallpaper), findsNothing);
      await pumpSurface(
        tester,
        brightness: Brightness.dark,
        props: const ThemeProps(pureBlack: true),
      );
      expect(find.byType(SeededAuroraWallpaper), findsNothing);
      await pumpSurface(tester, platform: TargetPlatform.windows);
      expect(find.byType(SeededAuroraWallpaper), findsNothing);
      final context = tester.element(find.byType(CommonCard));
      expect(AuroraAppearance.surfaceColorOf(context, Colors.red), Colors.red);
      expect(
        Theme.of(context).colorScheme,
        ColorScheme.fromSeed(seedColor: const Color(0xFF007AFF)),
      );
    },
  );

  testWidgets('dark Android keeps a static saved wallpaper', (tester) async {
    await pumpSurface(tester, brightness: Brightness.dark);
    final wallpaper = tester.widget<SeededAuroraWallpaper>(
      find.byType(SeededAuroraWallpaper),
    );
    expect(wallpaper.seed, 271828);
    expect(wallpaper.brightness, Brightness.dark);
    await tester.pump(const Duration(seconds: 5));
    expect(
      tester
          .widget<SeededAuroraWallpaper>(find.byType(SeededAuroraWallpaper))
          .seed,
      wallpaper.seed,
    );
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('page fill follows glass and explicit fills preserve their hue', (
    tester,
  ) async {
    for (final (enabled, fill) in [
      (true, null),
      (false, null),
      (true, Colors.red),
    ]) {
      final container = ProviderContainer(
        overrides: [
          themeSettingProvider.overrideWithBuild(
            (_, _) => ThemeProps(auroraEnabled: enabled),
          ),
        ],
      );
      addTearDown(container.dispose);
      globalState.container = container;
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            theme: ThemeData(
              platform: TargetPlatform.android,
              scaffoldBackgroundColor: Colors.teal,
            ),
            homeBuilder: (child) => AuroraAppSurface(child: child),
            child: CommonScaffold(
              title: 'Page',
              backgroundColor: fill,
              body: const SizedBox.expand(),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final scaffold = find.byType(Scaffold);
      final background =
          tester.widget<Scaffold>(scaffold).backgroundColor ??
          Theme.of(tester.element(scaffold)).scaffoldBackgroundColor;
      if (fill != null) {
        expect(background, fill.withValues(alpha: 0.24));
      } else if (enabled) {
        expect(background, Colors.transparent);
      } else {
        expect(background, Colors.teal);
      }
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(Duration.zero);
    }
  });

  testWidgets('toggling the wallpaper preserves mounted application state', (
    tester,
  ) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    var mounts = 0;
    var disposals = 0;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: ThemeData(platform: TargetPlatform.android),
          builder: (context, child) => AuroraAppSurface(
            child: _MountProbe(
              onMount: () => mounts++,
              onDispose: () => disposals++,
              child: child!,
            ),
          ),
          home: const Scaffold(body: Text('XClash')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    for (final enabled in [false, true]) {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(auroraEnabled: enabled));
      await tester.pumpAndSettle();
      expect(mounts, 1);
      expect(disposals, 0);
    }
  });

  for (final brightness in Brightness.values) {
    for (final gradient in [true, false]) {
      testWidgets(
        'Android color modes screenshot ($brightness, gradient=$gradient)',
        (tester) async {
          tester.view.physicalSize = const Size(430, 932);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            await tester.runAsync(() async {
              final bytes = await File(
                '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
              ).readAsBytes();
              final loader = FontLoader('XClashPreview');
              loader.addFont(Future.value(ByteData.sublistView(bytes)));
              await loader.load();
            });
          }
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWith(TestProfiles.new),
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(
                  themeMode: brightness == Brightness.light
                      ? ThemeMode.light
                      : ThemeMode.dark,
                  auroraEnabled: gradient,
                  primaryColor: 0xFFFF2D55,
                ),
              ),
            ],
          );
          addTearDown(container.dispose);
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = const Size(
            430,
            932,
          );
          final captureKey = GlobalKey();
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: TestApp(
                locale: const Locale('zh', 'CN'),
                theme: ThemeData(
                  useMaterial3: true,
                  platform: TargetPlatform.android,
                  colorScheme: container.read(
                    genColorSchemeProvider(brightness),
                  ),
                  fontFamily: output == null ? null : 'XClashPreview',
                ).withAppShapes,
                homeBuilder: (child) => RepaintBoundary(
                  key: captureKey,
                  child: AuroraAppSurface(child: child),
                ),
                child: const ThemeView(),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final pageScaffold = find.descendant(
            of: find.byType(ThemeView),
            matching: find.byType(Scaffold),
          );
          final pageContext = tester.element(pageScaffold);
          expect(AuroraAppearance.enabledOf(pageContext), gradient);
          if (gradient) {
            final colors = Theme.of(pageContext).colorScheme;
            final original = auroraColorScheme(brightness);
            expect(colors.primary, original.primary);
            expect(colors.surface, original.surface);
            expect(colors.error, original.error);
            expect(colors.onSurface.withValues(alpha: 1), original.onSurface);
            expect(colors.onSurface.a, 0.94);
            expect(
              colors.onSurfaceVariant.withValues(alpha: 1),
              original.onSurfaceVariant,
            );
            expect(colors.onSurfaceVariant.a, 0.94);
          }
          expect(container.read(themeSettingProvider).primaryColor, 0xFFFF2D55);
          if (gradient) {
            expect(
              tester.widget<Scaffold>(pageScaffold).backgroundColor ??
                  Theme.of(pageContext).scaffoldBackgroundColor,
              Colors.transparent,
            );
          }
          final wallpaperOption = find.text('随机渐变底图');
          await tester.scrollUntilVisible(
            wallpaperOption,
            250,
            scrollable: find.byType(Scrollable).first,
          );
          await tester.pumpAndSettle();
          expect(wallpaperOption, findsOneWidget);
          final colorHeader = find.text('主题色彩');
          await Scrollable.ensureVisible(
            tester.element(colorHeader),
            alignment: 0.15,
          );
          await tester.pumpAndSettle();
          expect(
            tester.getTopLeft(colorHeader).dy,
            greaterThanOrEqualTo(pageContext.appBarInset),
          );
          if (output != null) {
            final boundary =
                captureKey.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary;
            await tester.runAsync(() async {
              final image = await boundary.toImage(pixelRatio: 2);
              try {
                final data = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                final directory = Directory(output)
                  ..createSync(recursive: true);
                File(
                  '${directory.path}/xclash-android-theme-${gradient ? 'gradient' : 'solid'}-${brightness.name}.png',
                ).writeAsBytesSync(data.buffer.asUint8List());
                if (gradient && brightness == Brightness.light) {
                  File(
                    '${directory.path}/xclash-android-theme.png',
                  ).writeAsBytesSync(data.buffer.asUint8List());
                }
              } finally {
                image.dispose();
              }
            });
          }
        },
      );
    }
  }
}

class _MountProbe extends StatefulWidget {
  const _MountProbe({
    required this.onMount,
    required this.onDispose,
    required this.child,
  });

  final VoidCallback onMount;
  final VoidCallback onDispose;
  final Widget child;

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
  void dispose() {
    widget.onDispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
