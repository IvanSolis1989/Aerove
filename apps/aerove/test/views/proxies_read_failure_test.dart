import 'dart:async';
import 'dart:io';

import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/views/proxies/proxies.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import '../helpers/app_paths.dart';
import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _MockCore extends Mock implements CoreController {}

class _FailedInitialSetup extends SetupAction {
  final List<bool> calls = [];
  @override
  Future<bool> applyProfile({
    bool silence = false,
    bool force = false,
    Future<void> Function()? preloadInvoke,
  }) async {
    await globalState.safeRun(
      () => throw StateError('original source failure'),
    );
    return false;
  }

  @override
  Future<bool> setCoreRunning(bool running) async {
    calls.add(running);
    throw const CoreStopException(
      code: 'stop_graph_restore_failed',
      message: 'secondary GRAPH_OPEN failure',
    );
  }

  @override
  void resetCoreTraffic() {}
}

Future<List<Group>> _read(_MockCore core) => core.getProxiesGroups(
  sortType: any(named: 'sortType'),
  delayMap: any(named: 'delayMap'),
  selectedMap: any(named: 'selectedMap'),
  defaultTestUrl: any(named: 'defaultTestUrl'),
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  setUpAll(() => registerFallbackValue(ProxiesSortType.none));
  setUp(() async {
    home = await Directory.systemTemp.createTemp('aerove-proxies-read-');
    await useAppPaths(home);
  });
  tearDown(() => home.delete(recursive: true));
  testWidgets(
    'initial cleanup retains the first visible error and stopped state',
    (tester) async {
      final initialStatus = globalState.needInitStatus;
      addTearDown(() => globalState.needInitStatus = initialStatus);
      late _FailedInitialSetup setup;
      await tester.runAsync(() async {
        setup = _FailedInitialSetup();
      });
      final container = ProviderContainer(
        overrides: [
          profilesProvider.overrideWith(TestProfiles.new),
          setupActionProvider.overrideWith(() => setup),
        ],
      );
      addTearDown(container.dispose);
      globalState.container = container;
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const TestApp(
            withStatusManager: true,
            child: Scaffold(body: SizedBox()),
          ),
        ),
      );
      await tester.pump();
      final applied = await tester.runAsync(
        () => container
            .read(setupActionProvider.notifier)
            .setRunning(true, initialize: true),
      );
      expect(applied, isFalse);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('original source failure'), findsOneWidget);
      expect(find.textContaining('secondary GRAPH_OPEN'), findsNothing);
      expect(setup.calls, [false]);
      expect(container.read(isStartProvider), isFalse);
      expect(container.read(runningVpnOptionsProvider), isNull);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('an active picker retries from another selected page', (
    tester,
  ) async {
    final core = _MockCore();
    when(() => _read(core)).thenAnswer((_) async => []);
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        coreHandlerProvider.overrideWithValue(core),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(currentPageLabelProvider.notifier)
        .toPage(PageLabel.profiles);
    container.read(groupsReadFailedProvider.notifier).value = true;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(
          child: PageActivityScope(
            isActive: true,
            child: ProxiesView(picker: true),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text(AppLocalizations.current.retry));
    await tester.pumpAndSettle();
    expect(container.read(groupsReadFailedProvider), isFalse);
    verify(() => _read(core)).called(1);
    expect(tester.takeException(), isNull);
  });

  for (final type in ProxiesType.values) {
    testWidgets(
      '$type distinguishes failed reads from an empty response and retries',
      (tester) async {
        final core = _MockCore();
        final response = Completer<List<Group>>();
        when(() => _read(core)).thenAnswer((_) => response.future);
        final container = ProviderContainer(
          overrides: [
            profilesProvider.overrideWith(TestProfiles.new),
            coreHandlerProvider.overrideWithValue(core),
          ],
        );
        addTearDown(container.dispose);
        globalState.container = container;
        container.read(groupsReadFailedProvider.notifier).value = true;
        container
            .read(proxiesStyleSettingProvider.notifier)
            .update((state) => state.copyWith(type: type));
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: const TestApp(child: ProxiesView()),
          ),
        );
        await tester.pumpAndSettle();
        final l10n = AppLocalizations.current;
        expect(find.text(l10n.proxyGroupsUnavailable), findsOneWidget);
        expect(find.text(l10n.nullTip(l10n.proxies)), findsNothing);

        await tester.tap(find.text(l10n.retry));
        await tester.pump();
        expect(
          tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
          isNull,
        );
        response.complete([]);
        await tester.pumpAndSettle();

        expect(find.text(l10n.proxyGroupsUnavailable), findsNothing);
        expect(find.text(l10n.nullTip(l10n.proxies)), findsOneWidget);
        verify(() => _read(core)).called(1);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'a failed retry stays actionable and leaving cancels presentation only',
    (tester) async {
      final core = _MockCore();
      when(() => _read(core)).thenThrow(StateError('unavailable endpoint'));
      final container = ProviderContainer(
        overrides: [
          profilesProvider.overrideWith(TestProfiles.new),
          coreHandlerProvider.overrideWithValue(core),
        ],
      );
      addTearDown(container.dispose);
      globalState.container = container;
      container.read(groupsReadFailedProvider.notifier).value = true;
      Widget app(Widget child) => UncontrolledProviderScope(
        container: container,
        child: TestApp(child: child),
      );
      await tester.pumpWidget(app(const ProxiesView()));
      await tester.pumpAndSettle();
      await tester.tap(find.text(AppLocalizations.current.retry));
      await tester.pumpAndSettle();
      expect(
        find.text(AppLocalizations.current.proxyGroupsUnavailable),
        findsOneWidget,
      );
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull,
      );
      verify(() => _read(core)).called(3);

      final pending = Completer<List<Group>>();
      when(() => _read(core)).thenAnswer((_) => pending.future);
      await tester.tap(find.text(AppLocalizations.current.retry));
      await tester.pumpWidget(app(const SizedBox()));
      pending.complete([]);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
