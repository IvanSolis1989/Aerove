import 'dart:ui' as ui;

import 'package:fl_clash/common/service_probe.dart' show routedOutbound;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/cjk_test_fonts.dart';
import '../../helpers/system_test_font.dart';
import '../../helpers/test_app.dart';

/// The default test font draws every glyph a full em wide; the cards need
/// real proportions to lay out as on a phone.
const _font = 'BackdropRoboto';

var _fallback = const <String>[];

/// The card's width on a phone [device] dp wide.
double _cardOn(double device) => device - 2 * dashboardMobileSideOf(device);

class _Ips extends OutboundIpProbe {
  _Ips(this.ip);

  final String ip;

  @override
  RoutedProbeState<String, IpInfo> build() => RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: ip, countryCode: 'GB'),
    ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

/// Thirty samples whose download peaks at a quarter of the scale.
final _samples = [
  for (var index = 0; index < 30; index++)
    const Traffic(up: 8 * 1024, down: 16 * 1024),
];

/// Peaks at the top of the scale up to the newest sample, which reads zero,
/// so the curves run high under the figures while they show 0 B/s.
final _peaks = [
  for (var index = 0; index < 30; index++)
    index == 29
        ? const Traffic()
        : Traffic(
            up: (index.isEven ? 700 : 400) << 20,
            down: (index.isEven ? 1000 : 800) << 20,
          ),
];

final _quiet = [for (var index = 0; index < 30; index++) const Traffic()];

final _capture = GlobalKey();

Future<ProviderContainer> _pump(
  WidgetTester tester,
  Widget child, {
  bool running = false,
  List<Traffic> samples = const [],
  double device = 392,
  double? width,
  double scale = 1,
  Locale locale = const Locale('en'),
  DashboardDensity density = DashboardDensity.mobile,
  double? height,
  String ip = '203.0.113.9',
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
      isStartProvider.overrideWith((ref) => running),
      suspendProvider.overrideWith((ref) => false),
      outboundIpProbeProvider.overrideWith(() => _Ips(ip)),
      ipQualityProvider.overrideWith(
        (_, _) async => IpQuality(
          ip: ip,
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
  for (final sample in samples) {
    container.read(trafficsProvider.notifier).addTraffic(sample);
  }
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        locale: locale,
        theme: ThemeData(fontFamily: _font, fontFamilyFallback: _fallback),
        child: Scaffold(
          body: Align(
            alignment: Alignment.topLeft,
            child: SizedBox(
              width: width ?? _cardOn(device),
              height: height,
              child: MediaQuery.withClampedTextScaling(
                minScaleFactor: scale,
                maxScaleFactor: scale,
                child: DashboardDensityScope(
                  density: density,
                  scale: density.isDesktop ? 1 : scale,
                  child: RepaintBoundary(
                    key: _capture,
                    child: height == null
                        ? Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [child],
                          )
                        : child,
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
  await tester.pump();
  return container;
}

/// The captured card as rows of colours, with its own size.
Future<(List<List<Color>>, Size)> _pixels(WidgetTester tester) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_capture),
  );
  final size = boundary.size;
  final bytes = (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
  final width = size.width.round();
  return (
    [
      for (var y = 0; y < size.height.round(); y++)
        [
          for (var x = 0; x < width; x++)
            Color.fromARGB(
              bytes[(y * width + x) * 4 + 3],
              bytes[(y * width + x) * 4],
              bytes[(y * width + x) * 4 + 1],
              bytes[(y * width + x) * 4 + 2],
            ),
        ],
    ],
    size,
  );
}

/// The rows of the start padding where something besides the card is drawn,
/// between the rounded corners.
List<int> _drawnRows(List<List<Color>> rows, Size size) {
  final x = size.width ~/ 2;
  final card = rows[size.height.round() - 8][x];
  return [
    for (var y = size.height.round() - 6; y < size.height.round(); y++)
      if (rows[y][x] != card) y,
  ];
}

/// The vertical extent of every line of text in the card, by its text.
Map<String, (double, double)> _texts(WidgetTester tester) {
  final origin = tester.getTopLeft(find.byKey(_capture));
  return {
    for (final element
        in find
            .descendant(
              of: find.byType(CurrentOutboundCard),
              matching: find.byType(RichText),
            )
            .evaluate())
      if (element.renderObject case final RenderParagraph paragraph
          when paragraph.text.toPlainText().trim().isNotEmpty)
        '${paragraph.text.toPlainText()} @${paragraph.hashCode}': (
          paragraph.localToGlobal(Offset.zero).dy - origin.dy,
          paragraph.localToGlobal(Offset(0, paragraph.size.height)).dy -
              origin.dy,
        ),
  };
}

/// The card running high curves against the same card with quiet ones:
/// continuous fill also reaches the spaces around foreground glyphs.
Future<void> _expectContinuousBackdrop(
  WidgetTester tester,
  Future<void> Function(List<Traffic> samples) pump,
) async {
  await pump(_quiet);
  await tester.pump(fadeTabDuration);
  final (quiet, size) = await _pixels(tester);
  await tester.pumpWidget(const SizedBox.shrink());
  await pump(_peaks);
  await tester.pump(fadeTabDuration);
  final (high, highSize) = await _pixels(tester);
  expect(highSize, size);
  final origin = tester.getTopLeft(find.byKey(_capture));
  final boxes = <String, Rect>{};
  for (final element
      in find
          .descendant(
            of: find.byType(CurrentOutboundCard),
            matching: find.byType(RichText),
          )
          .evaluate()) {
    final paragraph = element.renderObject! as RenderParagraph;
    final text = paragraph.text.toPlainText();
    if (text.trim().isEmpty || text.runes.any((r) => r >= 0x1F1E6)) continue;
    for (final (index, box)
        in paragraph
            .getBoxesForSelection(
              TextSelection(baseOffset: 0, extentOffset: text.length),
            )
            .indexed) {
      boxes['$text line $index'] = MatrixUtils.transformRect(
        paragraph.getTransformTo(null),
        box.toRect(),
      ).shift(-origin);
    }
  }
  boxes['figures'] = tester
      .getRect(find.byType(OutboundTrafficFigures))
      .shift(-origin);
  var changed = 0;
  var changedInTextBounds = 0;
  for (var y = 0; y < size.height.round(); y++) {
    for (var x = 0; x < size.width.round(); x++) {
      if (high[y][x] == quiet[y][x]) continue;
      changed++;
      final point = Offset(x + 0.5, y + 0.5);
      if (boxes.values.any((box) => box.contains(point))) {
        changedInTextBounds++;
      }
    }
  }
  expect(changed, greaterThan(200), reason: 'the curves never showed');
  expect(
    changedInTextBounds,
    greaterThan(10),
    reason: 'the fill must stay continuous around the glyphs',
  );
  final masks = <ShaderMaskLayer>[];
  void visit(Layer layer) {
    if (layer is ShaderMaskLayer) masks.add(layer);
    if (layer is ContainerLayer) {
      for (
        var child = layer.firstChild;
        child != null;
        child = child.nextSibling
      ) {
        visit(child);
      }
    }
  }

  visit(
    tester
        .renderObject<RenderRepaintBoundary>(find.byKey(_capture))
        .debugLayer!,
  );
  expect(masks, isEmpty, reason: 'no local text mask may cut the backdrop');
  expect(tester.takeException(), isNull);
}

void main() {
  setUpAll(() async {
    final font = FontLoader(_font)..addFont(loadSdkTestFont());
    await font.load();
    _fallback = await loadCjkTestFonts();
  });

  for (final running in [false, true]) {
    testWidgets('a ${running ? 'running' : 'stopped'} status card always '
        'carries its speed lines and figures', (tester) async {
      await _pump(
        tester,
        const CurrentOutboundCard(),
        running: running,
        samples: running ? _samples : const [],
      );
      for (final part in [OutboundTrafficBackground, OutboundTrafficFigures]) {
        expect(
          find.descendant(
            of: find.byType(CurrentOutboundCard),
            matching: find.byType(part),
          ),
          findsOneWidget,
        );
      }
      expect(find.byType(LineChart), findsNWidgets(4));
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('a stopped status card lays both speed lines flat in the gap '
      'at the card bottom', (tester) async {
    await _pump(tester, const CurrentOutboundCard());
    final (rows, size) = await _pixels(tester);
    final drawn = _drawnRows(rows, size);
    expect(drawn, isNotEmpty, reason: 'no flat line was drawn');
    expect(drawn.last - drawn.first, lessThanOrEqualTo(3));
    expect(drawn.last, greaterThanOrEqualTo(size.height - 3));
    expect(tester.takeException(), isNull);
  });

  testWidgets('running speed lines fill only down to their zero line', (
    tester,
  ) async {
    // A name that fits beside a ticking run time, so both cards match.
    await _pump(tester, const CurrentOutboundCard(), organization: 'Example');
    final (flat, size) = await _pixels(tester);
    final line = _drawnRows(flat, size);
    await tester.pumpWidget(const SizedBox.shrink());
    await _pump(
      tester,
      const CurrentOutboundCard(),
      running: true,
      samples: _samples,
      organization: 'Example',
    );
    await tester.pump(fadeTabDuration);
    final (rows, running) = await _pixels(tester);
    expect(running, size);
    final above = [
      for (var y = 30; y < line.first - 3; y++) rows[y][12] == flat[y][12],
    ];
    expect(above, contains(isFalse), reason: 'the area above is filled');
    final bottom = size.height.round() - 6;
    final middle = size.width ~/ 2;
    expect(
      rows[bottom][middle],
      isNot(flat[bottom][middle]),
      reason: 'the fill must reach the footer bottom padding',
    );
    expect(tester.takeException(), isNull);
  });

  // The flat line runs the card's whole width, so it may cross no line of
  // text anywhere; the running band stays above the cells.
  const locales = [
    Locale('zh', 'CN'),
    Locale('en'),
    Locale('ja'),
    Locale('ko'),
    Locale('es'),
    Locale('fr'),
    Locale('ru'),
  ];
  for (final locale in locales) {
    for (final device in const [320.0, 360.0, 412.0]) {
      testWidgets('the speed lines cross no text '
          '${locale.toLanguageTag()} on a $device dp device', (tester) async {
        for (final scale in const [1.0, 1.3, 2.0]) {
          final why = '${locale.toLanguageTag()} $device dp x$scale';
          await _pump(
            tester,
            const CurrentOutboundCard(),
            device: device,
            scale: scale,
            locale: locale,
          );
          final (rows, size) = await _pixels(tester);
          final drawn = _drawnRows(rows, size);
          expect(drawn, isNotEmpty, reason: '$why draws no flat line');

          for (final MapEntry(key: text, value: (top, bottom)) in _texts(
            tester,
          ).entries) {
            for (final y in drawn) {
              expect(
                y + 0.5 < top || y + 0.5 > bottom,
                isTrue,
                reason:
                    '$why: the flat line at $y crosses "$text" '
                    '($top-$bottom)',
              );
            }
          }
          expect(
            drawn.last,
            greaterThanOrEqualTo(size.height - 3),
            reason: why,
          );
          await tester.pumpWidget(const SizedBox.shrink());
          await _pump(
            tester,
            const CurrentOutboundCard(),
            device: device,
            scale: scale,
            locale: locale,
            running: true,
            samples: _samples,
          );
          await tester.pump(fadeTabDuration);
          final (running, runningSize) = await _pixels(tester);
          final band = _drawnRows(running, runningSize);
          expect(band, isNotEmpty, reason: why);
          expect(
            band.last,
            greaterThanOrEqualTo(runningSize.height - 3),
            reason: '$why: the curves stop before the card bottom',
          );
          await tester.pumpWidget(const SizedBox.shrink());
        }
        expect(tester.takeException(), isNull);
      });
    }
  }

  for (final (density, width, height) in const [
    (DashboardDensity.standard, 1180.0, 208.0),
    (DashboardDensity.standard, 600.0, 208.0),
    (DashboardDensity.compact, 820.0, 176.0),
    (DashboardDensity.compact, 600.0, 176.0),
  ]) {
    testWidgets('the ${density.name} $width status card keeps its flat line '
        'off its text', (tester) async {
      await _pump(
        tester,
        const CurrentOutboundCard(),
        width: width,
        height: height,
        density: density,
      );
      final (rows, size) = await _pixels(tester);
      final drawn = _drawnRows(rows, size);
      expect(drawn, isNotEmpty);
      for (final MapEntry(key: text, value: (top, bottom)) in _texts(
        tester,
      ).entries) {
        for (final y in drawn) {
          expect(y + 0.5 < top || y + 0.5 > bottom, isTrue, reason: text);
        }
      }
      expect(drawn.last, greaterThanOrEqualTo(size.height - 3));
      expect(tester.takeException(), isNull);
    });
  }

  // Continuous curves stay behind the foreground; there are no text cutouts.
  for (final (locale, device, scale, ip) in const [
    (Locale('en'), 360.0, 1.0, '203.0.113.9'),
    (Locale('zh', 'CN'), 360.0, 1.0, '203.0.113.9'),
    (Locale('en'), 412.0, 1.3, '2001:db8:85a3::8a2e:370:7334'),
    (Locale('ja'), 320.0, 2.0, '203.0.113.9'),
    (Locale('ru'), 393.0, 1.0, '2001:db8:85a3::8a2e:370:7334'),
  ]) {
    testWidgets('running fill stays continuous around foreground text '
        '${locale.toLanguageTag()} on a $device dp device at $scale', (
      tester,
    ) async {
      await _expectContinuousBackdrop(
        tester,
        (samples) => _pump(
          tester,
          const CurrentOutboundCard(),
          running: true,
          samples: samples,
          device: device,
          scale: scale,
          locale: locale,
          ip: ip,
        ),
      );
    });
  }

  for (final (density, width, height) in const [
    (DashboardDensity.standard, 1180.0, 208.0),
    (DashboardDensity.compact, 760.0, 176.0),
  ]) {
    testWidgets('running fill stays continuous around foreground text on the '
        '${density.name} $width card', (tester) async {
      await _expectContinuousBackdrop(
        tester,
        (samples) => _pump(
          tester,
          const CurrentOutboundCard(),
          running: true,
          samples: samples,
          width: width,
          height: height,
          density: density,
          ip: '2001:db8:85a3::8a2e:370:7334',
        ),
      );
    });
  }

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('on ${quality.name} glass with Aurora the speed lines take '
        'one ink and no layer dims them', (tester) async {
      await _pump(
        tester,
        LiquidGlassScope(
          enabled: true,
          foregroundEnabled: true,
          quality: quality,
          child: const LiquidGlassHost(
            enabled: true,
            child: CurrentOutboundCard(),
          ),
        ),
        running: true,
        samples: _samples,
      );
      final backdrop = find.byType(OutboundTrafficBackground);
      final inks = tester.widgetList<GlassInk>(
        find.descendant(of: backdrop, matching: find.byType(GlassInk)),
      );
      expect(inks, hasLength(1));
      final colors = Theme.of(tester.element(backdrop)).colorScheme;
      expect(inks.single.role, GlassInkRole.icon);
      expect(inks.single.source, colors.onSurface);
      expect(
        find.descendant(of: backdrop, matching: find.byType(Opacity)),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('without glass the speed lines keep the theme colours and no '
      'layer dims them', (tester) async {
    await _pump(
      tester,
      const CurrentOutboundCard(),
      running: true,
      samples: _samples,
    );
    final backdrop = find.byType(OutboundTrafficBackground);
    expect(
      find.descendant(of: backdrop, matching: find.byType(GlassInk)),
      findsNothing,
    );
    expect(
      find.descendant(of: backdrop, matching: find.byType(Opacity)),
      findsNothing,
    );
    expect(tester.takeException(), isNull);
  });
}
