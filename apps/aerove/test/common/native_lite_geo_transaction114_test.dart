import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/widgets.dart';
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
        defaultScriptSha256: 'fixture',
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

class _Fixture {
  _Fixture(this.home, this.container, this.protocol);
  final Directory home;
  final ProviderContainer container;
  final _GeoProtocol protocol;
  int evaluations = 0;
  SetupAction get setup => container.read(setupActionProvider.notifier);
}

Future<void> _withFixture(Future<void> Function(_Fixture) body) async {
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
  bool holdUntilAbort = false;
  bool mismatchProducer = false;
  MeowGeoUpdateRequest? request;
  String body = '';
  int spills = 0;

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
        throw StateError('Observer lost after Native commit');
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
