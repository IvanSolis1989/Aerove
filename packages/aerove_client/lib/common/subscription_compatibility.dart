import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:fl_clash/models/models.dart';
import 'package:yaml/yaml.dart';

Future<SubscriptionCompatibilityProjection> projectSubscriptionYamlTask(
  String text, {
  required String coreId,
}) => compute((payload) {
  final value = jsonDecode(jsonEncode(loadYaml(payload.text)));
  if (value is! Map<String, dynamic>)
    throw const SubscriptionSourceFormatException();
  return projectSubscriptionCompatibility(value, coreId: payload.coreId);
}, (text: text, coreId: coreId));

class SubscriptionSourceFormatException extends FormatException {
  const SubscriptionSourceFormatException()
    : super('profile: expected mapping');
}

const meowUnsupportedOutboundTypes = {
  'tuic',
  'ssh',
  'wireguard',
  'tailscale',
  'shadowquic',
  'mieru',
};

class SubscriptionCompatibilityReport {
  const SubscriptionCompatibilityReport({
    this.removedByType = const {},
    this.removedByReason = const {},
    this.retained = 0,
    this.tunStackProjected = false,
  });

  final Map<String, int> removedByType;
  final Map<String, int> removedByReason;
  final int retained;
  final bool tunStackProjected;
  int get removed => removedByType.values.fold(0, (sum, value) => sum + value);
  bool get changed => removed > 0 || tunStackProjected;
}

typedef SubscriptionCompatibilityProjection = ({
  Map<String, dynamic> config,
  SubscriptionCompatibilityReport report,
});

SubscriptionCompatibilityProjection projectSubscriptionCompatibility(
  Map<String, dynamic> source, {
  required String coreId,
}) {
  if (coreId != 'meow-rs') {
    return (config: source, report: const SubscriptionCompatibilityReport());
  }
  final output = Map<String, dynamic>.from(source);
  final counts = <String, int>{};
  final reasons = <String, int>{};
  final removedNames = <String>{};
  final retainedNodes = <Map<String, dynamic>>[];
  List<dynamic> projectNodes(Object? value, String path) {
    if (value is! List) throw FormatException('$path: expected proxies list');
    final result = <dynamic>[];
    for (final (index, node) in value.indexed) {
      if (node is! Map || node['name'] is! String || node['type'] is! String) {
        throw FormatException('$path[$index]: expected named proxy with type');
      }
      final type = node['type'] as String;
      final legacy = isLegacyVmess(node);
      if (meowUnsupportedOutboundTypes.contains(type) || legacy) {
        if (legacy)
          reasons.update('vmessLegacyAlterId', (n) => n + 1, ifAbsent: () => 1);
        counts.update(type, (count) => count + 1, ifAbsent: () => 1);
        removedNames.add(node['name'] as String);
      } else {
        final retained = Map<String, dynamic>.from(node);
        result.add(retained);
        retainedNodes.add(retained);
      }
    }
    return result;
  }

  if (source.containsKey('proxies')) {
    output['proxies'] = projectNodes(source['proxies'], r'$.proxies');
  }
  final providers = source['proxy-providers'];
  var hasRemoteProviders = false;
  if (providers is Map) {
    output['proxy-providers'] = {
      for (final entry in providers.entries)
        entry.key:
            entry.value is Map && (entry.value as Map).containsKey('payload')
            ? {
                ...entry.value as Map,
                'payload': projectNodes(
                  (entry.value as Map)['payload'],
                  '\$.proxy-providers.${entry.key}.payload',
                ),
              }
            : entry.value,
    };
    hasRemoteProviders = providers.values.any(
      (value) => value is Map && !value.containsKey('payload'),
    );
  }
  removedNames.removeAll(retainedNodes.map((node) => node['name'] as String));
  if (counts.isNotEmpty && retainedNodes.isEmpty && !hasRemoteProviders) {
    throw const FormatException(
      r'$.proxies: no usable nodes remain after compatibility filtering',
    );
  }
  for (final node in retainedNodes) {
    if (removedNames.contains(node['dialer-proxy'])) {
      throw const FormatException(
        r'$.proxies: dialer-proxy refers to a filtered node',
      );
    }
  }
  final groups = source['proxy-groups'];
  if (groups is List && removedNames.isNotEmpty) {
    output['proxy-groups'] = [
      for (final (index, group) in groups.indexed)
        if (group is Map && group['proxies'] is List)
          (() {
            final members = (group['proxies'] as List)
                .where((name) => !removedNames.contains(name))
                .toList();
            if (members.isEmpty &&
                (group['proxies'] as List).isNotEmpty &&
                !(group['use'] is List && (group['use'] as List).isNotEmpty) &&
                group['include-all'] != true &&
                group['include-all-proxies'] != true &&
                group['include-all-providers'] != true) {
              throw FormatException(
                '\$.proxy-groups[$index].proxies: no usable members remain',
              );
            }
            return {...group, 'proxies': members};
          })()
        else
          group,
    ];
  }
  if (source['rules'] case final List rules) {
    for (final (index, raw) in rules.indexed) {
      if (raw is String && removedNames.contains(Rule.parse(raw).ruleTarget)) {
        throw FormatException(
          '\$.rules[$index]: target was filtered; no routing fallback is permitted',
        );
      }
    }
  }
  var stackProjected = false;
  if (source['tun'] case final Map tun when tun.containsKey('stack')) {
    if (tun['stack'] is! String)
      throw const FormatException(r'$.tun.stack: expected string');
    stackProjected = tun['stack'] != 'lwip';
    output['tun'] = {...tun, 'stack': 'lwip'};
  }
  return (
    config: output,
    report: SubscriptionCompatibilityReport(
      removedByType: Map.unmodifiable(counts),
      removedByReason: Map.unmodifiable(reasons),
      retained: retainedNodes.length,
      tunStackProjected: stackProjected,
    ),
  );
}

bool isLegacyVmess(Map node) {
  if (node['type'] != 'vmess') return false;
  if (node.containsKey('alterId') &&
      node.containsKey('alter-id') &&
      (node['alterId'] is! int ||
          node['alter-id'] is! int ||
          node['alterId'] != node['alter-id']))
    return false;
  final value = node.containsKey('alterId')
      ? node['alterId']
      : node['alter-id'];
  return value is int && value > 0;
}
