import 'dart:async';
import 'dart:io';

import 'package:fl_clash/common/app_ports.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/system.dart';
import 'package:fl_clash/common/tray.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/tray_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:tray/tray.dart';

import '../helpers/app_paths.dart';
import '../helpers/test_profiles.dart';

const _trayChannel = MethodChannel('tray');
const _codec = StandardMethodCodec();
final _macOS = TargetPlatformVariant.only(TargetPlatform.macOS);

const _proxyGroup = Group(
  name: 'Proxy',
  type: GroupType.Selector,
  hidden: false,
  all: [
    Proxy(name: 'A', type: 'Direct'),
    Proxy(name: 'B', type: 'Direct'),
  ],
);

const _autoGroup = Group(
  name: 'Auto',
  type: GroupType.URLTest,
  hidden: false,
  now: 'A',
  all: [
    Proxy(name: 'A', type: 'Direct'),
    Proxy(name: 'B', type: 'Direct'),
  ],
);

/// The fields the macOS provider fills, which a host of any platform can
/// then drive through [TrayManager.isMacOS].
TrayState _macOSTrayState(Ref ref) {
  final config = ref.watch(
    patchClashConfigProvider.select(
      (state) => (mode: state.mode, port: state.mixedPort),
    ),
  );
  return TrayState(
    mode: config.mode,
    port: config.port,
    autoLaunch: false,
    systemProxy: false,
    tunEnable: false,
    isStart: false,
    groups: ref.watch(currentGroupsStateProvider).value,
    selectedMap: ref.watch(selectedMapProvider),
    showTrayTitle: false,
    safeMode: false,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory root;
  late ProviderContainer container;
  late TestProfiles profiles;
  late Profile profile;
  late List<MethodCall> calls;
  Completer<void>? showGate;

  setUpAll(() async {
    root = Directory.systemTemp.createTempSync('tray_lazy_menu_test');
    await useAppPaths(root);
    await AppLocalizations.load(const Locale('en'));
  });

  tearDownAll(() {
    try {
      root.deleteSync(recursive: true);
    } on FileSystemException {
      return;
    }
  });

  setUp(() {
    calls = [];
    showGate = null;
    trayPort = AppTray.forPlatform(isMacOS: true, isWindows: false);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_trayChannel, (call) async {
          calls.add(call);
          if (call.method == 'show') {
            await showGate?.future;
          }
          return true;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_trayChannel, null);
    trayPort = null;
    Tray.instance.resetForTesting();
  });

  List<String> methods() => calls.map((call) => call.method).toList();

  List<Object?> lastMenu() {
    final show = calls.lastWhere((call) => call.method == 'show');
    return (show.arguments as Map)['menu'] as List<Object?>;
  }

  Map<Object?, Object?> menuItem(List<Object?> items, String label) {
    return items.cast<Map<Object?, Object?>>().firstWhere(
      (item) => item['label'] == label,
    );
  }

  Map<Object?, Object?> proxyItem(String group, String proxy) {
    final submenu = menuItem(lastMenu(), group);
    return menuItem(submenu['items'] as List<Object?>, proxy);
  }

  Future<void> pumpTray(WidgetTester tester, {bool isMacOS = true}) async {
    // The tray queue must start inside the test's fake zone, or the futures
    // chained onto it never settle there.
    Tray.instance.resetForTesting();
    profile = Profile.normal();
    profiles = TestProfiles([profile]);
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(() => profiles),
        trayStateProvider.overrideWith(_macOSTrayState),
      ],
    );
    addTearDown(container.dispose);
    container.read(currentProfileIdProvider.notifier).update((_) => profile.id);
    container
        .read(groupsProvider.notifier)
        .update((_) => const [_proxyGroup, _autoGroup]);
    globalState.container = container;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: TrayManager(isMacOS: isMacOS, child: const SizedBox.shrink()),
        ),
      ),
    );
    await container.read(systemActionProvider.notifier).updateTray();
    await tester.pump(Duration.zero);
    calls.clear();
  }

  Future<void> requestMenu(WidgetTester tester) async {
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          _trayChannel.name,
          _codec.encodeMethodCall(const MethodCall('onMenuRequested')),
          null,
        );
    await tester.pumpAndSettle();
  }

  void flushDelay(String proxy, int value) {
    container.read(delayDataSourceProvider.notifier).setDelays([
      Delay(name: proxy, url: defaultTestUrl, value: value),
    ]);
  }

  void select(String group, String proxy) {
    profiles.put(profile.copyWith(selectedMap: {group: proxy}));
  }

  void setMode(Mode mode) {
    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(mode: mode));
  }

  testWidgets('delay flushes during a test leave the tray alone', (
    tester,
  ) async {
    await pumpTray(tester);

    for (final (proxy, value) in [('A', 120), ('A', 80), ('B', 95)]) {
      flushDelay(proxy, value);
      await tester.pump();
    }

    expect(methods(), isEmpty);
  }, variant: _macOS);

  testWidgets('a stale menu is rebuilt before it opens', (tester) async {
    await pumpTray(tester);
    flushDelay('A', 120);
    await tester.pump();

    await requestMenu(tester);

    expect(methods(), ['show', 'openMenu']);
    expect(proxyItem('Proxy', 'A')['detail'], '120');
  }, variant: _macOS);

  testWidgets('a current menu opens without a rebuild', (tester) async {
    await pumpTray(tester);

    await requestMenu(tester);

    expect(methods(), ['openMenu']);
  }, variant: _macOS);

  testWidgets('a selection or group change waits for the click', (
    tester,
  ) async {
    await pumpTray(tester);

    select('Proxy', 'B');
    container
        .read(groupsProvider.notifier)
        .update(
          (_) => [
            _proxyGroup.copyWith(
              all: [
                ..._proxyGroup.all,
                const Proxy(name: 'C', type: 'Direct'),
              ],
            ),
            _autoGroup,
          ],
        );
    await tester.pump();

    expect(methods(), isEmpty);

    await requestMenu(tester);

    expect(methods(), ['show', 'openMenu']);
    expect(proxyItem('Proxy', 'B')['checked'], isTrue);
    expect(proxyItem('Proxy', 'C')['checked'], isFalse);
  }, variant: _macOS);

  testWidgets('a pick the core makes for a group shows on the next click', (
    tester,
  ) async {
    await pumpTray(tester);
    container
        .read(groupsProvider.notifier)
        .update((_) => [_proxyGroup, _autoGroup.copyWith(now: 'B')]);
    await tester.pump();

    await requestMenu(tester);

    expect(methods(), ['show', 'openMenu']);
    expect(proxyItem('Auto', 'B')['checked'], isTrue);
  }, variant: _macOS);

  testWidgets('the menu built on click matches an eager build', (tester) async {
    await pumpTray(tester);
    flushDelay('B', 64);
    select('Proxy', 'B');
    container
        .read(groupsProvider.notifier)
        .update((_) => [_proxyGroup, _autoGroup.copyWith(now: 'B')]);
    await tester.pump();

    await requestMenu(tester);
    final onClick = lastMenu();
    expect(proxyItem('Auto', 'B')['checked'], isTrue);

    Tray.instance.resetForTesting();
    calls.clear();
    await container.read(systemActionProvider.notifier).updateTray();
    await tester.pump();

    expect(lastMenu(), onClick);
  }, variant: _macOS);

  testWidgets('a change outside the groups reaches the tray at once', (
    tester,
  ) async {
    await pumpTray(tester);
    flushDelay('A', 120);

    setMode(Mode.global);
    await tester.pumpAndSettle();

    expect(methods(), ['show']);
    expect(proxyItem('Proxy', 'A')['detail'], '120');

    calls.clear();
    await requestMenu(tester);

    expect(methods(), ['openMenu']);
  }, variant: _macOS);

  testWidgets('a click during an update opens the newest menu', (tester) async {
    await pumpTray(tester);
    final gate = Completer<void>();
    showGate = gate;

    setMode(Mode.global);
    await tester.pump();
    setMode(Mode.direct);
    await tester.pump();
    await requestMenu(tester);
    gate.complete();
    await tester.pumpAndSettle();

    expect(methods(), ['show', 'show', 'openMenu']);
  }, variant: _macOS);

  testWidgets('another platform opens its menu directly', (tester) async {
    await pumpTray(tester, isMacOS: false);
    flushDelay('A', 120);
    await tester.pump();

    await requestMenu(tester);

    expect(methods(), ['openMenu']);
  }, variant: _macOS);

  test('the tray state carries groups only where the menu lists them', () {
    final scoped = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    addTearDown(scoped.dispose);
    var notified = 0;
    scoped.listen(trayStateProvider, (_, _) => notified++);

    scoped.read(groupsProvider.notifier).update((_) => const [_proxyGroup]);

    final groups = scoped.read(trayStateProvider).groups;
    expect(groups, system.isMacOS ? hasLength(1) : isEmpty);
    expect(notified, system.isMacOS ? 1 : 0);
  });
}
