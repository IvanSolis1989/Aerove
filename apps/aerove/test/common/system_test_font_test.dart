import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

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
      final font = ByteData.sublistView(bytes);
      int tableOffset(String tag) {
        for (var index = 0; index < font.getUint16(4); index++) {
          final record = 12 + index * 16;
          if (ascii.decode(bytes.sublist(record, record + 4)) == tag) {
            return font.getUint32(record + 8);
          }
        }
        throw StateError('Missing font table $tag');
      }

      final unitsPerEm = font.getUint16(tableOffset('head') + 18);
      expect(unitsPerEm, 1000);
      expect(font.getUint16(tableOffset('maxp') + 4), 2);
      expect(font.getUint16(tableOffset('hhea') + 34), 1);
      expect(font.getUint16(tableOffset('hmtx')), unitsPerEm);
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
      final recorder = ui.PictureRecorder();
      painter.paint(ui.Canvas(recorder)..scale(4), Offset.zero);
      final picture = recorder.endRecording();
      final image = await picture.toImage(360, 48);
      try {
        final pixels = (await image.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!;
        int alpha(int x, int y) =>
            pixels.getUint8((y * image.width + x) * 4 + 3);
        for (var glyph = 0; glyph < 9; glyph++) {
          final left = glyph * 40;
          expect(
            [
              alpha(left + 4, 4),
              alpha(left + 20, 20),
              alpha(left + 35, 35),
              alpha(left, 20),
              alpha(left + 39, 20),
              alpha(left + 20, 0),
              alpha(left + 20, 47),
            ],
            [255, 255, 255, 0, 0, 0, 0],
            reason: 'glyph $glyph square ink and bearings',
          );
        }
      } finally {
        image.dispose();
        picture.dispose();
        painter.dispose();
      }
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
