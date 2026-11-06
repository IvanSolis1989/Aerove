import 'dart:convert';
import 'package:fl_clash/common/subscription_compatibility.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'include-all-proxies keeps an explicitly emptied group for core expansion',
    () {
      final raw = <String, dynamic>{
        'proxies': [
          {'name': 'ssB', 'type': 'ss'},
          {'name': 'sshA', 'type': 'ssh'},
        ],
        'proxy-groups': [
          {
            'name': 'choice',
            'type': 'select',
            'proxies': ['sshA'],
            'include-all-proxies': true,
          },
        ],
        'rules': ['MATCH,choice'],
      };
      final result = projectSubscriptionCompatibility(raw, coreId: 'meow-rs');
      expect(result.config['proxy-groups'][0]['proxies'], isEmpty);
      expect(result.config['proxy-groups'][0]['include-all-proxies'], true);
      expect((result.config['proxies'] as List).single['name'], 'ssB');
      expect(result.report.removedByType, {'ssh': 1});
    },
  );
  Map<String, dynamic> source() => {
    'proxies': [
      {'name': 'supported', 'type': 'vless'},
      {'name': 'unsupported', 'type': 'mieru'},
    ],
    'proxy-groups': [
      {
        'name': 'choice',
        'type': 'select',
        'proxies': ['unsupported', 'supported'],
      },
    ],
    'rules': ['MATCH,choice'],
    'tun': {
      'stack': 'system',
      'mtu': 1400,
      'route-address': ['10.0.0.0/8'],
    },
    'dns': {'ipv6': false},
  };
  test(
    'Meow projects proven unsupported nodes and references without editing source',
    () {
      final raw = source();
      final original = jsonEncode(raw);
      final result = projectSubscriptionCompatibility(raw, coreId: 'meow-rs');
      expect(result.report.removedByType, {'mieru': 1});
      expect(result.report.retained, 1);
      expect(result.report.tunStackProjected, true);
      expect((result.config['proxy-groups'] as List).single['proxies'], [
        'supported',
      ]);
      expect(result.config['tun'], {
        'stack': 'lwip',
        'mtu': 1400,
        'route-address': ['10.0.0.0/8'],
      });
      expect(result.config['dns'], {'ipv6': false});
      expect(jsonEncode(raw), original);
      expect(
        projectSubscriptionCompatibility(raw, coreId: 'mihomo').config,
        raw,
      );
      final second = projectSubscriptionCompatibility(
        result.config,
        coreId: 'meow-rs',
      );
      expect(second.report.changed, false);
    },
  );
  test(
    'unknown and feature-gated protocols stay for strict native validation',
    () {
      final raw = {
        'proxies': [
          {'name': 'a', 'type': 'invented'},
          {'name': 'b', 'type': 'trusttunnel'},
        ],
      };
      final result = projectSubscriptionCompatibility(raw, coreId: 'meow-rs');
      expect(result.config['proxies'], raw['proxies']);
      expect(result.report.removed, 0);
    },
  );
  test('inline provider payload follows the same type projection', () {
    final raw = source()
      ..['proxy-providers'] = {
        'p': {
          'type': 'inline',
          'payload': [
            {'name': 'inline-drop', 'type': 'tuic'},
            {'name': 'inline-keep', 'type': 'ss'},
          ],
        },
      };
    final result = projectSubscriptionCompatibility(raw, coreId: 'meow-rs');
    expect(result.report.removedByType, {'mieru': 1, 'tuic': 1});
    expect(
      (result.config['proxy-providers']['p']['payload'] as List).single['name'],
      'inline-keep',
    );
  });
  test(
    'empty result, empty referenced groups, and dangling dialers or rules fail closed',
    () {
      final cases = [
        source()
          ..['proxies'] = [
            {'name': 'unsupported', 'type': 'mieru'},
          ],
        source()
          ..['proxy-groups'] = [
            {
              'name': 'choice',
              'type': 'select',
              'proxies': ['unsupported'],
            },
          ],
        source()..['proxies'][0]['dialer-proxy'] = 'unsupported',
        source()..['rules'] = ['MATCH,unsupported'],
      ];
      for (final raw in cases) {
        final before = jsonEncode(raw);
        expect(
          () => projectSubscriptionCompatibility(raw, coreId: 'meow-rs'),
          throwsFormatException,
        );
        expect(jsonEncode(raw), before);
      }
    },
  );
}
