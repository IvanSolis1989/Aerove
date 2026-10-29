import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart' show ScrollDirection;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  for (final material in [
    (glass: false, quality: LiquidGlassQuality.standard),
    (glass: true, quality: LiquidGlassQuality.standard),
    (glass: true, quality: LiquidGlassQuality.ultra),
  ]) {
    for (final bottom in [0.0, 24.0]) {
      testWidgets('fixed bar keeps material, content capture and safe area '
          'through transitions '
          'with $material and bottom $bottom', (tester) async {
        tester.view.physicalSize = const Size(400, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        const contentKey = ValueKey('fixed-content');
        const contentColor = Color(0xFFD13D25);
        final backdrop = ValueNotifier(contentColor);
        final appearance = ValueNotifier(material);
        double? contentInset;
        final container = ProviderContainer(
          overrides: [
            navigationItemsStateProvider.overrideWithValue(
              NavigationItemsState(
                value: [
                  NavigationItem(
                    glyph: AppGlyphs.dashboard,
                    label: PageLabel.dashboard,
                    builder: (_) => Builder(
                      builder: (context) {
                        contentInset = BottomInsetScope.of(context);
                        return ValueListenableBuilder(
                          valueListenable: backdrop,
                          builder: (_, color, _) => ColoredBox(
                            key: contentKey,
                            color: color,
                            child: const SizedBox.expand(),
                          ),
                        );
                      },
                    ),
                  ),
                  NavigationItem(
                    glyph: AppGlyphs.tools,
                    label: PageLabel.tools,
                    builder: (_) => const SizedBox.expand(),
                  ),
                ],
              ),
            ),
          ],
        );
        globalState.container = container;
        container.read(viewSizeProvider.notifier).value = const Size(400, 800);
        container
            .read(appSettingProvider.notifier)
            .update((state) => state.copyWith(floatingNavigationBar: false));
        LiquidGlassHost.debugLastCapture = null;
        try {
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: TestApp(
                includeNavigatorKey: false,
                theme: ThemeData(
                  navigationBarTheme: const NavigationBarThemeData(height: 76),
                ),
                child: MediaQuery(
                  data: MediaQueryData(
                    size: const Size(400, 800),
                    padding: EdgeInsets.only(bottom: bottom),
                  ),
                  child: ValueListenableBuilder(
                    valueListenable: appearance,
                    builder: (_, active, _) => LiquidGlassScope(
                      enabled: active.glass,
                      quality: active.quality,
                      child: LiquidGlassHost(
                        enabled: active.glass,
                        wallpaperOnly: true,
                        child: const Stack(
                          fit: StackFit.expand,
                          children: [
                            LiquidGlassColorField(
                              child: ColoredBox(color: Colors.blue),
                            ),
                            HomePage(),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final bar = find.byType(NavigationBar);
          final bounds = tester.getRect(bar);
          final homeElement = tester.element(find.byType(HomePage));
          Future<Color> sampledContent() async {
            final source = LiquidGlassHost.debugLastCapture!.panes.single;
            expect(source.wallpaper, isFalse);
            expect(source.pane, bounds);
            final point = Offset(8, bounds.center.dy);
            final texel =
                source.place.topLeft +
                (point - source.slot.topLeft) * source.scale;
            final bytes = (await tester.runAsync(source.atlas.toByteData))!;
            final offset =
                (texel.dy.floor() * source.atlas.width + texel.dx.floor()) * 4;
            return Color.fromARGB(
              bytes.getUint8(offset + 3),
              bytes.getUint8(offset),
              bytes.getUint8(offset + 1),
              bytes.getUint8(offset + 2),
            );
          }

          expect(bounds, Rect.fromLTWH(0, 800 - 76 - bottom, 400, 76 + bottom));
          expect(
            tester.getRect(find.byKey(contentKey)).bottom,
            material.glass ? 800 : bounds.top,
          );
          expect(contentInset, material.glass ? bounds.height : 0);
          expect(find.byType(NavigationDock), findsNothing);
          final paneFinder = find.ancestor(
            of: bar,
            matching: find.byType(LiquidGlass),
          );
          if (material.glass) {
            final pane = tester.widget<LiquidGlass>(paneFinder);
            expect(pane.contentBackdrop, isTrue);
            final style = pane.style.forQuality(material.quality);
            expect(style.blur + style.hazeBlur, greaterThan(0));
            expect(
              style.refraction,
              material.quality == LiquidGlassQuality.ultra ? greaterThan(0) : 0,
            );
            expect(
              tester.widget<NavigationBar>(bar).backgroundColor,
              Colors.transparent,
            );
            expect(await sampledContent(), contentColor);
            backdrop.value = const Color(0xFF32A06B);
            await tester.pump();
            expect(await sampledContent(), backdrop.value);
          } else {
            expect(paneFinder, findsNothing);
            expect(LiquidGlassHost.debugLastCapture, isNull);
          }
          if (material.glass) {
            for (final active in [
              (glass: false, quality: LiquidGlassQuality.standard),
              material,
              (glass: false, quality: LiquidGlassQuality.standard),
            ]) {
              appearance.value = active;
              for (final elapsed in [
                Duration.zero,
                const Duration(milliseconds: 100),
                const Duration(milliseconds: 200),
              ]) {
                await tester.pump(elapsed);
                expect(bar, findsOneWidget);
                expect(tester.getRect(bar), bounds);
                expect(
                  tester.element(find.byType(HomePage)),
                  same(homeElement),
                );
                expect(
                  tester.getRect(find.byKey(contentKey)).bottom,
                  active.glass ? 800 : bounds.top,
                );
                expect(contentInset, active.glass ? bounds.height : 0);
                if (active.glass) {
                  final pane = tester.widget<LiquidGlass>(paneFinder);
                  expect(pane.contentBackdrop, isTrue);
                  expect(
                    pane.style.forQuality(active.quality).refraction,
                    active.quality == LiquidGlassQuality.ultra
                        ? greaterThan(0)
                        : 0,
                  );
                  expect(await sampledContent(), backdrop.value);
                } else {
                  expect(paneFinder, findsNothing);
                }
              }
            }
          }
          await tester.tap(find.byType(NavigationDestination).last);
          await tester.pumpAndSettle();
          expect(container.read(currentPageLabelProvider), PageLabel.tools);
          expect(tester.getRect(bar), bounds);
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          appearance.dispose();
          backdrop.dispose();
          container.dispose();
        }
      });
    }
  }

  for (final glass in [false, true]) {
    testWidgets('home dock follows vertical user scroll with glass $glass', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final vertical = ScrollController();
      final horizontal = ScrollController();
      final container = ProviderContainer(
        overrides: [
          navigationItemsStateProvider.overrideWithValue(
            NavigationItemsState(
              value: [
                NavigationItem(
                  glyph: AppGlyphs.dashboard,
                  label: PageLabel.dashboard,
                  builder: (_) => ListView(
                    key: const ValueKey('vertical-content'),
                    controller: vertical,
                    children: [
                      SizedBox(
                        height: 100,
                        child: ListView(
                          key: const ValueKey('horizontal-content'),
                          controller: horizontal,
                          scrollDirection: Axis.horizontal,
                          children: const [SizedBox(width: 2000)],
                        ),
                      ),
                      const SizedBox(height: 3000),
                    ],
                  ),
                ),
                NavigationItem(
                  glyph: AppGlyphs.tools,
                  label: PageLabel.tools,
                  builder: (_) => const SizedBox.expand(),
                ),
              ],
            ),
          ),
        ],
      );
      globalState.container = container;
      container.read(viewSizeProvider.notifier).value = const Size(400, 800);
      try {
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: TestApp(
              includeNavigatorKey: false,
              child: LiquidGlassScope(enabled: glass, child: const HomePage()),
            ),
          ),
        );
        await tester.pumpAndSettle();
        bool collapsed() => tester
            .widget<NavigationDock>(find.byType(NavigationDock))
            .collapsed;
        expect(collapsed(), isFalse);
        final inset = NavigationDock.insetOf(
          tester.element(find.byType(NavigationDock)),
        );
        await tester.drag(
          find.byKey(const ValueKey('horizontal-content')),
          const Offset(-120, 0),
        );
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        vertical.jumpTo(200);
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        UserScrollNotification(
          direction: ScrollDirection.reverse,
          metrics: vertical.position,
          context: tester.element(
            find.byKey(const ValueKey('vertical-content')),
          ),
        ).dispatch(
          tester.element(find.byKey(const ValueKey('vertical-content'))),
        );
        final driven = vertical.animateTo(
          500,
          duration: const Duration(milliseconds: 100),
          curve: Curves.linear,
        );
        await tester.pumpAndSettle();
        await driven;
        expect(collapsed(), isFalse);
        await tester.drag(
          find.byKey(const ValueKey('vertical-content')),
          const Offset(0, -150),
        );
        await tester.pumpAndSettle();
        expect(collapsed(), isTrue);
        expect(
          NavigationDock.insetOf(tester.element(find.byType(NavigationDock))),
          inset,
        );
        await tester.drag(
          find.byKey(const ValueKey('vertical-content')),
          const Offset(0, 70),
        );
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        await tester.drag(
          find.byKey(const ValueKey('vertical-content')),
          const Offset(0, -100),
        );
        await tester.pumpAndSettle();
        expect(collapsed(), isTrue);
        vertical.jumpTo(0);
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        await tester.drag(
          find.byKey(const ValueKey('vertical-content')),
          const Offset(0, -140),
        );
        await tester.pumpAndSettle();
        expect(collapsed(), isTrue);
        container
            .read(currentPageLabelProvider.notifier)
            .toPage(PageLabel.tools);
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        vertical.dispose();
        horizontal.dispose();
        container.dispose();
      }
    });
  }
}
