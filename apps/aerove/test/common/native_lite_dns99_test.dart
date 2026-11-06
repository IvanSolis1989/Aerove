import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/core/meow.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart' show coreStatusProvider;
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/config/dns.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/providers/database.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:yaml/yaml.dart';
import '../helpers/app_paths.dart';

String get _nodeExecutable =>
    Platform.environment['AEROVE_TEST_NODE'] ??
    (Platform.isWindows ? 'C:/Program Files/nodejs/node.exe' : 'node');

class _UnusedCore extends CoreHandlerInterface {
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('No core lifecycle or fake result is allowed');
  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) => throw StateError(
    'Custom DNS fixture must not fabricate a core RPC result',
  );
}

class _ProfileFileBoundary extends _UnusedCore {
  int validations = 0;
  int sourceReads = 0;
  @override
  Future<String> validateConfig(String path) async {
    validations++;
    final candidate = loadYaml(await File(path).readAsString()) as Map;
    expect((candidate['proxies'] as List).map((node) => node['name']), [
      'supported',
    ]);
    return '';
  }

  @override
  Future<String> getConfig(String path) async {
    sourceReads++;
    final response = File(
      '${await appPath.homeDirPath}/responses/profile-fixture.json',
    );
    await response.parent.create(recursive: true);
    await response.writeAsString(
      jsonEncode(loadYaml(await File(path).readAsString())),
    );
    return response.path;
  }
}

class _ProviderAppliedBoundary extends _UnusedCore {
  Map<String, dynamic>? response;
  final methods = <CoreMethod>[];
  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) async {
    methods.add(method);
    expect(method, CoreMethod.getAppliedSetup);
    return response as T?;
  }
}

class _ProviderCancelService implements Service {
  final entered = Completer<CoreMethodCall>();
  final cancelled = Completer<String>();
  final joined = Completer<void>();
  final late = Completer<CoreMethodResponse?>();
  final lifecycle = <String>[];
  @override
  Future<String> init() async {
    lifecycle.add('init');
    return '';
  }

  @override
  Future<String> syncState(SharedState state) async {
    lifecycle.add('sync');
    return '';
  }

  @override
  Future<bool> shutdown() async {
    lifecycle.add('shutdown');
    return true;
  }

  @override
  Future<bool> stop() async {
    lifecycle.add('stop');
    return true;
  }

  @override
  Future<CoreMethodResponse?> invokeMethod(CoreMethodCall call) async {
    if (call.method == CoreMethod.getAppliedSetup)
      return CoreMethodResponse(
        id: call.id,
        result: {
          'revision': 'original-revision',
          'applied': const SetupRecord(
            digest: 'original-digest',
            yamlMd5: '0123456789abcdef0123456789abcdef',
          ).toJson(),
        },
      );
    if (call.method == CoreMethod.getConfig) {
      final response = File(
        '${await appPath.homeDirPath}/responses/provider-source.json',
      );
      await response.parent.create(recursive: true);
      await response.writeAsString(
        jsonEncode(
          loadYaml(await File(call.arguments as String).readAsString()),
        ),
      );
      return CoreMethodResponse(id: call.id, result: response.path);
    }
    if (call.method == CoreMethod.setupConfig) {
      entered.complete(call);
      return late.future;
    }
    throw StateError('Unexpected provider fixture RPC: ${call.method}');
  }

  @override
  Future<void> cancelMethod(String id) async {
    cancelled.complete(id);
    await joined.future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('Unexpected provider lifecycle call');
}

const core = String.fromEnvironment('DNS99_CORE', defaultValue: 'meow-rs');
Map<String, dynamic>? lastUpdateWire;
const baseSource = {
  'geodata': {
    'auto-update': false,
    'url': {'mmdb': 'https://profile.invalid/mmdb'},
  },
};
Future<String> actualProfile(
  Directory root, {
  bool ipv6 = true,
  Map<String, dynamic> source = baseSource,
  String? script,
  PatchClashConfig? patch,
  ProfileOverrides? overrides,
}) async {
  await useAppPaths(root);
  var actual = patch ?? PatchClashConfig(ipv6: ipv6);
  if (core == 'meow-rs')
    actual = actual.copyWith.tun(
      stack: TunStack.lwip,
      congestionController: 'builtin',
      device: '',
    );
  final config = Config.realFromJson(
    null,
    coreId: core,
  ).copyWith(patchClashConfig: actual);
  final container = ProviderContainer(
    overrides: [
      ...buildConfigOverrides(config),
      coreHandlerProvider.overrideWithValue(
        CoreController.scoped(_UnusedCore()),
      ),
    ],
  );
  Script? model;
  if (script != null) {
    model = Script(
      id: 41,
      label: 'fixture',
      lastUpdateTime: DateTime.fromMillisecondsSinceEpoch(1000),
    );
    final file = File(await model.path);
    await file.parent.create(recursive: true);
    await file.writeAsString(script);
  }
  try {
    lastUpdateWire =
        jsonDecode(jsonEncode(container.read(updateParamsProvider)))
            as Map<String, dynamic>;
    final profileOverrides = (overrides ?? const ProfileOverrides()).copyWith(
      baseConfig: source,
      applyExtensionToCustom: script != null,
    );
    final state = SetupState(
      profileId: 7,
      profileLastUpdateDate: 1000,
      profileType: ProfileType.custom,
      extendType: script == null ? ExtendType.standard : ExtendType.script,
      rules: [const Rule(ruleAction: RuleAction.MATCH, ruleTarget: 'DIRECT')],
      proxyGroups: [],
      addedRules: [],
      script: model,
      overrides: profileOverrides,
    );
    final built = await container
        .read(setupActionProvider.notifier)
        .getProfile(setupState: state, patchConfig: actual);
    return built.yaml;
  } finally {
    container.dispose();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  ClientProduct.configure(
    product: ProductSpec(
      id: 'fixture',
      displayName: 'fixture',
      expectedCoreId: core,
      minimumAndroidApi: 33,
      resources: ProductResources(
        defaultScriptAsset: '',
        defaultScriptSha256: '',
        defaultScriptUrl: '',
        legacyScriptUrls: {},
        iconAsset: '',
      ),
      releaseFeed: '',
      releaseTagPrefix: '',
      releaseAssetPattern: '',
      changelogPath: '',
      coreSourceUrl: '',
    ),
    backendCoreId: core,
    backendFactory: () => throw StateError('no core launch'),
  );
  globalState.packageInfo = PackageInfo(
    appName: 'fixture',
    packageName: 'fixture',
    version: '1',
    buildNumber: '1',
  );
  if (core == 'meow-rs')
    test(
      'an old same-name provider object cannot side-load into a new runtime',
      () async {
        final home = await Directory.systemTemp.createTemp(
          'stale-provider-object-',
        );
        final sql = db.Database(NativeDatabase.memory());
        final previous = db.database;
        db.database = sql;
        ProviderContainer? container;
        try {
          await useAppPaths(home);
          await sql.profiles.put(
            const Profile(
              id: 7,
              autoUpdateDuration: Duration.zero,
            ).toCompanion(),
          );
          final boundary = _ProviderAppliedBoundary()
            ..response = {
              'revision': 'new-runtime',
              'applied': const SetupRecord(
                digest: 'new-source',
                yamlMd5: '0123456789abcdef0123456789abcdef',
              ).toJson(),
            };
          final config = Config.realFromJson(
            null,
            coreId: core,
          ).copyWith(currentProfileId: 7);
          container = ProviderContainer(
            overrides: [
              ...buildConfigOverrides(config),
              coreHandlerProvider.overrideWithValue(
                CoreController.scoped(boundary),
              ),
            ],
          );
          final hold = container.listen(profilesProvider, (_, _) {});
          await container.read(profilesStreamProvider.future);
          container.read(coreStatusProvider.notifier).value =
              CoreStatus.connected;
          for (final captured in <String?>[null, 'old-runtime']) {
            boundary.methods.clear();
            final provider = ExternalProvider(
              name: 'same-name',
              type: 'Proxy',
              count: 1,
              path: '${home.path}/old-provider-source',
              vehicleType: 'HTTP',
              updateAt: DateTime.fromMillisecondsSinceEpoch(0),
              runtimeRevision: captured,
            );
            await expectLater(
              container
                  .read(proxiesActionProvider.notifier)
                  .sideLoadExternalProvider(provider, 'old-editor-content'),
              throwsA(
                isA<CoreMethodException>().having(
                  (error) => error.code,
                  'code',
                  'superseded',
                ),
              ),
            );
            expect(
              boundary.methods,
              captured == null ? <CoreMethod>[] : [CoreMethod.getAppliedSetup],
            );
            expect(
              await Directory(await appPath.homeDirPath)
                  .list()
                  .where((file) => file.path.contains('provider-input-'))
                  .isEmpty,
              true,
            );
          }
          hold.close();
        } finally {
          container?.dispose();
          db.database = previous;
          await sql.close();
          await home.delete(recursive: true);
        }
      },
    );
  if (core == 'meow-rs')
    for (final (sourceWrite, failJoin) in [
      (false, false),
      (true, false),
      (true, true),
    ])
      test(
        '${sourceWrite ? 'source publication' : 'profile switch'} cancels only the actual provider setup request and waits for native join${failJoin ? ' (join failure)' : ''}',
        () async {
          final home = await Directory.systemTemp.createTemp(
            'provider-profile-cancel-',
          );
          final sql = db.Database(NativeDatabase.memory());
          final previous = db.database;
          db.database = sql;
          final service = _ProviderCancelService();
          final handler = MeowCoreHandler.scoped(
            service,
            readSharedState: () async => const SharedState(
              stopTip: 'stop',
              startTip: 'start',
              localNetworkTip: 'network',
              currentProfileName: 'fixture',
              stopText: 'stop',
              onlyStatisticsProxy: true,
            ),
          );
          ProviderContainer? container;
          try {
            await useAppPaths(home);
            SharedPreferences.setMockInitialValues({});
            await preferences.rebind();
            final config = Config.realFromJson(
              null,
              coreId: core,
            ).copyWith(currentProfileId: 7);
            await preferences.saveConfig(config);
            const profile = Profile(id: 7, autoUpdateDuration: Duration.zero);
            await sql.profiles.put(profile.toCompanion());
            final file = await profile.file;
            await file.writeAsString(
              'proxy-providers: {fixture: {type: http, url: "https://fixture.invalid/proxies", path: "./provider.yaml"}}\n'
              'proxy-groups: [{name: choice, type: select, use: [fixture]}]\nrules: ["MATCH,choice"]\n',
            );
            await handler.start();
            container = ProviderContainer(
              overrides: [
                ...buildConfigOverrides(config),
                coreHandlerProvider.overrideWithValue(
                  CoreController.scoped(handler),
                ),
              ],
            );
            globalState.container = container;
            final hold = container.listen(profilesProvider, (_, _) {});
            await container.read(profilesStreamProvider.future);
            container.read(coreStatusProvider.notifier).value =
                CoreStatus.connected;
            final setup = container.read(setupActionProvider.notifier);
            var finished = false;
            final pending = setup
                .updateMeowProvider(
                  namespace: 'proxy-providers',
                  name: 'fixture',
                  expectedRuntimeRevision: 'original-revision',
                )
                .whenComplete(() => finished = true);
            final checked = expectLater(
              pending,
              throwsA(isA<CoreMethodException>()),
            );
            final request = await service.entered.future.timeout(
              const Duration(seconds: 10),
            );
            final oldSource = await file.readAsString();
            Future<void>? writing;
            Future<void>? checkedWriting;
            if (sourceWrite) {
              writing = setup.withProviderSourceWrite(() async {
                await file.writeAsString(
                  'new source after joined cancellation',
                );
              });
              if (failJoin)
                checkedWriting = expectLater(
                  writing,
                  throwsA(
                    isA<CoreMethodException>().having(
                      (error) => error.code,
                      'code',
                      'termination_unconfirmed',
                    ),
                  ),
                );
            } else {
              container.read(currentProfileIdProvider.notifier).value = 8;
            }
            expect(
              await service.cancelled.future.timeout(
                const Duration(seconds: 5),
              ),
              request.id,
            );
            expect(finished, false);
            expect(await file.readAsString(), oldSource);
            expect(service.lifecycle, ['init', 'sync']);
            if (failJoin)
              service.joined.completeError(
                PlatformException(code: 'termination_unconfirmed'),
              );
            else
              service.joined.complete();
            await checked;
            if (failJoin) {
              await checkedWriting;
              expect(await file.readAsString(), oldSource);
              await expectLater(
                setup.withProviderSourceWrite(() async {
                  await file.writeAsString('must not publish');
                }),
                throwsA(isA<CoreMethodException>()),
              );
              expect(await file.readAsString(), oldSource);
            } else if (writing != null) {
              await writing;
              expect(
                await file.readAsString(),
                'new source after joined cancellation',
              );
            }
            service.late.complete(
              CoreMethodResponse(id: request.id, result: ''),
            );
            await pumpEventQueue();
            if (failJoin) {
              await setup.withProviderSourceWrite(() async {
                await file.writeAsString(
                  'new source after actual native completion',
                );
              });
              expect(
                await file.readAsString(),
                'new source after actual native completion',
              );
            }
            hold.close();
          } finally {
            if (!service.joined.isCompleted) service.joined.complete();
            container?.dispose();
            await handler.close();
            db.database = previous;
            await sql.close();
            await home.delete(recursive: true);
          }
        },
      );
  if (core == 'meow-rs')
    test(
      'provider receipt read requires an actual revision and keeps applied identity',
      () async {
        final boundary = _ProviderAppliedBoundary();
        final controller = CoreController.scoped(boundary);
        const record = SetupRecord(
          digest: 'fixture-source',
          yamlMd5: '0123456789abcdef0123456789abcdef',
        );
        for (final value in <Map<String, dynamic>?>[
          null,
          {'applied': record.toJson()},
          {'revision': null, 'applied': record.toJson()},
          {'revision': 'current', 'applied': null},
        ]) {
          boundary.response = value;
          await expectLater(
            controller.readMeowProviderApplied(),
            throwsA(isA<CoreMethodException>()),
          );
        }
        boundary.response = {
          'revision': 'actual-revision',
          'applied': record.toJson(),
        };
        final actual = await controller.readMeowProviderApplied();
        expect(actual.revision, 'actual-revision');
        expect(actual.applied, record);
      },
    );
  if (core == 'meow-rs')
    test(
      'Dart file import keeps full source and materializes one compatible runtime read',
      () async {
        final home = await Directory.systemTemp.createTemp(
          'subscription-dart-closure-',
        );
        final sql = db.Database(NativeDatabase.memory());
        final previous = db.database;
        db.database = sql;
        const raw =
            'proxies: [{name: supported, type: vless}, {name: unsupported, type: mieru}]\n'
            'proxy-groups: [{name: choice, type: select, proxies: [supported, unsupported]}]\nrules: ["MATCH,choice"]\n';
        ProviderContainer? container;
        try {
          await useAppPaths(home);
          final boundary = _ProfileFileBoundary();
          final controller = CoreController.scoped(boundary);
          var removed = 0;
          final profile =
              await const Profile(
                id: 7,
                autoUpdateDuration: Duration.zero,
              ).saveFile(
                utf8.encode(raw),
                validate: (path, key) => controller.validateProfile(
                  path,
                  key,
                  (report) => removed = report.removed,
                ),
              );
          expect(await (await profile.file).readAsString(), raw);
          expect(removed, 1);
          await sql.profiles.put(profile.toCompanion());
          final patch = Config.realFromJson(
            null,
            coreId: core,
          ).patchClashConfig;
          container = ProviderContainer(
            overrides: [
              ...buildConfigOverrides(
                Config.realFromJson(
                  null,
                  coreId: core,
                ).copyWith(currentProfileId: 7),
              ),
              coreHandlerProvider.overrideWithValue(controller),
            ],
          );
          final keep = container.listen(profilesProvider, (_, _) {});
          await container.read(profilesStreamProvider.future);
          final result = await container
              .read(setupActionProvider.notifier)
              .getProfile(
                setupState: const SetupState(
                  profileId: 7,
                  profileLastUpdateDate: 1000,
                  profileType: ProfileType.file,
                  extendType: ExtendType.standard,
                  rules: [],
                  proxyGroups: [],
                  addedRules: [],
                  script: null,
                  overrides: ProfileOverrides(),
                ),
                patchConfig: patch,
              );
          final runtime = loadYaml(result.yaml) as Map;
          expect((runtime['proxies'] as List).map((node) => node['name']), [
            'supported',
          ]);
          expect(runtime['proxy-groups'][0]['proxies'], ['supported']);
          expect(result.base, isNull);
          expect(boundary.validations, 1);
          expect(boundary.sourceReads, 1);
          expect(await (await profile.file).readAsString(), raw);
          keep.close();
        } finally {
          container?.dispose();
          db.database = previous;
          await sql.close();
          await home.delete(recursive: true);
        }
      },
    );
  if (core == 'meow-rs')
    test(
      'explicit DNS IPv6 survives both root states and invalid scalars fail',
      () async {
        final home = await Directory.systemTemp.createTemp(
          'dns-ipv6-compatible-',
        );
        try {
          for (final root in [false, true]) {
            for (final dns in [false, true]) {
              final yaml = await actualProfile(
                home,
                ipv6: root,
                source: {
                  ...baseSource,
                  'dns': {'enable': true, 'ipv6': dns},
                },
              );
              final actual = jsonDecode(jsonEncode(loadYaml(yaml))) as Map;
              expect(actual['ipv6'], root);
              expect(actual['dns']['ipv6'], dns);
            }
          }
          for (final invalid in <Object?>['true', 1, null]) {
            await expectLater(
              actualProfile(
                home,
                source: {
                  ...baseSource,
                  'dns': {'ipv6': invalid},
                },
              ),
              throwsFormatException,
            );
          }
        } finally {
          await home.delete(recursive: true);
        }
      },
    );
  test(
    'constructor provenance survives storage but no direct DNS or update wire serializer leaks it',
    () async {
      final direct = jsonDecode(jsonEncode(xclashDefaultDns.toJson())) as Map;
      expect(direct.containsKey(Dns.generatedDefaultsJsonKey), false);
      expect(
        xclashDefaultDns.generatedDefaultKeys,
        xclashDefaultDnsOverrideKeys,
      );
      final patch = const PatchClashConfig();
      final saved = jsonDecode(jsonEncode(patch)) as Map<String, dynamic>;
      expect(saved['dns'][Dns.generatedDefaultsJsonKey], [
        'ipv6',
        'fake-ip-range6',
      ]);
      expect(
        PatchClashConfig.fromJson(saved).dns.generatedDefaultKeys,
        xclashDefaultDnsOverrideKeys,
      );
      final profile = ProfileOverrides.fromJson(
        jsonDecode(jsonEncode(const ProfileOverrides()))
            as Map<String, dynamic>,
      );
      expect(profile.dns.generatedDefaultKeys, xclashDefaultDnsOverrideKeys);
      final existing = Map<String, Object?>.from(saved['dns'] as Map)
        ..remove(Dns.generatedDefaultsJsonKey);
      expect(Dns.safeDnsFromJson(existing).generatedDefaultKeys, isEmpty);
      final missing = Map<String, Object?>.from(existing)
        ..remove('ipv6')
        ..remove('fake-ip-range6');
      expect(
        Dns.safeDnsFromJson(missing).generatedDefaultKeys,
        xclashDefaultDnsOverrideKeys,
      );
      final yaml = xclashDefaultDns.applyOverrideYaml('ipv6: true\n');
      expect(yaml.dns.generatedDefaultKeys, {DnsOverrideKey.fakeIpRange6});
      final equalEdit = const DnsView().spec
          .fieldOf(DnsOverrideKey.ipv6)
          .reset(xclashDefaultDns, xclashDefaultDns);
      expect(equalEdit.generatedDefaultKeys, {DnsOverrideKey.fakeIpRange6});
      final equalRange = const DnsView().spec
          .fieldOf(DnsOverrideKey.fakeIpRange6)
          .reset(xclashDefaultDns, xclashDefaultDns);
      expect(equalRange.generatedDefaultKeys, {DnsOverrideKey.ipv6});
      expect(
        equalEdit
            .copyWith(ipv6: false)
            .copyWith(ipv6: true)
            .generatedDefaultKeys,
        {DnsOverrideKey.fakeIpRange6},
      );
      final damaged = Map<String, Object?>.from(existing)
        ..[Dns.generatedDefaultsJsonKey] = ['ipv6', 'ipv6'];
      final safe = ProfileOverrides.safeFromJson({
        'dns': damaged,
        'dns-override-keys': ['ipv6', 'fake-ip-range6'],
      });
      expect(safe.dns.generatedDefaultKeys, isEmpty);
      expect(safe.dns.nameserver, xclashDefaultDns.nameserver);
      expect(
        () => Dns.safeDnsFromJson({
          Dns.generatedDefaultsJsonKey: ['ipv6', 'ipv6'],
        }),
        throwsA(isA<FormatException>()),
      );
      expect(
        jsonEncode(xclashDefaultDns.overrideJson(xclashDefaultDnsOverrideKeys)),
        isNot(contains(Dns.generatedDefaultsJsonKey)),
      );
    },
  );
  if (core == 'meow-rs')
    test(
      'ordinary Native/global merge preserves constructor origin while actual explicit Native keys erase it',
      () async {
        final home = await Directory.systemTemp.createTemp('dns99-normal-');
        final database = db.Database(NativeDatabase.memory());
        final prior = db.database;
        db.database = database;
        try {
          await useAppPaths(home);
          final native = ProfileOverrides(
            nativeProfileUuid: '11111111-1111-1111-1111-111111111111',
            dns: const Dns(nameserver: ['https://9.9.9.9/dns-query']),
            dnsOverrideKeys: {DnsOverrideKey.nameserver},
          );
          await database
              .into(database.profiles)
              .insert(
                Profile(
                  id: 7,
                  autoUpdateDuration: Duration.zero,
                  overrides: native,
                ).toCompanion(),
              );
          final patch = const PatchClashConfig(ipv6: false).copyWith.tun(
            stack: TunStack.lwip,
            congestionController: 'builtin',
            device: '',
          );
          final config = Config.realFromJson(
            null,
            coreId: core,
          ).copyWith(currentProfileId: 7, patchClashConfig: patch);
          final container = ProviderContainer(
            overrides: [
              ...buildConfigOverrides(config),
              coreHandlerProvider.overrideWithValue(
                CoreController.scoped(_UnusedCore()),
              ),
            ],
          );
          final keep = container.listen(profilesProvider, (_, _) {});
          await container.read(profilesStreamProvider.future);
          try {
            final state = await container.read(setupStateProvider(7).future);
            expect(
              state.overrides.dns.generatedDefaultKeys,
              xclashDefaultDnsOverrideKeys,
            );
            expect(state.overrides.dns.nameserver, [
              'https://9.9.9.9/dns-query',
            ]);
            expect(meowDnsScriptInput('{}', overrides: state.overrides), '{}');
            final overlay = await makeRealProfileTask(
              MakeRealProfileState(
                profilesPath: await appPath.profilesPath,
                profileId: 7,
                rawConfig: {
                  'dns': {
                    'enable': true,
                    'nameserver': ['https://1.1.1.1/dns-query'],
                  },
                  'rules': ['MATCH,DIRECT'],
                },
                realPatchConfig: patch,
                overrides: state.overrides,
                keepEmptyDnsOverrides: true,
                appendSystemDns: false,
                proxyGroups: [],
                rules: [],
                addedRules: [],
                defaultUA: 'fixture',
                safeMode: false,
              ),
              coreId: core,
            );
            final parsed =
                jsonDecode(jsonEncode(loadYaml(overlay.yaml))) as Map;
            expect(parsed['ipv6'], false);
            expect((parsed['dns'] as Map).containsKey('ipv6'), false);
            expect(parsed['dns']['nameserver'], ['https://9.9.9.9/dns-query']);
            container
                .read(profilesProvider.notifier)
                .updateProfile(
                  7,
                  (profile) => profile.copyWith.overrides(
                    dns: profile.overrides.dns.copyWith(ipv6: true),
                    dnsOverrideKeys: {
                      DnsOverrideKey.nameserver,
                      DnsOverrideKey.ipv6,
                    },
                  ),
                );
            await waitForDatabaseWrites();
            await container.pump();
            container.invalidate(setupStateProvider(7));
            final explicit = await container.read(setupStateProvider(7).future);
            expect(explicit.overrides.dns.generatedDefaultKeys, {
              DnsOverrideKey.fakeIpRange6,
            });
            expect(
              meowDnsScriptInput('{}', overrides: explicit.overrides),
              '{}',
            );
          } finally {
            keep.close();
            container.dispose();
          }
        } finally {
          db.database = prior;
          await database.close();
          await home.delete(recursive: true);
        }
      },
    );
  test(
    'capture full actual getProfile and existing single Node JS path without input deletion',
    () async {
      final home = await Directory.systemTemp.createTemp('dns99-profile-');
      final previous = scriptEvaluator;
      var evaluations = 0;
      scriptEvaluator = ({required String script, required String config}) async {
        expect(config, isNot(contains(Dns.generatedDefaultsJsonKey)));
        evaluations++;
        final result = await Process.run(_nodeExecutable, [
          '-e',
          'const config=JSON.parse(process.argv[1]); const main=eval(process.argv[2]); process.stdout.write(JSON.stringify(main(config)));',
          config,
          script,
        ]);
        if (result.exitCode != 0) throw StateError(result.stderr.toString());
        return result.stdout.toString();
      };
      try {
        final positive = await actualProfile(home);
        final negative = await actualProfile(home, ipv6: false);
        final scripted = await actualProfile(
          home,
          script:
              '(config)=>{config.dns={enable:true,nameserver:["https://1.1.1.1/dns-query"]};return config;}',
        );
        expect(evaluations, 1);
        expect(
          jsonEncode(lastUpdateWire),
          isNot(contains(Dns.generatedDefaultsJsonKey)),
        );
        if (core == 'meow-rs') {
          for (final text in [positive, negative, scripted]) {
            final dns = jsonDecode(jsonEncode(loadYaml(text)))['dns'] as Map;
            expect(dns.containsKey('ipv6'), false);
            expect(dns.containsKey('fake-ip-range6'), false);
            expect(text, isNot(contains(Dns.generatedDefaultsJsonKey)));
          }
        }
        final changed = await actualProfile(
          home,
          patch: PatchClashConfig(
            geoAutoUpdate: true,
            geoUpdateInterval: 72,
            geoXUrl: {
              ...defaultGeoXUrl,
              GeoResource.MMDB: 'https://global.invalid/mmdb',
              GeoResource.ASN: 'https://global.invalid/asn',
            },
          ),
        );
        Map decode(String text) =>
            jsonDecode(jsonEncode(loadYaml(text))) as Map;
        final output = const String.fromEnvironment('DNS99_OUTPUT');
        if (output.isNotEmpty) {
          final row = {
            'schema': 1,
            'initialYaml': positive,
            'rootFalseYaml': negative,
            'scriptYaml': scripted,
            'rerunYaml': changed,
            'expectedOrigin': decode(positive)['aerove-geo-origin'],
            'changedGlobal': {
              'autoUpdate': true,
              'intervalHours': 72,
              'urls': {
                'mmdb': 'https://global.invalid/mmdb',
                'asn': 'https://global.invalid/asn',
                'geosite': defaultGeoXUrl[GeoResource.GEOSITE],
              },
            },
            'expectedAfterGlobal': decode(changed)['geodata'],
            'dnsKeys': {
              for (final entry in {
                'rootTrue': positive,
                'rootFalse': negative,
                'script': scripted,
              }.entries)
                entry.key: (decode(entry.value)['dns'] as Map).keys.toList(),
            },
          };
          await File(output).writeAsString(jsonEncode(row));
          expect(jsonDecode(await File(output).readAsString())['schema'], 1);
        }
        if (core == 'mihomo') {
          expect(decode(positive)['dns']['ipv6'], true);
          expect(
            decode(positive)['dns']['fake-ip-range6'],
            xclashDefaultDns.fakeIpRange6,
          );
        }
      } finally {
        scriptEvaluator = previous;
        await home.delete(recursive: true);
      }
    },
  );
  if (core == 'meow-rs')
    test(
      'all thirteen unsupported original and script declarations reject before defaults hide them',
      () async {
        final values = <String, dynamic>{
          'listen-routing-mark': 0,
          'prefer-h3': false,
          'respect-rules': false,
          'ipv6-timeout': 100,
          'cache-algorithm': 'lru',
          'cache-max-size': 4096,
          'fake-ip-range6': xclashDefaultDns.fakeIpRange6,
          'fake-ip-ttl': 1,
          'fallback-lazy-query': false,
          'proxy-server-nameserver-policy': <String, dynamic>{},
          'direct-nameserver': <String>[],
          'direct-nameserver-follow-policy': false,
          'fallback-filter.geosite': <String>[],
        };
        final home = await Directory.systemTemp.createTemp('dns99-strict-');
        final previous = scriptEvaluator;
        var evaluations = 0;
        scriptEvaluator = ({required String script, required String config}) async {
          evaluations++;
          final result = await Process.run(_nodeExecutable, [
            '-e',
            'const config=JSON.parse(process.argv[1]); const main=eval(process.argv[2]); process.stdout.write(JSON.stringify(main(config)));',
            config,
            script,
          ]);
          if (result.exitCode != 0) throw StateError(result.stderr.toString());
          return result.stdout.toString();
        };
        try {
          for (final entry in values.entries) {
            final parts = entry.key.split('.');
            final dns = <String, dynamic>{
              if (parts.length == 1)
                entry.key: entry.value
              else
                parts[0]: {parts[1]: entry.value},
            };
            final expectation = throwsA(
              isA<FormatException>().having(
                (error) => error.message,
                'source path',
                contains('\$.dns.${entry.key}'),
              ),
            );
            await expectLater(
              actualProfile(home, source: {...baseSource, 'dns': dns}),
              expectation,
            );
            final before = evaluations;
            await expectLater(
              actualProfile(
                home,
                source: {...baseSource, 'dns': dns},
                script: '(config)=>{delete config.dns;return config;}',
              ),
              expectation,
            );
            expect(
              evaluations,
              before,
              reason: 'original source must reject before JS: ${entry.key}',
            );
            await expectLater(
              actualProfile(
                home,
                script:
                    '(config)=>{config.dns=${jsonEncode(dns)};return config;}',
              ),
              expectation,
            );
            expect(
              evaluations,
              before + 1,
              reason: 'single existing JS invocation: ${entry.key}',
            );
          }
          for (final dns in [
            xclashDefaultDns.copyWith(ipv6: false),
            xclashDefaultDns.withExplicitDnsKey(DnsOverrideKey.ipv6),
            xclashDefaultDns.copyWith(fakeIpRange6: 'fd00::/64'),
            xclashDefaultDns.withExplicitDnsKey(DnsOverrideKey.fakeIpRange6),
          ]) {
            await expectLater(
              actualProfile(home, overrides: ProfileOverrides(dns: dns)),
              throwsA(isA<FormatException>()),
            );
            final before = evaluations;
            await expectLater(
              actualProfile(
                home,
                overrides: ProfileOverrides(dns: dns),
                script: '(config)=>config',
              ),
              throwsA(isA<FormatException>()),
            );
            expect(evaluations, before);
          }
          for (final key in DnsOverrideKey.values.where(
            (key) =>
                values.containsKey(key.path) &&
                !xclashDefaultDnsOverrideKeys.contains(key),
          )) {
            await expectLater(
              actualProfile(
                home,
                overrides: ProfileOverrides(
                  dns: xclashDefaultDns,
                  dnsOverrideKeys: {key},
                ),
              ),
              throwsA(
                isA<FormatException>().having(
                  (e) => e.message,
                  'typed path',
                  contains('\$.dns.${key.path}'),
                ),
              ),
            );
          }
        } finally {
          scriptEvaluator = previous;
          await home.delete(recursive: true);
        }
      },
    );
}
