import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _RecordingStore extends StoreAction {
  final saves = <ThemeProps>[];

  @override
  void savePreferencesDebounce() {}

  @override
  Future<void> savePreferencesNow() async {
    saves.add(ref.read(configProvider).themeProps);
  }
}

void main() {
  for (final startup in [
    AppLifecycleState.resumed,
    AppLifecycleState.hidden,
    AppLifecycleState.paused,
  ]) {
    testWidgets('startup $startup waits for a real background transition', (
      tester,
    ) async {
      tester.binding.handleAppLifecycleStateChanged(startup);
      tester.binding.scheduleForcedFrame();
      const initial = ThemeProps(auroraRotationDay: 0);
      final container = ProviderContainer(
        overrides: [
          safeModeProvider.overrideWithValue(true),
          groupsProvider.overrideWithValue(const []),
          needUpdateGroupsProvider.overrideWithValue((
            isProxies: false,
            sortNum: 0,
            sortType: ProxiesSortType.none,
          )),
          themeSettingProvider.overrideWithBuild((_, _) => initial),
          storeActionProvider.overrideWith(_RecordingStore.new),
        ],
      );
      globalState.container = container;
      final notifier = container.read(storeActionProvider.notifier);
      final store = notifier as _RecordingStore;
      try {
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: const AppStateManager(child: SizedBox.shrink()),
          ),
        );
        await tester.pump();
        expect(find.byType(AppStateManager), findsOneWidget);
        expect(container.read(themeSettingProvider), initial);
        expect(store.saves, isEmpty);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
        await tester.pump();
        expect(container.read(themeSettingProvider), initial);
        expect(store.saves, isEmpty);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pump();
        expect(container.read(themeSettingProvider), initial);
        expect(store.saves, isEmpty);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
        await tester.pump();
        final changed = container.read(themeSettingProvider);
        expect(changed.auroraSeed, isNot(initial.auroraSeed));
        expect(changed.auroraRotationDay, greaterThan(0));
        expect(changed.auroraColors, initial.auroraColors);
        expect(store.saves, [changed]);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pump();
        expect(container.read(themeSettingProvider), changed);
        expect(store.saves, [changed]);
        expect(tester.takeException(), isNull);
      } finally {
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpWidget(const SizedBox.shrink());
        container.dispose();
      }
    });
  }
}
