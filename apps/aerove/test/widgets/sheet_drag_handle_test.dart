import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/sheet_header.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Future<Color> _handleColor(
  WidgetTester tester, {
  required bool glass,
  bool highContrast = false,
  Brightness brightness = Brightness.light,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: ThemeData(brightness: brightness),
      home: LiquidGlassScope(
        enabled: glass,
        child: Builder(
          builder: (context) => MediaQuery(
            data: MediaQuery.of(context).copyWith(highContrast: highContrast),
            child: const Scaffold(body: SheetDragHandle()),
          ),
        ),
      ),
    ),
  );
  final handle = tester.widget<Container>(
    find.descendant(
      of: find.byType(SheetDragHandle),
      matching: find.byType(Container),
    ),
  );
  return (handle.decoration! as ShapeDecoration).color!;
}

ColorScheme _colorScheme(WidgetTester tester) =>
    Theme.of(tester.element(find.byType(SheetDragHandle))).colorScheme;

void main() {
  for (final brightness in Brightness.values) {
    testWidgets('on glass the grabber is translucent ($brightness)', (
      tester,
    ) async {
      final color = await _handleColor(
        tester,
        glass: true,
        brightness: brightness,
      );
      final colorScheme = _colorScheme(tester);

      expect(color, colorScheme.onSurface.withValues(alpha: 0.3));
    });
  }

  testWidgets('in plain solid the grabber stays solid', (tester) async {
    final color = await _handleColor(tester, glass: false);

    expect(color, _colorScheme(tester).onSurfaceVariant);
    expect(color.a, 1);
  });

  testWidgets('high contrast keeps the solid grabber over glass', (
    tester,
  ) async {
    final color = await _handleColor(tester, glass: true, highContrast: true);

    expect(color, _colorScheme(tester).onSurfaceVariant);
  });
}
