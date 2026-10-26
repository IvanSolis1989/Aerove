import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _QuietStore extends StoreAction {
  @override
  void savePreferencesDebounce() {}

  @override
  Future<void> savePreferencesNow() async {}
}

class _CountingPermissions extends LocationPermissions {
  int refreshes = 0;

  @override
  Future<void> refresh() async {
    refreshes++;
  }
}

class _CountingProxies extends ProxiesAction {
  int groupReads = 0;

  @override
  void build() {}

  @override
  void updateGroupsDebounce([Duration? duration]) {
    groupReads++;
  }
}

class _Harness {
  _Harness(this.tester, this.container);

  final WidgetTester tester;
  final ProviderContainer container;

  int get resumes => container.read(routeTrackerProvider).resumes;

  int get refreshes =>
      (container.read(locationPermissionsProvider.notifier)
              as _CountingPermissions)
          .refreshes;

  int get groupReads =>
      (container.read(proxiesActionProvider.notifier) as _CountingProxies)
          .groupReads;

  /// Goes through the engine's lifecycle channel, so the framework expands a
  /// jump such as paused to resumed into every state in between.
  Future<void> platform(AppLifecycleState state) async {
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      SystemChannels.lifecycle.name,
      SystemChannels.lifecycle.codec.encodeMessage(state.toString()),
      (_) {},
    );
    await tester.pump();
    await tester.pump();
  }

  Future<void> settle() async {
    await tester.pump();
    await tester.pump();
  }
}

Future<_Harness> _pumpManager(
  WidgetTester tester, {
  AppLifecycleState startup = AppLifecycleState.resumed,
}) async {
  tester.binding.handleAppLifecycleStateChanged(startup);
  tester.binding.scheduleForcedFrame();
  final container = ProviderContainer(
    overrides: [
      safeModeProvider.overrideWithValue(true),
      groupsProvider.overrideWithValue(const []),
      storeActionProvider.overrideWith(_QuietStore.new),
      locationPermissionsProvider.overrideWith(_CountingPermissions.new),
      proxiesActionProvider.overrideWith(_CountingProxies.new),
    ],
  );
  globalState.container = container;
  addTearDown(() async {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const AppStateManager(child: SizedBox.shrink()),
    ),
  );
  await tester.pump();
  return _Harness(tester, container);
}

void main() {
  testWidgets('losing focus alone does not retry failed probes', (
    tester,
  ) async {
    final app = await _pumpManager(tester);
    final start = app.resumes;

    for (var i = 0; i < 3; i++) {
      await app.platform(AppLifecycleState.inactive);
      await app.platform(AppLifecycleState.resumed);
    }

    expect(app.resumes - start, 0);
    expect(app.refreshes, 3, reason: 'permissions are checked every time');
  });

  for (final background in [
    AppLifecycleState.paused,
    AppLifecycleState.hidden,
  ]) {
    testWidgets('a return from ${background.name} retries failed probes once', (
      tester,
    ) async {
      final app = await _pumpManager(tester);
      final start = app.resumes;

      await app.platform(background);
      await app.platform(AppLifecycleState.resumed);
      expect(app.resumes - start, 1);
      expect(app.refreshes, 1);

      await app.platform(AppLifecycleState.inactive);
      await app.platform(AppLifecycleState.resumed);
      expect(app.resumes - start, 1, reason: 'the return was already used');
      expect(app.refreshes, 2);

      await app.platform(background);
      await app.platform(AppLifecycleState.resumed);
      expect(app.resumes - start, 2);
      expect(app.refreshes, 3);
    });
  }

  for (final startup in [AppLifecycleState.hidden, AppLifecycleState.paused]) {
    testWidgets('the first resume after a ${startup.name} start is a return', (
      tester,
    ) async {
      final app = await _pumpManager(tester, startup: startup);
      final start = app.resumes;

      await app.platform(AppLifecycleState.resumed);
      expect(app.resumes - start, 1);
      expect(app.refreshes, 1);

      await app.platform(AppLifecycleState.inactive);
      await app.platform(AppLifecycleState.resumed);
      expect(app.resumes - start, 1);
      expect(app.refreshes, 2);
    });
  }

  testWidgets('the first resume after a visible start is not a return', (
    tester,
  ) async {
    final app = await _pumpManager(tester, startup: AppLifecycleState.inactive);
    final start = app.resumes;

    await app.platform(AppLifecycleState.resumed);

    expect(app.resumes - start, 0);
    expect(app.refreshes, 1);
  });

  testWidgets('groups are read on entering the proxies page, not leaving', (
    tester,
  ) async {
    final app = await _pumpManager(tester);
    final page = app.container.read(currentPageLabelProvider.notifier);
    expect(app.groupReads, 0);

    page.toPage(PageLabel.proxies);
    await app.settle();
    expect(app.groupReads, 1, reason: 'dashboard to proxies');

    page.toPage(PageLabel.dashboard);
    await app.settle();
    expect(app.groupReads, 1, reason: 'proxies to dashboard');

    page.toPage(PageLabel.profiles);
    await app.settle();
    expect(app.groupReads, 1, reason: 'between two other pages');

    page.toPage(PageLabel.proxies);
    await app.settle();
    expect(app.groupReads, 2, reason: 'entering again');
  });

  testWidgets('a sort change reads the groups on any page', (tester) async {
    final app = await _pumpManager(tester);

    app.container.read(sortNumProvider.notifier).add();
    await app.settle();
    expect(app.groupReads, 1, reason: 'sortNum off the proxies page');

    app.container
        .read(proxiesStyleSettingProvider.notifier)
        .update((state) => state.copyWith(sortType: ProxiesSortType.delay));
    await app.settle();
    expect(app.groupReads, 2, reason: 'sortType off the proxies page');

    app.container
        .read(currentPageLabelProvider.notifier)
        .toPage(PageLabel.proxies);
    await app.settle();
    app.container.read(sortNumProvider.notifier).add();
    await app.settle();
    expect(app.groupReads, 4, reason: 'entering, then sortNum on the page');
  });
}
