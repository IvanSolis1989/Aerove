import 'package:fl_clash/common/app_ports.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/last_page.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/views/navigation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/preferences.dart';
import '../helpers/test_profiles.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  NavigationPort? previousNavigation;

  setUp(() {
    previousNavigation = navigationPort;
    navigationPort = navigation;
  });

  tearDown(() => navigationPort = previousNavigation);

  Future<ProviderContainer> restore(
    String? saved, {
    bool isAndroid = true,
    bool hasProfiles = false,
    bool openLogs = false,
    bool degraded = false,
  }) async {
    await mockPreferences({lastPageLabelKey: ?saved});
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(
          () => TestProfiles([if (hasProfiles) Profile.normal(label: 'p')]),
        ),
        appSettingProvider.overrideWithBuild(
          (_, _) => AppSettingProps(openLogs: openLogs),
        ),
      ],
    );
    addTearDown(container.dispose);
    await restoreLastPage(container, isAndroid: isAndroid, degraded: degraded);
    return container;
  }

  test('reopens on the page it was left on', () async {
    final container = await restore('tools');

    expect(container.read(currentPageLabelProvider), PageLabel.tools);
  });

  test('restores a page only while the navigation still offers it', () async {
    expect(
      (await restore('proxies')).read(currentPageLabelProvider),
      PageLabel.dashboard,
    );
    expect(
      (await restore(
        'proxies',
        hasProfiles: true,
      )).read(currentPageLabelProvider),
      PageLabel.proxies,
    );
    expect(
      (await restore('logs')).read(currentPageLabelProvider),
      PageLabel.dashboard,
    );
    expect(
      (await restore('logs', openLogs: true)).read(currentPageLabelProvider),
      PageLabel.logs,
    );
    for (final saved in ['requests', 'removedPage', null]) {
      expect(
        (await restore(saved)).read(currentPageLabelProvider),
        PageLabel.dashboard,
        reason: '$saved',
      );
    }
  });

  test('remembers each page switch on Android', () async {
    final container = await restore(null);

    container.read(currentPageLabelProvider.notifier).toPage(PageLabel.tools);
    await pumpEventQueue();
    expect(await preferences.getLastPageLabel(), 'tools');

    container.read(currentPageLabelProvider.notifier).toProfiles();
    await pumpEventQueue();
    expect(await preferences.getLastPageLabel(), 'profiles');
  });

  test(
    'opens the dashboard after a degraded boot and forgets the page',
    () async {
      final container = await restore('tools', degraded: true);

      expect(container.read(currentPageLabelProvider), PageLabel.dashboard);
      expect(await preferences.getLastPageLabel(), 'dashboard');

      container.read(currentPageLabelProvider.notifier).toPage(PageLabel.tools);
      await pumpEventQueue();
      expect(await preferences.getLastPageLabel(), 'tools');
    },
  );

  test('leaves other platforms on the dashboard and saves nothing', () async {
    final container = await restore('tools', isAndroid: false);

    expect(container.read(currentPageLabelProvider), PageLabel.dashboard);
    container.read(currentPageLabelProvider.notifier).toPage(PageLabel.proxies);
    await pumpEventQueue();
    expect(await preferences.getLastPageLabel(), 'tools');
  });
}
