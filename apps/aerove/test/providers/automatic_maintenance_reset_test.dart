import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/application.dart';
import 'package:fl_clash/core/desktop/model.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_profiles.dart';

class _FailingProfiles extends ProfilesAction {
  int attempts = 0;
  bool cancelled = false;

  @override
  Future<void> updateProfile(
    Profile profile, {
    bool showLoading = false,
    bool Function()? isCurrent,
  }) async {
    attempts++;
    if (cancelled) throw const UpdateCancelledException();
    throw StateError('offline');
  }
}

class _FailingResources extends ResourcesAction {
  int attempts = 0;
  bool cancelled = false;

  @override
  Future<void> updateIconSet(
    IconSet iconSet, {
    bool Function()? isCurrent,
  }) async {
    attempts++;
    if (cancelled) throw const UpdateCancelledException();
    throw StateError('offline');
  }
}

class _LifecycleCore extends CoreAction {
  CoreLifecycleOutcome outcome = CoreLifecycleOutcome.applied;

  @override
  Future<void> initCore() async {}

  @override
  Future<CoreLifecycleResult> startLifecycle() async =>
      CoreLifecycleResult(revision: 1, outcome: outcome);

  @override
  Future<CoreLifecycleResult> restartLifecycle() => startLifecycle();
}

class _Setup extends SetupAction {
  bool failRestart = false;

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) async {
    if (running && failRestart) throw StateError('listener restart failed');
    ref.read(runTimeProvider.notifier).value = running ? 1 : null;
    return true;
  }

  @override
  Future<bool> applyProfile({
    bool silence = false,
    bool force = false,
    Future<void> Function()? preloadInvoke,
  }) async => true;
}

class _System extends SystemAction {
  @override
  Future<void> updateLocalIp() async {}
}

Widget _managers(Widget child) => ThemeManager(child: child);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late db.Database store;
  late ProviderContainer container;
  late _FailingProfiles profiles;
  late _FailingResources resources;
  late _LifecycleCore core;

  setUpAll(() => AppLocalizations.load(const Locale('en')));
  setUp(() async {
    store = db.Database(NativeDatabase.memory());
    db.database = store;
    await store.iconSetsDao.putAll([
      const IconSet(
        id: 1,
        name: 'Remote',
        url: 'https://example.com/icons',
      ).toCompanion(),
    ]);
    final profile = Profile.normal(
      url: 'https://example.com/profile',
    ).copyWith(autoUpdate: true, lastUpdateDate: DateTime(2000));
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(() => TestProfiles([profile])),
        profilesActionProvider.overrideWith(_FailingProfiles.new),
        resourcesActionProvider.overrideWith(_FailingResources.new),
        coreActionProvider.overrideWith(_LifecycleCore.new),
        setupActionProvider.overrideWith(_Setup.new),
        systemActionProvider.overrideWith(_System.new),
        currentProfileIdProvider.overrideWithBuild((_, _) => null),
        patchClashConfigProvider.overrideWithBuild(
          (_, _) => const PatchClashConfig(geoAutoUpdate: true),
        ),
        appSettingProvider.overrideWithBuild(
          (_, _) => const AppSettingProps(locale: 'en'),
        ),
      ],
    );
    globalState.container = container;
    container.read(appVisibleProvider.notifier).value = true;
    profiles =
        container.read(profilesActionProvider.notifier) as _FailingProfiles;
    resources =
        container.read(resourcesActionProvider.notifier) as _FailingResources;
    core = container.read(coreActionProvider.notifier) as _LifecycleCore;
  });
  tearDown(() async {
    container.dispose();
    await store.close();
  });

  Future<void> sweep() async {
    await profiles.autoUpdateProfiles();
    await resources.autoUpdate();
  }

  Future<void> blocked() async {
    await sweep();
    await sweep();
    expect(profiles.attempts, 1);
    expect(resources.attempts, 1);
  }

  for (final start in [true, false]) {
    test(
      'successful ${start ? 'start' : 'restart'} admits the next due sweep',
      () async {
        await blocked();
        if (start) {
          await core.startCore();
        } else {
          expect(await core.restartCore(), isTrue);
        }
        await sweep();
        expect(profiles.attempts, 2);
        expect(resources.attempts, 2);
        await sweep();
        expect(profiles.attempts, 2);
        expect(resources.attempts, 2);
      },
    );
  }

  test('a superseded restart leaves the current backoff in force', () async {
    await blocked();
    core.outcome = CoreLifecycleOutcome.superseded;
    expect(await core.restartCore(), isFalse);
    await sweep();
    expect(profiles.attempts, 1);
    expect(resources.attempts, 1);
  });

  test('cancelled maintenance does not consume the next due sweep', () async {
    profiles.cancelled = resources.cancelled = true;
    await sweep();
    await sweep();
    expect(profiles.attempts, 2);
    expect(resources.attempts, 2);
  });

  test('a completed VPN restart admits the next due sweep', () async {
    await blocked();
    container.read(runTimeProvider.notifier).value = 1;
    await container.read(setupActionProvider.notifier).restartVpn();
    await sweep();
    expect(profiles.attempts, 2);
    expect(resources.attempts, 2);
  });

  test('a stopped or failed VPN restart leaves backoff in force', () async {
    await blocked();
    final setup = container.read(setupActionProvider.notifier) as _Setup;
    await setup.restartVpn();
    await sweep();
    expect(profiles.attempts, 1);
    expect(resources.attempts, 1);
    container.read(runTimeProvider.notifier).value = 1;
    setup.failRestart = true;
    await expectLater(setup.restartVpn(), throwsStateError);
    await sweep();
    expect(profiles.attempts, 1);
    expect(resources.attempts, 1);
  });

  testWidgets(
    'the production connectivity callback admits the next due sweep',
    (tester) async {
      await tester.runAsync(blocked);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const Application.forTesting(
            wrapManagers: _managers,
            home: SizedBox.shrink(),
          ),
        ),
      );
      final state = tester.state<ApplicationState>(find.byType(Application));
      await state.handleConnectivityChanged([ConnectivityResult.vpn]);
      await tester.runAsync(sweep);
      expect(profiles.attempts, 2);
      expect(resources.attempts, 2);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
