import 'dart:convert';
import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/task.dart';
import 'package:yaml/yaml.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('canonical proof keeps Base64 slash literal across Android and Dart', () {
    expect(nativeLiteJsonHash('专线'),
        'fc39ea0670f02c52119d3cee45a9c5173cabe621dc2f6546b6523cf7c5bae3b5');
    expect(nativeLiteJsonHash('https://example.com/?target=ClashMeta'),
        'efa0b01208868aba8dbb6c59e31bd9670966da88c60141f638744d879d0646ab');
  });
  test(
    'selected SQLite closure binds real nodes/scripts/files and ignores theme and unrelated profiles',
    () async {
      SharedPreferences.setMockInitialValues({});
      final storage = await SharedPreferences.getInstance();
      final home = await Directory.systemTemp.createTemp('source-proof89-');
      final db = Database(NativeDatabase(File('${home.path}/database.sqlite')));
      try {
        Future<void> putProxies(List<CustomProxiesCompanion> items) => db.batch(
          (batch) => batch.insertAllOnConflictUpdate(db.customProxies, items),
        );
        const profile = Profile(
          id: 1,
          autoUpdateDuration: Duration.zero,
          type: ProfileType.custom,
          label: 'Selected 中文\u0085</',
          extendType: ExtendType.script,
          scriptId: 5,
          overrides: ProfileOverrides(
            scopedCustomProxyIds: [10],
            ownedCustomProxyIds: {10},
            applyExtensionToCustom: true,
          ),
        );
        const other = Profile(
          id: 2,
          autoUpdateDuration: Duration.zero,
          type: ProfileType.custom,
          label: 'Unrelated',
          overrides: ProfileOverrides(
            scopedCustomProxyIds: [20],
            ownedCustomProxyIds: {20},
          ),
        );
        await db.profilesDao.putAll([
          profile.toCompanion(),
          other.toCompanion(),
        ]);
        await db.scriptsDao.putAll([
          Script(
            id: 5,
            label: 'active',
            lastUpdateTime: DateTime.fromMillisecondsSinceEpoch(0),
          ).toCompanion(),
        ]);
        await putProxies([
          const CustomProxy(
            id: 10,
            definition: {'name': 'active', 'type': 'direct'},
          ).toCompanion(),
          const CustomProxy(
            id: 20,
            definition: {'name': 'other', 'type': 'direct'},
          ).toCompanion(),
        ]);
        await Directory('${home.path}/scripts').create();
        final script = File('${home.path}/scripts/5.js');
        await script.writeAsString('function main(config) { return config; }');
        final config = Config(
          currentProfileId: 1,
          themeProps: defaultThemeProps,
          patchClashConfig: newPatchClashConfig('meow-rs'),
        );
        final first = await nativeLiteBusinessSource(db, home, storage, config);
        final producer = await makeRealProfileTask(
          MakeRealProfileState(
            profilesPath: home.path,
            profileId: 1,
            rawConfig: {
              'rules': ['MATCH,DIRECT'],
            },
            realPatchConfig: config.patchClashConfig,
            appendSystemDns: false,
            proxyGroups: [],
            rules: [],
            addedRules: [],
            defaultUA: 'source-proof89 fixture',
            safeMode: true,
          ),
          coreId: 'meow-rs',
        );
        final compiled = jsonDecode(jsonEncode(loadYaml(producer.yaml))) as Map;
        expect(compiled['aerove-geo-origin'], isA<Map>());
        expect(compiled['rules'], ['MATCH,DIRECT']);
        final all = <String, Object?>{};
        for (final name in (first['tables'] as Map).keys.cast<String>()) {
          all[name] =
              (await db
                      .customSelect('SELECT * FROM "$name" ORDER BY rowid')
                      .get())
                  .map((row) => row.data)
                  .toList();
        }
        const output = String.fromEnvironment('SOURCE_PROOF89_OUTPUT');
        if (output.isNotEmpty)
          await File(output).writeAsString(
            jsonEncode({'config': config, 'all': all, 'proof': first}),
          );
        if (output.isNotEmpty) {
          await File('$output.full-producer.yaml').writeAsString(producer.yaml);
          await File('$output.producer.yaml').writeAsString(
            await encodeYamlTask({
              'aerove-geo-origin': compiled['aerove-geo-origin'],
              'rules': compiled['rules'],
            }),
          );
        }
        final themed = jsonDecode(jsonEncode(config)) as Map<String, dynamic>;
        themed['themeProps']['primaryColor'] = 42;
        themed['themeProps']['fontFamily'] = 'fixture-font';
        themed['appSettingProps']['onlyStatisticsProxy'] = false;
        themed['appSettingProps']['showNotificationStopAction'] = false;
        await storage.setString('config', jsonEncode(themed));
        expect(await nativeLiteBusinessSource(db, home, storage, null), first);
        await db.profilesDao.putAll([
          other.copyWith(label: 'Edited unrelated').toCompanion(),
        ]);
        await putProxies([
          const CustomProxy(
            id: 20,
            definition: {'name': 'edited-other', 'type': 'direct'},
          ).toCompanion(),
        ]);
        expect(await nativeLiteBusinessSource(db, home, storage, null), first);
        await putProxies([
          const CustomProxy(
            id: 10,
            definition: {'name': 'changed-selected', 'type': 'direct'},
          ).toCompanion(),
        ]);
        expect(
          (await nativeLiteBusinessSource(db, home, storage, null))['tables'],
          isNot(first['tables']),
        );
        await putProxies([
          const CustomProxy(
            id: 10,
            definition: {'name': 'active', 'type': 'direct'},
          ).toCompanion(),
        ]);
        await script.writeAsString('changed script bytes');
        expect(
          (await nativeLiteBusinessSource(db, home, storage, null))['files'],
          isNot(first['files']),
        );
        await script.writeAsString('function main(config) { return config; }');
        final live = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          config.copyWith.patchClashConfig(mode: Mode.global),
        );
        expect(live['configSha256'], isNot(first['configSha256']));
        expect(live['liveSha256'], isNot(first['liveSha256']));
        expect(live['setupSha256'], first['setupSha256']);
        final stale = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          config.copyWith.patchClashConfig(
            hosts: {'fixture.test': '127.0.0.2'},
          ),
        );
        expect(stale['setupSha256'], isNot(first['setupSha256']));
        await db.profilesDao.putAll([
          profile.copyWith(selectedMap: {'group': 'node'}).toCompanion(),
        ]);
        expect(
          (await nativeLiteBusinessSource(db, home, storage, config))['tables'],
          isNot(first['tables']),
        );

        await db.profilesDao.putAll([
          profile
              .copyWith(
                extendType: ExtendType.standard,
                overrides: const ProfileOverrides(
                  scopedCustomProxyIds: [10],
                  ownedCustomProxyIds: {10},
                  inheritGlobalRules: true,
                ),
              )
              .toCompanion(),
        ]);
        await db
            .into(db.rules)
            .insertOnConflictUpdate(
              const Rule(
                id: 50,
                ruleAction: RuleAction.RULE_SET,
                ruleProvider: 'Library',
                ruleTarget: 'DIRECT',
              ).toCompanion(),
            );
        const provider = ClashProvider(id: 100, label: 'Library');
        await db
            .into(db.clashProviders)
            .insertOnConflictUpdate(provider.toCompanion());
        await Directory('${home.path}/providers/rules').create(recursive: true);
        final source = File(
          '${home.path}/providers/rules/${provider.fileName}',
        );
        await source.writeAsString('payload: [fixture.test]');
        final referenced = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          config,
        );
        await source.writeAsString('payload: [changed.test]');
        expect(
          (await nativeLiteBusinessSource(db, home, storage, null))['files'],
          isNot(referenced['files']),
        );
        await source.writeAsString('payload: [fixture.test]');
        await db
            .into(db.rules)
            .insertOnConflictUpdate(
              const Rule(
                id: 50,
                ruleAction: RuleAction.RULE_SET,
                ruleProvider: 'Library',
                ruleTarget: 'REJECT',
              ).toCompanion(),
            );
        expect(
          (await nativeLiteBusinessSource(db, home, storage, null))['tables'],
          isNot(referenced['tables']),
        );
        final raw =
            (await db
                        .customSelect(
                          'SELECT overrides FROM profiles WHERE id=1',
                        )
                        .getSingle())
                    .data['overrides']
                as String;
        final overrides = jsonDecode(raw) as Map<String, dynamic>;
        final resource = File(
          '${home.path}/profiles/providers/1/rules/${'a' * 32}',
        );
        await resource.parent.create(recursive: true);
        await resource.writeAsString('exact-resource-input');
        final receipt = File('${resource.path}.receipt');
        await receipt.writeAsString('exact-resource-receipt');
        overrides['nativeResourceInputs'] = {
          r'$.provider': {
            'file': 'profiles/providers/1/rules/${'a' * 32}',
            'receiptFile': 'profiles/providers/1/rules/${'a' * 32}.receipt',
            'sourceDefinition': {'path': 'archived-native-only-original'},
          },
        };
        await db.customStatement(
          'UPDATE profiles SET overrides = ? WHERE id = 1',
          [jsonEncode(overrides)],
        );
        await db.profilesDao.putAll([
          const Profile(
            id: 3,
            type: ProfileType.file,
            label: 'Feed',
            autoUpdateDuration: Duration.zero,
          ).toCompanion(),
        ]);
        await File(
          '${home.path}/profiles/3.yaml',
        ).writeAsString('proxies: [{name: feed, type: direct}]');
        await db
            .into(db.proxyGroups)
            .insertOnConflictUpdate(
              const ProxyGroup(
                id: 60,
                profileId: 1,
                name: 'group',
                type: GroupType.Selector,
                proxies: ['active'],
                use: ['Feed'],
              ).toCompanion(),
            );
        overrides['filterBindings'] = {
          '60': [901],
        };
        await db.customStatement(
          'UPDATE profiles SET overrides = ? WHERE id = 1',
          [jsonEncode(overrides)],
        );
        final boundConfig = config.copyWith.appSettingProps(
          filters: [
            const Filter(id: 901, label: 'Bound', regex: 'active'),
            const Filter(id: 902, label: 'Unrelated', regex: 'other'),
          ],
        );
        final withResources = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          boundConfig,
        );
        final unrelatedFilter = boundConfig.copyWith.appSettingProps(
          filters: [
            const Filter(id: 901, label: 'Bound', regex: 'active'),
            const Filter(id: 902, label: 'Unrelated edited', regex: 'changed'),
          ],
        );
        expect(
          await nativeLiteBusinessSource(db, home, storage, unrelatedFilter),
          withResources,
        );
        final changedFilter = boundConfig.copyWith.appSettingProps(
          filters: [const Filter(id: 901, label: 'Bound', regex: 'changed')],
        );
        expect(
          (await nativeLiteBusinessSource(
            db,
            home,
            storage,
            changedFilter,
          ))['configSha256'],
          isNot(withResources['configSha256']),
        );
        await nativeLiteBusinessSource(db, home, storage, boundConfig);
        await File(
          '${home.path}/profiles/3.yaml',
        ).writeAsString('proxies: [{name: edited-feed, type: direct}]');
        expect(
          (await nativeLiteBusinessSource(db, home, storage, null))['files'],
          isNot(withResources['files']),
        );
        await File(
          '${home.path}/profiles/3.yaml',
        ).writeAsString('proxies: [{name: feed, type: direct}]');

        await receipt.writeAsString('changed receipt');
        expect(
          (await nativeLiteBusinessSource(db, home, storage, null))['files'],
          isNot(withResources['files']),
        );
        await receipt.writeAsString('exact-resource-receipt');
        final resourceAll = <String, Object?>{};
        for (final name
            in (withResources['tables'] as Map).keys.cast<String>()) {
          resourceAll[name] =
              (await db
                      .customSelect('SELECT * FROM "$name" ORDER BY rowid')
                      .get())
                  .map((row) => row.data)
                  .toList();
        }
        if (output.isNotEmpty)
          await File('$output.resources').writeAsString(
            jsonEncode({
              'config': boundConfig,
              'all': resourceAll,
              'proof': withResources,
            }),
          );
      } finally {
        await db.close();
        await home.delete(recursive: true);
      }
    },
  );
  test(
    'null selection binds an empty source without selecting library rows',
    () async {
      SharedPreferences.setMockInitialValues({});
      final storage = await SharedPreferences.getInstance();
      final home = await Directory.systemTemp.createTemp(
        'source-proof89-empty-',
      );
      final db = Database(NativeDatabase(File('${home.path}/database.sqlite')));
      try {
        final config = Config(
          currentProfileId: null,
          themeProps: defaultThemeProps,
          patchClashConfig: newPatchClashConfig('meow-rs'),
        );
        final proof = await nativeLiteBusinessSource(db, home, storage, config);
        for (final table in (proof['tables'] as Map).values) {
          expect((table as Map)['rows'], 0);
        }
        await db.profilesDao.putAll([
          const Profile(
            id: 11,
            type: ProfileType.custom,
            label: 'Unselected',
            autoUpdateDuration: Duration.zero,
          ).toCompanion(),
        ]);
        expect(
          await nativeLiteBusinessSource(db, home, storage, config),
          proof,
        );
        await expectLater(
          nativeLiteBusinessSource(
            db,
            home,
            storage,
            config.copyWith(currentProfileId: 999),
          ),
          throwsA(isA<NativeLiteMigrationException>()),
        );
        final selected = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          config.copyWith(currentProfileId: 11),
        );
        expect(((selected['tables'] as Map)['profiles'] as Map)['rows'], 1);
        final changed = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          config.copyWith.patchClashConfig(hosts: {'empty.test': '127.0.0.2'}),
        );
        expect(changed['configSha256'], isNot(proof['configSha256']));
        await nativeLiteBusinessSource(db, home, storage, config);
        final wire = config.toJson()..remove('currentProfileId');
        await storage.setString('config', jsonEncode(wire));
        await expectLater(
          nativeLiteBusinessSource(db, home, storage, null),
          throwsA(isA<NativeLiteMigrationException>()),
        );
        wire['currentProfileId'] = '11';
        await storage.setString('config', jsonEncode(wire));
        await expectLater(
          nativeLiteBusinessSource(db, home, storage, null),
          throwsA(isA<NativeLiteMigrationException>()),
        );
        await storage.setString('config', jsonEncode(config));
        await nativeLiteBusinessSource(db, home, storage, config);
        const output = String.fromEnvironment('SOURCE_PROOF89_OUTPUT');
        if (output.isNotEmpty) {
          final all = <String, Object?>{};
          for (final name in (proof['tables'] as Map).keys.cast<String>()) {
            all[name] =
                (await db
                        .customSelect('SELECT * FROM "$name" ORDER BY rowid')
                        .get())
                    .map((row) => row.data)
                    .toList();
          }
          await File('$output.empty').writeAsString(
            jsonEncode({'config': config, 'all': all, 'proof': proof}),
          );
        }
      } finally {
        await db.close();
        await home.delete(recursive: true);
      }
    },
  );
}
