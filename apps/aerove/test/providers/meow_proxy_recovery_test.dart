import 'dart:async';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/keep_scope.dart';
import 'package:fl_clash/views/proxies/proxies.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../../../aerove-lite/lib/lite_product.dart';
import '../helpers/app_paths.dart';
import '../helpers/preferences.dart';
import '../helpers/test_profiles.dart';
import '../helpers/test_app.dart';

const _absent = CoreMethodException(code: 'not_running', message: 'No graph');
const _emptySetup = SetupState(
  profileId: null,
  profileLastUpdateDate: null,
  profileType: ProfileType.file,
  extendType: ExtendType.standard,
  rules: [],
  proxyGroups: [],
  addedRules: [],
  script: null,
  overrides: ProfileOverrides(),
);

const _groups = <Group>[
  Group(type: GroupType.Selector, name: 'Proxy', all: []),
];

class _RecoveryCore extends Mock implements CoreController {
  int reads = 0;
  int applies = 0;
  bool available = false;
  bool restoreGraph = true;
  bool recoverReadAfterFailure = false;
  Exception readFailure = _absent;
  Object? applyFailure;
  Completer<void>? readGate;
  Completer<void>? applyGate;
  Completer<void>? applied;
  ValueListenable<bool>? setupCancellation;
  _PendingRequest? pendingRequest;
  SetupParams? params;

  @override
  Future<List<Group>> getProxiesGroups({
    required ProxiesSortType sortType,
    required Map<String, Map<String, int?>> delayMap,
    required Map<String, String> selectedMap,
    required String defaultTestUrl,
  }) async {
    reads++;
    await readGate?.future;
    if (!available) {
      if (recoverReadAfterFailure) available = true;
      throw readFailure;
    }
    return _groups;
  }

  @override
  Future<String> setupConfig({
    required SetupParams params,
    Future<void> Function()? preloadInvoke,
    ValueListenable<bool>? cancellation,
    void Function(CoreRequestQuiescence state)? observeQuiescence,
  }) async {
    applies++;
    this.params = params;
    setupCancellation = cancellation;
    if (pendingRequest case final request?) observeQuiescence?.call(request);
    applied?.complete();
    await applyGate?.future;
    if (applyFailure case final failure?) {
      Error.throwWithStackTrace(failure, StackTrace.current);
    }
    if (cancellation?.value != true) available = restoreGraph;
    expect(preloadInvoke, isNull);
    return '';
  }

  @override
  Future<bool> stopListener() async => true;

  @override
  void resetTraffic() {}
}

class _PendingRequest implements CoreRequestQuiescence {
  final joined = Completer<void>();
  @override
  bool get isQuiescent => joined.isCompleted;
  @override
  Future<void> get whenQuiescent => joined.future;
}

class _BuiltSetup extends SetupAction {
  Completer<void>? buildGate;
  Completer<void>? building;

  @override
  Future<BuiltProfile> getProfile({
    required SetupState setupState,
    required PatchClashConfig patchConfig,
  }) async {
    if (building?.isCompleted == false) building!.complete();
    await buildGate?.future;
    return (
      yaml: 'proxies: []\nproxy-groups: []\nrules: []\n',
      md5: '8cc022da3c0ff14fc6f13fa5db8851ac',
      counts: (groups: 0, proxies: 0, rules: 0),
      base: null,
      selectionPolicy: null,
      providerSourceWitness: const <String>[],
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  ClientProduct.configure(
    product: liteProduct,
    backendCoreId: 'meow-rs',
    backendFactory: () => throw StateError('No native backend in unit tests'),
  );
  late Directory home;
  late db.Database database;
  late ProviderContainer container;
  late _RecoveryCore core;
  late _BuiltSetup setup;
  late ProxiesAction proxies;
  late GlobalKey proxyPageKey;

  setUp(() async {
    home = await Directory.systemTemp.createTemp('proxy-recovery-');
    await useAppPaths(home);
    await mockPreferences();
    await AppLocalizations.load(const Locale('en'));
    database = db.Database(
      NativeDatabase(File('${home.path}/database.sqlite')),
    );
    db.database = database;
    core = _RecoveryCore();
    setup = _BuiltSetup();
    proxyPageKey = GlobalKey();
    container = ProviderContainer(
      overrides: [
        navigationItemsStateProvider.overrideWithValue(
          NavigationItemsState(
            value: [
              NavigationItem(
                glyph: AppGlyphs.dashboard,
                label: PageLabel.dashboard,
                builder: (_) => const SizedBox(),
              ),
              NavigationItem(
                glyph: AppGlyphs.proxies,
                label: PageLabel.proxies,
                builder: (_) => ProxiesView(key: proxyPageKey),
              ),
              NavigationItem(
                glyph: AppGlyphs.profiles,
                label: PageLabel.profiles,
                builder: (_) => const SizedBox(),
              ),
            ],
          ),
        ),
        profilesProvider.overrideWith(TestProfiles.new),
        currentProfileProvider.overrideWithValue(null),
        setupStateProvider.overrideWith((_, _) => _emptySetup),
        coreHandlerProvider.overrideWithValue(core),
        setupActionProvider.overrideWith(() => setup),
      ],
    );
    globalState.container = container;
    globalState.packageInfo = PackageInfo(
      appName: 'test',
      packageName: 'test',
      version: '1',
      buildNumber: '1',
    );
    globalState.lastConfigMd5 = null;
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    proxies = container.read(proxiesActionProvider.notifier);
    container.read(groupsReadFailedProvider.notifier).value = true;
  });
  tearDown(() async {
    container.dispose();
    await database.close();
    await home.delete(recursive: true);
  });

  for (final width in [500.0, 1000.0]) {
    for (final target in [PageLabel.dashboard, PageLabel.profiles]) {
      testWidgets('kept-alive $width navigation to $target cancels Retry', (
        tester,
      ) async {
        tester.view.physicalSize = Size(width, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        container.read(viewSizeProvider.notifier).value = Size(width, 800);
        final navigation = container.read(currentPageLabelProvider.notifier);
        navigation.toPage(PageLabel.proxies);
        core.readGate = Completer<void>();
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: const TestApp(includeNavigatorKey: false, child: HomePage()),
          ),
        );
        await tester.pumpAndSettle();
        final page = tester.state(find.byKey(proxyPageKey));
        expect(
          find.ancestor(
            of: find.byKey(proxyPageKey),
            matching: find.byType(KeepScope),
          ),
          findsOneWidget,
        );
        await tester.tap(find.text(AppLocalizations.current.retry));
        await tester.pump();
        expect(core.reads, 1);

        navigation.toPage(target);
        await tester.pumpAndSettle();
        expect(proxyPageKey.currentState, same(page));
        expect(page.mounted, isTrue);
        expect(container.read(appVisibleProvider), isTrue);
        expect(
          proxyPageKey.currentContext!
              .getInheritedWidgetOfExactType<PageActivityScope>()!
              .isActive,
          isFalse,
        );
        core.readGate!.complete();
        await tester.pump();
        await tester.pump(const Duration(seconds: 1));
        expect(core.reads, 1);
        expect(core.applies, 0);
        expect(container.read(groupsReadFailedProvider), isTrue);

        navigation.toPage(PageLabel.proxies);
        await tester.pumpAndSettle();
        expect(proxyPageKey.currentState, same(page));
        core.readGate = Completer<void>();
        await tester.tap(find.text(AppLocalizations.current.retry));
        await tester.pump();
        expect(core.reads, 2);
        await tester.pumpWidget(const SizedBox());
        core.readGate!.complete();
        await tester.pump();
        expect(core.applies, 0);
        expect(tester.takeException(), isNull);
      });
    }
  }

  test(
    'explicit unavailable Retry rebuilds once with source proof and reads again',
    () async {
      expect(await proxies.retryGroups(), isTrue);
      expect(core.reads, 4);
      expect(core.applies, 1);
      expect(core.params!.dataSourceProof, isNotNull);
      expect(core.params!.applied, isNotNull);
      expect(core.setupCancellation, isNotNull);
      expect(container.read(groupsProvider), _groups);
      expect(container.read(groupsReadFailedProvider), isFalse);
      expect(container.read(isStartProvider), isFalse);
    },
  );

  test('ordinary refresh never rebuilds a missing graph', () async {
    expect(await proxies.updateGroups(), isFalse);
    expect(core.reads, 3);
    expect(core.applies, 0);
  });

  test('transient failures only reread without rebuilding', () async {
    core.readFailure = TimeoutException('read timeout');
    core.recoverReadAfterFailure = true;
    expect(await proxies.retryGroups(), isTrue);
    expect(core.reads, 2);
    expect(core.applies, 0);
  });

  test(
    'persistent transport errors do not authorize a graph rebuild',
    () async {
      core.readFailure = TimeoutException('read timeout');
      expect(await proxies.retryGroups(), isFalse);
      expect(core.reads, 3);
      expect(core.applies, 0);
      expect(container.read(groupsReadFailedProvider), isTrue);
    },
  );

  test(
    'a recovery receipt without readable groups is not success or a loop',
    () async {
      core.restoreGraph = false;
      expect(await proxies.retryGroups(), isFalse);
      expect(core.reads, 6);
      expect(core.applies, 1);
      expect(container.read(groupsReadFailedProvider), isTrue);
    },
  );

  test('failed setup leaves Retry actionable without a second setup', () async {
    core.applyFailure = StateError('GRAPH_OPEN failed');
    expect(await proxies.retryGroups(), isFalse);
    expect(core.applies, 1);
    expect(core.reads, 3);
    expect(container.read(groupsReadFailedProvider), isTrue);
  });

  test(
    'leaving while reading does not start recovery or clear the error',
    () async {
      final cancel = ValueNotifier(false);
      addTearDown(cancel.dispose);
      core.readGate = Completer<void>();
      final retry = proxies.retryGroups(cancellation: cancel);
      cancel.value = true;
      core.readGate!.complete();
      expect(await retry, isFalse);
      expect(core.reads, 1);
      expect(core.applies, 0);
      expect(container.read(groupsReadFailedProvider), isTrue);
    },
  );

  for (final intent in ['leave', 'profile', 'stop', 'hide', 'disconnect']) {
    test('$intent supersedes setup before native publication', () async {
      final cancel = ValueNotifier(false);
      addTearDown(cancel.dispose);
      setup.buildGate = Completer<void>();
      setup.building = Completer<void>();
      final retry = proxies.retryGroups(cancellation: cancel);
      await setup.building!.future;
      switch (intent) {
        case 'leave':
          cancel.value = true;
        case 'profile':
          container.read(currentProfileIdProvider.notifier).value = 8;
        case 'stop':
          await setup.setRunning(false);
        case 'hide':
          container.read(appVisibleProvider.notifier).value = false;
        case 'disconnect':
          container.read(coreStatusProvider.notifier).value =
              CoreStatus.disconnected;
      }
      setup.buildGate!.complete();
      expect(await retry, isFalse);
      expect(core.applies, 0);
      expect(core.reads, 3);
    });
  }

  test('a newer Retry supersedes the older setup owner', () async {
    setup.buildGate = Completer<void>();
    setup.building = Completer<void>();
    final old = proxies.retryGroups();
    await setup.building!.future;
    final newer = proxies.retryGroups();
    setup.buildGate!.complete();
    expect(await old, isFalse);
    expect(await newer, isTrue);
    expect(core.applies, 1);
    expect(container.read(groupsReadFailedProvider), isFalse);
  });

  test(
    'leaving cancels an in-flight setup and ignores its late success',
    () async {
      final cancel = ValueNotifier(false);
      addTearDown(cancel.dispose);
      core.applyGate = Completer<void>();
      core.applied = Completer<void>();
      final retry = proxies.retryGroups(cancellation: cancel);
      await core.applied!.future;
      cancel.value = true;
      expect(core.setupCancellation!.value, isTrue);
      core.applyGate!.complete();
      expect(await retry, isFalse);
      expect(core.applies, 1);
      expect(core.reads, 3);
      expect(container.read(groupsReadFailedProvider), isTrue);
      expect(globalState.lastConfigMd5, isNull);
    },
  );

  test(
    'unconfirmed native cancellation fences later setup until joined',
    () async {
      final request = core.pendingRequest = _PendingRequest();
      core.applyFailure = const CoreMethodException(
        code: 'termination_unconfirmed',
        message: 'Cancellation not joined',
      );
      expect(await proxies.retryGroups(), isFalse);
      expect(await proxies.retryGroups(), isFalse);
      expect(core.applies, 1);
      request.joined.complete();
      await pumpEventQueue();
      core.pendingRequest = null;
      core.applyFailure = null;
      expect(await proxies.retryGroups(), isTrue);
      expect(core.applies, 2);
    },
  );
}
