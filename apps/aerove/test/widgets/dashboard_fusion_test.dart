import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/ip_quality/labels.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/providers/service_status.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/fusion.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/views/dashboard/widgets/status_layout.dart';
import 'package:fl_clash/views/dashboard/widgets/service_status.dart';
import 'package:fl_clash/views/dashboard/widgets/connection_stats.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/system_test_font.dart';
import '../helpers/test_app.dart';

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

class _Services extends ServiceStatus {
  int watches = 0;
  int refreshes = 0;

  @override
  RoutedProbeState<ServiceTarget, ServiceCheck> build() => RoutedProbeState({
    for (final target in ServiceTarget.values)
      target: const ProbeEntry(
        phase: ProbePhase.fresh,
        value: ServiceCheck(ServiceProbeStatus.available, delay: 42),
      ),
  });

  @override
  void watch(ServiceTarget target) {
    watches++;
  }

  @override
  void unwatch(ServiceTarget target) {}

  @override
  void refresh(Iterable<ServiceTarget> targets) {
    refreshes++;
  }
}

class _TestFlag extends Notifier<bool> {
  _TestFlag(this.initial);
  final bool initial;
  @override
  bool build() => initial;
  void setValue(bool value) => state = value;
}

final _running = NotifierProvider<_TestFlag, bool>(() => _TestFlag(true));
final _suspended = NotifierProvider<_TestFlag, bool>(() => _TestFlag(false));

/// The default test font draws every glyph a full em wide; figures need real
/// proportions to measure as on a phone.
const _font = 'FusionRoboto';

Iterable<Rect> _paintedBounds(WidgetTester tester, Finder finder) sync* {
  if (tester.widget(finder) is! Text) {
    yield tester.getRect(finder);
    return;
  }
  for (final paragraph in tester.renderObjectList<RenderParagraph>(
    find.descendant(of: finder, matching: find.byType(RichText)),
  )) {
    for (final box in paragraph.getBoxesForSelection(
      TextSelection(
        baseOffset: 0,
        extentOffset: paragraph.text.toPlainText().length,
      ),
    )) {
      yield MatrixUtils.transformRect(
        paragraph.getTransformTo(null),
        box.toRect(),
      );
    }
  }
}

void main() {
  setUpAll(() async {
    final font = FontLoader(_font)..addFont(loadSdkTestFont());
    await font.load();
  });

  test('fusion preserves first position and never restores hidden modules', () {
    final saved = [
      DashboardWidget.profiles,
      DashboardWidget.intranetIp,
      DashboardWidget.currentOutbound,
      DashboardWidget.connectionStats,
      DashboardWidget.outboundMode,
    ];
    final result = fuseDashboard(saved);
    expect(result.map((g) => g.anchor), saved.take(4));
    expect(result[1].members, {
      DashboardWidget.intranetIp,
      DashboardWidget.outboundMode,
    });
    expect(result.expand((g) => g.members).toSet(), saved.toSet());
    expect(result[2].members, {DashboardWidget.currentOutbound});
    expect(result[3].members, {DashboardWidget.connectionStats});
    expect(saved.last, DashboardWidget.outboundMode);
  });

  Future<ProviderContainer> pump(
    WidgetTester tester,
    Widget child, {
    double scale = 1,
    double width = 360,
    double? height,
    DashboardDensity density = DashboardDensity.mobile,
    double? densityScale,
    ProviderContainer? existing,
    String? fontFamily,
  }) async {
    tester.view.physicalSize = const Size(1000, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container =
        existing ??
        ProviderContainer(
          overrides: [
            profilesProvider.overrideWithValue(const [
              Profile(id: 1, autoUpdateDuration: Duration.zero),
            ]),
            isStartProvider.overrideWith((ref) => ref.watch(_running)),
            suspendProvider.overrideWith((ref) => ref.watch(_suspended)),
            outboundIpProbeProvider.overrideWith(_Ips.new),
            serviceStatusProvider.overrideWith(_Services.new),
            ipQualityProvider.overrideWith(
              (_, _) async => const IpQuality(
                ip: '203.0.113.9',
                source: IpQualitySource.ipLocate,
                type: IpType.residential,
                organization: 'Example Organization',
              ),
            ),
          ],
        );
    globalState.container = container;
    if (existing == null) {
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
          locale: const Locale('zh', 'CN'),
          theme: fontFamily == null ? null : ThemeData(fontFamily: fontFamily),
          child: Scaffold(
            body: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(
                width: width,
                height: height,
                child: MediaQuery.withClampedTextScaling(
                  minScaleFactor: scale,
                  maxScaleFactor: scale,
                  child: DashboardDensityScope(
                    density: density,
                    scale: densityScale ?? scale,
                    child: child is CurrentOutboundCard && height == null
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
    );
    await tester.pump();
    return container;
  }

  testWidgets('traffic adds its lines and figures without adding card height', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    try {
      final container = await pump(
        tester,
        const CurrentOutboundCard(),
        fontFamily: _font,
      );
      final layout = tester.renderObject<RenderStatusCardLayout>(
        find.byType(StatusCardLayout),
      );
      expect(layout.overlaid, isTrue);
      final body = layout.childAfter(layout.firstChild!)!;
      final originalHeight = body.size.height + 36;
      expect(tester.getSize(find.byType(DashboardCard)).height, originalHeight);
      final l10n = AppLocalizations.of(
        tester.element(find.byType(CurrentOutboundCard)),
      );
      final mainParts = [
        find.text(l10n.status),
        find.byType(DashboardChip).first,
        find.text('203.0.113.9'),
        find.byType(EmojiCover),
        find.text(l10n.runTime),
        find.text(l10n.ipOrganization),
        find.text('Example Organization'),
        find.byType(OutboundBubble),
      ];
      final figuresRect = tester.getRect(find.byType(OutboundTrafficFigures));
      final mainRects = [for (final part in mainParts) tester.getRect(part)];
      for (final part in [
        ...mainParts,
        for (var i = 0; i < find.byType(DashboardChip).evaluate().length; i++)
          find.byType(DashboardChip).at(i),
      ]) {
        for (final bound in _paintedBounds(tester, part)) {
          expect(figuresRect.overlaps(bound), isFalse);
        }
      }
      final status = tester.getRect(find.text('状态'));
      final figureTexts = find.descendant(
        of: find.byType(OutboundTrafficFigures),
        matching: find.byType(GlassText),
      );
      expect(figureTexts, findsNWidgets(2));
      final upload = tester.getRect(figureTexts.at(0));
      final download = tester.getRect(figureTexts.at(1));
      expect(upload.height, 10);
      expect(download.height, 10);
      expect(upload.height, lessThan(status.height));
      expect(
        upload.center.dy,
        closeTo(tester.getCenter(find.text(l10n.runTime)).dy, 0.01),
      );
      expect(
        download.center.dy,
        closeTo(
          tester
              .getCenter(
                find.text(getTimeText(container.read(runTimeProvider))),
              )
              .dy,
          0.01,
        ),
      );
      for (final text in tester.widgetList<GlassText>(figureTexts)) {
        expect(text.style?.fontSize, 10);
        expect(text.style?.fontWeight, FontWeight.w400);
      }
      final arrows = tester.widgetList<GlyphIcon>(
        find.descendant(
          of: find.byType(OutboundTrafficFigures),
          matching: find.byType(GlyphIcon),
        ),
      );
      expect(arrows.map((icon) => icon.glyph), [
        AppGlyphs.arrowUp,
        AppGlyphs.arrowDown,
      ]);
      for (final direction in TrafficDirection.values) {
        expect(
          find.descendant(
            of: find.byType(OutboundTrafficFigures),
            matching: find.text(
              direction.labelOf(tester.element(figureTexts.first)),
            ),
          ),
          findsNothing,
        );
      }
      final totals = container.read(totalTrafficProvider);
      final totalValues = [
        for (final direction in TrafficDirection.values)
          trafficAmount(direction.of(totals)),
      ];
      for (final value in totalValues.toSet()) {
        expect(
          find.descendant(
            of: find.byType(OutboundTrafficFigures),
            matching: find.text(value),
          ),
          findsNothing,
        );
        expect(find.bySemanticsLabel(l10n.trafficTotal(value)), findsNothing);
        expect(find.text(l10n.trafficTotal(value)), findsNothing);
      }

      expect(upload.overlaps(status), isFalse);
      expect(download.overlaps(status), isFalse);
      expect(
        tester
            .getRect(find.byType(OutboundTrafficFigures))
            .overlaps(tester.getRect(find.byType(OutboundBubble))),
        isFalse,
      );
      expect(find.byType(LineChart), findsNWidgets(4));
      for (final chart in tester.widgetList<LineChart>(
        find.byType(LineChart),
      )) {
        expect(chart.values, isEmpty);
      }
      container
          .read(trafficsProvider.notifier)
          .addTraffic(const Traffic(up: 1024, down: 4096));
      await tester.pump();
      expect(find.byType(LineChart), findsNWidgets(4));
      final charts = tester.widgetList<LineChart>(find.byType(LineChart));
      expect(charts.map((chart) => chart.minScale).toSet(), {8192.0});
      expect(find.byType(OutboundTrafficFigures), findsOneWidget);
      expect(tester.getSize(find.byType(DashboardCard)).height, originalHeight);
      for (var i = 0; i < mainParts.length; i++) {
        expect(tester.getRect(mainParts[i]), mainRects[i]);
      }
      container.read(trafficsProvider.notifier).clear();
      container.read(trafficsProvider.notifier).addTraffic(const Traffic());
      await tester.pumpAndSettle();
      for (final chart in tester.widgetList<LineChart>(
        find.byType(LineChart),
      )) {
        expect(chart.values, everyElement(0));
      }
      expect(tester.takeException(), null);
    } finally {
      semantics.dispose();
    }
  });

  Future<ProviderContainer> pumpTraffic(
    WidgetTester tester,
    Widget child, {
    double scale = 1,
    double width = 360,
    double? height,
    DashboardDensity density = DashboardDensity.mobile,
    double? densityScale,
    String? fontFamily,
  }) async {
    final container = await pump(
      tester,
      child,
      scale: scale,
      width: width,
      height: height,
      density: density,
      densityScale: densityScale,
      fontFamily: fontFamily,
    );
    container
        .read(trafficsProvider.notifier)
        .addTraffic(const Traffic(up: 1024, down: 2048));
    container.read(totalTrafficProvider.notifier).value = const Traffic(
      up: 4096,
      down: 8 << 20,
    );
    await tester.pump();
    return container;
  }

  List<String> readingOrder(WidgetTester tester) {
    final card = tester.getSemantics(find.byType(DashboardCard));
    bool inCard(SemanticsNode? node) =>
        node != null && (node == card || inCard(node.parent));
    return [
      for (final node in tester.semantics.simulatedAccessibilityTraversal())
        if (inCard(node)) node.label,
    ];
  }

  /// Upload before download, with direction and full speed in each stop.
  List<String> trafficStops(
    BuildContext context,
    ProviderContainer container, {
    required String Function(num speed) speed,
  }) {
    final latest = container.read(trafficsProvider).list.last;
    final l10n = AppLocalizations.of(context);
    final stops = [
      l10n.trafficUploadSpeed(speed(latest.up)),
      l10n.trafficDownloadSpeed(speed(latest.down)),
    ];
    expect(stops.toSet(), hasLength(2));
    return stops;
  }

  /// The title once, the state, then what the exit is before the controls
  /// acting on it.
  (List<String>, List<String>) statusStops(
    BuildContext context,
    ProviderContainer container,
  ) {
    final l10n = AppLocalizations.of(context);
    final quality = container
        .read(ipQualityProvider('203.0.113.9'))
        .requireValue;
    final level = '${l10n.ipQualityShort} ${quality.level.label(context)}';
    return (
      [
        l10n.status,
        l10n.running,
        '${quality.type.label(context)}\n$level',
        '${l10n.runTime}\n${getTimeText(container.read(runTimeProvider))}',
        '${l10n.ipOrganization}\nExample Organization',
      ],
      ['${l10n.retry}\nGB', '203.0.113.9', l10n.stop],
    );
  }

  for (final (label, width, scale, density, overlay) in const [
    ('mobile overlay', 360.0, 1.0, DashboardDensity.mobile, true),
    ('mobile a11y append', 328.0, 2.0, DashboardDensity.mobile, false),
    ('desktop overlay', 600.0, 0.9, DashboardDensity.standard, true),
  ]) {
    testWidgets(
      'a screen reader takes the $label status card in reading order',
      (tester) async {
        final semantics = tester.ensureSemantics();
        try {
          // A desktop card has a fixed height and a density scale of at least
          // one; its header leaves the overlay room only below that text scale.
          final container = await pumpTraffic(
            tester,
            const CurrentOutboundCard(),
            width: width,
            scale: scale,
            height: density.isDesktop ? 208 : null,
            density: density,
            densityScale: density.isDesktop ? 1 : null,
            fontFamily: _font,
          );
          expect(
            tester
                .renderObject<RenderStatusCardLayout>(
                  find.byType(StatusCardLayout),
                )
                .overlaid,
            overlay,
          );
          final context = tester.element(find.byType(OutboundTrafficFigures));
          final (summary, controls) = statusStops(context, container);
          final traffic = trafficStops(context, container, speed: trafficRate);
          final order = readingOrder(tester);
          expect(
            order,
            overlay
                ? [
                    ...summary.take(2),
                    ...traffic,
                    ...summary.skip(2),
                    ...controls,
                  ]
                : [...summary, ...controls, ...traffic],
            reason: order.join(' / '),
          );
        } finally {
          semantics.dispose();
        }
      },
    );
  }

  for (final (density, width, height) in const [
    (DashboardDensity.mobile, 360.0, null),
    (DashboardDensity.standard, 600.0, 208.0),
    (DashboardDensity.standard, 1180.0, 208.0),
    (DashboardDensity.compact, 600.0, 176.0),
  ]) {
    testWidgets('the ${density.name} $width status card reads its traffic '
        'where it is drawn', (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        final container = await pumpTraffic(
          tester,
          const CurrentOutboundCard(),
          width: width,
          height: height,
          density: density,
        );
        final context = tester.element(find.byType(CurrentOutboundCard));
        final (summary, controls) = statusStops(context, container);
        final traffic = trafficStops(context, container, speed: trafficRate);
        final overlaid = tester
            .renderObject<RenderStatusCardLayout>(find.byType(StatusCardLayout))
            .overlaid!;
        final order = readingOrder(tester);
        expect(
          order,
          overlaid
              ? [
                  ...summary.take(2),
                  ...traffic,
                  ...summary.skip(2),
                  ...controls,
                ]
              : [...summary, ...controls, ...traffic],
          reason: order.join(' / '),
        );
      } finally {
        semantics.dispose();
      }
    });
  }

  testWidgets(
    'service refresh preserves count owner and count events do not refresh services',
    (tester) async {
      final container = await pump(
        tester,
        const ServiceStatusCard(includeStats: true),
      );
      await tester.pump();
      final owner = container.read(serviceStatusProvider.notifier) as _Services;
      final initialWatches = owner.watches;
      final counts = tester.state(find.byType(ConnectionStatsCard));
      final l10n = AppLocalizations.of(
        tester.element(find.byType(ServiceStatusCard)),
      );
      await tester.tap(
        find.byWidgetPredicate(
          (w) => w is DashboardIconButton && w.tooltip == l10n.serviceCheckAll,
        ),
      );
      await tester.pump();
      expect(owner.refreshes, 1);
      expect(tester.state(find.byType(ConnectionStatsCard)), same(counts));
      container.read(requestCountProvider.notifier).value = 123;
      container.read(dnsQueryCountProvider.notifier).value = 22;
      await tester.pump(const Duration(milliseconds: 350));
      expect(owner.watches, initialWatches);
      expect(owner.refreshes, 1);
      expect(tester.state(find.byType(ConnectionStatsCard)), same(counts));
      expect(tester.takeException(), null);
    },
  );

  testWidgets('offscreen background holds samples and catches up on return', (
    tester,
  ) async {
    final container = await pump(
      tester,
      const SingleChildScrollView(
        child: Column(
          children: [
            SizedBox(
              height: 200,
              child: OutboundTrafficBackground(running: true),
            ),
            SizedBox(height: 1600),
          ],
        ),
      ),
    );
    container
        .read(trafficsProvider.notifier)
        .addTraffic(const Traffic(up: 100, down: 300));
    await tester.pump();
    await tester.drag(
      find.byType(SingleChildScrollView),
      const Offset(0, -500),
    );
    await tester.pump();
    await tester.pump();
    final held = tester
        .widget<LineChart>(find.byType(LineChart).first)
        .revision;
    container
        .read(trafficsProvider.notifier)
        .addTraffic(const Traffic(up: 200, down: 600));
    await tester.pump();
    expect(
      tester.widget<LineChart>(find.byType(LineChart).first).revision,
      held,
    );
    await tester.drag(find.byType(SingleChildScrollView), const Offset(0, 500));
    await tester.pump();
    await tester.pump();
    expect(
      tester.widget<LineChart>(find.byType(LineChart).first).revision,
      greaterThan(held),
    );
    expect(tester.takeException(), null);
  });

  testWidgets('foreground gaps do not connect old and resumed samples', (
    tester,
  ) async {
    final container = await pump(
      tester,
      const SizedBox(
        height: 200,
        child: OutboundTrafficBackground(running: true),
      ),
    );
    container
        .read(trafficsProvider.notifier)
        .addTraffic(const Traffic(up: 1024, down: 4096));
    await tester.pump();
    expect(find.byType(LineChart), findsNWidgets(4));
    container.read(appVisibleProvider.notifier).value = false;
    await tester.pump();
    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump();
    // The lines come back flat at once, without the samples from before.
    expect(find.byType(LineChart), findsNWidgets(4));
    for (final chart in tester.widgetList<LineChart>(find.byType(LineChart))) {
      expect(chart.values, isEmpty);
      expect(chart.lift!.value, 0);
    }
    expect(tester.binding.transientCallbackCount, 0);
    container.read(trafficsProvider.notifier).addTraffic(const Traffic());
    await tester.pump();
    expect(container.read(trafficsProvider).list.length, 2);
    for (final chart in tester.widgetList<LineChart>(find.byType(LineChart))) {
      expect(chart.values, [0]);
    }
    expect(tester.takeException(), null);
  });

  for (final boundary in ['pause', 'stop']) {
    testWidgets('$boundary resume starts a fresh traffic episode', (
      tester,
    ) async {
      final container = await pump(tester, const CurrentOutboundCard());
      container
          .read(trafficsProvider.notifier)
          .addTraffic(const Traffic(up: 1024, down: 4096));
      await tester.pump();
      expect(find.byType(LineChart), findsNWidgets(4));
      double lift() =>
          tester.widget<LineChart>(find.byType(LineChart).first).lift!.value;
      // The first sample raises the curve out of the flat line.
      expect(lift(), 0);
      await tester.pump(fadeTabDuration ~/ 2);
      expect(lift(), inExclusiveRange(0, 1));
      await tester.pump(fadeTabDuration);
      expect(lift(), 1);
      if (boundary == 'pause') {
        container.read(_suspended.notifier).setValue(true);
      } else {
        container.read(_running.notifier).setValue(false);
      }
      await tester.pump();
      await tester.pump(fadeTabDuration ~/ 2);
      expect(lift(), inExclusiveRange(0, 1));
      await tester.pump(fadeTabDuration);
      expect(lift(), 0);
      container
          .read(trafficsProvider.notifier)
          .addTraffic(const Traffic(up: 2048, down: 8192));
      await tester.pump();
      expect(lift(), 0);
      if (boundary == 'pause') {
        container.read(_suspended.notifier).setValue(false);
      } else {
        container.read(_running.notifier).setValue(true);
      }
      await tester.pump();
      for (final chart in tester.widgetList<LineChart>(
        find.byType(LineChart),
      )) {
        expect(chart.values, isEmpty);
      }
      container.read(trafficsProvider.notifier).addTraffic(const Traffic());
      await tester.pump();
      expect(container.read(trafficsProvider).list.length, 3);
      for (final chart in tester.widgetList<LineChart>(
        find.byType(LineChart),
      )) {
        expect(chart.values, [0]);
      }
      expect(tester.takeException(), null);
    });
  }

  testWidgets(
    'status speed and button do not overlap at narrow double text scale',
    (tester) async {
      await pump(
        tester,
        const CurrentOutboundCard(),
        scale: 2,
        width: 328,
        fontFamily: _font,
      );
      final layout = tester.renderObject<RenderStatusCardLayout>(
        find.byType(StatusCardLayout),
      );
      expect(layout.overlaid, isFalse);
      expect(layout.footerSlotAvailable, isFalse);
      final body = layout.childAfter(layout.firstChild!)!;
      final bodyRect = body.localToGlobal(Offset.zero) & body.size;
      final mainParts = [
        find.text('状态'),
        find.byType(DashboardChip).first,
        find.text('203.0.113.9'),
        find.byType(EmojiCover),
        find.byType(OutboundBubble),
      ];
      final figures = tester.getRect(find.byType(OutboundTrafficFigures));
      final chip = tester.getRect(find.byType(DashboardChip).first);
      final flag = tester.getRect(find.byType(OutboundBubble));
      final button = tester.getRect(
        find.byTooltip(
          AppLocalizations.of(tester.element(find.byType(DashboardCard))).stop,
        ),
      );
      for (final part in mainParts) {
        final rect = tester.getRect(part);
        expect(bodyRect.intersect(rect), rect, reason: '$part');
      }
      expect(figures.top, closeTo(bodyRect.bottom + 8, 0.01));
      expect(
        tester.getSize(find.byType(DashboardCard)).height,
        closeTo(body.size.height + 36 + 8 + figures.height, 0.01),
      );
      final speeds = find.descendant(
        of: find.byType(OutboundTrafficFigures),
        matching: find.byType(GlassText),
      );
      expect(speeds, findsNWidgets(2));
      for (final element in speeds.evaluate()) {
        expect(tester.getSize(find.byWidget(element.widget)).height, 20);
      }
      expect(
        figures.overlaps(chip),
        isFalse,
        reason: 'figures=$figures chip=$chip',
      );
      expect(
        figures.overlaps(flag),
        isFalse,
        reason: 'figures=$figures flag=$flag',
      );
      expect(
        figures.overlaps(button),
        isFalse,
        reason: 'figures=$figures button=$button',
      );
      expect(tester.takeException(), null);
    },
  );
}
