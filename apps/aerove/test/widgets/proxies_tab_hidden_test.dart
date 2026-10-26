import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/proxies/group_tabs.dart';
import 'package:fl_clash/views/proxies/tab.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

final _tabStateProvider = NotifierProvider<_TabStateNotifier, ProxiesTabState>(
  _TabStateNotifier.new,
);

class _TabStateNotifier extends Notifier<ProxiesTabState> {
  @override
  ProxiesTabState build() => _tabState(['B', 'C']);

  void set(List<String> names, [String current = 'B']) =>
      state = _tabState(names, current);
}

ProxiesTabState _tabState(List<String> names, [String current = 'B']) {
  return ProxiesTabState(
    groups: [
      for (final name in names) Group(type: GroupType.Selector, name: name),
    ],
    currentGroupName: current,
    proxyCardType: ProxyCardType.standard,
  );
}

final _groupNamesProvider = NotifierProvider<_GroupNamesNotifier, List<String>>(
  _GroupNamesNotifier.new,
);

class _GroupNamesNotifier extends Notifier<List<String>> {
  @override
  List<String> build() => ['B', 'C'];

  void set(List<String> names) => state = names;
}

ProviderContainer _container(Override tabState) {
  final profile = Profile.normal().copyWith(currentGroupName: 'B');
  final container = ProviderContainer(
    overrides: [
      currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
      profilesProvider.overrideWith(() => TestProfiles([profile])),
      tabState,
    ],
  );
  globalState.container = container;
  return container;
}

int _tabCount(WidgetTester tester, [Key? key]) => tester
    .widget<TabBarView>(
      find.descendant(
        of: key == null ? find.byType(ProxiesTabView) : find.byKey(key),
        matching: find.byType(TabBarView, skipOffstage: false),
      ),
    )
    .controller!
    .length;

void main() {
  group('with the group chain to itself', () {
    late ProviderContainer container;
    late ProviderSubscription<Profile?> profileSubscription;

    setUp(() {
      container = _container(
        proxiesTabStateProvider.overrideWith(
          (ref) => ref.watch(_tabStateProvider),
        ),
      );
      profileSubscription = container.listen(currentProfileProvider, (_, _) {});
    });

    tearDown(() {
      profileSubscription.close();
      container.dispose();
    });

    Future<ValueNotifier<bool>> pumpTabView(WidgetTester tester) async {
      final shown = ValueNotifier(true);
      addTearDown(shown.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            homeBuilder: (child) => Scaffold(body: child),
            child: ValueListenableBuilder<bool>(
              valueListenable: shown,
              builder: (_, enabled, child) =>
                  TickerMode(enabled: enabled, child: child!),
              child: const ProxiesTabView(),
            ),
          ),
        ),
      );
      await tester.pump();
      return shown;
    }

    Future<void> setGroups(
      WidgetTester tester,
      List<String> names, [
      String current = 'B',
    ]) async {
      container.read(_tabStateProvider.notifier).set(names, current);
      await tester.pump();
      await tester.pump();
    }

    testWidgets('groups added or dropped while hidden leave the tabs matching '
        'once shown', (tester) async {
      final shown = await pumpTabView(tester);
      expect(_tabCount(tester), 2);

      for (final names in [
        ['A', 'B', 'C', 'D'],
        ['C'],
        ['B', 'C', 'D'],
      ]) {
        shown.value = false;
        await tester.pump();
        await setGroups(tester, names);

        shown.value = true;
        await tester.pump();
        await tester.pumpAndSettle();

        expect(tester.takeException(), isNull);
        expect(_tabCount(tester), names.length);
        expect(
          find.descendant(of: find.byType(GroupTabs), matching: find.text('C')),
          findsOneWidget,
        );
      }
    });

    // The picker sheet's ProxiesView watches currentGroupDelayTesting, which
    // keeps the chain computing while the kept proxies page is hidden.
    testWidgets('new groups then only a new current group while hidden, with '
        'the chain kept awake, leave the tabs matching once shown', (
      tester,
    ) async {
      final awake = container.listen(
        currentGroupDelayTestingProvider,
        (_, _) {},
      );
      addTearDown(awake.close);
      final shown = await pumpTabView(tester);
      expect(_tabCount(tester), 2);

      shown.value = false;
      await tester.pump();
      await setGroups(tester, ['X', 'Y', 'Z']);
      await setGroups(tester, ['X', 'Y', 'Z'], 'Y');

      shown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), 3);
      for (final name in ['X', 'Y', 'Z']) {
        expect(
          find.descendant(
            of: find.byType(GroupTabs),
            matching: find.text(name),
          ),
          findsOneWidget,
        );
      }
    });
  });

  group('beside the dashboard picker sheet', () {
    late ProviderContainer container;
    late ProviderSubscription<Profile?> profileSubscription;

    setUp(() {
      container = _container(
        proxiesTabStateProvider.overrideWith((ref) {
          final names = ref.watch(_groupNamesProvider);
          final current = ref.watch(
            currentProfileProvider.select((state) => state?.currentGroupName),
          );
          return ProxiesTabState(
            groups: [
              for (final name in names)
                Group(type: GroupType.Selector, name: name),
            ],
            currentGroupName: current,
            proxyCardType: ProxyCardType.standard,
          );
        }),
      );
      profileSubscription = container.listen(currentProfileProvider, (_, _) {});
    });

    tearDown(() {
      profileSubscription.close();
      container.dispose();
    });

    // showProxiesSheet opens its own proxies view while the proxies page
    // stays mounted on a hidden home tab.
    testWidgets('the kept page matches the groups after the sheet saw them '
        'change and saved its pick', (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      const pageKey = ValueKey('page');
      final pageShown = ValueNotifier(true);
      final pickerOpen = ValueNotifier(false);
      addTearDown(pageShown.dispose);
      addTearDown(pickerOpen.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            homeBuilder: (child) => Scaffold(body: child),
            child: Column(
              children: [
                Expanded(
                  child: ValueListenableBuilder<bool>(
                    valueListenable: pageShown,
                    builder: (_, enabled, child) =>
                        TickerMode(enabled: enabled, child: child!),
                    child: const ProxiesTabView(key: pageKey),
                  ),
                ),
                Expanded(
                  child: ValueListenableBuilder<bool>(
                    valueListenable: pickerOpen,
                    builder: (_, open, _) =>
                        open ? const ProxiesTabView() : const SizedBox.shrink(),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pump();
      expect(_tabCount(tester, pageKey), 2);

      pageShown.value = false;
      await tester.pump();
      container.read(_groupNamesProvider.notifier).set(['X', 'Y', 'Z']);
      await tester.pump();

      pickerOpen.value = true;
      await tester.pump();
      await tester.pump();
      expect(container.read(currentProfileProvider)?.currentGroupName, 'X');

      pickerOpen.value = false;
      await tester.pump();
      pageShown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(_tabCount(tester, pageKey), 3);
    });
  });
}
