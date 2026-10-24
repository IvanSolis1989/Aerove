import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
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
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/views/dashboard/widgets/service_status.dart';
import 'package:fl_clash/views/dashboard/widgets/connection_stats.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

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

void main() {
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
    ProviderContainer? existing,
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
          child: Scaffold(
            body: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(
                width: width,
                child: MediaQuery.withClampedTextScaling(
                  minScaleFactor: scale,
                  maxScaleFactor: scale,
                  child: DashboardDensityScope(
                    density: DashboardDensity.mobile,
                    scale: scale,
                    child: child,
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

  testWidgets('traffic replaces the ribbon layer without adding card height', (
    tester,
  ) async {
    final container = await pump(tester, const CurrentOutboundCard());
    final originalHeight = tester.getSize(find.byType(DashboardCard)).height;
    await pump(
      tester,
      const CurrentOutboundCard(showTraffic: true),
      existing: container,
    );
    final fusedHeight = tester.getSize(find.byType(DashboardCard)).height;
    expect(fusedHeight, originalHeight);
    final status = tester.getRect(find.text('状态'));
    final upload = tester.getRect(find.text('上传'));
    final download = tester.getRect(find.text('下载'));
    expect(upload.top, status.top);
    expect(download.top, status.top);
    expect(upload.overlaps(status), isFalse);
    expect(download.overlaps(status), isFalse);
    expect(
      tester
          .getRect(find.byType(OutboundTrafficFigures))
          .overlaps(tester.getRect(find.byType(OutboundBubble))),
      isFalse,
    );
    container
        .read(trafficsProvider.notifier)
        .addTraffic(const Traffic(up: 1024, down: 4096));
    await tester.pump();
    expect(find.byType(LineChart), findsNWidgets(2));
    expect(find.byType(OutboundRibbon), findsNothing);
    final charts = tester.widgetList<LineChart>(find.byType(LineChart));
    expect(charts.map((chart) => chart.minScale).toSet(), {8192.0});
    expect(find.byType(OutboundTrafficFigures), findsOneWidget);
    expect(tester.getSize(find.byType(DashboardCard)).height, originalHeight);
    container.read(trafficsProvider.notifier).clear();
    container.read(trafficsProvider.notifier).addTraffic(const Traffic());
    await tester.pump();
    for (final chart in tester.widgetList<LineChart>(find.byType(LineChart))) {
      expect(chart.values, everyElement(0));
    }
    expect(tester.takeException(), null);
  });

  for (final scale in [1.0, 2.0]) {
    testWidgets('network controls retain separate switches at scale $scale', (
      tester,
    ) async {
      final container = await pump(
        tester,
        const FusedNetworkControls(
          members: {
            DashboardWidget.outboundMode,
            DashboardWidget.vpnButton,
            DashboardWidget.intranetIp,
          },
        ),
        scale: scale,
      );
      expect(find.byType(DashboardCard), findsOneWidget);
      expect(find.byType(DashboardSwitch), findsNWidgets(2));
      final switches = tester
          .widgetList<DashboardSwitch>(find.byType(DashboardSwitch))
          .toList();
      final oldLan = container.read(patchClashConfigProvider).allowLan;
      switches[0].onChanged!(!switches[0].value);
      expect(container.read(patchClashConfigProvider).allowLan, oldLan);
      switches[1].onChanged!(!oldLan);
      expect(container.read(patchClashConfigProvider).allowLan, !oldLan);
      expect(tester.takeException(), null);
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
    expect(find.byType(LineChart), findsNWidgets(2));
    container.read(appVisibleProvider.notifier).value = false;
    await tester.pump();
    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump();
    expect(find.byType(LineChart), findsNothing);
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
      final container = await pump(
        tester,
        const CurrentOutboundCard(showTraffic: true),
      );
      container
          .read(trafficsProvider.notifier)
          .addTraffic(const Traffic(up: 1024, down: 4096));
      await tester.pump();
      expect(find.byType(LineChart), findsNWidgets(2));
      if (boundary == 'pause') {
        container.read(_suspended.notifier).setValue(true);
      } else {
        container.read(_running.notifier).setValue(false);
      }
      await tester.pump();
      expect(find.byType(LineChart), findsNothing);
      container
          .read(trafficsProvider.notifier)
          .addTraffic(const Traffic(up: 2048, down: 8192));
      await tester.pump();
      expect(find.byType(LineChart), findsNothing);
      if (boundary == 'pause') {
        container.read(_suspended.notifier).setValue(false);
      } else {
        container.read(_running.notifier).setValue(true);
      }
      await tester.pump();
      expect(find.byType(LineChart), findsNothing);
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
        const CurrentOutboundCard(showTraffic: true),
        scale: 2,
        width: 328,
      );
      final figures = tester.getRect(find.byType(OutboundTrafficFigures));
      final chip = tester.getRect(find.byType(DashboardChip).first);
      final button = tester.getRect(find.byType(OutboundBubble));
      expect(
        tester.getRect(find.text('上传')).top,
        tester.getRect(find.text('状态')).top,
      );
      expect(
        tester.getRect(find.text('下载')).top,
        tester.getRect(find.text('状态')).top,
      );
      expect(
        figures.overlaps(chip),
        isFalse,
        reason: 'figures=$figures chip=$chip',
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
