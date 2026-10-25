import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/exit_snapshot.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/last_exit.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/route_state.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:riverpod/riverpod.dart';

import '../helpers/preferences.dart';

class _MockCore extends Mock implements CoreHandlerInterface {}

const _answer = OutboundIpResult(
  url: 'https://www.cloudflare.com/cdn-cgi/trace',
  body: 'ip=2.2.2.2\nloc=US\n',
  chains: ['HK-01'],
  coreEpoch: 1,
  routeVersion: 1,
);

void main() {
  setUpAll(() {
    registerFallbackValue(const OutboundIpParams(timeout: 0));
    registerFallbackValue(const CheckRoutesParams());
  });

  late _MockCore core;

  setUp(() => core = _MockCore());

  ProviderContainer build({
    bool proxied = true,
    int? profileId = 7,
    ExitSnapshot? lastExit,
  }) {
    final container = ProviderContainer(
      overrides: [
        currentProfileIdProvider.overrideWithBuild((_, _) => profileId),
        lastExitProvider.overrideWithBuild((_, _) => lastExit),
        routeTrackerProvider.overrideWithBuild(
          (_, _) => RouteState(
            coreEpoch: 1,
            routeVersion: 1,
            hostEpoch: 1,
            proxied: proxied,
            synced: true,
            live: true,
          ),
        ),
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      ],
    );
    addTearDown(container.dispose);
    container.listen(outboundIpProbeProvider, (_, _) {});
    return container;
  }

  Future<OutboundIpParams> asked() async {
    await pumpEventQueue();
    return verify(() => core.outboundIp(captureAny())).captured.single
        as OutboundIpParams;
  }

  test(
    'asks the Core for the address the current route exits through',
    () async {
      when(() => core.outboundIp(any())).thenAnswer((_) async => _answer);
      final container = build();

      container.read(outboundIpProbeProvider.notifier).watch(routedOutbound);

      final params = await asked();
      expect(params.proxyName, isEmpty);
      expect(params.urls, ipInfoSources.keys.toList());
      final entry = container
          .read(outboundIpProbeProvider)
          .entryOf(routedOutbound);
      expect(entry.phase, ProbePhase.fresh);
      expect(entry.value?.ip, '2.2.2.2');
    },
  );

  test('pins the direct outbound while the proxy is off', () async {
    when(() => core.outboundIp(any())).thenAnswer((_) async => _answer);
    final container = build(proxied: false);

    container.read(outboundIpProbeProvider.notifier).watch(routedOutbound);

    expect((await asked()).proxyName, directOutbound);
  });

  test('looks a node up through itself', () async {
    when(() => core.outboundIp(any())).thenAnswer((_) async => _answer);
    final container = build();

    container.read(outboundIpProbeProvider.notifier).watch('HK-01');

    expect((await asked()).proxyName, 'HK-01');
    expect(
      container.read(outboundIpProbeProvider).valueOf('HK-01')?.countryCode,
      'US',
    );
  });

  test('the routed answer fills in the node it left through', () async {
    when(() => core.outboundIp(any())).thenAnswer(
      (_) async => const OutboundIpResult(
        url: 'https://www.cloudflare.com/cdn-cgi/trace',
        body: 'ip=2.2.2.2\nloc=US\n',
        chains: ['HK-01', 'Balance', 'Proxy'],
        coreEpoch: 1,
        routeVersion: 1,
        hits: [RouteHit(host: 'www.cloudflare.com:443', exit: 'Balance')],
        nodeHits: [
          RouteHit(
            host: 'www.cloudflare.com:443',
            proxy: 'HK-01',
            exit: 'HK-01',
          ),
        ],
      ),
    );
    final container = build();
    final probe = container.read(outboundIpProbeProvider.notifier);

    probe.watch(routedOutbound);
    await pumpEventQueue();
    probe.watch('HK-01');
    await pumpEventQueue();

    verify(() => core.outboundIp(any())).called(1);
    final entry = container.read(outboundIpProbeProvider).entryOf('HK-01');
    expect(entry.phase, ProbePhase.fresh);
    expect(entry.value?.ip, '2.2.2.2');
    expect(entry.stamp?.hits, const [
      RouteHit(host: 'www.cloudflare.com:443', proxy: 'HK-01', exit: 'HK-01'),
    ]);
  });

  test('a Core error is a failure until it is refreshed', () async {
    when(() => core.outboundIp(any())).thenAnswer(
      (_) async => const OutboundIpResult(
        error: 'timeout',
        coreEpoch: 1,
        routeVersion: 1,
      ),
    );
    final container = build();
    final probe = container.read(outboundIpProbeProvider.notifier);

    probe.watch(routedOutbound);
    await pumpEventQueue();

    expect(
      container.read(outboundIpProbeProvider).entryOf(routedOutbound).phase,
      ProbePhase.failed,
    );
    verify(() => core.outboundIp(any())).called(1);

    probe.refresh([routedOutbound]);
    await pumpEventQueue();

    verify(() => core.outboundIp(any())).called(1);
  });

  testWidgets('a reselect that keeps the exit asks no source again, and one '
      'that moves it asks once', (tester) async {
    await mockPreferences();
    const hit = RouteHit(host: 'www.cloudflare.com:443', exit: 'HK-01');
    var version = 1;
    when(() => core.outboundIp(any())).thenAnswer(
      (_) async => _answer.copyWith(routeVersion: version, hits: const [hit]),
    );
    var exitHeld = true;
    final checked = <List<List<RouteHit>>>[];
    when(() => core.checkRoutes(any())).thenAnswer((invocation) async {
      final routes =
          (invocation.positionalArguments.single as CheckRoutesParams).routes;
      checked.add(routes);
      return RouteCheck(
        coreEpoch: 1,
        routeVersion: version,
        held: [for (final route in routes) route.isNotEmpty && exitHeld],
      );
    });
    final container = build();
    final tracker = container.read(routeTrackerProvider.notifier);
    container.read(outboundIpProbeProvider.notifier).watch(routedOutbound);
    await tester.pump();
    verify(() => core.outboundIp(any())).called(1);

    tracker.applySnapshot(RouteSnapshot(coreEpoch: 1, routeVersion: ++version));
    await tester.pump(probeSettleDuration);
    await tester.pump();

    expect(checked.single, contains(equals(const [hit])));
    verifyNever(() => core.outboundIp(any()));
    final entry = container
        .read(outboundIpProbeProvider)
        .entryOf(routedOutbound);
    expect(entry.phase, ProbePhase.fresh);
    expect(entry.stamp?.routeVersion, 2);

    exitHeld = false;
    tracker.applySnapshot(RouteSnapshot(coreEpoch: 1, routeVersion: ++version));
    await tester.pump(probeSettleDuration);
    await tester.pump(probeThrottleDuration);

    expect(checked, hasLength(2));
    verify(() => core.outboundIp(any())).called(1);
    await tester.pump(probeThrottleDuration);
  });

  group('the last exit', () {
    const last = ExitSnapshot(
      profileId: 7,
      proxied: true,
      ip: IpInfo(ip: '1.1.1.1', countryCode: 'JP'),
    );

    ProbeEntry<IpInfo> routed(ProviderContainer container) =>
        container.read(outboundIpProbeProvider).entryOf(routedOutbound);

    test('is shown as loading while the same profile is current', () {
      expect(
        routed(build(lastExit: last)),
        ProbeEntry(phase: ProbePhase.probing, value: last.ip),
      );
      expect(
        routed(build(lastExit: last, profileId: 8)).phase,
        ProbePhase.empty,
      );
    });

    test('becomes the fresh routed answer', () async {
      await mockPreferences();
      when(() => core.outboundIp(any())).thenAnswer((_) async => _answer);
      final container = build();

      container.read(outboundIpProbeProvider.notifier).watch(routedOutbound);
      await pumpEventQueue();

      const remembered = ExitSnapshot(
        profileId: 7,
        proxied: true,
        ip: IpInfo(ip: '2.2.2.2', countryCode: 'US'),
      );
      expect(container.read(lastExitProvider), remembered);
      expect(await preferences.getExitSnapshot(), remembered);
    });
  });
}
