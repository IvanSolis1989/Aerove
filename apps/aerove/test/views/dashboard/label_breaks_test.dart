import 'package:fl_clash/views/dashboard/label_breaks.dart';
import 'package:flutter_test/flutter_test.dart';

/// A CJK character a full em, any other one half of it, as the fonts set it.
double _width(String text) =>
    text.runes.fold(0, (width, rune) => width + (rune >= 0x1100 ? 1 : 0.5));

List<String> _lines(String text) {
  final breaks = labelBreaks(text);
  var start = 0;
  return [
    for (final at in [...breaks, text.length])
      text.substring(start, start = at),
  ];
}

void main() {
  test('Han breaks between any two characters unless U+200B places it', () {
    expect(_lines('局域网代理'), ['局', '域', '网', '代', '理']);
    expect(_lines('局域网​代理'), ['局域网​', '代理']);
  });

  test('a word, a kana run and a Hangul run stay whole', () {
    expect(labelBreaks('Глобальный'), isEmpty);
    expect(labelBreaks('グローバル'), isEmpty);
    expect(labelBreaks('ダイレクト'), isEmpty);
    expect(labelBreaks('허용'), isEmpty);
    expect(_lines('LANプロキシ'), ['LAN', 'プロキシ']);
    expect(_lines('LAN 허용'), ['LAN ', '허용']);
    expect(_lines('Разрешить LAN'), ['Разрешить ', 'LAN']);
    expect(_lines('Autoriser le LAN'), ['Autoriser ', 'le ', 'LAN']);
    expect(_lines('Durée d’exécution'), ['Durée ', 'd’exécution']);
  });

  test('CJK punctuation keeps to its neighbours', () {
    expect(_lines('出站模式：规则'), ['出', '站', '模', '式：规', '则']);
  });

  test('two lines break where the longer one comes out shortest', () {
    String? balanced(String text) => balancedLabel(text, _width)?.text;
    expect(balanced('局域网代理'), '局域网\n代理');
    expect(balanced('局域网​代理'), '局域网\n代理');
    expect(balanced('LANプロキシ'), 'LAN\nプロキシ');
    expect(balanced('LAN 허용'), 'LAN\n허용');
    expect(balanced('Allow LAN'), 'Allow\nLAN');
    expect(balanced('Autoriser le LAN'), 'Autoriser\nle LAN');
    expect(balanced('Разрешить LAN'), 'Разрешить\nLAN');
    expect(balanced('グローバル'), isNull);
    expect(balanced('VPN'), isNull);
    expect(balancedLabel('局域网代理', _width)?.width, 3);
  });

  test('the longest piece is the widest run that never breaks', () {
    expect(longestPiece('局域网代理', _width), 1);
    expect(longestPiece('LANプロキシ', _width), 4);
    expect(longestPiece('Autoriser le LAN', _width), 4.5);
    expect(longestPiece('Качество Хороший', _width), 4);
  });
}
