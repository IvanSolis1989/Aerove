import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
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
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      );
    }
  });

  for (final brightness in Brightness.values) {
    for (final landscape in [false, true]) {
      for (final layout in ['expanded', 'rail', 'overlay']) {
        testWidgets(
          'tablet $layout covers the status area in $brightness landscape=$landscape',
          (tester) async {
            final size = Size(
              layout == 'overlay'
                  ? (landscape ? 840.0 : 800.0)
                  : (landscape ? 1200.0 : 900.0),
              landscape ? 700.0 : 1200.0,
            );
            tester.view.physicalSize = size;
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.reset);
            for (final mode in [
              LiquidGlassMode.none,
              LiquidGlassMode.standard,
              LiquidGlassMode.ultra,
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
                    (_, _) =>
                        ThemeProps(auroraEnabled: false, liquidGlass: mode),
                  ),
                ],
              );
              try {
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
                            scaffoldBackgroundColor: const Color(0xFF286C85),
                          ),
                      localizationsDelegates: const [
                        AppLocalizations.delegate,
                        AppUiLocalizations.delegate,
                        ...GlobalMaterialLocalizations.delegates,
                      ],
                      supportedLocales:
                          AppLocalizations.delegate.supportedLocales,
                      builder: (context, child) => MediaQuery(
                        data: MediaQuery.of(context).copyWith(
                          padding: const EdgeInsets.only(top: 48, bottom: 24),
                          viewPadding: const EdgeInsets.only(
                            top: 48,
                            bottom: 24,
                          ),
                        ),
                        child: wrapAppScopes(
                          context,
                          RepaintBoundary(
                            key: captureKey,
                            child: AuroraAppSurface(
                              motion: false,
                              child: child!,
                            ),
                          ),
                        ),
                      ),
                      home: const AppSidebarContainer(
                        child: SizedBox.expand(key: ValueKey('tablet-content')),
                      ),
                    ),
                  ),
                );
                await tester.pumpAndSettle();
                final sidebar = find.byType(NavigationSidebar);
                final labels = UiLocalizations.of(tester.element(sidebar));
                final sidebarRect = tester.getRect(sidebar);
                final contentRect = tester.getRect(
                  find.byKey(const ValueKey('tablet-content')),
                );
                expect(sidebarRect.top, 0);
                expect(sidebarRect.height, size.height);
                expect(sidebarRect.width, layout == 'expanded' ? 220 : 48);
                expect(contentRect.left, sidebarRect.right);
                expect(contentRect.top, 0);
                expect(
                  tester
                      .getTopLeft(
                        find.byTooltip(
                          layout == 'expanded'
                              ? labels.showLess
                              : labels.showMore,
                        ),
                      )
                      .dy,
                  greaterThanOrEqualTo(48),
                );
                if (layout == 'overlay') {
                  await tester.tap(find.byTooltip(labels.showMore));
                  await tester.pumpAndSettle();
                  expect(
                    tester.getTopLeft(find.byTooltip(labels.showLess)).dy,
                    greaterThanOrEqualTo(48),
                  );
                  expect(
                    tester.getRect(
                      find.byKey(const ValueKey('tablet-content')),
                    ),
                    contentRect,
                  );
                }
                final pane = layout == 'overlay'
                    ? find.byWidgetPredicate(
                        (widget) => widget is Material && widget.elevation == 8,
                      )
                    : sidebar;
                final paneRect = tester.getRect(pane);
                expect(paneRect.top, 0);
                expect(paneRect.height, size.height);
                final glass = find.descendant(
                  of: pane,
                  matching: find.byType(LiquidGlass),
                );
                if (mode != LiquidGlassMode.none) {
                  expect(tester.getRect(glass.first), paneRect);
                } else {
                  expect(glass, findsNothing);
                }
                expect(
                  tester
                      .getRect(find.byKey(const ValueKey('Dashboard')).last)
                      .top,
                  greaterThanOrEqualTo(48),
                );
                final boundary = tester.renderObject<RenderRepaintBoundary>(
                  find.byKey(captureKey),
                );
                await tester.runAsync(() async {
                  final image = await boundary.toImage();
                  try {
                    final pixels = (await image.toByteData())!;
                    final x = paneRect.center.dx.floor();
                    for (var channel = 0; channel < 4; channel++) {
                      final above = (46 * image.width + x) * 4 + channel;
                      final below = (50 * image.width + x) * 4 + channel;
                      expect(
                        pixels.getUint8(above),
                        closeTo(pixels.getUint8(below), 2),
                        reason:
                            'safe-area padding must not create a horizontal seam',
                      );
                    }
                    final output =
                        Platform.environment['XCLASH_SCREENSHOT_DIR'];
                    if (output != null) {
                      Directory(output).createSync(recursive: true);
                      final png = (await image.toByteData(
                        format: ui.ImageByteFormat.png,
                      ))!;
                      File(
                        '$output/tablet-sidebar-$layout-${brightness.name}-$landscape-${mode.name}.png',
                      ).writeAsBytesSync(png.buffer.asUint8List());
                    }
                  } finally {
                    image.dispose();
                  }
                });
                expect(tester.takeException(), isNull);
              } finally {
                await tester.pumpWidget(const SizedBox.shrink());
                await tester.pump();
                container.dispose();
              }
            }
          },
        );
      }
    }
  }
}
