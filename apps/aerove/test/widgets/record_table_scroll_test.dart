import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  for (final startsWithGlass in [false, true]) {
    testWidgets('table preserves scroll state across glass toggles, '
        'initial glass $startsWithGlass', (tester) async {
      tester.view.physicalSize = const Size(1100, 700);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final glass = ValueNotifier(startsWithGlass);
      final controller = ScrollController(keepScrollOffset: false);
      double? topInset;
      double? bottomInset;
      addTearDown(() async {
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
        glass.dispose();
      });

      await tester.pumpWidget(
        ProviderScope(
          child: TestApp(
            includeNavigatorKey: false,
            homeBuilder: (child) => ValueListenableBuilder<bool>(
              valueListenable: glass,
              builder: (_, enabled, child) => LiquidGlassScope(
                enabled: enabled,
                child: BottomInsetScope(
                  inset: 20,
                  child: Material(
                    type: MaterialType.transparency,
                    child: child,
                  ),
                ),
              ),
              child: child,
            ),
            child: RecordTableFrame<int>(
              tableId: 'scroll-preservation',
              items: List.generate(200, (index) => index),
              columnsOf: (_) => [
                RecordColumn(
                  const TableColumn('name', 'Name', flex: 1),
                  cell: (_, item) => Text('row $item'),
                ),
              ],
              builder: (context, table, items) {
                expect(table, isNotNull);
                topInset = TopInsetScope.of(context);
                bottomInset = BottomInsetScope.of(context);
                return ListView.builder(
                  controller: controller,
                  itemExtent: 40,
                  padding: EdgeInsets.zero,
                  itemCount: items.length,
                  itemBuilder: (_, index) => Text('row ${items[index]}'),
                );
              },
            ),
          ),
        ),
      );
      await tester.pump();

      final scrollable = find.byType(Scrollable);
      expect(scrollable, findsOneWidget);
      final state = tester.state<ScrollableState>(scrollable);
      final position = controller.position;
      void expectLayout() {
        final list = find.byType(ListView);
        final body = tester.getRect(
          find.ancestor(of: list, matching: find.byType(Expanded)),
        );
        final insets = glass.value
            ? const EdgeInsets.fromLTRB(8, 4, 8, 28)
            : EdgeInsets.zero;
        expect(tester.getRect(list), insets.deflateRect(body));
        expect(topInset, glass.value ? 8 : 4);
        expect(bottomInset, glass.value ? 0 : 20);
      }

      expectLayout();
      for (final offset in [640.0, 1280.0, 320.0]) {
        controller.jumpTo(offset);
        await tester.pump();
        glass.value = !glass.value;
        await tester.pump();
        await tester.pump();

        expect(tester.state<ScrollableState>(scrollable), same(state));
        expect(controller.position, same(position));
        expect(controller.offset, offset);
        expectLayout();
        expect(tester.takeException(), isNull);
      }
    });
  }
}
