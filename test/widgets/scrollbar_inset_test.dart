import 'package:fl_clash/ui/header_blur.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/scrollbar.dart';
import '../helpers/test_app.dart';

Widget _list({ScrollController? controller}) {
  return ListView.builder(
    controller: controller,
    itemCount: 100,
    itemBuilder: (_, index) => SizedBox(height: 40, child: Text('$index')),
  );
}

Widget _sheet(Widget body) {
  return TestApp(
    wrapInProviderScope: true,
    child: SheetProvider(
      type: SheetType.bottomSheet,
      child: CommonScaffold(title: 'title', body: body),
    ),
  );
}

double _trackTop(WidgetTester tester) =>
    scrollbarPainter(tester).padding.resolve(TextDirection.ltr).top;

final _desktop = TargetPlatformVariant.only(TargetPlatform.macOS);
const _everyPlatform = TargetPlatformVariant({
  TargetPlatform.macOS,
  TargetPlatform.android,
});

void main() {
  testWidgets(
    'a padded scroll bar insets its own track only',
    variant: _desktop,
    (tester) async {
      const viewPadding = EdgeInsets.only(top: 20, bottom: 8);
      late EdgeInsets childPadding;

      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(padding: viewPadding),
          child: Directionality(
            textDirection: TextDirection.ltr,
            child: CommonScrollBar(
              controller: ScrollController(),
              padding: const EdgeInsets.only(top: sheetAppBarHeight),
              child: Builder(
                builder: (context) {
                  childPadding = MediaQuery.paddingOf(context);
                  return _list();
                },
              ),
            ),
          ),
        ),
      );

      final scrollBarContext = tester.element(
        find.byWidgetPredicate((widget) => widget is RawScrollbar).first,
      );
      expect(
        MediaQuery.paddingOf(scrollBarContext),
        viewPadding.copyWith(top: viewPadding.top + sheetAppBarHeight),
      );
      expect(childPadding, viewPadding);
    },
  );

  for (final (name, body) in [
    ('the ambient bar', (_) => _list()),
    (
      'a bar under a scroll behavior of its own',
      (_) => ScrollConfiguration(
        behavior: const ShowBarScrollBehavior(),
        child: _list(),
      ),
    ),
    (
      'a bar given its controller',
      (ScrollController controller) => CommonScrollBar(
        controller: controller,
        child: _list(controller: controller),
      ),
    ),
  ]) {
    testWidgets(
      'a bottom sheet keeps $name below its floating header',
      variant: _everyPlatform,
      (tester) async {
        final controller = ScrollController();
        addTearDown(controller.dispose);
        await tester.pumpWidget(_sheet(body(controller)));
        await tester.pumpAndSettle();

        expect(_trackTop(tester), sheetAppBarHeight);
      },
    );
  }

  testWidgets(
    'a page insets the track of a bar given its controller by its bar once',
    variant: _everyPlatform,
    (tester) async {
      final controller = ScrollController();
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        TestApp(
          wrapInProviderScope: true,
          child: CommonScaffold(
            title: 'title',
            body: CommonScrollBar(
              controller: controller,
              child: _list(controller: controller),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(_trackTop(tester), tester.getRect(find.byType(AppBar)).bottom);
    },
  );

  testWidgets(
    'a floating scroll bar is the only bar its list gets',
    variant: _everyPlatform,
    (tester) async {
      final controller = ScrollController();
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _sheet(
          FloatingScrollbar(
            controller: controller,
            hintBuilder: (_) => 'hint',
            child: _list(controller: controller),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(CommonScrollBar), findsOneWidget);
      expect(_trackTop(tester), sheetAppBarHeight);
    },
  );

  testWidgets(
    'a scroll bar given a list leaves a list nested in it the ambient bar',
    variant: _desktop,
    (tester) async {
      final controller = ScrollController();
      addTearDown(controller.dispose);
      const ambientPadding = EdgeInsets.only(top: 12);

      await tester.pumpWidget(
        TestApp(
          child: ScrollConfiguration(
            behavior: const BaseScrollBehavior(
              scrollbarPadding: ambientPadding,
            ),
            child: CommonScrollBar(
              controller: controller,
              child: ListView(
                controller: controller,
                children: [
                  SizedBox(height: 200, child: _list()),
                  for (var index = 0; index < 30; index++)
                    SizedBox(height: 40, child: Text('row $index')),
                ],
              ),
            ),
          ),
        ),
      );

      final bars = tester.widgetList<CommonScrollBar>(
        find.byType(CommonScrollBar),
      );
      expect(bars.where((bar) => bar.controller == controller), hasLength(1));
      final nested = bars.singleWhere((bar) => bar.controller != controller);
      expect(nested.padding, ambientPadding);
    },
  );

  testWidgets(
    'a snap sheet keeps its controls and scroll overlap without a top mask',
    variant: _everyPlatform,
    (tester) async {
      late ScrollController controller;
      await tester.pumpWidget(
        LiquidGlassScope(
          enabled: true,
          child: TestApp(
            includeNavigatorKey: false,
            wrapInProviderScope: true,
            child: Scaffold(
              body: Builder(
                builder: (context) => TextButton(
                  onPressed: () => Navigator.of(context).push<void>(
                    SnapSheetRoute<void>(
                      barrierLabel: 'Close sheet',
                      detents: const [1],
                      builder: (_, scrollController) {
                        controller = scrollController!;
                        return CommonScaffold(
                          title: 'title',
                          body: _list(controller: controller),
                        );
                      },
                    ),
                  ),
                  child: const Text('open sheet'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open sheet'));
      await tester.pumpAndSettle();

      final toolbar = tester.getRect(find.byType(SheetToolBar));
      final title = tester.getRect(find.text('title'));
      final context = tester.element(find.byType(CommonScaffold));
      final close = find.byTooltip(UiLocalizations.of(context).close);
      expect(close, findsOneWidget);
      final closeBounds = tester.getRect(close);
      final firstRow = tester.getRect(find.text('0'));
      final overlapOffset =
          tester.getRect(find.text('2')).top - toolbar.center.dy;
      expect(overlapOffset, greaterThan(0));

      for (final offset in [0.0, overlapOffset, 0.0]) {
        controller.jumpTo(offset);
        await tester.pumpAndSettle();
        expect(controller.offset, offset);
        expect(find.byType(HeaderBlur), findsNothing);
        expect(find.byType(BackdropFilter), findsNothing);
        expect(find.byType(FloatingHeader), findsNothing);
        expect(find.byType(SheetDragHandle), findsOneWidget);
        expect(tester.getRect(find.byType(SheetToolBar)), toolbar);
        expect(tester.getRect(find.text('title')), title);
        expect(tester.getRect(close), closeBounds);
        if (offset > 0) {
          final row = tester.getRect(find.text('2'));
          expect(row.top, moreOrLessEquals(toolbar.center.dy));
          expect(row.overlaps(toolbar), isTrue);
        } else {
          expect(tester.getRect(find.text('0')), firstRow);
        }
      }
      await tester.tap(close);
      await tester.pumpAndSettle();
      expect(find.byType(CommonScaffold), findsNothing);
      expect(find.byType(SheetDragHandle), findsNothing);
      expect(find.text('open sheet'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  for (final type in [null, SheetType.page, SheetType.sideSheet]) {
    testWidgets(
      '${type?.name ?? 'a page'} floats its bar over the list under it',
      variant: _everyPlatform,
      (tester) async {
        final scaffold = CommonScaffold(title: 'title', body: _list());
        await tester.pumpWidget(
          TestApp(
            wrapInProviderScope: true,
            child: type == null
                ? scaffold
                : SheetProvider(type: type, child: scaffold),
          ),
        );
        await tester.pumpAndSettle();

        final appBar = tester.widget<AppBar>(find.byType(AppBar));
        expect(appBar.forceMaterialTransparency, isTrue);
        expect(
          find.ancestor(
            of: find.byType(AppBar),
            matching: find.byType(FloatingHeader),
          ),
          findsOneWidget,
        );
        final barBottom = tester.getRect(find.byType(AppBar)).bottom;
        expect(tester.getRect(find.text('0')).top, barBottom);

        await tester.drag(find.byType(Scrollable), const Offset(0, -100));
        await tester.pumpAndSettle();

        expect(tester.getRect(find.text('2')).top, lessThan(barBottom));
      },
    );
  }

  testWidgets('a page with a title widget floats its bar as a titled one', (
    tester,
  ) async {
    await tester.pumpWidget(
      TestApp(
        wrapInProviderScope: true,
        child: CommonScaffold(titleWidget: const TextField(), body: _list()),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byType(AppBar),
        matching: find.byType(TextField),
      ),
      findsOneWidget,
    );

    expect(
      find.ancestor(
        of: find.byType(AppBar),
        matching: find.byType(FloatingHeader),
      ),
      findsOneWidget,
    );
    final barBottom = tester.getRect(find.byType(AppBar)).bottom;
    expect(tester.getRect(find.text('0')).top, barBottom);

    await tester.drag(find.text('0'), const Offset(0, -100));
    await tester.pumpAndSettle();

    expect(tester.getRect(find.text('2')).top, lessThan(barBottom));
  });

  testWidgets(
    'keyboard focus moving up clears the floating bar',
    variant: _everyPlatform,
    (tester) async {
      FocusManager.instance.highlightStrategy =
          FocusHighlightStrategy.alwaysTraditional;
      addTearDown(
        () => FocusManager.instance.highlightStrategy =
            FocusHighlightStrategy.automatic,
      );
      await tester.pumpWidget(
        TestApp(
          wrapInProviderScope: true,
          child: CommonScaffold(
            title: 'title',
            body: ListView.builder(
              itemCount: 100,
              itemBuilder: (_, index) =>
                  Focus(child: SizedBox(height: 40, child: Text('$index'))),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.drag(find.byType(Scrollable), const Offset(0, -410));
      await tester.pumpAndSettle();

      final barBottom = tester.getRect(find.byType(AppBar)).bottom;
      var below = 0;
      while (find.text('$below').evaluate().isEmpty ||
          tester.getRect(find.text('$below')).top < barBottom) {
        below++;
      }
      final above = find.text('${below - 1}');
      expect(tester.getRect(above).top, lessThan(barBottom));

      Focus.of(tester.element(find.text('$below'))).requestFocus();
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pumpAndSettle();

      expect(
        FocusManager.instance.primaryFocus,
        Focus.of(tester.element(above)),
      );
      expect(tester.getRect(above).top, moreOrLessEquals(barBottom));
    },
  );
}
