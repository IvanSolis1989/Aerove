import 'dart:async';

import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:riverpod/riverpod.dart';

import '../helpers/test_profiles.dart';

class MockCoreHandlerInterface extends Mock implements CoreHandlerInterface {}

const runningVersion = '0.8.96';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late MockCoreHandlerInterface core;

  setUpAll(() {
    core = MockCoreHandlerInterface();
    globalState.packageInfo = PackageInfo(
      appName: 'FlClash',
      packageName: 'com.follow.clash',
      version: runningVersion,
      buildNumber: '1',
    );
  });

  setUp(() => reset(core));

  /// A container where a visible dashboard card shows traffic, unless
  /// [shown] is false.
  ProviderContainer buildContainer({bool shown = true}) {
    final profile = Profile.normal(
      label: 'p',
    ).copyWith(id: 1, currentGroupName: 'Manual');
    final container = ProviderContainer(
      overrides: [
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        profilesProvider.overrideWith(() => TestProfiles([profile])),
        currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
      ],
    );
    addTearDown(container.dispose);
    if (shown) {
      container
          .read(trafficDemandProvider.notifier)
          .claim(Object(), TrafficNeed.traffic);
    }
    return container;
  }

  group('CommonAction.updateMode', () {
    test('advances through every mode and wraps back to the first', () {
      final container = buildContainer();
      final action = container.read(commonActionProvider.notifier);
      container
          .read(patchClashConfigProvider.notifier)
          .update((state) => state.copyWith(mode: Mode.values.first));

      final seen = <Mode>[container.read(patchClashConfigProvider).mode];
      for (var i = 0; i < Mode.values.length; i++) {
        action.updateMode();
        seen.add(container.read(patchClashConfigProvider).mode);
      }

      expect(seen.sublist(0, Mode.values.length), Mode.values);
      expect(seen.last, Mode.values.first, reason: 'wraps after the last mode');
      expect(
        container.read(currentProfileProvider)?.currentGroupName,
        GroupName.GLOBAL.name,
        reason: 'passing global selects its group, as the dashboard does',
      );
    });
  });

  group('CommonAction.updateSpeedStatistics', () {
    test('toggles the tray title flag both ways', () {
      final container = buildContainer();
      final action = container.read(commonActionProvider.notifier);
      final initial = container.read(appSettingProvider).showTrayTitle;

      action.updateSpeedStatistics();
      expect(container.read(appSettingProvider).showTrayTitle, !initial);

      action.updateSpeedStatistics();
      expect(container.read(appSettingProvider).showTrayTitle, initial);
    });
  });

  group('CommonAction.updateTraffic', () {
    test('asks the Core nothing while no visible card shows traffic', () async {
      final container = buildContainer(shown: false);
      when(
        () => core.getTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 1, down: 2));
      when(
        () => core.getTotalTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 3, down: 4));
      final action = container.read(commonActionProvider.notifier);
      final owner = Object();

      await action.updateTraffic();
      verifyNever(() => core.getTraffic(any()));
      verifyNever(() => core.getTotalTraffic(any()));
      expect(container.read(trafficsProvider).list, isEmpty);

      container
          .read(trafficDemandProvider.notifier)
          .claim(owner, TrafficNeed.runTime);
      await action.updateTraffic();
      verifyNever(() => core.getTraffic(any()));

      container
          .read(trafficDemandProvider.notifier)
          .claim(owner, TrafficNeed.traffic);
      await action.updateTraffic();
      verify(() => core.getTraffic(any())).called(1);
      expect(container.read(trafficsProvider).list.last.down, 2);

      container.read(trafficDemandProvider.notifier).release(owner);
      await action.updateTraffic();
      verifyNever(() => core.getTraffic(any()));
    });

    test('drops an answer from before hide even after resume', () async {
      final container = buildContainer();
      final answer = Completer<Traffic>();
      when(() => core.getTraffic(any())).thenAnswer((_) => answer.future);
      when(() => core.getTotalTraffic(any())).thenAnswer((_) => answer.future);
      final history = container.read(trafficsProvider).list.toList();
      final total = container.read(totalTrafficProvider);
      final action = container.read(commonActionProvider.notifier);
      final run = action.updateTraffic();

      container.read(appVisibleProvider.notifier).value = false;
      container.read(appVisibleProvider.notifier).value = true;
      await action.updateTraffic();
      answer.complete(const Traffic(up: 17, down: 29));
      await run;

      expect(container.read(trafficsProvider).list, history);
      expect(container.read(totalTrafficProvider), total);
      verify(() => core.getTraffic(any())).called(1);
      verify(() => core.getTotalTraffic(any())).called(1);
    });

    test('records the sampled traffic and the running total', () async {
      final container = buildContainer();
      container
          .read(appSettingProvider.notifier)
          .update((state) => state.copyWith(onlyStatisticsProxy: true));
      when(
        () => core.getTraffic(true),
      ).thenAnswer((_) async => const Traffic(up: 10, down: 20));
      when(
        () => core.getTotalTraffic(true),
      ).thenAnswer((_) async => const Traffic(up: 100, down: 200));

      await container.read(commonActionProvider.notifier).updateTraffic();

      expect(container.read(trafficsProvider).list.last.up, 10);
      expect(container.read(trafficsProvider).list.last.down, 20);
      expect(
        container.read(totalTrafficProvider),
        const Traffic(up: 100, down: 200),
      );
      verify(() => core.getTraffic(true)).called(1);
      verify(() => core.getTotalTraffic(true)).called(1);
    });

    test('swallows a core failure and leaves the total untouched', () async {
      final container = buildContainer();
      final before = container.read(totalTrafficProvider);
      when(() => core.getTraffic(any())).thenThrow(StateError('core down'));

      await expectLater(
        container.read(commonActionProvider.notifier).updateTraffic(),
        completes,
      );
      expect(container.read(totalTrafficProvider), before);
    });

    test('does not record a total when only the total call fails', () async {
      final container = buildContainer();
      final before = container.read(totalTrafficProvider);
      when(
        () => core.getTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 1, down: 2));
      when(() => core.getTotalTraffic(any())).thenThrow(StateError('boom'));

      await container.read(commonActionProvider.notifier).updateTraffic();

      expect(container.read(trafficsProvider).list.last.up, 1);
      expect(container.read(totalTrafficProvider), before);
    });

    test(
      'drops concurrent in-flight updates while one is in progress',
      () async {
        final container = buildContainer();
        final completer = Completer<Traffic>();
        when(() => core.getTraffic(any())).thenAnswer((_) => completer.future);
        when(
          () => core.getTotalTraffic(any()),
        ).thenAnswer((_) => completer.future);

        final first = container
            .read(commonActionProvider.notifier)
            .updateTraffic();
        final second = container
            .read(commonActionProvider.notifier)
            .updateTraffic();

        await expectLater(second, completes);
        completer.complete(const Traffic(up: 5, down: 10));
        await first;

        verify(() => core.getTraffic(any())).called(1);
        verify(() => core.getTotalTraffic(any())).called(1);
      },
    );
  });

  group('CommonAction.autoCheckUpdate', () {
    test('returns without a network call when the setting is off', () async {
      final container = buildContainer();
      container
          .read(appSettingProvider.notifier)
          .update((state) => state.copyWith(autoCheckUpdate: false));

      await expectLater(
        container.read(commonActionProvider.notifier).autoCheckUpdate(),
        completion(isFalse),
      );
    });
  });
}
