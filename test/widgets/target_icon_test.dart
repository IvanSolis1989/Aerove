import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/svg.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/glyph_finders.dart';
import '../helpers/test_app.dart';

Future<void> _pump(WidgetTester tester, Widget icon) {
  return tester.pumpWidget(
    TestApp(
      child: Center(
        child: IconTheme(data: const IconThemeData(size: 32), child: icon),
      ),
    ),
  );
}

void main() {
  test('group titles remove whole emoji and preserve plain text', () {
    for (final (name, title) in [
      ('🌐全球节点', '全球节点'),
      ('AI 🤖 服务 🌐', 'AI 服务'),
      ('香港 🇭🇰 节点', '香港 节点'),
      ('家庭 👨‍👩‍👧‍👦 节点 👍🏽', '家庭 节点'),
      ('🏛️ 金融 1️⃣ 支付', '金融 支付'),
      ('🌐🇭🇰👨‍👩‍👧‍👦👍🏽', ''),
      ('  普通 A1 名称  ', '  普通 A1 名称  '),
    ]) {
      expect(groupTitleWithoutEmoji(name), title, reason: name);
    }
  });

  testWidgets('group monogram caps its explicit foreground only in glass', (
    tester,
  ) async {
    for (final glass in [false, true]) {
      await _pump(
        tester,
        LiquidGlassScope(
          enabled: glass,
          child: const IconTheme(
            data: IconThemeData(color: Colors.red),
            child: GroupMonogram('🤖 AI'),
          ),
        ),
      );
      final text = tester.widget<Text>(find.text('🤖'));
      expect(
        text.style!.color,
        Colors.red,
      );
    }
  });

  testWidgets('a group without an icon prefers its first complete emoji', (
    tester,
  ) async {
    for (final (name, mark) in [
      ('🌐全球节点', '🌐'),
      ('AI 🤖 服务 🌐', '🤖'),
      ('香港 🇭🇰 节点', '🇭🇰'),
      ('家庭 👨‍👩‍👧‍👦 节点', '👨‍👩‍👧‍👦'),
      ('服务 👍🏽 节点', '👍🏽'),
      ('金融 🏛️ 支付', '🏛️'),
      ('收藏 ⭐ 服务', '⭐'),
      ('线路 1️⃣', '1️⃣'),
      ('♻️ 9 Auto', '♻️'),
    ]) {
      await _pump(
        tester,
        CommonTargetIcon(src: '', fallback: GroupMonogram(name)),
      );

      expect(find.text(mark), findsOne, reason: name);
    }
  });

  testWidgets('a name without emoji keeps its first meaningful character', (
    tester,
  ) async {
    for (final (name, mark) in [
      ('节点选择', '节'),
      ('proxy', 'P'),
      (' -- 9 Auto', '9'),
      ('A1普通数字', 'A'),
      (' -- ', ' '),
    ]) {
      await _pump(
        tester,
        CommonTargetIcon(src: '', fallback: GroupMonogram(name)),
      );

      expect(find.text(mark), findsOne, reason: name);
    }
  });

  testWidgets('a missing image shows a placeholder, as does an empty name', (
    tester,
  ) async {
    await _pump(tester, const CommonTargetIcon(src: ''));

    expect(find.byGlyph(AppGlyphs.photos), findsOne);

    await _pump(tester, const GroupMonogram(''));

    expect(find.byGlyph(AppGlyphs.photos), findsOne);
  });

  testWidgets('an explicit SVG takes priority over the name emoji', (
    tester,
  ) async {
    final svg = base64Encode(
      utf8.encode(
        '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1">'
        '<rect width="1" height="1" fill="red"/></svg>',
      ),
    );
    await _pump(
      tester,
      CommonTargetIcon(
        src: 'data:image/svg+xml;base64,$svg',
        fallback: const GroupMonogram('AI 🤖 服务'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(SvgPicture), findsOne);
    expect(find.text('🤖'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('the placeholder glyph is opaque, so a grid of icons that '
      'cannot load paints no offscreen layer per tile', (tester) async {
    await _pump(tester, const CommonTargetIcon(src: ''));

    final glyph = tester.widget<GlyphIcon>(find.byGlyph(AppGlyphs.photos));
    expect(glyph.color!.a, 1);
  });

  group('a remote icon', () {
    late List<String> reads;

    setUp(() {
      reads = [];
      readRemoteIcon = (src) {
        reads.add(src);
        return Stream.value(_onePixelPng);
      };
    });

    tearDown(() => readRemoteIcon = null);

    testWidgets('loads at once on a page that sits still', (tester) async {
      const src = 'https://example.com/still.png';
      await _pump(
        tester,
        const CommonTargetIcon(src: src, fallback: GroupMonogram('AI 🤖 服务')),
      );
      await tester.pump();

      expect(reads, [src]);
      expect(find.byType(Image), findsOne);
      expect(find.text('🤖'), findsNothing);
    });

    testWidgets('loads while the route bringing it in arrives', (tester) async {
      const src = 'https://example.com/pushed.png';
      await tester.pumpWidget(
        TestApp(
          child: Builder(
            builder: (context) => TextButton(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const Center(
                    child: IconTheme(
                      data: IconThemeData(size: 32),
                      child: CommonTargetIcon(src: src),
                    ),
                  ),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(reads, [src]);
      expect(find.byGlyph(AppGlyphs.photos), findsNothing);
      expect(find.byType(Image), findsOne);
      await tester.pumpAndSettle();
    });

    testWidgets('a page pushed again shows a whole set it loaded before '
        'from its first frame', (tester) async {
      readRemoteIcon = (src) {
        reads.add(src);
        return Stream.value(Uint8List.fromList(_onePixelPng));
      };
      final icons = Wrap(
        children: [
          for (var i = 0; i < 340; i++)
            CommonTargetIcon(src: 'https://example.com/set/$i.png'),
        ],
      );
      await _pump(tester, icons);
      await tester.pump();
      await tester.pumpWidget(
        TestApp(
          child: Builder(
            builder: (context) => TextButton(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => IconTheme(
                    data: const IconThemeData(size: 32),
                    child: icons,
                  ),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byType(Image), findsNWidgets(340));
    });

    testWidgets('an oversized icon decodes at icon size, into the one cache '
        'entry its precache filled', (tester) async {
      final png = (await tester.runAsync(() => _squarePng(512)))!;
      readRemoteIcon = (_) => Stream.value(png);
      const src = 'https://example.com/large.png';
      imageCache.clear();
      await tester.runAsync(() => precacheTargetIcons([src]));

      expect(imageCache.currentSize, 1);

      await _pump(tester, const CommonTargetIcon(src: src));
      await tester.pump();

      expect(tester.widget<RawImage>(find.byType(RawImage)).image!.width, 192);
      expect(imageCache.currentSize, 1);
    });

    testWidgets('shows the fallback once a load ends without an image', (
      tester,
    ) async {
      readRemoteIcon = (_) => const Stream.empty();
      await _pump(
        tester,
        const CommonTargetIcon(
          src: 'https://example.com/missing.png',
          fallback: GroupMonogram('proxy'),
        ),
      );

      expect(find.text('P'), findsNothing);

      await tester.pump();

      expect(find.text('P'), findsOne);
    });

    testWidgets('a failed download falls back to the complete name emoji', (
      tester,
    ) async {
      readRemoteIcon = (_) => Stream.error(StateError('unavailable'));
      await _pump(
        tester,
        const CommonTargetIcon(
          src: 'https://example.com/failed-emoji.png',
          fallback: GroupMonogram('香港 🇭🇰 节点'),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('🇭🇰'), findsOne);
      expect(tester.takeException(), isNull);
    });

    testWidgets('unreadable image bytes fall back to the name emoji', (
      tester,
    ) async {
      readRemoteIcon = (_) => Stream.value(Uint8List.fromList([0, 1, 2]));
      await _pump(
        tester,
        const CommonTargetIcon(
          src: 'https://example.com/corrupt-emoji.png',
          fallback: GroupMonogram('家庭 👨‍👩‍👧‍👦 节点'),
        ),
      );
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();

      expect(find.text('👨‍👩‍👧‍👦'), findsOne);
      expect(tester.takeException(), isNull);
    });
  });
}

Future<Uint8List> _squarePng(int size) async {
  final recorder = ui.PictureRecorder();
  Canvas(recorder).drawRect(
    Rect.fromLTWH(0, 0, size.toDouble(), size.toDouble()),
    Paint()..color = const Color(0xFF2196F3),
  );
  final image = await recorder.endRecording().toImage(size, size);
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  return data!.buffer.asUint8List();
}

final _onePixelPng = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
);
