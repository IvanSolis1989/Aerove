import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/application.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/profiles/profiles.dart';
import 'package:fl_clash/views/tools.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

// Temporary screenshot harness. Render actual UI in offline state; never run
// startup/Core/VPN/probe managers or claim an Android-device screen capture.
class _OfflineProfiles extends Profiles {
  @override
  List<Profile> build() => const [];
}

Widget _themeOnly(Widget child) => ThemeManager(child: child);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final fontPath = Platform.environment['XCLASH_SHOWCASE_FONT'] ??
      '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc';
  setUpAll(() async {
    expect(ui.ImageFilter.isShaderFilterSupported, isTrue,
        reason: 'README previews require --enable-impeller, never Skia fallback.');
    expect(File(fontPath).existsSync(), isTrue,
        reason: 'Install fonts-noto-cjk; do not silently render Ahem.');
    final font = FontLoader('ReadmeShowcase');
    font.addFont(Future.value(ByteData.sublistView(await File(fontPath).readAsBytes())));
    await font.load();
    await GlassInk.prepare();
  });

  for (final brightness in Brightness.values) {
    testWidgets('actual HomePage README ${brightness.name}', (tester) async {
      final previousPlatform = debugDefaultTargetPlatformOverride;
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      try {
      tester.view.physicalSize = const Size(430, 932);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      tester.platformDispatcher.accessibilityFeaturesTestValue =
          FakeAccessibilityFeatures(disableAnimations: true);
      addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      expect(output, isNotNull,
          reason: 'Set XCLASH_SCREENSHOT_DIR to preserve the rendered images.');
      final page = Platform.environment['XCLASH_SHOWCASE_PAGE'] == 'tools'
          ? PageLabel.tools : PageLabel.dashboard;
      final container = ProviderContainer(overrides: [
        profilesProvider.overrideWith(_OfflineProfiles.new),
        appSettingProvider.overrideWithBuild((_, _) => const AppSettingProps(
          locale: 'zh_CN',
          autoCheckUpdate: false,
          autoRun: false,
          floatingNavigationBar: true,
          dashboardWidgets: [
            DashboardWidget.networkSpeed,
            DashboardWidget.outboundMode,
            DashboardWidget.profiles,
          ],
        )),
        themeSettingProvider.overrideWithBuild((_, _) => ThemeProps(
          themeMode: brightness == Brightness.light ? ThemeMode.light : ThemeMode.dark,
          auroraEnabled: true,
          auroraSeed: 271828,
          liquidGlass: LiquidGlassMode.ultra,
          pureBlack: false,
          fontFamily: 'ReadmeShowcase',
        )),
        navigationItemsStateProvider.overrideWithValue(NavigationItemsState(value: [
          NavigationItem(glyph: AppGlyphs.dashboard, label: PageLabel.dashboard,
            builder: (_) => const DashboardView(platform: SupportPlatform.Android)),
          NavigationItem(glyph: AppGlyphs.profiles, label: PageLabel.profiles,
            builder: (_) => const ProfilesView()),
          NavigationItem(glyph: AppGlyphs.tools, label: PageLabel.tools,
            builder: (_) => const ToolsView()),
        ])),
      ]);
      addTearDown(container.dispose);
      globalState.container = container;
      container.read(viewSizeProvider.notifier).value = const Size(430, 932);
      container.read(currentPageLabelProvider.notifier).toPage(page);
      final capture = GlobalKey();
      var networkAttempts = 0;
      await HttpOverrides.runZoned(() async {
        await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: RepaintBoundary(key: capture,
            child: const Application.forTesting(
              home: HomePage(), wrapManagers: _themeOnly,
            ),
          ),
        ));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.byType(HomePage), findsOneWidget);
        expect(find.byType(page == PageLabel.dashboard ? DashboardView : ToolsView), findsOneWidget);
        expect(find.byType(ThemeManager), findsOneWidget);
        expect(find.byType(AuroraAppSurface), findsOneWidget);
        expect(find.byType(LiquidGlassHost), findsOneWidget);
        expect(find.byType(SeededAuroraWallpaper), findsOneWidget);
        expect(find.byType(NavigationDock), findsOneWidget);
        final context = tester.element(find.byType(HomePage));
        expect(Theme.of(context).brightness, brightness);
        expect(Theme.of(context).textTheme.bodyLarge!.fontFamily, 'ReadmeShowcase');
        expect(LiquidGlassScope.qualityOf(context), LiquidGlassQuality.ultra);
        expect(networkAttempts, 0);
        final boundary = capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        expect(boundary.size, const Size(430, 932));
        await tester.runAsync(() async {
          final image = await boundary.toImage(pixelRatio: 2);
          try {
            expect(image.width, 860);
            expect(image.height, 1864);
            final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
            await Directory(output!).create(recursive: true);
            await File('$output/readme-${brightness.name}.png').writeAsBytes(
              png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes));
            await File('$output/readme-${brightness.name}.json').writeAsString(jsonEncode({
              'source': Platform.environment['GITHUB_SHA'],
              'rendering': 'Flutter Impeller offscreen, not an Android device screenshot',
              'page': page.name,
              'logicalSize': [430, 932],
              'exportScale': 2,
              'locale': 'zh_CN',
              'font': fontPath,
              'seed': 271828,
              'quality': 'ultra',
              'data': 'Offline empty profile/connection state; no synthetic connected status',
              'networkAttempts': networkAttempts,
              'managers': 'Application.forTesting + production ThemeManager; runtime managers omitted',
            }));
          } finally {
            image.dispose();
          }
        });
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
      }, createHttpClient: (_) {
        networkAttempts++;
        throw StateError('Network requests are forbidden in README rendering.');
      });
      expect(networkAttempts, 0);
      expect(tester.takeException(), isNull);
      } finally {
        debugDefaultTargetPlatformOverride = previousPlatform;
      }
    });
  }
}
