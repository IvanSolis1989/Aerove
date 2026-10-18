import 'package:fl_clash/ui/ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  testWidgets('high contrast glass buttons keep an opaque foreground', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: MediaQueryData.fromView(
            tester.view,
          ).copyWith(highContrast: true),
          child: LiquidGlassScope(
            enabled: true,
            child: TonalButtonTheme(
              child: FilledButton(
                onPressed: () {},
                child: const Text('Action'),
              ),
            ),
          ),
        ),
      ),
    );
    final button = find.widgetWithText(FilledButton, 'Action');
    final context = tester.element(button);
    final style = FilledButtonTheme.of(context).style!;
    final source = Theme.of(context).colorScheme.onSurface;
    expect(style.foregroundColor!.resolve({}), source);
    expect(source.a, 1);
  });
  for (final quality in LiquidGlassQuality.values) {
    for (final enabled in [false, true]) {
      testWidgets('shared surfaces follow glass scope ($quality, $enabled)', (
        tester,
      ) async {
        var presses = 0;
        var selections = 0;
        await tester.pumpWidget(
          MaterialApp(
            home: LiquidGlassScope(
              enabled: enabled,
              quality: quality,
              child: Scaffold(
                body: Column(
                  children: [
                    TonalButtonTheme(
                      child: FilledButton(
                        onPressed: () => presses++,
                        child: const Text('Scoped action'),
                      ),
                    ),
                    TonalButton(
                      label: 'Tonal action',
                      onPressed: () => presses++,
                    ),
                    const FilledInputTheme(child: TextField()),
                    SegmentedControl<int>(
                      segments: const [(0, 'First'), (1, 'Second')],
                      value: 0,
                      onChanged: (_) => selections++,
                    ),
                    const CommonChip(label: 'Chip'),
                    const MetaChip(label: 'Metadata'),
                  ],
                ),
              ),
            ),
          ),
        );
        final context = tester.element(find.byType(CommonChip));
        final surface = LiquidGlassScope.surfaceColorOf(context, Colors.red);
        expect(surface.a, enabled ? 0.24 : 1);
        expect(surface.r, Colors.red.r);
        expect(
          LiquidGlassScope.surfaceColorOf(context, Colors.transparent),
          Colors.transparent,
        );
        final field = tester.element(find.byType(TextField));
        final fill = InputDecorationTheme.of(field).fillColor!;
        expect(fill.a, enabled ? lessThan(1) : equals(1));
        final button = find.widgetWithText(FilledButton, 'Scoped action');
        final buttonTheme = FilledButtonTheme.of(tester.element(button)).style!;
        expect(buttonTheme.backgroundBuilder, enabled ? isNotNull : isNull);
        final scheme = Theme.of(tester.element(button)).colorScheme;
        if (!enabled) {
          expect(buttonTheme.foregroundColor?.resolve({}), isNull);
          final material = tester.widget<Material>(
            find.descendant(of: button, matching: find.byType(Material)),
          );
          expect(material.color, scheme.primary);
          expect(material.color?.a, 1);
        }
        final foreground = DefaultTextStyle.of(
          tester.element(find.text('Scoped action')),
        ).style.color!;
        final source = enabled ? scheme.onSurface : scheme.onPrimary;
        expect(foreground.a, enabled ? 1.0 : source.a);
        expect(foreground.withValues(alpha: source.a), source);
        final segmentFills = tester
            .widgetList<DecoratedBox>(
              find.descendant(
                of: find.byType(SegmentedControl<int>),
                matching: find.byType(DecoratedBox),
              ),
            )
            .map((box) => box.decoration)
            .whereType<ShapeDecoration>()
            .map((decoration) => decoration.color)
            .whereType<Color>();
        expect(segmentFills, isNotEmpty);
        for (final color in segmentFills) {
          expect(color.a, enabled ? lessThan(1) : equals(1));
        }
        final selectedLabel = tester.element(find.text('First'));
        expect(
          DefaultTextStyle.of(selectedLabel).style.color,
          enabled
              ? Theme.of(selectedLabel).colorScheme.onSurface
              : Theme.of(selectedLabel).colorScheme.onPrimary,
        );
        expect(
          find.descendant(
            of: find.byType(TonalButton),
            matching: find.byType(LiquidGlass),
          ),
          enabled ? findsOneWidget : findsNothing,
        );
        await tester.tap(find.text('Scoped action'));
        await tester.tap(find.text('Tonal action'));
        await tester.tap(find.text('Second'));
        await tester.pumpAndSettle();
        expect(presses, 2);
        expect(selections, 1);
      });
    }
  }
}
