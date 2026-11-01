import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

Future<Map<String, dynamic>> compileOrigin(
  Map<String, dynamic> source, {
  PatchClashConfig patch = const PatchClashConfig(),
  ProfileOverrides overrides = const ProfileOverrides(),
  String core = 'meow-rs',
  String? scriptOutput,
}) async {
  final result = await makeRealProfileTask(
    MakeRealProfileState(
      profilesPath: 'fixture/profiles',
      profileId: 7,
      rawConfig: source,
      rawConfigJson: scriptOutput,
      realPatchConfig: core == 'meow-rs'
          ? patch.copyWith.tun(
              stack: TunStack.lwip,
              congestionController: 'builtin',
              device: '',
            )
          : patch,
      overrides: overrides,
      appendSystemDns: false,
      proxyGroups: [],
      rules: [],
      addedRules: [],
      defaultUA: 'fixture',
      safeMode: false,
    ),
    coreId: core,
  );
  return {
    'yaml': result.yaml,
    'config': jsonDecode(jsonEncode(loadYaml(result.yaml))),
  };
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'typed global producer shares the exact default GEOIP rule without accepting source declarations',
    () async {
      final global = const PatchClashConfig();
      expect(meowGlobalGeoDownloadUrls(global).keys.toSet(), {
        'mmdb',
        'asn',
        'geosite',
      });
      expect(global.geoXUrl.containsKey(GeoResource.GEOIP), true);
      final changed = global.copyWith(
        geoXUrl: {
          ...defaultGeoXUrl,
          GeoResource.GEOIP: 'https://fixture.invalid/custom.dat',
        },
      );
      expect(
        () => meowGlobalGeoDownloadUrls(changed),
        throwsA(isA<FormatException>()),
      );
      await expectLater(
        compileOrigin({
          'geox-url': {'geoip': defaultGeoXUrl[GeoResource.GEOIP]},
        }),
        throwsA(isA<FormatException>()),
      );
      await expectLater(
        compileOrigin({}, patch: changed),
        throwsA(isA<FormatException>()),
      );
    },
  );
  test(
    'only actual explicit source leaves are marked; Standard adds no metadata',
    () async {
      final output = (await compileOrigin({}))['config'] as Map;
      expect(output['aerove-geo-origin'], {'schema': 1, 'source': {}});
      final standard =
          (await compileOrigin({}, core: 'mihomo'))['config'] as Map;
      expect(standard.containsKey('aerove-geo-origin'), false);
      expect(standard['geox-url']['geoip'], defaultGeoXUrl[GeoResource.GEOIP]);
    },
  );
  test(
    'explicit equality is retained through later global changes; sparse fallback remains live',
    () async {
      final source = <String, dynamic>{
        'geo-auto-update': false,
        'geodata': {
          'auto-update': false,
          'url': {'mmdb': 'https://profile.invalid/mmdb'},
        },
      };
      final original = jsonEncode(source);
      final first = await compileOrigin(source);
      final changedPatch = PatchClashConfig(
        geoAutoUpdate: true,
        geoUpdateInterval: 72,
        geoXUrl: {
          ...defaultGeoXUrl,
          GeoResource.MMDB: 'https://global.invalid/mmdb',
          GeoResource.ASN: 'https://global.invalid/asn',
        },
      );
      final second = await compileOrigin(source, patch: changedPatch);
      final a = first['config'] as Map;
      final b = second['config'] as Map;
      final expected = {
        'schema': 1,
        'source': {
          'auto-update': false,
          'url': {'mmdb': 'https://profile.invalid/mmdb'},
        },
      };
      expect(a['aerove-geo-origin'], expected);
      expect(b['aerove-geo-origin'], expected);
      expect(b['geodata']['auto-update'], false);
      expect(b['geodata']['auto-update-interval'], 72);
      expect(b['geodata']['url']['mmdb'], 'https://profile.invalid/mmdb');
      expect(b['geodata']['url']['asn'], 'https://global.invalid/asn');
      expect(jsonEncode(source), original);
      final destination = const String.fromEnvironment('GEO_ORIGIN_FIXTURE');
      if (destination.isNotEmpty)
        await File(destination).writeAsString(
          jsonEncode({
            'schema': 1,
            'initialYaml': first['yaml'],
            'changedGlobal': {
              'autoUpdate': true,
              'intervalHours': 72,
              'urls': {
                'mmdb': 'https://global.invalid/mmdb',
                'asn': 'https://global.invalid/asn',
                'geosite': defaultGeoXUrl[GeoResource.GEOSITE],
              },
            },
            'expectedAfterGlobal': b['geodata'],
            'rerunYaml': second['yaml'],
            'expectedOrigin': expected,
          }),
        );
    },
  );
  test(
    'Native cache output hints never become source provenance or override profile URL',
    () async {
      final oldUrl = defaultGeoXUrl[GeoResource.MMDB]!;
      final cache = ProfileOverrides(
        nativeResourceInputs: {
          r'$.mmdb': {
            'file': 'profiles/providers/7/rules/${'a' * 32}',
            'nativeRecord': {'kind': 'mmdb', 'url': oldUrl},
          },
        },
      );
      final first =
          (await compileOrigin({}, overrides: cache))['config'] as Map;
      expect(first['aerove-geo-origin'], {'schema': 1, 'source': {}});
      expect(first['mmdb-download-url'], oldUrl);
      final patch = PatchClashConfig(
        geoXUrl: {
          ...defaultGeoXUrl,
          GeoResource.MMDB: 'https://global.invalid/new',
        },
      );
      final second =
          (await compileOrigin({}, overrides: cache, patch: patch))['config']
              as Map;
      expect(second['aerove-geo-origin'], {'schema': 1, 'source': {}});
      expect(second['geodata']['url']['mmdb'], 'https://global.invalid/new');
      expect(second.containsKey('mmdb'), false);
      final profile =
          (await compileOrigin(
                {
                  'geodata': {
                    'url': {'mmdb': oldUrl},
                  },
                },
                overrides: cache,
                patch: patch,
              ))['config']
              as Map;
      expect(profile['aerove-geo-origin']['source'], {
        'url': {'mmdb': oldUrl},
      });
      expect(profile['mmdb-download-url'], oldUrl);
      expect(profile['mmdb'], contains('profiles/providers/7/rules/'));
    },
  );
  test(
    'root/nested/top URL declarations map to one sparse origin and conflict explicitly',
    () async {
      final result =
          (await compileOrigin({
                'geo-update-interval': 48,
                'geodata': {'auto-update-interval': 48},
                'asn-mmdb-download-url': 'https://profile.invalid/asn',
              }))['config']
              as Map;
      expect(result['aerove-geo-origin'], {
        'schema': 1,
        'source': {
          'auto-update-interval': 48,
          'url': {'asn': 'https://profile.invalid/asn'},
        },
      });
      for (final source in <Map<String, dynamic>>[
        {
          'geo-auto-update': true,
          'geodata': {'auto-update': false},
        },
        {
          'mmdb-download-url': 'https://a.invalid',
          'geodata': {
            'url': {'mmdb': 'https://b.invalid'},
          },
        },
      ]) {
        await expectLater(
          compileOrigin(source),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'path',
              contains(r'$.geo'),
            ),
          ),
        );
      }
    },
  );
  test(
    'reserved metadata from raw profile or existing JS result is rejected before trusted production',
    () async {
      final forged = {
        'schema': 1,
        'source': {'auto-update': true},
      };
      await expectLater(
        compileOrigin({'aerove-geo-origin': forged}),
        throwsA(
          isA<FormatException>().having(
            (e) => e.message,
            'reserved path',
            contains(r'$.aerove-geo-origin'),
          ),
        ),
      );
      await expectLater(
        compileOrigin(
          {},
          scriptOutput: jsonEncode({'aerove-geo-origin': forged}),
        ),
        throwsA(isA<FormatException>()),
      );
      final script =
          (await compileOrigin(
                {},
                scriptOutput: jsonEncode({
                  'geodata': {'auto-update': true},
                }),
              ))['config']
              as Map;
      expect(script['aerove-geo-origin']['source'], {'auto-update': true});
    },
  );
}
