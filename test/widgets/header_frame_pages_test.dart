import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/config/advanced.dart';
import 'package:fl_clash/views/config/dns.dart';
import 'package:fl_clash/views/config/general.dart';
import 'package:fl_clash/views/config/rules.dart';
import 'package:fl_clash/views/config/scripts.dart';
import 'package:fl_clash/views/config/user_agents.dart';
import 'package:fl_clash/views/hotkey.dart';
import 'package:fl_clash/views/navigation.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/views.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../helpers/test_app.dart';
import '../helpers/test_database_providers.dart';
import '../helpers/test_profiles.dart';

const _phone = Size(390, 800);
const _desktop = Size(1400, 1000);
const _records = 60;
const _recordPages = {'requests', 'dns', 'logs', 'connections'};

class _Core extends Mock implements CoreHandlerInterface {}

TrackerInfo _tracker(int index) => TrackerInfo(
  id: 'request $index',
  start: DateTime.utc(2026, 10, 20, 22, 47, index % 60),
  metadata: Metadata(
    network: 'tcp',
    host: 'host$index.test',
    destinationIP: '1.1.1.1',
    destinationPort: '443',
    process: 'curl',
  ),
  chains: const ['Proxy'],
  rule: 'DOMAIN',
  rulePayload: 'host$index.test',
);

DnsQuery _dnsQuery(int index) => DnsQuery(
  domain: 'host$index.test',
  type: 'A',
  upstream: 'https://dns.test/dns-query',
  answers: const ['1.1.1.1'],
  rcode: 'NOERROR',
  delay: 12,
  time: DateTime.utc(2026, 10, 20, 22, 47, index % 60),
);

void _seedRecords(ProviderContainer container) {
  final requests = container.read(requestsProvider.notifier)
    ..value = FixedList<TrackerInfo>(500);
  final dnsQueries = container.read(dnsQueriesProvider.notifier)
    ..value = FixedList<DnsQuery>(500);
  final logs = container.read(logsProvider.notifier);
  for (var index = 0; index < _records; index++) {
    requests.addRequest(_tracker(index));
    dnsQueries.addQuery(_dnsQuery(index));
    logs.add(Log(payload: 'log $index', dateTime: '2026-10-20 22:47:$index'));
  }
}

/// The scroll views a [CommonScaffold] has no other vertical scroll view
/// between, which are the ones that can carry content under its header.
List<ScrollableState> _headerScrolls(WidgetTester tester) => [
  for (final state in tester.stateList<ScrollableState>(
    find.byType(Scrollable),
  ))
    if (state.widget.axis == Axis.vertical &&
        state.context.findAncestorStateOfType<CommonScaffoldState>() != null &&
        Scrollable.maybeOf(
              state.context,
              axis: Axis.vertical,
            )?.context.findAncestorStateOfType<CommonScaffoldState>() !=
            state.context.findAncestorStateOfType<CommonScaffoldState>())
      state,
];

Finder _scaffoldOf(BuildContext body) =>
    find.byWidget(body.findAncestorStateOfType<CommonScaffoldState>()!.widget);

ValueListenable<double> _progressOf(WidgetTester tester, BuildContext body) =>
    tester
        .widget<HeaderButtonGlassScope>(
          find.descendant(
            of: _scaffoldOf(body),
            matching: find.byType(HeaderButtonGlassScope),
          ),
        )
        .progress;

Finder get _frames => find.descendant(
  of: find.byType(HeaderButtonGlassScope),
  matching: find.byWidgetPredicate(
    (widget) =>
        widget is LiquidGlassFade ||
        widget is FadeTransition && widget.child is DecoratedBox,
  ),
);

Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 600));
  await tester.pump();
}

Future<void> _pumpPage(
  WidgetTester tester,
  ProviderContainer container,
  Widget page,
  Size size,
) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  container.read(viewSizeProvider.notifier).update((_) => size);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(child: page),
    ),
  );
  await tester.pump(const Duration(milliseconds: 301));
  await _settle(tester);
}

/// Every page shows its header buttons bare until the reader moves content
/// up under the header, whichever way its list runs, and bare again once it
/// is back; a list held below the header never frames them.
Future<void> _expectFramesFollowContent(
  WidgetTester tester, {
  required bool scrolls,
}) async {
  for (final scope in tester.widgetList<HeaderButtonGlassScope>(
    find.byType(HeaderButtonGlassScope),
  )) {
    expect(scope.progress.value, 0, reason: 'framed before any scroll');
  }
  expect(_frames, findsNothing);
  final candidates = _headerScrolls(tester).where((state) {
    final position = state.position;
    final rect = tester.getRect(find.byWidget(state.widget));
    return position.maxScrollExtent - position.minScrollExtent > 120 &&
        rect.height > 200;
  }).toList();
  expect(candidates, scrolls ? isNotEmpty : anything);
  if (candidates.isEmpty) {
    return;
  }
  final scroll = candidates.first;
  final progress = _progressOf(tester, scroll.context);
  final rect = tester.getRect(find.byWidget(scroll.widget));
  final header = tester.getRect(
    find.descendant(
      of: _scaffoldOf(scroll.context),
      matching: find.byType(AppBar),
    ),
  );
  final underHeader = rect.top < header.bottom - 1;
  final start = scroll.position.pixels;
  await tester.dragFrom(rect.center, const Offset(0, -240));
  await _settle(tester);
  expect((scroll.position.pixels - start).abs(), greaterThan(24));
  expect(
    progress.value,
    underHeader ? 1 : 0,
    reason: underHeader
        ? 'content under the header is not framed'
        : 'a list held below the header frames it',
  );
  final buttons = find.descendant(
    of: find.byType(HeaderButtonGlassScope),
    matching: find.byWidgetPredicate(
      (widget) => widget is IconButton || widget is ButtonStyleButton,
    ),
  );
  expect(
    _frames,
    underHeader && buttons.evaluate().isNotEmpty ? findsWidgets : findsNothing,
  );
  await tester.dragFrom(rect.center, const Offset(0, 1200));
  await _settle(tester);
  await tester.pump(const Duration(seconds: 1));
  expect(progress.value, 0, reason: 'the frame outlives the content');
  await tester.pump(const Duration(milliseconds: 600));
  expect(_frames, findsNothing);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  setUp(() {
    final core = _Core();
    when(core.getConnections).thenAnswer(
      (_) async => [
        for (var index = 0; index < _records; index++) _tracker(index),
      ],
    );
    container = ProviderContainer(
      overrides: [
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        profilesProvider.overrideWith(TestProfiles.new),
        scriptsProvider.overrideWith(TestScripts.new),
        globalRulesProvider.overrideWith(TestGlobalRules.new),
        iconSetsProvider.overrideWith(TestIconSets.new),
        clashProvidersProvider.overrideWith(TestClashProviders.new),
      ],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  final items = navigation.getItems(openLogs: true, hasProxies: true);

  test('every page label reaches a navigation item checked below', () {
    expect(items.map((item) => item.label).toSet(), PageLabel.values.toSet());
  });

  final pages = <String, Widget Function(BuildContext context)>{
    for (final item in items) item.label.name: item.builder,
    'general settings': (_) => const GeneralView(),
    'dns config': (_) => const DnsView(),
    'advanced config': (_) => const AdvancedConfigView(),
    'theme': (_) => const ThemeView(),
    'backup and restore': (_) => const BackupAndRestore(),
    'hotkeys': (_) => const HotKeyView(),
    'access control': (_) => const AccessView(),
    'added rules': (_) => const AddedRulesView(),
    'scripts': (_) => const ScriptsView(),
    'user agents': (_) => const UserAgentsView(),
  };

  for (final size in [_phone, _desktop]) {
    final layout = size == _phone ? 'phone' : 'desktop';
    for (final MapEntry(key: name, value: builder) in pages.entries) {
      testWidgets('$name frames its header buttons only over content '
          '($layout)', (tester) async {
        _seedRecords(container);
        try {
          await _pumpPage(tester, container, Builder(builder: builder), size);
          await _expectFramesFollowContent(
            tester,
            scrolls: _recordPages.contains(name),
          );
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump(const Duration(seconds: 2));
        }
      });
    }
  }

  testWidgets('a keyword filter holds the records below the header and drops '
      'its frame', (tester) async {
    _seedRecords(container);
    try {
      await _pumpPage(tester, container, const DnsQueriesView(), _phone);
      final list = find.byType(Scrollable).first;
      await tester.drag(list, const Offset(0, -240));
      await _settle(tester);
      final progress = _progressOf(tester, tester.element(list));
      expect(progress.value, 1);
      tester
          .state<CommonScaffoldState>(find.byType(CommonScaffold))
          .addKeyword('A');
      await _settle(tester);
      expect(progress.value, 0);
      await tester.pump(const Duration(milliseconds: 600));
      expect(_frames, findsNothing);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 2));
    }
  });
}
