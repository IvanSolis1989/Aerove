import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/foundation.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../helpers/app_paths.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  ClientProduct.configure(
    product: ProductSpec(
      id: 'geo-transaction-fixture',
      displayName: 'Geo transaction fixture',
      expectedCoreId: 'meow-rs',
      minimumAndroidApi: 33,
      resources: ProductResources(
        defaultScriptAsset: 'fixture',
        defaultScriptSha256: sha256
            .convert(utf8.encode('(config)=>config'))
            .toString(),
        defaultScriptUrl: 'https://example.invalid/fixture',
        legacyScriptUrls: {},
        iconAsset: 'fixture',
      ),
      releaseFeed: 'https://example.invalid/fixture',
      releaseTagPrefix: 'fixture',
      releaseAssetPattern: 'fixture',
      changelogPath: 'fixture',
      coreSourceUrl: 'https://example.invalid/fixture',
    ),
    backendCoreId: 'meow-rs',
    backendFactory: () => throw StateError('No native Core in producer test'),
  );

  test(
    'fresh Meow Android preset keeps the supported neutral keepalive values',
    () async {
      await _withDefaults((defaults, journal, storage, saved, database) async {
        final original = Config.realFromJson(null, coreId: 'meow-rs');
        final next = await defaults.initialize(original);
        expect(next.patchClashConfig.keepAliveInterval, 30);
        expect(next.patchClashConfig.keepAliveIdle, 15);
        expect(next.patchClashConfig.disableKeepAlive, false);
        expect(next.themeProps, original.themeProps);
        expect(saved, [next]);
        expect(jsonDecode(await journal.readAsString())['complete'], true);
        expect(
          jsonDecode(
            await journal.readAsString(),
          )['meowKeepAlivePresetVersion'],
          1,
        );
      });
    },
  );

  test(
    'historical Meow preset tuple compatibility changes only its three fields once',
    () async {
      final original = Config.realFromJson(null, coreId: 'meow-rs').copyWith
          .themeProps(themeMode: ThemeMode.dark, auroraEnabled: true)
          .copyWith
          .patchClashConfig(keepAliveInterval: 15, keepAliveIdle: 30);
      await _withDefaults(
        (defaults, journal, storage, saved, database) async {
          final next = await defaults.initialize(original);
          expect(
            next,
            original.copyWith.patchClashConfig(
              keepAliveInterval: 30,
              keepAliveIdle: 15,
              disableKeepAlive: false,
            ),
          );
          expect(next.themeProps, original.themeProps);
          expect(saved, [next]);
          expect(
            Config.strictFromJson(jsonDecode(storage.getString(configKey)!)),
            next,
          );
          expect(
            jsonDecode(
              await journal.readAsString(),
            )['meowKeepAlivePresetVersion'],
            1,
          );
          final later = next.copyWith.patchClashConfig(
            keepAliveInterval: 15,
            keepAliveIdle: 30,
          );
          expect(await defaults.initialize(later), same(later));
          expect(saved, hasLength(1));
        },
        stored: original,
        journal: const {
          'scriptId': 123,
          'freshAndroid': true,
          'complete': true,
        },
      );
    },
  );

  test(
    'historical Meow preset save failure keeps config and journal retryable',
    () async {
      final original = Config.realFromJson(
        null,
        coreId: 'meow-rs',
      ).copyWith.patchClashConfig(keepAliveInterval: 15, keepAliveIdle: 30);
      await _withDefaults(
        (defaults, journal, storage, saved, database) async {
          final bytes = await journal.readAsBytes();
          final config = storage.getString(configKey);
          await expectLater(defaults.initialize(original), throwsStateError);
          expect(storage.getString(configKey), config);
          expect(await journal.readAsBytes(), bytes);
          expect(saved, isEmpty);
        },
        stored: original,
        failSave: true,
        journal: const {
          'scriptId': 123,
          'freshAndroid': true,
          'complete': true,
        },
      );
    },
  );

  test(
    'migration journal failure does not mark the historical tuple policy complete',
    () async {
      final original = Config.realFromJson(
        null,
        coreId: 'meow-rs',
      ).copyWith.patchClashConfig(keepAliveInterval: 15, keepAliveIdle: 30);
      await _withDefaults(
        (defaults, journal, storage, saved, database) async {
          final before = await journal.readAsBytes();
          await Directory('${journal.path}.pending').create();
          await expectLater(
            defaults.initialize(original),
            throwsA(isA<FileSystemException>()),
          );
          expect(await journal.readAsBytes(), before);
          expect(saved, hasLength(1));
          expect(
            Config.strictFromJson(jsonDecode(storage.getString(configKey)!)),
            original.copyWith.patchClashConfig(
              keepAliveInterval: 30,
              keepAliveIdle: 15,
              disableKeepAlive: false,
            ),
          );
          final neutral = Config.strictFromJson(
            jsonDecode(storage.getString(configKey)!),
          );
          await Directory('${journal.path}.pending').delete();
          XClashDefaults reopen() => XClashDefaults(
            storage: storage,
            database: database,
            directory: journal.parent,
            loadScript: () async => throw StateError('Must not reseed'),
            createId: () => 999,
            saveConfig: (_) async =>
                throw StateError('Must not save Config again'),
          );
          final recovered = reopen();
          await recovered.prepare(isAndroid: true);
          expect(await recovered.initialize(neutral), same(neutral));
          expect(
            jsonDecode(
              await journal.readAsString(),
            )['meowKeepAlivePresetVersion'],
            1,
          );
          expect(
            Config.strictFromJson(jsonDecode(storage.getString(configKey)!)),
            neutral,
          );
          expect(saved, hasLength(1));
          await storage.setString(configKey, jsonEncode(original));
          final restarted = reopen();
          await restarted.prepare(isAndroid: true);
          expect(await restarted.initialize(original), same(original));
          expect(
            Config.strictFromJson(jsonDecode(storage.getString(configKey)!)),
            original,
          );
          expect(saved, hasLength(1));
        },
        stored: original,
        journal: const {
          'scriptId': 123,
          'freshAndroid': true,
          'complete': true,
        },
      );
    },
  );

  for (final patch in [
    const PatchClashConfig(keepAliveInterval: 16, keepAliveIdle: 30),
    const PatchClashConfig(keepAliveInterval: 15, keepAliveIdle: 31),
    const PatchClashConfig(
      keepAliveInterval: 15,
      keepAliveIdle: 30,
      disableKeepAlive: true,
    ),
  ]) {
    test(
      'non-default unsupported keepalive tuple stays explicit: $patch',
      () async {
        final original = Config.realFromJson(
          null,
          coreId: 'meow-rs',
        ).copyWith(patchClashConfig: patch);
        await _withDefaults(
          (defaults, journal, storage, saved, database) async {
            expect(await defaults.initialize(original), same(original));
            expect(saved, isEmpty);
            expect(
              jsonDecode(
                await journal.readAsString(),
              )['meowKeepAlivePresetVersion'],
              1,
            );
            expect(
              Config.strictFromJson(jsonDecode(storage.getString(configKey)!)),
              original,
            );
          },
          stored: original,
          journal: const {
            'scriptId': 123,
            'freshAndroid': true,
            'complete': true,
          },
        );
      },
    );
  }

  test(
    'an unfinished Meow journal with saved config preserves user data on this and later starts',
    () async {
      final original = Config.realFromJson(null, coreId: 'meow-rs').copyWith
          .themeProps(themeMode: ThemeMode.dark)
          .copyWith
          .patchClashConfig(keepAliveInterval: 15, keepAliveIdle: 30);
      await _withDefaults(
        (defaults, journal, storage, saved, database) async {
          expect(await defaults.initialize(original), same(original));
          expect(saved, isEmpty);
          expect(jsonDecode(await journal.readAsString())['complete'], true);
          expect(
            jsonDecode(
              await journal.readAsString(),
            )['meowKeepAlivePresetVersion'],
            1,
          );
          final restarted = XClashDefaults(
            storage: storage,
            database: database,
            directory: journal.parent,
            loadScript: () async => throw StateError('Must not reseed'),
            createId: () => 999,
            saveConfig: (_) async =>
                throw StateError('Must not rewrite saved user config'),
          );
          await restarted.prepare(isAndroid: true);
          expect(await restarted.initialize(original), same(original));
          expect(
            Config.strictFromJson(jsonDecode(storage.getString(configKey)!)),
            original,
          );
        },
        stored: original,
        journal: const {
          'scriptId': 123,
          'freshAndroid': true,
          'complete': false,
        },
      );
    },
  );

  test(
    'Native or nonfresh journal does not authorize the tuple migration',
    () async {
      final original = Config.realFromJson(
        null,
        coreId: 'meow-rs',
      ).copyWith.patchClashConfig(keepAliveInterval: 15, keepAliveIdle: 30);
      await _withDefaults(
        (defaults, journal, storage, saved, database) async {
          final bytes = await journal.readAsBytes();
          expect(await defaults.initialize(original), same(original));
          expect(saved, isEmpty);
          expect(await journal.readAsBytes(), bytes);
        },
        stored: original,
        journal: const {
          'scriptId': 123,
          'freshAndroid': false,
          'complete': true,
          'nativeSourceDigest':
              'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        },
      );
    },
  );

  test(
    'Meow profile build failure preserves active YAML, receipt state and the original error',
    () async {
      await _withFixture((fixture) async {
        final file = File(await appPath.configFilePath);
        await file.parent.create(recursive: true);
        await file.writeAsString('valid-active-yaml');
        globalState.lastConfigMd5 = 'previous-applied';
        const counts = (groups: 1, proxies: 2, rules: 3);
        globalState.lastConfigCounts = counts;
        fixture.container
            .read(patchClashConfigProvider.notifier)
            .update(
              (value) =>
                  value.copyWith(keepAliveInterval: 15, keepAliveIdle: 30),
            );
        final printed = <String?>[];
        final previousPrint = debugPrint;
        debugPrint = (message, {wrapWidth}) => printed.add(message);
        try {
          expect(await fixture.setup.applyProfile(force: true), false);
        } finally {
          debugPrint = previousPrint;
        }
        final failures = printed
            .where(
              (message) => message?.contains('build profile ===>') ?? false,
            )
            .toList();
        expect(failures, hasLength(1));
        expect(failures.single, contains('FormatException:'));
        expect(
          failures.single,
          contains(
            r'$.keep-alive-interval: fixed Meow does not support global TCP keepalive tuning',
          ),
        );
        expect(await file.readAsString(), 'valid-active-yaml');
        expect(globalState.lastConfigMd5, 'previous-applied');
        expect(globalState.lastConfigCounts, counts);
        expect(fixture.protocol.setupCalls, isEmpty);
      });
    },
  );

  test('Meow missing digest record cannot write YAML or call setup', () async {
    final invalidDigest = <double>[];
    final action = _DigestFailureSetupAction(invalidDigest);
    final state = SetupState(
      profileId: 1,
      profileLastUpdateDate: null,
      profileType: ProfileType.file,
      extendType: ExtendType.script,
      rules: const [],
      proxyGroups: const [],
      addedRules: const [],
      script: Script(
        id: 2,
        label: 'actual Node',
        lastUpdateTime: DateTime.fromMillisecondsSinceEpoch(1),
      ),
      overrides: ProfileOverrides(
        baseConfig: {'digest-fixture': invalidDigest},
      ),
    );
    await _withFixture(
      (fixture) async {
        final file = File(await appPath.configFilePath);
        await file.parent.create(recursive: true);
        await file.writeAsString('valid-active-yaml');
        globalState.lastConfigMd5 = 'previous-applied';
        const counts = (groups: 4, proxies: 5, rules: 6);
        globalState.lastConfigCounts = counts;
        expect(await fixture.setup.applyProfile(force: true), false);
        expect(action.builtProfile, true);
        expect(invalidDigest.single.isNaN, true);
        expect(await file.readAsString(), 'valid-active-yaml');
        expect(globalState.lastConfigMd5, 'previous-applied');
        expect(globalState.lastConfigCounts, counts);
        expect(fixture.protocol.setupCalls, isEmpty);
      },
      setupActionFactory: () => action,
      setupState: state,
    );
  });

  test(
    'Geo commit uses one actual Node producer and the full YAML MD5',
    () async {
      await _withFixture((fixture) async {
        await fixture.setup.updateMeowGeo('automatic');
        expect(fixture.protocol.operations, ['admit', 'update']);
        expect(fixture.evaluations, 1);
        expect(
          fixture.protocol.request!.setupParams.dataSourceProof?['tables'],
          isNotNull,
        );
        expect(fixture.protocol.body, contains('aerove-geo-origin'));
        expect(globalState.lastConfigMd5, fixture.protocol.body.toMd5());
        expect(
          fixture.protocol.request!.setupParams.applied!.yamlMd5,
          globalState.lastConfigMd5,
        );
        expect(
          await Directory('${fixture.home.path}/geo-requests').list().toList(),
          isEmpty,
        );
      });
    },
  );

  test(
    'lost update observer reads the same transaction status without rerunning JS',
    () async {
      await _withFixture((fixture) async {
        fixture.protocol.loseUpdateObserver = true;
        await fixture.container
            .read(geoResourceActionProvider.notifier)
            .updateGeoResource(GeoResource.MMDB, announceSuccess: false);
        expect(fixture.protocol.operations, ['update', 'status']);
        expect(fixture.protocol.request!.type, 'mmdb');
        expect(fixture.evaluations, 1);
        expect(globalState.lastConfigMd5, fixture.protocol.body.toMd5());
      });
    },
  );

  test('failed Geo recovery preserves the original preparation error', () async {
    await _withFixture((fixture) async {
      globalState.lastConfigMd5 = 'previous-applied-body';
      fixture.protocol.loseUpdateObserver = true;
      fixture.protocol.unknownStatus = true;
      await expectLater(
        fixture.setup.updateMeowGeo('mmdb'),
        throwsA(same(fixture.protocol.updateFailure)),
      );
      expect(fixture.protocol.operations, ['update', 'status']);
      expect(globalState.lastConfigMd5, 'previous-applied-body');
    });
  });

  test(
    'profile switch aborts the owned transaction and keeps the previous applied MD5',
    () async {
      await _withFixture((fixture) async {
        globalState.lastConfigMd5 = 'previous-applied-body';
        fixture.protocol.holdUntilAbort = true;
        final pending = fixture.setup.updateMeowGeo('geosite');
        final rejected = expectLater(pending, throwsA(isA<StateError>()));
        await fixture.protocol.entered.future;
        fixture.container.read(currentProfileIdProvider.notifier).value = 99;
        await rejected;
        expect(fixture.protocol.operations, ['update', 'abort']);
        expect(globalState.lastConfigMd5, 'previous-applied-body');
        expect(
          await Directory('${fixture.home.path}/geo-requests').list().toList(),
          isEmpty,
        );
      });
    },
  );

  test('mismatched producer receipt cannot advance applied state', () async {
    await _withFixture((fixture) async {
      globalState.lastConfigMd5 = 'previous-applied-body';
      fixture.protocol.mismatchProducer = true;
      await expectLater(
        fixture.setup.updateMeowGeo('asn'),
        throwsA(isA<StateError>()),
      );
      expect(globalState.lastConfigMd5, 'previous-applied-body');
      expect(fixture.evaluations, 1);
    });
  });
}

Future<void> _withDefaults(
  Future<void> Function(
    XClashDefaults,
    File,
    SharedPreferences,
    List<Config>,
    db.Database,
  )
  body, {
  Config? stored,
  Map<String, Object?>? journal,
  bool failSave = false,
}) async {
  final home = await Directory.systemTemp.createTemp('meow-defaults-');
  final database = db.Database(NativeDatabase.memory());
  SharedPreferences.setMockInitialValues({});
  final storage = await SharedPreferences.getInstance();
  final saved = <Config>[];
  final journalFile = File('${home.path}/xclash-defaults.json');
  if (stored != null) await storage.setString(configKey, jsonEncode(stored));
  if (journal != null) await journalFile.writeAsString(jsonEncode(journal));
  final defaults = XClashDefaults(
    storage: storage,
    database: database,
    directory: home,
    loadScript: () async => Uint8List.fromList(utf8.encode('(config)=>config')),
    createId: () => 123,
    saveConfig: (config) async {
      if (failSave) return false;
      saved.add(config);
      return storage.setString(configKey, jsonEncode(config));
    },
  );
  try {
    await defaults.prepare(isAndroid: true);
    await body(defaults, journalFile, storage, saved, database);
  } finally {
    await database.close();
    await home.delete(recursive: true);
  }
}

class _DigestFailureSetupAction extends SetupAction {
  _DigestFailureSetupAction(this.digestValues);
  final List<double> digestValues;
  bool builtProfile = false;
  @override
  Future<BuiltProfile> getProfile({
    required SetupState setupState,
    required PatchClashConfig patchConfig,
  }) async {
    final result = await super.getProfile(
      setupState: setupState,
      patchConfig: patchConfig,
    );
    builtProfile = true;
    digestValues.add(double.nan);
    return result;
  }
}

class _Fixture {
  _Fixture(this.home, this.container, this.protocol);
  final Directory home;
  final ProviderContainer container;
  final _GeoProtocol protocol;
  int evaluations = 0;
  SetupAction get setup => container.read(setupActionProvider.notifier);
}

Future<void> _withFixture(
  Future<void> Function(_Fixture) body, {
  SetupAction Function()? setupActionFactory,
  SetupState? setupState,
}) async {
  await AppLocalizations.load(const Locale('en'));
  final home = await Directory.systemTemp.createTemp('geo-transaction114-');
  await useAppPaths(home);
  SharedPreferences.setMockInitialValues({});
  await preferences.rebind();
  final priorDatabase = db.database;
  final database = db.Database(
    NativeDatabase(File('${home.path}/database.sqlite')),
  );
  db.database = database;
  final script = Script(
    id: 2,
    label: 'actual Node',
    lastUpdateTime: DateTime.fromMillisecondsSinceEpoch(1),
  );
  await database.scripts.put(script.toCompanion());
  await Directory('${home.path}/scripts').create();
  await File('${home.path}/scripts/2.js').writeAsString('(config)=>config');
  await Directory('${home.path}/profiles').create();
  await File('${home.path}/profiles/1.yaml').writeAsString('proxies: []');
  final profile = Profile(
    id: 1,
    label: 'actual',
    autoUpdateDuration: Duration.zero,
    autoUpdate: false,
    extendType: ExtendType.script,
    scriptId: 2,
    selectedMap: {'g': 'DIRECT'},
  );
  await database.profiles.put(profile.toCompanion());
  final protocol = _GeoProtocol();
  final container = ProviderContainer(
    overrides: [
      if (setupActionFactory case final factory?)
        setupActionProvider.overrideWith(factory),
      if (setupState case final state?)
        setupStateProvider.overrideWith((_, _) => state),
      coreHandlerProvider.overrideWithValue(CoreController.scoped(protocol)),
      coreStatusProvider.overrideWithBuild(
        (ref, notifier) => CoreStatus.connected,
      ),
    ],
  );
  globalState.container = container;
  globalState.packageInfo = PackageInfo(
    appName: 'fixture',
    packageName: 'fixture',
    version: '1',
    buildNumber: '1',
  );
  globalState.appEnv = 'dev';
  final keep = container.listen(configProvider, (_, next) {});
  container.read(currentProfileIdProvider.notifier).value = 1;
  container.read(profilesProvider.notifier).put(profile);
  final fixture = _Fixture(home, container, protocol);
  final previousEvaluator = scriptEvaluator;
  final previousMd5 = globalState.lastConfigMd5;
  final previousCounts = globalState.lastConfigCounts;
  scriptEvaluator = ({required String script, required String config}) async {
    fixture.evaluations++;
    final result = await Process.run(
      Platform.environment['AEROVE_TEST_NODE'] ?? 'node',
      [
        '-e',
        'const config=JSON.parse(process.argv[1]);const main=eval(process.argv[2]);process.stdout.write(JSON.stringify(main(config)));',
        config,
        script,
      ],
    );
    if (result.exitCode != 0) throw StateError(result.stderr.toString());
    return result.stdout.toString();
  };
  try {
    await waitForDatabaseWrites();
    await body(fixture);
  } finally {
    scriptEvaluator = previousEvaluator;
    globalState.lastConfigMd5 = previousMd5;
    globalState.lastConfigCounts = previousCounts;
    await waitForDatabaseWrites();
    keep.close();
    container.dispose();
    await database.close();
    db.database = priorDatabase;
    await home.delete(recursive: true);
  }
}

// Only platform RPC outcomes are controlled; Drift, source proof, SetupAction, Task and Node are real.
class _GeoProtocol implements CoreHandlerInterface {
  final operations = <String>[];
  final entered = Completer<void>();
  final released = Completer<void>();
  bool loseUpdateObserver = false;
  bool unknownStatus = false;
  final updateFailure = StateError('Original resource preparation failed');
  bool holdUntilAbort = false;
  bool mismatchProducer = false;
  MeowGeoUpdateRequest? request;
  String body = '';
  int spills = 0;
  final setupCalls = <SetupParams>[];

  @override
  Future<String> setupConfig(SetupParams params) async {
    setupCalls.add(params);
    throw StateError('Failed preparation must never submit setup');
  }

  @override
  Future<String> getConfig(String path) async {
    final directory = Directory('${await appPath.homeDirPath}/responses');
    await directory.create(recursive: true);
    final file = File('${directory.path}/${spills++}.json');
    await file.writeAsString(
      jsonEncode({
        'proxy-groups': [
          {
            'name': 'g',
            'type': 'select',
            'proxies': ['DIRECT', 'REJECT'],
          },
        ],
        'rules': ['MATCH,DIRECT'],
      }),
    );
    return file.path;
  }

  @override
  Future<MeowGeoReply> updateGeoPrepared(MeowGeoRequest value) async {
    final operation = value.toJson()['operation'] as String;
    operations.add(operation);
    if (operation == 'admit') {
      return MeowGeoReply(
        schemaVersion: 1,
        transactionId: value.transactionId,
        state: 'needs-source',
        active: false,
      );
    }
    if (operation == 'status' && unknownStatus) {
      throw StateError('Unknown or superseded Geo request');
    }
    if (operation == 'abort') {
      if (!released.isCompleted) released.complete();
      return MeowGeoReply(
        schemaVersion: 1,
        transactionId: value.transactionId,
        state: 'aborted',
        active: false,
      );
    }
    if (value is MeowGeoUpdateRequest) {
      request = value;
      body = await File(value.source.path).readAsString();
      expect(sha256.convert(utf8.encode(body)).toString(), value.source.sha256);
      expect(utf8.encode(body).length, value.source.bytes);
      if (!entered.isCompleted) entered.complete();
      if (holdUntilAbort) {
        await released.future;
        return MeowGeoReply(
          schemaVersion: 1,
          transactionId: value.transactionId,
          state: 'aborted',
          active: false,
        );
      }
      if (loseUpdateObserver)
        throw updateFailure;
    }
    final submitted = request!;
    expect(value.transactionId, submitted.transactionId);
    return MeowGeoReply(
      schemaVersion: 1,
      transactionId: value.transactionId,
      state: 'committed',
      active: true,
      applied: submitted.setupParams.applied,
      producerSha256: mismatchProducer ? '0' * 64 : submitted.source.sha256,
      compiledSha256: 'a' * 64,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('Unexpected RPC ${invocation.memberName}');
}
