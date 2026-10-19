import 'dart:ui' as ui;

import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/sheet_surface.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';

Future<void> _pumpStackedSheets(
  WidgetTester tester,
  Size size, {
  required GlobalKey captureKey,
  required VoidCallback onUnderlayTap,
  required VoidCallback onInnerTap,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    overrides: [viewSizeProvider.overrideWithBuild((_, _) => size)],
  );
  addTearDown(container.dispose);
  globalState.container = container;
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: RepaintBoundary(
        key: captureKey,
        child: TestApp(
          child: Scaffold(
            backgroundColor: const Color(0xFF0000FF),
            body: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onUnderlayTap,
              child: Center(
                child: Builder(
                  builder: (context) => FilledButton(
                    onPressed: () {
                      showSheet<void>(
                        context: context,
                        builder: (_) => Builder(
                          builder: (context) => FilledButton(
                            onPressed: () {
                              showSheet<void>(
                                context: context,
                                builder: (_) => TextButton(
                                  onPressed: onInnerTap,
                                  child: const Text('inner action'),
                                ),
                              );
                            },
                            child: const Text('open inner'),
                          ),
                        ),
                      );
                    },
                    child: const Text('open outer'),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

Future<List<int>> _outsidePixel(WidgetTester tester, GlobalKey key) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final image = boundary.toImageSync();
  try {
    final bytes = await tester.runAsync(
      () => image.toByteData(format: ui.ImageByteFormat.rawRgba),
    );
    final offset = (4 * image.width + 4) * 4;
    return bytes!.buffer.asUint8List(bytes.offsetInBytes + offset, 4).toList();
  } finally {
    image.dispose();
  }
}

void _expectBarriers(WidgetTester tester, int count) {
  final barriers = tester
      .widgetList<ModalBarrier>(find.byType(ModalBarrier, skipOffstage: false))
      .where((barrier) => barrier.dismissible)
      .toList();
  expect(barriers, hasLength(count));
  for (final barrier in barriers) {
    expect(barrier.color?.a ?? 0, 0);
    expect(barrier.semanticsLabel, isNotEmpty);
  }
}

void _expectOpaqueSheets(WidgetTester tester, int count) {
  final surfaces = find.byType(SheetSurface, skipOffstage: false);
  expect(surfaces, findsNWidgets(count));
  for (final element in surfaces.evaluate()) {
    final material = find
        .descendant(
          of: find.byWidget(element.widget, skipOffstage: false),
          matching: find.byType(Material, skipOffstage: false),
        )
        .first;
    expect(tester.widget<Material>(material).color!.a, 1);
  }
}

void main() {
  for (final (kind, size) in const [
    ('side sheet', Size(1000, 800)),
    ('bottom sheet', Size(400, 800)),
  ]) {
    testWidgets('a $kind has transparent, independent modal barriers', (
      tester,
    ) async {
      final captureKey = GlobalKey();
      var underlayTaps = 0;
      var innerTaps = 0;
      await _pumpStackedSheets(
        tester,
        size,
        captureKey: captureKey,
        onUnderlayTap: () => underlayTaps++,
        onInnerTap: () => innerTaps++,
      );
      final baseline = await _outsidePixel(tester, captureKey);
      expect(baseline, [0, 0, 255, 255]);
      _expectBarriers(tester, 0);

      await tester.tap(find.text('open outer'));
      await tester.pumpAndSettle();
      final outer = ModalRoute.of(tester.element(find.text('open inner')))!;
      expect(outer.barrierColor?.a ?? 0, 0);
      expect(outer.barrierDismissible, isTrue);
      _expectBarriers(tester, 1);
      _expectOpaqueSheets(tester, 1);
      expect(await _outsidePixel(tester, captureKey), baseline);

      await tester.tap(find.text('open inner'));
      await tester.pumpAndSettle();
      final inner = ModalRoute.of(tester.element(find.text('inner action')))!;
      expect(inner, isNot(same(outer)));
      expect(inner.barrierColor?.a ?? 0, 0);
      expect(inner.barrierDismissible, isTrue);
      _expectBarriers(tester, 2);
      _expectOpaqueSheets(tester, 2);
      expect(await _outsidePixel(tester, captureKey), baseline);
      expect(find.byType(BackdropFilter), findsNothing);

      await tester.tap(find.text('inner action'));
      await tester.pumpAndSettle();
      expect(innerTaps, 1);
      expect(underlayTaps, 0);
      _expectBarriers(tester, 2);
      await tester.tapAt(const Offset(4, 4));
      await tester.pumpAndSettle();
      expect(find.text('inner action'), findsNothing);
      expect(find.text('open inner'), findsOneWidget);
      expect(outer.isCurrent, isTrue);
      _expectBarriers(tester, 1);
      expect(underlayTaps, 0);
      expect(await _outsidePixel(tester, captureKey), baseline);

      await tester.tapAt(const Offset(4, 4));
      await tester.pumpAndSettle();
      expect(find.text('open inner'), findsNothing);
      _expectBarriers(tester, 0);
      expect(underlayTaps, 0);
      expect(await _outsidePixel(tester, captureKey), baseline);
      await tester.tapAt(const Offset(4, 4));
      await tester.pump();
      expect(underlayTaps, 1);
      expect(tester.takeException(), isNull);
    });
  }
}
