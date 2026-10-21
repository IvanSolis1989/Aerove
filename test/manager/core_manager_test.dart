import 'dart:async';
import 'dart:io';

import 'package:fl_clash/core/core.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/core_manager.dart';
import 'package:fl_clash/manager/status_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/route_state.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../helpers/app_paths.dart';
import '../helpers/test_profiles.dart';

class _MockCoreHandlerInterface extends Mock implements CoreHandlerInterface {}

const _nullProfileSetupState = SetupState(
  profileId: null,
  profileLastUpdateDate: null,
  profileType: ProfileType.file,
  extendType: ExtendType.standard,
  rules: [],
  proxyGroups: [],
  addedRules: [],
  script: null,
  overrides: ProfileOverrides(),
);

const _crash = CoreEvent(type: CoreEventType.crash, data: 'boom');

CoreEvent _geoUpdate({
  bool updating = false,
  bool skipped = false,
  String? error,
}) {
  return CoreEvent(
    type: CoreEventType.geoUpdate,
    data: <String, dynamic>{
      'type': 'MMDB',
      'updating': updating,
      'skipped': skipped,
      'error': error,
    },
  );
}

class _ApplyRecordingSetupAction extends SetupAction {
  int updates = 0;
  int applies = 0;

  @override
  Future<void> updateConfigDebounce() async {
    updates++;
  }

  @override
  void applyProfileDebounce({bool silence = false, bool force = false}) {
    applies++;
  }
}

class _GroupRecordingProxiesAction extends ProxiesAction {
  int groupReads = 0;
  bool succeed = true;

  @override
  Future<bool> updateGroups() async {
    groupReads++;
    return succeed;
  }
}

List<ExternalProvider> _listedProviders(String name) => [
  ExternalProvider(
    name: name,
    type: 'Proxy',
    count: 1,
    vehicleType: 'HTTP',
    updateAt: DateTime.utc(2026),
  ),
];

// onLoaded runs in the zone of coreEventManager's permanent subscription, so
// its debounce timer and the refresh it starts run on the real clock.
Future<void> _settleLoadedRefresh(WidgetTester tester) async {
  await tester.runAsync(
    () => Future<void>.delayed(const Duration(milliseconds: 600)),
  );
  await tester.pump(const Duration(milliseconds: 1));
}

_MockCoreHandlerInterface _coreInterface() {
  final coreInterface = _MockCoreHandlerInterface();
  when(() => coreInterface.startLog()).thenAnswer((_) {});
  when(() => coreInterface.stopLog()).thenAnswer((_) {});
  when(
    () => coreInterface.setUiTelemetryActive(any()),
  ).thenAnswer((_) async {});
  when(coreInterface.getLatestDelays).thenAnswer((_) async => const []);
  return coreInterface;
}

Future<ProviderContainer> _pumpCoreManager(
  WidgetTester tester,
  CoreHandlerInterface coreInterface, {
  List<Override> overrides = const [],
}) async {
  final container = ProviderContainer(
    overrides: [
      coreHandlerProvider.overrideWithValue(
        CoreController.scoped(coreInterface),
      ),
      ...overrides,
    ],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        navigatorKey: globalState.navigatorKey,
        localizationsDelegates: const [AppLocalizations.delegate],
        supportedLocales: AppLocalizations.delegate.supportedLocales,
        builder: (context, child) {
          globalState.measure = Measure.of(context, 1);
          return StatusManager(child: child!);
        },
        home: const CoreManager(child: SizedBox()),
      ),
    ),
  );
  return container;
}

// Loading.stop leaves a minDuration timer (up to 1000ms) pending after
// setupConfig resolves. Polling on the actual outcome instead of a fixed
// pump budget avoids racing it, and draining past it here keeps its
// callback from firing on a disposed container.
Future<void> _waitForSetupToSettle(
  WidgetTester tester,
  bool Function() condition,
) async {
  await tester.runAsync(() async {
    final deadline = DateTime.now().add(const Duration(seconds: 10));
    while (!condition() && DateTime.now().isBefore(deadline)) {
      await tester.pump();
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    final drainDeadline = DateTime.now().add(
      const Duration(milliseconds: 1100),
    );
    while (DateTime.now().isBefore(drainDeadline)) {
      await tester.pump();
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
  });
}

void main() {
  late Directory profileSwitchTempDir;

  setUpAll(() async {
    // The permanent event subscription must not capture a widget test's clock.
    expect(coreEventManager.hasListeners, isFalse);
    registerFallbackValue(const SetupParams(selectedMap: {}, testUrl: ''));
    await AppLocalizations.load(const Locale('en'));
    profileSwitchTempDir = Directory.systemTemp.createTempSync(
      'core_manager_test',
    );
    await useAppPaths(profileSwitchTempDir);
  });

  tearDownAll(() {
    try {
      profileSwitchTempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  testWidgets('duplicate crash events disconnect the core only once', (
    tester,
  ) async {
    final coreInterface = _MockCoreHandlerInterface();
    when(() => coreInterface.stopLog()).thenAnswer((_) {});
    final container = ProviderContainer(
      overrides: [
        coreHandlerProvider.overrideWithValue(
          CoreController.scoped(coreInterface),
        ),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: CoreManager(child: SizedBox())),
      ),
    );
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    final transitions = <CoreStatus>[];
    final subscription = container.listen<CoreStatus>(
      coreStatusProvider,
      (_, next) => transitions.add(next),
    );
    addTearDown(subscription.close);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);

    coreEventManager.sendEvent(_crash);
    coreEventManager.sendEvent(_crash);
    await tester.pump();

    expect(container.read(coreStatusProvider), CoreStatus.disconnected);
    expect(transitions, [CoreStatus.disconnected]);
    verifyNever(() => coreInterface.stop());

    await tester.pumpWidget(const SizedBox());
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  });

  testWidgets('a crash while the app is visible surfaces the message', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;

    coreEventManager.sendEvent(_crash);
    await tester.pump();

    expect(container.read(coreStatusProvider), CoreStatus.disconnected);
    expect(find.text('boom'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a crash is ignored when the core is not connected', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
    final transitions = <CoreStatus>[];
    final subscription = container.listen<CoreStatus>(
      coreStatusProvider,
      (_, next) => transitions.add(next),
    );
    addTearDown(subscription.close);

    coreEventManager.sendEvent(_crash);
    await tester.pump();

    expect(container.read(coreStatusProvider), CoreStatus.connecting);
    expect(transitions, isEmpty);
    expect(find.text('boom'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('the log stream follows the openLogs setting', (tester) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();

    verify(() => coreInterface.stopLog()).called(1);
    verifyNever(() => coreInterface.startLog());

    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(openLogs: true));
    await tester.pump();

    verify(() => coreInterface.startLog()).called(1);

    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(openLogs: false));
    await tester.pump();

    verify(() => coreInterface.stopLog()).called(1);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a pending enable converges to hidden without restarting logs', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final enable = Completer<void>();
    when(
      () => coreInterface.setUiTelemetryActive(true),
    ).thenAnswer((_) => enable.future);
    final container = await _pumpCoreManager(tester, coreInterface);
    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(openLogs: true));
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    container.read(appVisibleProvider.notifier).value = false;
    await tester.pump();
    clearInteractions(coreInterface);
    enable.complete();
    await tester.pump();

    verify(() => coreInterface.setUiTelemetryActive(false)).called(1);
    verifyNever(() => coreInterface.startLog());
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('dispose closes telemetry after an outstanding enable settles', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final enabled = Completer<void>();
    when(
      () => coreInterface.setUiTelemetryActive(true),
    ).thenAnswer((_) => enabled.future);
    final container = await _pumpCoreManager(tester, coreInterface);
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    clearInteractions(coreInterface);

    await tester.pumpWidget(const SizedBox.shrink());
    verify(() => coreInterface.stopLog()).called(1);
    verifyNever(() => coreInterface.setUiTelemetryActive(false));
    enabled.complete();
    await tester.pump();

    verify(() => coreInterface.setUiTelemetryActive(false)).called(1);
    verify(() => coreInterface.stopLog()).called(1);
    verifyNever(() => coreInterface.startLog());
    expect(tester.takeException(), isNull);
  });

  testWidgets('a failed resume refresh is retried on the next connection', (
    tester,
  ) async {
    final action = _GroupRecordingProxiesAction()..succeed = false;
    final coreInterface = _coreInterface();
    var providerReads = 0;
    when(() => coreInterface.getExternalProvider('remote')).thenAnswer((
      _,
    ) async {
      if (++providerReads == 1) throw StateError('temporarily unavailable');
      return null;
    });
    final container = await _pumpCoreManager(
      tester,
      coreInterface,
      overrides: [proxiesActionProvider.overrideWith(() => action)],
    );
    container.read(providersProvider.notifier).value = _listedProviders(
      'remote',
    );
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    container.read(appVisibleProvider.notifier).value = false;
    coreEventManager.sendEvent(
      const CoreEvent(type: CoreEventType.loaded, data: 'remote'),
    );
    await tester.pump();
    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump();
    expect(providerReads, 1);
    expect(action.groupReads, 1);
    await tester.pump(const Duration(seconds: 30));
    expect(providerReads, 1);
    expect(action.groupReads, 1);

    action.succeed = true;
    container.read(coreStatusProvider.notifier).value = CoreStatus.disconnected;
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    expect(providerReads, 2);
    expect(action.groupReads, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a loaded read spanning hide and resume is refreshed again', (
    tester,
  ) async {
    final action = _GroupRecordingProxiesAction();
    final coreInterface = _coreInterface();
    final first = Completer<ExternalProvider?>();
    var reads = 0;
    when(() => coreInterface.getExternalProvider('remote')).thenAnswer((_) {
      return ++reads == 1 ? first.future : Future<ExternalProvider?>.value();
    });
    final container = await _pumpCoreManager(
      tester,
      coreInterface,
      overrides: [proxiesActionProvider.overrideWith(() => action)],
    );
    try {
      container.read(providersProvider.notifier).value = _listedProviders(
        'remote',
      );
      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
      await tester.pump(const Duration(milliseconds: 1));
      coreEventManager.sendEvent(
        const CoreEvent(type: CoreEventType.loaded, data: 'remote'),
      );
      await tester.pump(const Duration(milliseconds: 1));
      expect(reads, 0);
      await _settleLoadedRefresh(tester);
      expect(reads, 1);
      container.read(appVisibleProvider.notifier).value = false;
      container.read(appVisibleProvider.notifier).value = true;
      await tester.pump(const Duration(milliseconds: 1));
      first.complete(null);
      await tester.pump(const Duration(milliseconds: 1));
      await _settleLoadedRefresh(tester);

      expect(reads, 2);
      expect(action.groupReads, 1);
      await tester.pump(const Duration(seconds: 30));
      expect(reads, 2);
      expect(action.groupReads, 1);
    } finally {
      if (!first.isCompleted) first.complete(null);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(milliseconds: 1));
    }
  });

  testWidgets('a burst of loaded events is read back in one pass', (
    tester,
  ) async {
    final action = _GroupRecordingProxiesAction();
    final coreInterface = _coreInterface();
    var listReads = 0;
    when(() => coreInterface.getExternalProviders()).thenAnswer((_) async {
      listReads++;
      return const <ExternalProvider>[];
    });
    final container = await _pumpCoreManager(
      tester,
      coreInterface,
      overrides: [proxiesActionProvider.overrideWith(() => action)],
    );
    try {
      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
      await tester.pump(const Duration(milliseconds: 1));
      for (var index = 0; index < 20; index++) {
        coreEventManager.sendEvent(
          CoreEvent(type: CoreEventType.loaded, data: 'provider-$index'),
        );
      }
      await tester.pump(const Duration(milliseconds: 1));
      expect(listReads, 0);
      expect(action.groupReads, 0);

      await _settleLoadedRefresh(tester);

      expect(listReads, 1);
      expect(action.groupReads, 1);
      verifyNever(() => coreInterface.getExternalProvider(any()));

      container.read(coreStatusProvider.notifier).value =
          CoreStatus.disconnected;
      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
      await tester.pump(const Duration(milliseconds: 1));
      expect(listReads, 1);
      expect(action.groupReads, 1);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(milliseconds: 1));
    }
  });

  testWidgets('an unanswered bulk read keeps the listed providers', (
    tester,
  ) async {
    final action = _GroupRecordingProxiesAction();
    final coreInterface = _coreInterface();
    final listed = _listedProviders('provider-0');
    final fresh = _listedProviders('provider-1');
    var listReads = 0;
    when(() => coreInterface.getExternalProviders()).thenAnswer((_) async {
      return ++listReads == 1 ? const <ExternalProvider>[] : fresh;
    });
    final container = await _pumpCoreManager(
      tester,
      coreInterface,
      overrides: [proxiesActionProvider.overrideWith(() => action)],
    );
    try {
      container.read(providersProvider.notifier).value = listed;
      container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
      await tester.pump(const Duration(milliseconds: 1));
      for (final name in const ['provider-0', 'provider-1']) {
        coreEventManager.sendEvent(
          CoreEvent(type: CoreEventType.loaded, data: name),
        );
      }
      await _settleLoadedRefresh(tester);

      expect(listReads, 1);
      expect(container.read(providersProvider), same(listed));
      expect(action.groupReads, 0);

      container.read(appVisibleProvider.notifier).value = false;
      container.read(appVisibleProvider.notifier).value = true;
      await tester.pump(const Duration(milliseconds: 1));

      expect(listReads, 2);
      expect(container.read(providersProvider), same(fresh));
      expect(action.groupReads, 1);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(milliseconds: 1));
    }
  });

  testWidgets('hidden UI events leave histories and delay results untouched', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    container.read(appVisibleProvider.notifier).value = false;
    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.log,
        data: {'LogLevel': 'info', 'Payload': 'hidden'},
      ),
    );
    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.dns,
        data: {
          'domain': 'hidden.test',
          'type': 'A',
          'answers': ['1.1.1.1'],
          'delay': 3,
          'time': '2026-09-18T04:30:01Z',
        },
      ),
    );
    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.request,
        data: {
          'id': 'hidden-request',
          'start': '2026-09-18T04:30:01Z',
          'metadata': <String, Object?>{},
          'chains': ['DIRECT'],
          'rule': 'MATCH',
          'rulePayload': '',
        },
      ),
    );
    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.requestTraffic,
        data: [
          {'id': 'hidden-request', 'upload': 40, 'download': 80},
        ],
      ),
    );
    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.delay,
        data: {'name': 'HK', 'url': 'https://delay.test', 'value': 9},
      ),
    );
    await tester.pump(const Duration(seconds: 6));

    expect(container.read(logsProvider).list, isEmpty);
    expect(container.read(dnsQueriesProvider).list, isEmpty);
    expect(container.read(dnsQueryCountProvider), 0);
    expect(container.read(requestsProvider).list, isEmpty);
    expect(container.read(requestCountProvider), 0);
    expect(container.read(delayDataSourceProvider), isEmpty);
    verifyNever(coreInterface.getProxies);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a resume replaces a stale failure with the latest Core result', (
    tester,
  ) async {
    const url = 'https://delay.test';
    final coreInterface = _coreInterface();
    var latest = const <Delay>[];
    when(coreInterface.getLatestDelays).thenAnswer((_) async => latest);
    final container = await _pumpCoreManager(
      tester,
      coreInterface,
      overrides: [
        proxiesActionProvider.overrideWith(_GroupRecordingProxiesAction.new),
      ],
    );
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    container.read(delayDataSourceProvider.notifier).setDelays(const [
      Delay(name: 'HK', url: url, value: -1),
      Delay(name: 'JP', url: url, value: -1),
      Delay(name: 'SG', url: url, value: -1),
    ]);
    container.read(appVisibleProvider.notifier).value = false;
    await tester.pump();
    latest = const [
      Delay(name: 'HK', url: url, value: 120),
      Delay(name: 'JP', url: url, value: -2),
    ];

    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump();

    expect(container.read(delayDataSourceProvider)[url], {
      'HK': 120,
      'JP': -1,
      'SG': -1,
    });
    verify(coreInterface.getLatestDelays).called(2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a dialer loop reported while hidden is shown once on return', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    container.read(appVisibleProvider.notifier).value = false;
    for (var i = 0; i < 3; i++) {
      coreEventManager.sendEvent(
        const CoreEvent(type: CoreEventType.dialerLoop, data: 'Front'),
      );
      await tester.pump();
      expect(find.textContaining('Front'), findsNothing);
      await tester.pump(const Duration(seconds: 4));
    }

    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump();

    expect(find.textContaining('Front'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('hiding cancels pending delay refresh and resumes groups once', (
    tester,
  ) async {
    final action = _GroupRecordingProxiesAction();
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(
      tester,
      coreInterface,
      overrides: [proxiesActionProvider.overrideWith(() => action)],
    );
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();
    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.delay,
        data: {'name': 'HK', 'url': 'https://delay.test', 'value': 9},
      ),
    );
    await tester.pump();
    container.read(appVisibleProvider.notifier).value = false;
    await tester.pump(const Duration(seconds: 6));
    expect(action.groupReads, 0);
    expect(container.read(delayDataSourceProvider), isEmpty);

    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump();
    expect(action.groupReads, 1);
    await tester.pump(const Duration(seconds: 6));
    expect(action.groupReads, 1);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('the log stream waits for the core and is restored on '
      'every connection', (tester) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(openLogs: true));
    await tester.pump();

    verifyNever(() => coreInterface.startLog());
    verifyNever(() => coreInterface.stopLog());

    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();

    verify(() => coreInterface.startLog()).called(1);

    container.read(coreStatusProvider.notifier).value = CoreStatus.disconnected;
    await tester.pump();
    container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
    await tester.pump();

    verifyNever(() => coreInterface.startLog());

    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();

    verify(() => coreInterface.startLog()).called(1);
    verifyNever(() => coreInterface.stopLog());

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a setting the Core patches live is not re-applied', (
    tester,
  ) async {
    final setupAction = _ApplyRecordingSetupAction();
    final container = await _pumpCoreManager(
      tester,
      _coreInterface(),
      overrides: [setupActionProvider.overrideWith(() => setupAction)],
    );

    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(mode: Mode.global, mixedPort: 8899));
    await tester.pump();

    expect(setupAction.updates, 1);
    expect(setupAction.applies, 0);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a setting only a setup carries re-applies the profile', (
    tester,
  ) async {
    final setupAction = _ApplyRecordingSetupAction();
    final container = await _pumpCoreManager(
      tester,
      _coreInterface(),
      overrides: [setupActionProvider.overrideWith(() => setupAction)],
    );

    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(hosts: const {'a.test': '1.1.1.1'}));
    await tester.pump();
    container
        .read(networkSettingProvider.notifier)
        .update(
          (state) => state.copyWith(appendSystemDns: !state.appendSystemDns),
        );
    await tester.pump();

    expect(setupAction.applies, 2);
    expect(setupAction.updates, 0);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('core logs are recorded for the logs view', (tester) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);

    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.log,
        data: {'LogLevel': 'info', 'Payload': 'hello'},
      ),
    );
    await tester.pump();

    expect(
      container.read(logsProvider).list.map((log) => log.payload),
      contains('hello'),
    );

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('core dns queries are recorded and counted', (tester) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);

    for (final domain in ['alpha.test', 'beta.test']) {
      coreEventManager.sendEvent(
        CoreEvent(
          type: CoreEventType.dns,
          data: {
            'domain': domain,
            'type': 'A',
            'answers': ['1.1.1.1'],
            'delay': 3,
            'time': '2026-09-18T04:30:01Z',
          },
        ),
      );
    }
    await tester.pump();

    expect(
      container.read(dnsQueriesProvider).list.map((query) => query.domain),
      ['alpha.test', 'beta.test'],
    );
    expect(container.read(dnsQueryCountProvider), 2);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('geo events are forwarded to the geo resource action', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);
    final key = GeoResource.MMDB.updatingKey;
    final subscription = container.listen<bool>(
      isUpdatingProvider(key),
      (_, _) {},
    );
    addTearDown(subscription.close);

    coreEventManager.sendEvent(_geoUpdate(updating: true));
    await tester.pump();

    expect(container.read(isUpdatingProvider(key)), isTrue);

    coreEventManager.sendEvent(_geoUpdate(error: 'background failure'));
    await tester.pump();

    expect(container.read(isUpdatingProvider(key)), isFalse);
    expect(find.text('background failure'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('route events reach the route tracker', (tester) async {
    final coreInterface = _coreInterface();
    final container = await _pumpCoreManager(tester, coreInterface);

    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.routeChanged,
        data: {
          'core-epoch': 7,
          'picks-version': 42,
          'picks': {'Proxy': 'HK-01'},
        },
      ),
    );
    await tester.pump();

    final route = container.read(routeTrackerProvider);
    expect(route.coreEpoch, 7);
    expect(route.picksVersion, 42);
    expect(route.picks, {'Proxy': 'HK-01'});
    expect(route.synced, isTrue);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('non-geo Core errors retain global notifications', (
    tester,
  ) async {
    final coreInterface = _coreInterface();
    await _pumpCoreManager(tester, coreInterface);

    coreEventManager.sendEvent(
      const CoreEvent(
        type: CoreEventType.log,
        data: {'LogLevel': 'error', 'Payload': 'core failure'},
      ),
    );
    await tester.pump();

    expect(find.text('core failure'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  group('profile switch failure', () {
    late Profile previous;
    late Profile next;
    List<Override> profileOverrides() => [
      initProvider.overrideWithBuild((_, _) => true),
      profilesProvider.overrideWith(() => TestProfiles([previous, next])),
      currentProfileIdProvider.overrideWithBuild((_, _) => previous.id),
      setupStateProvider.overrideWith((_, _) => _nullProfileSetupState),
    ];

    setUp(() {
      previous = Profile.normal(label: 'previous');
      next = Profile.normal(label: 'next');
    });

    testWidgets('keeps the selected profile when Core rejects it', (
      tester,
    ) async {
      final coreInterface = _coreInterface();
      var setupCalls = 0;
      when(() => coreInterface.setupConfig(any())).thenAnswer((_) async {
        setupCalls++;
        return 'rejected';
      });
      final container = await _pumpCoreManager(
        tester,
        coreInterface,
        overrides: profileOverrides(),
      );
      globalState.container = container;

      container.read(currentProfileIdProvider.notifier).value = next.id;
      await _waitForSetupToSettle(tester, () => setupCalls >= 1);

      expect(container.read(currentProfileIdProvider), next.id);
      expect(setupCalls, 1);

      await tester.pumpWidget(const SizedBox.shrink());
    });

    testWidgets('rapid A -> B -> C settles on C even when both are rejected', (
      tester,
    ) async {
      final coreInterface = _coreInterface();
      var setupCalls = 0;
      when(() => coreInterface.setupConfig(any())).thenAnswer((_) async {
        setupCalls++;
        return 'rejected';
      });
      final a = Profile.normal(label: 'a');
      final b = Profile.normal(label: 'b');
      final c = Profile.normal(label: 'c');
      final container = await _pumpCoreManager(
        tester,
        coreInterface,
        overrides: [
          initProvider.overrideWithBuild((_, _) => true),
          profilesProvider.overrideWith(() => TestProfiles([a, b, c])),
          currentProfileIdProvider.overrideWithBuild((_, _) => a.id),
          setupStateProvider.overrideWith((_, _) => _nullProfileSetupState),
        ],
      );
      globalState.container = container;

      container.read(currentProfileIdProvider.notifier).value = b.id;
      container.read(currentProfileIdProvider.notifier).value = c.id;
      await _waitForSetupToSettle(tester, () => setupCalls >= 2);

      expect(container.read(currentProfileIdProvider), c.id);
      expect(setupCalls, 2);

      await tester.pumpWidget(const SizedBox.shrink());
    });

    testWidgets(
      'a missing profile id recovers to a fallback without looping when '
      'the fallback is also rejected',
      (tester) async {
        final coreInterface = _coreInterface();
        var setupCalls = 0;
        when(() => coreInterface.setupConfig(any())).thenAnswer((_) async {
          setupCalls++;
          return 'rejected';
        });
        const missingId = -1;
        final container = await _pumpCoreManager(
          tester,
          coreInterface,
          overrides: [
            initProvider.overrideWithBuild((_, _) => true),
            profilesProvider.overrideWith(() => TestProfiles([previous])),
            currentProfileIdProvider.overrideWithBuild((_, _) => null),
            setupStateProvider.overrideWith((_, _) => _nullProfileSetupState),
          ],
        );
        globalState.container = container;

        container.read(currentProfileIdProvider.notifier).value = missingId;
        await _waitForSetupToSettle(
          tester,
          () => container.read(currentProfileIdProvider) == previous.id,
        );

        expect(setupCalls, lessThanOrEqualTo(3));
        expect(container.read(currentProfileIdProvider), previous.id);

        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  });
}
