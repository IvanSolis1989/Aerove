import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/system_test_font.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'the pinned test font and original license match their source record',
    () async {
      final source =
          jsonDecode(
                await File('test/fixtures/fonts/SOURCE.json').readAsString(),
              )
              as Map<String, dynamic>;
      final font = source['font'] as Map<String, dynamic>;
      final license = source['license'] as Map<String, dynamic>;
      final data = await loadSdkTestFont();
      expect(data.lengthInBytes, font['sizeBytes']);
      expect(
        sha256.convert(data.buffer.asUint8List()).toString(),
        font['sha256'],
      );
      expect(
        sha256
            .convert(
              await File(
                'test/fixtures/fonts/${license['file']}',
              ).readAsBytes(),
            )
            .toString(),
        license['sha256'],
      );
    },
  );

  test(
    'the CJK square fixture matches its record and advances a full em',
    () async {
      final source =
          (jsonDecode(
                    await File(
                      'test/fixtures/fonts/SOURCE.json',
                    ).readAsString(),
                  )
                  as Map<String, dynamic>)['cjkSquare']
              as Map<String, dynamic>;
      final bytes = await File(
        'test/fixtures/fonts/${source['file']}',
      ).readAsBytes();
      expect(bytes.length, source['sizeBytes']);
      expect(sha256.convert(bytes).toString(), source['sha256']);
      await (FontLoader(
        'PinnedCjkSquare',
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
      final painter = TextPainter(
        text: const TextSpan(
          text: '局域网プロキシ허용',
          style: TextStyle(fontFamily: 'PinnedCjkSquare', fontSize: 10),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      expect(painter.width, 90);
      painter.dispose();
    },
  );

  test(
    'the fixture renders proportional Latin glyphs without SDK path lookup',
    () async {
      await (FontLoader(
        'PinnedRobotoFixture',
      )..addFont(loadSdkTestFont())).load();
      double width(String text) {
        final painter = TextPainter(
          text: TextSpan(
            text: text,
            style: const TextStyle(
              fontFamily: 'PinnedRobotoFixture',
              fontSize: 16,
            ),
          ),
          textDirection: TextDirection.ltr,
        )..layout();
        try {
          return painter.width;
        } finally {
          painter.dispose();
        }
      }

      expect(width('WWWW') - width('iiii'), greaterThan(10));
    },
  );
}
