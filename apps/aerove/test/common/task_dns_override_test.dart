import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

const _profileDns = <String, dynamic>{
  'enable': true,
  'listen': '0.0.0.0:53',
  'nameserver': ['9.9.9.9'],
  'fallback-filter': {
    'geoip': false,
    'domain': ['keep.me'],
  },
};

const _dns = Dns(
  listen: ':1053',
  ipv6: true,
  nameserver: ['1.1.1.1'],
  fallbackFilter: FallbackFilter(geoip: true),
);

const _keys = {
  DnsOverrideKey.nameserver,
  DnsOverrideKey.fallbackFilterGeoip,
  DnsOverrideKey.ipv6,
};

Future<YamlMap> _dnsOf({
  required Map<String, dynamic> rawConfig,
  Dns dns = _dns,
  Set<DnsOverrideKey> keys = _keys,
  bool keepEmpty = false,
}) async {
  final result = await makeRealProfileTask(
    MakeRealProfileState(
      profilesPath: '/profiles',
      profileId: 1,
      rawConfig: rawConfig,
      realPatchConfig: const PatchClashConfig(),
      overrides: ProfileOverrides(dns: dns, dnsOverrideKeys: keys),
      keepEmptyDnsOverrides: keepEmpty,
      appendSystemDns: false,
      proxyGroups: const [],
      rules: const [],
      addedRules: const [],
      defaultUA: 'FlClash-Test',
    ),
  );
  final config = loadYaml(result.yaml) as YamlMap;
  return config['dns'] as YamlMap;
}

void main() {
  test(
    'legacy missing IPv6 fields produce a complete final DNS override',
    () async {
      final config = PatchClashConfig.fromJson({
        'dns': {'nameserver': xclashDomesticNameservers},
      });
      final dns = await _dnsOf(
        rawConfig: {
          'dns': {
            'enable': true,
            'enhanced-mode': 'fake-ip',
            'ipv6': false,
            'nameserver': xclashDomesticNameservers,
          },
        },
        dns: config.dns,
        keys: config.dnsOverrideKeys.intersection(
          DnsOverrideKey.normalProfileKeys,
        ),
        keepEmpty: true,
      );
      expect(dns['ipv6'], true);
      expect(dns['fake-ip-range6'], xclashDefaultDns.fakeIpRange6);
      expect(dns['nameserver'], xclashDomesticNameservers);
    },
  );

  test(
    'client IPv6 defaults reach final YAML without changing resolvers',
    () async {
      final source = <String, dynamic>{
        'enable': true,
        'ipv6': false,
        'enhanced-mode': 'fake-ip',
        'default-nameserver': xclashBootstrapNameservers,
        'nameserver': xclashDomesticNameservers,
        'fallback': xclashForeignNameservers,
        'proxy-server-nameserver': xclashDefaultDns.proxyServerNameserver,
        'direct-nameserver': xclashDomesticNameservers,
        'nameserver-policy': {
          'geosite:cn': xclashDomesticNameservers,
          'geosite:geolocation-!cn': xclashForeignNameservers,
        },
        'proxy-server-nameserver-policy': {
          'node.example': xclashForeignNameservers,
        },
      };
      final dns = await _dnsOf(
        rawConfig: {'dns': source},
        dns: xclashDefaultDns,
        keys: const PatchClashConfig().dnsOverrideKeys.intersection(
          DnsOverrideKey.normalProfileKeys,
        ),
        keepEmpty: true,
      );
      expect(dns, {
        ...source,
        'ipv6': true,
        'fake-ip-range6': xclashDefaultDns.fakeIpRange6,
      });
    },
  );

  test(
    'new custom and no-DNS profiles produce IPv6-ready fake-ip YAML',
    () async {
      for (final raw in <Map<String, dynamic>>[
        {},
        {
          'dns': {'enable': false, 'ipv6': false},
        },
      ]) {
        final dns = await _dnsOf(
          rawConfig: raw,
          dns: const ProfileOverrides().dns,
          keys: const ProfileOverrides().dnsOverrideKeys,
        );
        expect(dns['enable'], true);
        expect(dns['enhanced-mode'], 'fake-ip');
        expect(dns['ipv6'], true);
        expect(dns['fake-ip-range6'], xclashDefaultDns.fakeIpRange6);
        expect(dns['nameserver'], xclashDomesticNameservers);
        expect(dns['default-nameserver'], xclashBootstrapNameservers);
      }
    },
  );

  test('saved opt-outs remain intact in final profile YAML', () async {
    final source = {
      'enable': true,
      'ipv6': false,
      'fake-ip-range6': 'fd00::/64',
      'nameserver': xclashDomesticNameservers,
    };
    final unselected = await _dnsOf(
      rawConfig: {'dns': source},
      dns: xclashDefaultDns,
      keys: {},
    );
    expect(unselected, source);
    final disabled = await _dnsOf(
      rawConfig: {'dns': source},
      dns: const Dns(ipv6: false, fakeIpRange6: ''),
      keys: xclashDefaultDnsOverrideKeys,
      keepEmpty: true,
    );
    expect(disabled['ipv6'], false);
    expect(disabled['fake-ip-range6'], '');
  });

  test('overrides only the selected DNS keys of an enabled profile', () async {
    final dns = await _dnsOf(rawConfig: {'dns': _profileDns});

    expect(dns['nameserver'], ['1.1.1.1']);
    expect(dns['ipv6'], true);
    expect(dns['listen'], '0.0.0.0:53');
    expect(dns['fallback-filter'], {
      'geoip': true,
      'domain': ['keep.me'],
    });
  });

  test('leaves an enabled profile alone without picked keys', () async {
    final dns = await _dnsOf(rawConfig: {'dns': _profileDns}, keys: {});

    expect(dns['nameserver'], ['9.9.9.9']);
    expect(dns.containsKey('ipv6'), isFalse);
    expect(dns['fallback-filter'], {
      'geoip': false,
      'domain': ['keep.me'],
    });
  });

  test(
    'fills the minimal defaults plus overrides for a profile without DNS',
    () async {
      final dns = await _dnsOf(rawConfig: {});

      expect(dns, {
        'enable': true,
        'enhanced-mode': 'fake-ip',
        'default-nameserver': [
          'https://223.5.5.5/dns-query',
          'https://223.6.6.6/dns-query',
          'https://8.8.8.8/dns-query',
          'https://1.1.1.1/dns-query',
        ],
        'nameserver': ['1.1.1.1'],
        'ipv6': true,
        'fallback-filter': {'geoip': true},
      });
    },
  );

  test('a key picked without a value writes nothing', () async {
    final dns = await _dnsOf(
      rawConfig: {'dns': _profileDns},
      dns: const Dns(nameserver: [], listen: ''),
      keys: {DnsOverrideKey.nameserver, DnsOverrideKey.listen},
    );

    expect(dns['nameserver'], ['9.9.9.9']);
    expect(dns['listen'], '0.0.0.0:53');
  });

  test('an app entry emptied over a subscription replaces its value, save '
      'nameserver', () async {
    final dns = await _dnsOf(
      rawConfig: {'dns': _profileDns},
      dns: const Dns(
        nameserver: [],
        listen: '',
        fallbackFilter: FallbackFilter(domain: []),
      ),
      keys: {
        DnsOverrideKey.nameserver,
        DnsOverrideKey.listen,
        DnsOverrideKey.fallbackFilterDomain,
      },
      keepEmpty: true,
    );

    expect(dns['nameserver'], ['9.9.9.9']);
    expect(dns['listen'], '');
    expect(dns['fallback-filter'], {'geoip': false, 'domain': []});
  });
}
