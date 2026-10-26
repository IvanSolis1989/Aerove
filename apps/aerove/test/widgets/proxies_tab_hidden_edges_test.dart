import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/views/proxies/group_tabs.dart';
import 'package:fl_clash/views/proxies/tab.dart';
import 'package:flutter/widgets.dart' as widgets;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

final _namesProvider = NotifierProvider<_NamesNotifier, List<String>>(
  _NamesNotifier.new,
);

class _NamesNotifier extends Notifier<List<String>> {
  @override
  List<String> build() => ['B', 'C'];

  void set(List<String> names) => state = names;
}

/// Emulates queryProvider(QueryTag.proxies) filtering group names.
final _queryProvider = NotifierProvider<_QueryNotifier, String>(
  _QueryNotifier.new,
);

class _QueryNotifier extends Notifier<String> {
  @override
  String build() => '';

  void set(String value) => state = value;
}

var _withProxies = false;

ProviderContainer _container() {
  final profile = Profile.normal().copyWith(currentGroupName: 'B');
  final container = ProviderContainer(
    overrides: [
      currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
      profilesProvider.overrideWith(() => TestProfiles([profile])),
      proxiesTabStateProvider.overrideWith((ref) {
        final names = ref.watch(_namesProvider);
        final query = ref.watch(_queryProvider);
        final current = ref.watch(
          currentProfileProvider.select((state) => state?.currentGroupName),
        );
        return ProxiesTabState(
          groups: [
            for (final name in names)
              if (name.contains(query))
                Group(
                  type: GroupType.Selector,
                  name: name,
                  all: _withProxies
                      ? [
                          for (var i = 0; i < 12; i++)
                            Proxy(name: '$name-p$i', type: 'ss'),
                        ]
                      : const [],
                ),
          ],
          currentGroupName: current,
          proxyCardType: ProxyCardType.standard,
        );
      }),
    ],
  );
  globalState.container = container;
  return container;
}

const _pageKey = ValueKey('page');

TabBarView _tabBarView(WidgetTester tester) => tester.widget<TabBarView>(
  find.descendant(
    of: find.byKey(_pageKey),
    matching: find.byType(TabBarView, skipOffstage: false),
  ),
);

int _tabCount(WidgetTester tester) => _tabBarView(tester).controller!.length;

int _childCount(WidgetTester tester) => _tabBarView(tester).children.length;

int _tabIndex(WidgetTester tester) => _tabBarView(tester).controller!.index;

void main() {
  late ProviderContainer container;
  late ProviderSubscription<Profile?> profileSubscription;

  setUp(() {
    _withProxies = false;
    container = _container();
    profileSubscription = container.listen(currentProfileProvider, (_, _) {});
  });

  tearDown(() {
    profileSubscription.close();
    container.dispose();
  });

  Future<
    ({
      ValueNotifier<bool> shown,
      ValueNotifier<Locale> locale,
      ValueNotifier<bool> picker,
    })
  >
  pumpPage(WidgetTester tester, {bool initiallyShown = true}) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final shown = ValueNotifier(initiallyShown);
    final locale = ValueNotifier(const Locale('en'));
    final picker = ValueNotifier(false);
    addTearDown(shown.dispose);
    addTearDown(locale.dispose);
    addTearDown(picker.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: ValueListenableBuilder<Locale>(
          valueListenable: locale,
          builder: (_, value, _) => TestApp(
            locale: value,
            homeBuilder: (child) => Scaffold(body: child),
            child: Column(
              children: [
                Expanded(
                  child: ValueListenableBuilder<bool>(
                    valueListenable: shown,
                    builder: (_, enabled, child) =>
                        TickerMode(enabled: enabled, child: child!),
                    child: const ProxiesTabView(key: _pageKey),
                  ),
                ),
                Expanded(
                  child: ValueListenableBuilder<bool>(
                    valueListenable: picker,
                    builder: (_, open, _) =>
                        open ? const ProxiesTabView() : const SizedBox.shrink(),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    return (shown: shown, locale: locale, picker: picker);
  }

  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 4; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
  }

  group('hidden rebuild while paused', () {
    testWidgets('E1 app language changes while the page is hidden after its '
        'groups changed', (tester) async {
      final page = await pumpPage(tester);
      expect(_tabCount(tester), 2);

      page.shown.value = false;
      await tester.pump();
      container.read(_namesProvider.notifier).set(['X', 'Y', 'Z']);
      await settle(tester);

      // Settings > language while the proxies page sits on a hidden tab.
      page.locale.value = const Locale('ja');
      await settle(tester);
      await tester.pump(const Duration(milliseconds: 300));

      final hiddenError = tester.takeException();
      final hiddenTabs = _tabCount(tester);
      final hiddenChildren = _childCount(tester);

      page.shown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();
      final shownError = tester.takeException();
      expect(hiddenError, isNull);
      expect(hiddenTabs, hiddenChildren);
      expect(shownError, isNull);
      expect(_tabCount(tester), 3);
    });

    testWidgets('E2 groups shrink while hidden, then the app language '
        'changes', (tester) async {
      container.read(_namesProvider.notifier).set(['A', 'B', 'C']);
      final page = await pumpPage(tester);
      expect(_tabCount(tester), 3);

      page.shown.value = false;
      await tester.pump();
      container.read(_namesProvider.notifier).set(['B']);
      await settle(tester);
      page.locale.value = const Locale('zh', 'CN');
      await settle(tester);
      await tester.pump(const Duration(milliseconds: 300));
      final hiddenError = tester.takeException();

      page.shown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();
      expect(hiddenError, isNull);
      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), 1);
    });
  });

  group('lifecycle', () {
    testWidgets('E3 rotation / split-screen resize while hidden after the '
        'groups changed', (tester) async {
      final page = await pumpPage(tester);
      page.shown.value = false;
      await tester.pump();
      container.read(_namesProvider.notifier).set(['X', 'Y', 'Z']);
      await settle(tester);
      tester.view.physicalSize = const Size(1200, 800);
      await settle(tester);
      tester.view.physicalSize = const Size(500, 1100);
      await settle(tester);
      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), _childCount(tester));

      page.shown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), 3);
    });

    testWidgets('E4 page first built while hidden (process recreation on '
        'another tab) with groups arriving later', (tester) async {
      container.read(_namesProvider.notifier).set([]);
      final page = await pumpPage(tester, initiallyShown: false);
      container.read(_namesProvider.notifier).set(['A', 'B']);
      await settle(tester);
      container.read(_namesProvider.notifier).set(['A', 'B', 'C']);
      await settle(tester);
      expect(tester.takeException(), isNull);

      page.shown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), 3);
      expect(_childCount(tester), 3);
    });

    testWidgets('E5 quick tab switching with changes between every flip', (
      tester,
    ) async {
      final page = await pumpPage(tester);
      final steps = [
        ['A'],
        ['A', 'B', 'C', 'D'],
        ['C', 'D'],
        ['X', 'Y', 'Z'],
      ];
      for (final names in steps) {
        page.shown.value = false;
        await tester.pump();
        container.read(_namesProvider.notifier).set(names);
        await tester.pump();
        page.shown.value = true;
        await tester.pump();
        page.shown.value = false;
        await tester.pump();
        page.shown.value = true;
        await tester.pump();
        expect(tester.takeException(), isNull);
        expect(_tabCount(tester), names.length);
        expect(_childCount(tester), names.length);
      }
    });

    testWidgets('E6 covered by an opaque route while the groups change, then '
        'popped', (tester) async {
      await pumpPage(tester);
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('top')),
        ),
      );
      await tester.pumpAndSettle();
      container.read(_namesProvider.notifier).set(['X', 'Y', 'Z']);
      await settle(tester);
      navigator.pop();
      await tester.pump();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), 3);
      expect(_childCount(tester), 3);
    });
  });

  group('behaviour on return', () {
    // showProxiesSheet: the dashboard taps a group, the sheet clears the
    // page's search for itself and restores it when closed.
    testWidgets('E7 dashboard group tap with a search active on the page', (
      tester,
    ) async {
      container.read(_namesProvider.notifier).set(['HK1', 'HK2', 'US1']);
      container.read(_queryProvider.notifier).set('HK');
      final page = await pumpPage(tester);
      await settle(tester);
      expect(_tabCount(tester), 2);
      expect(_tabIndex(tester), 0);

      page.shown.value = false;
      await tester.pump();

      container
          .read(proxiesActionProvider.notifier)
          .updateCurrentGroupName('HK2');
      container.read(_queryProvider.notifier).set('');
      page.picker.value = true;
      await settle(tester);
      page.picker.value = false;
      container.read(_queryProvider.notifier).set('HK');
      await settle(tester);

      page.shown.value = true;
      await tester.pump();
      await tester.pumpAndSettle();
      final saved = container.read(currentProfileProvider)?.currentGroupName;
      final selected = _tabIndex(tester);
      expect(tester.takeException(), isNull);
      expect(_tabCount(tester), 2);
      // The kept page rebuilds its controller on the query change and lands
      // on the group tapped on the dashboard, as Lite's pager does.
      expect(selected, 1);
      expect(saved, 'HK2');
    });
  });

  group('cost', () {
    testWidgets('E9 rebuilds of a home-tab flip as _NavigationPage does it '
        '(PageActivityScope + TickerMode + ExcludeFocus)', (tester) async {
      _withProxies = true;
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final active = ValueNotifier(true);
      addTearDown(active.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            homeBuilder: (child) => Scaffold(body: child),
            child: ValueListenableBuilder<bool>(
              valueListenable: active,
              builder: (_, isActive, child) => PageActivityScope(
                isActive: isActive,
                child: TickerMode(
                  enabled: isActive,
                  child: ExcludeFocus(excluding: !isActive, child: child!),
                ),
              ),
              child: const ProxiesTabView(key: _pageKey),
            ),
          ),
        ),
      );
      await settle(tester);
      final counts = <String, int>{};
      widgets.debugOnRebuildDirtyWidget = (element, builtOnce) {
        final name = element.widget.runtimeType.toString();
        counts[name] = (counts[name] ?? 0) + 1;
      };
      addTearDown(() => widgets.debugOnRebuildDirtyWidget = null);
      var pageRebuilds = 0;
      var cardRebuilds = 0;
      for (var i = 0; i < 2; i++) {
        counts.clear();
        active.value = false;
        await tester.pump();
        pageRebuilds += counts['ProxiesTabView'] ?? 0;
        cardRebuilds += counts['ProxyCard'] ?? 0;
        counts.clear();
        active.value = true;
        await tester.pump();
        pageRebuilds += counts['ProxiesTabView'] ?? 0;
        cardRebuilds += counts['ProxyCard'] ?? 0;
      }
      widgets.debugOnRebuildDirtyWidget = null;
      expect(tester.takeException(), isNull);
      // A flip with nothing changed rebuilds no page and no card.
      expect(pageRebuilds, 0);
      expect(cardRebuilds, 0);
    });

    testWidgets('E8 rebuilds caused by a hide/show round trip with nothing '
        'changed', (tester) async {
      _withProxies = true;
      final page = await pumpPage(tester);
      await settle(tester);
      final counts = <String, int>{};
      widgets.debugOnRebuildDirtyWidget = (element, builtOnce) {
        final name = element.widget.runtimeType.toString();
        counts[name] = (counts[name] ?? 0) + 1;
      };
      addTearDown(() => widgets.debugOnRebuildDirtyWidget = null);
      page.shown.value = false;
      await tester.pump();
      final hide = Map.of(counts);
      counts.clear();
      page.shown.value = true;
      await tester.pump();
      final show = Map.of(counts);
      widgets.debugOnRebuildDirtyWidget = null;
      expect(find.byType(GroupTabs), findsOneWidget);
      expect(hide['ProxiesTabView'] ?? 0, 0);
      expect(show['ProxiesTabView'] ?? 0, 0);
      expect(show['ProxyCard'] ?? 0, 0);
    });
  });
}
