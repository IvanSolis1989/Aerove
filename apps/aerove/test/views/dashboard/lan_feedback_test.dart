import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/desktop/model.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

class _Core extends Mock implements CoreHandlerInterface {}

class _Setup extends SetupAction {
  bool authorize = true;
  bool realAuthorization = false;
  bool? applyResult;
  Completer<void>? applyGate;
  int applies = 0;
  PatchClashConfig? appliedPatch;

  @override
  Future<bool> requestAdmin(bool enableTun) => realAuthorization
      ? super.requestAdmin(enableTun)
      : Future.value(authorize);

  @override
  Future<AuthorizeCode> authorizeCore() async => AuthorizeCode.success;

  @override
  Future<bool> applyProfile({
    bool silence = false,
    bool force = false,
    Future<void> Function()? preloadInvoke,
  }) async {
    if (applyResult == null) {
      return super.applyProfile(
        silence: silence,
        force: force,
        preloadInvoke: preloadInvoke,
      );
    }
    applies++;
    appliedPatch = ref.read(patchClashConfigProvider);
    await applyGate?.future;
    return applyResult!;
  }

  @override
  Future<bool> setCoreRunning(bool running) async => true;

  @override
  void resetCoreTraffic() {}
}

const _restarted = CoreLifecycleResult(
  revision: 1,
  outcome: CoreLifecycleOutcome.applied,
);

class _RestartCore extends CoreAction {
  final reply = Completer<CoreLifecycleResult>();

  @override
  Future<CoreLifecycleResult> restartLifecycle() => reply.future;

  @override
  Future<CoreLifecycleResult> startLifecycle() async => _restarted;

  @override
  Future<void> initCore() async {}
}

class _System extends SystemAction {
  String? address = '192.168.1.23';
  Completer<void>? gate;
  int reads = 0;
  bool realLookup = false;
  final replies = <Completer<String?>>[];

  @override
  Future<void> updateLocalIp() async {
    if (realLookup) return super.updateLocalIp();
    reads++;
    await gate?.future;
    ref.read(localIpProvider.notifier).value = address;
  }

  @override
  Future<String?> readLocalIpAddress() {
    reads++;
    return replies.removeAt(0).future;
  }
}

void main() {
  late ProviderContainer container;
  late _Core core;
  late _Setup setup;
  late _System system;
  late _RestartCore restart;
  late Completer<String> coreReply;
  late ValueNotifier<bool> pageActive;
  late List<String> copied;
  late bool clipboardFails;
  var actionsInitialized = false;

  setUpAll(() {
    registerFallbackValue(
      const PatchClashConfig().toUpdateParams(
        authentication: const [],
        skipCertVerify: false,
      ),
    );
  });

  setUp(() {
    core = _Core();
    actionsInitialized = false;
    copied = [];
    clipboardFails = false;
    pageActive = ValueNotifier(true);
    when(() => core.updateConfig(any())).thenAnswer((_) => coreReply.future);
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        setupActionProvider.overrideWith(() => setup = _Setup()),
        coreActionProvider.overrideWith(() => restart = _RestartCore()),
        systemActionProvider.overrideWith(() => system = _System()),
        vpnRouteAddressProvider.overrideWith((_) async => const <String>[]),
        safeModeProvider.overrideWithValue(false),
      ],
    );
    globalState.container = container;
    container
      ..listen(patchClashConfigProvider, (_, _) {})
      ..listen(vpnSettingProvider, (_, _) {})
      ..listen(localIpProvider, (_, _) {});
    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(allowLan: false, mixedPort: 8899));
  });

  void initializeActions() {
    if (actionsInitialized) return;
    actionsInitialized = true;
    coreReply = Completer<String>();
    container.read(setupActionProvider.notifier);
    container.read(coreActionProvider.notifier);
    container.read(systemActionProvider.notifier);
  }

  Future<void> pumpCard(WidgetTester tester) async {
    initializeActions();
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          if (clipboardFails) throw PlatformException(code: 'denied');
          copied.add((call.arguments as Map)['text'] as String);
        }
        return null;
      },
    );
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
      pageActive.dispose();
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
      await tester.pump();
    });
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          locale: const Locale('en'),
          withStatusManager: true,
          child: Scaffold(
            body: ValueListenableBuilder<bool>(
              valueListenable: pageActive,
              builder: (_, active, child) =>
                  PageActivityScope(isActive: active, child: child!),
              child: const FusedNetworkControls(
                members: {DashboardWidget.intranetIp},
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  final lan = find.byKey(const ValueKey('dashboard-lan'));

  Future<void> awaitCompletion<T>(WidgetTester tester, Future<T> future) async {
    var completed = false;
    Object? failure;
    StackTrace? failureStack;
    unawaited(
      future.then<void>(
        (_) => completed = true,
        onError: (Object error, StackTrace stack) {
          failure = error;
          failureStack = stack;
          completed = true;
        },
      ),
    );
    for (var frame = 0; frame < 50 && !completed; frame++) {
      await tester.pump(const Duration(milliseconds: 20));
    }
    expect(
      completed,
      isTrue,
      reason: 'The controlled operation did not finish within 50 pumps',
    );
    if (failure != null) {
      Error.throwWithStackTrace(failure!, failureStack!);
    }
  }

  Future<({Future<void> update})> start(WidgetTester tester) async {
    await tester.tap(lan);
    final update = setup.updateConfig();
    await tester.pump();
    expect(copied, isEmpty);
    expect(system.reads, 0);
    return (update: update);
  }

  testWidgets('copies once only after the Core commits the actual port', (
    tester,
  ) async {
    await pumpCard(tester);
    final height = tester.getSize(find.byType(FusedNetworkControls)).height;
    final update = (await start(tester)).update;
    verify(() => core.updateConfig(any())).called(1);
    coreReply.complete('');
    await awaitCompletion(tester, update);
    await tester.pump();
    expect(copied, ['192.168.1.23:8899']);
    expect(system.reads, 1);
    final l10n = tester.element(lan).appLocalizations;
    expect(find.text(l10n.lanAddressCopied(copied.single)), findsOneWidget);
    await tester.tap(lan);
    await tester.pump();
    expect(copied, hasLength(1));
    expect(tester.getSize(find.byType(FusedNetworkControls)).height, height);
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('formats usable IPv6 with brackets', (tester) async {
    initializeActions();
    system.address = 'fd00::23';
    await pumpCard(tester);
    final update = (await start(tester)).update;
    coreReply.complete('');
    await awaitCompletion(tester, update);
    await tester.pump();
    expect(copied, ['[fd00::23]:8899']);
    await tester.pump(const Duration(seconds: 4));
  });

  for (final applySucceeded in [true, false]) {
    testWidgets('authorization restart preserves the LAN intent only when '
        'the final apply succeeds ($applySucceeded)', (tester) async {
      initializeActions();
      setup
        ..realAuthorization = true
        ..applyResult = applySucceeded
        ..applyGate = Completer<void>();
      container
          .read(patchClashConfigProvider.notifier)
          .update((state) => state.copyWith.tun(enable: true));
      await pumpCard(tester);
      final update = (await start(tester)).update;
      expect(
        container.read(authorizedTunEnableProvider),
        TunAuthorizationState.authorized,
      );
      expect(container.read(coreStatusProvider), CoreStatus.connecting);
      restart.reply.complete(_restarted);
      await tester.pump();
      expect(container.read(coreStatusProvider), CoreStatus.connected);
      expect(setup.applies, 1);
      expect(setup.appliedPatch!.allowLan, isTrue);
      expect(setup.appliedPatch!.tun.enable, isTrue);
      expect(copied, isEmpty);
      setup.applyGate!.complete();
      await awaitCompletion(tester, update);
      await tester.pump();
      expect(copied, applySucceeded ? ['192.168.1.23:8899'] : isEmpty);
      expect(system.reads, applySucceeded ? 1 : 0);
      await tester.pump(const Duration(seconds: 4));
    });
  }

  for (final newerIntent in ['start', 'restart', 'stop']) {
    testWidgets(
      'an external $newerIntent cancels an authorization LAN handoff',
      (tester) async {
        initializeActions();
        setup
          ..realAuthorization = true
          ..applyResult = true;
        container
            .read(patchClashConfigProvider.notifier)
            .update((state) => state.copyWith.tun(enable: true));
        await pumpCard(tester);
        final update = (await start(tester)).update;
        Future<void>? externalStart;
        Future<bool>? externalRestart;
        switch (newerIntent) {
          case 'start':
            externalStart = restart.startCore();
          case 'restart':
            externalRestart = restart.restartCore();
          case 'stop':
            await awaitCompletion(tester, setup.setRunning(false));
        }
        restart.reply.complete(_restarted);
        await awaitCompletion(tester, update);
        if (externalStart != null) {
          await awaitCompletion(tester, externalStart);
        }
        if (externalRestart != null) {
          await awaitCompletion(tester, externalRestart);
        }
        await tester.pump();
        expect(copied, isEmpty);
        expect(system.reads, 0);
      },
    );
  }

  testWidgets(
    'a foreground LAN intent awaits the next IP epoch before copying',
    (tester) async {
      final oldReply = Completer<String?>();
      final currentReply = Completer<String?>();
      initializeActions();
      system
        ..realLookup = true
        ..replies.addAll([oldReply, currentReply]);
      await pumpCard(tester);
      final oldUpdate = system.updateLocalIp();
      await tester.pump(const Duration(seconds: 1));
      expect(system.reads, 1);
      container.read(appVisibleProvider.notifier).value = false;
      container.read(appVisibleProvider.notifier).value = true;
      await tester.tap(lan);
      final update = setup.updateConfig();
      coreReply.complete('');
      await awaitCompletion(tester, update);
      await tester.pump();
      oldReply.complete('192.0.2.10');
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      expect(system.reads, 2);
      expect(copied, isEmpty);
      final l10n = tester.element(lan).appLocalizations;
      expect(find.text(l10n.lanEnabledNoAddress), findsNothing);
      currentReply.complete('192.168.1.23');
      await tester.pump();
      await awaitCompletion(tester, oldUpdate);
      expect(copied, ['192.168.1.23:8899']);
      await tester.pump(const Duration(seconds: 4));
    },
  );

  for (final missing in <String?>[
    null,
    '',
    'localhost',
    '127.0.0.1',
    '198.18.0.1',
    'fe80::1',
  ]) {
    testWidgets('reports an unavailable LAN address ($missing)', (
      tester,
    ) async {
      initializeActions();
      system.address = missing;
      await pumpCard(tester);
      final update = (await start(tester)).update;
      coreReply.complete('');
      await awaitCompletion(tester, update);
      await tester.pump();
      expect(copied, isEmpty);
      final l10n = tester.element(lan).appLocalizations;
      expect(find.text(l10n.lanEnabledNoAddress), findsOneWidget);
      await tester.pump(const Duration(seconds: 4));
    });
  }

  testWidgets('reports the address when clipboard writing fails', (
    tester,
  ) async {
    clipboardFails = true;
    await pumpCard(tester);
    final update = (await start(tester)).update;
    coreReply.complete('');
    await awaitCompletion(tester, update);
    await tester.pump();
    expect(copied, isEmpty);
    final l10n = tester.element(lan).appLocalizations;
    expect(
      find.text(l10n.lanAddressCopyFailed('192.168.1.23:8899')),
      findsOneWidget,
    );
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('a Core rejection never copies or claims success', (
    tester,
  ) async {
    await pumpCard(tester);
    final update = (await start(tester)).update;
    coreReply.complete('rejected');
    await awaitCompletion(tester, update);
    await tester.pump();
    expect(copied, isEmpty);
    expect(system.reads, 0);
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('a cancelled Core call never copies or claims success', (
    tester,
  ) async {
    await pumpCard(tester);
    final update = (await start(tester)).update;
    coreReply.completeError(StateError('cancelled'));
    await awaitCompletion(tester, update);
    await tester.pump();
    expect(copied, isEmpty);
    expect(system.reads, 0);
    await tester.pump(const Duration(seconds: 4));
  });

  for (final boundary in [
    'disable',
    'page',
    'background',
    'dispose',
    'profile',
  ]) {
    testWidgets('$boundary prevents a late Core success from copying', (
      tester,
    ) async {
      await pumpCard(tester);
      final update = (await start(tester)).update;
      switch (boundary) {
        case 'disable':
          await tester.tap(lan);
        case 'page':
          pageActive.value = false;
        case 'background':
          container.read(appVisibleProvider.notifier).value = false;
        case 'dispose':
          await tester.pumpWidget(const SizedBox.shrink());
        case 'profile':
          container.read(currentProfileIdProvider.notifier).value = 2;
      }
      await tester.pump();
      coreReply.complete('');
      await awaitCompletion(tester, update);
      await tester.pump();
      expect(copied, isEmpty);
      expect(system.reads, 0);
    });
  }

  for (final boundary in ['page', 'background', 'disable', 'port', 'profile']) {
    testWidgets('$boundary also invalidates an address lookup in flight', (
      tester,
    ) async {
      initializeActions();
      system.gate = Completer<void>();
      await pumpCard(tester);
      final update = (await start(tester)).update;
      coreReply.complete('');
      await awaitCompletion(tester, update);
      await tester.pump();
      expect(system.reads, 1);
      switch (boundary) {
        case 'page':
          pageActive.value = false;
        case 'background':
          container.read(appVisibleProvider.notifier).value = false;
        case 'disable':
          await tester.tap(lan);
        case 'port':
          container
              .read(patchClashConfigProvider.notifier)
              .update((state) => state.copyWith(mixedPort: 9900));
        case 'profile':
          container.read(currentProfileIdProvider.notifier).value = 2;
      }
      await tester.pump();
      system.gate!.complete();
      await tester.pump();
      expect(copied, isEmpty);
    });
  }

  testWidgets('restoring preferences cannot trigger a copy', (tester) async {
    await pumpCard(tester);
    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(allowLan: true));
    final update = setup.updateConfig();
    coreReply.complete('');
    await awaitCompletion(tester, update);
    await tester.pump();
    expect(copied, isEmpty);
    expect(system.reads, 0);
  });

  testWidgets('a superseded enable cannot acknowledge a newer request', (
    tester,
  ) async {
    await pumpCard(tester);
    final first = (await start(tester)).update;
    await tester.tap(lan);
    await tester.tap(lan);
    final secondReply = Completer<String>();
    var calls = 0;
    when(() => core.updateConfig(any())).thenAnswer((_) {
      calls++;
      return secondReply.future;
    });
    final second = setup.updateConfig();
    await tester.pump();
    expect(calls, 0);
    coreReply.complete('');
    await awaitCompletion(tester, first);
    await tester.pump();
    expect(copied, isEmpty);
    expect(calls, 1);
    secondReply.complete('');
    await awaitCompletion(tester, second);
    await tester.pump();
    expect(copied, ['192.168.1.23:8899']);
    await tester.pump(const Duration(seconds: 4));
  });
}
