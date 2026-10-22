import 'package:fl_clash/features/form/custom_issue.dart';
import 'package:fl_clash/ui/card.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/views/theme_preview.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

Container _firstContainer(WidgetTester tester, Type type) =>
    tester.widget<Container>(
      find
          .descendant(of: find.byType(type), matching: find.byType(Container))
          .first,
    );

void main() {
  for (final quality in [null, ...LiquidGlassQuality.values]) {
    testWidgets('feature preview and error surfaces respect glass $quality', (
      tester,
    ) async {
      await tester.pumpWidget(
        ProviderScope(
          child: TestApp(
            homeBuilder: (child) => LiquidGlassScope(
              enabled: quality != null,
              quality: quality ?? LiquidGlassQuality.standard,
              child: child,
            ),
            child: const Column(
              children: [
                ErrorBanner(message: 'Invalid configuration'),
                Expanded(child: ThemeLivePreview()),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final banner =
          _firstContainer(tester, ErrorBanner).decoration! as ShapeDecoration;
      expect(banner.color!.a, quality == null ? 1 : 0.24);
      final preview = _firstContainer(tester, ThemeLivePreview);
      if (quality == null) {
        final decoration = preview.decoration! as ShapeDecoration;
        expect(decoration.color!.a, 1);
        return;
      }
      expect(preview.decoration, isNull);
      final group = find.ancestor(
        of: find.byType(MiniScreen),
        matching: find.byType(CardGroup),
      );
      expect(group, findsOneWidget);
      expect(
        find.descendant(of: group, matching: find.byType(LiquidGlass)),
        findsOneWidget,
      );
    });
  }
}
