import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/navigation_dock.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/gestures.dart' show kTouchSlop;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
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
            NavigationItemsState(value: [
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
            ]),
          ),
        ],
      );
      globalState.container = container;
      container.read(viewSizeProvider.notifier).value = const Size(400, 800);
      try {
        await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: TestApp(
            includeNavigatorKey: false,
            child: LiquidGlassScope(enabled: glass, child: const HomePage()),
          ),
        ));
        await tester.pumpAndSettle();
        bool collapsed() => tester.widget<NavigationDock>(find.byType(NavigationDock)).collapsed;
        expect(collapsed(), isFalse);
        final inset = NavigationDock.insetOf(tester.element(find.byType(NavigationDock)));
        await tester.drag(find.byKey(const ValueKey('horizontal-content')), const Offset(-120, 0));
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        vertical.jumpTo(200);
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        final gesture = await tester.startGesture(
          tester.getCenter(find.byKey(const ValueKey('vertical-content'))),
        );
        try {
          await gesture.moveBy(const Offset(0, -kTouchSlop - 2));
          await tester.pump();
          expect(collapsed(), isFalse);
          final driven = vertical.animateTo(
            500,
            duration: const Duration(milliseconds: 100),
            curve: Curves.linear,
          );
          await tester.pumpAndSettle();
          await driven;
          expect(collapsed(), isFalse);
        } finally {
          await gesture.up();
        }
        await tester.drag(find.byKey(const ValueKey('vertical-content')), const Offset(0, -150));
        await tester.pumpAndSettle();
        expect(collapsed(), isTrue);
        expect(NavigationDock.insetOf(tester.element(find.byType(NavigationDock))), inset);
        await tester.drag(find.byKey(const ValueKey('vertical-content')), const Offset(0, 70));
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        await tester.drag(find.byKey(const ValueKey('vertical-content')), const Offset(0, -100));
        await tester.pumpAndSettle();
        expect(collapsed(), isTrue);
        vertical.jumpTo(0);
        await tester.pumpAndSettle();
        expect(collapsed(), isFalse);
        await tester.drag(find.byKey(const ValueKey('vertical-content')), const Offset(0, -140));
        await tester.pumpAndSettle();
        expect(collapsed(), isTrue);
        container.read(currentPageLabelProvider.notifier).toPage(PageLabel.tools);
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
