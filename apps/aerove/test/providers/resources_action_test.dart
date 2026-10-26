import 'package:fl_clash/common/maintenance_visibility.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/widgets.dart' show AppLifecycleState, Locale;
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:riverpod/riverpod.dart';

class _MockCoreHandlerInterface extends Mock implements CoreHandlerInterface {}

class _AutoResourcesAction extends ResourcesAction {
  final updated = <int>[];

  @override
  Future<void> updateIconSet(
    IconSet iconSet, {
    bool Function()? isCurrent,
  }) async {
    updated.add(iconSet.id);
    ref
        .read(maintenanceVisibleProvider.notifier)
        .setLifecycle(AppLifecycleState.hidden);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late db.Database testDatabase;
  late ProviderContainer container;
  late Map<String, int> requests;

  setUpAll(() => AppLocalizations.load(const Locale('en')));

  setUp(() {
    testDatabase = db.Database(NativeDatabase.memory());
    db.database = testDatabase;
    requests = {};
    final core = _MockCoreHandlerInterface();
    when(() => core.updateGeoData(any())).thenAnswer((invocation) async {
      final name = invocation.positionalArguments.single as String;
      final count = requests[name] = (requests[name] ?? 0) + 1;
      return count == 1 ? '' : 'geo update already in progress';
    });
    container = ProviderContainer(
      overrides: [
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      ],
    );
  });

  tearDown(() async {
    container.dispose();
    await testDatabase.close();
  });

  test('automatic resources stop between downloads when hidden', () async {
    final sets = [
      for (var index = 1; index <= 2; index++)
        IconSet(id: index, name: '$index', url: 'https://example.com/$index'),
    ];
    await testDatabase.iconSetsDao.putAll([
      for (final iconSet in sets) iconSet.toCompanion(),
    ]);
    final scoped = ProviderContainer(
      overrides: [
        resourcesActionProvider.overrideWith(_AutoResourcesAction.new),
        patchClashConfigProvider.overrideWithBuild(
          (_, _) => const PatchClashConfig(geoAutoUpdate: true),
        ),
      ],
    );
    addTearDown(scoped.dispose);
    final action =
        scoped.read(resourcesActionProvider.notifier) as _AutoResourcesAction;
    scoped.read(appVisibleProvider.notifier).value = false;
    scoped
        .read(maintenanceVisibleProvider.notifier)
        .setLifecycle(AppLifecycleState.hidden);
    await action.autoUpdate();
    expect(action.updated, isEmpty);
    scoped
        .read(maintenanceVisibleProvider.notifier)
        .setLifecycle(AppLifecycleState.inactive);
    await action.autoUpdate();
    expect(action.updated, [sets.first.id]);
  });

  test(
    'an update all that overlaps one running skips what that one runs',
    () async {
      final action = container.read(resourcesActionProvider.notifier);

      final failures = await Future.wait([
        action.updateAll(),
        action.updateAll(),
      ]);

      expect(failures.expand((items) => items), isEmpty);
      expect(requests, {
        for (final geoResource in GeoResource.values) geoResource.name: 1,
      });
    },
  );
}
