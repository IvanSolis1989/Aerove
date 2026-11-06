import 'package:fl_clash/product.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/javascript.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:drift/native.dart';
import 'package:fl_clash/state.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/widgets.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

String get _nodeExecutable =>
    Platform.environment['AEROVE_TEST_NODE'] ??
    (Platform.isWindows ? 'C:/Program Files/nodejs/node.exe' : 'node');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const core = String.fromEnvironment('DTO_CORE', defaultValue: 'meow-rs');
  ClientProduct.configure(
    product: ProductSpec(
      id: 'dto-fixture',
      displayName: 'DTO fixture',
      expectedCoreId: core,
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
    backendCoreId: core,
    backendFactory: () =>
        throw StateError('DTO test must not create a Core backend'),
  );

  test(
    'actual ack triggers the unique real setup/Node producer and rejects stale or changed output',
    () async {
      await AppLocalizations.load(const Locale('en'));
      final home = await Directory.systemTemp.createTemp('geo91-ack-producer-');
      AppPath.supportDirectory = () async => home;
      AppPath.temporaryDirectory = () async => home;
      AppPath.cacheDirectory = () async => home;
      AppPath.downloadDirectory = () async => home;
      await appPath.rebind();
      SharedPreferences.setMockInitialValues({});
      await preferences.rebind();
      db.database = db.Database(
        NativeDatabase(File('${home.path}/database.sqlite')),
      );
      final script = Script(
        id: 2,
        label: 'actual Node',
        lastUpdateTime: DateTime.fromMillisecondsSinceEpoch(1),
      );
      await db.database.scripts.put(script.toCompanion());
      await Directory('${home.path}/scripts').create();
      final scriptFile = File('${home.path}/scripts/2.js');
      await scriptFile.writeAsString('(config)=>config');
      await Directory('${home.path}/profiles').create();
      await File('${home.path}/profiles/1.yaml').writeAsString('proxies: []');
      final profile = Profile(
        id: 1,
        label: 'actual',
        autoUpdateDuration: Duration.zero,
        autoUpdate: false,
        extendType: ExtendType.script,
        scriptId: 2,
        selectedMap: {'g': 'A'},
      );
      await db.database.profiles.put(profile.toCompanion());
      final protocol = _AckProtocol();
      final container = ProviderContainer(
        overrides: [
          coreHandlerProvider.overrideWithValue(
            CoreController.scoped(protocol),
          ),
          coreStatusProvider.overrideWithBuild(
            (ref, n) => CoreStatus.connected,
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
      final keep = container.listen(configProvider, (_, __) {});
      container.read(currentProfileIdProvider.notifier).value = 1;
      container.read(profilesProvider.notifier).put(profile);
      final previous = scriptEvaluator;
      var evaluations = 0;
      scriptEvaluator = ({required String script, required String config}) async {
        evaluations++;
        final result = await Process.run(_nodeExecutable, [
          '-e',
          'const config=JSON.parse(process.argv[1]);const main=eval(process.argv[2]);process.stdout.write(JSON.stringify(main(config)));',
          config,
          script,
        ]);
        if (result.exitCode != 0) throw StateError(result.stderr.toString());
        return result.stdout.toString();
      };
      try {
        await waitForDatabaseWrites();
        final setup = container.read(setupActionProvider.notifier);
        final built = await setup.getProfile(
          setupState: await container.read(setupStateProvider(1).future),
          patchConfig: container.read(patchClashConfigProvider),
        );
        globalState.lastConfigMd5 = built.md5;
        final proxies = container.read(proxiesActionProvider.notifier);
        final first = proxies.changeProxy(groupName: 'g', proxyName: 'B');
        await protocol.entered(0);
        await waitForDatabaseWrites();
        expect(
          (await db.database.profilesDao.query().get()).single.selectedMap['g'],
          'B',
        );
        expect(protocol.refreshes, isEmpty);
        protocol.acks
            .removeAt(0)
            .complete(const ChangeProxyResult(changed: false));
        await first;
        expect(protocol.refreshes.length, 1);
        expect(protocol.refreshes.single.selectedMap['g'], 'B');
        expect(evaluations, 2);
        expect(protocol.refreshes.single.dataSourceProof?['tables'], isNotNull);
        final a = proxies.changeProxy(groupName: 'g', proxyName: 'A');
        await protocol.entered(1);
        final b = proxies.changeProxy(groupName: 'g', proxyName: 'B');
        await protocol.entered(2);
        protocol.acks
            .removeAt(0)
            .complete(const ChangeProxyResult(changed: true));
        await a;
        expect(protocol.refreshes.length, 1);
        protocol.acks
            .removeAt(0)
            .complete(const ChangeProxyResult(changed: false));
        await b;
        expect(protocol.refreshes.length, 2);
        final switched = proxies.changeProxy(groupName: 'g', proxyName: 'A');
        await protocol.entered(3);
        container.read(currentProfileIdProvider.notifier).value = 99;
        protocol.acks
            .removeAt(0)
            .complete(const ChangeProxyResult(changed: false));
        await switched;
        expect(protocol.refreshes.length, 2);
        container.read(currentProfileIdProvider.notifier).value = 1;
        final failed = proxies.changeProxy(groupName: 'g', proxyName: 'B');
        await protocol.entered(4);
        protocol.acks
            .removeAt(0)
            .complete(
              const ChangeProxyResult(
                message: 'actual RPC failed',
                changed: false,
              ),
            );
        await failed;
        expect(protocol.refreshes.length, 2);
        expect(container.read(currentProfileProvider)?.selectedMap['g'], 'A');
        await scriptFile.writeAsString(
          '(config)=>{config.rules=["MATCH,REJECT"];return config;}',
        );
        final changed = proxies.changeProxy(groupName: 'g', proxyName: 'B');
        await protocol.entered(5);
        protocol.acks
            .removeAt(0)
            .complete(const ChangeProxyResult(changed: false));
        await changed;
        expect(protocol.refreshes.length, 2);
        expect(evaluations, 4);
        expect(globalState.lastConfigMd5, built.md5);
        await scriptFile.writeAsString('(config)=>config');
        for (final secondFails in [false, true]) {
          final seeded = container
              .read(currentProfileProvider)!
              .copyWith(selectedMap: {'g': 'A', 'h': 'X'});
          await container.read(profilesProvider.notifier).putCommitted(seeded);
          final sourceEntered = Completer<void>();
          final sourceRelease = Completer<void>();
          final overrides = seeded.overrides.copyWith(
            hosts: {
              'fixture.invalid': [secondFails ? '127.0.0.2' : '127.0.0.1'],
            },
          );
          final publishing = setup.withProviderSourceWrite(() async {
            sourceEntered.complete();
            await sourceRelease.future;
            await container
                .read(profilesProvider.notifier)
                .putCommitted(seeded.copyWith(overrides: overrides));
          });
          await sourceEntered.future;
          final callOffset = protocol.calls.length;
          final refreshOffset = protocol.refreshes.length;
          final firstGroup = proxies.changeProxy(
            groupName: 'g',
            proxyName: 'B',
          );
          final secondGroup = proxies.changeProxy(
            groupName: 'h',
            proxyName: 'Y',
          );
          await pumpEventQueue();
          expect(protocol.calls.length, callOffset);
          expect((await db.database.profilesDao.get(1))!.selectedMap, {
            'g': 'A',
            'h': 'X',
          });
          sourceRelease.complete();
          await publishing;
          await protocol.entered(callOffset);
          await protocol.entered(callOffset + 1);
          await waitForDatabaseWrites();
          final persisted = (await db.database.profilesDao.get(1))!;
          expect(persisted.selectedMap, {'g': 'B', 'h': 'Y'});
          expect(persisted.overrides, overrides);
          expect(protocol.calls.skip(callOffset), [
            const ChangeProxyParams(groupName: 'g', proxyName: 'B'),
            const ChangeProxyParams(groupName: 'h', proxyName: 'Y'),
          ]);
          final published = await setup.getProfile(
            setupState: await container.read(setupStateProvider(1).future),
            patchConfig: container.read(patchClashConfigProvider),
          );
          globalState.lastConfigMd5 = published.md5;
          protocol.acks
              .removeAt(0)
              .complete(const ChangeProxyResult(changed: false));
          await firstGroup;
          expect(protocol.refreshes.length, refreshOffset);
          protocol.acks
              .removeAt(0)
              .complete(
                ChangeProxyResult(
                  changed: false,
                  message: secondFails ? 'second group rejected' : '',
                ),
              );
          await secondGroup;
          final expected = {'g': 'B', 'h': secondFails ? 'X' : 'Y'};
          expect(protocol.refreshes.length, refreshOffset + 1);
          expect(protocol.refreshes.last.selectedMap, expected);
          expect(protocol.refreshes.last.dataSourceProof?['tables'], isNotNull);
          final finalRow = (await db.database.profilesDao.get(1))!;
          expect(finalRow.selectedMap, expected);
          expect(finalRow.overrides, overrides);
          expect(container.read(currentProfileProvider)!.selectedMap, expected);
        }
        final callOffset = protocol.calls.length;
        final refreshOffset = protocol.refreshes.length;
        final older = proxies.changeProxy(groupName: 'g', proxyName: 'A');
        await protocol.entered(callOffset);
        final newer = proxies.changeProxy(groupName: 'g', proxyName: 'B');
        await protocol.entered(callOffset + 1);
        protocol.acks
            .removeAt(1)
            .complete(const ChangeProxyResult(changed: false));
        await newer;
        expect(protocol.refreshes.length, refreshOffset + 1);
        expect(protocol.refreshes.last.selectedMap, {'g': 'B', 'h': 'X'});
        protocol.acks
            .removeAt(0)
            .complete(
              const ChangeProxyResult(message: 'older failure', changed: false),
            );
        await older;
        expect(protocol.refreshes.length, refreshOffset + 1);
        expect((await db.database.profilesDao.get(1))!.selectedMap, {
          'g': 'B',
          'h': 'X',
        });
      } finally {
        scriptEvaluator = previous;
        await waitForDatabaseWrites();
        keep.close();
        container.dispose();
        await db.database.close();
        await home.delete(recursive: true);
      }
    },
  );
}

// Only the platform RPC boundary is controlled; the real SetupAction, Drift proof,
// Task builder and one real Node script evaluation are executed above.
class _AckProtocol implements CoreHandlerInterface {
  final acks = <Completer<ChangeProxyResult>>[];
  final calls = <ChangeProxyParams>[];
  final arrivals = <int, Completer<void>>{};
  final refreshes = <SetupParams>[];
  Future<void> entered(int index) =>
      arrivals.putIfAbsent(index, Completer<void>.new).future;
  @override
  Future<ChangeProxyResult> changeProxy(ChangeProxyParams params) {
    final ack = Completer<ChangeProxyResult>();
    acks.add(ack);
    final index = calls.length;
    calls.add(params);
    arrivals.putIfAbsent(index, Completer<void>.new).complete();
    return ack.future;
  }

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
          {
            'name': 'h',
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
  Future<bool> refreshAppliedSetup(SetupParams params) async {
    refreshes.add(params);
    return true;
  }

  @override
  Future<bool> resetConnections() async => true;
  @override
  Future<bool> closeConnections() async => true;
  @override
  dynamic noSuchMethod(Invocation i) =>
      throw StateError('Unexpected RPC ${i.memberName}');
}
