import 'package:collection/collection.dart';
import 'package:fl_clash/common/subscription_collection.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> ss(String name, String server, {bool? udp}) => {
  'name': name,
  'type': 'ss',
  'server': server,
  'port': 443,
  'cipher': 'aes-128-gcm',
  'password': 'fixture',
  if (udp != null) 'udp': udp,
};

SubscriptionCollectionSource source(
  String id,
  Map<String, dynamic> config, {
  String? label,
}) => SubscriptionCollectionSource(id: id, label: label ?? id, config: config);

void main() {
  test(
    'primary owns the full policy and foreign routing does not replace it',
    () {
      final primary = <String, dynamic>{
        'proxies': [ss('HK', 'primary.invalid')],
        'proxy-groups': [
          {
            'name': 'Choice',
            'type': 'select',
            'proxies': ['HK', 'DIRECT'],
            'custom-option': 7,
          },
        ],
        'rules': ['DOMAIN,primary.invalid,Choice', 'MATCH,DIRECT'],
        'sub-rules': {
          'nested': ['MATCH,Choice'],
        },
        'dns': {
          'enable': true,
          'nameserver': ['https://dns.invalid/dns-query#Choice'],
        },
        'tun': {'stack': 'lwip', 'mtu': 1400},
        'hosts': {
          'node.invalid': ['192.0.2.4'],
        },
        'rule-providers': {
          'rule': {'type': 'http', 'url': 'https://rules.invalid'},
        },
        'future-option': {
          'kept': [true, 4],
        },
      };
      final foreign = <String, dynamic>{
        'proxies': [ss('HK', 'secondary.invalid')],
        'proxy-groups': [
          {
            'name': 'Unused',
            'type': 'select',
            'proxies': ['HK'],
          },
        ],
        'rules': ['MATCH,Unused'],
        'dns': {'enable': false},
        'tun': {'mtu': 9000},
      };
      final primaryCopy = _copy(primary);
      final foreignCopy = _copy(foreign);
      final merged = mergeSubscriptionCollection(
        sources: [source('b', foreign), source('a', primary)],
        primarySourceId: 'a',
      );
      for (final key in [
        'rules',
        'sub-rules',
        'dns',
        'tun',
        'hosts',
        'rule-providers',
        'future-option',
      ]) {
        expect(merged.config[key], primary[key]);
      }
      expect(merged.config['proxy-groups'], primary['proxy-groups']);
      expect((merged.config['proxies'] as List).length, 2);
      expect(merged.sources['a']!.proxies['HK'], 'HK');
      expect(merged.sources['b']!.proxies['HK'], '[b] HK');
      expect(merged.sources['b']!.groups, isEmpty);
      expect(primary, primaryCopy);
      expect(foreign, foreignCopy);
      // The returned tree is independent, not merely an unmodified shallow view.
      ((merged.config['future-option'] as Map)['kept'] as List).add(false);
      expect(primary, primaryCopy);
    },
  );

  test('source ids give stable namespaces independent of labels', () {
    final a = source('a', {
      'proxies': [ss('HK', 'one.invalid')],
    });
    final b = {
      'proxies': [ss('HK', 'two.invalid'), ss('Unique', 'three.invalid')],
    };
    final first = mergeSubscriptionCollection(
      sources: [
        a,
        source('b', b, label: 'First label'),
      ],
      primarySourceId: 'a',
    );
    final renamed = mergeSubscriptionCollection(
      sources: [
        a,
        source('b', b, label: 'Changed label'),
      ],
      primarySourceId: 'a',
    );
    expect(first.config, renamed.config);
    expect(first.renamedProxyCount, 2);
    expect(first.sources['b']!.proxies['Unique'], '[b] Unique');
  });

  test('only complete equal definitions collapse and all origins survive', () {
    final first = ss('Original', 'shared.invalid');
    final identical = {...first, 'name': 'Renamed'};
    final different = {...first, 'name': 'Original', 'udp': false};
    final merged = mergeSubscriptionCollection(
      sources: [
        source('a', {
          'proxies': [first],
        }),
        source('b', {
          'proxies': [identical, different],
        }),
      ],
      primarySourceId: 'a',
    );
    expect((merged.config['proxies'] as List).length, 2);
    expect(merged.deduplicatedProxyCount, 1);
    expect(merged.sources['b']!.proxies['Renamed'], 'Original');
    expect(merged.sources['b']!.proxies['Original'], '[b] Original');
    expect(merged.proxyOrigins['Original']!.map((item) => item.sourceId), [
      'a',
      'b',
    ]);
    expect(merged.proxyOrigins['Original']!.map((item) => item.originalName), [
      'Original',
      'Renamed',
    ]);
  });

  test('strict comparison does not coerce transport values or credentials', () {
    final primary = ss('A', 'shared.invalid');
    final merged = mergeSubscriptionCollection(
      sources: [
        source('a', {
          'proxies': [primary],
        }),
        source('b', {
          'proxies': [
            {...primary, 'name': 'TextPort', 'port': '443'},
            {...primary, 'name': 'OtherSecret', 'password': 'other'},
            {
              ...primary,
              'name': 'OtherTls',
              'tls': true,
              'servername': 'sni.invalid',
            },
          ],
        }),
      ],
      primarySourceId: 'a',
    );
    expect((merged.config['proxies'] as List).length, 4);
    expect(merged.deduplicatedProxyCount, 0);
  });

  test('forward dialer dependencies are canonicalized before comparison', () {
    final hop = ss('Hop', 'hop.invalid');
    final merged = mergeSubscriptionCollection(
      sources: [
        source('a', {
          'proxies': [hop],
        }),
        source('b', {
          'proxies': [
            {...ss('Entry', 'entry.invalid'), 'dialer-proxy': 'Forward'},
            {...hop, 'name': 'Forward'},
            {...ss('Via', 'via.invalid'), 'connect-via': 'Forward'},
          ],
        }),
      ],
      primarySourceId: 'a',
    );
    final nodes = (merged.config['proxies'] as List).cast<Map>();
    expect(merged.sources['b']!.proxies['Forward'], 'Hop');
    expect(
      nodes.singleWhere((item) => item['name'] == '[b] Entry')['dialer-proxy'],
      'Hop',
    );
    expect(
      nodes.singleWhere((item) => item['name'] == '[b] Via')['connect-via'],
      'Hop',
    );
    expect(merged.deduplicatedProxyCount, 1);
  });

  test('dialer groups and provider selectors keep every local reference', () {
    final merged = mergeSubscriptionCollection(
      sources: [
        source('a', {
          'proxies': [ss('Hop', 'primary.invalid')],
        }),
        source('b', {
          'proxies': [
            {...ss('Entry', 'entry.invalid'), 'dialer-proxy': 'Gate'},
            ss('Hop', 'foreign.invalid'),
          ],
          'proxy-groups': [
            {
              'name': 'Gate',
              'type': 'select',
              'proxies': ['Hop'],
              'use': ['Remote'],
              'use-position': {'Remote': 0},
              'default-selected': 'Hop',
              'empty-fallback': 'DIRECT',
              'future-field': true,
            },
            {
              'name': 'Unused',
              'type': 'select',
              'proxies': ['Entry'],
            },
          ],
          'proxy-providers': {
            'Remote': {
              'type': 'http',
              'url': 'https://provider.invalid',
              'proxy': 'Gate',
              'override': {'dialer-proxy': 'Hop'},
            },
          },
        }),
      ],
      primarySourceId: 'a',
    );
    final group = (merged.config['proxy-groups'] as List).single as Map;
    expect(group['name'], '[b] Gate');
    expect(group['proxies'], ['[b] Hop']);
    expect(group['use'], ['[b] Remote']);
    expect(group['use-position'], {'[b] Remote': 0});
    expect(group['default-selected'], '[b] Hop');
    expect(group['empty-fallback'], 'DIRECT');
    expect(group['future-field'], true);
    final provider =
        (merged.config['proxy-providers'] as Map)['[b] Remote'] as Map;
    expect(provider['proxy'], '[b] Gate');
    expect((provider['override'] as Map)['dialer-proxy'], '[b] Hop');
    final entry = (merged.config['proxies'] as List).cast<Map>().singleWhere(
      (item) => item['name'] == '[b] Entry',
    );
    expect(entry['dialer-proxy'], '[b] Gate');
    expect(merged.sources['b']!.groups.keys, ['Gate']);
  });

  test('implicit dependency membership is confined to its original source', () {
    final merged = mergeSubscriptionCollection(
      sources: [
        source('a', {
          'proxies': [ss('Primary', 'primary.invalid')],
        }),
        source('b', {
          'proxies': [ss('Zulu', 'z.invalid'), ss('Alpha', 'a.invalid')],
          'proxy-groups': [
            {
              'name': 'Gate',
              'type': 'select',
              'proxies': ['DIRECT'],
              'include-all': true,
              'use': ['ignored'],
            },
          ],
          'proxy-providers': {
            'Remote': {
              'type': 'http',
              'url': 'https://provider.invalid',
              'proxy': 'Gate',
            },
          },
        }),
      ],
      primarySourceId: 'a',
    );
    final group = (merged.config['proxy-groups'] as List).single as Map;
    expect(group['proxies'], ['DIRECT', '[b] Alpha', '[b] Zulu']);
    expect(group['use'], ['[b] Remote']);
    expect(group.containsKey('include-all'), false);
    expect(group.toString(), isNot(contains('Primary')));
  });

  test(
    'identical http providers reuse one fetch definition but files do not',
    () {
      final provider = {'type': 'http', 'url': 'https://provider.invalid'};
      final merged = mergeSubscriptionCollection(
        sources: [
          source('a', {
            'proxy-providers': {
              'A': provider,
              'Local': {'type': 'file', 'path': 'nodes.yaml'},
            },
          }),
          source('b', {
            'proxy-providers': {
              'B': provider,
              'Local': {'type': 'file', 'path': 'nodes.yaml'},
            },
          }),
        ],
        primarySourceId: 'a',
      );
      expect(merged.sources['b']!.providers['B'], 'A');
      expect(merged.sources['b']!.providers['Local'], '[b] Local');
      expect((merged.config['proxy-providers'] as Map).length, 3);
    },
  );

  test('unsafe dependencies fail without changing any source', () {
    for (final defect in ['missing', 'filtered', 'cycle']) {
      final secondary = <String, dynamic>{
        'proxies': [
          {
            ...ss('Entry', 'entry.invalid'),
            'dialer-proxy': defect == 'missing'
                ? 'Absent'
                : defect == 'cycle'
                ? 'Entry'
                : 'Gate',
          },
        ],
        if (defect == 'filtered')
          'proxy-groups': [
            {
              'name': 'Gate',
              'type': 'select',
              'proxies': ['DIRECT'],
              'filter': '^HK',
            },
          ],
      };
      final snapshot = _copy(secondary);
      expect(
        () => mergeSubscriptionCollection(
          sources: [source('a', {}), source('b', secondary)],
          primarySourceId: 'a',
        ),
        throwsA(isA<SubscriptionCollectionException>()),
        reason: defect,
      );
      expect(secondary, snapshot);
    }
  });

  test('legacy importer retains edited-node identity reuse', () {
    final old = {...ss('HK', 'shared.invalid'), 'udp': true};
    final incoming = {...old, 'udp': false};
    final legacy = importProxyNamespace(
      [incoming],
      groups: const [],
      existing: [old],
      groupNames: const {},
    );
    expect(legacy.added, isEmpty);
    expect(legacy.names['HK'], 'HK');
  });

  test(
    'source identity is explicit and cannot silently select another primary',
    () {
      for (final inputs in [
        <SubscriptionCollectionSource>[],
        [source('a', {}), source('a', {})],
        [source('a/b', {})],
      ]) {
        expect(
          () => mergeSubscriptionCollection(
            sources: inputs,
            primarySourceId: 'a',
          ),
          throwsA(isA<SubscriptionCollectionException>()),
        );
      }
      expect(
        () => mergeSubscriptionCollection(
          sources: [source('b', {})],
          primarySourceId: 'a',
        ),
        throwsA(isA<SubscriptionCollectionException>()),
      );
    },
  );
}

Map<String, dynamic> _copy(Map<String, dynamic> value) {
  Object? copy(Object? item) => switch (item) {
    final Map map => {
      for (final entry in map.entries) entry.key: copy(entry.value),
    },
    final List list => [for (final value in list) copy(value)],
    _ => item,
  };
  final result = Map<String, dynamic>.from(copy(value) as Map);
  expect(const DeepCollectionEquality().equals(result, value), true);
  return result;
}
