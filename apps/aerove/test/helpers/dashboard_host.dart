import 'package:fl_clash/common/service_probe.dart' show routedOutbound;
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
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
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import 'test_app.dart';

class DashboardCore extends Mock implements CoreHandlerInterface {}

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

/// Counts the layouts of what it wraps.
class LayoutCount extends SingleChildRenderObjectWidget {
  const LayoutCount(this.name, {super.key, required super.child});

  final String name;

  static final counts = <String, int>{};

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderLayouts(name);
}

class _RenderLayouts extends RenderProxyBox {
  _RenderLayouts(this.name);

  final String name;

  @override
  void performLayout() {
    LayoutCount.counts[name] = (LayoutCount.counts[name] ?? 0) + 1;
    super.performLayout();
  }
}

/// The phone dashboard's shape: a kept page of a [PageView] holding a sliver
/// list of cards, each behind its own repaint boundary, with a second page
/// to switch to, as home.dart builds them.
class DashboardHost {
  DashboardHost(this.tester, this.container, this.pages, this.shown, this.core);

  final WidgetTester tester;
  final ProviderContainer container;
  final PageController pages;
  final ValueNotifier<int> shown;
  final DashboardCore core;
  var _up = 1024;

  void sample() {
    _up = (_up * 13 + 7919) % 900000 + 2048;
    container
        .read(trafficsProvider.notifier)
        .addTraffic(Traffic(up: _up, down: _up * 3));
    final total = container.read(totalTrafficProvider);
    container.read(totalTrafficProvider.notifier).value = Traffic(
      up: total.up + _up,
      down: total.down + _up * 3,
    );
    final runTime = container.read(runTimeProvider);
    container.read(runTimeProvider.notifier).value = (runTime ?? 0) + 1000;
  }

  /// One second of a 60 Hz display, after a sample unless [traffic] is off.
  Future<void> second({bool traffic = true}) async {
    if (traffic) sample();
    for (var frame = 0; frame < 60; frame++) {
      await tester.pump(const Duration(microseconds: 16667));
    }
  }

  Future<void> show(int page) async {
    shown.value = page;
    pages.jumpToPage(page);
    await tester.pump();
    await tester.pump();
  }
}

class _Page extends StatelessWidget {
  const _Page({required this.index, required this.shown, required this.child});

  final int index;
  final ValueListenable<int> shown;
  final Widget child;

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<int>(
    valueListenable: shown,
    builder: (context, current, child) => PageActivityScope(
      isActive: current == index,
      child: TickerMode(enabled: current == index, child: child!),
    ),
    child: KeepScope(child: child),
  );
}

Widget _glass(Widget child) => LiquidGlassScope(
  enabled: true,
  foregroundEnabled: true,
  child: LiquidGlassHost(
    enabled: true,
    wallpaperOnly: true,
    child: Stack(
      fit: StackFit.expand,
      children: [
        const Positioned.fill(
          child: LiquidGlassColorField(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [Color(0xFF1B5E4A), Color(0xFF4A1B5E)],
                ),
              ),
            ),
          ),
        ),
        child,
      ],
    ),
  ),
);

Future<DashboardHost> pumpDashboardHost(
  WidgetTester tester, {
  bool glass = false,
  DashboardDensity density = DashboardDensity.mobile,
  bool controls = false,
}) async {
  // A desktop status card spans the window's whole top row.
  final width = density.isDesktop ? 1220.0 : 412.0;
  tester.view.physicalSize = Size(width * 3, 915 * 3);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  LayoutCount.counts.clear();
  final core = DashboardCore();
  when(
    () => core.getTraffic(any()),
  ).thenAnswer((_) async => const Traffic(up: 1, down: 2));
  when(
    () => core.getTotalTraffic(any()),
  ).thenAnswer((_) async => const Traffic(up: 3, down: 4));
  final container = ProviderContainer(
    overrides: [
      coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      profilesProvider.overrideWithValue(const [
        Profile(id: 1, autoUpdateDuration: Duration.zero),
      ]),
      isStartProvider.overrideWith((ref) => true),
      suspendProvider.overrideWith((ref) => false),
      outboundIpProbeProvider.overrideWith(_Ips.new),
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
  container
    ..listen(patchClashConfigProvider, (_, _) {})
    ..listen(vpnSettingProvider, (_, _) {});
  final pages = PageController();
  final shown = ValueNotifier(0);
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
    pages.dispose();
    shown.dispose();
    await tester.pump();
  });
  // A desktop card stands in a grid cell of its own height.
  final card = density.isDesktop
      ? const SizedBox(height: 208, child: CurrentOutboundCard())
      : const CurrentOutboundCard();
  final page = Scaffold(
    backgroundColor: glass ? Colors.transparent : null,
    body: AuroraMotionActivity(
      child: DashboardDensityScope(
        density: density,
        scale: 1,
        child: PageView(
          controller: pages,
          physics: const NeverScrollableScrollPhysics(),
          children: [
            _Page(
              index: 0,
              shown: shown,
              child: CustomScrollView(
                slivers: [
                  SliverLayoutBuilder(
                    builder: (context, constraints) => SliverPadding(
                      padding: const EdgeInsets.all(16),
                      sliver: SliverToBoxAdapter(
                        child: LayoutCount(
                          'column',
                          child: Column(
                            spacing: 12,
                            children: [
                              RepaintBoundary(
                                child: LayoutCount('card', child: card),
                              ),
                              if (controls)
                                const RepaintBoundary(
                                  child: FusedNetworkControls(
                                    members: {
                                      DashboardWidget.outboundMode,
                                      DashboardWidget.vpnButton,
                                      DashboardWidget.intranetIp,
                                    },
                                  ),
                                ),
                              SizedBox(height: 1600, key: UniqueKey()),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            _Page(
              index: 1,
              shown: shown,
              child: const ColoredBox(color: Colors.black12),
            ),
          ],
        ),
      ),
    ),
  );
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        locale: const Locale('en'),
        child: glass ? _glass(page) : page,
      ),
    ),
  );
  await tester.pump();
  final host = DashboardHost(tester, container, pages, shown, core);
  for (var second = 0; second < 3; second++) {
    await host.second();
  }
  await host.second(traffic: false);
  return host;
}

/// One second of drift with no traffic: nothing but the bubble may work.
Future<void> expectOnlyTheBubbleDrifts(DashboardHost host) async {
  var rebuilds = 0;
  var paragraphs = 0;
  var paints = 0;
  LayoutCount.counts.clear();
  debugOnRebuildDirtyWidget = (_, _) => rebuilds++;
  debugOnProfilePaint = (object) {
    paints++;
    if (object is RenderParagraph) paragraphs++;
  };
  try {
    await host.second(traffic: false);
  } finally {
    debugOnRebuildDirtyWidget = null;
    debugOnProfilePaint = null;
  }
  expect(rebuilds, 0, reason: 'a drift frame rebuilt widgets');
  expect(paragraphs, 0, reason: 'a drift frame repainted the card text');
  expect(paints, lessThanOrEqualTo(15 * 16));
  expect(
    LayoutCount.counts,
    isEmpty,
    reason: 'a drift frame laid out the card',
  );
}
