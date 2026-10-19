import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/about.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../helpers/test_app.dart';

const _font = 'AboutMiSans';
const _fontHash =
    '0ddef90648998900175cfdca9a6f087a2544c182f130b0ad4f7e94a03a115e79';
final _fontPath = Platform.environment['XCLASH_MISANS_FONT'];
const _dpr = 3.0;

typedef _Pixels = ({ByteData rgba, Uint8List png, int width, int height});

InlineSpan _recolor(InlineSpan span, Color color) {
  final text = span as TextSpan;
  return TextSpan(
    text: text.text,
    style: text.style?.copyWith(color: color, shadows: const []),
    children: text.children?.map((child) => _recolor(child, color)).toList(),
  );
}

Future<_Pixels> _read(WidgetTester tester, ui.Image image) async {
  try {
    return (await tester.runAsync<_Pixels>(() async {
      final rgba = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      final png = await image.toByteData(format: ui.ImageByteFormat.png);
      return (
        rgba: rgba!,
        png: png!.buffer.asUint8List(),
        width: image.width,
        height: image.height,
      );
    }))!;
  } finally {
    image.dispose();
  }
}

Future<_Pixels> _capture(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
) async {
  final image = (await tester.runAsync(
    () => boundary.toImage(pixelRatio: _dpr),
  ))!;
  return _read(tester, image);
}

List<Map<String, Object?>> _ancestors(
  RenderParagraph paragraph,
  RenderRepaintBoundary boundary,
) {
  final result = <Map<String, Object?>>[];
  RenderObject child = paragraph;
  while (true) {
    final transform = child.getTransformTo(boundary);
    final parent = child == boundary ? null : child.parent;
    final clip = parent?.describeApproximatePaintClip(child);
    result.add({
      'type': child.runtimeType.toString(),
      'paintBounds': child.paintBounds.toString(),
      'globalPaintBounds': MatrixUtils.transformRect(
        transform,
        child.paintBounds,
      ).toString(),
      'transform': transform.storage.toList(),
      'parentClip': clip?.toString(),
      'globalParentClip': clip == null
          ? null
          : MatrixUtils.transformRect(
              parent!.getTransformTo(boundary),
              clip,
            ).toString(),
    });
    if (child == boundary || parent == null) break;
    child = parent;
  }
  return result;
}

List<Map<String, Object?>> _inkLayers(ContainerLayer root, Rect title) {
  final result = <Map<String, Object?>>[];
  void visit(ContainerLayer layer, Matrix4 transform) {
    for (Layer? child = layer.firstChild; child != null;) {
      final next = transform.clone();
      if (child is OffsetLayer) {
        next.translateByDouble(child.offset.dx, child.offset.dy, 0, 1);
      }
      if (child is TransformLayer) next.multiply(child.transform!);
      if (child is LiquidGlassInkLayer) {
        final bounds = MatrixUtils.transformRect(next, child.maskRect);
        if (bounds.overlaps(title)) {
          result.add({
            'maskRect': child.maskRect.toString(),
            'globalMaskRect': bounds.toString(),
            'transform': next.storage.toList(),
            'strength': child.strength,
            'source': child.source.toARGB32(),
            'contrast': child.contrast,
          });
        }
      }
      if (child is ContainerLayer) visit(child, next);
      child = child.nextSibling;
    }
  }

  visit(root, Matrix4.identity());
  return result;
}

Future<Map<String, Object?>> _measure(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
  RenderParagraph paragraph,
  String name,
  Brightness brightness,
) async {
  final actual = await _capture(tester, boundary);
  final original = paragraph.text;
  final origin = paragraph.localToGlobal(Offset.zero, ancestor: boundary);
  final painter = TextPainter(
    text: _recolor(original, Colors.white),
    textAlign: paragraph.textAlign,
    textDirection: paragraph.textDirection,
    textScaler: paragraph.textScaler,
    maxLines: paragraph.maxLines,
    locale: paragraph.locale,
    strutStyle: paragraph.strutStyle,
    textWidthBasis: paragraph.textWidthBasis,
    textHeightBehavior: paragraph.textHeightBehavior,
  )..layout(minWidth: paragraph.size.width, maxWidth: paragraph.size.width);
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder)..scale(_dpr);
  painter.paint(canvas, origin);
  final picture = recorder.endRecording();
  final mask = await _read(
    tester,
    picture.toImageSync(actual.width, actual.height),
  );
  picture.dispose();
  late _Pixels blank;
  try {
    paragraph.text = _recolor(original, Colors.transparent);
    await tester.pump();
    blank = await _capture(tester, boundary);
  } finally {
    paragraph.text = original;
    await tester.pump();
  }
  final missing = <List<int>>[];
  final supportByRow = <int, int>{};
  final missingByThreshold = {8: 0, 16: 0, 32: 0, 64: 0, 180: 0};
  var support = 0;
  for (var y = 0; y < actual.height; y++) {
    for (var x = 0; x < actual.width; x++) {
      final offset = (y * actual.width + x) * 4;
      final alpha = mask.rgba.getUint8(offset + 3);
      if (alpha < 8) continue;
      support++;
      supportByRow.update(y, (count) => count + 1, ifAbsent: () => 1);
      var difference = 0;
      for (var channel = 0; channel < 3; channel++) {
        difference +=
            (actual.rgba.getUint8(offset + channel) -
                    blank.rgba.getUint8(offset + channel))
                .abs();
      }
      if (difference >= 3) continue;
      missing.add([x, y, alpha, difference]);
      for (final threshold in missingByThreshold.keys) {
        if (alpha >= threshold) {
          missingByThreshold[threshold] = missingByThreshold[threshold]! + 1;
        }
      }
    }
  }
  final glyphs = <Map<String, Object?>>[];
  for (var index = 0; index < displayAppName.length; index++) {
    final boxes = painter.getBoxesForSelection(
      TextSelection(baseOffset: index, extentOffset: index + 1),
    );
    final glyphRows = <int, int>{};
    for (final row in supportByRow.keys) {
      for (var x = 0; x < actual.width; x++) {
        if (!boxes.any(
          (box) =>
              x + 0.5 >= (origin.dx + box.left) * _dpr &&
              x + 0.5 < (origin.dx + box.right) * _dpr,
        )) {
          continue;
        }
        if (mask.rgba.getUint8((row * actual.width + x) * 4 + 3) >= 8) {
          glyphRows.update(row, (count) => count + 1, ifAbsent: () => 1);
        }
      }
    }
    glyphs.add({
      'index': index,
      'glyph': displayAppName[index],
      'boxes': boxes.map((box) => box.toRect().toString()).toList(),
      'supportByPhysicalRow': {
        for (final entry in glyphRows.entries) '${entry.key}': entry.value,
      },
      'missing': [
        for (final point in missing)
          if (boxes.any(
            (box) =>
                point[0] + 0.5 >= (origin.dx + box.left) * _dpr &&
                point[0] + 0.5 < (origin.dx + box.right) * _dpr,
          ))
            point,
      ],
    });
  }
  final rows = supportByRow.keys.toList()..sort();
  final result = <String, Object?>{
    'name': name,
    'brightness': brightness.name,
    'font': 'official MiSans VF 4.009; not verified OS4 shipped binary',
    'fontSHA256': _fontHash,
    'viewportPhysical': [actual.width, actual.height],
    'viewportLogical': [actual.width / _dpr, actual.height / _dpr],
    'viewportAssumption':
        '390dp reference viewport, DPR3, scale1; not device calibrated; '
        'chat screenshot dimensions are not treated as device dimensions',
    'dpr': _dpr,
    'text': original.toPlainText(),
    'fontFamily': original.style?.fontFamily,
    'fontSize': original.style?.fontSize,
    'fontWeight': original.style?.fontWeight?.value,
    'fontHeight': original.style?.height,
    'sourceARGB': original.style?.color?.toARGB32(),
    'textScale': paragraph.textScaler.scale(22) / 22,
    'paragraphSize': paragraph.size.toString(),
    'overflow': paragraph.overflow.name,
    'maxLines': paragraph.maxLines,
    'originPhysical': [origin.dx * _dpr, origin.dy * _dpr],
    'baselinesPhysical': [
      for (final line in painter.computeLineMetrics())
        (origin.dy + line.baseline) * _dpr,
    ],
    'supportAlpha8': support,
    'supportByRow': {
      for (final entry in supportByRow.entries) '${entry.key}': entry.value,
    },
    'bottomTwoRowsMissing': [
      for (final point in missing)
        if (rows.isNotEmpty && point[1] >= rows.last - 1) point,
    ],
    'missingPoints': missing,
    'missingByThreshold': {
      for (final entry in missingByThreshold.entries)
        '${entry.key}': entry.value,
    },
    'glyphs': glyphs,
    'ancestors': _ancestors(paragraph, boundary),
    'inkLayers': _inkLayers(boundary.debugLayer!, origin & paragraph.size),
  };
  painter.dispose();
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output != null) {
    final directory = Directory(output)..createSync(recursive: true);
    final base = '${directory.path}/about-title-$name';
    File('$base.png').writeAsBytesSync(actual.png);
    File('$base-background.png').writeAsBytesSync(blank.png);
    File('$base-mask.png').writeAsBytesSync(mask.png);
    File(
      '$base.json',
    ).writeAsStringSync(const JsonEncoder.withIndent('  ').convert(result));
  }
  return result;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() async {
    globalState.packageInfo = PackageInfo(
      appName: 'Aerove',
      packageName: 'com.follow.clash',
      version: 'test',
      buildNumber: '1',
    );
    if (_fontPath case final path?) {
      final bytes = await File(path).readAsBytes();
      expect(sha256.convert(bytes).toString(), _fontHash);
      await (FontLoader(
        _font,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    }
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  testWidgets(
    'MiSans About hero preserves every lower-edge glyph point at DPR3',
    (tester) async {
      tester.view.physicalSize = const Size(390 * _dpr, 800 * _dpr);
      tester.view.devicePixelRatio = _dpr;
      addTearDown(tester.view.reset);
      final results = <Map<String, Object?>>[];
      final settings = [
        for (final brightness in [Brightness.light, Brightness.dark])
          for (final mode in LiquidGlassMode.values)
            (brightness: brightness, mode: mode),
      ];
      for (final (:brightness, :mode) in settings) {
        final container = ProviderContainer(
          overrides: [
            themeSettingProvider.overrideWithBuild(
              (_, _) => ThemeProps(
                fontFamily: _font,
                auroraEnabled: mode != LiquidGlassMode.none,
                liquidGlass: mode,
              ),
            ),
          ],
        );
        globalState.container = container;
        try {
          for (var phase = 0; phase < 3; phase++) {
            final capture = GlobalKey();
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: Consumer(
                  builder: (context, ref, _) {
                    final theme = watchAppTheme(ref, brightness);
                    return MaterialApp(
                      theme: theme,
                      darkTheme: theme,
                      themeMode: brightness == Brightness.light
                          ? ThemeMode.light
                          : ThemeMode.dark,
                      themeAnimationStyle: AnimationStyle.noAnimation,
                      locale: const Locale('zh', 'CN'),
                      localizationsDelegates: const [
                        AppLocalizations.delegate,
                        AppUiLocalizations.delegate,
                        ...GlobalMaterialLocalizations.delegates,
                      ],
                      supportedLocales:
                          AppLocalizations.delegate.supportedLocales,
                      builder: (context, child) => wrapAppScopes(
                        context,
                        RepaintBoundary(
                          key: capture,
                          child: Transform.translate(
                            offset: Offset(0, phase / 9),
                            child: AuroraAppSurface(
                              motion: false,
                              child: child!,
                            ),
                          ),
                        ),
                      ),
                      home: const AboutView(),
                    );
                  },
                ),
              ),
            );
            await tester.pumpAndSettle();
            final title = find.byWidgetPredicate(
              (widget) =>
                  widget is RichText &&
                  widget.text.toPlainText() == displayAppName,
            );
            expect(title, findsOneWidget);
            expect(Theme.of(tester.element(title)).brightness, brightness);
            final paragraph = tester.renderObject<RenderParagraph>(title);
            expect(paragraph.text.style?.fontFamily, _font);
            expect(paragraph.text.style?.fontSize, 22);
            expect(paragraph.text.style?.fontWeight, FontWeight.w700);
            expect(paragraph.text.style?.height, closeTo(28 / 22, 1e-9));
            results.add(
              await _measure(
                tester,
                capture.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary,
                paragraph,
                'MiSans-${brightness.name}-${mode.name}-phase$phase',
                brightness,
              ),
            );
          }
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          container.dispose();
        }
      }
      for (final result in results) {
        expect(
          result['supportAlpha8'],
          greaterThan(100),
          reason: '${result['name']}',
        );
        expect(result['missingPoints'], isEmpty, reason: jsonEncode(result));
        expect(result['bottomTwoRowsMissing'], isEmpty);
        expect(result['paragraphSize'], results.first['paragraphSize']);
        final layers = result['inkLayers']! as List<Map<String, Object?>>;
        if ((result['name']! as String).contains('-none-') ||
            !ui.ImageFilter.isShaderFilterSupported) {
          expect(layers, isEmpty, reason: jsonEncode(result));
        } else {
          expect(layers, isNotEmpty, reason: jsonEncode(result));
        }
      }
      for (var group = 0; group < results.length; group += 3) {
        final baseline =
            (results[group]['baselinesPhysical']! as List<double>).single;
        for (var phase = 0; phase < 3; phase++) {
          final current =
              (results[group + phase]['baselinesPhysical']! as List<double>)
                  .single;
          expect(current - baseline, closeTo(phase / 3, 1e-6));
        }
      }
      expect(tester.takeException(), isNull);
    },
    skip: _fontPath == null,
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );
}
