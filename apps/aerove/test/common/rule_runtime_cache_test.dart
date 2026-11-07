import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:yaml/yaml.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'cold SQL HTTP rules seed a fresh confined Core cache and preserve request semantics',
    () async {
      final home = await Directory.systemTemp.createTemp('rule-cold-cache-');
      const url = 'https://example.com/rules';
      Future<({String path, File source})> compile(
        int sourceId,
        String body,
      ) async {
        final source = File(
          p.join(home.path, 'providers/rules', '$sourceId@$url'.toMd5()),
        );
        await source.parent.create(recursive: true);
        await source.writeAsString(body, flush: true);
        final digest = sha256.convert(await source.readAsBytes()).toString();
        final result = await makeRealProfileTask(
          MakeRealProfileState(
            profilesPath: p.join(home.path, 'profiles'),
            profileId: 17,
            rawConfig: const {},
            realPatchConfig: const PatchClashConfig(),
            appendSystemDns: false,
            proxyGroups: const [],
            rules: const [],
            addedRules: const [],
            defaultUA: 'fixture',
            injectedRuleProviders: const {
              'Library': {
                'type': 'http',
                'url': url,
                'format': 'text',
                'behavior': 'domain',
                'interval': 3600,
                'header': {
                  'Authorization': ['fixture'],
                },
              },
            },
          ),
          providerFileReferences: (
            proxies: const {},
            rules: {'Library': source.path},
          ),
          ruleSourceRevisions: {
            'Library': (providerId: sourceId, sha256: digest),
          },
        );
        final provider =
            (loadYaml(result.yaml) as YamlMap)['rule-providers']['Library'];
        expect(provider['url'], url);
        expect(provider['format'], 'text');
        expect(provider['interval'], 3600);
        expect(provider['header']['Authorization'], ['fixture']);
        final path = provider['path'] as String;
        expect(
          p.relative(path, from: home.path).replaceAll('\\', '/'),
          matches(r'^profiles/providers/17/rules/[0-9a-f]{32}$'),
        );
        expect(await File(path).readAsString(), body);
        return (path: path, source: source);
      }

      try {
        final first = await compile(11, 'first.example\n');
        await File(
          first.path,
        ).writeAsString('core automatic refresh\n', flush: true);
        expect(await first.source.readAsString(), 'first.example\n');
        final revision = await compile(11, 'newest.example\n');
        final other = await compile(12, 'other.example\n');
        expect(revision.path, isNot(first.path));
        expect(other.path, isNot(revision.path));
        expect(
          await File(first.path).readAsString(),
          'core automatic refresh\n',
        );
        expect(await revision.source.readAsString(), 'newest.example\n');
      } finally {
        await home.delete(recursive: true);
      }
    },
  );
  test(
    'same-name third script definition is not identified as the frozen SQL HTTP source',
    () async {
      final home = await Directory.systemTemp.createTemp(
        'rule-third-definition-',
      );
      const url = 'https://example.com/rules';
      final source = File(
        p.join(home.path, 'providers/rules', '11@$url'.toMd5()),
      );
      final cache = File(
        p.join(
          home.path,
          'profiles/providers/17/rules',
          'Library@$url'.toMd5(),
        ),
      );
      try {
        await source.parent.create(recursive: true);
        await source.writeAsString('sql.example\n');
        await cache.parent.create(recursive: true);
        await cache.writeAsString('third script cache\n');
        final expected = {
          'type': 'http',
          'url': url,
          'path': source.path,
          'behavior': 'domain',
          'format': 'text',
          'header': {
            'Authorization': ['sql'],
          },
        };
        final third = {
          ...expected,
          'header': {
            'Authorization': ['script third'],
          },
        };
        final result = await makeRealProfileTask(
          MakeRealProfileState(
            profilesPath: p.join(home.path, 'profiles'),
            profileId: 17,
            rawConfig: {
              'rule-providers': {'Library': expected},
            },
            rawConfigJson: jsonEncode({
              'rule-providers': {'Library': third},
            }),
            realPatchConfig: const PatchClashConfig(),
            appendSystemDns: false,
            proxyGroups: const [],
            rules: const [],
            addedRules: const [],
            defaultUA: 'fixture',
          ),
          ruleSourceDefinitions: {'Library': expected},
          providerFileReferences: (
            proxies: const {},
            rules: {'Library': source.path},
          ),
          ruleSourceRevisions: {
            'Library': (
              providerId: 11,
              sha256: sha256.convert(await source.readAsBytes()).toString(),
            ),
          },
        );
        final provider =
            (loadYaml(result.yaml) as YamlMap)['rule-providers']['Library'];
        expect(provider['header']['Authorization'], ['script third']);
        expect(provider['path'], cache.path);
        expect(await cache.readAsString(), 'third script cache\n');
        expect(await source.readAsString(), 'sql.example\n');
      } finally {
        await home.delete(recursive: true);
      }
    },
  );
}
