import 'dart:convert';

import 'package:fl_clash/manager/android_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../helpers/preferences.dart';
import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _RecordingSetupAction extends SetupAction {
  static final requests = <bool>[];

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) async {
    requests.add(running);
    return true;
  }
}

class _PackagesSystemAction extends SystemAction {
  static int reads = 0;

  @override
  Future<List<Package>> getPackages() async {
    reads++;
    return ref.read(packagesProvider);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;
  late SharedPreferences store;

  setUp(() async {
    await mockPreferences();
    store = await SharedPreferences.getInstance();
    _RecordingSetupAction.requests.clear();
    _PackagesSystemAction.reads = 0;
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        setupActionProvider.overrideWith(_RecordingSetupAction.new),
        systemActionProvider.overrideWith(_PackagesSystemAction.new),
        // Bypassing private addresses computes the routes in Isolate.run, which
        // the fake clock never lets finish.
        networkSettingProvider.overrideWithBuild(
          (_, _) => const NetworkProps(bypassPrivateRoute: false),
        ),
      ],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  Future<void> pumpAndroidManager(WidgetTester tester) async {
    await container.read(vpnOptionsProvider.future);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(
          child: AndroidManager(child: SizedBox(key: Key('child'))),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('renders its child unchanged', (tester) async {
    await pumpAndroidManager(tester);

    expect(find.byKey(const Key('child')), findsOneWidget);
    expect(tester.takeException(), null);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('background package events reload once on resume', (
    tester,
  ) async {
    await pumpAndroidManager(tester);
    container.read(packagesProvider.notifier).value = const [
      Package(
        packageName: 'example.app',
        label: 'Example',
        system: false,
        internet: true,
        lastUpdateTime: 0,
      ),
    ];
    final dynamic listener = tester.state(find.byType(AndroidManager));
    listener.onPackagesChanged();
    container.read(appVisibleProvider.notifier).value = false;
    listener.onPackagesChanged();
    listener.onPackagesChanged();
    await tester.pump(const Duration(minutes: 30));
    expect(_PackagesSystemAction.reads, 0);
    container.read(appVisibleProvider.notifier).value = true;
    await tester.pump(const Duration(seconds: 3));
    expect(_PackagesSystemAction.reads, 1);
    listener.onPackagesChanged();
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 3));
    expect(_PackagesSystemAction.reads, 1);
  });

  testWidgets('a stop the service reports turns the running state off', (
    tester,
  ) async {
    await pumpAndroidManager(tester);
    final listener =
        tester.state(find.byType(AndroidManager)) as ServiceListener;

    listener.onServiceStopped();
    await tester.pump();

    expect(_RecordingSetupAction.requests, [false]);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('persists shared state after the debounce window', (
    tester,
  ) async {
    await pumpAndroidManager(tester);
    expect(store.getString('sharedState'), isNull);

    container.read(appSettingProvider.notifier).value = const AppSettingProps(
      testUrl: 'http://shared.test',
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    final raw = store.getString('sharedState');
    expect(raw, isNotNull);
    final restored = SharedState.fromJson(
      json.decode(raw!) as Map<String, Object?>,
    );
    expect(restored.onlyStatisticsProxy, isTrue);
    expect(tester.takeException(), null);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('does not persist when shared state is unchanged', (
    tester,
  ) async {
    await pumpAndroidManager(tester);

    container.read(appSettingProvider.notifier).value = const AppSettingProps();
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(store.getString('sharedState'), isNull);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
