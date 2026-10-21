import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/scroll.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/foundation.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/scrollbar.dart';

const _itemHeight = 40.0;
const _itemCount = 200;
const _hintKey = ValueKey('scrollbarHintPill');
const _rateKey = ValueKey('scrollbarScrubRate');

Widget _list(
  ScrollController controller, {
  bool thumbVisibility = false,
  int itemCount = _itemCount,
  bool glass = false,
  bool highContrast = false,
}) => MaterialApp(
  home: LiquidGlassScope(
    enabled: glass,
    child: Builder(
      builder: (context) => MediaQuery(
        data: MediaQuery.of(context).copyWith(highContrast: highContrast),
        child: Scaffold(
          body: FloatingScrollbar(
            controller: controller,
            thumbVisibility: thumbVisibility,
            hintBuilder: (fraction) => fraction.toStringAsFixed(2),
            child: ListView.builder(
              controller: controller,
              itemCount: itemCount,
              itemBuilder: (_, index) =>
                  SizedBox(height: _itemHeight, child: Text('$index')),
            ),
          ),
        ),
      ),
    ),
  ),
);

Future<ScrollController> _pumpList(
  WidgetTester tester, {
  bool thumbVisibility = false,
  bool glass = false,
  bool highContrast = false,
}) async {
  final controller = ScrollController();
  addTearDown(controller.dispose);
  await tester.pumpWidget(
    _list(
      controller,
      thumbVisibility: thumbVisibility,
      glass: glass,
      highContrast: highContrast,
    ),
  );
  return controller;
}

Future<TestGesture> _holdThumb(WidgetTester tester) async {
  final thumb = await revealScrollbarThumb(tester);
  final gesture = await tester.startGesture(thumb);
  await gesture.moveBy(const Offset(0, 40));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 200));
  return gesture;
}

final _elevatedFill = find.byWidgetPredicate(
  (widget) =>
      widget is DecoratedBox &&
      widget.decoration is ShapeDecoration &&
      listEquals(
        (widget.decoration as ShapeDecoration).shadows,
        kElevationToShadow[2],
      ),
);

Finder _inScrollbar(Finder finder) =>
    find.descendant(of: find.byType(FloatingScrollbar), matching: finder);

void main() {
  testWidgets('dragging the content leaves the hint hidden', (tester) async {
    await _pumpList(tester);

    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(Scrollable)),
    );
    await gesture.moveBy(const Offset(0, -300));
    await tester.pump();
    expect(find.byKey(_hintKey), findsNothing);

    await gesture.up();
    await tester.pumpAndSettle(const Duration(seconds: 2));
  });

  testWidgets('holding the thumb floats the hint beside it until let go', (
    tester,
  ) async {
    await _pumpList(tester);

    final thumb = await revealScrollbarThumb(tester);
    final gesture = await tester.startGesture(thumb);
    await gesture.moveBy(const Offset(0, 200));
    await tester.pump();

    final hint = find.byKey(_hintKey);
    expect(hint, findsOneWidget);
    expect(tester.getCenter(hint).dy, closeTo(thumb.dy + 200, 2));
    expect(tester.getRect(hint).right, lessThan(thumb.dx - 20));
    expect(find.byKey(_rateKey), findsNothing);

    await gesture.up();
    await tester.pump(const Duration(milliseconds: 300));
    expect(hint, findsOneWidget);
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(hint, findsNothing);
  });

  testWidgets('a hint whose list stops scrolling still goes away', (
    tester,
  ) async {
    final controller = await _pumpList(tester);

    final thumb = await revealScrollbarThumb(tester);
    final gesture = await tester.startGesture(thumb);
    await gesture.moveBy(const Offset(0, 200));
    await tester.pumpAndSettle();
    expect(find.byKey(_hintKey), findsOneWidget);

    await gesture.up();
    await tester.pumpWidget(_list(controller, itemCount: 3));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    await tester.pumpWidget(_list(controller));
    await tester.drag(find.byType(Scrollable), const Offset(0, -300));
    await tester.pump();
    expect(find.byKey(_hintKey), findsNothing);
    await tester.pumpAndSettle();
  });

  testWidgets('the hint fades in when the thumb is grabbed', (tester) async {
    await _pumpList(tester);

    final thumb = await revealScrollbarThumb(tester);
    final gesture = await tester.startGesture(thumb);
    await gesture.moveBy(const Offset(0, 10));
    await tester.pump();

    double hintOpacity() => tester
        .widget<FadeTransition>(
          find
              .ancestor(
                of: find.byKey(_hintKey),
                matching: find.byType(FadeTransition),
              )
              .first,
        )
        .opacity
        .value;
    expect(hintOpacity(), 0);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 75));
    expect(hintOpacity(), inExclusiveRange(0, 1));
    await tester.pump(const Duration(milliseconds: 100));
    expect(hintOpacity(), 1);

    await gesture.up();
    await tester.pumpAndSettle(const Duration(seconds: 2));
  });

  testWidgets('on glass the hint is one menu pane that fades as glass', (
    tester,
  ) async {
    await _pumpList(tester, glass: true);
    final gesture = await _holdThumb(tester);

    final hint = find.byKey(_hintKey);
    final colorScheme = Theme.of(tester.element(hint)).colorScheme;
    final pane = tester.widget<LiquidGlass>(hint);
    expect(_inScrollbar(find.byType(LiquidGlass)), findsOneWidget);
    expect(pane.style, LiquidGlassStyle.menu(colorScheme));
    expect(pane.shadows, glassMenuShadows(colorScheme));
    expect((pane.clipper! as ShapeBorderClipper).shape, AppShape.sm);
    expect(_inScrollbar(_elevatedFill), findsNothing);
    expect(_inScrollbar(find.byType(FadeTransition)), findsNothing);
    final fade = find.ancestor(
      of: hint,
      matching: find.byType(LiquidGlassFade),
    );
    expect(tester.widget<LiquidGlassFade>(fade).opacity.value, 1);

    await gesture.up();
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump(const Duration(milliseconds: 75));
    expect(
      tester.widget<LiquidGlassFade>(fade).opacity.value,
      inExclusiveRange(0, 1),
    );
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(hint, findsNothing);
  });

  for (final (name, glass, highContrast) in [
    ('plain solid', false, false),
    ('high contrast', true, true),
  ]) {
    testWidgets('in $name the hint keeps its opaque fill and shadow', (
      tester,
    ) async {
      await _pumpList(tester, glass: glass, highContrast: highContrast);
      final gesture = await _holdThumb(tester);

      final hint = find.byKey(_hintKey);
      final fill = tester.widget<DecoratedBox>(hint);
      expect(_inScrollbar(_elevatedFill), findsOneWidget);
      expect((fill.decoration as ShapeDecoration).color!.a, 1);
      expect(_inScrollbar(find.byType(LiquidGlass)), findsNothing);
      expect(_inScrollbar(find.byType(LiquidGlassFade)), findsNothing);
      expect(
        find.ancestor(of: hint, matching: find.byType(FadeTransition)),
        findsWidgets,
      );

      await gesture.up();
      await tester.pumpAndSettle(const Duration(seconds: 2));
    });
  }

  testWidgets('straying sideways keeps the scrub speed on desktop', (
    tester,
  ) async {
    final controller = await _pumpList(tester);
    final track = tester.getSize(find.byType(Scrollable)).height;
    final pixelsPerThumbPixel =
        controller.position.maxScrollExtent / (track - 48);

    final thumb = await revealScrollbarThumb(tester);
    final gesture = await tester.startGesture(thumb);
    await gesture.moveBy(const Offset(-110, 0));
    await gesture.moveBy(const Offset(0, 40));
    await tester.pump();

    expect(find.byKey(_rateKey), findsNothing);
    expect(controller.offset, closeTo(40 * pixelsPerThumbPixel, 1));

    await gesture.up();
    await tester.pumpAndSettle(const Duration(seconds: 2));
  }, variant: TargetPlatformVariant.desktop());

  testWidgets('the thumb eases wider while held and back when let go', (
    tester,
  ) async {
    await _pumpList(tester);

    final thumb = await revealScrollbarThumb(tester);
    expect(scrollbarPainter(tester).thickness, 6);

    final gesture = await tester.startGesture(thumb);
    await gesture.moveBy(const Offset(0, 10));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 40));
    expect(scrollbarPainter(tester).thickness, inExclusiveRange(6, 10));
    await tester.pump(const Duration(milliseconds: 100));
    expect(scrollbarPainter(tester).thickness, 10);

    await gesture.up();
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(scrollbarPainter(tester).thickness, 6);
  });

  testWidgets('straying sideways from the thumb slows the scrub', (
    tester,
  ) async {
    final controller = await _pumpList(tester);
    final position = controller.position;
    final track = tester.getSize(find.byType(Scrollable)).height;
    final pixelsPerThumbPixel = position.maxScrollExtent / (track - 48);

    final thumb = await revealScrollbarThumb(tester);
    final gesture = await tester.startGesture(thumb);
    await gesture.moveBy(const Offset(0, 40));
    await tester.pump();
    expect(controller.offset, closeTo(40 * pixelsPerThumbPixel, 1));

    await gesture.moveBy(const Offset(-110, 0));
    await tester.pump();
    expect(find.byKey(_rateKey), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(_rateKey)).data, '×1/4');

    await gesture.moveBy(const Offset(0, 40));
    await tester.pump();
    expect(controller.offset, closeTo(50 * pixelsPerThumbPixel, 1));

    await gesture.moveBy(const Offset(110, 0));
    await tester.pump();
    expect(find.byKey(_rateKey), findsNothing);

    await gesture.up();
    await tester.pumpAndSettle(const Duration(seconds: 2));
  });

  testWidgets('a pinned thumb can be grabbed without scrolling first', (
    tester,
  ) async {
    final controller = await _pumpList(tester, thumbVisibility: true);
    await tester.pumpAndSettle();

    final listRect = tester.getRect(find.byType(Scrollable));
    final gesture = await tester.startGesture(
      Offset(listRect.right - 5, listRect.top + 24),
    );
    await gesture.moveBy(const Offset(0, 40));
    await tester.pump();
    expect(controller.offset, greaterThan(0));
    expect(find.byKey(_hintKey), findsOneWidget);

    await gesture.up();
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(scrollbarPainter(tester).fadeoutOpacityAnimation.value, 1);
  });
}
