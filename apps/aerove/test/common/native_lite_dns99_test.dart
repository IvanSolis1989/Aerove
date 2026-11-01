import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
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
              () => meowDnsScriptInput('{}', overrides: explicit.overrides),
              throwsA(isA<FormatException>()),
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
      'all fourteen original and script declarations reject before a default or override can hide them',
      () async {
        final values = <String, dynamic>{
          'listen-routing-mark': 0,
          'prefer-h3': false,
          'respect-rules': false,
          'ipv6': true,
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
