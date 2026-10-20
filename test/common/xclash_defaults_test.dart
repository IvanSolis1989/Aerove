import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/backup.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yaml/yaml.dart';

import '../helpers/app_paths.dart';
import '../helpers/preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  late Directory home;
  late Database db;
  late SharedPreferences storage;
  late Uint8List bundledScript;
  Config? savedConfig;

  const initial = Config(themeProps: defaultThemeProps);

  setUp(() async {
    home = await Directory.systemTemp.createTemp('xclash-defaults-');
    db = Database(NativeDatabase.memory());
    storage = await SharedPreferences.getInstance();
    await storage.clear();
    bundledScript = Uint8List.fromList(
      utf8.encode(
        (await File(
          xclashDefaultScriptAsset,
        ).readAsString()).replaceAll('\r\n', '\n'),
      ),
    );
    savedConfig = null;
  });

  tearDown(() async {
    xclashDefaults = null;
    await db.close();
    await home.delete(recursive: true);
  });

  XClashDefaults service({bool failSave = false, Uint8List? source}) {
    return XClashDefaults(
      storage: storage,
      database: db,
      directory: home,
      loadScript: () async => source ?? bundledScript,
      createId: () => 123,
      saveConfig: (config) async {
        if (failSave) return false;
        savedConfig = config;
        return storage.setString(configKey, jsonEncode(config));
      },
    );
  }

  Future<XClashDefaults> install({bool isAndroid = true}) async {
    final defaults = service();
    await defaults.prepare(isAndroid: isAndroid);
    await defaults.initialize(initial);
    return defaults;
  }

  File scriptFile() => File('${home.path}/scripts/123.js');

  test(
    'open uses the owned preference store and public support path',
    () async {
      await mockPreferences();
      final owned = await SharedPreferences.getInstance();
      SharedPreferences.setMockInitialValues({'version': 1});
      final replacement = await SharedPreferences.getInstance();
      await useAppPaths(home);
      final previousDatabase = database;
      database = db;
      addTearDown(() => database = previousDatabase);

      final defaults = (await XClashDefaults.open())!;
      await defaults.prepare(isAndroid: true);
      final config = await defaults.initialize(initial);

      expect(config.vpnProps.dnsHijacking, false);
      expect(owned.getString(configKey), isNotNull);
      expect(replacement.getString(configKey), isNull);
      final script = (await db.scriptsDao.query().get()).single;
      expect(defaults.recommendedScriptId, script.id);
      expect(await File('${home.path}/scripts/${script.id}.js').exists(), true);
    },
  );

  test(
    'open respects preference rebind after a test store replacement',
    () async {
      await mockPreferences();
      SharedPreferences.setMockInitialValues({'version': 1});
      await preferences.rebind();
      await useAppPaths(home);
      final previousDatabase = database;
      database = db;
      addTearDown(() => database = previousDatabase);

      final defaults = (await XClashDefaults.open())!;
      await defaults.prepare(isAndroid: true);
      expect(await defaults.initialize(initial), initial);
      expect(await preferences.getVersion(), 1);
      expect(await preferences.getConfigMap(), isNull);
      expect(await db.scriptsDao.query().get(), hasLength(1));
    },
  );

  test(
    'fresh Android installation saves exact script and source URL',
    () async {
      await install();

      final script = (await db.scriptsDao.query().get()).single;
      expect(script.id, 123);
      expect(script.url, xclashDefaultScriptUrl);
      expect(await scriptFile().readAsBytes(), bundledScript);
      expect(
        sha256.convert(bundledScript).toString(),
        xclashDefaultScriptSha256,
      );
      expect(savedConfig!.vpnProps.enable, true);
      expect(savedConfig!.vpnProps.systemProxy, true);
      expect(savedConfig!.vpnProps.ipv6, true);
      expect(savedConfig!.vpnProps.dnsHijacking, false);
      expect(savedConfig!.vpnProps.allowBypass, true);
      expect(savedConfig!.vpnProps.accessControlProps.enable, false);
      expect(savedConfig!.networkProps.appendSystemDns, false);
      expect(savedConfig!.patchClashConfig.dnsOverrideKeys, {
        ...xclashDefaultDnsOverrideKeys,
        DnsOverrideKey.defaultNameserver,
      });
      expect(savedConfig!.patchClashConfig.ipv6, true);
      expect(savedConfig!.patchClashConfig.mode, Mode.rule);
      expect(savedConfig!.patchClashConfig.keepAliveIdle, 30);
      expect(savedConfig!.patchClashConfig.keepAliveInterval, 15);
      expect(savedConfig!.patchClashConfig.tcpConcurrent, true);
      expect(savedConfig!.patchClashConfig.unifiedDelay, true);
      expect(
        savedConfig!.patchClashConfig.findProcessMode,
        FindProcessMode.always,
      );
      expect(savedConfig!.appSettingProps.onlyStatisticsProxy, true);
      expect(savedConfig!.patchClashConfig.tun, initial.patchClashConfig.tun);
      expect(savedConfig!.patchClashConfig.dns, xclashDefaultDns);
      expect(
        savedConfig!.networkProps.bypassPrivateRoute,
        initial.networkProps.bypassPrivateRoute,
      );
      expect(savedConfig!.themeProps, initial.themeProps);
      expect(
        Config.fromJson(jsonDecode(storage.getString(configKey)!)),
        savedConfig,
      );
    },
  );

  test(
    'repeated startup keeps user settings and updated script bytes',
    () async {
      await install();
      await scriptFile().writeAsString('user-updated-script');
      final user = initial.copyWith.vpnProps(
        systemProxy: false,
        ipv6: false,
        dnsHijacking: true,
        allowBypass: false,
      );
      savedConfig = null;
      final restarted = service();
      await restarted.prepare(isAndroid: true);
      expect(await restarted.initialize(user), user);
      expect(savedConfig, isNull);
      expect(await db.scriptsDao.query().get(), hasLength(1));
      expect(await scriptFile().readAsString(), 'user-updated-script');
    },
  );

  test(
    'desktop gets the offline script without Android network changes',
    () async {
      await install(isAndroid: false);
      expect(savedConfig, isNull);
      expect(await db.scriptsDao.query().get(), hasLength(1));
    },
  );

  test(
    'existing or corrupt preference data never receives the fresh preset',
    () async {
      await storage.setString(configKey, '{broken-json');
      await install();
      expect(savedConfig, isNull);
      expect(storage.getString(configKey), '{broken-json');
      expect(await db.scriptsDao.query().get(), hasLength(1));
    },
  );

  test('upgrades preserve chosen DNS and network settings', () async {
    final previous = initial.copyWith(
      networkProps: initial.networkProps.copyWith(appendSystemDns: true),
      vpnProps: initial.vpnProps.copyWith(
        enable: false,
        systemProxy: false,
        ipv6: false,
        dnsHijacking: true,
        allowBypass: false,
      ),
      patchClashConfig: initial.patchClashConfig.copyWith(
        mode: Mode.global,
        dns: const Dns(defaultNameserver: ['tls://resolver.example.test']),
        dnsOverrideKeys: {DnsOverrideKey.defaultNameserver},
      ),
    );
    await storage.setInt('version', 1);
    final defaults = service();
    await defaults.prepare(isAndroid: true);
    expect(await defaults.initialize(previous), previous);
    expect(savedConfig, isNull);
  });

  test('only an intact untouched legacy DNS baseline is replaced', () async {
    final previous = initial.copyWith(
      vpnProps: initial.vpnProps.copyWith(enable: false, allowBypass: true),
      patchClashConfig: initial.patchClashConfig.copyWith(
        dns: defaultDns,
        dnsOverrideKeys: {},
      ),
    );
    await storage.setString(configKey, jsonEncode(previous));
    final defaults = service();
    await defaults.prepare(isAndroid: true);
    final next = await defaults.initialize(previous);
    expect(next.patchClashConfig.dns, xclashDefaultDns);
    expect(next.patchClashConfig.dnsOverrideKeys, {
      ...xclashDefaultDnsOverrideKeys,
      DnsOverrideKey.defaultNameserver,
    });
    expect(next.vpnProps, previous.vpnProps);
    expect(next.networkProps, previous.networkProps);
    expect(next.patchClashConfig.tun, previous.patchClashConfig.tun);
  });

  test('an explicitly selected legacy DNS override is preserved', () async {
    final previous = initial.copyWith.patchClashConfig(
      dns: defaultDns,
      dnsOverrideKeys: {DnsOverrideKey.defaultNameserver},
    );
    await storage.setString(configKey, jsonEncode(previous));
    final defaults = service();
    await defaults.prepare(isAndroid: true);
    expect(await defaults.initialize(previous), previous);
    expect(savedConfig, isNull);
  });

  test(
    'malformed stored DNS cannot qualify for legacy baseline replacement',
    () async {
      final previous = initial.copyWith.patchClashConfig(
        dns: defaultDns,
        dnsOverrideKeys: {},
      );
      final raw = jsonDecode(jsonEncode(previous)) as Map<String, dynamic>;
      raw['patchClashConfig']['dns']['ipv6'] = 'invalid';
      await storage.setString(configKey, jsonEncode(raw));
      final defaults = service();
      await defaults.prepare(isAndroid: true);
      expect(await defaults.initialize(previous), previous);
      expect(savedConfig, isNull);
    },
  );

  test('app defaults expose only the approved encrypted resolver lists', () {
    final dns = const PatchClashConfig().dns;
    expect(dns, xclashDefaultDns);
    expect(dns.defaultNameserver, xclashBootstrapNameservers);
    expect(dns.nameserver, xclashDomesticNameservers);
    expect(dns.fallback, xclashForeignNameservers);
    for (final list in [
      dns.defaultNameserver,
      dns.nameserver,
      dns.fallback,
      dns.directNameserver,
      dns.proxyServerNameserver,
    ]) {
      expect(list.every((value) => value.startsWith('https://')), true);
    }
    expect(baselineDnsOverrideKeys, contains(DnsOverrideKey.defaultNameserver));
  });

  test(
    'a nonempty database protects an installation with missing config',
    () async {
      final previous = Profile.normal(label: 'Existing subscription');
      await db.profilesDao.putAll([previous.toCompanion()]);
      await install();
      expect(savedConfig, isNull);
      expect(await db.profilesDao.query().get(), [previous]);
    },
  );

  test(
    'existing data files protect an installation even with empty stores',
    () async {
      await File(
        '${home.path}/previous-config.yaml',
      ).writeAsString('proxies: []');
      await install();
      expect(savedConfig, isNull);
    },
  );

  test('a failed asset load writes no script row and can be retried', () async {
    final failed = service(source: Uint8List(0));
    expect(failed.recommendedScriptId, isNull);
    await failed.prepare(isAndroid: true);
    await expectLater(failed.initialize(initial), throwsStateError);
    expect(await db.scriptsDao.query().get(), isEmpty);
    expect(await scriptFile().exists(), false);
    expect(savedConfig, isNull);
    expect(failed.recommendedScriptId, isNull);

    await install();
    expect(await db.scriptsDao.query().get(), hasLength(1));
    expect(savedConfig!.vpnProps.dnsHijacking, false);
  });

  test(
    'failed preference save rolls back the row and retries the same id',
    () async {
      final failed = service(failSave: true);
      await failed.prepare(isAndroid: true);
      await expectLater(failed.initialize(initial), throwsStateError);
      expect(await db.scriptsDao.query().get(), isEmpty);
      expect(savedConfig, isNull);

      await install();
      expect((await db.scriptsDao.query().get()).single.id, 123);
      expect(savedConfig!.vpnProps.dnsHijacking, false);
    },
  );

  test(
    'script file failure happens before insertion and can be retried',
    () async {
      final failed = service();
      await failed.prepare(isAndroid: true);
      final blocked = File('${home.path}/scripts');
      await blocked.writeAsString('cannot create a script directory here');
      await expectLater(
        failed.initialize(initial),
        throwsA(isA<FileSystemException>()),
      );
      expect(await db.scriptsDao.query().get(), isEmpty);
      expect(failed.recommendedScriptId, isNull);
      expect(savedConfig, isNull);

      await blocked.delete();
      await install();
      expect((await db.scriptsDao.query().get()).single.id, 123);
    },
  );

  test(
    'new subscription and file profiles associate with the installed script',
    () async {
      final defaults = await install();
      expect(defaults.recommendedScriptId, 123);
      for (final profile in [
        Profile.normal(url: 'https://example.test/subscription'),
        Profile.normal(label: 'Local or share-link profile'),
      ]) {
        final linked = await defaults.associateNewProfile(profile);
        expect(linked.extendType, ExtendType.script);
        expect(linked.scriptId, 123);
        expect(
          linked.copyWith(extendType: profile.extendType, scriptId: null),
          profile,
        );
      }
    },
  );

  test(
    'editing the script source URL preserves its default association',
    () async {
      final defaults = await install();
      final script = (await db.scriptsDao.query().get()).single;
      await db.scriptsDao.putAll([
        script
            .copyWith(url: 'https://example.test/custom-script.js')
            .toCompanion(),
      ]);

      final linked = await defaults.associateNewProfile(Profile.normal());
      expect(linked.extendType, ExtendType.script);
      expect(linked.scriptId, script.id);
    },
  );

  test('a missing script file disables default association', () async {
    final defaults = await install();
    await scriptFile().delete();
    final profile = Profile.normal();
    expect(await defaults.associateNewProfile(profile), profile);
    expect(await db.scriptsDao.query().get(), hasLength(1));
  });

  test(
    'a deleted script record disables association even if its file remains',
    () async {
      final defaults = await install();
      await db.delete(db.scripts).go();
      final profile = Profile.normal();
      expect(await defaults.associateNewProfile(profile), profile);
      expect(await scriptFile().exists(), true);
    },
  );

  test(
    'existing, custom and explicitly configured profiles retain their extensions',
    () async {
      final defaults = await install();
      final previous = Profile.normal();
      await db.profilesDao.putAll([previous.toCompanion()]);
      final explicit = Profile.normal().copyWith(
        extendType: ExtendType.script,
        scriptId: 99,
      );
      for (final profile in [previous, Profile.custom(), explicit]) {
        expect(await defaults.associateNewProfile(profile), profile);
      }
    },
  );

  test(
    'deleting the default script survives restart and disables default linking',
    () async {
      await install();
      await db.delete(db.scripts).go();
      await scriptFile().delete();
      final restarted = service();
      await restarted.prepare(isAndroid: true);
      await restarted.initialize(initial);
      expect(await db.scriptsDao.query().get(), isEmpty);
      expect(await scriptFile().exists(), false);
      final profile = Profile.normal();
      expect(await restarted.associateNewProfile(profile), profile);
    },
  );

  test(
    'restoring profiles keeps their extension and does not restore the device marker',
    () async {
      final defaults = await install();
      final restored = Profile.normal(label: 'Restored').copyWith(scriptId: 77);
      await db.restore([restored], [], [], [], [], isOverride: true);
      expect(await defaults.associateNewProfile(restored), restored);
      final next = Profile.normal();
      expect(await defaults.associateNewProfile(next), next);
      final restarted = service();
      await restarted.prepare(isAndroid: true);
      await restarted.initialize(initial);
      expect(await db.scriptsDao.query().get(), isEmpty);
      expect(
        BackupEntries.of(profileIds: [], scriptIds: [], providers: []),
        isEmpty,
      );
      expect(await File('${home.path}/xclash-defaults.json').exists(), true);
    },
  );

  for (final fixture in ['baseline', 'private']) {
    test(
      'normal profile assembly changes only selected DNS defaults ($fixture)',
      () async {
        await install();
        final config = savedConfig!;
        final dns =
            jsonDecode(
                  await File(
                    'test/fixtures/smart_config_kit/dns-$fixture.json',
                  ).readAsString(),
                )
                as Map<String, dynamic>;
        final result = await makeRealProfileTask(
          MakeRealProfileState(
            profilesPath: '${home.path}/profiles',
            profileId: 7,
            rawConfig: {'dns': dns},
            realPatchConfig: config.patchClashConfig,
            overrides: ProfileOverrides(
              dns: config.patchClashConfig.dns,
              dnsOverrideKeys: config.patchClashConfig.dnsOverrideKeys,
            ),
            keepEmptyDnsOverrides: true,
            appendSystemDns: config.networkProps.appendSystemDns,
            proxyGroups: const [],
            rules: const [],
            addedRules: const [],
            defaultUA: 'XClash-Test',
            safeMode: false,
          ),
        );
        final finalConfig = loadYaml(result.yaml) as YamlMap;
        expect(finalConfig['dns'], {
          ...dns,
          'ipv6': true,
          'fake-ip-range6': xclashDefaultDns.fakeIpRange6,
          'default-nameserver': xclashBootstrapNameservers,
        });
        for (final key in const [
          'nameserver',
          'fallback',
          'proxy-server-nameserver',
          'proxy-server-nameserver-policy',
          'direct-nameserver',
          'nameserver-policy',
        ]) {
          expect(finalConfig['dns'][key], dns[key], reason: key);
        }
        expect(finalConfig['dns']['nameserver'], isNot(contains('system://')));
      },
    );
  }
}
