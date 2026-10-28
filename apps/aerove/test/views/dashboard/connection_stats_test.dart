import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/connection/tracker_info_list.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/connection/connections.dart';
import 'package:fl_clash/views/connection/requests.dart';
import 'package:fl_clash/views/dashboard/widgets/connection_stats.dart';
import 'package:fl_clash/views/dns_queries.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

class _MockCore extends Mock implements CoreHandlerInterface {}

const _mobileSize = Size(400, 800);

TrackerInfo _request(int index) {
  return TrackerInfo(
    id: '$index',
    start: DateTime.utc(2026),
    metadata: Metadata(
      network: 'tcp',
      host: 'host-$index.test',
      destinationIP: '1.1.1.1',
      destinationPort: '443',
      process: 'curl',
    ),
    chains: const ['Proxy'],
    rule: 'DOMAIN',
    rulePayload: 'host-$index.test',
  );
}

Finder _stat(String label) => find.descendant(
  of: find.byType(ConnectionStatsCard),
  matching: find.text(label),
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _MockCore core;
  late ProviderContainer container;

  setUp(() {
    core = _MockCore();
    when(() => core.getConnections()).thenAnswer((_) async => []);
    when(() => core.getConnectionCount()).thenAnswer((_) async => 0);
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        coreStatusProvider.overrideWithBuild((_, _) => CoreStatus.connected),
      ],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  Future<void> pumpCard(
    WidgetTester tester, {
    Size size = _mobileSize,
    Locale? locale,
  }) async {
    container.read(viewSizeProvider.notifier).value = size;
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          locale: locale,
          child: Scaffold(
            body: AuroraMotionActivity(
              enabled: false,
              child: ListView(children: const [ConnectionStatsCard()]),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  Future<void> teardownCard(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 2));
  }

  testWidgets('counts what the Core is holding open', (tester) async {
    when(() => core.getConnectionCount()).thenAnswer((_) async => 34);

    await pumpCard(tester);

    expect(find.text('34'), findsOneWidget);

    await teardownCard(tester);
  });

  testWidgets('the request and DNS counts follow on the throttled cadence', (
    tester,
  ) async {
    await pumpCard(tester);

    container.read(requestCountProvider.notifier).value = 1284;
    container.read(dnsQueryCountProvider.notifier).value = 42;
    await tester.pump();

    expect(find.text('1,284'), findsNothing);
    await tester.pump(const Duration(milliseconds: 999));
    expect(find.text('1,284'), findsNothing);
    expect(find.text('42'), findsNothing);
    container.read(requestCountProvider.notifier).value = 1285;
    container.read(dnsQueryCountProvider.notifier).value = 43;
    await tester.pump(const Duration(milliseconds: 1));

    expect(find.text('1,285'), findsOneWidget);
    expect(find.text('43'), findsOneWidget);

    await teardownCard(tester);
  });

  testWidgets(
    'all three counts keep their one second cadence during interaction',
    (tester) async {
      var live = 30;
      when(() => core.getConnectionCount()).thenAnswer((_) async => live);
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester);
      final gesture = await tester.startGesture(const Offset(200, 200));
      for (var second = 1; second <= 3; second++) {
        live = 30 + second;
        for (var event = 0; event < 100; event++) {
          container.read(requestCountProvider.notifier).value++;
          container.read(dnsQueryCountProvider.notifier).value++;
        }
        await tester.pump(const Duration(milliseconds: 999));
        expect(find.text('$live'), findsNothing);
        expect(find.text('${second * 100}'), findsNothing);
        await tester.pump(const Duration(milliseconds: 1));
        await tester.pump();
        expect(find.text('$live'), findsOneWidget);
        expect(find.text('${second * 100}'), findsNWidgets(2));
        expect(container.read(requestCountProvider), second * 100);
        expect(container.read(dnsQueryCountProvider), second * 100);
      }
      await gesture.up();
      await teardownCard(tester);
    },
  );

  testWidgets('a count too long for a third of a phone is shortened in any '
      'locale', (tester) async {
    when(() => core.getConnectionCount()).thenAnswer((_) async => 99999);
    container.read(requestCountProvider.notifier).value = 123456;

    await pumpCard(tester, locale: const Locale('zh', 'CN'));

    expect(find.text('99,999'), findsOneWidget);
    expect(find.text('123K'), findsOneWidget);

    await teardownCard(tester);
  });

  testWidgets('every count opens its own list in a sheet', (tester) async {
    await pumpCard(tester);

    for (final (label, view) in [
      ('Live connections', ConnectionsView),
      ('Recent requests', RequestsView),
      ('DNS queries', DnsQueriesView),
    ]) {
      await tester.tap(_stat(label));
      await tester.pumpAndSettle();

      expect(find.byType(view), findsOneWidget);
      expect(
        tester.getTopLeft(find.byType(SheetDragHandle)).dy,
        closeTo(_mobileSize.height * (1 - snapSheetDetents.first), 1),
      );

      await tester.tapAt(const Offset(200, 20));
      await tester.pumpAndSettle();
      expect(find.byType(view), findsNothing);
    }

    await teardownCard(tester);
  });

  testWidgets('the requests sheet keeps the newest request at its top while '
      'it is dragged and requests arrive', (tester) async {
    final notifier = container.read(requestsProvider.notifier);
    var next = 0;
    while (next < 200) {
      notifier.addRequest(_request(next++));
    }
    await pumpCard(tester);
    await tester.tap(_stat('Recent requests'));
    await tester.pumpAndSettle();
    final list = find.descendant(
      of: find.byType(TrackerInfoList),
      matching: find.byType(Scrollable),
    );
    ScrollPosition position() =>
        tester.state<ScrollableState>(list.first).position;

    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(SheetDragHandle)),
    );
    for (var frame = 0; frame < 40; frame++) {
      if (frame % 10 == 0) {
        for (var i = 0; i < 5; i++) {
          notifier.addRequest(_request(next++));
        }
      }
      await gesture.moveBy(const Offset(0, -6));
      await tester.pump(const Duration(milliseconds: 16));
      expect(position().pixels, position().maxScrollExtent);
    }
    await gesture.up();
    await tester.pumpAndSettle();

    expect(position().pixels, position().maxScrollExtent);
    expect(find.textContaining('host-${next - 1}.test'), findsWidgets);

    await teardownCard(tester);
  });
}
