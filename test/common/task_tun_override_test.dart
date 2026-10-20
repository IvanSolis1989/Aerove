import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

const _overrides = ProfileOverrides(
  tun: ProfileTun(
    mtu: 1400,
    disableIcmpForwarding: true,
    excludeInterface: ['docker0'],
  ),
  tunOverrideKeys: {
    TunOverrideKey.mtu,
    TunOverrideKey.disableIcmpForwarding,
    TunOverrideKey.excludeInterface,
  },
);

Future<YamlMap> _tunOf({
  required Map<String, dynamic> rawConfig,
  ProfileOverrides overrides = _overrides,
  bool bypassPrivateRoute = false,
  PatchClashConfig patchConfig = const PatchClashConfig(),
  bool safeMode = false,
}) async {
  final result = await makeRealProfileTask(
    MakeRealProfileState(
      profilesPath: '/profiles',
      profileId: 1,
      rawConfig: rawConfig,
      realPatchConfig: patchConfig,
      overrides: overrides,
      bypassPrivateRoute: bypassPrivateRoute,
      appendSystemDns: false,
      proxyGroups: const [],
      rules: const [],
      addedRules: const [],
      defaultUA: 'FlClash-Test',
      safeMode: safeMode,
    ),
  );
  final config = loadYaml(result.yaml) as YamlMap;
  return config['tun'] as YamlMap;
}

void main() {
  test('writes the selected keys beside the network settings', () async {
    final tun = await _tunOf(rawConfig: {});

    expect(tun['disable-icmp-forwarding'], true);
    expect(tun['exclude-interface'], ['docker0']);
    expect(tun['mtu'], 1400);
  });

  test('replaces the same keys of the profile and keeps the rest', () async {
    final tun = await _tunOf(
      rawConfig: {
        'tun': {
          'exclude-interface': ['eth1'],
          'include-uid': [1000],
        },
      },
    );

    expect(tun['exclude-interface'], ['docker0']);
    expect(tun['include-uid'], [1000]);
  });

  test('hijacks every port 53 query over the profile unless a key is '
      'picked', () async {
    const profileHijack = {
      'tun': {
        'dns-hijack': ['198.18.0.2:53'],
      },
    };
    final tun = await _tunOf(
      rawConfig: profileHijack,
      overrides: const ProfileOverrides(),
    );
    final picked = await _tunOf(
      rawConfig: profileHijack,
      overrides: const ProfileOverrides(
        tun: ProfileTun(dnsHijack: ['tcp://any:53']),
        tunOverrideKeys: {TunOverrideKey.dnsHijack},
      ),
    );

    expect(tun['dns-hijack'], defaultDnsHijack);
    expect(picked['dns-hijack'], ['tcp://any:53']);
  });

  test(
    'excludes private ranges after the routes the profile excludes',
    () async {
      final tun = await _tunOf(
        rawConfig: {
          'tun': {
            'route-exclude-address': ['100.64.0.0/10'],
          },
        },
        overrides: const ProfileOverrides(dnsOverrideKeys: {}),
        bypassPrivateRoute: true,
      );

      expect(tun['route-exclude-address'], [
        '100.64.0.0/10',
        ...privateRouteAddress,
      ]);
    },
  );

  test('leaves the profile alone without picked keys', () async {
    final tun = await _tunOf(
      rawConfig: {
        'tun': {
          'mtu': 1500,
          'exclude-interface': ['eth1'],
        },
      },
      overrides: const ProfileOverrides(
        tun: ProfileTun(disableIcmpForwarding: true),
      ),
    );

    expect(tun['mtu'], 1500);
    expect(tun['exclude-interface'], ['eth1']);
    expect(tun.containsKey('disable-icmp-forwarding'), isFalse);
  });

  test('reserves the selected client pool in automatic exclusions', () async {
    const pool = ['fdfe:dcba:9876::1/64'];
    const overrides = ProfileOverrides(
      dns: Dns(ipv6: true, fakeIpRange6: 'fdfe:dcba:9876::1/64'),
      dnsOverrideKeys: {DnsOverrideKey.ipv6, DnsOverrideKey.fakeIpRange6},
    );
    final tun = await _tunOf(
      rawConfig: {
        'dns': {'enable': true, 'ipv6': false, 'enhanced-mode': 'fake-ip'},
        'tun': {'route-exclude-address': ['fdfe:dcba:9876::/80']},
      },
      overrides: overrides,
      bypassPrivateRoute: true,
    );
    final excluded = List<String>.from(tun['route-exclude-address'] as List);

    expect(excluded.first, 'fdfe:dcba:9876::/80');
    expect(subtractCidrs(['fd12::/64'], excluded), isEmpty);
    expect(subtractCidrs(pool, excluded), isNotEmpty);
    expect(subtractCidrs(['fdfe:dcba:9876::/80'], excluded), isEmpty);

    for (final input in [
      (patch: const PatchClashConfig(ipv6: false), safe: false),
      (patch: const PatchClashConfig(), safe: true),
    ]) {
      final disabled = await _tunOf(
        rawConfig: {},
        overrides: overrides,
        bypassPrivateRoute: true,
        patchConfig: input.patch,
        safeMode: input.safe,
      );
      expect(disabled['route-exclude-address'], privateRouteAddress);
    }
  });

  test('pool routes preserve resolvers and explicit includes', () async {
    const nameservers = ['https://dns.alidns.com/dns-query'];
    const fallback = ['https://1.1.1.1/dns-query'];
    final result = await makeRealProfileTask(
      MakeRealProfileState(
        profilesPath: '/profiles',
        profileId: 1,
        rawConfig: {
          'dns': {
            'enable': true,
            'enhanced-mode': 'fake-ip',
            'nameserver': nameservers,
            'fallback': fallback,
          },
          'tun': {'route-address': ['2000::/3']},
        },
        realPatchConfig: const PatchClashConfig(),
        overrides: const ProfileOverrides(
          dns: Dns(ipv6: true, fakeIpRange6: 'fdfe:dcba:9876::1/64'),
          dnsOverrideKeys: {DnsOverrideKey.ipv6, DnsOverrideKey.fakeIpRange6},
        ),
        bypassPrivateRoute: true,
        appendSystemDns: false,
        proxyGroups: const [],
        rules: const [],
        addedRules: const [],
        defaultUA: 'FlClash-Test',
      ),
    );
    final config = loadYaml(result.yaml) as YamlMap;

    expect(config['dns']['nameserver'], nameservers);
    expect(config['dns']['fallback'], fallback);
    expect(config['tun']['route-address'], ['2000::/3']);
    expect(
      subtractCidrs(
        ['fdfe:dcba:9876::/64'],
        List<String>.from(config['tun']['route-exclude-address'] as List),
      ),
      ['fdfe:dcba:9876::/64'],
    );
  });
}
