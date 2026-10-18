import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
    }
  });

  for (final brightness in Brightness.values) {
    for (final layout in ['expanded', 'rail', 'overlay']) {
      testWidgets('$layout sidebar transmits two backdrops in $brightness', (
        tester,
      ) async {
        final size = Size(layout == 'overlay' ? 900 : 1400, 900);
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final samples = <LiquidGlassMode, List<List<Color>>>{};
        for (final (mode, highContrast, pureBlack, backdrop) in [
          (LiquidGlassMode.standard, false, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.standard, false, false, const Color(0xFFC04020)),
          (LiquidGlassMode.ultra, false, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.ultra, false, false, const Color(0xFFC04020)),
          (LiquidGlassMode.none, false, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.standard, true, false, const Color(0xFF2040C0)),
          if (brightness == Brightness.dark)
            (LiquidGlassMode.ultra, false, true, const Color(0xFF2040C0)),
        ]) {
          final container = ProviderContainer(
            overrides: [
              navigationItemsStateProvider.overrideWithValue(
                NavigationItemsState(
                  value: [
                    NavigationItem(
                      glyph: AppGlyphs.dashboard,
                      label: PageLabel.dashboard,
                      builder: (_) => const SizedBox.expand(),
                    ),
                    NavigationItem(
                      glyph: AppGlyphs.tools,
                      label: PageLabel.tools,
                      builder: (_) => const SizedBox.expand(),
                    ),
                  ],
                ),
              ),
              appSettingProvider.overrideWithBuild(
                (_, _) =>
                    AppSettingProps(sidebarExpanded: layout == 'expanded'),
              ),
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(
                  auroraEnabled: false,
                  liquidGlass: mode,
                  pureBlack: pureBlack,
                ),
              ),
            ],
          );
          try {
            globalState.container = container;
            container.read(viewSizeProvider.notifier).value = size;
            final captureKey = GlobalKey();
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: MaterialApp(
                  theme:
                      ThemeData(
                        platform: TargetPlatform.android,
                        brightness: brightness,
                      ).withAppShapes.withAppSurfaces.copyWith(
                        scaffoldBackgroundColor: pureBlack
                            ? Colors.black
                            : backdrop,
                      ),
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    AppUiLocalizations.delegate,
                    ...GlobalMaterialLocalizations.delegates,
                  ],
                  supportedLocales: AppLocalizations.delegate.supportedLocales,
                  builder: (context, child) => MediaQuery(
                    data: MediaQuery.of(
                      context,
                    ).copyWith(highContrast: highContrast),
                    child: wrapAppScopes(
                      context,
                      RepaintBoundary(
                        key: captureKey,
                        child: AuroraAppSurface(motion: false, child: child!),
                      ),
                    ),
                  ),
                  home: const AppSidebarContainer(child: SizedBox.expand()),
                ),
              ),
            );
            await tester.pumpAndSettle();
            final sidebar = find.byType(NavigationSidebar);
            final labels = UiLocalizations.of(tester.element(sidebar));
            expect(
              tester.getSize(sidebar).width,
              layout == 'expanded' ? 220 : 48,
            );
            if (layout == 'overlay') {
              await tester.tap(find.byTooltip(labels.showMore));
              await tester.pumpAndSettle();
            }
            final pane = layout == 'overlay'
                ? find.byWidgetPredicate(
                    (widget) => widget is Material && widget.elevation == 8,
                  )
                : sidebar;
            final paneRect = tester.getRect(pane);
            final selected = find.byKey(const ValueKey('Dashboard')).last;
            final selectedRect = tester.getRect(selected);
            final glass =
                mode != LiquidGlassMode.none && !highContrast && !pureBlack;
            expect(find.byType(LiquidGlassHost), findsOneWidget);
            final sidebarPanes = find.descendant(
              of: pane,
              matching: find.byType(LiquidGlass),
            );
            expect(sidebarPanes, glass ? findsWidgets : findsNothing);
            expect(
              find.descendant(of: selected, matching: find.byType(LiquidGlass)),
              glass ? findsOneWidget : findsNothing,
            );
            final boundary = tester.renderObject<RenderRepaintBoundary>(
              find.byKey(captureKey),
            );
            final image = boundary.toImageSync();
            try {
              final pixels = (await tester.runAsync(() async {
                final bytes = (await image.toByteData())!;
                final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
                if (output != null && glass) {
                  final png = (await image.toByteData(
                    format: ui.ImageByteFormat.png,
                  ))!;
                  await Directory(output).create(recursive: true);
                  await File(
                    '$output/xclash-sidebar-$layout-${brightness.name}-${mode.name}-${backdrop.toARGB32()}.png',
                  ).writeAsBytes(png.buffer.asUint8List());
                }
                return [
                  for (final point in [
                    Offset(paneRect.center.dx, paneRect.top + 500),
                    Offset(selectedRect.center.dx, selectedRect.top + 7),
                  ])
                    Color.fromARGB(
                      255,
                      bytes.getUint8(
                        (point.dy.floor() * image.width + point.dx.floor()) * 4,
                      ),
                      bytes.getUint8(
                        (point.dy.floor() * image.width + point.dx.floor()) *
                                4 +
                            1,
                      ),
                      bytes.getUint8(
                        (point.dy.floor() * image.width + point.dx.floor()) *
                                4 +
                            2,
                      ),
                    ),
                ];
              }))!;
              if (glass) {
                (samples[mode] ??= []).add(pixels);
              } else {
                final scheme = Theme.of(tester.element(sidebar)).colorScheme;
                final expected = scheme.chromeSurface;
                expect(expected.a, 1);
                for (final (actual, source) in [
                  (pixels.first.r, expected.r),
                  (pixels.first.g, expected.g),
                  (pixels.first.b, expected.b),
                ]) {
                  expect(actual, closeTo(source, 2 / 255));
                }
              }
            } finally {
              image.dispose();
            }
            if (layout != 'overlay') {
              await tester.tap(
                find.byTooltip(
                  layout == 'expanded' ? labels.showLess : labels.showMore,
                ),
              );
              await tester.pumpAndSettle();
              expect(
                tester.getSize(sidebar).width,
                layout == 'expanded' ? 48 : 220,
              );
            }
            await tester.tap(find.byKey(const ValueKey('Tools')).last);
            await tester.pumpAndSettle();
            expect(container.read(currentPageLabelProvider), PageLabel.tools);
            if (layout == 'overlay') {
              expect(
                find.byWidgetPredicate(
                  (widget) => widget is Material && widget.elevation == 8,
                ),
                findsNothing,
              );
            }
            expect(tester.takeException(), isNull);
          } finally {
            try {
              await tester.pumpWidget(const SizedBox.shrink());
              await tester.pump(Duration.zero);
            } finally {
              container.dispose();
            }
          }
        }
        for (final pair in samples.values) {
          expect(pair, hasLength(2));
          for (var point = 0; point < 2; point++) {
            expect(
              (pair[0][point].r - pair[1][point].r).abs(),
              greaterThan(0.2),
            );
            expect(
              (pair[0][point].b - pair[1][point].b).abs(),
              greaterThan(0.2),
            );
          }
        }
      });
    }
  }
}
