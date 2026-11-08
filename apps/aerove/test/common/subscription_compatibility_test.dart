import 'dart:convert';
import 'package:fl_clash/models/provider_compatibility.dart';
import 'package:fl_clash/common/subscription_compatibility.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'old provider receipts remain readable and new reasons are explicit',
    () {
      final old = <String, dynamic>{
        'sourceSha256': 'a',
        'derivedSha256': 'b',
        'coreIdentity': 'c',
        'filterIdentity': 'meow-provider-outbounds-v1',
        'retained': 1,
        'removedByType': {'ssh': 1},
      };
      expect(ProviderCompatibility.fromJson(old).removedByReason, isEmpty);
      final report = ProviderCompatibility.fromJson({
        ...old,
        'removedByType': {'vmess': 1},
        'removedByReason': {'vmessLegacyAlterId': 1},
      });
      expect(
        providerCompatibilityDetails(
          report.removedByType,
          report.removedByReason,
        ),
        contains('VMess alterId > 0'),
      );
      expect(
        ProviderCompatibility.fromJson(report.toJson()).removedByReason,
        report.removedByReason,
      );
    },
  );

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
  test('legacy VMess is isolated with an explicit reason, never rewritten', () {
    for (final fields in [
      {'alterId': 16},
      {'alter-id': 16},
      {'alterId': 16, 'alter-id': 16},
    ]) {
      final raw = <String, dynamic>{
        'proxies': [
          {'name': 'old', 'type': 'vmess', ...fields},
          {'name': 'modern', 'type': 'vmess', 'alterId': 0},
        ],
      };
      final before = jsonEncode(raw);
      final result = projectSubscriptionCompatibility(raw, coreId: 'meow-rs');
      expect(result.report.removedByType, {'vmess': 1});
      expect(result.report.removedByReason, {'vmessLegacyAlterId': 1});
      expect((result.config['proxies'] as List).single['name'], 'modern');
      expect(jsonEncode(raw), before);
      expect(
        projectSubscriptionCompatibility(raw, coreId: 'mihomo').config,
        raw,
      );
      for (final extra in [
        <String, dynamic>{
          'rules': ['MATCH,old'],
        },
        <String, dynamic>{
          'proxy-groups': [
            {
              'name': 'choice',
              'type': 'select',
              'proxies': ['old'],
            },
          ],
        },
        <String, dynamic>{
          'proxies': [
            {'name': 'old', 'type': 'vmess', ...fields},
          ],
        },
      ]) {
        expect(
          () => projectSubscriptionCompatibility({
            ...raw,
            ...extra,
          }, coreId: 'meow-rs'),
          throwsFormatException,
        );
      }
    }
    for (final value in [0, -1, '16', true, 1.5, null]) {
      final raw = {
        'proxies': [
          {'name': 'unchanged', 'type': 'vmess', 'alterId': value},
        ],
      };
      expect(
        projectSubscriptionCompatibility(raw, coreId: 'meow-rs').config,
        raw,
      );
    }
    final dangling = {
      'proxies': [
        {'name': 'old', 'type': 'vmess', 'alterId': 16},
        {'name': 'keep', 'type': 'http', 'dialer-proxy': 'old'},
      ],
    };
    expect(
      () => projectSubscriptionCompatibility(dangling, coreId: 'meow-rs'),
      throwsFormatException,
    );
    final mixed = {
      'proxies': [
        {'name': 'mixed', 'type': 'vmess', 'alterId': 1, 'alter-id': 1.0},
      ],
    };
    expect(
      projectSubscriptionCompatibility(mixed, coreId: 'meow-rs').config,
      mixed,
    );
    final conflict = {
      'proxies': [
        {'name': 'conflict', 'type': 'vmess', 'alterId': 16, 'alter-id': 0},
      ],
    };
    expect(
      projectSubscriptionCompatibility(conflict, coreId: 'meow-rs').config,
      conflict,
    );
  });
}
