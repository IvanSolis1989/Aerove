import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/keep_scope.dart';
import 'package:fl_clash/ui/scroll.dart';
import 'package:fl_clash/views/connection/requests.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/dashboard/widgets/widgets.dart';
import 'package:fl_clash/views/dns_queries.dart';
import 'package:fl_clash/views/logs.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _MockCore extends Mock implements CoreHandlerInterface {}

DnsQuery _query(String domain) => DnsQuery(
  domain: domain,
  type: 'A',
  answers: const ['1.1.1.1'],
  rcode: 'NOERROR',
  time: DateTime.utc(2026, 9, 18, 4, 30),
);

TrackerInfo _request(String host) => TrackerInfo(
  id: host,
  start: DateTime.utc(2026),
  metadata: Metadata(
    network: 'tcp',
    host: host,
    destinationIP: '1.1.1.1',
    destinationPort: '443',
  ),
  chains: const ['Proxy'],
  rule: 'DOMAIN',
  rulePayload: host,
);

/// The desktop home page: each tab keeps its own navigator alive, and only
/// the shown one is active and ticking.
class _Tab extends StatelessWidget {
  const _Tab({required this.index, required this.shown, required this.view});

  final int index;
  final ValueNotifier<int> shown;
  final Widget view;

  @override
  Widget build(BuildContext context) {
    final kept = KeepScope(
      child: Navigator(
        pages: [MaterialPage(child: view)],
        onDidRemovePage: (_) {},
      ),
    );
    return ValueListenableBuilder<int>(
      valueListenable: shown,
      child: kept,
      builder: (_, current, child) => PageActivityScope(
        isActive: current == index,
        child: TickerMode(enabled: current == index, child: child!),
      ),
    );
  }
}

/// The home page's wrapping of each tab, which leaves the page in sight
/// here so only the activity gate can keep it still.
class _HomeTab extends StatelessWidget {
  const _HomeTab({required this.active, required this.view});

  final ValueListenable<bool> active;
  final Widget view;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: active,
      child: view,
      builder: (_, isActive, child) => PageActivityScope(
        isActive: isActive,
        child: TickerMode(
          enabled: isActive,
          child: ExcludeFocus(excluding: !isActive, child: child!),
        ),
      ),
    );
  }
}

typedef _FeedPage = ({
  String name,
  Widget view,
  void Function(ProviderContainer container, String name) add,
});

final List<_FeedPage> _feedPages = [
  (
    name: 'LogsView',
    view: const LogsView(),
    add: (container, name) => container
        .read(logsProvider.notifier)
        .add(Log(payload: name, dateTime: '2024-01-01 12:00:00')),
  ),
  (
    name: 'RequestsView',
    view: const RequestsView(),
    add: (container, name) =>
        container.read(requestsProvider.notifier).addRequest(_request(name)),
  ),
  (
    name: 'DnsQueriesView',
    view: const DnsQueriesView(),
    add: (container, name) =>
        container.read(dnsQueriesProvider.notifier).addQuery(_query(name)),
  ),
];

int _built(String text) =>
    find.textContaining(text, skipOffstage: false).evaluate().length;

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        coreHandlerProvider.overrideWithValue(
          CoreController.scoped(_MockCore()),
        ),
      ],
    );
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1400, 1000));
  });

  tearDown(() => container.dispose());

  void setSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(1400, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  Future<void> teardownView(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 2));
  }

  /// Adds one batch, lets its refresh window pass and reports whether that
  /// asked for a frame and how many of its rows were built.
  Future<({bool frame, int built})> feedBatch(
    WidgetTester tester,
    String prefix,
    void Function(String name) add, {
    int size = 20,
  }) async {
    for (var item = 0; item < size; item++) {
      add('$prefix-$item');
    }
    await tester.pump(const Duration(milliseconds: 310));
    final frame = tester.binding.hasScheduledFrame;
    await tester.pump();
    return (frame: frame, built: _built('$prefix-'));
  }

  Future<ValueNotifier<bool>> pumpHomeTab(
    WidgetTester tester,
    _FeedPage page,
  ) async {
    setSize(tester);
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: _HomeTab(active: active, view: page.view),
        ),
      ),
    );
    page.add(container, 'seed-${page.name}');
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pumpAndSettle();
    expect(find.textContaining('seed-${page.name}'), findsWidgets);
    return active;
  }

  Map<String, int> countRebuilds() {
    final rebuilds = <String, int>{};
    debugOnRebuildDirtyWidget = (element, _) {
      final name = '${element.widget.runtimeType}';
      rebuilds[name] = (rebuilds[name] ?? 0) + 1;
    };
    addTearDown(() => debugOnRebuildDirtyWidget = null);
    return rebuilds;
  }

  testWidgets('flipping a home tab past a feed page rebuilds no page', (
    tester,
  ) async {
    for (final page in _feedPages) {
      final active = await pumpHomeTab(tester, page);
      final rebuilds = countRebuilds();
      for (var flip = 0; flip < 3; flip++) {
        active.value = false;
        await tester.pump();
        active.value = true;
        await tester.pump();
      }
      debugOnRebuildDirtyWidget = null;

      expect(rebuilds[page.name] ?? 0, 0, reason: page.name);
      await teardownView(tester);
    }
  });

  testWidgets('a feed page on a hidden home tab builds no rows and asks for '
      'no frames, and its first frame back shows the latest without '
      'rebuilding the page', (tester) async {
    for (final page in _feedPages) {
      final active = await pumpHomeTab(tester, page);
      active.value = false;
      await tester.pump();

      for (var batch = 0; batch < 3; batch++) {
        final result = await feedBatch(
          tester,
          'tab$batch',
          (name) => page.add(container, '$name.${page.name}'),
          size: 5,
        );
        expect(result.frame, isFalse, reason: '${page.name} batch $batch');
        expect(result.built, 0, reason: '${page.name} batch $batch');
      }

      final rebuilds = countRebuilds();
      active.value = true;
      await tester.pump();
      debugOnRebuildDirtyWidget = null;

      expect(find.textContaining('tab2-4.${page.name}'), findsWidgets);
      expect(rebuilds[page.name] ?? 0, 0, reason: page.name);
      await teardownView(tester);
    }
  });

  testWidgets('a logs page under an opaque route neither builds nor asks for '
      'frames, and shows the latest once uncovered', (tester) async {
    setSize(tester);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: LogsView()),
      ),
    );
    final logs = container.read(logsProvider.notifier);
    logs.add(const Log(payload: 'seed', dateTime: '2024-01-01 12:00:00'));
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pumpAndSettle();
    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    navigator.push(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: SizedBox.expand()),
      ),
    );
    await tester.pumpAndSettle();

    for (var batch = 0; batch < 5; batch++) {
      final result = await feedBatch(
        tester,
        'cov$batch',
        (name) => logs.add(Log(payload: name, dateTime: '2024-01-01 12:01:00')),
      );
      expect(result.frame, isFalse, reason: 'batch $batch asked for a frame');
      expect(result.built, 0, reason: 'batch $batch built hidden rows');
    }

    navigator.pop();
    await tester.pumpAndSettle();
    expect(find.text('cov4-19'), findsOneWidget);
    await teardownView(tester);
  });

  testWidgets('a log row rebuilt by a refresh keeps the payload it parsed', (
    tester,
  ) async {
    setSize(tester);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: LogsView()),
      ),
    );
    final logs = container.read(logsProvider.notifier);
    logs.add(const Log(payload: '[TCP] seed', dateTime: '2024-01-01 12:00:00'));
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pumpAndSettle();
    final row = find.ancestor(
      of: find.textContaining('seed'),
      matching: find.byType(LogItem),
    );
    Object? payload() {
      final body = find.descendant(
        of: row,
        matching: find.byWidgetPredicate(
          (w) => '${w.runtimeType}' == '_LogBody',
        ),
      );
      return (tester.widget(body) as dynamic).payload;
    }

    final item = tester.widget(row);
    final parsed = payload();
    logs.add(const Log(payload: 'next', dateTime: '2024-01-01 12:00:01'));
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pump();

    expect(find.textContaining('next'), findsOneWidget);
    expect(identical(tester.widget(row), item), isFalse);
    expect(identical(payload(), parsed), isTrue);
    await teardownView(tester);
  });

  testWidgets('a requests page under an opaque route builds no rows', (
    tester,
  ) async {
    setSize(tester);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: RequestsView()),
      ),
    );
    final requests = container.read(requestsProvider.notifier);
    requests.addRequest(_request('seed.test'));
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pumpAndSettle();
    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    navigator.push(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: SizedBox.expand()),
      ),
    );
    await tester.pumpAndSettle();

    for (var batch = 0; batch < 3; batch++) {
      final result = await feedBatch(
        tester,
        'req$batch',
        (name) => requests.addRequest(_request('$name.test')),
        size: 5,
      );
      expect(result.frame, isFalse, reason: 'batch $batch asked for a frame');
      expect(result.built, 0, reason: 'batch $batch built hidden rows');
    }

    navigator.pop();
    await tester.pumpAndSettle();
    expect(find.textContaining('req2-4.test'), findsWidgets);
    await teardownView(tester);
  });

  testWidgets('a dashboard requests table under an opaque route takes no '
      'snapshot, and shows the latest once uncovered', (tester) async {
    const size = Size(1220, 824);
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    container.read(viewSizeProvider.notifier).update((_) => size);
    final requests = container.read(requestsProvider.notifier);
    requests.addRequest(_request('seed.test'));
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(
          child: DashboardView(platform: SupportPlatform.MacOS),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.descendant(
        of: find.byType(ConnectionsTableCard),
        matching: find.text('Recent requests'),
      ),
    );
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.textContaining('seed.test'), findsWidgets);
    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    navigator.push(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: SizedBox.expand()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));

    for (var batch = 0; batch < 3; batch++) {
      final result = await feedBatch(
        tester,
        'card$batch',
        (name) => requests.addRequest(_request('$name.test')),
        size: 5,
      );
      expect(result.frame, isFalse, reason: 'batch $batch asked for a frame');
      expect(result.built, 0, reason: 'batch $batch built hidden rows');
    }

    navigator.pop();
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.textContaining('card2-4.test'), findsWidgets);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 5));
  });

  testWidgets('a DNS page kept alive on another tab rests, and its first '
      'frame back shows the latest queries', (tester) async {
    setSize(tester);
    final shown = ValueNotifier(0);
    final pages = PageController();
    addTearDown(shown.dispose);
    addTearDown(pages.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: PageView(
            controller: pages,
            physics: const NeverScrollableScrollPhysics(),
            children: [
              _Tab(index: 0, shown: shown, view: const DnsQueriesView()),
              _Tab(
                index: 1,
                shown: shown,
                view: const Scaffold(body: Text('other tab')),
              ),
            ],
          ),
        ),
      ),
    );
    final queries = container.read(dnsQueriesProvider.notifier);
    queries.addQuery(_query('seed.example'));
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pumpAndSettle();
    shown.value = 1;
    pages.jumpToPage(1);
    await tester.pumpAndSettle();

    for (var batch = 0; batch < 5; batch++) {
      final result = await feedBatch(
        tester,
        'hid$batch',
        (name) => queries.addQuery(_query('$name.example')),
      );
      expect(result.frame, isFalse, reason: 'batch $batch asked for a frame');
      expect(result.built, 0, reason: 'batch $batch built hidden rows');
    }

    shown.value = 0;
    pages.jumpToPage(0);
    await tester.pump();
    expect(find.textContaining('hid4-19.example'), findsWidgets);
    await teardownView(tester);
  });

  testWidgets('a hidden DNS page subscribed first never holds off the one '
      'in sight', (tester) async {
    setSize(tester);
    final shown = ValueNotifier(0);
    final pages = PageController();
    addTearDown(shown.dispose);
    addTearDown(pages.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: PageView(
            controller: pages,
            physics: const NeverScrollableScrollPhysics(),
            children: [
              _Tab(index: 0, shown: shown, view: const DnsQueriesView()),
              _Tab(index: 1, shown: shown, view: const DnsQueriesView()),
            ],
          ),
        ),
      ),
    );
    final queries = container.read(dnsQueriesProvider.notifier);
    queries.addQuery(_query('seed.example'));
    await tester.pump(const Duration(milliseconds: 310));
    await tester.pumpAndSettle();
    shown.value = 1;
    pages.jumpToPage(1);
    await tester.pumpAndSettle();

    for (var batch = 0; batch < 5; batch++) {
      for (var item = 0; item < 5; item++) {
        queries.addQuery(_query('two$batch-$item.example'));
      }
      await tester.pump(const Duration(milliseconds: 290));
      expect(find.textContaining('two$batch-'), findsNothing);
      await tester.pump(const Duration(milliseconds: 20));
      expect(
        find.textContaining('two$batch-').evaluate().length,
        5,
        reason: 'the page in sight missed batch $batch',
      );
    }
    await teardownView(tester);
  });

  testWidgets('a paused DNS page uncovered after more than its store holds '
      'keeps what it showed ahead of the latest', (tester) async {
    setSize(tester);
    final queries = container.read(dnsQueriesProvider.notifier);
    queries.value = FixedList<DnsQuery>(40);
    for (var item = 0; item < 60; item++) {
      queries.addQuery(_query('old$item.example'));
    }
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: DnsQueriesView()),
      ),
    );
    await tester.pumpAndSettle();
    final box = find.byType(ScrollToEndBox);
    await tester.drag(box, const Offset(0, -300));
    await tester.pumpAndSettle();
    expect(tester.widget<ScrollToEndBox>(box).enable, isFalse);
    DnsQueriesState state() => tester
        .widget<ValueListenableBuilder<DnsQueriesState>>(
          find.byType(ValueListenableBuilder<DnsQueriesState>),
        )
        .valueListenable
        .value;
    final before = state().dnsQueries;
    expect(before, hasLength(40));

    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    navigator.push(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: SizedBox.expand()),
      ),
    );
    await tester.pumpAndSettle();
    for (var item = 0; item < 50; item++) {
      queries.addQuery(_query('new$item.example'));
    }
    await tester.pump(const Duration(milliseconds: 310));
    final latest = container.read(dnsQueriesProvider).list;
    expect(latest, hasLength(40));
    expect(latest.any(before.contains), isFalse);

    navigator.pop();
    await tester.pumpAndSettle();

    // Nothing in the latest list anchors what was shown, so the paused view
    // keeps all of it and appends the latest after a gap.
    final after = state().dnsQueries;
    expect(after, hasLength(80));
    for (var index = 0; index < 40; index++) {
      expect(identical(after[index], before[index]), isTrue);
      expect(identical(after[40 + index], latest[index]), isTrue);
    }
    expect(tester.widget<ScrollToEndBox>(box).enable, isFalse);
    await teardownView(tester);
  });
}
