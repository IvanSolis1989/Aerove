import 'dart:convert';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

Future<Map> compileGeo(
  Map<String, dynamic> source, {
  PatchClashConfig? patch,
  String core = 'meow-rs',
}) async {
  final actual = patch ?? const PatchClashConfig();
  final result = await makeRealProfileTask(
    MakeRealProfileState(
      profilesPath: 'fixture/profiles',
      profileId: 1,
      rawConfig: source,
      realPatchConfig: core == 'meow-rs'
          ? actual.copyWith.tun(
              stack: TunStack.lwip,
              congestionController: 'builtin',
              device: '',
            )
          : actual,
      appendSystemDns: false,
      proxyGroups: [],
      rules: [],
      addedRules: [],
      defaultUA: 'fixture',
      safeMode: false,
    ),
    coreId: core,
  );
  return jsonDecode(jsonEncode(loadYaml(result.yaml))) as Map;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Meow maps generated shared geo preferences to exact fixed-core shape; Standard unchanged',
    () async {
      final meow = await compileGeo({});
      for (final key in [
        'geo-auto-update',
        'geo-update-interval',
        'geox-url',
      ]) {
        expect(meow.containsKey(key), false);
      }
      expect(meow['geodata']['auto-update'], false);
      expect(meow['geodata']['auto-update-interval'], 24);
      expect((meow['geodata']['url'] as Map).keys.toSet(), {
        'mmdb',
        'asn',
        'geosite',
      });
      final standard = await compileGeo({}, core: 'mihomo');
      expect(standard['geo-auto-update'], false);
      expect(standard['geo-update-interval'], 24);
      expect(standard['geox-url']['geoip'], defaultGeoXUrl[GeoResource.GEOIP]);
      expect(standard.containsKey('geodata'), false);
    },
  );
  test(
    'source geo declarations and unknown nested fields survive without patch default overwrite',
    () async {
      final source = <String, dynamic>{
        'geo-auto-update': true,
        'geo-update-interval': 48,
        'geox-url': {'mmdb': 'https://fixture.invalid/country'},
        'geodata': {
          'auto-update': true,
          'auto-update-interval': 48,
          'url': {
            'mmdb': 'https://fixture.invalid/country',
            'asn': 'https://fixture.invalid/asn',
          },
          'unknown': {'keep': 7},
        },
      };
      final original = jsonEncode(source);
      final output = await compileGeo(source);
      expect(jsonEncode(source), original);
      expect(output['geodata']['auto-update'], true);
      expect(output['geodata']['auto-update-interval'], 48);
      expect(
        output['geodata']['url']['mmdb'],
        'https://fixture.invalid/country',
      );
      expect(output['geodata']['url']['asn'], 'https://fixture.invalid/asn');
      expect(output['geodata']['unknown'], {'keep': 7});
    },
  );
  test(
    'conflicting root/nested/client settings and invalid intervals fail by source path',
    () async {
      for (final source in <Map<String, dynamic>>[
        {
          'geo-auto-update': true,
          'geodata': {'auto-update': false},
        },
        {
          'geo-update-interval': 48,
          'geodata': {'auto-update-interval': 24},
        },
        {
          'geox-url': {'mmdb': 'https://a.invalid'},
          'geodata': {
            'url': {'mmdb': 'https://b.invalid'},
          },
        },
        {'geo-update-interval': 87601},
        {
          'geodata': {'auto-update-interval': 0},
        },
        {
          'geox-url': {'asn': null},
        },
      ]) {
        await expectLater(
          compileGeo(source),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'path',
              contains(r'$.geo'),
            ),
          ),
        );
      }
      final preserved = await compileGeo(
        {
          'geodata': {
            'auto-update': false,
            'url': {'mmdb': 'https://profile.invalid/current'},
          },
        },
        patch: PatchClashConfig(
          geoAutoUpdate: true,
          geoXUrl: {
            ...defaultGeoXUrl,
            GeoResource.MMDB: 'https://global.invalid/default',
          },
        ),
      );
      expect(preserved['geodata']['auto-update'], false);
      expect(
        preserved['geodata']['url']['mmdb'],
        'https://profile.invalid/current',
      );
      final top = await compileGeo({
        'mmdb-download-url': 'https://profile.invalid/explicit',
      });
      expect(top['geodata']['url']['mmdb'], 'https://profile.invalid/explicit');
      await expectLater(
        compileGeo({
          'mmdb-download-url': 'https://a.invalid',
          'geodata': {
            'url': {'mmdb': 'https://b.invalid'},
          },
        }),
        throwsA(isA<FormatException>()),
      );
    },
  );
  test(
    'only generated default GEOIP dat URL is omitted, explicit and custom URL rejected',
    () async {
      await expectLater(
        compileGeo({
          'geox-url': {'geoip': defaultGeoXUrl[GeoResource.GEOIP]},
        }),
        throwsA(
          isA<FormatException>().having(
            (e) => e.message,
            'path',
            contains(r'$.geox-url.geoip'),
          ),
        ),
      );
      await expectLater(
        compileGeo(
          {},
          patch: PatchClashConfig(
            geoXUrl: {
              ...defaultGeoXUrl,
              GeoResource.GEOIP: 'https://fixture.invalid/custom.dat',
            },
          ),
        ),
        throwsA(isA<FormatException>()),
      );
      final output = await compileGeo(
        {},
        patch: const PatchClashConfig(
          geoAutoUpdate: true,
          geoUpdateInterval: 72,
        ),
      );
      expect(output['geodata']['auto-update'], true);
      expect(output['geodata']['auto-update-interval'], 72);
    },
  );
}
