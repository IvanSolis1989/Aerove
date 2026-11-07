import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/path.dart';
import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:flutter/foundation.dart' show ValueListenable, listEquals;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mocktail/mocktail.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yaml/yaml.dart';
import '../../../aerove-lite/lib/lite_product.dart';
import '../helpers/test_profiles.dart';
import '../helpers/app_paths.dart';
import 'native_lite_migration_test.dart' show FixtureAuthority;

class _UnusedCore extends Mock implements CoreHandlerInterface {}

class _BuilderSourceInspection implements CoreRequestQuiescence {
  final _closed = Completer<void>();
  @override
  bool get isQuiescent => _closed.isCompleted;
  @override
  Future<void> get whenQuiescent => _closed.future;
  void joined() => _closed.complete();
}

/// Only the fixed source fixture is recognized; this is not a native parser or Android receipt.
class _BuilderSourceOnlyCore extends Mock
    implements CoreHandlerInterface, CancellableCoreRequest {
  final inspections = <_BuilderSourceInspection>[];
  final inspectedDigests = <String>[];

  @override
  Future<T?> invokeCancellableMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
    required ValueListenable<bool> cancellation,
    void Function(CoreRequestQuiescence state)? observeQuiescence,
  }) async {
    if (method != CoreMethod.compileRuleSet || timeout != null) {
      throw UnsupportedError(
        'Source fixture only supports local compile inspection',
      );
    }
    final owner = _BuilderSourceInspection();
    inspections.add(owner);
    observeQuiescence?.call(owner);
    RandomAccessFile? input;
    void current() {
      if (cancellation.value) {
        throw const CoreMethodException(
          code: 'cancelled',
          message: 'Source fixture inspection cancelled',
        );
      }
    }

    try {
      current();
      final row = Map<String, dynamic>.from(arguments as Map);
      final name = row['name'];
      if (row.length != 2 ||
          row['url'] != '' ||
          name is! String ||
          !RegExp(r'^[0-9a-f]{32}\.[0-9]+\.new$').hasMatch(name)) {
        throw StateError(
          'Source fixture requires the actual owned local rule stage',
        );
      }
      final path = await appPath.getProviderCachePath(name);
      final file = File(path);
      await nativeLitePrivateDestination(
        file,
        Directory(await appPath.homeDirPath),
      );
      if (await FileSystemEntity.type(path, followLinks: false) !=
          FileSystemEntityType.file) {
        throw StateError('Source fixture stage is not a regular private file');
      }
      input = await file.open(mode: FileMode.read);
      final bytes = BytesBuilder(copy: false);
      final expected = utf8.encode('+.example.invalid\r\n');
      while (true) {
        current();
        final chunk = await input.read(8192);
        if (chunk.isEmpty) break;
        bytes.add(chunk);
        if (bytes.length > expected.length) {
          throw StateError(
            'Source fixture stage exceeds its fixed declared input',
          );
        }
      }
      current();
      final actual = bytes.takeBytes();
      if (!listEquals(actual, expected)) {
        throw StateError(
          'Source fixture inspector does not qualify this rule payload',
        );
      }
      final digest = sha256.convert(actual).toString();
      inspectedDigests.add(digest);
      return <String, dynamic>{
            'behavior': 'domain',
            'format': 'text',
            'sourceSha256': digest,
          }
          as T;
    } finally {
      // A failed file close must not grant quiescence or allow stage deletion.
      await input?.close();
      owner.joined();
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});
  try {
    ClientProduct.current;
  } on StateError {
    ClientProduct.configure(
      product: liteProduct,
      backendCoreId: 'meow-rs',
      backendFactory: () => throw StateError('fixture must not start a core'),
    );
  }
  test(
    'fresh null selection uses the real Lite producer and no core profile read',
    () async {
      final home = await Directory.systemTemp.createTemp(
        'native-empty-producer-',
      );
      final database = db.Database(
        NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
      );
      final prior = db.database;
      db.database = database;
      final storage = await SharedPreferences.getInstance();
      await storage.clear();
      ProviderContainer? container;
      try {
        final config = Config(
          currentProfileId: null,
          themeProps: defaultThemeProps,
          patchClashConfig: newPatchClashConfig(
            'meow-rs',
          ).copyWith(hosts: {'fresh.test': '127.0.0.2'}, mixedPort: 7897),
        );
        await useAppPaths(home);
        globalState.packageInfo = PackageInfo(
          appName: 'AEROVE Lite',
          packageName: 'fixture',
          version: '1.0.0',
          buildNumber: '1',
        );
        final core = _UnusedCore();
        container = ProviderContainer(
          overrides: [
            ...buildConfigOverrides(config),
            profilesProvider.overrideWith(() => TestProfiles([])),
            coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
          ],
        );
        final state = await container.read(setupStateProvider(null).future);
        expect(state.profileId, isNull);
        final built = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: state,
              patchConfig: config.patchClashConfig,
            );
        expect(built.yaml, isNotEmpty);
        final yaml = jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
        expect(built.base, isNull);
        expect(built.md5, isNotEmpty);
        expect(yaml['aerove-geo-origin'], isA<Map>());
        expect(yaml['mixed-port'], 7897);
        expect((yaml['hosts'] as Map)['fresh.test'], '127.0.0.2');
        expect(yaml['dns'], isA<Map>());
        expect(built.selectionPolicy!.binding, isNull);
        verifyNever(() => core.getProfileBase(any()));
        verifyNever(() => core.getConfig(any()));
        expect(
          await Directory(p.join(home.path, 'profiles', 'null')).exists(),
          isFalse,
        );
        final proof = await nativeLiteBusinessSource(
          database,
          home,
          storage,
          config,
        );
        for (final row in (proof['tables'] as Map).values) {
          expect((row as Map)['rows'], 0);
        }
        await database.profilesDao.putAll([
          const Profile(
            id: 981,
            type: ProfileType.custom,
            label: 'Unselected',
            autoUpdateDuration: Duration.zero,
          ).toCompanion(),
        ]);
        expect(
          await nativeLiteBusinessSource(database, home, storage, config),
          proof,
        );
        expect(
          (await container
                  .read(setupActionProvider.notifier)
                  .getProfile(
                    setupState: state,
                    patchConfig: config.patchClashConfig,
                  ))
              .yaml,
          built.yaml,
        );
      } finally {
        container?.dispose();
        db.database = prior;
        await database.close();
        await home.delete(recursive: true);
      }
    },
  );

  test(
    'Native Builder import through actual setup state and YAML keeps scoped nodes and local bytes',
    () async {
      const fixture = String.fromEnvironment('NATIVE_LITE_BUILDER_FIXTURE');
      final descriptor = jsonDecode(await File(fixture).readAsString()) as Map;
      final source = await NativeLiteExport.open(
        File(descriptor['manifest'] as String),
        descriptor['sha256'] as String,
      );
      final home = await Directory.systemTemp.createTemp(
        'native-builder-drift-',
      );
      final database = db.Database(
        NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
      );
      final prior = db.database;
      db.database = database;
      final storage = await SharedPreferences.getInstance();
      await storage.clear();
      var next = 500;
      final defaults = XClashDefaults(
        storage: storage,
        database: database,
        directory: home,
        loadScript: () async => throw StateError('must not seed'),
        createId: () => 9999,
      );
      ProviderContainer? container;
      try {
        await NativeLiteMigration(
          home: home,
          database: database,
          preferences: storage,
          defaults: defaults,
          authority: FixtureAuthority(source, home),
          createId: () => ++next,
        ).run(source);
        final profiles = await database.profilesDao.query().get();
        final config = Config.strictFromJson(
          Map<String, Object?>.from(
            jsonDecode(storage.getString(configKey)!) as Map,
          ),
        );
        final providers = await database.clashProvidersDao.query().get();
        expect(providers.single.sourceDefinition!['interval'], 3600);
        final edited = providers.single.copyWith(
          url: 'https://example.invalid/edited',
          behavior: RuleProviderBehavior.ipcidr,
        );
        expect(
          edited.definition('private.mrs', coreId: 'mihomo')['url'],
          edited.url,
        );
        expect(
          edited.definition('private.mrs', coreId: 'mihomo')['behavior'],
          'ipcidr',
        );
        expect(
          edited.definition('private.mrs', coreId: 'mihomo')['interval'],
          3600,
        );
        expect(
          (await database.iconSetsDao.query().get())
              .single
              .lastUpdateTime!
              .millisecondsSinceEpoch,
          1700000000789,
        );
        await useAppPaths(home);
        globalState.packageInfo = PackageInfo(
          appName: 'AEROVE Lite',
          packageName: 'fixture',
          version: '1.0.0',
          buildNumber: '1',
        );
        final sourceCore = _BuilderSourceOnlyCore();
        container = ProviderContainer(
          overrides: [
            ...buildConfigOverrides(config),
            profilesProvider.overrideWith(() => TestProfiles(profiles)),
            coreHandlerProvider.overrideWithValue(
              CoreController.scoped(sourceCore),
            ),
          ],
        );
        final first = profiles.firstWhere(
          (row) => row.label == 'Fixture custom',
        );
        final second = profiles.firstWhere(
          (row) => row.label == 'Fixture custom other',
        );
        final state = await container.read(setupStateProvider(first.id).future);
        final other = await container.read(
          setupStateProvider(second.id).future,
        );
        expect(state.appProxies.single.definition['server'], '127.0.0.2');
        expect(other.appProxies.single.definition['server'], '127.0.0.3');
        expect(state.appProxies.single.id, isNot(other.appProxies.single.id));
        final built = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: state,
              patchConfig: config.patchClashConfig,
            );
        expect(built.yaml, isNotEmpty);
        final yaml = jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
        expect(sourceCore.inspections, isNotEmpty);
        expect(
          sourceCore.inspections.every((owner) => owner.isQuiescent),
          isTrue,
        );
        expect(sourceCore.inspectedDigests.toSet(), {
          sha256.convert(utf8.encode('+.example.invalid\r\n')).toString(),
        });
        verifyNever(() => sourceCore.getProfileBase(any()));
        verifyNever(() => sourceCore.getConfig(any()));
        expect(yaml['custom-fact'], {'preserve': true});
        expect((yaml['proxies'] as List).single['server'], '127.0.0.2');
        expect((yaml['proxy-groups'] as List).single['proxies'], [
          'Local SS',
          'DIRECT',
        ]);
        final path = (yaml['rule-providers'] as Map)['local']['path'] as String;
        expect(p.isWithin(home.path, path), isTrue);
        expect(await File(path).readAsString(), '+.example.invalid\r\n');
        final proof = await nativeLiteBusinessSource(
          database,
          home,
          storage,
          config,
        );
        expect(
          await nativeLiteBusinessSource(database, home, storage, null),
          proof,
        );
        final before = await File(path).readAsBytes();
        await File(path).writeAsString('+.changed.invalid\r\n');
        expect(
          await nativeLiteBusinessSource(database, home, storage, null),
          isNot(proof),
        );
        await File(path).writeAsBytes(before);
        expect(
          await nativeLiteBusinessSource(database, home, storage, null),
          proof,
        );
        final subscription = container.listen(customProxiesProvider, (_, _) {});
        addTearDown(subscription.close);
        await container.read(customProxiesProvider.future);
        final node = state.appProxies.single;
        container
            .read(customProxiesProvider.notifier)
            .putForProfile(
              node.copyWith(
                definition: {...node.definition, 'name': 'Renamed fixture'},
              ),
              first.id,
            );
        await waitForDatabaseWrites();
        final firstGroups = await database.proxyGroupsDao.query(first.id).get();
        final otherGroups = await database.proxyGroupsDao
            .query(second.id)
            .get();
        expect(firstGroups.single.proxies, contains('Renamed fixture'));
        expect(otherGroups.single.proxies, contains('Local SS'));
        final created = CustomProxy(
          id: 999001,
          definition: {...node.definition, 'name': 'Created fixture'},
        );
        container
            .read(customProxiesProvider.notifier)
            .putForProfile(created, first.id);
        await waitForDatabaseWrites();
        var saved = (await database.profilesDao.query().get()).singleWhere(
          (row) => row.id == first.id,
        );
        expect(saved.overrides.scopedCustomProxyIds, contains(created.id));
        expect(saved.overrides.ownedCustomProxyIds, contains(created.id));
        expect(
          (await database.profilesDao.query().get())
              .singleWhere((row) => row.id == second.id)
              .overrides
              .scopedCustomProxyIds,
          isNot(contains(created.id)),
        );
        container.read(customProxiesProvider.notifier).delAll([created.id]);
        await waitForDatabaseWrites();
        saved = (await database.profilesDao.query().get()).singleWhere(
          (row) => row.id == first.id,
        );
        expect(
          saved.overrides.scopedCustomProxyIds,
          isNot(contains(created.id)),
        );
        expect(
          saved.overrides.ownedCustomProxyIds,
          isNot(contains(created.id)),
        );
        await source.verify();
      } finally {
        container?.dispose();
        db.database = prior;
        await database.close();
        await home.delete(recursive: true);
      }
    },
  );
}
