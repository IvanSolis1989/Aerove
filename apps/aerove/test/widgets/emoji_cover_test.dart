import 'package:fl_clash/ui/ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  test('emoji spans let the platform resolve its own glyphs', () {
    const style = TextStyle(fontFamily: 'Installed CJK');
    final span = EmojiText.spanOf('中文 A 🇯🇵 👨‍👩‍👧‍👦', style);
    expect(span.text, '中文 A 🇯🇵 👨‍👩‍👧‍👦');
    expect(span.style, style);
    expect(span.children, isNull);
  });

  for (final flag in ['🇸🇬', '🇺🇸', '🇯🇵', '🇭🇰', '🇨🇭', '🇳🇵']) {
    for (final size in [40.0, 48.0]) {
      testWidgets('$flag fits a $size badge without font-specific metrics', (
        tester,
      ) async {
        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.rtl,
            child: MediaQuery(
              data: const MediaQueryData(textScaler: TextScaler.linear(2)),
              child: Center(child: EmojiCover(flag, size: size)),
            ),
          ),
        );
        expect(tester.getSize(find.byType(EmojiCover)), Size.square(size));
        expect(
          tester.widget<FittedBox>(find.byType(FittedBox)).fit,
          BoxFit.cover,
        );
        final text = tester.widget<EmojiText>(find.byType(EmojiText));
        expect(text.text, flag);
        expect(text.style!.fontFamily, isNull);
        expect(
          MediaQuery.textScalerOf(tester.element(find.byType(EmojiText))),
          TextScaler.noScaling,
        );
        expect(tester.takeException(), isNull);
      });
    }
  }
}
