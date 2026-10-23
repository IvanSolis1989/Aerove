import 'package:fl_clash/pages/editor.dart';
import 'package:fl_clash/ui/font.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/system_test_font.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    await (FontLoader(
      'EditorProportionalFixture',
    )..addFont(loadSdkTestFont())).load();
  });

  double width(String text, TextStyle style) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: TextDirection.ltr,
    )..layout();
    try {
      return painter.width;
    } finally {
      painter.dispose();
    }
  }

  test('a proportional app theme cannot change editor column alignment', () {
    const app = TextStyle(
      fontFamily: 'EditorProportionalFixture',
      fontSize: 16,
    );
    expect((width('WWWW', app) - width('iiii', app)).abs(), greaterThan(10));
    final styles = editorMonospaceStyles(
      platform: TargetPlatform.android,
      fontSize: 16,
    );
    final editor = styles.text;
    expect(width('WWWW', editor), closeTo(width('iiii', editor), 0.01));
    final lineNumbers = styles.lineNumbers;
    expect(
      width('1001', lineNumbers),
      closeTo(width('8888', lineNumbers), 0.01),
    );
    expect(editor.fontFamilyFallback, contains('sans-serif'));
    expect(editor.fontFamilyFallback, isNot(contains(desktopEmojiFontFamily)));
  });

  test('desktop code retains platform monospace and emoji compatibility', () {
    final windows = editorMonospaceStyles(
      platform: TargetPlatform.windows,
      fontSize: 16,
      systemFallback: const ['Microsoft YaHei UI'],
    );
    expect(windows.text.fontFamily, 'Consolas');
    expect(windows.text.fontFamilyFallback, [
      desktopEmojiFontFamily,
      'Microsoft YaHei UI',
    ]);
    expect(
      editorMonospaceStyles(
        platform: TargetPlatform.macOS,
        fontSize: 16,
      ).text.fontFamily,
      'Menlo',
    );
  });
}
