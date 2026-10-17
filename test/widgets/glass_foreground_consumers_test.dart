import 'package:fl_clash/features/form/form_row.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  testWidgets('explicit chip and form colors follow the glass scope', (
    tester,
  ) async {
    const source = Color(0xFFB32E49);
    const subdued = Color.fromRGBO(34, 99, 173, 0.38);
    for (final enabled in [false, true]) {
      for (final quality in LiquidGlassQuality.values) {
        for (final highContrast in [false, true]) {
          await tester.pumpWidget(
            TestApp(
              child: MediaQuery(
                data: MediaQueryData(highContrast: highContrast),
                child: LiquidGlassScope(
                  enabled: enabled,
                  quality: quality,
                  child: const Column(
                    children: [
                      TonalChip(
                        label: 'Explicit chip',
                        color: Colors.blue,
                        foregroundColor: source,
                      ),
                      TonalChip(
                        label: 'Subdued chip',
                        color: Colors.blue,
                        foregroundColor: subdued,
                      ),
                      FormRow(
                        title: 'Explicit form',
                        titleStyle: TextStyle(color: source),
                      ),
                      FormRow(title: 'Inherited form'),
                    ],
                  ),
                ),
              ),
            ),
          );
          final expected = enabled && !highContrast
              ? GlassForeground.cap(source)
              : source;
          expect(
            tester.widget<Text>(find.text('Explicit chip')).style!.color,
            expected,
          );
          expect(
            tester.widget<Text>(find.text('Subdued chip')).style!.color,
            subdued,
          );
          final labels = tester.widgetList<TooltipLabel>(
            find.byType(TooltipLabel),
          );
          expect(
            labels.singleWhere((label) => label.text == 'Explicit form')
                .style!.color,
            expected,
          );
          expect(
            labels.singleWhere((label) => label.text == 'Inherited form').style,
            isNull,
          );
          expect(tester.takeException(), isNull);
        }
      }
    }
  });
}
