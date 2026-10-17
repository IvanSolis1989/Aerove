import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/shape.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/aurora_settings.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/widgets/card.dart';
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
          theme: ThemeData(platform: platform, brightness: brightness),
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

  testWidgets('original Android theme page exposes the wallpaper options', (
    tester,
  ) async {
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
          (_, _) => const ThemeProps(themeMode: ThemeMode.light),
        ),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(430, 932);
    final captureKey = GlobalKey();
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          locale: const Locale('zh', 'CN'),
          theme: ThemeData(
            useMaterial3: true,
            platform: TargetPlatform.android,
            colorScheme: container.read(genColorSchemeProvider(Brightness.light)),
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
    final wallpaperOption = find.text('随机渐变底图');
    await tester.scrollUntilVisible(
      wallpaperOption,
      250,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    expect(wallpaperOption, findsOneWidget);
    await Scrollable.ensureVisible(
      tester.element(find.byType(AuroraSettings)),
      alignment: 0.1,
    );
    await tester.pumpAndSettle();
    if (output != null) {
      final boundary =
          captureKey.currentContext!.findRenderObject()!
              as RenderRepaintBoundary;
      await tester.runAsync(() async {
        final image = await boundary.toImage(pixelRatio: 2);
        try {
          final data = await image.toByteData(format: ui.ImageByteFormat.png);
          final directory = Directory(output)..createSync(recursive: true);
          File(
            '${directory.path}/xclash-android-theme.png',
          ).writeAsBytesSync(data!.buffer.asUint8List());
        } finally {
          image.dispose();
        }
      });
    }
  });
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
