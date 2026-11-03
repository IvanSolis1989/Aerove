part of 'native_lite_mapping.dart';

class NativeLiteBuilderProfile {
  NativeLiteBuilderProfile(
    this.overrides,
    this.proxies,
    this.groups,
    this.rules,
    this.files,
  );
  final ProfileOverrides overrides;
  final List<CustomProxy> proxies;
  final List<ProxyGroup> groups;
  final List<Rule> rules;
  final List<NativeLiteFile> files;
}

Future<NativeLiteBuilderProfile> mapNativeLiteBuilder(
  NativeLiteExport source,
  NativeLiteIds ids,
  String uuid,
  int profileId,
  String yaml,
  ProfileOverrides overrides,
  List<CustomProxy> libraryProxies,
  List<ClashProvider> libraryProviders,
  Map<String, dynamic> library,
  Rule Function(String, String, int?, String?) makeRule,
) async {
  Map<String, dynamic> document;
  try {
    document = legacyObject(
      jsonDecode(jsonEncode(loadYaml(yaml))),
      'profiles.custom.source',
    );
  } catch (error) {
    if (error is NativeLiteMigrationException) rethrow;
    legacyFailure('profiles.custom.source', 'invalid YAML mapping');
  }
  final header = RegExp(
    r'^\s*# aerove-builder-bindings: ([^\r\n]*)',
    multiLine: true,
  ).allMatches(yaml).toList();
  if (header.length > 1)
    legacyFailure('profiles.builder.bindings', 'duplicate binding header');
  Map<String, dynamic> bindings = {};
  if (header.isNotEmpty) {
    try {
      bindings = legacyObject(
        jsonDecode(utf8.decode(base64Decode(header.single.group(1)!))),
        'profiles.builder.bindings',
      );
    } catch (error) {
      if (error is NativeLiteMigrationException) rethrow;
      legacyFailure('profiles.builder.bindings', 'invalid UTF8 binding header');
    }
    if (bindings['schema'] != 1 && bindings['schema'] != 2)
      legacyFailure(
        'profiles.builder.bindings.schema',
        'unsupported binding version',
      );
  }
  String resource(String section, Object? reference) {
    final key = legacyText(reference, 'profiles.builder.$section');
    final matches = legacyList(library[section] ?? [], 'builder.$section')
        .map((row) => legacyObject(row, 'builder.$section'))
        .where(
          (row) =>
              row[bindings['schema'] == 1
                  ? (section == 'filters' ? 'label' : 'name')
                  : 'id'] ==
              key,
        )
        .toList();
    if (matches.length != 1)
      legacyFailure(
        'profiles.builder.$section',
        'missing or ambiguous library identity',
      );
    return legacyText(matches.single['id'], 'builder.$section.id');
  }

  final linked = {
    for (final entry in legacyObject(
      bindings['proxies'] ?? {},
      'profiles.builder.proxies',
    ).entries)
      entry.key: resource('proxies', entry.value),
  };
  final proxies = <CustomProxy>[];
  final scope = <int>[];
  final owned = <int>{};
  final aliases = <int, String>{};
  final rawProxies = legacyList(
    document['proxies'] ?? [],
    'profiles.custom.proxies',
  );
  final orders = indexing.generateNKeys(rawProxies.length);
  for (final (position, raw) in rawProxies.indexed) {
    final definition = legacyObject(raw, 'profiles.custom.proxies');
    final name = legacyText(definition['name'], 'profiles.custom.proxies.name');
    if (linked[name] case final String key) {
      final id = ids.values['builder-proxy:$key'];
      if (id == null || !libraryProxies.any((proxy) => proxy.id == id))
        legacyFailure(
          'profiles.builder.proxies',
          'bound library identity is missing',
        );
      if (aliases.containsKey(id))
        legacyFailure(
          'profiles.builder.proxies',
          'one resource has ambiguous local names',
        );
      aliases[id] = name;
      scope.add(id);
    } else {
      final id = ids.id('profile-proxy:$uuid:$position');
      scope.add(id);
      owned.add(id);
      proxies.add(
        CustomProxy(id: id, definition: definition, order: orders[position]),
      );
    }
  }
  if (linked.keys.any(
    (name) => !rawProxies.any((raw) => raw is Map && raw['name'] == name),
  ))
    legacyFailure('profiles.builder.proxies', 'bound local node is missing');
  final groups = <ProxyGroup>[];
  final rawGroups = legacyList(
    document['proxy-groups'] ?? [],
    'profiles.custom.proxy-groups',
  );
  final groupOrders = indexing.generateNKeys(rawGroups.length);
  for (final (position, raw) in rawGroups.indexed) {
    final definition = legacyObject(raw, 'profiles.custom.proxy-groups');
    ProxyGroup group;
    try {
      group = ProxyGroup.fromJson({
        ...definition,
        'id': ids.id('profile-group:$uuid:$position'),
        'profileId': profileId,
        'order': groupOrders[position],
      });
    } catch (_) {
      legacyFailure(
        'profiles.custom.proxy-groups',
        'group fields cannot be represented',
      );
    }
    final reconstructed = group.definition;
    for (final entry in definition.entries) {
      if (jsonEncode(reconstructed[entry.key]) != jsonEncode(entry.value))
        legacyFailure(
          'profiles.custom.proxy-groups.${entry.key}',
          'group field cannot round-trip',
        );
    }
    groups.add(group);
  }
  final rules = <Rule>[];
  final rawRules = legacyList(document['rules'] ?? [], 'profiles.custom.rules');
  final ruleOrders = indexing.generateNKeys(rawRules.length);
  for (final (position, raw) in rawRules.indexed) {
    rules.add(
      makeRule(
        legacyText(raw, 'profiles.custom.rules'),
        'builder-rule:$uuid:$position',
        profileId,
        ruleOrders[position],
      ),
    );
  }
  final files = <NativeLiteFile>[];
  final filterBindings = <int, List<int>>{};
  final filterManualMembers = <int, List<String>>{};
  for (final entry in legacyObject(
    bindings['filters'] ?? {},
    'profiles.builder.filters',
  ).entries) {
    if (!groups.any((group) => group.name == entry.key))
      legacyFailure('profiles.builder.filters', 'bound group is missing');
    final value = legacyObject(entry.value, 'profiles.builder.filters');
    final groupId = groups.singleWhere((group) => group.name == entry.key).id;
    filterBindings[groupId] = [
      for (final label in legacyList(
        value['labels'],
        'profiles.builder.filters.labels',
      ))
        ids.id('builder-filter:${resource('filters', label)}'),
    ];
    filterManualMembers[groupId] =
        legacyList(value['manual'], 'profiles.builder.filters.manual')
            .map(
              (value) => legacyText(value, 'profiles.builder.filters.manual'),
            )
            .toList();
  }
  final iconBindings = <int, Map<String, dynamic>>{};
  for (final entry in legacyObject(
    bindings['icons'] ?? {},
    'profiles.builder.icons',
  ).entries) {
    if (!groups.any((group) => group.name == entry.key))
      legacyFailure('profiles.builder.icons', 'bound group is missing');
    final value = legacyObject(entry.value, 'profiles.builder.icons');
    iconBindings[groups.singleWhere((group) => group.name == entry.key).id] = {
      'set': ids.id('builder-icon:${resource('icon-sets', value['set'])}'),
      'url': legacyText(value['url'], 'profiles.builder.icons.url'),
    };
  }
  final providerBindings = <String, int>{};
  final sourceProxyProviders = <String, Map<String, dynamic>>{};
  final sourceRuleProviders = <String, Map<String, dynamic>>{};
  for (final section in ['proxy-providers', 'rule-providers']) {
    final target = section == 'proxy-providers'
        ? sourceProxyProviders
        : sourceRuleProviders;
    for (final entry in legacyObject(
      document[section] ?? {},
      'profiles.custom.$section',
    ).entries) {
      final definition = legacyObject(
        entry.value,
        'profiles.custom.$section.definition',
      );
      final linkedRules = legacyObject(
        bindings['rule-providers'] ?? {},
        'profiles.builder.rule-providers',
      );
      if (linkedRules[entry.key] case final String key
          when section == 'rule-providers') {
        final id =
            ids.values['builder-provider:${resource('rule-providers', key)}'];
        final matched = libraryProviders
            .where((provider) => provider.id == id)
            .toList();
        if (matched.length != 1)
          legacyFailure(
            'profiles.builder.rule-providers',
            'bound library identity is missing',
          );
        // A durable ID binding, consumed by the existing provider preparation path.
        providerBindings[entry.key] = id!;
      } else {
        target[entry.key] = definition;
      }
      if (definition['type'] == 'file' &&
          !providerBindings.containsKey(entry.key)) {
        final path = legacyText(
          definition['path'],
          'profiles.custom.$section.path',
        );
        if (path.startsWith('.library/'))
          legacyFailure(
            'profiles.custom.$section.path',
            'unbound library file requires source identity adapter',
          );
        final original = await source.source(
          'runtime/resources/imports/$uuid/$path',
        );
        final hash = md5
            .convert(utf8.encode('$section/${entry.key}'))
            .toString();
        files.add(
          NativeLiteFile.copy(
            'profiles/providers/$profileId/${section == 'proxy-providers' ? 'proxies' : 'rules'}/$hash',
            original,
          ),
        );
      }
    }
  }
  final base = {...document}
    ..remove('proxies')
    ..remove('proxy-groups')
    ..remove('rules')
    ..remove('proxy-providers')
    ..remove('rule-providers');
  return NativeLiteBuilderProfile(
    overrides.copyWith(
      scopedCustomProxyIds: scope,
      ownedCustomProxyIds: owned,
      proxyAliases: aliases,
      baseConfig: base,
      sourceProxyProviders: sourceProxyProviders,
      sourceRuleProviders: sourceRuleProviders,
      inheritGlobalRules: true,
      applyExtensionToCustom: true,
      ruleProviderBindings: providerBindings,
      filterBindings: filterBindings,
      filterManualMembers: filterManualMembers,
      iconBindings: iconBindings,
    ),
    proxies,
    groups,
    rules,
    files,
  );
}
