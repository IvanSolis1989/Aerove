import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/common/traffic_format.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/views/dashboard/widgets/status_layout.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/cjk_test_fonts.dart';
import '../../helpers/system_test_font.dart';
import '../../helpers/test_app.dart';

const _font = 'FiguresRoboto';
final _capture = GlobalKey();

var _fallback = const <String>[];

/// The card's width on a phone [device] dp wide.
double _cardOn(double device) => device - 2 * dashboardMobileSideOf(device);

class _Ips extends OutboundIpProbe {
  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: '203.0.113.9', countryCode: 'GB'),
    ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

/// Android 14 scales small text by the whole factor and large text by less;
/// this follows its table for 200%, sp to dp.
class _NonlinearScaler extends TextScaler {
  const _NonlinearScaler();

  static const _from = [8.0, 10, 12, 14, 18, 20, 24, 30, 100];
  static const _to = [16.0, 20, 24, 26, 30, 34, 36, 38, 100];

  @override
  double scale(double fontSize) {
    if (fontSize <= _from.first) return fontSize * _to.first / _from.first;
    for (var index = 1; index < _from.length; index++) {
      if (fontSize <= _from[index]) {
        final share =
            (fontSize - _from[index - 1]) / (_from[index] - _from[index - 1]);
        return _to[index - 1] + share * (_to[index] - _to[index - 1]);
      }
    }
    return fontSize;
  }

  @override
  // ignore: deprecated_member_use
  double get textScaleFactor => 2;

  @override
  String toString() => 'android-nonlinear-200%';
}

class _OrganizationNonlinearScaler extends TextScaler {
  const _OrganizationNonlinearScaler();

  @override
  double scale(double size) => size <= 14 ? size * 2 : 28 + (size - 14);

  @override
  // ignore: deprecated_member_use
  double get textScaleFactor => 2;
}

const _terabyte = 1 << 40;

/// Speeds and totals from idle to the widest each unit shows.
final _readings = <(String, Traffic, Traffic)>[
  ('idle', const Traffic(), const Traffic()),
  (
    'screenshot',
    const Traffic(up: 3686, down: 10547),
    const Traffic(up: 131379, down: 393830),
  ),
  (
    'kilobytes',
    const Traffic(up: 1023948, down: 1023948),
    const Traffic(up: 1023948, down: 1023948),
  ),
  (
    'megabytes',
    const Traffic(up: 1048471142, down: 1048471142),
    const Traffic(up: 1073634449818, down: 1073634449818),
  ),
  (
    'terabytes',
    const Traffic(up: 9999 * _terabyte ~/ 10, down: 9999 * _terabyte ~/ 10),
    const Traffic(up: 9999 * _terabyte, down: 9999 * _terabyte),
  ),
];

Future<ProviderContainer> _pump(
  WidgetTester tester, {
  required Locale locale,
  required double device,
  required TextScaler scaler,
  String organization = 'Example Organization',
  String fontFamily = _font,
  bool showTraffic = true,
  bool desktop = false,
  TextDirection direction = TextDirection.ltr,
  ProviderContainer? existingContainer,
}) async {
  tester.view.physicalSize = const Size(1000, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final container =
      existingContainer ??
      ProviderContainer(
        overrides: [
          profilesProvider.overrideWithValue(const [
            Profile(id: 1, autoUpdateDuration: Duration.zero),
          ]),
          isStartProvider.overrideWith((ref) => true),
          suspendProvider.overrideWith((ref) => false),
          outboundIpProbeProvider.overrideWith(_Ips.new),
          ipQualityProvider.overrideWith(
            (_, _) async => IpQuality(
              ip: '203.0.113.9',
              source: IpQualitySource.ipLocate,
              type: IpType.residential,
              organization: organization,
            ),
          ),
        ],
      );
  globalState.container = container;
  if (existingContainer == null) {
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
      await tester.pump();
    });
  }
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        locale: locale,
        homeBuilder: (child) =>
            Directionality(textDirection: direction, child: child),
        theme: ThemeData(fontFamily: fontFamily, fontFamilyFallback: _fallback),
        child: Builder(
          builder: (context) => MediaQuery(
            data: MediaQuery.of(context).copyWith(textScaler: scaler),
            child: Scaffold(
              body: Align(
                alignment: Alignment.topLeft,
                child: SizedBox(
                  width: _cardOn(device),
                  height: desktop ? 208 : null,
                  child: DashboardDensityScope(
                    density: desktop
                        ? DashboardDensity.standard
                        : DashboardDensity.mobile,
                    scale: desktop ? 1 : scaler.scale(1),
                    child: RepaintBoundary(
                      key: _capture,
                      child: desktop
                          ? CurrentOutboundCard(showTraffic: showTraffic)
                          : Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                CurrentOutboundCard(showTraffic: showTraffic),
                              ],
                            ),
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
  await tester.pump();
  return container;
}

Future<void> _show(
  WidgetTester tester,
  ProviderContainer container,
  Traffic speed,
  Traffic total,
) async {
  container.read(trafficsProvider.notifier).addTraffic(speed);
  container.read(totalTrafficProvider.notifier).value = total;
  await tester.pump();
  await tester.pump();
}

List<RenderParagraph> _paragraphsIn(WidgetTester tester, Finder scope) => [
  for (final element
      in find.descendant(of: scope, matching: find.byType(RichText)).evaluate())
    element.renderObject! as RenderParagraph,
];

Rect _rectOf(WidgetTester tester, RenderBox box) =>
    box.localToGlobal(Offset.zero) & box.size;

/// Every line a reading shows is whole: nothing wider than its box, no line
/// beyond its limit, no ellipsis or fade standing in for the rest.
void _expectWhole(WidgetTester tester, Iterable<RenderParagraph> paragraphs) {
  for (final paragraph in paragraphs) {
    final text = paragraph.text.toPlainText();
    expect(paragraph.didExceedMaxLines, isFalse, reason: text);
    expect(
      paragraph.overflow,
      isNot(anyOf(TextOverflow.ellipsis, TextOverflow.fade)),
      reason: text,
    );
    final natural = paragraph.getMaxIntrinsicWidth(double.infinity);
    expect(
      natural,
      lessThanOrEqualTo(paragraph.size.width + 0.5),
      reason: '$text needs $natural, has ${paragraph.size.width}',
    );
  }
}

TextStyle? _styleOf(RenderParagraph paragraph) {
  final span = paragraph.text;
  if (span is TextSpan && span.children != null) {
    for (final child in span.children!) {
      if (child is TextSpan && child.style?.fontSize != null) {
        return child.style;
      }
    }
  }
  return span.style;
}

bool _tabular(TextStyle? style) =>
    style?.fontFeatures?.any((feature) => feature.feature == 'tnum') ?? false;

bool _clearsBody(WidgetTester tester, Rect candidate) {
  final readings = _paragraphsIn(
    tester,
    find.byType(OutboundTrafficFigures),
  ).toSet();
  for (final paragraph in _paragraphsIn(
    tester,
    find.byType(CurrentOutboundCard),
  )) {
    if (readings.contains(paragraph)) continue;
    final text = paragraph.text.toPlainText();
    if (text.runes.any((rune) => rune >= 0x1F1E6)) continue;
    for (final word in RegExp(r'\S+').allMatches(text)) {
      for (final box in paragraph.getBoxesForSelection(
        TextSelection(baseOffset: word.start, extentOffset: word.end),
      )) {
        final rect = MatrixUtils.transformRect(
          paragraph.getTransformTo(null),
          box.toRect(),
        );
        if (rect.overlaps(candidate)) return false;
      }
    }
  }
  for (final element
      in find
          .descendant(
            of: find.byType(CurrentOutboundCard),
            matching: find.byType(CustomPaint),
          )
          .evaluate()) {
    final box = element.renderObject! as RenderCustomPaint;
    if (box.size.width > 60 && box.painter is OutboundDropletPainter) {
      final path = (box.painter! as OutboundDropletPainter)
          .pathOf(box.size)
          .transform(box.getTransformTo(null).storage);
      if (!Path.combine(
        PathOperation.intersect,
        path,
        Path()..addRect(candidate),
      ).getBounds().isEmpty) {
        return false;
      }
    }
  }
  return true;
}

List<Rect> _figureParts(WidgetTester tester) {
  final result = <Rect>[];
  final root = tester.renderObject(find.byType(OutboundTrafficFigures));
  void visit(RenderObject node) {
    if (node is RenderParagraph) {
      final text = node.text.toPlainText();
      for (final word in RegExp(r'\S+').allMatches(text)) {
        for (final box in node.getBoxesForSelection(
          TextSelection(baseOffset: word.start, extentOffset: word.end),
        )) {
          result.add(
            MatrixUtils.transformRect(node.getTransformTo(null), box.toRect()),
          );
        }
      }
    } else if (node is RenderCustomPaint && node.painter is GlyphPainter) {
      result.add(
        MatrixUtils.transformRect(
          node.getTransformTo(null),
          (node.painter! as GlyphPainter).paintedBounds(
            node.size,
            pixelRatio: tester.view.devicePixelRatio,
          ),
        ),
      );
    }
    node.visitChildren(visit);
  }

  visit(root);
  return result;
}

List<(String, Rect)> _mainRects(RenderObject root) {
  final result = <(String, Rect)>[];
  void visit(RenderObject node) {
    if (node is RenderParagraph) {
      result.add((
        node.text.toPlainText(),
        node.localToGlobal(Offset.zero) & node.size,
      ));
    }
    node.visitChildren(visit);
  }

  visit(root);
  return result;
}

InlineSpan _whiteSpan(InlineSpan span) {
  final text = span as TextSpan;
  return TextSpan(
    text: text.text,
    style: text.style?.copyWith(color: Colors.white, shadows: const []),
    children: text.children?.map(_whiteSpan).toList(),
  );
}

Future<int> _overlapPixels(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
  RenderObject body,
  RenderObject figures,
) async {
  ui.Image mask(RenderObject root) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder)..scale(3);
    void visit(RenderObject node) {
      if (node is RenderParagraph) {
        final painter =
            TextPainter(
              text: _whiteSpan(node.text),
              textAlign: node.textAlign,
              textDirection: node.textDirection,
              textScaler: node.textScaler,
              maxLines: node.maxLines,
              ellipsis: node.overflow == TextOverflow.ellipsis
                  ? '\u2026'
                  : null,
              locale: node.locale,
              strutStyle: node.strutStyle,
              textWidthBasis: node.textWidthBasis,
              textHeightBehavior: node.textHeightBehavior,
            )..layout(
              minWidth: node.constraints.minWidth,
              maxWidth: node.constraints.maxWidth,
            );
        canvas.save();
        canvas.transform(node.getTransformTo(boundary).storage);
        painter.paint(canvas, Offset.zero);
        canvas.restore();
        painter.dispose();
      } else if (node is RenderCustomPaint) {
        final paint = node.painter;
        if (paint is GlyphPainter || paint is OutboundDropletPainter) {
          canvas.save();
          canvas.transform(node.getTransformTo(boundary).storage);
          if (paint is GlyphPainter) {
            GlyphPainter(
              glyph: paint.glyph,
              fill: paint.fill,
              color: Colors.white,
            ).paint(canvas, node.size);
          } else if (paint is OutboundDropletPainter) {
            canvas.drawPath(
              paint.pathOf(node.size),
              Paint()..color = Colors.white,
            );
          }
          canvas.restore();
        }
      }
      node.visitChildren(visit);
    }

    visit(root);
    final picture = recorder.endRecording();
    final image = picture.toImageSync(
      (boundary.size.width * 3).ceil(),
      (boundary.size.height * 3).ceil(),
    );
    picture.dispose();
    return image;
  }

  final main = mask(body), speed = mask(figures);
  try {
    final bytes = (await tester.runAsync(
      () async => [
        (await main.toByteData(format: ui.ImageByteFormat.rawRgba))!,
        (await speed.toByteData(format: ui.ImageByteFormat.rawRgba))!,
      ],
    ))!;
    var overlap = 0;
    for (var pixel = 3; pixel < bytes[0].lengthInBytes; pixel += 4) {
      if (bytes[0].getUint8(pixel) >= 180 && bytes[1].getUint8(pixel) >= 180) {
        overlap++;
      }
    }
    return overlap;
  } finally {
    main.dispose();
    speed.dispose();
  }
}

double _rSafeRight(WidgetTester tester) {
  final layout = tester.renderObject<RenderStatusCardLayout>(
    find.byType(StatusCardLayout),
  );
  final widget = tester.widget<StatusCardLayout>(find.byType(StatusCardLayout));
  final shape = widget.shape as RoundedSuperellipseBorder;
  final direction = Directionality.of(
    tester.element(find.byType(StatusCardLayout)),
  );
  final inner = shape.borderRadius
      .resolve(direction)
      .toRSuperellipse(Offset.zero & layout.size)
      .deflate(shape.side.strokeInset)
      .scaleRadii();
  return tester.getRect(find.byType(DashboardCard)).left +
      inner.right -
      inner.trRadiusX;
}

void _expectNaturalPlacement(
  WidgetTester tester, {
  required Rect baselineBody,
  required List<(String, Rect)> baselineTexts,
  required Size baselineCard,
  required Rect baselineButton,
  required String why,
}) {
  final layout = tester.renderObject<RenderStatusCardLayout>(
    find.byType(StatusCardLayout),
  );
  final body = layout.childAfter(layout.firstChild!)!;
  final figures = tester.getRect(find.byType(OutboundTrafficFigures));
  final card = tester.getSize(find.byType(DashboardCard));
  final stop = AppLocalizations.of(
    tester.element(find.byType(CurrentOutboundCard)),
  ).stop;
  expect(
    body.localToGlobal(Offset.zero) & body.size,
    baselineBody,
    reason: why,
  );
  expect(_mainRects(body), baselineTexts, reason: why);
  expect(tester.getRect(find.byTooltip(stop)), baselineButton, reason: why);
  expect(card.width, baselineCard.width, reason: why);
  final right = _rSafeRight(tester);
  expect(
    figures.right,
    lessThanOrEqualTo(right + 0.01),
    reason: '$why exceeds physical R',
  );
  final parts = _figureParts(tester);
  expect(layout.footerSlotAvailable, isNotNull, reason: why);
  if (layout.footerSlotAvailable == true) {
    expect(layout.overlaid, isTrue, reason: why);
    expect(card, baselineCard, reason: why);
    final footer = find.byWidgetPredicate(
      (widget) => widget is StatusCurveEdge && widget.floor,
    );
    final cells = _paragraphsIn(tester, footer);
    final readings = _paragraphsIn(tester, find.byType(OutboundTrafficFigures));
    for (var line = 0; line < 2; line++) {
      expect(
        _rectOf(tester, readings[line]).center.dy,
        closeTo(_rectOf(tester, cells[line]).center.dy, 0.01),
        reason: why,
      );
    }
    final delta = Offset(right - figures.right, 0);
    if (parts.every((part) => _clearsBody(tester, part.shift(delta)))) {
      expect(
        figures.right,
        closeTo(right, 0.01),
        reason: '$why leaves an available R anchor',
      );
    }
  } else {
    expect(
      layout.overlaid,
      isFalse,
      reason: '$why must append only after no slot',
    );
    expect(figures.top, closeTo(baselineBody.bottom + 8, 0.01), reason: why);
    expect(
      card.height,
      closeTo(baselineCard.height + 8 + figures.height, 0.01),
      reason: why,
    );
    expect(
      figures.right,
      closeTo(right, 0.01),
      reason: '$why append must remain physically right',
    );
  }
  expect(
    parts.every((part) => _clearsBody(tester, part)),
    isTrue,
    reason: '$why painted component collides',
  );
}

void main() {
  setUpAll(() async {
    final font = FontLoader(_font)..addFont(loadSdkTestFont());
    await font.load();
    _fallback = await loadCjkTestFonts();
  });

  for (final scale in [1.0, 1.15]) {
    for (final (reading, speed, total) in _readings) {
      testWidgets('CI52 $reading painted parts and baseline zh-CN 360 $scale', (
        tester,
      ) async {
        final baselineContainer = await _pump(
          tester,
          locale: const Locale('zh', 'CN'),
          device: 360,
          scaler: TextScaler.linear(scale),
          showTraffic: false,
        );
        final base = tester.renderObject<RenderStatusCardLayout>(
          find.byType(StatusCardLayout),
        );
        final baseBody = base.childAfter(base.firstChild!)!;
        final bodyRect = baseBody.localToGlobal(Offset.zero) & baseBody.size;
        final textRects = _mainRects(baseBody);
        final cardSize = tester.getSize(find.byType(DashboardCard));
        final stop = AppLocalizations.of(
          tester.element(find.byType(CurrentOutboundCard)),
        ).stop;
        final buttonRect = tester.getRect(find.byTooltip(stop));
        final container = await _pump(
          tester,
          locale: const Locale('zh', 'CN'),
          device: 360,
          scaler: TextScaler.linear(scale),
          existingContainer: baselineContainer,
        );
        await _show(tester, container, speed, total);
        final layout = tester.renderObject<RenderStatusCardLayout>(
          find.byType(StatusCardLayout),
        );
        final body = layout.childAfter(layout.firstChild!)!;
        final boundary =
            _capture.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final pixels = await _overlapPixels(
          tester,
          boundary,
          body,
          tester.renderObject(find.byType(OutboundTrafficFigures)),
        );
        final readings = _paragraphsIn(
          tester,
          find.byType(OutboundTrafficFigures),
        );
        final footer = find.byWidgetPredicate(
          (widget) => widget is StatusCurveEdge && widget.floor,
        );
        final cells = _paragraphsIn(tester, footer);
        final facts = <String, Object?>{
          'reading': reading,
          'scale': scale,
          'device': 360,
          'cardWidth': _cardOn(360),
          'slot': layout.footerSlotAvailable,
          'overlaid': layout.overlaid,
          'baselineBody': bodyRect.toString(),
          'body': (body.localToGlobal(Offset.zero) & body.size).toString(),
          'baselineCard': cardSize.toString(),
          'card': tester.getSize(find.byType(DashboardCard)).toString(),
          'baselineButton': buttonRect.toString(),
          'button': tester.getRect(find.byTooltip(stop)).toString(),
          'figures': tester
              .getRect(find.byType(OutboundTrafficFigures))
              .toString(),
          'parts': _figureParts(tester).map((part) => part.toString()).toList(),
          'footerCenters': [
            for (var i = 0; i < 2; i++) _rectOf(tester, cells[i]).center.dy,
          ],
          'speedCenters': [
            for (final row in readings) _rectOf(tester, row).center.dy,
          ],
          'collisionPixels': pixels,
        };
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          final directory = Directory(output)..createSync(recursive: true);
          File('${directory.path}/$reading-$scale.json').writeAsStringSync(
            const JsonEncoder.withIndent('  ').convert(facts),
          );
          await tester.runAsync(() async {
            final image = await boundary.toImage(pixelRatio: 3);
            final bytes = await image.toByteData(
              format: ui.ImageByteFormat.png,
            );
            File(
              '${directory.path}/$reading-$scale.png',
            ).writeAsBytesSync(bytes!.buffer.asUint8List());
            image.dispose();
          });
        }
        expect(
          [for (final value in readings) value.text.toPlainText()],
          [trafficRate(speed.up), trafficRate(speed.down)],
        );
        _expectNaturalPlacement(
          tester,
          baselineBody: bodyRect,
          baselineTexts: textRects,
          baselineCard: cardSize,
          baselineButton: buttonRect,
          why: '$facts',
        );
        expect(pixels, 0, reason: '$facts');
        expect(tester.takeException(), isNull);
      });
    }
  }

  for (final direction in [TextDirection.ltr, TextDirection.rtl]) {
    testWidgets('natural append recovers its footer slot ${direction.name}', (
      tester,
    ) async {
      final container = await _pump(
        tester,
        locale: const Locale('zh', 'CN'),
        device: 360,
        scaler: const TextScaler.linear(1.15),
        showTraffic: false,
        direction: direction,
      );
      final base = tester.renderObject<RenderStatusCardLayout>(
        find.byType(StatusCardLayout),
      );
      final baseBody = base.childAfter(base.firstChild!)!;
      final baseRect = baseBody.localToGlobal(Offset.zero) & baseBody.size;
      final baseTexts = _mainRects(baseBody);
      final baseCard = tester.getSize(find.byType(DashboardCard));
      final stop = AppLocalizations.of(
        tester.element(find.byType(CurrentOutboundCard)),
      ).stop;
      final baseButton = tester.getRect(find.byTooltip(stop));
      await _pump(
        tester,
        locale: const Locale('zh', 'CN'),
        device: 360,
        scaler: const TextScaler.linear(1.15),
        existingContainer: container,
        direction: direction,
      );
      final high = _readings.firstWhere((item) => item.$1 == 'megabytes');
      for (final (step, speed) in [
        ('high', high.$2),
        ('restored', const Traffic()),
        ('high-again', high.$2),
      ]) {
        await _show(tester, container, speed, const Traffic());
        final layout = tester.renderObject<RenderStatusCardLayout>(
          find.byType(StatusCardLayout),
        );
        final body = layout.childAfter(layout.firstChild!)!;
        _expectNaturalPlacement(
          tester,
          baselineBody: baseRect,
          baselineTexts: baseTexts,
          baselineCard: baseCard,
          baselineButton: baseButton,
          why: '$direction $step',
        );
        if (direction == TextDirection.ltr && step == 'restored') {
          expect(
            layout.overlaid,
            isTrue,
            reason: '$step must follow current real space',
          );
        }
        final readings = _paragraphsIn(
          tester,
          find.byType(OutboundTrafficFigures),
        );
        expect(
          [for (final row in readings) row.text.toPlainText()],
          [trafficRate(speed.up), trafficRate(speed.down)],
        );
        final boundary =
            _capture.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final overlap = await _overlapPixels(
          tester,
          boundary,
          body,
          tester.renderObject(find.byType(OutboundTrafficFigures)),
        );
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          final directory = Directory(output)..createSync(recursive: true);
          final facts = {
            'step': step,
            'direction': direction.name,
            'slot': layout.footerSlotAvailable,
            'overlaid': layout.overlaid,
            'body': (body.localToGlobal(Offset.zero) & body.size).toString(),
            'baselineBody': baseRect.toString(),
            'card': tester.getSize(find.byType(DashboardCard)).toString(),
            'baselineCard': baseCard.toString(),
            'button': tester.getRect(find.byTooltip(stop)).toString(),
            'baselineButton': baseButton.toString(),
            'figure': tester
                .getRect(find.byType(OutboundTrafficFigures))
                .toString(),
            'physicalR': _rSafeRight(tester),
            'visible': [for (final row in readings) row.text.toPlainText()],
            'collisionPixels': overlap,
          };
          File(
            '${directory.path}/transition-${direction.name}-$step.json',
          ).writeAsStringSync(
            const JsonEncoder.withIndent('  ').convert(facts),
          );
          if (step != 'high-again') {
            await tester.runAsync(() async {
              final image = await boundary.toImage(pixelRatio: 3);
              final bytes = await image.toByteData(
                format: ui.ImageByteFormat.png,
              );
              File(
                '${directory.path}/transition-${direction.name}-$step.png',
              ).writeAsBytesSync(bytes!.buffer.asUint8List());
              image.dispose();
            });
          }
        }
        expect(overlap, 0, reason: '$direction $step');
      }
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('nonlinear organization shrinks before ellipsis', (tester) async {
    await _pump(
      tester,
      locale: const Locale('en'),
      device: 363.2,
      scaler: const _OrganizationNonlinearScaler(),
      organization: 'LEASEWEB',
      fontFamily: 'Ahem',
      showTraffic: false,
    );
    final paragraph = _paragraphsIn(
      tester,
      find.byType(CurrentOutboundCard),
    ).singleWhere((paragraph) => paragraph.text.toPlainText() == 'LEASEWEB');
    expect(paragraph.constraints.maxWidth, closeTo(197.2, 0.01));
    expect(paragraph.didExceedMaxLines, isFalse);
    expect(_styleOf(paragraph)!.fontSize, 12);
    expect(tester.takeException(), isNull);
  });

  testWidgets('nonlinear organization line fits its fixed box', (tester) async {
    const organization = 'PT. Telekomunikasi Selular (Telkomsel) Indonesia';
    await _pump(
      tester,
      locale: const Locale('en'),
      device: 360,
      scaler: const _OrganizationNonlinearScaler(),
      organization: organization,
      fontFamily: 'Ahem',
      showTraffic: false,
    );
    final paragraph = _paragraphsIn(
      tester,
      find.byType(CurrentOutboundCard),
    ).singleWhere((paragraph) => paragraph.text.toPlainText() == organization);
    final painter = TextPainter(
      text: paragraph.text,
      textDirection: paragraph.textDirection,
      textScaler: paragraph.textScaler,
      maxLines: 1,
      ellipsis: '…',
    )..layout(maxWidth: paragraph.size.width);
    expect(painter.height, closeTo(39, 0.01));
    expect(painter.height, lessThanOrEqualTo(paragraph.size.height + 0.01));
    painter.dispose();
    expect(tester.takeException(), isNull);
  });
  for (final (device, scale, desktop) in const [
    (360.0, 1.0, false),
    (360.0, 1.15, false),
    (412.0, 1.0, false),
    (640.0, 0.9, true),
  ]) {
    testWidgets('organization stays single line $device $scale $desktop', (
      tester,
    ) async {
      const organizations = [
        'Example',
        'PT. Telekomunikasi Selular (Telkomsel) Indonesia',
        'LEASEWEB SINGAPORE PTE. LTD.',
      ];
      Size? baselineCard;
      List<double>? baselineCenters;
      for (final organization in organizations) {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        final container = await _pump(
          tester,
          locale: const Locale('en'),
          device: device,
          scaler: TextScaler.linear(scale),
          desktop: desktop,
          organization: organization,
        );
        await _show(
          tester,
          container,
          const Traffic(up: 3686, down: 10547),
          const Traffic(),
        );
        final paragraph =
            _paragraphsIn(tester, find.byType(CurrentOutboundCard)).singleWhere(
              (paragraph) => paragraph.text.toPlainText() == organization,
            );
        expect(paragraph.maxLines, 1);
        expect(paragraph.softWrap, isFalse);
        expect(paragraph.overflow, TextOverflow.ellipsis);
        final font = _styleOf(paragraph)!.fontSize!;
        expect(font, greaterThanOrEqualTo(11));
        expect(font, lessThanOrEqualTo(15));
        if (paragraph.didExceedMaxLines) expect(font, 11);
        final card = tester.getSize(find.byType(DashboardCard));
        final centers = _paragraphsIn(
          tester,
          find.byType(OutboundTrafficFigures),
        ).map((paragraph) => _rectOf(tester, paragraph).center.dy).toList();
        baselineCard ??= card;
        baselineCenters ??= centers;
        expect(card, baselineCard, reason: organization);
        expect(centers, baselineCenters, reason: organization);
        expect(tester.takeException(), isNull);
      }
    });
  }

  testWidgets('status background pixels stay inside continuous card corners', (
    tester,
  ) async {
    final key = GlobalKey();
    await tester.pumpWidget(
      TestApp(
        child: Center(
          child: RepaintBoundary(
            key: key,
            child: SizedBox(
              width: 160,
              child: StatusCardLayout(
                padding: EdgeInsets.zero,
                shape: AppShape.xl,
                baselineRatio: 1,
                backdrop: const ColoredBox(color: Colors.red),
                body: const SizedBox(width: 160, height: 120),
                figures: const SizedBox.shrink(),
              ),
            ),
          ),
        ),
      ),
    );
    final boundary =
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final image = await boundary.toImage();
    final bytes = (await tester.runAsync(
      () => image.toByteData(format: ui.ImageByteFormat.rawRgba),
    ))!;
    final path = AppShape.xl.getOuterPath(const Rect.fromLTWH(0, 0, 160, 120));
    final recorder = ui.PictureRecorder();
    Canvas(recorder).drawPath(path, Paint()..color = Colors.white);
    final picture = recorder.endRecording();
    final mask = picture.toImageSync(160, 120);
    final maskBytes = (await tester.runAsync(
      () => mask.toByteData(format: ui.ImageByteFormat.rawRgba),
    ))!;
    var outside = 0;
    for (var pixel = 3; pixel < bytes.lengthInBytes; pixel += 4) {
      if (maskBytes.getUint8(pixel) == 0 && bytes.getUint8(pixel) > 8) {
        outside++;
      }
    }
    mask.dispose();
    picture.dispose();
    expect(outside, 0);
    expect(bytes.getUint8((119 * 160 + 80) * 4), 244);
    expect(bytes.getUint8((119 * 160 + 80) * 4 + 3), 255);
    image.dispose();
    expect(tester.takeException(), isNull);
  });

  const locales = [
    Locale('zh', 'CN'),
    Locale('en'),
    Locale('ja'),
    Locale('ko'),
    Locale('es'),
    Locale('fr'),
    Locale('ru'),
  ];
  const widths = [320.0, 360.0, 393.0, 412.0];
  const scalers = <TextScaler>[
    TextScaler.linear(1),
    TextScaler.linear(1.15),
    TextScaler.linear(1.3),
    TextScaler.linear(1.5),
    TextScaler.linear(2),
    _NonlinearScaler(),
  ];

  for (final locale in const [Locale('zh', 'CN'), Locale('en')]) {
    testWidgets(
      'bottom readings align to both footer centers ${locale.toLanguageTag()}',
      (tester) async {
        final container = await _pump(
          tester,
          locale: locale,
          device: 360,
          scaler: TextScaler.noScaling,
          organization: 'Example',
        );
        await _show(
          tester,
          container,
          const Traffic(up: 155100, down: 290200),
          const Traffic(),
        );
        final readings = _paragraphsIn(
          tester,
          find.byType(OutboundTrafficFigures),
        );
        final footer = find.byWidgetPredicate(
          (widget) => widget is StatusCurveEdge && widget.floor,
        );
        final cells = _paragraphsIn(tester, footer);
        expect(
          _rectOf(tester, readings[0]).center.dy,
          closeTo(_rectOf(tester, cells[0]).center.dy, 0.01),
        );
        expect(
          _rectOf(tester, readings[1]).center.dy,
          closeTo(_rectOf(tester, cells[1]).center.dy, 0.01),
        );
        expect(
          _figureParts(tester).every((part) => _clearsBody(tester, part)),
          isTrue,
        );
        final layout = tester.renderObject<RenderStatusCardLayout>(
          find.byType(StatusCardLayout),
        );
        expect(layout.overlaid, isTrue);
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final locale in locales) {
    for (final device in widths) {
      for (final scaler in scalers) {
        final name = scaler is _NonlinearScaler
            ? scaler.toString()
            : '${scaler.scale(1)}';
        testWidgets('the status card figures stay whole and aligned '
            '${locale.toLanguageTag()} on a $device dp device at $name', (
          tester,
        ) async {
          final container = await _pump(
            tester,
            locale: locale,
            device: device,
            scaler: scaler,
            showTraffic: false,
          );
          final baseLayout = tester.renderObject<RenderStatusCardLayout>(
            find.byType(StatusCardLayout),
          );
          final baseBody = baseLayout.childAfter(baseLayout.firstChild!)!;
          final baseRect = baseBody.localToGlobal(Offset.zero) & baseBody.size;
          final baseTexts = _mainRects(baseBody);
          final baseCard = tester.getSize(find.byType(DashboardCard));
          final baseStop = AppLocalizations.of(
            tester.element(find.byType(CurrentOutboundCard)),
          ).stop;
          final baseButton = tester.getRect(find.byTooltip(baseStop));
          await _pump(
            tester,
            locale: locale,
            device: device,
            scaler: scaler,
            existingContainer: container,
          );
          final layout = tester.renderObject<RenderStatusCardLayout>(
            find.byType(StatusCardLayout),
          );
          final figures = find.byType(OutboundTrafficFigures);
          final stop = AppLocalizations.of(tester.element(figures)).stop;
          for (final (reading, speed, total) in _readings) {
            await _show(tester, container, speed, total);
            final why =
                '$reading at ${locale.toLanguageTag()} $device dp $name';
            final paragraphs = _paragraphsIn(tester, figures);
            expect(paragraphs, hasLength(2), reason: why);
            _expectWhole(tester, paragraphs);
            final rows = find.descendant(
              of: figures,
              matching: find.byType(DashboardStop),
            );
            expect(rows, findsNWidgets(2), reason: why);
            final ends = <double>[];
            for (var index = 0; index < 2; index++) {
              final lines = _paragraphsIn(tester, rows.at(index));
              expect(lines, hasLength(1), reason: why);
              final icon = tester.widget<GlyphIcon>(
                find.descendant(
                  of: rows.at(index),
                  matching: find.byType(GlyphIcon),
                ),
              );
              expect(
                icon.glyph,
                index == 0 ? AppGlyphs.arrowUp : AppGlyphs.arrowDown,
                reason: why,
              );
              final figure = _styleOf(lines.single)!;
              expect(figure.fontSize, 10, reason: why);
              expect(figure.fontWeight, FontWeight.w400, reason: why);
              expect(_tabular(figure), isTrue, reason: why);
              expect(
                lines.single.textScaler.scale(10),
                scaler.scale(10),
                reason: why,
              );
              final arrow = tester.getRect(
                find.descendant(
                  of: rows.at(index),
                  matching: find.byType(GlyphIcon),
                ),
              );
              expect(
                _rectOf(tester, lines.single).left - arrow.right,
                closeTo(3, 0.01),
                reason: '$why separates the arrow from its value',
              );
              ends.add(_rectOf(tester, lines.single).right);
            }
            expect(ends[0], closeTo(ends[1], 0.01), reason: why);
            expect(
              _rectOf(tester, paragraphs[1]).top,
              greaterThanOrEqualTo(_rectOf(tester, paragraphs[0]).bottom),
              reason: why,
            );
            final overlaid = layout.overlaid == true;
            if (overlaid) {
              final parts = <String, Rect>{
                'flag': tester.getRect(find.byType(OutboundBubble).first),
                'ip': tester.getRect(find.text('203.0.113.9')),
                for (final (index, chip)
                    in find.byType(DashboardChip).evaluate().indexed)
                  'chip $index': tester.getRect(
                    find.byElementPredicate((element) => element == chip),
                  ),
              };
              for (final element
                  in find
                      .descendant(
                        of: find.byTooltip(stop),
                        matching: find.byType(CustomPaint),
                      )
                      .evaluate()) {
                final paint = element.renderObject! as RenderCustomPaint;
                if (paint.painter case final OutboundDropletPainter bubble) {
                  final path = bubble
                      .pathOf(paint.size)
                      .transform(paint.getTransformTo(null).storage);
                  for (final part in _figureParts(tester)) {
                    final overlap = Path.combine(
                      PathOperation.intersect,
                      path,
                      Path()..addRect(part),
                    ).getBounds();
                    expect(
                      overlap.width <= 0.01 || overlap.height <= 0.01,
                      isTrue,
                      reason: '$why component $part touches the painted bubble',
                    );
                  }
                }
              }
              for (final MapEntry(:key, :value) in parts.entries) {
                for (final part in _figureParts(tester)) {
                  final overlap = part.intersect(value);
                  expect(
                    overlap.width <= 0.01 || overlap.height <= 0.01,
                    isTrue,
                    reason: '$why component $part covers $key $value',
                  );
                }
              }
            }
            _expectNaturalPlacement(
              tester,
              baselineBody: baseRect,
              baselineTexts: baseTexts,
              baselineCard: baseCard,
              baselineButton: baseButton,
              why: why,
            );
          }
          expect(tester.takeException(), isNull);
        });
      }
    }
  }

  testWidgets('the largest speed a counter holds is shown whole', (
    tester,
  ) async {
    final container = await _pump(
      tester,
      locale: const Locale('en'),
      device: 360,
      scaler: TextScaler.noScaling,
    );
    await _show(
      tester,
      container,
      const Traffic(up: 9223372036854775807, down: 9223372036854775807),
      const Traffic(up: 9223372036854775807, down: 9223372036854775807),
    );
    _expectWhole(
      tester,
      _paragraphsIn(tester, find.byType(OutboundTrafficFigures)),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('two named speed rows ignore cumulative-counter updates', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    try {
      final container = await _pump(
        tester,
        locale: const Locale('en'),
        device: 360,
        scaler: TextScaler.noScaling,
      );
      final figures = find.byType(OutboundTrafficFigures);
      expect(find.bySemanticsLabel('Upload speed: 0 B/s'), findsOneWidget);
      expect(find.bySemanticsLabel('Download speed: 0 B/s'), findsOneWidget);
      final shown = tester.widget(
        find.descendant(of: figures, matching: find.byType(TrafficReadings)),
      );
      container.read(totalTrafficProvider.notifier).value = const Traffic(
        up: 1 << 40,
        down: 1 << 50,
      );
      await tester.pump();
      await tester.pump();
      expect(
        tester.widget(
          find.descendant(of: figures, matching: find.byType(TrafficReadings)),
        ),
        same(shown),
      );
      expect(_paragraphsIn(tester, figures), hasLength(2));
      expect(
        find.bySemanticsLabel(RegExp('Total|cumulative', caseSensitive: false)),
        findsNothing,
      );
    } finally {
      semantics.dispose();
    }
  });

  // The IP type and quality chips move to a line of their own rather than
  // be clipped away, and neither they nor the address are cut short.
  for (final locale in const [Locale('zh', 'CN'), Locale('en'), Locale('ru')]) {
    for (final device in const [320.0, 360.0]) {
      testWidgets('the exit address and its chips stay whole '
          '${locale.toLanguageTag()} on a $device dp device', (tester) async {
        for (final scale in const [1.0, 1.3, 2.0]) {
          final why = '${locale.toLanguageTag()} $device dp x$scale';
          await _pump(
            tester,
            locale: locale,
            device: device,
            scaler: TextScaler.linear(scale),
          );
          final card = tester.getRect(find.byType(DashboardCard));
          final chips = find.byType(DashboardChip);
          expect(chips, findsNWidgets(3), reason: why);
          final labels = <String>[];
          for (final paragraph in [
            for (var index = 1; index < 3; index++)
              ..._paragraphsIn(tester, chips.at(index)),
          ]) {
            final text = paragraph.text.toPlainText();
            labels.add(text);
            expect(paragraph.didExceedMaxLines, isFalse, reason: '$why $text');
            expect(paragraph.overflow, isNot(TextOverflow.ellipsis));
            final shown = MatrixUtils.transformRect(
              paragraph.getTransformTo(null),
              Offset.zero & paragraph.size,
            );
            expect(card.intersect(shown), shown, reason: '$why $text');
            for (
              RenderObject? node = paragraph.parent;
              node != null && node is! RenderStatusCardLayout;
              node = node.parent
            ) {
              if (node is RenderClipRect && node.clipBehavior != Clip.none) {
                final clip = MatrixUtils.transformRect(
                  node.getTransformTo(null),
                  Offset.zero & node.size,
                );
                expect(
                  clip.intersect(shown),
                  shown,
                  reason: '$why $text is clipped',
                );
              }
            }
          }
          expect(labels, hasLength(2), reason: why);
          final address = [
            for (final element in find.byType(RichText).evaluate())
              if ((element.renderObject! as RenderParagraph).text
                  .toPlainText()
                  .replaceAll('\u200B', '')
                  .contains('203.0.113.9'))
                element.renderObject! as RenderParagraph,
          ];
          expect(address, hasLength(1), reason: why);
          expect(address.single.didExceedMaxLines, isFalse, reason: why);
          expect(
            address.single.overflow,
            isNot(TextOverflow.ellipsis),
            reason: why,
          );
          expect(tester.takeException(), isNull, reason: why);
          await tester.pumpWidget(const SizedBox.shrink());
        }
      });
    }
  }
}
