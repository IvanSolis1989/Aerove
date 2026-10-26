import 'dart:async';

import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/route_state.dart';
import 'package:fl_clash/providers/service_status.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/service_status.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../../helpers/test_app.dart';

class _MockCore extends Mock implements CoreHandlerInterface {}

/// Google and GitHub answer through the Proxy group, the other two shown
/// services through Media, so a re-pick of one group leaves the others fresh.
String _groupOf(String name) =>
    name == ServiceTarget.google.id || name == ServiceTarget.github.id
    ? 'Proxy'
    : 'Media';

void main() {
  setUpAll(() {
    registerFallbackValue(const ServiceCheckParams(timeout: 0));
    registerFallbackValue(const OutboundIpParams(timeout: 0));
  });

  final shownIds = [
    for (final target in ServiceTarget.values.take(4)) target.id,
  ];

  late _MockCore core;
  late List<String> asked;
  late Map<String, String> picks;
  late int picksVersion;
  late Set<String> timingOut;
  late List<Completer<void>> held;
  late bool hold;

  setUp(() {
    core = _MockCore();
    asked = [];
    picks = {'Proxy': 'NodeA', 'Media': 'NodeM'};
    picksVersion = 1;
    timingOut = {};
    held = [];
    hold = false;
    when(() => core.serviceCheck(any())).thenAnswer((invocation) async {
      final params =
          invocation.positionalArguments.single as ServiceCheckParams;
      asked.addAll(params.names);
      final version = picksVersion;
      final chains = {
        for (final name in params.names)
          name: [picks[_groupOf(name)]!, _groupOf(name)],
      };
      if (hold) {
        final answer = Completer<void>();
        held.add(answer);
        await answer.future;
      }
      return [
        for (final name in params.names)
          ServiceCheckItem(
            name: name,
            status: timingOut.contains(name)
                ? ServiceProbeStatus.timeout.id
                : ServiceProbeStatus.available.id,
            delay: 40,
            chains: chains[name]!,
            region: '',
            checkedAt: 1700000000000,
            coreEpoch: 1,
            picksVersion: version,
          ),
      ];
    });
    when(
      () => core.outboundIp(any()),
    ).thenAnswer((_) => Completer<OutboundIpResult?>().future);
  });

  Widget app(Widget body) => TestApp(
    overrides: [
      viewSizeProvider.overrideWithBuild((_, _) => const Size(400, 800)),
      runTimeProvider.overrideWithBuild((_, _) => 1),
      routeTrackerProvider.overrideWithBuild(
        (ref, _) => const RouteState(
          coreEpoch: 1,
          picksVersion: 1,
          picks: {'Proxy': 'NodeA', 'Media': 'NodeM'},
          hostEpoch: 1,
          proxied: true,
          synced: true,
          live: true,
        ),
      ),
      coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      ipQualityProvider.overrideWith((_, _) => Completer<IpQuality>().future),
    ],
    child: Scaffold(body: body),
  );

  Widget card() => const SizedBox(
    width: 360,
    child: DashboardDensityScope(
      density: DashboardDensity.mobile,
      scale: 1,
      child: ServiceStatusCard(),
    ),
  );

  Widget page(ValueListenable<bool> active) => ValueListenableBuilder<bool>(
    valueListenable: active,
    builder: (_, isActive, child) => PageActivityScope(
      isActive: isActive,
      child: TickerMode(enabled: isActive, child: child!),
    ),
    child: Center(child: card()),
  );

  /// What HomePage does: the shell ticks only while the app is visible, and
  /// AppStateManager counts every state but resumed as not visible, a window
  /// or the notification shade taking focus included.
  Widget shell(Widget child) => Consumer(
    builder: (_, ref, child) =>
        TickerMode(enabled: ref.watch(appVisibleProvider), child: child!),
    child: child,
  );

  ProviderContainer containerOf(WidgetTester tester) =>
      ProviderScope.containerOf(
        tester.element(find.byType(ServiceStatusCard, skipOffstage: false)),
      );

  /// The status card holds the route too, so it stays live while the
  /// service card is away.
  void holdRoute(WidgetTester tester) =>
      containerOf(tester).read(routeTrackerProvider.notifier).attach();

  List<String> labels(WidgetTester tester) => [
    for (final element
        in find
            .descendant(
              of: find.byType(ServiceStatusCard),
              matching: find.byWidgetPredicate(
                (widget) =>
                    widget is Semantics && widget.properties.liveRegion == true,
              ),
            )
            .evaluate())
      (element.widget as Semantics).properties.value!,
  ];

  /// What a watchRoute(true) answers on the route the Core is already on.
  void resubscribe(WidgetTester tester) {
    containerOf(tester)
        .read(routeTrackerProvider.notifier)
        .applySnapshot(
          RouteSnapshot(
            coreEpoch: 1,
            picksVersion: picksVersion,
            picks: Map.of(picks),
          ),
        );
  }

  /// What the Core publishes after a group re-picks.
  void publish(WidgetTester tester) {
    picksVersion++;
    resubscribe(tester);
  }

  Map<String, String> phases(WidgetTester tester) {
    final state = containerOf(tester).read(serviceStatusProvider);
    return {
      for (final target in ServiceTarget.values.take(4))
        target.id: state.entryOf(target).phase.name,
    };
  }

  Future<void> settle(WidgetTester tester) async {
    await tester.pump();
    await tester.pump(const Duration(seconds: 3));
  }

  testWidgets('a dashboard left for another page checks nothing until it '
      'returns, then each shown service once', (tester) async {
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(app(page(active)));
    await settle(tester);
    expect(asked, unorderedEquals(shownIds));
    expect(phases(tester).values, everyElement('fresh'));

    asked.clear();
    active.value = false;
    await tester.pump();
    for (final (proxy, media) in [('NodeB', 'NodeN'), ('NodeC', 'NodeO')]) {
      picks = {'Proxy': proxy, 'Media': media};
      publish(tester);
      await settle(tester);
    }
    expect(asked, isEmpty);

    active.value = true;
    await settle(tester);
    expect(asked, unorderedEquals(shownIds));
    expect(phases(tester).values, everyElement('fresh'));
  });

  testWidgets('returning on the route it left asks nothing again', (
    tester,
  ) async {
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(app(page(active)));
    await settle(tester);
    asked.clear();

    active.value = false;
    await settle(tester);
    expect(phases(tester).values, everyElement('fresh'));

    active.value = true;
    await tester.pump();
    resubscribe(tester);
    await settle(tester);
    expect(asked, isEmpty);
    expect(phases(tester).values, everyElement('fresh'));
  });

  testWidgets('a card scrolled off screen lets its services go and checks '
      'only the stale ones when it scrolls back', (tester) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      app(
        CustomScrollView(
          controller: controller,
          slivers: [
            SliverToBoxAdapter(
              child: Column(children: [card(), const SizedBox(height: 3000)]),
            ),
          ],
        ),
      ),
    );
    await settle(tester);
    expect(asked, unorderedEquals(shownIds));
    asked.clear();

    controller.jumpTo(2000);
    await tester.pump();
    picks = {...picks, 'Proxy': 'NodeB'};
    publish(tester);
    await settle(tester);
    expect(asked, isEmpty);
    expect(phases(tester), {
      ServiceTarget.google.id: 'empty',
      ServiceTarget.github.id: 'empty',
      ServiceTarget.youtube.id: 'fresh',
      ServiceTarget.chatgpt.id: 'fresh',
    });

    controller.jumpTo(0);
    await settle(tester);
    expect(
      asked,
      unorderedEquals([ServiceTarget.google.id, ServiceTarget.github.id]),
    );
    expect(phases(tester).values, everyElement('fresh'));
  });

  testWidgets('the service sheet keeps the card watching its first four', (
    tester,
  ) async {
    await tester.pumpWidget(app(Center(child: card())));
    await settle(tester);
    await tester.tap(find.byType(ServiceStatusCard));
    await tester.pumpAndSettle();
    expect(find.byType(ServiceStatusSheet), findsOneWidget);
    asked.clear();

    picks = {'Proxy': 'NodeB', 'Media': 'NodeN'};
    publish(tester);
    await settle(tester);
    expect(asked, unorderedEquals(shownIds));
    expect(phases(tester).values, everyElement('fresh'));
  });

  testWidgets('a focus that leaves and comes back keeps every service '
      'watched and asks nothing again', (tester) async {
    timingOut = {ServiceTarget.google.id};
    await tester.pumpWidget(app(shell(Center(child: card()))));
    await settle(tester);
    expect(phases(tester)[ServiceTarget.google.id], 'failed');
    asked.clear();

    final visible = containerOf(tester).read(appVisibleProvider.notifier);
    for (var cycle = 0; cycle < 3; cycle++) {
      visible.value = false;
      await settle(tester);
      visible.value = true;
      resubscribe(tester);
      await settle(tester);
    }
    expect(asked, isEmpty);
    expect(phases(tester)[ServiceTarget.google.id], 'failed');
  });

  testWidgets('a page covered while the app was away lets its services go '
      'once the app is back, and checks them as it shows again', (
    tester,
  ) async {
    await tester.pumpWidget(app(shell(Center(child: card()))));
    await settle(tester);
    holdRoute(tester);
    final visible = containerOf(tester).read(appVisibleProvider.notifier);

    visible.value = false;
    await settle(tester);
    unawaited(
      globalState.navigatorKey.currentState!.push(
        PageRouteBuilder<void>(
          transitionDuration: Duration.zero,
          reverseTransitionDuration: Duration.zero,
          pageBuilder: (_, _, _) => const SizedBox.expand(),
        ),
      ),
    );
    await settle(tester);
    visible.value = true;
    await settle(tester);

    asked.clear();
    picks = {'Proxy': 'NodeB', 'Media': 'NodeN'};
    publish(tester);
    await settle(tester);
    expect(asked, isEmpty);

    globalState.navigatorKey.currentState!.pop();
    await settle(tester);
    expect(asked, unorderedEquals(shownIds));
  });

  testWidgets('a return from the background retries a failed service once', (
    tester,
  ) async {
    timingOut = {ServiceTarget.google.id};
    await tester.pumpWidget(app(shell(Center(child: card()))));
    await settle(tester);
    asked.clear();

    final container = containerOf(tester);
    for (var cycle = 1; cycle <= 3; cycle++) {
      container.read(appVisibleProvider.notifier).value = false;
      await settle(tester);
      container.read(appVisibleProvider.notifier).value = true;
      resubscribe(tester);
      container.read(routeTrackerProvider.notifier).markResumed();
      await settle(tester);
      expect(asked, List.filled(cycle, ServiceTarget.google.id));
    }
  });

  testWidgets('a failed service is asked again once per return to the page, '
      'never twice while that check is in flight', (tester) async {
    timingOut = {ServiceTarget.google.id};
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(app(page(active)));
    await settle(tester);
    holdRoute(tester);
    asked.clear();

    for (var cycle = 1; cycle <= 2; cycle++) {
      active.value = false;
      await settle(tester);
      active.value = true;
      await settle(tester);
      expect(asked, List.filled(cycle, ServiceTarget.google.id));
    }

    hold = true;
    for (var cycle = 0; cycle < 2; cycle++) {
      active.value = false;
      await settle(tester);
      active.value = true;
      await settle(tester);
    }
    expect(asked, List.filled(3, ServiceTarget.google.id));
    for (final answer in held) {
      answer.complete();
    }
    await settle(tester);
    expect(asked, List.filled(3, ServiceTarget.google.id));
    expect(phases(tester)[ServiceTarget.google.id], 'failed');
  });

  testWidgets('a service forgotten while the page was away reads as checking '
      'as it returns, never as unchecked', (tester) async {
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(app(page(active)));
    await settle(tester);
    holdRoute(tester);

    active.value = false;
    await tester.pump();
    picks = {'Proxy': 'NodeB', 'Media': 'NodeN'};
    publish(tester);
    await settle(tester);

    active.value = true;
    final shown = <List<String>>[];
    for (var frame = 0; frame < 3; frame++) {
      await tester.pump(const Duration(milliseconds: 16));
      shown.add(labels(tester));
    }
    expect(shown.expand((cells) => cells), isNot(contains('Not checked')));
    expect(shown.last, everyElement('40 ms'));
  });

  testWidgets('a service forgotten off screen reads as checking as it '
      'scrolls back, never as unchecked', (tester) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      app(
        CustomScrollView(
          controller: controller,
          slivers: [
            SliverToBoxAdapter(
              child: Column(children: [card(), const SizedBox(height: 3000)]),
            ),
          ],
        ),
      ),
    );
    await settle(tester);
    holdRoute(tester);

    controller.jumpTo(2000);
    await tester.pump();
    picks = {...picks, 'Proxy': 'NodeB'};
    publish(tester);
    await settle(tester);

    controller.jumpTo(0);
    final shown = <List<String>>[];
    for (var frame = 0; frame < 3; frame++) {
      await tester.pump(const Duration(milliseconds: 16));
      shown.add(labels(tester));
    }
    expect(shown.expand((cells) => cells), isNot(contains('Not checked')));
    expect(shown.last, everyElement('40 ms'));
  });

  testWidgets('a service forgotten as the Core went away keeps reading as '
      'checking, and one never answered as unchecked', (tester) async {
    Widget coreRouted(Widget body) => TestApp(
      overrides: [
        viewSizeProvider.overrideWithBuild((_, _) => const Size(400, 800)),
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        ipQualityProvider.overrideWith((_, _) => Completer<IpQuality>().future),
      ],
      child: Scaffold(body: body),
    );
    await tester.pumpWidget(coreRouted(Center(child: card())));
    await settle(tester);
    expect(labels(tester), everyElement('Not checked'));
    expect(asked, isEmpty);

    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(coreRouted(page(active)));
    await tester.pump();
    resubscribe(tester);
    await settle(tester);
    expect(labels(tester), everyElement('40 ms'));

    active.value = false;
    await settle(tester);
    final container = containerOf(tester);
    container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
    await settle(tester);

    active.value = true;
    await settle(tester);
    expect(container.read(routeTrackerProvider).live, isFalse);
    expect(labels(tester), everyElement('Loading…'));
  });
}
