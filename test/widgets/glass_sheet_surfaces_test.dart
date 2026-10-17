import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/ui/dialog.dart';
import 'package:fl_clash/ui/form_sheet.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/paged_sheet.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:fl_clash/ui/sheet.dart';
import 'package:fl_clash/ui/side_sheet.dart';
import 'package:fl_clash/ui/surface.dart';
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
      Rect? originalSurfaceBounds;
      for (final (enabled, quality) in [
        (false, LiquidGlassQuality.ultra),
        (true, LiquidGlassQuality.standard),
        (true, LiquidGlassQuality.ultra),
      ]) {
        final props = enabled && quality == LiquidGlassQuality.ultra
            ? const SheetProps(backgroundColor: Colors.orange)
            : const SheetProps();
        await tester.pumpWidget(
          LiquidGlassScope(
            enabled: enabled,
            quality: quality,
            child: TestApp(
              key: UniqueKey(),
              includeNavigatorKey: false,
              overrides: [viewSizeProvider.overrideWithBuild((_, _) => size)],
              theme: ThemeData(brightness: Brightness.dark),
              child: Scaffold(
                body: Builder(
                  builder: (context) => TextButton(
                    onPressed: () => show<void>(
                      context: context,
                      props: props,
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
        final route = ModalRoute.of(tester.element(find.byKey(_probe)));
        if (kind == 'side') {
          expect(route, isA<ModalSideSheetRoute<void>>());
          expect(
            (route! as ModalSideSheetRoute<void>).backgroundColor,
            props.backgroundColor,
          );
        } else if (kind == 'form') {
          expect(route, isA<FormSheetRoute<void>>());
          expect(
            (route! as FormSheetRoute<void>).backgroundColor,
            props.backgroundColor,
          );
        }
        final sheetMaterial = find
            .ancestor(
              of: find.byType(CommonScaffold),
              matching: find.byType(Material),
            )
            .first;
        final material = tester.widget<Material>(sheetMaterial);
        final explicitColor =
            kind != 'bottom' && enabled && quality == LiquidGlassQuality.ultra;
        final baseColor = explicitColor
            ? Colors.orange
            : SurfaceLevel.page.colorOf(
                Theme.of(tester.element(sheetMaterial)).colorScheme,
              );
        expect(material.color, isNotNull);
        expect(material.color!.a, enabled ? lessThanOrEqualTo(0.24) : 1.0);
        expect(
          material.color!.toARGB32() & 0xFFFFFF,
          baseColor.toARGB32() & 0xFFFFFF,
        );
        final sheetScaffold = find.descendant(
          of: find.byType(CommonScaffold),
          matching: find.byType(Scaffold),
        );
        if (kind == 'side') {
          expect(
            tester.widget<Scaffold>(sheetScaffold).backgroundColor,
            enabled ? Colors.transparent : null,
          );
        } else {
          expect(sheetScaffold, findsNothing);
        }
        final bounds = tester.getRect(find.byKey(_probe));
        originalBounds ??= bounds;
        expect(bounds, originalBounds);
        final surfaceBounds = tester.getRect(sheetMaterial);
        originalSurfaceBounds ??= surfaceBounds;
        expect(surfaceBounds, originalSurfaceBounds);
        final barrier = tester.widget<AnimatedModalBarrier>(
          find.byType(AnimatedModalBarrier).last,
        );
        expect(barrier.color.value!.a, greaterThan(0));
        await tester.tapAt(const Offset(4, 4));
        await tester.pumpAndSettle();
        expect(find.byKey(_probe), findsNothing);
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        expect(find.byKey(_probe), findsOneWidget);
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
          child: MaterialApp(
            home: SheetProvider(
              type: SheetType.bottomSheet,
              child: SizedBox(
                width: 400,
                height: 600,
                child: Align(
                  alignment: Alignment.bottomCenter,
                  child: PagedSheet(
                    child: Navigator(
                      onGenerateInitialRoutes: (_, _) => [
                        PagedSheetRoute<void>(
                          builder: (_) => const SizedBox(
                            key: _probe,
                            width: 400,
                            height: 200,
                          ),
                        ),
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
      final material = tester.widget<Material>(
        find.descendant(
          of: find.byType(PagedSheet),
          matching: find.byType(Material),
        ),
      );
      expect(material.color!.a, enabled ? 0.24 : 1);
      expect(material.clipBehavior, Clip.antiAlias);
      final bounds = tester.getRect(find.byType(PagedSheet));
      originalBounds ??= bounds;
      expect(bounds, originalBounds);
      expect(find.byKey(_probe), findsOneWidget);
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets(
    'default alert action uses tinted glass and preserves its action',
    (tester) async {
      var taps = 0;
      for (final (enabled, highContrast) in [
        (false, false),
        (true, false),
        (true, true),
      ]) {
        await tester.pumpWidget(
          LiquidGlassScope(
            enabled: enabled,
            child: TestApp(
              wrapInProviderScope: true,
              child: MediaQuery(
                data: MediaQueryData.fromView(tester.view).copyWith(
                  highContrast: highContrast,
                ),
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
        final foreground = style.foregroundColor!.resolve({})!;
        final source = enabled ? scheme.primary : scheme.onPrimary;
        expect(foreground.a, enabled && !highContrast ? 0.94 : 1);
        expect(foreground.withValues(alpha: source.a), source);
        await tester.tap(button);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }
      expect(taps, 3);
    },
  );
}
