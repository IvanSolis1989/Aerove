import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';

const _installed = {
  'TestCjkHan': [
    'C:/Windows/Fonts/NotoSansSC-VF.ttf',
    '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
    '/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc',
  ],
  'TestCjkKana': [
    'C:/Windows/Fonts/msgothic.ttc',
    '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
  ],
  'TestCjkHangul': [
    'C:/Windows/Fonts/malgun.ttf',
    '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
    '/usr/share/fonts/truetype/nanum/NanumGothic.ttf',
  ],
};

const _square = 'TestCjkSquare';

Future<List<String>>? _families;

/// CI uses synthetic full-em squares; local acceptance uses installed fonts.
Future<List<String>> loadCjkTestFonts() => _families ??= _load();

Future<List<String>> _load() async {
  final families = <String>[];
  final installed = Platform.environment['AEROVE_TEST_CJK_FONTS'] == 'fixture'
      ? const <String, List<String>>{}
      : _installed;
  for (final MapEntry(key: family, value: paths) in installed.entries) {
    for (final path in paths) {
      final file = File(path);
      if (!file.existsSync()) continue;
      await (FontLoader(
        family,
      )..addFont(file.readAsBytes().then(ByteData.sublistView))).load();
      families.add(family);
      break;
    }
  }
  final bytes = await File('test/fixtures/fonts/CjkSquare.ttf').readAsBytes();
  if (sha256.convert(bytes).toString() !=
      '3334afaf7b6e1621fdf8e90bd5032e69e3071ee3c14e39cb0049c3f25df3dc6f') {
    throw StateError('CJK square test fixture SHA256 mismatch');
  }
  await (FontLoader(
    _square,
  )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
  return [...families, _square];
}
