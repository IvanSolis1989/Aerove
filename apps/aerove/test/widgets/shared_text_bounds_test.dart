import '../helpers/system_test_font.dart';

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/proxies/card.dart';
import 'package:fl_clash/views/proxies/common.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_profiles.dart';

const _url = 'https://example.com/generate_204';
const _name = 'Ágj a\u0301j\u0301 🇮🇩 Long fallback proxy';
const _font = 'SharedBoundsLatin';
const _protocols = [
  'URLTest',
  'Vless',
  'AnyTLS',
  'Mieru',
  'Hysteria',
  'Hysteria2',
];
const _latencies = [23, 99, 130, 999, 1234, 9800];
bool _hasCjk = false;

InlineSpan _colored(InlineSpan value, Color color) {
  final span = value as TextSpan;
  return TextSpan(
    text: span.text,
    style: span.style?.copyWith(color: color, shadows: const []),
    children: span.children?.map((child) => _colored(child, color)).toList(),
  );
}

Future<({ByteData bytes, int width, int height, Uint8List png})> _shot(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
) async => (await tester.runAsync(() async {
  final image = await boundary.toImage();
  try {
    return (
      bytes: (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!,
      width: image.width,
      height: image.height,
      png: (await image.toByteData(
        format: ui.ImageByteFormat.png,
      ))!.buffer.asUint8List(),
    );
  } finally {
    image.dispose();
  }
}))!;

Future<List<Map<String, Object?>>> _glyphRows(
  WidgetTester tester,
  GlobalKey capture,
  String name,
) async {
  final boundary =
      capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final paragraphs = tester
      .renderObjectList<RenderParagraph>(
        find.descendant(
          of: find.byKey(capture),
          matching: find.byType(RichText),
        ),
      )
      .toList();
  final cards = {
    for (final element
        in find
            .descendant(
              of: find.byKey(capture),
              matching: find.byType(RichText),
            )
            .evaluate())
      element.findRenderObject(): element
          .findAncestorWidgetOfExactType<ProxyCard>(),
  };
  final painted = await _shot(tester, boundary);
  final originals = [for (final paragraph in paragraphs) paragraph.text];
  final masks = <({ByteData bytes, Rect region})>[];
  final results = <Map<String, Object?>>[];
  for (final paragraph in paragraphs) {
    final painter = TextPainter(
      text: _colored(paragraph.text, Colors.white),
      textAlign: paragraph.textAlign,
      textDirection: paragraph.textDirection,
      textScaler: paragraph.textScaler,
      maxLines: paragraph.maxLines,
      ellipsis: paragraph.overflow == TextOverflow.ellipsis ? '\u2026' : null,
      locale: paragraph.locale,
      strutStyle: paragraph.strutStyle,
      textWidthBasis: paragraph.textWidthBasis,
      textHeightBehavior: paragraph.textHeightBehavior,
    )..layout(minWidth: paragraph.size.width, maxWidth: paragraph.size.width);
    final origin = paragraph.localToGlobal(Offset.zero, ancestor: boundary);
    final recorder = ui.PictureRecorder();
    painter.paint(Canvas(recorder), origin);
    final picture = recorder.endRecording();
    final image = picture.toImageSync(painted.width, painted.height);
    picture.dispose();
    final data = (await tester.runAsync(
      () => image.toByteData(format: ui.ImageByteFormat.rawRgba),
    ))!;
    image.dispose();
    final clips = <String>[];
    RenderObject child = paragraph;
    while (child.parent != null) {
      final parent = child.parent!;
      final clip = parent.describeApproximatePaintClip(child);
      if (clip != null) {
        clips.add(
          '${parent.runtimeType}:${MatrixUtils.transformRect(parent.getTransformTo(boundary), clip)}',
        );
      }
      if (parent == boundary) break;
      child = parent;
    }
    masks.add((bytes: data, region: (origin & paragraph.size).inflate(8)));
    results.add({
      'text': paragraph.text.toPlainText(),
      'bounds': (origin & paragraph.size).toString(),
      'ellipsis': painter.didExceedMaxLines,
      'layoutWidth': paragraph.size.width,
      'intrinsicWidth': painter.maxIntrinsicWidth,
      'fontFamily': paragraph.text.style?.fontFamily,
      'fontFallback': paragraph.text.style?.fontFamilyFallback,
      'fontSize': paragraph.text.style?.fontSize,
      'proxyCard': cards[paragraph]?.type.name,
      'proxyProtocol': cards[paragraph]?.proxy.type,
      'clips': clips,
    });
    painter.dispose();
  }
  late ({ByteData bytes, int width, int height, Uint8List png}) blank;
  try {
    for (var i = 0; i < paragraphs.length; i++) {
      paragraphs[i].text = _colored(originals[i], Colors.transparent);
    }
    await tester.pump();
    blank = await _shot(tester, boundary);
  } finally {
    for (var i = 0; i < paragraphs.length; i++) {
      paragraphs[i].text = originals[i];
    }
    await tester.pump();
  }
  for (var i = 0; i < masks.length; i++) {
    final mask = masks[i];
    final area = mask.region.intersect(
      Rect.fromLTWH(0, 0, painted.width.toDouble(), painted.height.toDouble()),
    );
    final missing = <int>[];
    var supported = 0;
    for (var y = area.top.ceil(); y < area.bottom.floor(); y++) {
      var expected = 0;
      var visible = 0;
      for (var x = area.left.ceil(); x < area.right.floor(); x++) {
        final offset = (y * painted.width + x) * 4;
        if (mask.bytes.getUint8(offset + 3) < 180) continue;
        expected++;
        var difference = 0;
        for (var channel = 0; channel < 3; channel++) {
          difference +=
              (painted.bytes.getUint8(offset + channel) -
                      blank.bytes.getUint8(offset + channel))
                  .abs();
        }
        if (difference >= 3) visible++;
      }
      if (expected >= 3) {
        supported++;
        if (visible < expected * 0.6) missing.add(y);
      }
    }
    results[i].addAll({'inkRows': supported, 'missingRows': missing});
  }
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output != null) {
    Directory(output).createSync(recursive: true);
    File('$output/shared-text-$name.png').writeAsBytesSync(painted.png);
    File(
      '$output/shared-text-$name-background.png',
    ).writeAsBytesSync(blank.png);
    File('$output/shared-text-$name.json').writeAsStringSync(
      jsonEncode({'cjkFontLoaded': _hasCjk, 'paragraphs': results}),
    );
  }
  return results;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() async {
    await (FontLoader(_font)..addFont(loadSdkTestFont())).load();
    final latinProbe = TextPainter(
      text: const TextSpan(
        text: 'Hg',
        style: TextStyle(fontFamily: _font, fontSize: 20),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    expect(
      latinProbe.width,
      closeTo(25.4, 0.2),
      reason: 'The real font must replace Ahem',
    );
    latinProbe.dispose();
    final cjk = File('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc');
    if (cjk.existsSync()) {
      await (FontLoader(
            'SharedBoundsCjk',
          )..addFont(Future.value(ByteData.sublistView(cjk.readAsBytesSync()))))
          .load();
      _hasCjk = true;
    }
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
      expect(
        _hasCjk,
        isTrue,
        reason: 'CI real-font coverage requires Noto CJK',
      );
    }
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  testWidgets('shared detector catches a clipped real glyph positive control', (
    tester,
  ) async {
    final key = GlobalKey();
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(fontFamily: _font),
        home: RepaintBoundary(
          key: key,
          child: ColoredBox(
            color: Colors.black,
            child: Align(
              alignment: Alignment.topLeft,
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: ClipRect(
                  child: SizedBox(
                    height: 8,
                    width: 180,
                    child: OverflowBox(
                      alignment: Alignment.topLeft,
                      minHeight: 50,
                      maxHeight: 50,
                      child: const Text(
                        'Ágj Ágj',
                        style: TextStyle(fontSize: 32, color: Colors.white),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    final rows = await _glyphRows(tester, key, 'positive-control');
    expect(rows.single['inkRows'] as int, greaterThan(8));
    expect(rows.single['missingRows'] as List<int>, isNotEmpty);
    expect(rows.single['clips'].toString(), contains('RenderClipRect'));
  });

  for (final brightness in Brightness.values) {
    for (final scale in [0.8, 1.0, 1.4]) {
      for (final quality in [null, ...LiquidGlassQuality.values]) {
        final name = '${brightness.name}-$scale-${quality?.name ?? 'none'}';
        testWidgets('shared and actual proxy glyph rows survive $name', (
          tester,
        ) async {
          tester.view.physicalSize = const Size(360, 1800);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWith(TestProfiles.new),
              delayDataSourceProvider.overrideWithBuild(
                (_, _) => {
                  _url: {_name: 123},
                },
              ),
            ],
          );
          addTearDown(container.dispose);
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = const Size(
            360,
            1800,
          );
          final key = GlobalKey();
          final scheme = ColorScheme.fromSeed(
            seedColor: Colors.teal,
            brightness: brightness,
          );
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: MaterialApp(
                theme: ThemeData(
                  colorScheme: scheme,
                  fontFamily: _font,
                  fontFamilyFallback: [_font, if (_hasCjk) 'SharedBoundsCjk'],
                ).withAppTypography.withAppShapes.withAppSurfaces,
                themeAnimationStyle: AnimationStyle.noAnimation,
                localizationsDelegates: const [
                  AppLocalizations.delegate,
                  AppUiLocalizations.delegate,
                  ...GlobalMaterialLocalizations.delegates,
                ],
                supportedLocales: AppLocalizations.delegate.supportedLocales,
                builder: (context, child) => MediaQuery(
                  data: MediaQuery.of(
                    context,
                  ).copyWith(textScaler: TextScaler.linear(scale)),
                  child: Builder(
                    builder: (context) {
                      final measure = Measure.of(context, scale);
                      globalState.measure = measure;
                      return MeasureScope(
                        measure: measure,
                        child: AppViewScope(
                          child: RepaintBoundary(
                            key: key,
                            child: LiquidGlassScope(
                              enabled: quality != null,
                              quality: quality ?? LiquidGlassQuality.standard,
                              child: LiquidGlassHost(
                                enabled: quality != null,
                                child: Stack(
                                  fit: StackFit.expand,
                                  children: [
                                    Positioned.fill(
                                      child: LiquidGlassColorField(
                                        child: LiquidGlassColorSource(
                                          child: ColoredBox(
                                            color: brightness == Brightness.dark
                                                ? const Color(0xFF16353F)
                                                : const Color(0xFFBDE5EC),
                                          ),
                                        ),
                                      ),
                                    ),
                                    child!,
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
                home: Material(
                  type: MaterialType.transparency,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Builder(
                      builder: (context) => Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        spacing: 12,
                        children: [
                          ChipTitle(
                            title:
                                'Ágj a\u0301j\u0301 ${_hasCjk ? '中文' : 'Fallback'}',
                            chip: 'Ágj',
                            style: AppTextStyle.headline.of(context),
                          ),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: TonalChip(
                              label:
                                  'Ágj a\u0301j\u0301 ${_hasCjk ? '中文' : 'Fallback'}',
                              color: scheme.surfaceContainer,
                              foregroundColor: scheme.onSurface,
                            ),
                          ),
                          DetailRow.text(
                            title: 'Destination GeoIP',
                            value:
                                'Ágj ${_hasCjk ? '中文' : 'Fallback'} 203.0.113.15',
                          ),
                          GlassText.rich(
                            TextSpan(
                              children: [
                                const TextSpan(text: 'Long rich Ágj '),
                                TextSpan(
                                  text:
                                      'a\u0301j\u0301 ${_hasCjk ? '中文' : 'Fallback'}',
                                  style: const TextStyle(
                                    fontStyle: FontStyle.italic,
                                  ),
                                ),
                              ],
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: AppTextStyle.subheadline.of(context),
                          ),
                          for (final type in ProxyCardType.values)
                            SizedBox(
                              height: getItemHeight(type),
                              child: ProxyCard(
                                groupName: 'group',
                                testUrl: _url,
                                proxy: const Proxy(
                                  name: _name,
                                  type: 'Shadowsocks',
                                ),
                                groupType: GroupType.Selector,
                                type: type,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final rows = await _glyphRows(tester, key, name);
          expect(rows.length, greaterThanOrEqualTo(12));
          for (final row in rows) {
            expect(row['inkRows'] as int, greaterThan(0), reason: '$name $row');
            expect(row['missingRows'], isEmpty, reason: '$name $row');
          }
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox.shrink());
        }, skip: quality != null && !ui.ImageFilter.isShaderFilterSupported);
      }
    }
  }

  for (final brightness in Brightness.values) {
    for (final scale in [0.8, 1.0, 1.4]) {
      for (final quality in [null, ...LiquidGlassQuality.values]) {
        final name =
            'protocol-${brightness.name}-$scale-${quality?.name ?? 'none'}';
        testWidgets('three-column protocol and latency glyph geometry $name', (
          tester,
        ) async {
          tester.view.physicalSize = const Size(406, 1050);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWith(TestProfiles.new),
              delayDataSourceProvider.overrideWithBuild(
                (_, _) => {
                  _url: {
                    for (final (index, protocol) in _protocols.indexed)
                      '$protocol $_name': _latencies[index],
                  },
                },
              ),
            ],
          );
          addTearDown(container.dispose);
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = const Size(
            406,
            1050,
          );
          final capture = GlobalKey();
          final scheme = ColorScheme.fromSeed(
            seedColor: Colors.teal,
            brightness: brightness,
          );
          Future<List<Map<String, Object?>>> render(
            LiquidGlassQuality? activeQuality,
          ) async {
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: MaterialApp(
                  theme: ThemeData(
                    colorScheme: scheme,
                    fontFamily: _font,
                  ).withAppTypography.withAppShapes.withAppSurfaces,
                  themeAnimationStyle: AnimationStyle.noAnimation,
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    AppUiLocalizations.delegate,
                    ...GlobalMaterialLocalizations.delegates,
                  ],
                  supportedLocales: AppLocalizations.delegate.supportedLocales,
                  builder: (context, child) => MediaQuery(
                    data: MediaQuery.of(
                      context,
                    ).copyWith(textScaler: TextScaler.linear(scale)),
                    child: Builder(
                      builder: (context) {
                        final measure = Measure.of(context, scale);
                        globalState.measure = measure;
                        return MeasureScope(
                          measure: measure,
                          child: AppViewScope(
                            child: RepaintBoundary(
                              key: capture,
                              child: LiquidGlassScope(
                                enabled: activeQuality != null,
                                quality:
                                    activeQuality ??
                                    LiquidGlassQuality.standard,
                                child: LiquidGlassHost(
                                  enabled: activeQuality != null,
                                  child: Stack(
                                    fit: StackFit.expand,
                                    children: [
                                      Positioned.fill(
                                        child: LiquidGlassColorField(
                                          child: LiquidGlassColorSource(
                                            child: ColoredBox(
                                              color:
                                                  brightness == Brightness.dark
                                                  ? const Color(0xFF16353F)
                                                  : const Color(0xFFBDE5EC),
                                            ),
                                          ),
                                        ),
                                      ),
                                      child!,
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                  home: Material(
                    type: MaterialType.transparency,
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Builder(
                        builder: (context) => Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          spacing: 8,
                          children: [
                            for (final type in ProxyCardType.values)
                              for (var row = 0; row < 2; row++)
                                Row(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  spacing: 8,
                                  children: [
                                    for (var column = 0; column < 3; column++)
                                      SizedBox(
                                        width: [110.0, 120.0, 130.0][column],
                                        height: getItemHeight(type),
                                        child: ProxyCard(
                                          groupName: 'group',
                                          testUrl: _url,
                                          proxy: Proxy(
                                            name:
                                                '${_protocols[row * 3 + column]} $_name',
                                            type: _protocols[row * 3 + column],
                                          ),
                                          groupType: GroupType.Selector,
                                          type: type,
                                        ),
                                      ),
                                  ],
                                ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
            await tester.pumpAndSettle();
            return _glyphRows(
              tester,
              capture,
              activeQuality == null && quality != null
                  ? '$name-reference-none'
                  : name,
            );
          }

          final reference = await render(null);
          final rows = quality == null ? reference : await render(quality);
          expect(rows.length, reference.length);
          final protocols = rows.where(
            (row) => _protocols.contains(row['text']),
          );
          final delays = rows.where(
            (row) => RegExp(r'^\d+ ms$').hasMatch(row['text']! as String),
          );
          expect(protocols, hasLength(18));
          expect(delays, hasLength(18));
          for (final row in [...protocols, ...delays]) {
            expect(row['inkRows'] as int, greaterThan(0), reason: '$name $row');
            expect(
              row['missingRows'],
              isEmpty,
              reason:
                  'Vertical glyph loss is distinct from recorded horizontal ellipsis: $name $row',
            );
          }
          for (final (index, row) in rows.indexed) {
            final original = reference[index];
            expect(
              original['inkRows'] as int,
              greaterThan(0),
              reason: '$name $original',
            );
            expect(original['missingRows'], isEmpty, reason: '$name $original');
            for (final field in ['text', 'bounds', 'layoutWidth', 'ellipsis']) {
              expect(
                row[field],
                original[field],
                reason:
                    'Glass must preserve the original layout and horizontal ellipsis: $name $row',
              );
            }
            expect(
              row['missingRows'],
              isEmpty,
              reason:
                  'Glass must preserve the glyph support of the original layout: $name $row',
            );
          }
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox.shrink());
        }, skip: quality != null && !ui.ImageFilter.isShaderFilterSupported);
      }
    }
  }
}
