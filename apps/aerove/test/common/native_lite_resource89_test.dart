import 'dart:convert';
import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/path.dart';
import 'package:fl_clash/common/task.dart';
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
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:fl_clash/core/method.dart';
import 'package:crypto/crypto.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yaml/yaml.dart';
import '../../../aerove-lite/lib/lite_product.dart';
import '../helpers/app_paths.dart';
import 'native_lite_migration_test.dart' show FixtureAuthority;

class _ExactCore extends CoreHandlerInterface {
  var calls = 0;
  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) async {
    if (method == CoreMethod.getProfileBase ||
        method == CoreMethod.buildProfile ||
        method == CoreMethod.matchFilters) {
      if (method == CoreMethod.matchFilters) calls++;
      final command =
          jsonDecode(
                await File(
                  const String.fromEnvironment('PROFILE89_BRIDGE'),
                ).readAsString(),
              )
              as Map;
      final home = method == CoreMethod.matchFilters
          ? await appPath.homeDirPath
          : p.dirname(
              p.dirname(
                method == CoreMethod.getProfileBase
                    ? arguments as String
                    : (arguments as Map)['path'] as String,
              ),
            );
      final worker = await Process.start(command['executable'] as String, [
        ...(command['args'] as List).cast<String>(),
        home,
      ]);
      worker.stdin.write(
        jsonEncode({
          'id': 'fixture-profile',
          'method': method.name,
          'arguments': arguments,
        }),
      );
      await worker.stdin.close();
      final result =
          jsonDecode(await utf8.decoder.bind(worker.stdout).join()) as Map;
      if (await worker.exitCode != 0 || result.containsKey('error'))
        throw StateError(
          'real Native profile protocol failed: ${result['error']}',
        );
      return result['result'] as T;
    }
    throw StateError('unexpected fixture core method');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('unexpected fixture core action');
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
    'resource89 actual Native generation imports and dynamic stable bindings survive edits',
    () async {
      const fixture = String.fromEnvironment('RESOURCE89_FIXTURE');
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
      final engine = _ExactCore();
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
        await useAppPaths(home);
        globalState.packageInfo = PackageInfo(
          appName: 'AEROVE Lite',
          packageName: 'fixture',
          version: '1.0.0',
          buildNumber: '1',
        );
        container = ProviderContainer(
          overrides: [
            ...buildConfigOverrides(config),
            coreHandlerProvider.overrideWithValue(
              CoreController.scoped(engine),
            ),
          ],
        );
        final profilesSubscription = container.listen(
          profilesProvider,
          (_, _) {},
        );
        addTearDown(profilesSubscription.close);
        await container.read(profilesStreamProvider.future);
        final ordinary = profiles.firstWhere(
          (row) => row.label == 'Ordinary resource fixture',
        );
        final first = profiles.firstWhere(
          (row) => row.label == 'Bound fixture',
        );
        expect(config.currentProfileId, ordinary.id);
        expect(
          ordinary.overrides.proxyProviders['remote']!.sourceDefinition!['url'],
          'https://example.invalid/proxy',
        );
        expect(
          ordinary.overrides.nativeResourceInputs.length,
          greaterThanOrEqualTo(6),
        );
        for (final item in ordinary.overrides.nativeResourceInputs.values) {
          expect(
            sha256
                .convert(
                  await File(
                    p.join(home.path, item['file'] as String),
                  ).readAsBytes(),
                )
                .toString(),
            item['sha256'],
          );
          expect(
            sha256
                .convert(
                  await File(
                    p.join(home.path, item['receiptFile'] as String),
                  ).readAsBytes(),
                )
                .toString(),
            item['receiptSha256'],
          );
        }
        final ordinaryState = await container.read(
          setupStateProvider(ordinary.id).future,
        );
        final actualOrdinary = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: ordinaryState,
              patchConfig: config.patchClashConfig,
            );
        final ordinaryText = actualOrdinary.base == null
            ? actualOrdinary.yaml
            : await CoreController.scoped(
                engine,
              ).buildProfile(actualOrdinary.base!);
        final ordinaryYaml =
            jsonDecode(jsonEncode(loadYaml(ordinaryText))) as Map;
        final remote =
            (ordinaryYaml['proxy-providers'] as Map)['remote'] as Map;
        expect(remote['type'], 'http');
        expect(remote['url'], 'https://example.invalid/proxy');
        expect(remote['header'], {
          'X-Fixture': ['fixture-only'],
        });
        expect(
          await File(remote['path'] as String).readAsString(),
          contains('name: Remote'),
        );
        expect(
          await File(
            (ordinaryYaml['rule-providers'] as Map)['localRules']['path']
                as String,
          ).readAsString(),
          '+.local.invalid\r\n',
        );
        expect(p.isWithin(home.path, ordinaryYaml['mmdb'] as String), isTrue);
        expect(await File(ordinaryYaml['mmdb'] as String).readAsBytes(), [
          0,
          4,
          8,
          16,
          32,
        ]);
        expect(
          ordinaryYaml['mmdb-download-url'],
          'https://example.invalid/country',
        );
        expect((ordinaryYaml['dns'] as Map)['nameserver'], [
          'https://1.0.0.1/dns-query',
        ]);
        expect((ordinaryYaml['hosts'] as Map)['fixture.invalid'], [
          '127.0.0.4',
        ]);
        await File(
          p.join(home.path, 'ordinary-compiled.yaml'),
        ).writeAsString(ordinaryText);
        container
            .read(profilesProvider.notifier)
            .updateProfile(
              ordinary.id,
              (profile) => profile.copyWith.overrides(
                proxyProviders: {
                  ...profile.overrides.proxyProviders,
                  'remote': profile.overrides.proxyProviders['remote']!
                      .copyWith(filter: null, excludeFilter: '^changed'),
                },
              ),
            );
        await waitForDatabaseWrites();
        await container.pump();
        final savedOrdinary = (await database.profilesDao.query().get())
            .singleWhere((row) => row.id == ordinary.id);
        expect(
          savedOrdinary.overrides.proxyProviders['remote']!.filter,
          isNull,
        );
        container.invalidate(setupStateProvider(ordinary.id));
        final editedOrdinaryState = await container.read(
          setupStateProvider(ordinary.id).future,
        );
        final editedOrdinary = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: editedOrdinaryState,
              patchConfig: config.patchClashConfig,
            );
        final editedOrdinaryText = await CoreController.scoped(
          engine,
        ).buildProfile(editedOrdinary.base!);
        final editedProvider =
            (jsonDecode(jsonEncode(loadYaml(editedOrdinaryText)))
                    as Map)['proxy-providers']['remote']
                as Map;
        expect(editedProvider.containsKey('filter'), isFalse);
        expect(editedProvider['exclude-filter'], '^changed');
        expect(editedProvider['url'], 'https://example.invalid/proxy');
        expect(editedProvider['header'], {
          'X-Fixture': ['fixture-only'],
        });
        final originalOptions = ordinary.overrides.proxyProviders['remote']!;
        expect(originalOptions.editedFields, isEmpty);
        expect(
          savedOrdinary.overrides.proxyProviders['remote']!.editedFields,
          contains('exclude-filter'),
        );
        container
            .read(profilesProvider.notifier)
            .updateProfile(
              ordinary.id,
              (profile) => profile.copyWith.overrides(
                proxyProviders: {
                  ...profile.overrides.proxyProviders,
                  'remote': profile.overrides.proxyProviders['remote']!
                      .copyWith(
                        filter: originalOptions.filter,
                        excludeFilter: null,
                      ),
                },
              ),
            );
        await waitForDatabaseWrites();
        await container.pump();
        final reset = (await database.profilesDao.query().get())
            .singleWhere((row) => row.id == ordinary.id)
            .overrides
            .proxyProviders['remote']!;
        expect(reset.editedFields, contains('exclude-filter'));
        expect(reset.excludeFilter, isNull);
        container
            .read(profilesProvider.notifier)
            .updateProfile(
              ordinary.id,
              (profile) =>
                  profile.copyWith(label: 'Subscription refreshed label'),
            );
        await waitForDatabaseWrites();
        await container.pump();
        final refreshed = (await database.profilesDao.query().get())
            .singleWhere((row) => row.id == ordinary.id);
        expect(
          refreshed.overrides.proxyProviders['remote']!.editedFields,
          reset.editedFields,
        );
        final freshSource = {
          'type': 'http',
          'url': 'https://fresh.invalid/subscription',
          'exclude-filter': 'from-subscription',
          'unknown-new': 17,
        };
        final applied = jsonDecode(
          nativeProxyProviderScriptInput(
            jsonEncode({
              'proxy-providers': {'remote': freshSource},
            }),
            refreshed.overrides,
          ),
        )['proxy-providers']['remote'];
        expect(applied.containsKey('exclude-filter'), false);
        expect(applied['url'], 'https://fresh.invalid/subscription');
        expect(applied['unknown-new'], 17);
        container
            .read(profilesProvider.notifier)
            .updateProfile(
              ordinary.id,
              (profile) => profile.copyWith.overrides(
                proxyProviders: {
                  ...profile.overrides.proxyProviders,
                  'user-created': const ProxyProviderOptions(
                    filter: 'user-filter',
                  ),
                },
              ),
            );
        await waitForDatabaseWrites();
        await container.pump();
        final created = (await database.profilesDao.query().get())
            .singleWhere((row) => row.id == ordinary.id)
            .overrides
            .proxyProviders['user-created']!;
        expect(created.filter, 'user-filter');
        expect(created.editedFields, isNull);
        final initial = await container.read(
          setupStateProvider(first.id).future,
        );
        final built = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: initial,
              patchConfig: config.patchClashConfig,
            );
        final before = jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
        expect((before['proxy-groups'] as List).single['proxies'], [
          'DIRECT',
          'B two',
          'A one',
        ]);
        expect(
          (before['proxy-groups'] as List).single['icon'],
          'https://example.invalid/icon.png',
        );
        final groupSubscription = container.listen(
          proxyGroupsProvider(first.id),
          (_, _) {},
        );
        final iconSubscription = container.listen(iconSetsProvider, (_, _) {});
        addTearDown(groupSubscription.close);
        addTearDown(iconSubscription.close);
        final groups = await container.read(
          proxyGroupsProvider(first.id).future,
        );
        final iconSet = (await container.read(iconSetsProvider.future)).single;
        final stableGroupId = groups.single.id;
        container
            .read(proxyGroupsProvider(first.id).notifier)
            .put(groups.single.copyWith(name: 'Renamed group'));
        container
            .read(iconSetsProvider.notifier)
            .put(iconSet.copyWith(name: 'Renamed icon set'));
        container
            .read(appSettingProvider.notifier)
            .update(
              (value) => value.copyWith(
                filters: [
                  for (final filter in value.filters)
                    filter.copyWith(label: 'Renamed ${filter.label}'),
                ],
              ),
            );
        await waitForDatabaseWrites();
        await container.pump();
        container.invalidate(setupStateProvider(first.id));
        final editedState = await container.read(
          setupStateProvider(first.id).future,
        );
        expect(
          editedState.overrides.filterBindings.keys,
          contains(stableGroupId),
        );
        final edited = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: editedState,
              patchConfig: config.patchClashConfig,
            );
        final renamed = jsonDecode(jsonEncode(loadYaml(edited.yaml))) as Map;
        expect(
          (renamed['proxy-groups'] as List).single['name'],
          'Renamed group',
        );
        expect((renamed['proxy-groups'] as List).single['proxies'], [
          'DIRECT',
          'B two',
          'A one',
        ]);
        expect(
          (renamed['proxy-groups'] as List).single['icon'],
          'https://example.invalid/icon.png',
        );
        final firstFilterId = config.appSettingProps.filters.first.id;
        container
            .read(appSettingProvider.notifier)
            .update(
              (value) => value.copyWith(
                filters: [
                  for (final filter in value.filters)
                    filter.id == firstFilterId
                        ? filter.copyWith(regex: '^A')
                        : filter,
                ],
              ),
            );
        container.invalidate(setupStateProvider(first.id));
        final changedState = await container.read(
          setupStateProvider(first.id).future,
        );
        final changed = await container
            .read(setupActionProvider.notifier)
            .getProfile(
              setupState: changedState,
              patchConfig: config.patchClashConfig,
            );
        final changedYaml =
            jsonDecode(jsonEncode(loadYaml(changed.yaml))) as Map;
        expect((changedYaml['proxy-groups'] as List).single['proxies'], [
          'DIRECT',
          'A one',
        ]);
        expect(engine.calls, 3);
        final output = const String.fromEnvironment('RESOURCE89_OUTPUT');
        if (output.isNotEmpty) {
          final target = Directory(output);
          await target.create(recursive: true);
          for (final item in ordinary.overrides.nativeResourceInputs.values) {
            for (final key in ['file', 'receiptFile']) {
              final relative = item[key] as String;
              final file = File(p.join(target.path, relative));
              await file.parent.create(recursive: true);
              await File(p.join(home.path, relative)).copy(file.path);
            }
          }
          await File(
            p.join(target.path, 'ordinary-compiled.yaml'),
          ).writeAsString(ordinaryText.replaceAll(home.path, target.path));
        }
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
