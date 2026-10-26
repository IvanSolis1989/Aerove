import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
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
}) async {
  tester.view.physicalSize = const Size(1000, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(
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
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
    await tester.pump();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        locale: locale,
        theme: ThemeData(fontFamily: _font, fontFamilyFallback: _fallback),
        child: Builder(
          builder: (context) => MediaQuery(
            data: MediaQuery.of(context).copyWith(textScaler: scaler),
            child: Scaffold(
              body: Align(
                alignment: Alignment.topLeft,
                child: SizedBox(
                  width: _cardOn(device),
                  child: DashboardDensityScope(
                    density: DashboardDensity.mobile,
                    scale: scaler.scale(1),
                    child: const Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [CurrentOutboundCard()],
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
    for (final box in paragraph.getBoxesForSelection(
      TextSelection(baseOffset: 0, extentOffset: text.length),
    )) {
      final rect = MatrixUtils.transformRect(
        paragraph.getTransformTo(null),
        box.toRect(),
      );
      if (rect.overlaps(candidate)) return false;
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

void main() {
  setUpAll(() async {
    final font = FontLoader(_font)..addFont(loadSdkTestFont());
    await font.load();
    _fallback = await loadCjkTestFonts();
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
          _clearsBody(
            tester,
            tester.getRect(find.byType(OutboundTrafficFigures)),
          ),
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
          );
          final layout = tester.renderObject<RenderStatusCardLayout>(
            find.byType(StatusCardLayout),
          );
          final figures = find.byType(OutboundTrafficFigures);
          final stop = AppLocalizations.of(tester.element(figures)).stop;
          Rect? firstRect;
          double? firstHeight;
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
              ends.add(_rectOf(tester, lines.single).right);
            }
            expect(ends[0], closeTo(ends[1], 0.01), reason: why);
            expect(
              _rectOf(tester, paragraphs[1]).top,
              greaterThanOrEqualTo(_rectOf(tester, paragraphs[0]).bottom),
              reason: why,
            );
            final rect = tester.getRect(figures);
            final card = tester.getRect(find.byType(DashboardCard));
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
                  final overlap = Path.combine(
                    PathOperation.intersect,
                    path,
                    Path()..addRect(rect),
                  ).getBounds();
                  expect(
                    overlap.width <= 0.01 || overlap.height <= 0.01,
                    isTrue,
                    reason: '$why indicators touch the painted bubble',
                  );
                }
              }
              for (final MapEntry(:key, :value) in parts.entries) {
                final overlap = rect.intersect(value);
                expect(
                  overlap.width <= 0.01 || overlap.height <= 0.01,
                  isTrue,
                  reason: '$why figures $rect cover $key $value',
                );
              }
            }
            firstRect ??= rect;
            firstHeight ??= card.height;
            expect(rect, firstRect, reason: '$why moves the figures');
            expect(card.height, firstHeight, reason: '$why resizes the card');
            if (scaler == const TextScaler.linear(1) &&
                device >= 360 &&
                (locale.languageCode == 'zh' || locale.languageCode == 'en')) {
              expect(overlaid, isTrue, reason: '$why lays them out under');
              if (device == 360) {
                final shape =
                    tester
                            .widget<StatusCardLayout>(
                              find.byType(StatusCardLayout),
                            )
                            .shape
                        as RoundedSuperellipseBorder;
                final inner = shape.borderRadius
                    .resolve(TextDirection.ltr)
                    .toRSuperellipse(Offset.zero & layout.size)
                    .deflate(shape.side.strokeInset)
                    .scaleRadii();
                final anchor = Offset(
                  inner.right - inner.trRadiusX,
                  inner.top + inner.trRadiusY,
                );
                expect(
                  rect.right - card.left,
                  closeTo(anchor.dx, 0.01),
                  reason: '$why has the wrong R-safe horizontal anchor',
                );
                final footer = find.byWidgetPredicate(
                  (widget) => widget is StatusCurveEdge && widget.floor,
                );
                final cells = _paragraphsIn(tester, footer);
                final ideal = Rect.fromLTRB(
                  rect.left,
                  _rectOf(tester, cells[0]).center.dy -
                      _rectOf(tester, paragraphs[0]).height / 2,
                  rect.right,
                  _rectOf(tester, cells[1]).center.dy +
                      _rectOf(tester, paragraphs[1]).height / 2,
                );
                if (_clearsBody(tester, ideal)) {
                  expect(
                    _rectOf(tester, paragraphs[0]).center.dy,
                    closeTo(_rectOf(tester, cells[0]).center.dy, 0.01),
                    reason: '$why upload does not align with the footer label',
                  );
                  expect(
                    _rectOf(tester, paragraphs[1]).center.dy,
                    closeTo(_rectOf(tester, cells[1]).center.dy, 0.01),
                    reason:
                        '$why download does not align with the footer value',
                  );
                }
                expect(
                  _clearsBody(tester, rect),
                  isTrue,
                  reason: '$why collides after choosing a right-side slot',
                );
              }
              final body = layout.childAfter(layout.firstChild!)!;
              expect(
                card.height,
                closeTo(body.size.height + 36, 0.01),
                reason: '$why grows the card',
              );
            }
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
