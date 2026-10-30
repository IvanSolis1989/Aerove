import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/widgets/core_status_button.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/glyph_finders.dart';
import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });

  for (final brightness in Brightness.values) {
    for (final status in CoreStatus.values) {
      testWidgets(
        'status $status is glass with retained state color $brightness',
        (tester) async {
          tester.view.physicalSize = const Size(320, 160);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final samples = <LiquidGlassMode, List<Color>>{};
          for (final (mode, highContrast) in [
            (LiquidGlassMode.none, false),
            (LiquidGlassMode.standard, false),
            (LiquidGlassMode.ultra, false),
            (LiquidGlassMode.standard, true),
          ]) {
            for (final backdrop in [
              const Color(0xFF2040C0),
              const Color(0xFFC04020),
            ]) {
              final container = ProviderContainer(
                overrides: [
                  themeSettingProvider.overrideWithBuild(
                    (_, _) =>
                        ThemeProps(auroraEnabled: false, liquidGlass: mode),
                  ),
                ],
              );
              try {
                globalState.container = container;
                container.read(coreStatusProvider.notifier).value = status;
                final capture = GlobalKey();
                await tester.pumpWidget(
                  UncontrolledProviderScope(
                    container: container,
                    child: TestApp(
                      includeNavigatorKey: false,
                      theme:
                          ThemeData(
                            platform: TargetPlatform.android,
                            brightness: brightness,
                          ).withAppShapes.withAppSurfaces.copyWith(
                            scaffoldBackgroundColor: backdrop,
                          ),
                      homeBuilder: (child) => Builder(
                        builder: (context) => MediaQuery(
                          data: MediaQuery.of(
                            context,
                          ).copyWith(highContrast: highContrast),
                          child: RepaintBoundary(
                            key: capture,
                            child: AuroraAppSurface(
                              motion: false,
                              child: Scaffold(body: Center(child: child)),
                            ),
                          ),
                        ),
                      ),
                      child: const CoreStatusButton(),
                    ),
                  ),
                );
                await tester.pump(const Duration(milliseconds: 500));
                await tester.pump();
                final host = find.byType(CoreStatusButton);
                final context = tester.element(host);
                final colors = Theme.of(context).colorScheme;
                final glass = mode != LiquidGlassMode.none && !highContrast;
                expect(TonalButtonTheme.glassOf(context), isFalse);
                expect(LiquidGlassScope.of(context), glass);
                final panes = find.descendant(
                  of: host,
                  matching: find.byType(LiquidGlass),
                );
                expect(panes, glass ? findsOneWidget : findsNothing);
                final stateColor = switch (status) {
                  CoreStatus.connecting => colors.primary,
                  CoreStatus.connected => colors.successContainer,
                  CoreStatus.disconnected => colors.errorContainer,
                };
                if (glass) {
                  final tint = tester.widget<LiquidGlass>(panes).style.tint;
                  expect(tint.a, lessThanOrEqualTo(0.24));
                  if (status != CoreStatus.connecting) {
                    expect(tint.withValues(alpha: 1), stateColor);
                  }
                }
                final action = status == CoreStatus.connected
                    ? find.descendant(
                        of: host,
                        matching: find.byType(IconButton),
                      )
                    : find.descendant(
                        of: host,
                        matching: find.byType(FilledButton),
                      );
                final style = status == CoreStatus.connected
                    ? tester.widget<IconButton>(action).style!
                    : tester.widget<FilledButton>(action).style!;
                if (glass || status != CoreStatus.connecting) {
                  expect(
                    style.backgroundColor!.resolve({}),
                    glass ? Colors.transparent : stateColor,
                  );
                }
                final expectedInk = switch (status) {
                  CoreStatus.connecting => null,
                  CoreStatus.connected => colors.onSuccessContainer,
                  CoreStatus.disconnected => colors.onErrorContainer,
                };
                expect(style.foregroundColor?.resolve({}), expectedInk);
                final bounds = tester.getRect(action);
                final point = Offset(bounds.center.dx, bounds.top + 5);
                final boundary = tester.renderObject<RenderRepaintBoundary>(
                  find.byKey(capture),
                );
                final pixel = (await tester.runAsync(() async {
                  final image = await boundary.toImage();
                  try {
                    final bytes = (await image.toByteData())!;
                    final index =
                        (point.dy.floor() * image.width + point.dx.floor()) * 4;
                    final output =
                        Platform.environment['XCLASH_SCREENSHOT_DIR'];
                    if (output != null) {
                      Directory(output).createSync(recursive: true);
                      final png = (await image.toByteData(
                        format: ui.ImageByteFormat.png,
                      ))!;
                      File(
                        '$output/core-status-${status.name}-${brightness.name}-${mode.name}-$highContrast-${backdrop.toARGB32()}.png',
                      ).writeAsBytesSync(png.buffer.asUint8List());
                    }
                    return Color.fromARGB(
                      bytes.getUint8(index + 3),
                      bytes.getUint8(index),
                      bytes.getUint8(index + 1),
                      bytes.getUint8(index + 2),
                    );
                  } finally {
                    image.dispose();
                  }
                }))!;
                if (glass) {
                  (samples[mode] ??= []).add(pixel);
                } else {
                  expect(pixel, stateColor);
                }
                expect(find.byType(LiquidGlassHost), findsOneWidget);
                if (status == CoreStatus.connecting) {
                  await tester.tap(action);
                  await tester.pump();
                  expect(container.read(coreStatusProvider), status);
                  expect(find.byType(CommonDialog), findsNothing);
                }
                expect(tester.takeException(), isNull);
              } finally {
                await tester.pumpWidget(const SizedBox.shrink());
                await tester.pump(Duration.zero);
                container.dispose();
              }
            }
          }
          for (final pair in samples.values) {
            expect(pair, hasLength(2));
            expect((pair[0].r - pair[1].r).abs(), greaterThan(0.2));
            expect((pair[0].b - pair[1].b).abs(), greaterThan(0.2));
          }
        },
      );
    }
  }

  testWidgets(
    'holds connecting for the minimum duration after a fast restart',
    (tester) async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      globalState.container = container;
      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            includeNavigatorKey: false,
            homeBuilder: (child) => Scaffold(body: Center(child: child)),
            child: const CoreStatusButton(),
          ),
        ),
      );
      await tester.pump();

      expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
      expect(find.byType(CommonCircleLoading), findsNothing);

      container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byType(CommonCircleLoading), findsOneWidget);
      expect(find.byGlyph(AppGlyphs.check), findsNothing);

      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
      await tester.pump();
      expect(find.byType(CommonCircleLoading), findsOneWidget);
      expect(find.byGlyph(AppGlyphs.check), findsNothing);

      await tester.pump(const Duration(milliseconds: 199));
      expect(find.byType(CommonCircleLoading), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
      expect(find.byType(CommonCircleLoading), findsNothing);

      await tester.pump(const Duration(seconds: 1));
      expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
    },
  );

  testWidgets(
    'shows disconnected immediately when restart fails during the hold',
    (tester) async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      globalState.container = container;
      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            includeNavigatorKey: false,
            homeBuilder: (child) => Scaffold(body: Center(child: child)),
            child: const CoreStatusButton(),
          ),
        ),
      );
      await tester.pump();

      container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byType(CommonCircleLoading), findsOneWidget);

      container.read(coreStatusProvider.notifier).value =
          CoreStatus.disconnected;
      await tester.pump();
      expect(find.byGlyph(AppGlyphs.refresh), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byGlyph(AppGlyphs.refresh), findsOneWidget);
      expect(find.byType(CommonCircleLoading), findsNothing);

      await tester.pump(const Duration(seconds: 1));
      expect(find.byGlyph(AppGlyphs.refresh), findsOneWidget);
      expect(find.byType(CommonCircleLoading), findsNothing);
    },
  );

  testWidgets('does not clip a connecting state longer than the hold', (
    tester,
  ) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          includeNavigatorKey: false,
          homeBuilder: (child) => Scaffold(body: Center(child: child)),
          child: const CoreStatusButton(),
        ),
      ),
    );
    await tester.pump();

    container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 700));
    expect(find.byType(CommonCircleLoading), findsOneWidget);

    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
    expect(find.byType(CommonCircleLoading), findsNothing);

    await tester.pump(const Duration(seconds: 1));
    expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
  });

  testWidgets('holds connecting when restarting from disconnected', (
    tester,
  ) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(coreStatusProvider.notifier).value = CoreStatus.disconnected;

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          includeNavigatorKey: false,
          homeBuilder: (child) => Scaffold(body: Center(child: child)),
          child: const CoreStatusButton(),
        ),
      ),
    );
    await tester.pump();

    expect(find.byGlyph(AppGlyphs.refresh), findsOneWidget);

    container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(CommonCircleLoading), findsOneWidget);
    expect(find.byGlyph(AppGlyphs.refresh), findsNothing);

    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    expect(find.byType(CommonCircleLoading), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 199));
    expect(find.byType(CommonCircleLoading), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
    expect(find.byType(CommonCircleLoading), findsNothing);

    await tester.pump(const Duration(seconds: 1));
    expect(find.byGlyph(AppGlyphs.check), findsOneWidget);
  });
}
