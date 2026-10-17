import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/ui/dialog.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/paged_sheet.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:fl_clash/ui/sheet.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';

const _probe = ValueKey('sheet-body');

void main() {
  for (final (kind, size, show) in [
    ('bottom', const Size(400, 800), showSheet),
    ('side', const Size(1200, 800), showSheet),
    ('form', const Size(1200, 800), showFormSheet),
  ]) {
    testWidgets('$kind glass keeps sheet geometry and modal separation', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      Rect? originalBounds;
      for (final (enabled, quality) in [
        (false, LiquidGlassQuality.ultra),
        (true, LiquidGlassQuality.standard),
        (true, LiquidGlassQuality.ultra),
      ]) {
        await tester.pumpWidget(
          LiquidGlassScope(
            enabled: enabled,
            quality: quality,
            child: TestApp(
              key: UniqueKey(),
              overrides: [viewSizeProvider.overrideWithBuild((_, _) => size)],
              theme: ThemeData(brightness: Brightness.dark),
              child: Scaffold(
                body: Builder(
                  builder: (context) => TextButton(
                    onPressed: () => show<void>(
                      context: context,
                      props: enabled && quality == LiquidGlassQuality.ultra
                          ? const SheetProps(backgroundColor: Colors.orange)
                          : const SheetProps(),
                      builder: (_) => const CommonScaffold(
                        title: 'Sheet',
                        body: SizedBox(key: _probe, height: 200),
                      ),
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        final sheetMaterials = find.ancestor(
          of: find.byKey(_probe),
          matching: find.byType(Material),
        );
        final painted = tester.widgetList<Material>(sheetMaterials).where(
          (material) => material.color != null && material.color!.a > 0,
        );
        expect(painted, isNotEmpty);
        expect(
          painted.map((material) => material.color!.a),
          enabled ? everyElement(lessThanOrEqualTo(0.24)) : contains(1.0),
        );
        if (kind != 'bottom' && enabled && quality == LiquidGlassQuality.ultra) {
          expect(
            painted.any((material) =>
                material.color!.withValues(alpha: 1) == Colors.orange),
            isTrue,
          );
        }
        final sheetScaffold = tester.widget<Scaffold>(
          find.ancestor(of: find.byKey(_probe), matching: find.byType(Scaffold)).first,
        );
        expect(sheetScaffold.backgroundColor, enabled ? Colors.transparent : null);
        final bounds = tester.getRect(find.byKey(_probe));
        originalBounds ??= bounds;
        expect(bounds, originalBounds);
        final barrier = tester.widget<AnimatedModalBarrier>(
          find.byType(AnimatedModalBarrier).last,
        );
        expect(barrier.color.value!.a, greaterThan(0));
        final context = tester.element(find.byKey(_probe));
        Navigator.of(context).pop();
        await tester.pumpAndSettle();
        expect(find.byKey(_probe), findsNothing);
        expect(tester.takeException(), isNull);
      }
    });
  }

  testWidgets('nested pages keep a translucent base in both glass qualities', (
    tester,
  ) async {
    for (final (enabled, quality) in [
      (false, LiquidGlassQuality.ultra),
      (true, LiquidGlassQuality.standard),
      (true, LiquidGlassQuality.ultra),
    ]) {
      await tester.pumpWidget(
        LiquidGlassScope(
          enabled: enabled,
          quality: quality,
          child: const MaterialApp(
            home: PagedSheet(child: SizedBox.expand(key: _probe)),
          ),
        ),
      );
      final material = tester.widget<Material>(
        find.descendant(of: find.byType(PagedSheet), matching: find.byType(Material)),
      );
      expect(material.color!.a, enabled ? 0.24 : 1);
      expect(material.clipBehavior, Clip.antiAlias);
    }
  });

  testWidgets('default alert action uses tinted glass and preserves its action', (
    tester,
  ) async {
    var taps = 0;
    for (final enabled in [false, true]) {
      await tester.pumpWidget(
        LiquidGlassScope(
          enabled: enabled,
          child: TestApp(
            wrapInProviderScope: true,
            child: Scaffold(
              body: CommonDialog(
                title: 'Confirm',
                actions: [
                  TextButton(
                    onPressed: () => taps++,
                    child: const Text('Continue'),
                  ),
                ],
                child: const Text('Body'),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final button = find.widgetWithText(TextButton, 'Continue');
      final context = tester.element(button);
      final style = TextButtonTheme.of(context).style!;
      final scheme = Theme.of(context).colorScheme;
      expect(
        style.backgroundColor!.resolve({}),
        enabled ? Colors.transparent : scheme.primary,
      );
      expect(style.backgroundBuilder, enabled ? isNotNull : isNull);
      expect(
        style.foregroundColor!.resolve({}),
        enabled ? scheme.primary : scheme.onPrimary,
      );
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
    expect(taps, 2);
  });
}
