import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart';
import 'package:yaml/yaml.dart';

import '../helpers/app_paths.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'exported files use the display brand while internal identity stays stable',
    () {
      expect(displayAppName, 'AEROVE');
      expect(appName, 'FlClash');
      expect(getBackupFileName(), startsWith('AEROVE_backup_'));
      expect(logFileName, startsWith('AEROVE_'));
    },
  );

  late Directory root;

  setUpAll(() async {
    root = Directory.systemTemp.createTempSync('path_test');
    await useAppPaths(root);
  });

  tearDownAll(() {
    if (root.existsSync()) {
      root.deleteSync(recursive: true);
    }
  });

  test(
    'only trusted Lite Android support root resolves its platform alias',
    () async {
      final real = await Directory('${root.path}/trusted-support').create();
      final link = await Link('${root.path}/support-alias').create(real.path);
      final alias = Directory(link.path);
      try {
        final lite = await AppPath.resolveSupportDirectory(
          alias,
          isAndroid: true,
          productId: 'aerove-lite',
        );
        expect(lite.path, await real.resolveSymbolicLinks());
        expect(
          join(lite.path, 'profiles', '1.yaml'),
          join(await real.resolveSymbolicLinks(), 'profiles', '1.yaml'),
        );
        expect(
          (await AppPath.resolveSupportDirectory(
            alias,
            isAndroid: true,
            productId: 'aerove-standard',
          )).path,
          alias.path,
        );
        expect(
          (await AppPath.resolveSupportDirectory(
            alias,
            isAndroid: false,
            productId: 'aerove-lite',
          )).path,
          alias.path,
        );
      } finally {
        await link.delete();
        await real.delete();
      }
    },
  );
  test('provider directories match the paths handed to the core', () async {
    const proxiesUrl = 'https://example.com/a.yaml';
    const rulesUrl = 'https://example.com/b.yaml';
    final proxiesDir = await appPath.getProviderDirPath(
      7,
      proxiesProviderDirectoryName,
    );
    final rulesDir = await appPath.getProviderDirPath(
      7,
      rulesProviderDirectoryName,
    );

    final result = await makeRealProfileTask(
      MakeRealProfileState(
        profilesPath: await appPath.profilesPath,
        profileId: 7,
        rawConfig: {
          'proxy-providers': {
            'a': {'type': 'http', 'url': proxiesUrl},
          },
          'rule-providers': {
            'b': {'type': 'http', 'url': rulesUrl},
          },
        },
        realPatchConfig: const PatchClashConfig(),
        appendSystemDns: false,
        proxyGroups: const [],
        rules: const [],
        addedRules: const [],
        defaultUA: 'FlClash',
      ),
    );
    final config = loadYaml(result.yaml) as YamlMap;

    expect(
      config['proxy-providers']['a']['path'],
      join(proxiesDir, 'a@$proxiesUrl'.toMd5()),
    );
    expect(
      config['rule-providers']['b']['path'],
      join(rulesDir, 'b@$rulesUrl'.toMd5()),
    );
  });

  test('confines a provider path the profile tried to choose', () async {
    final proxiesDir = await appPath.getProviderDirPath(
      7,
      proxiesProviderDirectoryName,
    );
    final rulesDir = await appPath.getProviderDirPath(
      7,
      rulesProviderDirectoryName,
    );

    final result = await makeRealProfileTask(
      MakeRealProfileState(
        profilesPath: await appPath.profilesPath,
        profileId: 7,
        rawConfig: {
          'proxy-providers': {
            'escape': {'type': 'file', 'path': '../../../../config.yaml'},
            'urlless': {'type': 'http', 'path': 'cache.db'},
            'literal': {
              'type': 'inline',
              'payload': ['DIRECT'],
            },
          },
          'rule-providers': {
            'sneak': {'type': 'file', 'path': '/etc/hosts'},
          },
        },
        realPatchConfig: const PatchClashConfig(),
        appendSystemDns: false,
        proxyGroups: const [],
        rules: const [],
        addedRules: const [],
        defaultUA: 'FlClash',
      ),
    );
    final config = loadYaml(result.yaml) as YamlMap;

    expect(
      config['proxy-providers']['escape']['path'],
      join(proxiesDir, 'proxy-providers/escape'.toMd5()),
    );
    expect(
      config['proxy-providers']['urlless']['path'],
      join(proxiesDir, 'proxy-providers/urlless'.toMd5()),
    );
    expect(config['proxy-providers']['literal']['path'], isNull);
    expect(
      config['rule-providers']['sneak']['path'],
      join(rulesDir, 'rule-providers/sneak'.toMd5()),
    );
  });

  test('survives a provider section that is not a map', () async {
    final result = await makeRealProfileTask(
      MakeRealProfileState(
        profilesPath: await appPath.profilesPath,
        profileId: 7,
        rawConfig: {
          'proxy-providers': ['not-a-map'],
          'rule-providers': {
            'broken': ['also-not-a-map'],
          },
        },
        realPatchConfig: const PatchClashConfig(),
        appendSystemDns: false,
        proxyGroups: const [],
        rules: const [],
        addedRules: const [],
        defaultUA: 'FlClash',
      ),
    );

    expect(result.yaml, isNotEmpty);
  });

  test(
    'no persisted provider edits keep malformed sections tolerable',
    () async {
      const overrides = ProfileOverrides(
        proxyProviders: {
          'unmodified': ProxyProviderOptions(
            sourceDefinition: {'type': 'file'},
            editedFields: {},
          ),
        },
      );
      const source = '{"proxy-providers":["not-a-map"]}';
      expect(nativeProxyProviderScriptInput(source, overrides), source);
      final result = await makeRealProfileTask(
        MakeRealProfileState(
          profilesPath: await appPath.profilesPath,
          profileId: 7,
          rawConfig: jsonDecode(source) as Map<String, dynamic>,
          overrides: overrides,
          realPatchConfig: const PatchClashConfig(),
          appendSystemDns: false,
          proxyGroups: const [],
          rules: const [],
          addedRules: const [],
          defaultUA: 'FlClash',
        ),
      );
      expect(result.yaml, isNotEmpty);
    },
  );

  test('persisted provider edits still reject a malformed section', () async {
    const overrides = ProfileOverrides(
      proxyProviders: {
        'edited': ProxyProviderOptions(
          sourceDefinition: {'type': 'http'},
          editedFields: {'filter'},
          filter: 'HK',
        ),
      },
    );
    expect(
      () => nativeProxyProviderScriptInput(
        '{"proxy-providers":["not-a-map"]}',
        overrides,
      ),
      throwsFormatException,
    );
    await expectLater(
      makeRealProfileTask(
        MakeRealProfileState(
          profilesPath: await appPath.profilesPath,
          profileId: 7,
          rawConfig: {
            'proxy-providers': ['not-a-map'],
          },
          overrides: overrides,
          realPatchConfig: const PatchClashConfig(),
          appendSystemDns: false,
          proxyGroups: const [],
          rules: const [],
          addedRules: const [],
          defaultUA: 'FlClash',
        ),
      ),
      throwsFormatException,
    );
  });

  test(
    'unchanged injected file references stay readable across script output',
    () async {
      final profilePath = await appPath.getProfilePath('42');
      final rulePath = await appPath.getProviderCachePath('owned-rules.yaml');
      File(profilePath)
        ..createSync(recursive: true)
        ..writeAsStringSync('proxies: []\n');
      File(rulePath)
        ..createSync(recursive: true)
        ..writeAsStringSync('payload: [example.invalid]\n');
      for (final scripted in [false, true]) {
        final proxies = <String, dynamic>{
          'sub': {'type': 'file', 'path': profilePath},
        };
        final rules = <String, dynamic>{
          'owned': {'type': 'file', 'path': rulePath},
        };
        final raw = <String, dynamic>{
          'proxy-providers': proxies,
          'rule-providers': rules,
        };
        final result = await makeRealProfileTask(
          MakeRealProfileState(
            profilesPath: await appPath.profilesPath,
            profileId: 7,
            rawConfig: raw,
            rawConfigJson: scripted ? jsonEncode(raw) : null,
            injectedProxyProviders: scripted ? const {} : proxies,
            injectedRuleProviders: scripted ? const {} : rules,
            realPatchConfig: const PatchClashConfig(),
            appendSystemDns: false,
            proxyGroups: const [],
            rules: const [],
            addedRules: const [],
            defaultUA: 'FlClash',
          ),
          providerFileReferences: (
            proxies: {'sub': profilePath},
            rules: {'owned': rulePath},
          ),
        );
        final config = loadYaml(result.yaml) as YamlMap;
        final proxy = config['proxy-providers']['sub'];
        final rule = config['rule-providers']['owned'];
        expect(proxy['path'], profilePath);
        expect(rule['path'], rulePath);
        expect(
          File(proxy['path'] as String).readAsStringSync(),
          'proxies: []\n',
        );
        expect(
          File(rule['path'] as String).readAsStringSync(),
          'payload: [example.invalid]\n',
        );
      }
    },
  );

  test(
    'script path type and name changes cannot inherit injected file trust',
    () async {
      final profilePath = await appPath.getProfilePath('42');
      final rulePath = await appPath.getProviderCachePath('owned-rules.yaml');
      for (final (name, type, escapes, changedSource) in [
        ('sub', 'file', true, false),
        ('sub', 'http', false, false),
        ('renamed', 'file', false, false),
        ('sub', 'file', false, true),
      ]) {
        final result = await makeRealProfileTask(
          MakeRealProfileState(
            profilesPath: await appPath.profilesPath,
            profileId: 7,
            rawConfig: {
              'proxy-providers': {
                'sub': {
                  'type': 'file',
                  'path': changedSource
                      ? '../../../../config.yaml'
                      : profilePath,
                },
              },
              'rule-providers': {
                'sub': {
                  'type': 'file',
                  'path': changedSource ? '../../../../config.yaml' : rulePath,
                },
              },
            },
            rawConfigJson: jsonEncode({
              'proxy-providers': {
                name: {
                  'type': type,
                  'path': escapes ? '../../../../config.yaml' : profilePath,
                },
              },
              'rule-providers': {
                name: {
                  'type': type,
                  'path': escapes ? '../../../../config.yaml' : rulePath,
                },
              },
            }),
            realPatchConfig: const PatchClashConfig(),
            appendSystemDns: false,
            proxyGroups: const [],
            rules: const [],
            addedRules: const [],
            defaultUA: 'FlClash',
          ),
          providerFileReferences: (
            proxies: {'sub': profilePath},
            rules: {'sub': rulePath},
          ),
        );
        final config = loadYaml(result.yaml) as YamlMap;
        for (final (section, directory) in [
          ('proxy-providers', proxiesProviderDirectoryName),
          ('rule-providers', rulesProviderDirectoryName),
        ]) {
          expect(
            config[section][name]['path'],
            join(
              await appPath.getProviderDirPath(7, directory),
              '$section/$name'.toMd5(),
            ),
          );
        }
      }
    },
  );

  test(
    'a script can remove an injected file without it being restored',
    () async {
      final profilePath = await appPath.getProfilePath('42');
      final result = await makeRealProfileTask(
        MakeRealProfileState(
          profilesPath: await appPath.profilesPath,
          profileId: 7,
          rawConfig: {
            'proxy-providers': {
              'sub': {'type': 'file', 'path': profilePath},
            },
          },
          rawConfigJson: '{"proxy-providers":{}}',
          realPatchConfig: const PatchClashConfig(),
          appendSystemDns: false,
          proxyGroups: const [],
          rules: const [],
          addedRules: const [],
          defaultUA: 'FlClash',
        ),
        providerFileReferences: (proxies: {'sub': profilePath}, rules: {}),
      );
      expect((loadYaml(result.yaml) as YamlMap)['proxy-providers'], isEmpty);
    },
  );

  test('ensureProviderDirs creates both provider directories', () async {
    await appPath.ensureProviderDirs(9);

    for (final type in const [
      proxiesProviderDirectoryName,
      rulesProviderDirectoryName,
    ]) {
      expect(
        Directory(await appPath.getProviderDirPath(9, type)).existsSync(),
        isTrue,
      );
    }

    await expectLater(appPath.ensureProviderDirs(9), completes);
  });
}
