import '../helpers/system_test_font.dart';

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/providers/service_status.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _organization = 'PT TELKOM INDONESIA';
const _ip = '203.0.113.9';
const _androidFont = 'DashboardAndroidRoboto';
final _androidFontPath = Platform.environment['XCLASH_ANDROID_ROBOTO_FONT'];
const _miSansFont = 'DashboardMiSans';
final _miSansFontPath = Platform.environment['XCLASH_MISANS_FONT'];
const _targets = [
  ServiceTarget.google,
  ServiceTarget.github,
  ServiceTarget.youtube,
  ServiceTarget.chatgpt,
];

class _Ips extends OutboundIpProbe {
  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: _ip, countryCode: 'ID'),
    ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

class _Services extends ServiceStatus {
  @override
  RoutedProbeState<ServiceTarget, ServiceCheck> build() => RoutedProbeState({
    for (final target in _targets)
      target: const ProbeEntry(
        phase: ProbePhase.fresh,
        value: ServiceCheck(ServiceProbeStatus.available, delay: 42),
      ),
  });

  @override
  void watch(ServiceTarget target) {}

  @override
  void unwatch(ServiceTarget target) {}
}

InlineSpan _paintSpan(InlineSpan span, Color color) {
  final text = span as TextSpan;
  return TextSpan(
    text: text.text,
    style: text.style?.copyWith(color: color, shadows: const []),
    children: text.children?.map((child) => _paintSpan(child, color)).toList(),
  );
}

Finder _paragraphs(String text) => find.byWidgetPredicate(
  (widget) => widget is RichText && widget.text.toPlainText() == text,
);

Future<({ByteData rgba, Uint8List png, int width, int height})> _capture(
  WidgetTester tester,
  RenderRepaintBoundary boundary, {
  double pixelRatio = 1,
}) async => (await tester.runAsync(() async {
  final image = await boundary.toImage(pixelRatio: pixelRatio);
  try {
    return (
      rgba: (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!,
      png: (await image.toByteData(
        format: ui.ImageByteFormat.png,
      ))!.buffer.asUint8List(),
      width: image.width,
      height: image.height,
    );
  } finally {
    image.dispose();
  }
}))!;

Future<List<Map<String, Object?>>> _measure(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
  List<RenderParagraph> paragraphs,
  String name, {
  double pixelRatio = 1,
}) async {
  final painted = await _capture(tester, boundary, pixelRatio: pixelRatio);
  final original = [for (final paragraph in paragraphs) paragraph.text];
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  canvas.scale(pixelRatio);
  final geometry = <Map<String, Object?>>[];
  final regions = <Rect>[];
  final glyphRegions = <List<({String text, int index, Rect rect})>>[];
  for (final paragraph in paragraphs) {
    final painter = TextPainter(
      text: _paintSpan(paragraph.text, Colors.white),
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
    painter.paint(canvas, origin);
    final metrics = painter.computeLineMetrics();
    final text = paragraph.text.toPlainText(includeSemanticsLabels: false);
    final glyphs = <({String text, int index, Rect rect})>[];
    for (var index = 0; index < text.length; index++) {
      if (!RegExp('[A-Za-z]').hasMatch(text[index])) continue;
      for (final box in painter.getBoxesForSelection(
        TextSelection(baseOffset: index, extentOffset: index + 1),
      )) {
        glyphs.add((
          text: text[index],
          index: index,
          rect: Rect.fromLTRB(
            (origin.dx + box.left) * pixelRatio,
            (origin.dy - 4) * pixelRatio,
            (origin.dx + box.right) * pixelRatio,
            (origin.dy + paragraph.size.height + 4) * pixelRatio,
          ),
        ));
      }
    }
    glyphRegions.add(glyphs);
    regions.add(
      Rect.fromLTWH(
        origin.dx * pixelRatio,
        origin.dy * pixelRatio,
        paragraph.size.width * pixelRatio,
        paragraph.size.height * pixelRatio,
      ).inflate(4 * pixelRatio),
    );
    final clips = <String>[];
    final ancestors = <Map<String, Object?>>[];
    RenderObject child = paragraph;
    while (child.parent != null) {
      final parent = child.parent!;
      ancestors.add({
        'type': parent.runtimeType.toString(),
        'paintBounds': parent.paintBounds.toString(),
      });
      final clip = parent.describeApproximatePaintClip(child);
      if (clip != null) {
        final rect = MatrixUtils.transformRect(
          parent.getTransformTo(boundary),
          clip,
        );
        clips.add('${parent.runtimeType}:$rect');
      }
      if (parent == boundary) break;
      child = parent;
    }
    geometry.add({
      'text': paragraph.text.toPlainText(),
      'paragraph': (origin & paragraph.size).toString(),
      'pixelRatio': pixelRatio,
      'lineBoxHeight': painter.height,
      'fontFamily': paragraph.text.style?.fontFamily,
      'fontFixtureVersion': paragraph.text.style?.fontFamily == _miSansFont
          ? 'MiSans VF 4.009'
          : paragraph.text.style?.fontFamily == _androidFont
          ? 'AOSP Roboto 3.005'
          : 'Flutter SDK Roboto Regular',
      'fontSize': paragraph.text.style?.fontSize,
      'fontWeight': paragraph.text.style?.fontWeight?.value,
      'fontHeight': paragraph.text.style?.height,
      'textScale': paragraph.textScaler.scale(15) / 15,
      'originPhysical': [origin.dx * pixelRatio, origin.dy * pixelRatio],
      'baselinesPhysical': [
        for (final line in metrics) (origin.dy + line.baseline) * pixelRatio,
      ],
      'paragraphPaintBounds': paragraph.paintBounds.toString(),
      'paragraphOverflow': paragraph.overflow.name,
      'ellipsis': painter.didExceedMaxLines,
      'clips': clips,
      'ancestors': ancestors,
    });
    painter.dispose();
  }
  final picture = recorder.endRecording();
  final mask = picture.toImageSync(painted.width, painted.height);
  picture.dispose();
  late ByteData expected;
  Uint8List? maskPng;
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  try {
    expected = (await tester.runAsync<ByteData>(
      () async => (await mask.toByteData(format: ui.ImageByteFormat.rawRgba))!,
    ))!;
    if (output != null) {
      maskPng = (await tester.runAsync<ByteData>(
        () async => (await mask.toByteData(format: ui.ImageByteFormat.png))!,
      ))!.buffer.asUint8List();
    }
  } finally {
    mask.dispose();
  }
  late ({ByteData rgba, Uint8List png, int width, int height}) blank;
  try {
    for (var i = 0; i < paragraphs.length; i++) {
      paragraphs[i].text = _paintSpan(original[i], Colors.transparent);
    }
    await tester.pump();
    blank = await _capture(tester, boundary, pixelRatio: pixelRatio);
  } finally {
    for (var i = 0; i < paragraphs.length; i++) {
      paragraphs[i].text = original[i];
    }
    await tester.pump();
  }
  for (var i = 0; i < geometry.length; i++) {
    final region = regions[i].intersect(
      Rect.fromLTWH(0, 0, painted.width.toDouble(), painted.height.toDouble()),
    );
    final missingRows = <int>[];
    final inkRows = <int>[];
    final visibleRows = <int>[];
    final missingPoints = <List<int>>[];
    for (var y = region.top.ceil(); y < region.bottom.floor(); y++) {
      var ink = 0;
      var visible = 0;
      for (var x = region.left.ceil(); x < region.right.floor(); x++) {
        final offset = (y * painted.width + x) * 4;
        if (expected.getUint8(offset + 3) < 180) continue;
        ink++;
        var difference = 0;
        for (var channel = 0; channel < 3; channel++) {
          difference +=
              (painted.rgba.getUint8(offset + channel) -
                      blank.rgba.getUint8(offset + channel))
                  .abs();
        }
        if (difference >= 3) {
          visible++;
        } else {
          missingPoints.add([x, y]);
        }
      }
      if (ink >= 3) {
        inkRows.add(y);
        if (visible >= ink * 0.6) visibleRows.add(y);
        if (visible < ink * 0.6) missingRows.add(y);
      }
    }
    final glyphs = <Map<String, Object?>>[];
    final baselines = geometry[i]['baselinesPhysical']! as List<double>;
    for (final glyph in glyphRegions[i]) {
      final bounds = glyph.rect.intersect(region);
      var strong = 0, descent = 0;
      for (var y = bounds.top.ceil(); y < bounds.bottom.floor(); y++) {
        for (var x = bounds.left.ceil(); x < bounds.right.floor(); x++) {
          if (expected.getUint8((y * painted.width + x) * 4 + 3) < 180)
            continue;
          strong++;
          if (baselines.isNotEmpty && y + 0.5 >= baselines.first) descent++;
        }
      }
      glyphs.add({
        'text': glyph.text,
        'index': glyph.index,
        'physicalBounds': glyph.rect.toString(),
        'strongPixels': strong,
        'descentStrongPixels': descent,
        'missingPoints': [
          for (final point in missingPoints)
            if (glyph.rect.contains(Offset(point[0] + 0.5, point[1] + 0.5)))
              point,
        ],
      });
    }
    geometry[i].addAll({
      'inkRows': inkRows,
      'visibleRows': visibleRows,
      'missingRows': missingRows,
      'missingPoints': missingPoints,
      'glyphs': glyphs,
    });
  }
  if (output != null) {
    final directory = Directory(output)..createSync(recursive: true);
    File(
      '${directory.path}/dashboard-bounds-$name.png',
    ).writeAsBytesSync(painted.png);
    File(
      '${directory.path}/dashboard-bounds-$name-background.png',
    ).writeAsBytesSync(blank.png);
    File(
      '${directory.path}/dashboard-bounds-$name-mask.png',
    ).writeAsBytesSync(maskPng!);
    File(
      '${directory.path}/dashboard-bounds-$name.json',
    ).writeAsStringSync(const JsonEncoder.withIndent('  ').convert(geometry));
  }
  return geometry;
}

Future<GlobalKey> _mountDashboard(
  WidgetTester tester, {
  required Brightness brightness,
  required LiquidGlassMode mode,
  required double scale,
  required double width,
  double pixelRatio = 1,
  String organization = _organization,
  String fontFamily = 'DashboardBounds',
  Offset paintShift = Offset.zero,
}) async {
  tester.view.physicalSize = Size(width * pixelRatio, 1000 * pixelRatio);
  tester.view.devicePixelRatio = pixelRatio;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(
    overrides: [
      profilesProvider.overrideWithValue(const [
        Profile(id: 1, autoUpdateDuration: Duration.zero),
      ]),
      suspendProvider.overrideWithValue(false),
      outboundIpProbeProvider.overrideWith(_Ips.new),
      serviceStatusProvider.overrideWith(_Services.new),
      enabledServiceTargetsProvider.overrideWithValue(_targets),
      ipQualityProvider.overrideWith(
        (_, _) async => IpQuality(
          ip: _ip,
          source: IpQualitySource.ipLocate,
          type: IpType.residential,
          organization: organization,
        ),
      ),
      themeSettingProvider.overrideWithBuild(
        (_, _) => ThemeProps(
          fontFamily: fontFamily,
          auroraEnabled: mode != LiquidGlassMode.none,
          liquidGlass: mode,
        ),
      ),
      dashboardStateProvider.overrideWithValue(
        const DashboardState(
          dashboardWidgets: [
            DashboardWidget.currentOutbound,
            DashboardWidget.serviceStatus,
          ],
        ),
      ),
      navigationItemsStateProvider.overrideWithValue(
        NavigationItemsState(
          value: [
            NavigationItem(
              glyph: AppGlyphs.dashboard,
              label: PageLabel.dashboard,
              builder: (_) =>
                  const DashboardView(platform: SupportPlatform.Android),
            ),
            NavigationItem(
              glyph: AppGlyphs.tools,
              label: PageLabel.tools,
              builder: (_) => const SizedBox.shrink(),
            ),
          ],
        ),
      ),
    ],
  );
  globalState.container = container;
  addTearDown(container.dispose);
  container.read(viewSizeProvider.notifier).value = Size(width, 1000);
  final capture = GlobalKey();
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: Consumer(
        builder: (_, ref, _) => TestApp(
          includeNavigatorKey: false,
          locale: const Locale('en'),
          theme: watchAppTheme(ref, brightness),
          child: Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(context).copyWith(
                textScaler: TextScaler.linear(scale),
                disableAnimations: true,
              ),
              child: RepaintBoundary(
                key: capture,
                child: Transform.translate(
                  offset: paintShift,
                  child: const AuroraAppSurface(
                    motion: false,
                    child: HomePage(),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return capture;
}

void main() {
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final font = FontLoader('DashboardBounds')..addFont(loadSdkTestFont());
    await font.load();
    if (_androidFontPath case final path?) {
      final bytes = await File(path).readAsBytes();
      expect(
        sha256.convert(bytes).toString(),
        '9ca9debb09459bf4e3e7f826f5cd0f35f253902b85684921fce2ba3f28dd0f50',
      );
      await (FontLoader(
        _androidFont,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    }
    if (_miSansFontPath case final path?) {
      final bytes = await File(path).readAsBytes();
      expect(
        sha256.convert(bytes).toString(),
        '0ddef90648998900175cfdca9a6f087a2544c182f130b0ad4f7e94a03a115e79',
      );
      await (FontLoader(
        _miSansFont,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    }
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  testWidgets('glyph detector identifies a vertically clipped real paragraph', (
    tester,
  ) async {
    final capture = GlobalKey();
    await tester.pumpWidget(
      TestApp(
        theme: ThemeData(fontFamily: 'DashboardBounds'),
        homeBuilder: (child) => Scaffold(body: child),
        child: RepaintBoundary(
          key: capture,
          child: const ColoredBox(
            color: Colors.white,
            child: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(
                width: 200,
                height: 12,
                child: ClipRect(
                  child: OverflowBox(
                    alignment: Alignment.topLeft,
                    maxHeight: 80,
                    child: Text(
                      'Google gjy',
                      style: TextStyle(color: Colors.black),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final rows = await _measure(
      tester,
      capture.currentContext!.findRenderObject()! as RenderRepaintBoundary,
      [tester.renderObject<RenderParagraph>(_paragraphs('Google gjy'))],
      'detector-positive-control',
    );
    expect(rows.single['inkRows'], isNotEmpty);
    expect(rows.single['missingRows'], isNotEmpty);
    expect(rows.single['clips'].toString(), contains('RenderClipRect'));
  });

  testWidgets('point detector catches a partial descender hidden by row voting', (
    tester,
  ) async {
    const pixelRatio = 3.0;
    final cutout = ValueNotifier<Rect?>(null);
    addTearDown(cutout.dispose);
    final capture = GlobalKey();
    await tester.pumpWidget(
      TestApp(
        theme: ThemeData(fontFamily: 'DashboardBounds'),
        child: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: 200,
            height: 70,
            child: RepaintBoundary(
              key: capture,
              child: ColoredBox(
                color: Colors.white,
                child: ValueListenableBuilder<Rect?>(
                  valueListenable: cutout,
                  builder: (context, hole, _) => ClipPath(
                    clipper: _Cutout(hole),
                    child: Align(
                      alignment: Alignment.topLeft,
                      child: Text(
                        'Google gjy',
                        style: AppTextStyle.body
                            .of(context)
                            .copyWith(color: Colors.black),
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
    await tester.pumpAndSettle();
    final boundary =
        capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final paragraph = tester.renderObject<RenderParagraph>(
      _paragraphs('Google gjy'),
    );
    final image = await _capture(tester, boundary, pixelRatio: pixelRatio);
    final origin = paragraph.localToGlobal(Offset.zero, ancestor: boundary);
    final glyphBox = paragraph
        .getBoxesForSelection(
          const TextSelection(baseOffset: 7, extentOffset: 8),
        )
        .single;
    final g = Rect.fromLTRB(
      (origin.dx + glyphBox.left) * pixelRatio,
      (origin.dy + glyphBox.top) * pixelRatio,
      (origin.dx + glyphBox.right) * pixelRatio,
      (origin.dy + glyphBox.bottom) * pixelRatio,
    );
    final metrics = TextPainter(
      text: paragraph.text,
      textAlign: paragraph.textAlign,
      textDirection: paragraph.textDirection,
      textScaler: paragraph.textScaler,
      maxLines: paragraph.maxLines,
      locale: paragraph.locale,
      strutStyle: paragraph.strutStyle,
      textWidthBasis: paragraph.textWidthBasis,
      textHeightBehavior: paragraph.textHeightBehavior,
    )..layout(minWidth: paragraph.size.width, maxWidth: paragraph.size.width);
    final baseline =
        (origin.dy + metrics.computeLineMetrics().single.baseline) * pixelRatio;
    metrics.dispose();
    Rect? selected;
    for (
      var y = baseline.ceil();
      y < (origin.dy + paragraph.size.height) * pixelRatio && selected == null;
      y++
    ) {
      var strong = 0;
      for (var x = 0; x < image.width; x++) {
        if (image.rgba.getUint8((y * image.width + x) * 4) < 70) strong++;
      }
      if (strong < 4) continue;
      for (var x = g.left.ceil(); x < g.right.floor(); x++) {
        if (image.rgba.getUint8((y * image.width + x) * 4) < 70) {
          selected = Rect.fromLTWH(
            x / pixelRatio,
            y / pixelRatio,
            1 / pixelRatio,
            1 / pixelRatio,
          );
          break;
        }
      }
    }
    expect(
      selected,
      isNotNull,
      reason:
          'font=${paragraph.text.style}; baseline=$baseline; glyph=$g; capture=${image.width}x${image.height}',
    );
    cutout.value = selected;
    await tester.pump();
    final rows = await _measure(
      tester,
      boundary,
      [paragraph],
      'point-detector-control',
      pixelRatio: pixelRatio,
    );
    expect(rows.single['missingRows'], isEmpty);
    expect(rows.single['missingPoints'], isNotEmpty);
    expect(rows.single['missingPoints'], hasLength(1));
    final glyphs = rows.single['glyphs']! as List<Map<String, Object?>>;
    expect(
      glyphs.singleWhere((glyph) => glyph['index'] == 7)['missingPoints'],
      isNotEmpty,
    );
  });

  for (final brightness in Brightness.values) {
    for (final family in [
      if (_miSansFontPath != null) _miSansFont,
      'DashboardBounds',
      if (_androidFontPath != null) _androidFont,
    ]) {
      testWidgets('single-line organization keeps descenders at DPR3 '
          '${brightness.name} $family', (tester) async {
        const organization = 'gjpqy Telekomunikasi Indonesia long organization';
        Map<String, Object?>? nativeLayout;
        final samples = <Map<String, Object?>>[];
        final firstBaselines = <LiquidGlassMode, List<double>>{};
        for (final phase in [0.0, 1 / 3, 2 / 3]) {
          for (final mode in LiquidGlassMode.values) {
            Size? shortCard;
            double? shortLineHeight;
            for (final (name, value) in const [
              ('short', 'gjpqy'),
              ('shrink', 'gjpqy Telekomunikasi Selular'),
              ('ellipsis', organization),
            ]) {
              final capture = await _mountDashboard(
                tester,
                brightness: brightness,
                mode: mode,
                scale: 1,
                width: 430,
                pixelRatio: 3,
                organization: value,
                fontFamily: family,
                paintShift: Offset(0, phase / 3),
              );
              final valueFinder = _paragraphs(value);
              expect(valueFinder, findsOneWidget);
              await tester.ensureVisible(valueFinder);
              await tester.pumpAndSettle();
              final valueParagraph = tester.renderObject<RenderParagraph>(
                valueFinder,
              );
              final card = tester.getSize(find.byType(CurrentOutboundCard));
              shortCard ??= card;
              shortLineHeight ??= valueParagraph.size.height;
              expect(
                card,
                shortCard,
                reason: '$name must not increase card height',
              );
              expect(
                valueParagraph.size.height,
                shortLineHeight,
                reason: '$name must keep the original value line height',
              );
              expect(valueParagraph.maxLines, 1);
              expect(valueParagraph.softWrap, isFalse);
              expect(valueParagraph.overflow, TextOverflow.ellipsis);
              final fontSize = valueParagraph.text.style!.fontSize!;
              expect(fontSize, inInclusiveRange(11, 15));
              expect(valueParagraph.didExceedMaxLines, name == 'ellipsis');
              if (name == 'short') {
                expect(fontSize, 15);
              } else if (name == 'shrink') {
                expect(fontSize, lessThan(15));
              } else {
                expect(fontSize, 11);
              }
              if (name != 'ellipsis') {
                await tester.pumpWidget(const SizedBox.shrink());
                await tester.pump();
                continue;
              }
              final paragraphFinder = _paragraphs(organization);
              expect(paragraphFinder, findsOneWidget);
              await tester.ensureVisible(paragraphFinder);
              await tester.pumpAndSettle();
              final paragraph = tester.renderObject<RenderParagraph>(
                paragraphFinder,
              );
              final style = paragraph.text.style;
              final layout = <String, Object?>{
                'size': paragraph.size,
                'fontFamily': style?.fontFamily,
                'fontSize': style?.fontSize,
                'height': style?.height,
                'weight': style?.fontWeight,
                'letterSpacing': style?.letterSpacing,
                'maxLines': paragraph.maxLines,
                'overflow': paragraph.overflow,
              };
              if (nativeLayout == null) {
                nativeLayout = layout;
              } else {
                expect(
                  layout,
                  nativeLayout,
                  reason: 'Ink must preserve text layout',
                );
              }
              final rows = await _measure(
                tester,
                capture.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary,
                [paragraph],
                'organization-descenders-${brightness.name}-${mode.name}-'
                '$family-phase${phase.toStringAsFixed(3)}-dpr3',
                pixelRatio: 3,
              );
              final baselines =
                  rows.single['baselinesPhysical']! as List<double>;
              expect(paragraph.maxLines, 1);
              expect(baselines, hasLength(1));
              if (phase == 0) {
                firstBaselines[mode] = baselines;
              } else {
                final first = firstBaselines[mode]!;
                expect(baselines, hasLength(first.length));
                for (var line = 0; line < baselines.length; line++) {
                  expect(baselines[line], closeTo(first[line] + phase, 1e-6));
                }
              }
              samples.add({'mode': mode.name, ...rows.single});
              expect(tester.takeException(), isNull);
              await tester.pumpWidget(const SizedBox.shrink());
              await tester.pump();
            }
          }
        }
        for (final sample in samples) {
          expect(sample['ellipsis'], isTrue);
          expect(sample['inkRows'], isNotEmpty);
          expect(sample['missingRows'], isEmpty, reason: '$sample');
          expect(sample['missingPoints'], isEmpty, reason: '$sample');
          final glyphs = sample['glyphs']! as List<Map<String, Object?>>;
          final descenders = glyphs.where(
            (glyph) =>
                'gjpqy'.contains(glyph['text']! as String) &&
                (glyph['descentStrongPixels']! as int) > 0,
          );
          expect(descenders.length, greaterThanOrEqualTo(2), reason: '$sample');
        }
      }, variant: TargetPlatformVariant.only(TargetPlatform.android));
    }
    testWidgets('known dashboard horizontal limit matches None at 320dp 2x '
        '${brightness.name}', (tester) async {
      List<Map<String, Object?>>? baselineLayout;
      Map<String, List<double>>? baselineOverflow;
      for (final mode in [LiquidGlassMode.none, LiquidGlassMode.ultra]) {
        final horizontal = <String, Set<double>>{};
        final previousError = FlutterError.onError!;
        FlutterError.onError = (details) {
          final match = RegExp(
            r'A RenderFlex overflowed by ([0-9.]+) pixels on the right\.',
          ).firstMatch(details.exceptionAsString());
          final diagnostics = [
            details.toString(),
            for (final node
                in details.informationCollector?.call() ?? <DiagnosticsNode>[])
              node.toStringDeep(minLevel: DiagnosticLevel.hidden),
          ].join('\n');
          final component = diagnostics.contains('DashboardChip')
              ? 'chip'
              : diagnostics.contains('DashboardCardHeader')
              ? 'header'
              : diagnostics.contains('NavigationToolbar')
              ? 'toolbar'
              : null;
          if (match != null && component != null) {
            horizontal
                .putIfAbsent(component, () => <double>{})
                .add(double.parse(match[1]!));
          } else {
            previousError(details);
          }
        };
        try {
          final capture = await _mountDashboard(
            tester,
            brightness: brightness,
            mode: mode,
            scale: 2,
            width: 320,
          );
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          final layout = <Map<String, Object?>>[];
          for (final label in [
            _organization,
            ..._targets.map((target) => target.label),
          ]) {
            final paragraph = tester.renderObject<RenderParagraph>(
              _paragraphs(label),
            );
            final origin = paragraph.localToGlobal(
              Offset.zero,
              ancestor: boundary,
            );
            layout.add({
              'text': label,
              'x': origin.dx,
              'y': origin.dy,
              'width': paragraph.size.width,
              'height': paragraph.size.height,
            });
          }
          final overflow = {
            for (final entry in horizontal.entries)
              entry.key: entry.value.toList()..sort(),
          };
          final frame = await _capture(tester, boundary);
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            final directory = Directory(output)..createSync(recursive: true);
            final prefix =
                '${directory.path}/dashboard-known-horizontal-'
                '${brightness.name}-${mode.name}-320-2x';
            File('$prefix.png').writeAsBytesSync(frame.png);
            File('$prefix.json').writeAsStringSync(
              const JsonEncoder.withIndent('  ').convert({
                'knownHorizontalLimit': overflow.isNotEmpty,
                'layout': layout,
                'overflow': overflow,
              }),
            );
          }
          if (baselineLayout == null) {
            baselineLayout = layout;
            baselineOverflow = overflow;
          } else {
            expect(
              layout,
              baselineLayout,
              reason: 'Glass must preserve None layout',
            );
            expect(
              overflow,
              baselineOverflow,
              reason: 'Glass must preserve None overflow',
            );
          }
          expect(tester.takeException(), isNull);
        } finally {
          try {
            await tester.pumpWidget(const SizedBox.shrink());
          } finally {
            FlutterError.onError = previousError;
          }
        }
      }
    }, variant: TargetPlatformVariant.only(TargetPlatform.android));
    for (final mode in LiquidGlassMode.values) {
      for (final scale in [1.0, 1.3]) {
        for (final width in [430.0, 1100.0]) {
          final name = '${brightness.name}-${mode.name}-$scale-$width';
          testWidgets('dashboard glyph rows survive $name', (tester) async {
            final capture = await _mountDashboard(
              tester,
              brightness: brightness,
              mode: mode,
              scale: scale,
              width: width,
            );
            for (final labels in [
              [_organization],
              [for (final target in _targets) target.label],
            ]) {
              expect(_paragraphs(labels.first), findsOneWidget);
              await tester.ensureVisible(_paragraphs(labels.first));
              await tester.pumpAndSettle();
              final paragraphs = [
                for (final label in labels)
                  tester.renderObject<RenderParagraph>(_paragraphs(label)),
                if (labels.first != _organization)
                  for (var i = 0; i < _targets.length; i++)
                    tester.renderObject<RenderParagraph>(
                      _paragraphs('42 ms').at(i),
                    ),
              ];
              final rows = await _measure(
                tester,
                capture.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary,
                paragraphs,
                '$name-${labels.first == _organization ? 'organization' : 'services'}',
              );
              for (final row in rows) {
                if (row['ellipsis'] != true) {
                  expect(row['inkRows'], isNotEmpty, reason: '$row');
                }
                expect(row['missingRows'], isEmpty, reason: '$row');
              }
              expect(tester.takeException(), isNull);
            }
            await tester.pumpWidget(const SizedBox.shrink());
          }, variant: TargetPlatformVariant.only(TargetPlatform.android));
        }
      }
    }
  }
}

class _Cutout extends CustomClipper<Path> {
  const _Cutout(this.hole);
  final Rect? hole;

  @override
  Path getClip(Size size) {
    final full = Path()..addRect(Offset.zero & size);
    return hole == null
        ? full
        : Path.combine(PathOperation.difference, full, Path()..addRect(hole!));
  }

  @override
  bool shouldReclip(_Cutout oldClipper) => oldClipper.hole != hole;
}
