import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/tile_manager.dart';
import 'package:fl_clash/plugins/tile.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_profiles.dart';

class _RecordingSetupAction extends SetupAction {
  static final requests = <bool>[];

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) async {
    requests.add(running);
    return true;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  setUpAll(() async {
    await AppLocalizations.load(const Locale('en'));
  });

  setUp(() {
    _RecordingSetupAction.requests.clear();
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        setupActionProvider.overrideWith(_RecordingSetupAction.new),
      ],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  Future<TileListener> pumpTileManager(WidgetTester tester) async {
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: TileManager(child: SizedBox.shrink())),
      ),
    );
    await tester.pump();
    return tester.state(find.byType(TileManager)) as TileListener;
  }

  void markStarted() {
    container.read(runTimeProvider.notifier).value = 1;
  }

  void markInitialized() {
    container.read(initProvider.notifier).value = true;
  }

  testWidgets('renders its child untouched', (tester) async {
    await pumpTileManager(tester);

    expect(find.byType(SizedBox), findsOneWidget);
  });

  testWidgets('a tile start request starts a stopped core', (tester) async {
    final listener = await pumpTileManager(tester);
    markInitialized();

    listener.onStart();
    await tester.pumpAndSettle();

    expect(_RecordingSetupAction.requests, [true]);
  });

  testWidgets('a tile start request is ignored once already connected', (
    tester,
  ) async {
    final listener = await pumpTileManager(tester);
    markStarted();
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    await tester.pump();

    listener.onStart();
    await tester.pumpAndSettle();

    expect(_RecordingSetupAction.requests, isEmpty);
  });

  testWidgets('a tile start request still runs while the core reconnects', (
    tester,
  ) async {
    final listener = await pumpTileManager(tester);
    markInitialized();
    markStarted();
    container.read(coreStatusProvider.notifier).value = CoreStatus.connecting;
    await tester.pump();

    listener.onStart();
    await tester.pumpAndSettle();

    expect(_RecordingSetupAction.requests, [true]);
  });

  group('during startup', () {
    testWidgets('a tile start waits for the app to initialize', (tester) async {
      final listener = await pumpTileManager(tester);

      listener.onStart();
      listener.onStart();
      await tester.pumpAndSettle();
      expect(_RecordingSetupAction.requests, isEmpty);

      markInitialized();
      await tester.pumpAndSettle();
      expect(_RecordingSetupAction.requests, [true]);
    });

    testWidgets('a tile start is dropped once startup brought the run up', (
      tester,
    ) async {
      final listener = await pumpTileManager(tester);

      listener.onStart();
      markStarted();
      markInitialized();
      await tester.pumpAndSettle();

      expect(_RecordingSetupAction.requests, isEmpty);
    });

    testWidgets('a stop made after the tile start drops it', (tester) async {
      final listener = await pumpTileManager(tester);

      listener.onStart();
      markStarted();
      await tester.pump();
      container.read(runTimeProvider.notifier).value = null;
      await tester.pump();
      markInitialized();
      await tester.pumpAndSettle();

      expect(_RecordingSetupAction.requests, isEmpty);
    });
  });
}
