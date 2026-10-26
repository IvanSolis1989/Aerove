import 'dart:async';

import 'package:fl_clash/application.dart';
import 'package:fl_clash/common/maintenance_visibility.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _Store extends StoreAction {
  @override
  void savePreferencesDebounce() {}

  @override
  Future<void> savePreferencesNow() async {}
}

Future<ProviderContainer> _mount(
  WidgetTester tester, {
  AppLifecycleState startup = AppLifecycleState.resumed,
}) async {
  tester.binding.handleAppLifecycleStateChanged(startup);
  tester.binding.scheduleForcedFrame();
  final container = ProviderContainer(
    overrides: [
      safeModeProvider.overrideWithValue(true),
      groupsProvider.overrideWithValue(const []),
      needUpdateGroupsProvider.overrideWithValue((
        isProxies: false,
        sortNum: 0,
        sortType: ProxiesSortType.none,
      )),
      themeSettingProvider.overrideWithBuild((_, _) => const ThemeProps()),
      storeActionProvider.overrideWith(_Store.new),
    ],
  );
  globalState.container = container;
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const AppStateManager(child: SizedBox.shrink()),
    ),
  );
  await tester.pump();
  return container;
}

void main() {
  for (final startup in [
    AppLifecycleState.inactive,
    AppLifecycleState.hidden,
  ]) {
    testWidgets('initial $startup separates maintenance from focus', (
      tester,
    ) async {
      final container = await _mount(tester, startup: startup);
      try {
        expect(container.read(appVisibleProvider), isFalse);
        expect(
          container.read(maintenanceVisibleProvider),
          startup == AppLifecycleState.inactive,
        );
      } finally {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pumpWidget(const SizedBox.shrink());
        container.dispose();
      }
    });
  }

  testWidgets(
    'focus loss stops UI demand but preserves in-flight maintenance',
    (tester) async {
      final container = await _mount(tester);
      final pending = Completer<void>();
      var runs = 0;
      var applied = 0;
      final scheduler = ForegroundAutoUpdateScheduler(
        run: (isCurrent) async {
          runs++;
          await pending.future;
          if (isCurrent()) applied++;
        },
        now: () => tester.binding.clock.now(),
      );
      final subscription = container.listen(
        maintenanceVisibleProvider,
        (_, visible) => scheduler.setVisible(visible),
        fireImmediately: true,
      );
      try {
        await tester.pump(const Duration(minutes: 20));
        expect(runs, 1);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.inactive,
        );
        await tester.pump();
        expect(container.read(appVisibleProvider), isFalse);
        expect(container.read(maintenanceVisibleProvider), isTrue);
        pending.complete();
        await tester.pump();
        expect(applied, 1);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pump();
        expect(container.read(appVisibleProvider), isTrue);
        expect(runs, 1);
      } finally {
        scheduler.dispose();
        subscription.close();
        await tester.pumpWidget(const SizedBox.shrink());
        container.dispose();
      }
    },
  );

  testWidgets('true background cancels and visible inactive resumes once', (
    tester,
  ) async {
    final container = await _mount(tester);
    final pending = Completer<void>();
    var runs = 0;
    var applied = 0;
    final scheduler = ForegroundAutoUpdateScheduler(
      run: (isCurrent) async {
        runs++;
        if (runs == 1) await pending.future;
        if (isCurrent()) applied++;
      },
      now: () => tester.binding.clock.now(),
    );
    final subscription = container.listen(
      maintenanceVisibleProvider,
      (_, visible) => scheduler.setVisible(visible),
      fireImmediately: true,
    );
    try {
      await tester.pump(const Duration(minutes: 20));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      expect(container.read(maintenanceVisibleProvider), isFalse);
      pending.complete();
      await tester.pump();
      expect(applied, 0);
      await tester.pump(const Duration(minutes: 40));
      expect(runs, 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      expect(container.read(appVisibleProvider), isFalse);
      expect(container.read(maintenanceVisibleProvider), isTrue);
      expect(runs, 2);
      expect(applied, 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(runs, 2);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.detached);
      expect(container.read(maintenanceVisibleProvider), isFalse);
    } finally {
      scheduler.dispose();
      subscription.close();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    }
  });
}
