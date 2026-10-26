import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

/// Counts every paint transform a row below asks for on its way to the
/// screen, which is the work the separators repeat after each frame.
class _TransformCounter extends SingleChildRenderObjectWidget {
  const _TransformCounter({required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderTransformCounter();
}

class _RenderTransformCounter extends RenderProxyBox {
  var count = 0;

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    count++;
    super.applyPaintTransform(child, transform);
  }
}

Widget _list() => _TransformCounter(
  child: ListView(
    children: [
      for (var section = 0; section < 2; section++)
        generateSection(
          title: 'Section $section',
          items: [
            for (var row = 0; row < 10; row++)
              ListItem(title: Text('Row $section.$row'), onTap: () {}),
          ],
        ),
    ],
  ),
);

/// Frames that end with nothing else scheduled, as each paced wallpaper
/// frame does, so the separators look at every row they watch.
Future<int> _idleFrames(WidgetTester tester) async {
  final counter = tester.renderObject<_RenderTransformCounter>(
    find.byType(_TransformCounter, skipOffstage: false),
  );
  counter.count = 0;
  for (var frame = 0; frame < 10; frame++) {
    tester.binding.scheduleFrame();
    await tester.pump(const Duration(milliseconds: 66));
  }
  return counter.count;
}

void main() {
  void setSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  testWidgets('rows under an opaque route are left alone until it pops', (
    tester,
  ) async {
    setSize(tester);
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      ProviderScope(
        child: TestApp(
          includeNavigatorKey: false,
          homeBuilder: (child) => Navigator(
            key: navigator,
            onGenerateRoute: (_) =>
                MaterialPageRoute<void>(builder: (_) => child),
          ),
          child: Scaffold(body: _list()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(await _idleFrames(tester), greaterThan(0));

    navigator.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold()),
    );
    await tester.pumpAndSettle();
    expect(await _idleFrames(tester), 0);

    navigator.currentState!.pop();
    await tester.pumpAndSettle();
    expect(await _idleFrames(tester), greaterThan(0));
    expect(tester.takeException(), isNull);
  });

  testWidgets('rows of a kept-alive page are left alone until it returns', (
    tester,
  ) async {
    setSize(tester);
    final pages = PageController();
    addTearDown(pages.dispose);
    await tester.pumpWidget(
      ProviderScope(
        child: TestApp(
          includeNavigatorKey: false,
          child: Scaffold(
            body: PageView(
              controller: pages,
              physics: const NeverScrollableScrollPhysics(),
              children: [
                KeepScope(child: _list()),
                const KeepScope(child: SizedBox.expand()),
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(await _idleFrames(tester), greaterThan(0));

    pages.jumpToPage(1);
    await tester.pumpAndSettle();
    expect(await _idleFrames(tester), 0);

    pages.jumpToPage(0);
    await tester.pumpAndSettle();
    expect(await _idleFrames(tester), greaterThan(0));
    expect(tester.takeException(), isNull);
  });
}
