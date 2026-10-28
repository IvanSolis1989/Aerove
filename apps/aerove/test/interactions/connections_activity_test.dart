import 'dart:async';

import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/views/dashboard/widgets/connections_table.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';
import '../helpers/test_app.dart';

class _Core extends Mock implements CoreHandlerInterface {}

class _Polling extends ConsumerStatefulWidget {
  const _Polling({this.full = false});
  final bool full;
  @override
  ConsumerState<_Polling> createState() => _PollingState();
}

class _PollingState extends ConsumerState<_Polling>
    with
        WidgetsBindingObserver,
        ActivePollingMixin<_Polling>,
        RouteMotionHoldMixin<_Polling>,
        ConnectionsPolling<_Polling> {
  @override
  bool get pollsConnections => widget.full;
  @override
  Widget build(BuildContext context) => gateConnections(
    SizedBox(
      width: 100,
      height: 50,
      child: ValueListenableBuilder<int>(
        valueListenable: connectionCount,
        builder: (_, count, _) => Text('$count'),
      ),
    ),
  );
}

void main() {
  late _Core core;
  late ProviderContainer container;
  var reads = 0;
  var remaining = 0;
  setUp(() {
    reads = 0;
    remaining = 0;
    core = _Core();
    when(() => core.getConnectionCount()).thenAnswer((_) async {
      reads++;
      return remaining;
    });
    when(() => core.getConnections()).thenAnswer((_) async {
      reads++;
      return [
        for (var i = 0; i < remaining; i++)
          TrackerInfo(
            id: '$i',
            start: DateTime.utc(2026),
            metadata: const Metadata(network: 'tcp'),
            chains: const [],
            rule: '',
            rulePayload: '',
          ),
      ];
    });
    container = ProviderContainer(
      overrides: [
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        coreStatusProvider.overrideWithBuild((_, _) => CoreStatus.connected),
      ],
    );
  });
  tearDown(() => container.dispose());
  Future<void> mount(
    WidgetTester tester, {
    bool full = false,
    Widget? body,
  }) async {
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: Scaffold(body: body ?? _Polling(full: full)),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  Future<void> unmount(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 5));
  }

  for (final full in [false, true]) {
    testWidgets(
      'stopped ${full ? 'list' : 'count'} drains zero once and stays asleep through foreground',
      (tester) async {
        await mount(tester, full: full);
        expect(reads, 1);
        await tester.pump(const Duration(seconds: 20));
        expect(reads, 1);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pump();
        expect(reads, 1);
        await unmount(tester);
      },
    );
  }
  testWidgets(
    'stopped nonzero count drains to zero and start resumes immediately',
    (tester) async {
      remaining = 2;
      await mount(tester);
      expect(reads, 1);
      remaining = 0;
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(reads, 2);
      await tester.pump(const Duration(seconds: 10));
      expect(reads, 2);
      remaining = 4;
      container.read(runTimeProvider.notifier).value = 1;
      await tester.pump();
      expect(reads, 3);
      await tester.pump();
      expect(find.text('4'), findsOneWidget);
      await unmount(tester);
    },
  );
  testWidgets(
    'a card initially outside the viewport reads nothing until shown',
    (tester) async {
      final scroll = ScrollController();
      addTearDown(scroll.dispose);
      await mount(
        tester,
        body: SingleChildScrollView(
          controller: scroll,
          child: const Column(
            children: [
              SizedBox(height: 1600),
              _Polling(),
              SizedBox(height: 800),
            ],
          ),
        ),
      );
      expect(reads, 0);
      scroll.jumpTo(1300);
      await tester.pump();
      await tester.pump();
      expect(reads, 1);
      await unmount(tester);
    },
  );
  testWidgets(
    'hidden event counts neither rebuild nor schedule a frame and return latest',
    (tester) async {
      final shown = ValueNotifier(true);
      addTearDown(shown.dispose);
      var builds = 0;
      await mount(
        tester,
        body: ValueListenableBuilder<bool>(
          valueListenable: shown,
          builder: (_, active, child) => PageActivityScope(
            isActive: active,
            child: TickerMode(enabled: active, child: child!),
          ),
          child: ThrottledCount(
            provider: requestCountProvider,
            builder: (_, count) {
              builds++;
              return Text('events $count');
            },
          ),
        ),
      );
      shown.value = false;
      await tester.pump();
      final before = builds;
      container.read(requestCountProvider.notifier).value = 23;
      await tester.pump(const Duration(seconds: 1));
      expect(builds, before);
      expect(tester.binding.hasScheduledFrame, isFalse);
      shown.value = true;
      await tester.pump();
      expect(find.text('events 23'), findsOneWidget);
      await unmount(tester);
    },
  );
  testWidgets('count reads once per second and resumes with one timer', (
    tester,
  ) async {
    container.read(runTimeProvider.notifier).value = 1;
    remaining = 1;
    await mount(tester);
    expect(reads, 1);
    remaining = 2;
    await tester.pump(const Duration(milliseconds: 999));
    expect(reads, 1);
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump();
    expect(reads, 2);
    expect(find.text('2'), findsOneWidget);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump(const Duration(seconds: 20));
    expect(reads, 2);
    remaining = 3;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(reads, 3);
    await tester.pump();
    expect(find.text('3'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 999));
    expect(reads, 3);
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump();
    expect(reads, 4);
    await unmount(tester);
  });

  testWidgets(
    'an old count RPC cannot replace a resumed read or restart its timer',
    (tester) async {
      final old = Completer<int>();
      when(() => core.getConnectionCount()).thenAnswer((_) {
        reads++;
        return reads == 1 ? old.future : Future.value(9);
      });
      container.read(runTimeProvider.notifier).value = 1;
      await mount(tester);
      expect(reads, 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(reads, 2);
      await tester.pump();
      expect(find.text('9'), findsOneWidget);
      old.complete(99);
      await tester.pump();
      expect(find.text('99'), findsNothing);
      await tester.pump(const Duration(milliseconds: 999));
      expect(reads, 2);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(reads, 3);
      await unmount(tester);
    },
  );

  testWidgets(
    'app visibility synchronously cancels the pending count at 999ms',
    (tester) async {
      var builds = 0;
      await mount(
        tester,
        body: ThrottledCount(
          provider: requestCountProvider,
          builder: (_, count) {
            builds++;
            return Text('events $count');
          },
        ),
      );
      container.read(requestCountProvider.notifier).value = 1;
      await tester.pump(const Duration(milliseconds: 999));
      expect(find.text('events 0'), findsOneWidget);
      final before = builds;
      container.read(appVisibleProvider.notifier).value = false;
      container.read(requestCountProvider.notifier).value = 23;
      await tester.pump(const Duration(milliseconds: 1));
      expect(builds, before);
      expect(tester.binding.hasScheduledFrame, isFalse);
      await tester.pump(const Duration(seconds: 20));
      expect(builds, before);
      expect(tester.binding.hasScheduledFrame, isFalse);
      container.read(appVisibleProvider.notifier).value = true;
      await tester.pump();
      expect(find.text('events 23'), findsOneWidget);
      expect(builds, before + 1);
      container.read(requestCountProvider.notifier).value = 24;
      await tester.pump(const Duration(milliseconds: 999));
      expect(find.text('events 23'), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 1));
      expect(find.text('events 24'), findsOneWidget);
      await tester.pump(const Duration(seconds: 20));
      expect(builds, before + 2);
      expect(tester.binding.hasScheduledFrame, isFalse);
      await unmount(tester);
    },
  );
}
