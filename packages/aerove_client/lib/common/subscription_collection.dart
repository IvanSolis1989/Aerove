import 'dart:collection';

import 'package:collection/collection.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/clash_config.dart';

/// Persist with the source-id list and primary source, not with node counts.
const subscriptionCollectionPolicyVersion = 1;

typedef ProxyNamespaceImport = ({
  Map<String, String> names,
  List<String> importedGroupNames,
  List<Map<String, dynamic>> added,
  Map<String, String> dialers,
});

/// Shared by custom import and collections, so collision suffixes stay stable.
String claimProxyName(String base, Set<String> taken) {
  var name = base;
  for (var index = 1; !taken.add(name); index++) {
    final suffix = index.toString().padLeft(2, '0');
    name = '$base-$suffix';
  }
  return name;
}

Map<String, dynamic> _withoutName(Map<String, dynamic> definition) =>
    {...definition}..remove('name');

Map<String, dynamic> _withoutDialer(Map<String, dynamic> definition) =>
    {...definition}..remove('dialer-proxy');

Object? _weaklyTyped(Object? value) => switch (value) {
  final Map map => {
    for (final MapEntry(:key, :value) in map.entries)
      key.toString(): _weaklyTyped(value),
  },
  final List list => [for (final item in list) _weaklyTyped(item)],
  final num number => number.toString(),
  _ => value,
};

Object? _exactValue(Object? value) => switch (value) {
  final Map map => {
    for (final MapEntry(:key, :value) in map.entries) key: _exactValue(value),
  },
  final List list => [for (final item in list) _exactValue(item)],
  // Parser input types are part of the definition; "443" and 443 are not a
  // safe reason to drop one source's node as a duplicate.
  final num number => (number is int ? 'integer' : 'decimal', number),
  _ => value,
};

Object? _definitionKey(Map<String, dynamic> definition, bool exact) => exact
    ? _exactValue(_withoutName(definition))
    : _weaklyTyped(_withoutName(_withoutDialer(definition)));

List<Object?>? _identityOf(Map<String, dynamic> definition) {
  final proxy = CustomProxy(id: 0, definition: definition);
  final server = proxy.server;
  if (server == null || server.isEmpty || proxy.credentials.isEmpty)
    return null;
  return [
    proxy.name,
    proxy.type,
    server,
    proxy.port,
    for (final credential in proxy.credentials)
      proxy.valueOf(credential)?.toString(),
  ];
}

/// Custom import retains its historical identity reuse. Collections instead
/// compare complete definitions, including dialers and transport options.
ProxyNamespaceImport importProxyNamespace(
  List<Map<String, dynamic>> proxies, {
  required List<Map<String, Object?>> groups,
  required List<Map<String, dynamic>> existing,
  required Set<String> groupNames,
  bool exactDefinitions = false,
  String namePrefix = '',
}) {
  const equality = DeepCollectionEquality();
  final held = LinkedHashMap<Object?, String>(
    equals: equality.equals,
    hashCode: equality.hash,
  );
  final identities = HashSet<List<Object?>>(
    equals: equality.equals,
    hashCode: equality.hash,
  );
  for (final definition in existing) {
    final name = _name(definition);
    if (name.isEmpty) continue;
    held.putIfAbsent(_definitionKey(definition, exactDefinitions), () => name);
    if (!exactDefinitions) {
      if (_identityOf(definition) case final identity?) {
        identities.add(identity);
      }
    }
  }
  final existingNames = {for (final item in existing) _name(item)};
  final kept = {
    for (final proxy in proxies)
      if (!existingNames.contains(_name(proxy)) &&
          !groupNames.contains(_name(proxy)))
        _name(proxy),
    for (final group in groups)
      if (!existingNames.contains(_name(group))) _name(group),
  };
  final taken = {
    ...RuleTarget.ruleTargets,
    ...existingNames,
    ...groupNames,
    ...kept,
  };
  final used = <String>{};
  String freeName(String name) => namePrefix.isNotEmpty
      ? claimProxyName(namePrefix + name, taken)
      : kept.contains(name) && used.add(name)
      ? name
      : claimProxyName(name, taken);
  final names = <String, String>{};
  final groupResults = <String>[];

  // Allocate the collection namespace before comparison. A foreign dialer
  // named HK must never accidentally bind to the primary source's HK.
  if (exactDefinitions) {
    for (final proxy in proxies) {
      names[_name(proxy)] = freeName(_name(proxy));
    }
    for (final group in groups) {
      final assigned = freeName(_name(group));
      names[_name(group)] = assigned;
      groupResults.add(assigned);
    }
  }
  final added = <Map<String, dynamic>>[];
  final proxyResults = <String, String>{};
  final proxyNamesByIndex = List<String?>.filled(proxies.length, null);
  final order = exactDefinitions
      ? _proxyDependencyOrder(proxies)
      : List<int>.generate(proxies.length, (index) => index);
  for (final index in order) {
    final proxy = proxies[index];
    final name = _name(proxy);
    final definition = exactDefinitions
        ? _renameNode(_copyMap(proxy), names)
        : _withoutDialer(proxy);
    final key = _definitionKey(definition, exactDefinitions);
    final String imported;
    if (held[key] case final same?) {
      imported = same;
    } else if (!exactDefinitions &&
        identities.contains(_identityOf(definition))) {
      imported = name;
    } else {
      imported = exactDefinitions
          ? names[name]!
          : RuleTarget.ruleTargets.contains(name)
          ? claimProxyName(name, taken)
          : freeName(name);
      final item = {...definition, 'name': imported};
      added.add(item);
      // The old importer intentionally resolves against the existing library.
      // Collections also collapse identical nodes within one source.
      if (exactDefinitions) held[key] = imported;
    }
    if (exactDefinitions) {
      names[name] = imported;
    } else {
      names.putIfAbsent(name, () => imported);
    }
    proxyResults[name] = imported;
    proxyNamesByIndex[index] = imported;
  }
  if (!exactDefinitions) {
    for (final group in groups) {
      final imported = freeName(_name(group));
      names.putIfAbsent(_name(group), () => imported);
      groupResults.add(imported);
    }
  }
  // Preserve source order even though dialer dependencies were resolved first.
  if (exactDefinitions) {
    final byName = {for (final item in added) _name(item): item};
    final emitted = <String>{};
    added
      ..clear()
      ..addAll([
        for (final proxy in proxies)
          if (proxyResults[_name(proxy)] case final assigned?
              when byName.containsKey(assigned) && emitted.add(assigned))
            byName[assigned]!,
      ]);
  }
  final dialers = {
    for (final (index, proxy) in proxies.indexed)
      if (proxy['dialer-proxy']?.toString() case final dialer?
          when dialer.isNotEmpty)
        proxyNamesByIndex[index]!: names[dialer] ?? dialer,
  };
  return (
    names: names,
    importedGroupNames: groupResults,
    added: added,
    dialers: dialers,
  );
}

List<int> _proxyDependencyOrder(List<Map<String, dynamic>> proxies) {
  final indexes = {
    for (final (index, proxy) in proxies.indexed) _name(proxy): index,
  };
  final active = <int>{};
  final done = <int>{};
  final order = <int>[];
  void visit(int index) {
    if (done.contains(index)) return;
    if (!active.add(index)) {
      throw SubscriptionCollectionException('cyclic_proxy_dependency');
    }
    for (final field in const ['dialer-proxy', 'connect-via']) {
      final target = proxies[index][field];
      if (target is String) {
        if (indexes[target] case final dependency?) {
          visit(dependency);
        }
      }
    }
    active.remove(index);
    done.add(index);
    order.add(index);
  }

  for (var index = 0; index < proxies.length; index++) {
    visit(index);
  }
  return order;
}

final class SubscriptionCollectionSource {
  const SubscriptionCollectionSource({
    required this.id,
    required this.label,
    required this.config,
  });

  final String id;
  final String label;
  final Map<String, dynamic> config;
}

final class SubscriptionCollectionOrigin {
  const SubscriptionCollectionOrigin(this.sourceId, this.originalName);
  final String sourceId;
  final String originalName;
}

final class SubscriptionCollectionSourceMap {
  SubscriptionCollectionSourceMap({
    required Map<String, String> proxies,
    required Map<String, String> groups,
    required Map<String, String> providers,
  }) : proxies = Map.unmodifiable(proxies),
       groups = Map.unmodifiable(groups),
       providers = Map.unmodifiable(providers);

  final Map<String, String> proxies;
  final Map<String, String> groups;
  final Map<String, String> providers;
}

/// This is a detached config snapshot. Provider source maps remain available
/// for the caller's existing file confinement/materialization stage.
final class SubscriptionCollectionResult {
  const SubscriptionCollectionResult({
    required this.config,
    required this.sources,
    required this.proxyOrigins,
    required this.deduplicatedProxyCount,
    required this.renamedProxyCount,
  });

  final Map<String, dynamic> config;
  final Map<String, SubscriptionCollectionSourceMap> sources;

  /// Configuration contributors are not proof of separate billing accounts.
  final Map<String, List<SubscriptionCollectionOrigin>> proxyOrigins;
  final int deduplicatedProxyCount;
  final int renamedProxyCount;
}

/// A machine-readable failure; callers localize it without exposing credentials.
final class SubscriptionCollectionException implements Exception {
  const SubscriptionCollectionException(this.code, {this.sourceId, this.path});
  final String code;
  final String? sourceId;
  final String? path;
  @override
  String toString() =>
      'SubscriptionCollectionException($code, $sourceId, $path)';
}

/// Combines already-cleaned sources without I/O. The primary document owns
/// routing, DNS and TUN; secondary sources contribute nodes, providers and only
/// groups needed by their node/provider dialers. Raw source maps are untouched.
SubscriptionCollectionResult mergeSubscriptionCollection({
  required List<SubscriptionCollectionSource> sources,
  required String primarySourceId,
}) {
  final byId = <String, SubscriptionCollectionSource>{};
  for (final source in sources) {
    if (!RegExp(r'^[A-Za-z0-9._-]{1,96}$').hasMatch(source.id)) {
      throw const SubscriptionCollectionException('invalid_source_id');
    }
    if (byId.containsKey(source.id)) {
      throw SubscriptionCollectionException(
        'invalid_source_id',
        sourceId: source.id,
      );
    }
    byId[source.id] = source;
  }
  final primary = byId[primarySourceId];
  if (primary == null) {
    throw const SubscriptionCollectionException('primary_source_missing');
  }
  final output = _copyMap(primary.config);
  final proxies = _entries(output, 'proxies', primary.id);
  final groups = _entries(output, 'proxy-groups', primary.id);
  final providers = _providers(output, primary.id);
  _validateNames(proxies, groups, primary.id);
  final maps = <String, SubscriptionCollectionSourceMap>{};
  final origins = <String, List<SubscriptionCollectionOrigin>>{};
  void record(String sourceId, Map<String, String> names) {
    for (final entry in names.entries) {
      origins
          .putIfAbsent(entry.value, () => [])
          .add(SubscriptionCollectionOrigin(sourceId, entry.key));
    }
  }

  final primaryProxies = {
    for (final proxy in proxies) _name(proxy): _name(proxy),
  };
  maps[primary.id] = SubscriptionCollectionSourceMap(
    proxies: primaryProxies,
    groups: {for (final group in groups) _name(group): _name(group)},
    providers: {for (final name in providers.keys) name: name},
  );
  record(primary.id, primaryProxies);
  var deduplicated = 0;
  var renamed = 0;
  for (final source in sources) {
    if (source.id == primary.id) continue;
    final incoming = _entries(source.config, 'proxies', source.id);
    final sourceGroups = _entries(source.config, 'proxy-groups', source.id);
    final sourceProviders = _providers(source.config, source.id);
    _validateNames(incoming, sourceGroups, source.id);
    // Unrelated secondary routing tables do not get to precede a primary
    // MATCH rule. Only outbound dependencies cross the source boundary.
    final requiredGroups = _dependencyGroups(
      incoming,
      sourceGroups,
      sourceProviders,
      source.id,
    );
    final ProxyNamespaceImport imported;
    try {
      imported = importProxyNamespace(
        incoming,
        groups: requiredGroups,
        existing: proxies,
        groupNames: {for (final group in groups) _name(group)},
        exactDefinitions: true,
        namePrefix: '[' + source.id + '] ',
      );
    } on SubscriptionCollectionException catch (failure) {
      throw SubscriptionCollectionException(
        failure.code,
        sourceId: source.id,
        path: failure.path,
      );
    }
    final nodeNames = {
      for (final proxy in incoming) _name(proxy): imported.names[_name(proxy)]!,
    };
    final groupNames = {
      for (final group in requiredGroups)
        _name(group): imported.names[_name(group)]!,
    };
    // Providers have their own namespace; a group and provider may share a
    // label without turning group.use into a proxy reference.
    final providerNames = <String, String>{};
    final takenProviders = providers.keys.toSet();
    for (final entry in sourceProviders.entries) {
      final definition = _renameProvider(_copyMap(entry.value), imported.names);
      // Reusing an identical HTTP source avoids another fetch. File providers
      // are source-relative and cannot be equated by an equal path string.
      final same = definition['type'] == 'http'
          ? providers.entries
                .where(
                  (entry) => const DeepCollectionEquality().equals(
                    _exactValue(entry.value),
                    _exactValue(definition),
                  ),
                )
                .firstOrNull
          : null;
      final assigned =
          same?.key ??
          claimProxyName('[' + source.id + '] ' + entry.key, takenProviders);
      providerNames[entry.key] = assigned;
      if (same == null) providers[assigned] = definition;
    }
    for (final group in requiredGroups) {
      groups.add(
        _renameGroup(
          _copyMap(group),
          imported.names,
          providerNames,
          sourceProxyNames: incoming.map(_name).toList(),
          sourceProviderNames: sourceProviders.keys.toList(),
          sourceId: source.id,
        ),
      );
    }
    proxies.addAll(imported.added);
    deduplicated += incoming.length - imported.added.length;
    renamed += nodeNames.entries
        .where((entry) => entry.key != entry.value)
        .length;
    maps[source.id] = SubscriptionCollectionSourceMap(
      proxies: nodeNames,
      groups: groupNames,
      providers: providerNames,
    );
    record(source.id, nodeNames);
  }
  if (proxies.isNotEmpty || output.containsKey('proxies'))
    output['proxies'] = proxies;
  if (groups.isNotEmpty || output.containsKey('proxy-groups'))
    output['proxy-groups'] = groups;
  if (providers.isNotEmpty || output.containsKey('proxy-providers'))
    output['proxy-providers'] = providers;
  return SubscriptionCollectionResult(
    config: output,
    sources: Map.unmodifiable(maps),
    proxyOrigins: Map.unmodifiable({
      for (final entry in origins.entries)
        entry.key: List.unmodifiable(entry.value),
    }),
    deduplicatedProxyCount: deduplicated,
    renamedProxyCount: renamed,
  );
}

String _name(Map map) => map['name']?.toString() ?? '';

Object? _copy(Object? value) => switch (value) {
  final Map map => {
    for (final MapEntry(:key, :value) in map.entries)
      key as String: _copy(value),
  },
  final List list => [for (final item in list) _copy(item)],
  null || String() || num() || bool() => value,
  _ => throw const SubscriptionCollectionException('non_config_value'),
};

Map<String, dynamic> _copyMap(Map map) =>
    Map<String, dynamic>.from(_copy(map) as Map);

List<Map<String, dynamic>> _entries(Map config, String key, String sourceId) {
  final raw = config[key];
  if (raw == null) return [];
  if (raw is! List || raw.any((entry) => entry is! Map)) {
    throw SubscriptionCollectionException(
      'invalid_section',
      sourceId: sourceId,
      path: key,
    );
  }
  return [for (final entry in raw) Map<String, dynamic>.from(entry as Map)];
}

Map<String, Map<String, dynamic>> _providers(Map config, String sourceId) {
  final raw = config['proxy-providers'];
  if (raw == null) return {};
  if (raw is! Map ||
      raw.entries.any(
        (entry) =>
            entry.key is! String ||
            (entry.key as String).trim().isEmpty ||
            entry.value is! Map,
      )) {
    throw SubscriptionCollectionException(
      'invalid_section',
      sourceId: sourceId,
      path: 'proxy-providers',
    );
  }
  return {
    for (final entry in raw.entries)
      entry.key as String: Map<String, dynamic>.from(entry.value as Map),
  };
}

void _validateNames(
  List<Map<String, dynamic>> proxies,
  List<Map<String, dynamic>> groups,
  String sourceId,
) {
  final names = <String>{};
  for (final entry in [...proxies, ...groups]) {
    if (entry['name'] is! String ||
        _name(entry).isEmpty ||
        !names.add(_name(entry))) {
      throw SubscriptionCollectionException(
        'ambiguous_outbound_name',
        sourceId: sourceId,
        path: 'name',
      );
    }
  }
  for (final proxy in proxies) {
    if (RuleTarget.ruleTargets.contains(_name(proxy))) {
      throw SubscriptionCollectionException(
        'reserved_proxy_name',
        sourceId: sourceId,
        path: _name(proxy),
      );
    }
  }
  for (final group in groups) {
    if (RuleTarget.baseTargets.contains(_name(group))) {
      throw SubscriptionCollectionException(
        'reserved_group_name',
        sourceId: sourceId,
        path: _name(group),
      );
    }
  }
}

Map<String, dynamic> _renameNode(
  Map<String, dynamic> node,
  Map<String, String> names,
) {
  for (final field in const ['dialer-proxy', 'connect-via']) {
    if (node[field] case final String target)
      node[field] = names[target] ?? target;
  }
  return node;
}

Map<String, dynamic> _renameProvider(
  Map<String, dynamic> provider,
  Map<String, String> names,
) {
  for (final field in const ['proxy', 'dialer-proxy', 'connect-via']) {
    if (provider[field] case final String target)
      provider[field] = names[target] ?? target;
  }
  if (provider['override'] case final Map override) {
    provider['override'] = _renameNode(_copyMap(override), names);
  }
  // Paths stay source-owned. This pure stage never guesses a new directory.
  return provider;
}

List<String> _references(Map map, String field, String sourceId) {
  final raw = map[field];
  if (raw == null) return [];
  if (raw is! List || raw.any((value) => value is! String)) {
    throw SubscriptionCollectionException(
      'invalid_reference_list',
      sourceId: sourceId,
      path: field,
    );
  }
  return raw.cast<String>();
}

List<Map<String, dynamic>> _dependencyGroups(
  List<Map<String, dynamic>> proxies,
  List<Map<String, dynamic>> groups,
  Map<String, Map<String, dynamic>> providers,
  String sourceId,
) {
  final nodes = {for (final proxy in proxies) _name(proxy)};
  final byName = {for (final group in groups) _name(group): group};
  final groupIndexes = {
    for (final (index, group) in groups.indexed) _name(group): index,
  };
  final required = <String>{};
  final pending = <String>[];
  void take(Object? value, String path) {
    if (value == null || value == '') return;
    if (value is! String) {
      throw SubscriptionCollectionException(
        'invalid_dependency',
        sourceId: sourceId,
        path: path,
      );
    }
    if (byName.containsKey(value)) {
      if (required.add(value)) pending.add(value);
    } else if (!nodes.contains(value) &&
        !RuleTarget.ruleTargets.contains(value)) {
      throw SubscriptionCollectionException(
        'missing_dependency',
        sourceId: sourceId,
        path: path,
      );
    }
  }

  for (final (index, node) in proxies.indexed) {
    for (final field in const ['dialer-proxy', 'connect-via']) {
      take(node[field], 'proxies[$index].' + field);
    }
  }
  for (final (index, entry) in providers.entries.indexed) {
    for (final field in const ['proxy', 'dialer-proxy', 'connect-via']) {
      take(entry.value[field], 'proxy-providers[$index].' + field);
    }
    if (entry.value['override'] case final Map override) {
      for (final field in const ['dialer-proxy', 'connect-via']) {
        take(override[field], 'proxy-providers[$index].override.' + field);
      }
    }
  }
  while (pending.isNotEmpty) {
    final group = byName[pending.removeLast()]!;
    final groupPath =
        'proxy-groups[' + groupIndexes[_name(group)].toString() + ']';
    for (final field in const ['filter', 'exclude-filter']) {
      if (group[field] != null && group[field] != '') {
        // Core regexp2 filtering cannot be reproduced by Dart's RegExp after
        // namespacing. Callers must resolve this group with the actual core.
        throw SubscriptionCollectionException(
          'group_requires_core_resolution',
          sourceId: sourceId,
          path: groupPath + '.' + field,
        );
      }
    }
    for (final member in _references(group, 'proxies', sourceId)) {
      take(member, groupPath + '.proxies');
    }
    for (final field in const ['default-selected', 'empty-fallback']) {
      take(group[field], groupPath + '.' + field);
    }
    final ignoresUse =
        group['include-all'] == true ||
        group['include-all-providers'] == true ||
        group['type'] == 'relay';
    for (final provider
        in ignoresUse
            ? const <String>[]
            : _references(group, 'use', sourceId)) {
      if (!providers.containsKey(provider)) {
        throw SubscriptionCollectionException(
          'missing_provider',
          sourceId: sourceId,
          path: groupPath + '.use',
        );
      }
    }
  }
  return [
    for (final group in groups)
      if (required.contains(_name(group))) group,
  ];
}

Map<String, dynamic> _renameGroup(
  Map<String, dynamic> group,
  Map<String, String> names,
  Map<String, String> providers, {
  required List<String> sourceProxyNames,
  required List<String> sourceProviderNames,
  required String sourceId,
}) {
  group['name'] = names[_name(group)]!;
  var proxies = _references(group, 'proxies', sourceId).toList();
  var use = _references(group, 'use', sourceId).toList();
  // Materialize implicit membership before merging registries, retaining the
  // core's sorted expansion and duplicate/order semantics.
  final all = group['include-all'] == true;
  if (all || group['include-all-proxies'] == true) {
    proxies.addAll(sourceProxyNames.toList()..sort());
  }
  if (all || group['include-all-providers'] == true) {
    use = sourceProviderNames.toList()..sort();
  }
  group.remove('include-all');
  group.remove('include-all-proxies');
  group.remove('include-all-providers');
  if (group.containsKey('proxies') || proxies.isNotEmpty) {
    group['proxies'] = [for (final name in proxies) names[name] ?? name];
  }
  if (group.containsKey('use') || use.isNotEmpty) {
    group['use'] = [for (final name in use) providers[name] ?? name];
  }
  if (group['use-position'] case final Map positions) {
    group['use-position'] = {
      for (final entry in positions.entries)
        providers[entry.key] ?? entry.key: entry.value,
    };
  }
  for (final field in const ['default-selected', 'empty-fallback']) {
    if (group[field] case final String name) group[field] = names[name] ?? name;
  }
  return group;
}
