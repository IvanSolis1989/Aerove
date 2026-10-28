import 'dart:math' as math;
import 'dart:ui' show SemanticsAction;
import 'package:flutter/services.dart';
import 'package:flutter/semantics.dart'
    show CustomSemanticsAction, SemanticsNode;
import 'package:fl_clash/ui/icons/icons.dart';

import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/service_status.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/dashboard/edit.dart';
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:fl_clash/views/dashboard/widgets/widgets.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';
import '../../helpers/preferences.dart';
import '../../helpers/test_profiles.dart';

class _EditorServices extends ServiceStatus {
  final active = <ServiceTarget>{};
  var watches = 0;
  var unwatches = 0;
  var refreshes = 0;

  @override
  RoutedProbeState<ServiceTarget, ServiceCheck> build() =>
      const RoutedProbeState({});
  @override
  void watch(ServiceTarget target) {
    watches++;
    active.add(target);
  }

  @override
  void unwatch(ServiceTarget target) {
    unwatches++;
    active.remove(target);
  }

  @override
  void refresh(Iterable<ServiceTarget> targets) => refreshes++;
}

void main() {
  late ProviderContainer container;

  setUp(() async {
    await mockPreferences();
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        serviceStatusProvider.overrideWith(_EditorServices.new),
      ],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  List<DashboardWidget> saved() =>
      container.read(appSettingProvider).dashboardWidgets;

  void seed(List<DashboardWidget> widgets) {
    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(dashboardWidgets: widgets));
  }

  // The cards sway for as long as they are edited, so nothing settles.
  Future<void> settle(WidgetTester tester) async {
    for (var frame = 0; frame < 10; frame++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  Future<void> pumpDashboard(
    WidgetTester tester, {
    SupportPlatform platform = SupportPlatform.Android,
    Size size = const Size(412, 1400),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    container.read(viewSizeProvider.notifier).value = size;

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(child: DashboardView(platform: platform)),
      ),
    );
    await tester.pump();
  }

  Future<void> edit(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Edit dashboard'));
    await settle(tester);
  }

  Future<void> pumpEditor(
    WidgetTester tester, {
    SupportPlatform platform = SupportPlatform.Android,
    Size size = const Size(412, 1400),
  }) async {
    await pumpDashboard(tester, platform: platform, size: size);
    await edit(tester);
  }

  Future<void> teardown(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 5));
  }

  double top(WidgetTester tester, Finder finder) =>
      tester.getTopLeft(finder).dy;

  double opacityOf(WidgetTester tester, Finder card) => tester
      .widget<Opacity>(
        find.ancestor(of: card, matching: find.byType(Opacity)).first,
      )
      .opacity;

  ScrollPosition positionOf(WidgetTester tester) =>
      tester.state<ScrollableState>(find.byType(Scrollable).first).position;

  FocusNode vpnFocus(WidgetTester tester) => Focus.of(
    tester.element(
      find
          .descendant(
            of: find.byKey(const ValueKey('dashboard-vpn')),
            matching: find.byType(GlassText),
          )
          .last,
    ),
  );

  Future<bool> tabTo(WidgetTester tester, FocusNode focus) async {
    for (var step = 0; step < 40 && !focus.hasPrimaryFocus; step++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
    }
    return focus.hasPrimaryFocus;
  }

  List<SemanticsNode> semanticNodes(WidgetTester tester) {
    final nodes = <SemanticsNode>[];
    void visit(SemanticsNode node) {
      nodes.add(node);
      node.visitChildren((child) {
        visit(child);
        return true;
      });
    }

    visit(
      tester
          .binding
          .renderViews
          .first
          .owner!
          .semanticsOwner!
          .rootSemanticsNode!,
    );
    return nodes;
  }

  const controlWidgets = [
    DashboardWidget.currentOutbound,
    DashboardWidget.outboundMode,
    DashboardWidget.vpnButton,
    DashboardWidget.intranetIp,
  ];

  testWidgets(
    'keyboard Tab and Space cannot activate a live preview while editing',
    (tester) async {
      seed(controlWidgets);
      await pumpEditor(tester);
      final before = container.read(vpnSettingProvider);
      final focus = vpnFocus(tester);
      final reached = await tabTo(tester, focus);
      await tester.sendKeyEvent(LogicalKeyboardKey.space);
      await tester.pump();
      expect(container.read(vpnSettingProvider), before);
      expect(reached, isFalse);
      await teardown(tester);
    },
  );

  testWidgets(
    'keyboard focus on a normal live control is revoked on entering edit',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        seed(controlWidgets);
        await pumpDashboard(tester);
        final focus = vpnFocus(tester);
        expect(await tabTo(tester, focus), isTrue);
        final before = container.read(vpnSettingProvider);
        final edit = tester.getSemantics(find.byTooltip('Edit dashboard'));
        expect(edit.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
        tester.binding.renderViews.first.owner!.semanticsOwner!.performAction(
          edit.id,
          SemanticsAction.tap,
        );
        await tester.pump();
        await settle(tester);
        expect(find.byType(DashboardEditor), findsOneWidget);
        expect(vpnFocus(tester), same(focus));
        await tester.sendKeyEvent(LogicalKeyboardKey.space);
        await tester.pump();
        expect(container.read(vpnSettingProvider), before);
        expect(focus.hasPrimaryFocus, isFalse);
        await teardown(tester);
      } finally {
        semantics.dispose();
      }
    },
  );

  testWidgets(
    'keyboard editor actions and outer reorder semantics remain available',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        seed(controlWidgets);
        await pumpEditor(tester);
        final nodes = semanticNodes(tester);
        expect(
          nodes.where((node) => node.getSemanticsData().label == 'VPN'),
          isEmpty,
        );
        final vpn = nodes.singleWhere(
          (node) => node.getSemanticsData().label == 'Network controls',
        );
        expect(vpn.getSemanticsData().hasAction(SemanticsAction.tap), isFalse);
        final before = container.read(vpnSettingProvider);
        tester.binding.renderViews.first.owner!.semanticsOwner!.performAction(
          vpn.id,
          SemanticsAction.tap,
        );
        await tester.pump();
        expect(container.read(vpnSettingProvider), before);
        FocusNode actionFocus(String tooltip) => Focus.of(
          tester.element(
            find
                .descendant(
                  of: find.byTooltip(tooltip),
                  matching: find.byType(GlyphIcon),
                )
                .last,
          ),
        );
        expect(
          await tabTo(tester, actionFocus('Remove Network controls')),
          isTrue,
        );
        await tester.sendKeyEvent(LogicalKeyboardKey.space);
        await tester.pump();
        await settle(tester);
        expect(saved(), [DashboardWidget.currentOutbound]);
        expect(
          await tabTo(tester, actionFocus('Add Network controls')),
          isTrue,
        );
        await tester.sendKeyEvent(LogicalKeyboardKey.space);
        await tester.pump();
        await settle(tester);
        expect(saved(), controlWidgets);
        final outer = find
            .ancestor(
              of: find.byType(FusedNetworkControls),
              matching: find.byWidgetPredicate(
                (widget) =>
                    widget is Semantics &&
                    (widget.properties.customSemanticsActions?.isNotEmpty ??
                        false),
              ),
            )
            .first;
        final actions = tester
            .widget<Semantics>(outer)
            .properties
            .customSemanticsActions!;
        final startLabel = WidgetsLocalizations.of(
          tester.element(outer),
        ).reorderItemToStart;
        final action = actions.keys.singleWhere(
          (action) => action.label == startLabel,
        );
        final node = tester.getSemantics(outer);
        tester.binding.renderViews.first.owner!.semanticsOwner!.performAction(
          node.id,
          SemanticsAction.customAction,
          CustomSemanticsAction.getIdentifier(action),
        );
        await tester.pump();
        await settle(tester);
        expect(saved(), [
          DashboardWidget.outboundMode,
          DashboardWidget.vpnButton,
          DashboardWidget.intranetIp,
          DashboardWidget.currentOutbound,
        ]);
        expect(await tabTo(tester, actionFocus('Done')), isTrue);
        await tester.sendKeyEvent(LogicalKeyboardKey.space);
        await tester.pump();
        expect(find.byType(DashboardEditor), findsNothing);
        await teardown(tester);
      } finally {
        semantics.dispose();
      }
    },
  );

  testWidgets(
    'editing and dragging keep the normal fused cards and their owners',
    (tester) async {
      seed(const [
        DashboardWidget.currentOutbound,
        DashboardWidget.outboundMode,
        DashboardWidget.vpnButton,
        DashboardWidget.intranetIp,
        DashboardWidget.serviceStatus,
        DashboardWidget.connectionStats,
        DashboardWidget.profiles,
      ]);
      await pumpDashboard(tester);
      final services = tester.state(find.byType(ServiceStatusCard));
      final stats = tester.state(find.byType(ConnectionStatsCard));
      final countOwner =
          container.read(serviceStatusProvider.notifier) as _EditorServices;
      final watches = countOwner.watches;
      final controlsSize = tester.getSize(find.byType(FusedNetworkControls));
      final overviewSize = tester.getSize(find.byType(ServiceStatusCard));
      await edit(tester);
      expect(find.byType(FusedNetworkControls), findsOneWidget);
      expect(tester.getSize(find.byType(FusedNetworkControls)), controlsSize);
      expect(tester.getSize(find.byType(ServiceStatusCard)), overviewSize);
      expect(countOwner.watches, watches);
      expect(find.byType(OutboundModeCard), findsNothing);
      expect(find.byType(VpnCard), findsNothing);
      expect(find.byType(IntranetIpCard), findsNothing);
      expect(
        tester
            .widget<ServiceStatusCard>(find.byType(ServiceStatusCard))
            .includeStats,
        isTrue,
      );
      expect(tester.state(find.byType(ServiceStatusCard)), same(services));
      expect(tester.state(find.byType(ConnectionStatsCard)), same(stats));
      expect(container.read(serviceStatusProvider.notifier), same(countOwner));
      final before = saved();
      final gesture = await tester.startGesture(
        tester.getCenter(find.byType(FusedNetworkControls)),
      );
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
      expect(find.byType(FusedNetworkControls), findsOneWidget);
      expect(tester.state(find.byType(ServiceStatusCard)), same(services));
      await gesture.up();
      await settle(tester);
      final overviewGesture = await tester.startGesture(
        tester.getCenter(find.byType(ServiceStatusCard)),
      );
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
      expect(tester.state(find.byType(ServiceStatusCard)), same(services));
      expect(tester.state(find.byType(ConnectionStatsCard)), same(stats));
      expect(countOwner.watches, watches);
      await overviewGesture.up();
      await settle(tester);
      final vpn = container.read(vpnSettingProvider);
      final config = container.read(patchClashConfigProvider);
      for (final name in ['mode', 'vpn', 'lan']) {
        await tester.tapAt(
          tester.getCenter(find.byKey(ValueKey('dashboard-$name'))),
        );
      }
      final refresh = find.byWidgetPredicate(
        (widget) =>
            widget is DashboardIconButton &&
            widget.tooltip ==
                AppLocalizations.of(
                  tester.element(find.byType(ServiceStatusCard)),
                ).serviceCheckAll,
      );
      expect(refresh, findsOneWidget);
      await tester.tapAt(tester.getCenter(refresh));
      await tester.pump();
      expect(container.read(vpnSettingProvider), vpn);
      expect(container.read(patchClashConfigProvider), config);
      expect(countOwner.refreshes, 0);
      expect(countOwner.watches, watches);
      expect(saved(), before);
      await tester.tap(find.byTooltip('Done'));
      await tester.pump();
      expect(tester.state(find.byType(ServiceStatusCard)), same(services));
      expect(tester.state(find.byType(ConnectionStatsCard)), same(stats));
      await teardown(tester);
    },
  );

  testWidgets(
    'partial groups survive no-op editing and explicit restoration persists the whole platform family',
    (tester) async {
      const raw = [
        DashboardWidget.profiles,
        DashboardWidget.intranetIp,
        DashboardWidget.networkTakeover,
        DashboardWidget.currentOutbound,
        DashboardWidget.connectionStats,
        DashboardWidget.outboundMode,
      ];
      seed(raw);
      await pumpEditor(tester);
      expect(
        tester
            .widget<FusedNetworkControls>(find.byType(FusedNetworkControls))
            .members
            .toList(),
        [DashboardWidget.intranetIp, DashboardWidget.outboundMode],
      );
      expect(find.byType(ServiceStatusCard), findsNothing);
      expect(find.byTooltip('Add VPN'), findsNothing);
      expect(find.byTooltip('Add Network overview'), findsNothing);
      await tester.tap(find.byTooltip('Done'));
      await tester.pump();
      expect(saved(), raw);
      await edit(tester);
      container
          .read(appSettingProvider.notifier)
          .update((state) => state.copyWith(onlyStatisticsProxy: false));
      await tester.tap(find.byTooltip('Remove Network controls'));
      await tester.pump();
      final hidden = raw
          .where(
            (item) =>
                item != DashboardWidget.intranetIp &&
                item != DashboardWidget.outboundMode,
          )
          .toList();
      expect(saved(), hidden);
      expect(container.read(appSettingProvider).onlyStatisticsProxy, isFalse);
      await tester.tap(find.byTooltip('Back'));
      await tester.pump();
      expect(saved(), hidden);
      await edit(tester);
      await tester.scrollUntilVisible(
        find.byTooltip('Add Network controls'),
        100,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byTooltip('Add Network controls'));
      await settle(tester);
      final restored = [
        ...hidden,
        DashboardWidget.outboundMode,
        DashboardWidget.vpnButton,
        DashboardWidget.intranetIp,
      ];
      expect(saved(), restored);
      expect(container.read(appSettingProvider).onlyStatisticsProxy, isFalse);
      await tester.tap(find.byTooltip('Done'));
      await tester.pump();
      await tester.runAsync(() async {
        expect(
          await preferences.saveConfig(container.read(configProvider)),
          isTrue,
        );
        final readback = await preferences.getConfig();
        expect(readback!.appSettingProps.dashboardWidgets, restored);
        expect(readback.appSettingProps.onlyStatisticsProxy, isFalse);
      });
      await teardown(tester);
    },
  );

  testWidgets(
    'hiding overview releases its demand and restoring it creates one shared card',
    (tester) async {
      seed(const [
        DashboardWidget.currentOutbound,
        DashboardWidget.serviceStatus,
        DashboardWidget.connectionStats,
      ]);
      await pumpEditor(tester);
      final owner =
          container.read(serviceStatusProvider.notifier) as _EditorServices;
      final active = {...owner.active};
      final watches = owner.watches;
      expect(active, isNotEmpty);
      await tester.tap(find.byTooltip('Remove Network overview'));
      await tester.pump();
      await settle(tester);
      expect(saved(), [DashboardWidget.currentOutbound]);
      expect(owner.active, isEmpty);
      expect(owner.unwatches, active.length);
      expect(find.byTooltip('Add Network overview'), findsOneWidget);
      expect(find.byTooltip('Add Connection stats'), findsNothing);
      await tester.tap(find.byTooltip('Add Network overview'));
      await tester.pump();
      await settle(tester);
      expect(owner.active, active);
      expect(owner.watches, watches + active.length);
      expect(owner.refreshes, 0);
      expect(find.byType(ServiceStatusCard), findsOneWidget);
      expect(find.byType(ConnectionStatsCard), findsOneWidget);
      await teardown(tester);
    },
  );

  testWidgets(
    'dragging a partial control group moves only its existing raw members',
    (tester) async {
      seed(const [
        DashboardWidget.profiles,
        DashboardWidget.intranetIp,
        DashboardWidget.networkTakeover,
        DashboardWidget.currentOutbound,
        DashboardWidget.connectionStats,
        DashboardWidget.outboundMode,
      ]);
      await pumpEditor(tester);
      final controls = find.byType(FusedNetworkControls);
      final rise =
          top(tester, controls) - top(tester, find.byType(ProfilesCard)) - 10;
      final gesture = await tester.startGesture(tester.getCenter(controls));
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
      for (var step = 0; step < 4; step++) {
        await gesture.moveBy(Offset(0, -rise / 4));
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(find.byType(FusedNetworkControls), findsOneWidget);
      await gesture.up();
      await settle(tester);
      expect(saved(), [
        DashboardWidget.intranetIp,
        DashboardWidget.outboundMode,
        DashboardWidget.profiles,
        DashboardWidget.networkTakeover,
        DashboardWidget.currentOutbound,
        DashboardWidget.connectionStats,
      ]);
      await tester.tap(find.byTooltip('Done'));
      await tester.pump();
      expect(
        top(tester, controls),
        lessThan(top(tester, find.byType(ProfilesCard))),
      );
      await teardown(tester);
    },
  );

  testWidgets('edits the cards themselves, in order, above the ones to add', (
    tester,
  ) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.proxyGroups,
      DashboardWidget.connectionStats,
    ]);
    await pumpEditor(tester);

    expect(find.byType(DashboardEditor), findsOneWidget);
    final current = top(tester, find.byType(CurrentOutboundCard));
    final groups = top(tester, find.byType(ProxyGroupsCard));
    final stats = top(tester, find.byType(ConnectionStatsCard));
    expect(current, lessThan(groups));
    expect(groups, lessThan(stats));
    expect(stats, lessThan(top(tester, find.text('Available to add'))));
    expect(find.byTooltip('Add Memory info'), findsOneWidget);
    expect(find.byType(SortHandle), findsNothing);
    // The desktop-only card has no row on a phone.
    expect(find.byTooltip('Add Traffic takeover'), findsNothing);
    expect(tester.takeException(), isNull);

    await teardown(tester);
  });

  testWidgets('the current outbound holds the start button and stays', (
    tester,
  ) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
    ]);
    await pumpEditor(tester);

    final removeBadges = find.byWidgetPredicate(
      (widget) =>
          widget is Tooltip && (widget.message?.startsWith('Remove ') ?? false),
    );
    expect(removeBadges, findsNWidgets(2));
    expect(find.byTooltip('Remove Memory info'), findsOneWidget);
    expect(find.byTooltip('Remove Profile'), findsOneWidget);

    await teardown(tester);
  });

  testWidgets('editing while the proxy runs keeps every card in its place', (
    tester,
  ) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.connectionStats,
      DashboardWidget.memoryInfo,
    ]);
    container.read(runTimeProvider.notifier).value = 1;
    await pumpDashboard(tester);
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -200));
    await tester.pump();
    await edit(tester);

    expect(find.byType(ErrorWidget), findsNothing);
    expect(positionOf(tester).maxScrollExtent, lessThan(1400));
    expect(
      top(tester, find.byType(CurrentOutboundCard)),
      lessThan(top(tester, find.byType(ConnectionStatsCard))),
    );

    await teardown(tester);
  });

  testWidgets('a removed card saves at once, goes out before its room closes '
      'and can come back', (tester) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
    ]);
    await pumpEditor(tester);
    final profiles = top(tester, find.byType(ProfilesCard));

    await tester.tap(find.byTooltip('Remove Memory info'));
    await tester.pump();

    expect(saved(), const [
      DashboardWidget.currentOutbound,
      DashboardWidget.profiles,
    ]);
    final memory = find.byType(MemoryInfo);
    await tester.pump(const Duration(milliseconds: 100));
    expect(opacityOf(tester, memory), inExclusiveRange(0, 1));
    expect(top(tester, find.byType(ProfilesCard)), closeTo(profiles, 4));

    await tester.pump(const Duration(milliseconds: 200));
    expect(opacityOf(tester, memory), 0);
    expect(top(tester, find.byType(ProfilesCard)), lessThan(profiles - 4));

    await settle(tester);
    expect(find.byType(MemoryInfo), findsNothing);
    expect(
      top(tester, find.byTooltip('Add Memory info')),
      greaterThan(top(tester, find.text('Available to add'))),
    );

    await tester.tap(find.byTooltip('Add Memory info'));
    await settle(tester);

    expect(saved(), const [
      DashboardWidget.currentOutbound,
      DashboardWidget.profiles,
      DashboardWidget.memoryInfo,
    ]);
    expect(
      top(tester, find.byType(MemoryInfo)),
      greaterThan(top(tester, find.byType(ProfilesCard))),
    );
    expect(find.byTooltip('Add Memory info'), findsNothing);

    await teardown(tester);
  });

  testWidgets('an edit keeps the cards only another platform shows', (
    tester,
  ) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.networkTakeover,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
    ]);
    await pumpEditor(tester);

    await tester.tap(find.byTooltip('Remove Memory info'));
    await tester.pump();

    expect(saved(), const [
      DashboardWidget.currentOutbound,
      DashboardWidget.networkTakeover,
      DashboardWidget.profiles,
    ]);

    await teardown(tester);
  });

  testWidgets('an added card first opens its room in view, then comes in', (
    tester,
  ) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.outboundMode,
      DashboardWidget.vpnButton,
      DashboardWidget.intranetIp,
      DashboardWidget.connectionStats,
      DashboardWidget.profiles,
      DashboardWidget.proxyGroups,
      DashboardWidget.serviceStatus,
    ]);
    await pumpEditor(tester, size: const Size(412, 700));
    final position = positionOf(tester);
    // The one row left to add sits near the bottom edge; its card's room
    // opens above it and reaches past that edge.
    final row = find.byTooltip('Add Memory info');
    await tester.scrollUntilVisible(
      row,
      100,
      scrollable: find.byType(Scrollable).first,
    );
    position.jumpTo(
      math.min(
        position.maxScrollExtent,
        position.pixels + tester.getRect(row).top - 600,
      ),
    );
    await tester.pump();
    final before = position.pixels;
    final memory = find.byType(MemoryInfo);

    await tester.tap(row);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(opacityOf(tester, memory), 0);
    expect(position.pixels, greaterThan(before));

    await settle(tester);

    expect(opacityOf(tester, memory), 1);
    final card = tester.getRect(find.byType(MemoryInfo));
    expect(card.top, greaterThan(0));
    expect(card.bottom, lessThanOrEqualTo(700 - 16 + 1));
    expect(tester.takeException(), isNull);

    await teardown(tester);
  });

  testWidgets('a card held and dragged saves the new order and keeps its '
      'state', (tester) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.profiles,
      DashboardWidget.memoryInfo,
    ]);
    await pumpEditor(tester);
    final memory = tester.state(find.byType(MemoryInfo));
    final card = find.byType(MemoryInfo);
    final rise =
        top(tester, card) - top(tester, find.byType(ProfilesCard)) - 10;

    final gesture = await tester.startGesture(tester.getCenter(card));
    await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
    for (var step = 0; step < 4; step++) {
      await gesture.moveBy(Offset(0, -rise / 4));
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.byType(MemoryInfo), findsOneWidget);
    expect(tester.state(find.byType(MemoryInfo)), same(memory));
    await gesture.up();
    await settle(tester);

    expect(saved(), const [
      DashboardWidget.currentOutbound,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
    ]);
    expect(tester.state(find.byType(MemoryInfo)), same(memory));
    expect(tester.takeException(), isNull);

    await teardown(tester);
  });

  testWidgets('a short press neither drags nor opens a card', (tester) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
    ]);
    await pumpEditor(tester);

    await tester.tapAt(tester.getCenter(find.byType(MemoryInfo)));
    await settle(tester);

    expect(find.byType(MemoryDetailSheet), findsNothing);
    expect(saved(), const [
      DashboardWidget.currentOutbound,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
    ]);

    await teardown(tester);
  });

  testWidgets('editing keeps the cards where they stood, state and all', (
    tester,
  ) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.connectionStats,
      DashboardWidget.memoryInfo,
      DashboardWidget.profiles,
      DashboardWidget.proxyGroups,
    ]);
    await pumpDashboard(tester, size: const Size(412, 700));
    final position = positionOf(tester);
    position.jumpTo(position.maxScrollExtent / 2);
    await tester.pump();
    final offset = position.pixels;
    final memory = tester.state(find.byType(MemoryInfo, skipOffstage: false));

    await edit(tester);

    expect(find.byType(DashboardEditor), findsOneWidget);
    expect(positionOf(tester), same(position));
    expect(position.pixels, offset);
    expect(
      tester.state(find.byType(MemoryInfo, skipOffstage: false)),
      same(memory),
    );

    await tester.tap(find.byTooltip('Done'));
    await tester.pump();

    expect(find.byType(DashboardEditor), findsNothing);
    expect(find.byTooltip('Remove Connection stats'), findsNothing);
    expect(find.byTooltip('Edit dashboard'), findsOneWidget);
    expect(position.pixels, offset);
    expect(
      tester.state(find.byType(MemoryInfo, skipOffstage: false)),
      same(memory),
    );

    await teardown(tester);
  });

  testWidgets('the scroll end holds still while the cards are edited', (
    tester,
  ) async {
    await pumpEditor(tester, size: const Size(412, 700));
    final position = positionOf(tester);
    final end = position.maxScrollExtent;
    expect(end, greaterThan(0));

    final gesture = await tester.startGesture(const Offset(200, 500));
    while (position.pixels < end) {
      await gesture.moveBy(const Offset(0, -40));
      await tester.pump(const Duration(milliseconds: 16));
      expect(position.maxScrollExtent, end);
    }
    await gesture.up();
    await tester.pump(const Duration(seconds: 2));
    expect(position.pixels, end);

    await teardown(tester);
  });

  testWidgets('a narrow desktop window edits its cards in the card column, '
      'held and dragged with a mouse too', (tester) async {
    seed(const [
      DashboardWidget.currentOutbound,
      DashboardWidget.networkTakeover,
      DashboardWidget.profiles,
    ]);
    await pumpEditor(
      tester,
      platform: SupportPlatform.MacOS,
      size: const Size(620, 1400),
    );

    expect(find.byType(DashboardEditor), findsOneWidget);
    expect(find.byTooltip('Add VPN'), findsNothing);
    final current = find.byType(CurrentOutboundCard);
    expect(tester.getSize(current).width, 560);
    expect(tester.getCenter(current).dx, closeTo(310, 0.01));

    final card = find.byType(ProfilesCard);
    final rise =
        top(tester, card) - top(tester, find.byType(NetworkTakeoverCard)) - 10;
    final gesture = await tester.startGesture(
      tester.getCenter(card),
      kind: PointerDeviceKind.mouse,
    );
    await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
    for (var step = 0; step < 4; step++) {
      await gesture.moveBy(Offset(0, -rise / 4));
      await tester.pump(const Duration(milliseconds: 100));
    }
    await gesture.up();
    await settle(tester);

    expect(saved(), const [
      DashboardWidget.currentOutbound,
      DashboardWidget.profiles,
      DashboardWidget.networkTakeover,
    ]);

    await tester.tap(find.byTooltip('Remove Traffic takeover'));
    await settle(tester);

    expect(saved(), isNot(contains(DashboardWidget.networkTakeover)));
    expect(find.byTooltip('Add Traffic takeover'), findsOneWidget);

    await teardown(tester);
  });

  testWidgets('a landscape phone gets the fixed grid without an edit mode', (
    tester,
  ) async {
    seed(const [DashboardWidget.currentOutbound, DashboardWidget.profiles]);
    await pumpDashboard(tester, size: const Size(915, 412));

    expect(find.byTooltip('Edit dashboard'), findsNothing);
    expect(find.byType(DashboardEditor), findsNothing);
    expect(find.byType(OutboundModeBar), findsOneWidget);

    await teardown(tester);
  });

  testWidgets('turning to the fixed grid ends editing for good', (
    tester,
  ) async {
    seed(const [DashboardWidget.currentOutbound, DashboardWidget.profiles]);
    await pumpEditor(tester);
    expect(find.byType(DashboardEditor), findsOneWidget);

    Future<void> resize(Size size) async {
      tester.view.physicalSize = size;
      container.read(viewSizeProvider.notifier).value = size;
      await settle(tester);
    }

    await resize(const Size(915, 412));
    expect(find.byType(DashboardEditor), findsNothing);
    await resize(const Size(412, 1400));
    expect(find.byType(DashboardEditor), findsNothing);
    expect(find.byTooltip('Edit dashboard'), findsOneWidget);

    await teardown(tester);
  });

  testWidgets('the desktop grid keeps its fixed layout without an edit mode', (
    tester,
  ) async {
    await pumpDashboard(
      tester,
      platform: SupportPlatform.MacOS,
      size: const Size(1220, 900),
    );

    expect(find.byTooltip('Edit dashboard'), findsNothing);
    expect(find.byType(DashboardEditor), findsNothing);

    await teardown(tester);
  });
}
