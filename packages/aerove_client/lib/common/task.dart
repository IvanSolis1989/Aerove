import 'package:fl_clash/common/core_capabilities.dart';
import 'package:fl_clash/common/tun_policy.dart';
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart' show sha256;
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart';

/// Applies only persisted user edits before the existing single script evaluation.
String nativeProxyProviderScriptInput(
  String configJson,
  ProfileOverrides overrides,
) {
  if (!overrides.proxyProviders.values.any(
    (value) => value.sourceDefinition != null,
  ))
    return configJson;
  final decoded = jsonDecode(configJson);
  if (decoded is! Map<String, dynamic>)
    throw const FormatException('profile: expected mapping');
  _applyNativeProxyProviderEdits(decoded, overrides);
  return jsonEncode(decoded);
}

void _applyNativeProxyProviderEdits(Map config, ProfileOverrides overrides) {
  final providers = config['proxy-providers'];
  if (providers == null) return;
  if (providers is! Map)
    throw const FormatException('proxy-providers: expected mapping');
  for (final entry in overrides.proxyProviders.entries) {
    if (entry.value.sourceDefinition == null ||
        !providers.containsKey(entry.key))
      continue;
    final current = providers[entry.key];
    if (current is! Map)
      throw FormatException('proxy-providers.${entry.key}: expected mapping');
    providers[entry.key] = entry.value.applyNativeEdits(
      Map<String, dynamic>.from(current),
    );
  }
}

const _meowUnsupportedDnsPaths = {
  'listen-routing-mark',
  'prefer-h3',
  'respect-rules',
  'ipv6',
  'ipv6-timeout',
  'cache-algorithm',
  'cache-max-size',
  'fake-ip-range6',
  'fake-ip-ttl',
  'fallback-lazy-query',
  'proxy-server-nameserver-policy',
  'direct-nameserver',
  'direct-nameserver-follow-policy',
  'fallback-filter.geosite',
};

void _validateMeowSourceDns(Map config) {
  final dns = config['dns'];
  if (dns == null) return;
  if (dns is! Map)
    throw const FormatException('\$.dns: expected source DNS mapping');
  if (dns.containsKey(Dns.generatedDefaultsJsonKey))
    throw const FormatException(
      '\$.dns.__aerove_dns_defaults: reserved stored constructor provenance',
    );
  for (final path in _meowUnsupportedDnsPaths) {
    final parts = path.split('.');
    final present = parts.length == 1
        ? dns.containsKey(path)
        : dns[parts[0]] is Map && (dns[parts[0]] as Map).containsKey(parts[1]);
    if (present)
      throw FormatException(
        '\$.dns.$path: explicit source field has no fixed Meow DNS consumer',
      );
  }
}

String meowDnsScriptInput(String configJson, {ProfileOverrides? overrides}) {
  final decoded = jsonDecode(configJson);
  if (decoded is! Map)
    throw const FormatException(
      'profile: expected source configuration mapping',
    );
  _validateMeowSourceDns(decoded);
  if (overrides != null)
    _meowDnsOverrideKeys(overrides.dns, overrides.dnsOverrideKeys);
  return configJson;
}

Set<DnsOverrideKey> _meowDnsOverrideKeys(Dns dns, Set<DnsOverrideKey> keys) {
  final result = <DnsOverrideKey>{};
  for (final key in keys) {
    if (!_meowUnsupportedDnsPaths.contains(key.path)) {
      result.add(key);
      continue;
    }
    final generated =
        dns.generatedDefaultKeys.contains(key) &&
        switch (key) {
          DnsOverrideKey.ipv6 => dns.ipv6 == xclashDefaultDns.ipv6,
          DnsOverrideKey.fakeIpRange6 =>
            dns.fakeIpRange6 == xclashDefaultDns.fakeIpRange6,
          _ => false,
        };
    if (!generated)
      throw FormatException(
        '\$.dns.${key.path}: explicit typed value has no fixed Meow DNS consumer',
      );
  }
  return result;
}

/// Projects the typed global producer; explicit profile declarations use separate strict validation.
Map<String, String> meowGlobalGeoDownloadUrls(PatchClashConfig patch) {
  final urls = Map<String, String>.from(patch.geoXUrl.raw);
  if (urls['geoip'] != null &&
      urls['geoip'] != defaultGeoXUrl[GeoResource.GEOIP]) {
    throw const FormatException(
      '\$.geox-url.geoip: fixed Meow has no geoip.dat download-source consumer',
    );
  }
  urls.remove('geoip');
  return urls;
}

void _applyMeowGeodata(Map config, PatchClashConfig patch) {
  const originKey = 'aerove-geo-origin';
  if (config.containsKey(originKey))
    throw const FormatException(
      '\$.aerove-geo-origin: reserved Task producer metadata',
    );
  Map<String, dynamic> mapping(Object? value, String path) {
    if (value == null) return {};
    if (value is! Map || value.keys.any((key) => key is! String))
      throw FormatException('$path: expected mapping');
    return Map<String, dynamic>.from(value);
  }

  final geo = mapping(config['geodata'], r'$.geodata');
  final urls = mapping(geo['url'], r'$.geodata.url');
  final rootUrls = mapping(config['geox-url'], r'$.geox-url');
  final explicitAuto =
      config.containsKey('geo-auto-update') || geo.containsKey('auto-update');
  final explicitInterval =
      config.containsKey('geo-update-interval') ||
      geo.containsKey('auto-update-interval');
  final explicitUrls = <String, String>{};
  for (final key in rootUrls.keys) {
    if (!['mmdb', 'asn', 'geosite'].contains(key))
      throw FormatException(
        '\$.geox-url.$key: fixed Meow accepts mmdb/asn/geosite download sources',
      );
  }
  final patchUrls = meowGlobalGeoDownloadUrls(patch);
  Object? resolve(
    String sourcePath,
    Object? root,
    bool rootPresent,
    String nestedPath,
    Object? nested,
    bool nestedPresent,
    Object? fallback,
  ) {
    if (rootPresent && nestedPresent && root != nested)
      throw FormatException('$sourcePath conflicts with $nestedPath');
    final source = rootPresent ? root : nested;
    return rootPresent || nestedPresent ? source : fallback;
  }

  final auto = resolve(
    r'$.geo-auto-update',
    config['geo-auto-update'],
    config.containsKey('geo-auto-update'),
    r'$.geodata.auto-update',
    geo['auto-update'],
    geo.containsKey('auto-update'),
    patch.geoAutoUpdate,
  );
  if (auto is! bool)
    throw const FormatException('\$.geodata.auto-update: expected Boolean');
  final interval = resolve(
    r'$.geo-update-interval',
    config['geo-update-interval'],
    config.containsKey('geo-update-interval'),
    r'$.geodata.auto-update-interval',
    geo['auto-update-interval'],
    geo.containsKey('auto-update-interval'),
    patch.geoUpdateInterval,
  );
  if (interval is! int || interval < 1 || interval > 87600)
    throw const FormatException(
      '\$.geodata.auto-update-interval: expected 1..87600 hours',
    );
  geo['auto-update'] = auto;
  geo['auto-update-interval'] = interval;
  for (final key in ['mmdb', 'asn', 'geosite']) {
    final declared = resolve(
      '\$.geox-url.$key',
      rootUrls[key],
      rootUrls.containsKey(key),
      '\$.geodata.url.$key',
      urls[key],
      urls.containsKey(key),
      patchUrls[key],
    );
    final alias = key == 'asn' ? 'asn-mmdb-download-url' : '$key-download-url';
    final topPresent = config.containsKey(alias);
    final top = config[alias];
    if (topPresent &&
        (rootUrls.containsKey(key) || urls.containsKey(key)) &&
        declared != top)
      throw FormatException('\$.$alias conflicts with \$.geodata.url.$key');
    final value = topPresent ? top : declared;
    if (value == null &&
        (topPresent || rootUrls.containsKey(key) || urls.containsKey(key)))
      throw FormatException(
        '\$.geodata.url.$key: explicit null URL is not supported',
      );
    if (value != null) {
      if (value is! String || value.isEmpty)
        throw FormatException('\$.geodata.url.$key: expected nonempty URL');
      urls[key] = value;
      if (topPresent ||
          rootUrls.containsKey(key) ||
          (geo['url'] is Map && (geo['url'] as Map).containsKey(key)))
        explicitUrls[key] = value;
    }
  }
  geo['url'] = urls;
  config['geodata'] = geo;
  config[originKey] = {
    'schema': 1,
    'source': {
      if (explicitAuto) 'auto-update': auto,
      if (explicitInterval) 'auto-update-interval': interval,
      if (explicitUrls.isNotEmpty) 'url': explicitUrls,
    },
  };
  config.remove('geo-auto-update');
  config.remove('geo-update-interval');
  config.remove('geox-url');
}

Future<T> decodeJSONTask<T>(String data) async {
  return compute<String, T>(_decodeJSON, data);
}

Future<T> _decodeJSON<T>(String content) async {
  return json.decode(content);
}

Future<String> encodeYamlTask<T>(T data) async {
  return compute<T, String>(_encodeYaml, data);
}

Future<String> _encodeYaml<T>(T content) async {
  return yaml.encode(content);
}

/// Throws [FormatException] when the file is not UTF-8.
Future<String?> readTextFileTask(String path) {
  return compute(_readTextFile, path);
}

String? _readTextFile(String path) {
  final file = File(path);
  if (!file.existsSync()) {
    return null;
  }
  return utf8.decode(file.readAsBytesSync());
}

Future<List<Group>> toGroupsTask(ComputeGroupsState data) async {
  return compute<ComputeGroupsState, List<Group>>(buildGroups, data);
}

@visibleForTesting
Future<List<Group>> buildGroups(ComputeGroupsState state) async {
  final proxiesData = state.proxiesData;
  final all = proxiesData.all;
  final sortType = state.sortType;
  final delayMap = state.delayMap;
  final selectedMap = state.selectedMap;
  final defaultTestUrl = state.defaultTestUrl;
  final proxies = proxiesData.proxies;
  if (proxies.isEmpty) return [];
  final membersByName = <String, Proxy>{};
  final groups = <Group>[];
  for (final groupName in all) {
    final raw = proxies[groupName];
    if (raw is! Map) continue;
    if (!GroupTypeExtension.valueList.contains(raw['type'])) continue;
    final memberNames = raw['all'];
    final group = Map<String, dynamic>.from(raw);
    final members = <Proxy>[];
    if (memberNames is List) {
      for (final name in memberNames) {
        final member = proxies[name];
        if (member == null) continue;
        members.add(
          membersByName.putIfAbsent(
            name,
            () => Proxy.fromJson(member as Map<String, dynamic>),
          ),
        );
      }
    }
    group['all'] = const [];
    groups.add(Group.fromJson(group).copyWith(all: members));
  }
  return computeSort(
    groups: groups,
    sortType: sortType,
    delayMap: delayMap,
    selectedMap: selectedMap,
    defaultTestUrl: defaultTestUrl,
  );
}

Future<ClashConfig> clashConfigTask(Map<String, dynamic> data) async {
  return compute<Map<String, dynamic>, ClashConfig>(_buildClashConfig, data);
}

ClashConfig _buildClashConfig(Map<String, dynamic> configMap) {
  return ClashConfig.fromJson(configMap);
}

typedef ConfigCounts = ({int groups, int proxies, int rules});

class ProfileSelectionBinding {
  ProfileSelectionBinding(this.profileId, Map<String, String> selectedMap)
    : selectedMap = Map.unmodifiable(selectedMap);

  final int profileId;
  final Map<String, String> selectedMap;
}

class ProfileSelectionPolicy {
  const ProfileSelectionPolicy({
    required this.sourceStoreSelected,
    required this.requestedStoreSelected,
    required this.binding,
  });

  final bool? sourceStoreSelected;
  final bool? requestedStoreSelected;
  final ProfileSelectionBinding? binding;

  SetupParams consumeSql(SetupParams params, {required int? profileId}) {
    final actual = binding;
    final proof = params.dataSourceProof;
    if (actual == null ||
        actual.profileId != profileId ||
        proof == null ||
        proof['schema'] != 2 ||
        proof['tables'] is! Map ||
        (proof['tables'] as Map)['profiles'] is! Map) {
      throw StateError(
        'Profile selection requires its shared SQL source proof',
      );
    }
    if (!mapEquals(actual.selectedMap, params.selectedMap)) {
      throw StateError(
        'Profile SQL selection changed while compiling its policy',
      );
    }
    return params.copyWith(selectedMap: actual.selectedMap);
  }
}

bool? _sourceStoreSelected(Map config, {required bool meow}) {
  if (!config.containsKey('profile')) return null;
  final profile = config['profile'];
  if (profile is! Map) {
    throw const FormatException('\$.profile: expected mapping');
  }
  if (meow) {
    for (final key in profile.keys) {
      if (key != 'store-selected') {
        throw FormatException(
          '\$.profile.$key: no shared SQL selection policy',
        );
      }
    }
  }
  if (profile.containsKey('store-selected') &&
      profile['store-selected'] is! bool) {
    throw const FormatException('\$.profile.store-selected: expected Boolean');
  }
  return profile['store-selected'] as bool?;
}

typedef RealProfile = ({
  String yaml,
  String md5,
  ConfigCounts counts,
  ProfileSelectionPolicy selectionPolicy,
});

/// Counts what the Core was handed, not what the profile file declares.
ConfigCounts configCountsOf(Map<Object?, Object?> config) {
  int lengthOf(Object? value) => value is List ? value.length : 0;
  return (
    groups: lengthOf(config['proxy-groups']),
    proxies: lengthOf(config['proxies']),
    rules: lengthOf(config['rules']),
  );
}

String? lastMatchTarget(List<String> rules) {
  for (int i = rules.length - 1; i >= 0; i--) {
    final parsed = Rule.parse(rules[i]);
    if (parsed.ruleAction == RuleAction.MATCH) {
      final target = parsed.ruleTarget;
      if (target != null && target.isNotEmpty) {
        return target;
      }
    }
  }
  return null;
}

typedef SetupInputsState = ({
  SetupState setupState,
  PatchClashConfig setupPatch,
  bool appendSystemDns,
  bool bypassPrivateRoute,
  String testUrl,
  bool skipCertVerify,
  bool safeMode,
  String build,
});

Future<String> setupInputsDigestTask(
  SetupInputsState state, {
  required String coreId,
}) => compute(
  (payload) => setupInputsDigest(payload.state, coreId: payload.coreId),
  (state: state, coreId: coreId),
);

@visibleForTesting
String setupInputsDigest(SetupInputsState state, {String coreId = 'mihomo'}) {
  final setupState = state.setupState;
  final canonical = json.encode({
    'core-id': coreId,
    'profile-id': setupState.profileId,
    'profile-last-update-date': setupState.profileLastUpdateDate,
    'profile-type': setupState.profileType.name,
    'extend-type': setupState.extendType.name,
    'rules': setupState.rules,
    'proxy-groups': setupState.proxyGroups,
    'added-rules': setupState.addedRules,
    'script': setupState.script,
    'overrides': setupState.overrides,
    'clash-providers': [
      for (final provider in setupState.clashProviders)
        [
          provider.id,
          provider.label,
          provider.url,
          provider.behavior.name,
          provider.format.name,
        ],
    ],
    'profile-providers': setupState.profileProviders,
    'app-proxies': setupState.appProxies,
    'proxy-dialers': {
      for (final MapEntry(:key, :value) in setupState.proxyDialers.entries)
        '$key': value,
    },
    'match-target': setupState.matchTarget,
    'setup-patch': state.setupPatch,
    'append-system-dns': state.appendSystemDns,
    'bypass-private-route': state.bypassPrivateRoute,
    'test-url': state.testUrl,
    'skip-cert-verify': state.skipCertVerify,
    'safe-mode': state.safeMode,
    'build': state.build,
  });
  return sha256.convert(utf8.encode(canonical)).toString();
}

Future<RealProfile> makeRealProfileTask(
  MakeRealProfileState data, {
  String coreId = 'mihomo',
  ProfileSelectionBinding? selectionBinding,
}) => compute(_makeRealProfileTask, (
  data: data,
  coreId: coreId,
  selectionBinding: selectionBinding,
));

Future<RealProfile> _makeRealProfileTask(
  ({
    MakeRealProfileState data,
    String coreId,
    ProfileSelectionBinding? selectionBinding,
  })
  payload,
) async {
  final data = payload.data;
  final capabilities = CoreCapabilities.forCore(payload.coreId);
  final rawConfig = switch (data.rawConfigJson) {
    final text? => decodeScriptResult(text),
    null => Map.from(data.rawConfig),
  };
  final sourceStoreSelected = _sourceStoreSelected(
    data.rawConfig,
    meow: capabilities.androidHostTun,
  );
  final requestedStoreSelected = _sourceStoreSelected(
    rawConfig,
    meow: capabilities.androidHostTun,
  );
  final selectionBinding = payload.selectionBinding;
  if (selectionBinding != null &&
      selectionBinding.profileId != data.profileId) {
    throw const FormatException(
      '\$.profile.store-selected: SQL profile identity differs',
    );
  }
  if (capabilities.androidHostTun &&
      selectionBinding == null &&
      (sourceStoreSelected == true || requestedStoreSelected == true)) {
    throw const FormatException(
      '\$.profile.store-selected: shared SQL selection consumer is required',
    );
  }
  final selectionPolicy = ProfileSelectionPolicy(
    sourceStoreSelected: sourceStoreSelected,
    requestedStoreSelected: requestedStoreSelected,
    binding: selectionBinding,
  );
  if (capabilities.androidHostTun) {
    _validateMeowSourceDns(data.rawConfig);
    _validateMeowSourceDns(rawConfig);
  }
  bool emptyText(Object? value) => value == null || value == '';
  bool zero(Object? value) => value == null || value == 0;
  bool emptyList(Object? value) =>
      value == null || value is List && value.isEmpty;
  bool disabledMap(Object? value) =>
      value == null ||
      value is Map &&
          (value.isEmpty || value.length == 1 && value['enable'] == false);
  void mihomoInactive(
    String key,
    Object value,
    bool Function(Object?) inactive,
  ) {
    if (!capabilities.androidHostTun) {
      rawConfig[key] = value;
      return;
    }
    if (rawConfig.containsKey(key) && !inactive(rawConfig[key]))
      throw FormatException(
        '\$.$key: source field has no verified Android host consumer',
      );
    if (!inactive(value))
      throw FormatException(
        '\$.$key: active client setting needs its Android host consumer',
      );
    rawConfig.remove(key);
  }

  final unsupportedSourceKeys = [
    if (!capabilities.globalTcpKeepAliveTuning) ...[
      'keep-alive-interval',
      'keep-alive-idle',
      'disable-keep-alive',
    ],
    if (!capabilities.geoDataLoaderSelection) 'geodata-loader',
  ];
  for (final key in unsupportedSourceKeys) {
    if (rawConfig.containsKey(key))
      throw FormatException(
        '\$.$key: explicit source policy has no fixed Meow configuration consumer',
      );
  }
  final realPatchConfig = data.realPatchConfig;
  if (capabilities.androidHostTun)
    _applyMeowGeodata(rawConfig, realPatchConfig);
  final tunOverrides = data.overrides.tun.overrideJson(
    data.overrides.tunOverrideKeys,
  );
  final sourceTun = capabilities.androidHostTun
      ? meowSourceTun(rawConfig['tun'], tunOverrides)
      : rawConfig['tun'];
  validateTunPolicy(
    coreId: payload.coreId,
    stack: realPatchConfig.tun.stack.name,
    congestionController: realPatchConfig.tun.congestionController,
    device: realPatchConfig.tun.device,
    sourceTun: sourceTun,
  );
  final profilesPath = data.profilesPath;
  final profileId = data.profileId;
  final overrides = data.overrides;
  final addedRules = data.addedRules;
  final appendSystemDns = data.appendSystemDns;
  final defaultUA = data.defaultUA;
  String getProvidersFilePathInner(String type, String key) {
    return join(
      profilesPath,
      providersDirectoryName,
      profileId.toString(),
      type,
      key.toMd5(),
    );
  }

  void confineProviders(String section, String type) {
    final providers = rawConfig[section];
    if (providers is! Map) {
      return;
    }
    for (final name in providers.keys) {
      final provider = providers[name];
      if (provider is! Map || provider['type'] == 'inline') {
        continue;
      }
      // Two providers may share a URL and differ only by header.
      final url = provider['url'];
      final hasUrl = url is String && url.isNotEmpty;
      final path = getProvidersFilePathInner(
        type,
        hasUrl ? '$name@$url' : '$section/$name',
      );
      if (hasUrl) {
        _migrateLegacyProviderFile(
          legacyPath: getProvidersFilePathInner(type, url),
          newPath: path,
        );
      }
      provider['path'] = path;
    }
  }

  rawConfig['external-controller'] =
      data.safeMode || realPatchConfig.secret.isEmpty
      ? ''
      : realPatchConfig.externalController.value;
  rawConfig['secret'] = realPatchConfig.secret;
  // mihomo serves the unix and pipe controllers without a secret, and the unix
  // one unlinks whatever sits at its path before opening it to everyone.
  mihomoInactive('external-controller-tls', '', emptyText);
  mihomoInactive('external-controller-unix', '', emptyText);
  mihomoInactive('external-controller-pipe', '', emptyText);
  final iptables = rawConfig['iptables'];
  if (!capabilities.androidHostTun && iptables is Map) {
    final inboundInterface = iptables['inbound-interface'];
    if (inboundInterface is String && inboundInterface.contains(' ')) {
      mihomoInactive('iptables', {'enable': false}, disabledMap);
    }
  }
  mihomoInactive('external-ui', '', emptyText);
  switch (realPatchConfig.interfaceNameMode) {
    case InterfaceNameMode.clear:
      mihomoInactive('interface-name', '', emptyText);
    case InterfaceNameMode.follow:
      break;
    case InterfaceNameMode.custom:
      mihomoInactive(
        'interface-name',
        realPatchConfig.interfaceName,
        emptyText,
      );
  }
  mihomoInactive('external-ui-url', '', emptyText);
  rawConfig['tcp-concurrent'] = realPatchConfig.tcpConcurrent;
  rawConfig['unified-delay'] = realPatchConfig.unifiedDelay;
  rawConfig['ipv6'] = realPatchConfig.ipv6;
  rawConfig['log-level'] = realPatchConfig.logLevel.name;
  rawConfig['port'] = 0;
  rawConfig['socks-port'] = 0;
  if (capabilities.globalTcpKeepAliveTuning) {
    rawConfig['keep-alive-interval'] = realPatchConfig.keepAliveInterval;
    rawConfig['keep-alive-idle'] = realPatchConfig.keepAliveIdle;
    rawConfig['disable-keep-alive'] = realPatchConfig.disableKeepAlive;
  } else {
    if (realPatchConfig.keepAliveInterval != 30)
      throw const FormatException(
        '\$.keep-alive-interval: fixed Meow does not support global TCP keepalive tuning',
      );
    if (realPatchConfig.keepAliveIdle != 15)
      throw const FormatException(
        '\$.keep-alive-idle: fixed Meow does not support global TCP keepalive tuning',
      );
    if (realPatchConfig.disableKeepAlive)
      throw const FormatException(
        '\$.disable-keep-alive: fixed Meow does not support global TCP keepalive tuning',
      );
  }
  mihomoInactive('routing-mark', realPatchConfig.routingMark, zero);
  rawConfig['mixed-port'] = realPatchConfig.mixedPort;
  rawConfig['port'] = realPatchConfig.port;
  rawConfig['socks-port'] = realPatchConfig.socksPort;
  mihomoInactive('redir-port', realPatchConfig.redirPort, zero);
  mihomoInactive('tproxy-port', realPatchConfig.tproxyPort, zero);
  rawConfig['find-process-mode'] = realPatchConfig.findProcessMode.name;
  rawConfig['allow-lan'] = realPatchConfig.allowLan;
  // The app owns local inbound authentication; a profile-provided
  // skip-auth-prefixes could silently exempt loopback and defeat it.
  rawConfig['authentication'] = data.authentication;
  mihomoInactive('skip-auth-prefixes', [], emptyList);
  rawConfig['mode'] = realPatchConfig.mode.name;
  if (capabilities.androidHostTun) {
    if (sourceTun != null) rawConfig['tun'] = sourceTun;
  } else {
    // Always-on Mihomo routing needs egress detection to avoid TUN loops; interface-name takes precedence.
    // Keep any:53 unless explicitly overridden; profile DNS hijacks may miss other resolvers.
    final tun = {
      if (rawConfig['tun'] is Map) ...rawConfig['tun'] as Map,
      'dns-hijack': defaultDnsHijack,
      ...tunOverrides,
      ...realPatchConfig.tun.coreJson,
      'auto-detect-interface': true,
    };
    if (data.bypassPrivateRoute) {
      final reservedFakeIpRanges = realPatchConfig.ipv6 && !data.safeMode
          ? overrides.dns.reservedFakeIpRanges(overrides.dnsOverrideKeys)
          : const <String>[];
      tun['route-exclude-address'] = [
        if (tun['route-exclude-address'] case final List routes) ...routes,
        ...(reservedFakeIpRanges.isEmpty
            ? privateRouteAddress
            : subtractCidrs(privateRouteAddress, reservedFakeIpRanges)),
      ];
    }
    rawConfig['tun'] = tun;
  }
  if (capabilities.geoDataLoaderSelection) {
    rawConfig['geodata-loader'] = realPatchConfig.geodataLoader.name;
  } else if (realPatchConfig.geodataLoader != GeodataLoader.memconservative) {
    throw const FormatException(
      '\$.geodata-loader: fixed Meow does not support loader selection',
    );
  }
  if (!capabilities.androidHostTun) {
    rawConfig['geo-auto-update'] = realPatchConfig.geoAutoUpdate;
    rawConfig['geo-update-interval'] = realPatchConfig.geoUpdateInterval;
  }
  final snifferOverride = overrides.sniffer.overrideJson(
    overrides.snifferOverrideKeys,
  );
  if (snifferOverride.isNotEmpty) {
    rawConfig['sniffer'] = mergeSnifferOverride(
      rawConfig['sniffer'] is Map
          ? Map<String, dynamic>.from(rawConfig['sniffer'] as Map)
          : <String, dynamic>{},
      snifferOverride,
    );
  }
  if (rawConfig['sniffer']?['sniff'] != null) {
    for (final value in (rawConfig['sniffer']?['sniff'] as Map).values) {
      if (value['ports'] != null && value['ports'] is List) {
        value['ports'] =
            value['ports']?.map((item) => item.toString()).toList() ?? [];
      }
    }
  }
  if (capabilities.androidHostTun) {
    rawConfig.remove('profile');
  } else if (rawConfig['profile'] == null) {
    rawConfig['profile'] = {};
  }
  void injectUnconfinedProviders(
    String section,
    Map<String, dynamic> injected,
  ) {
    if (injected.isEmpty) {
      return;
    }
    final providers = rawConfig[section];
    rawConfig[section] = {
      ...injected,
      if (providers is Map) ...providers.cast<String, dynamic>(),
    };
  }

  if (data.rawConfigJson == null) {
    _applyNativeProxyProviderEdits(rawConfig, overrides);
  }
  String resourcePath(Map<String, dynamic> item) {
    final relative = item['file'];
    if (relative is! String ||
        !RegExp(
          r'^profiles/providers/[1-9][0-9]*/(?:rules|proxies)/[0-9a-f]{32}$',
        ).hasMatch(relative))
      throw StateError(
        'nativeResourceInputs.file: invalid private resource path',
      );
    return join(dirname(profilesPath), relative);
  }

  final nativeInputs = overrides.nativeResourceInputs;
  if (nativeInputs['\$.__migration-input-index'] case final index?)
    rawConfig['aerove-native-resource-manifest'] = resourcePath(index);
  final pathReplacements = <String, String>{};
  for (final entry in nativeInputs.entries) {
    final record = entry.value['nativeRecord'];
    if (record is Map &&
        record['kind'] == 'private-file' &&
        record['localPath'] is String) {
      final original = record['localPath'] as String;
      final target = resourcePath(entry.value);
      if (pathReplacements.containsKey(original) &&
          pathReplacements[original] != target)
        throw StateError(
          'nativeResourceInputs.private-file: ambiguous original private path',
        );
      pathReplacements[original] = target;
    }
  }
  dynamic replacePrivate(dynamic value) => switch (value) {
    Map() => {
      for (final entry in value.entries)
        entry.key:
            ['tls-cert', 'tls-key', 'ca'].contains(entry.key) &&
                entry.value is String
            ? pathReplacements[entry.value] ?? entry.value
            : replacePrivate(entry.value),
    },
    List() => value.map(replacePrivate).toList(),
    _ => value,
  };
  if (rawConfig['proxies'] is List)
    rawConfig['proxies'] = replacePrivate(rawConfig['proxies']);
  for (final field in ['mmdb', 'asn-mmdb', 'geosite']) {
    final item = nativeInputs['\$.$field'];
    if (item == null) continue;
    final record = item['nativeRecord'];
    final sourceUrl = record is Map ? record['url'] : null;
    final urlKey = field == 'asn-mmdb' ? 'asn' : field;
    final currentUrl = capabilities.androidHostTun
        ? (rawConfig['geodata'] as Map)['url'][urlKey]
        : rawConfig['$field-download-url'] ??
              realPatchConfig.geoXUrl.raw[urlKey];
    if (sourceUrl != null && currentUrl != null)
      rawConfig['$field-download-url'] = currentUrl;
    if (sourceUrl != null && sourceUrl != currentUrl) continue;
    if (sourceUrl == null &&
        record is Map &&
        rawConfig[field] != record['localPath'])
      continue;
    rawConfig[field] = resourcePath(item);
  }

  confineProviders('proxy-providers', proxiesProviderDirectoryName);
  confineProviders('rule-providers', rulesProviderDirectoryName);
  injectUnconfinedProviders('proxy-providers', data.injectedProxyProviders);
  injectUnconfinedProviders('rule-providers', data.injectedRuleProviders);
  if (!capabilities.androidHostTun)
    rawConfig['profile']['store-selected'] = false;
  if (!capabilities.androidHostTun)
    rawConfig['geox-url'] = realPatchConfig.geoXUrl.raw;
  rawConfig['global-ua'] = realPatchConfig.globalUa ?? defaultUA;
  if (rawConfig['hosts'] == null) {
    rawConfig['hosts'] = {};
  }
  for (final host in realPatchConfig.hosts.entries) {
    rawConfig['hosts'][host.key] = host.value.splitByMultipleSeparators;
  }
  for (final host in overrides.hosts.entries) {
    rawConfig['hosts'][host.key] = host.value;
  }
  var rawDns = rawConfig['dns'] is Map
      ? Map<String, dynamic>.from(rawConfig['dns'] as Map)
      : <String, dynamic>{};
  final isEnableDns = rawDns['enable'] == true;
  const systemDns = 'system://';
  if (!isEnableDns) {
    rawDns = mergeDnsOverride(
      rawDns,
      baselineDns.overrideJson(baselineDnsOverrideKeys),
    );
  }
  rawDns = mergeDnsOverride(
    rawDns,
    overrides.dns.overrideJson(
      capabilities.androidHostTun
          ? _meowDnsOverrideKeys(overrides.dns, overrides.dnsOverrideKeys)
          : overrides.dnsOverrideKeys,
      keepEmpty: data.keepEmptyDnsOverrides,
    ),
  );
  rawConfig['dns'] = rawDns;
  final ntpOverride = overrides.ntp.overrideJson(overrides.ntpOverrideKeys);
  if (ntpOverride.isNotEmpty) {
    final rawNtp = rawConfig['ntp'] is Map
        ? Map<String, dynamic>.from(rawConfig['ntp'] as Map)
        : <String, dynamic>{};
    rawConfig['ntp'] = {...rawNtp, ...ntpOverride};
  }
  if (appendSystemDns) {
    final List<String> nameserver = List<String>.from(
      rawConfig['dns']['nameserver'] ?? [],
    );
    if (!nameserver.contains(systemDns)) {
      rawConfig['dns']['nameserver'] = [...nameserver, systemDns];
    }
  }
  if (data.safeMode) {
    if (capabilities.androidHostTun &&
        rawConfig.containsKey('listeners') &&
        !emptyList(rawConfig['listeners']))
      throw const FormatException(
        '\$.listeners: source listener policy conflicts with client safe mode',
      );
    rawConfig['listeners'] = [];
    mihomoInactive('tunnels', [], emptyList);
    mihomoInactive('ss-config', '', emptyText);
    mihomoInactive('vmess-config', '', emptyText);
    mihomoInactive('tuic-server', {'enable': false}, disabledMap);
    mihomoInactive('iptables', {'enable': false}, disabledMap);
    rawConfig['dns']['listen'] = '';
    final rawNtp = rawConfig['ntp'];
    if (!capabilities.androidHostTun && rawNtp is Map) {
      rawConfig['ntp'] = {
        ...Map<String, dynamic>.from(rawNtp),
        NtpOverrideKey.writeToSystem.path: false,
      };
    }
  }
  List<String> rules = [];
  if (data.rules.isEmpty) {
    if (rawConfig['rules'] != null) {
      rules = List<String>.from(rawConfig['rules']);
    }
    if (addedRules.isNotEmpty) {
      final hasMatchPlaceholder = addedRules.any(
        (item) => item.ruleTarget?.toUpperCase() == 'MATCH',
      );
      String? replacementTarget = data.matchTarget?.trim();
      if (replacementTarget?.isEmpty == true) {
        replacementTarget = null;
      }

      if (hasMatchPlaceholder && replacementTarget == null) {
        replacementTarget = lastMatchTarget(rules);
      }
      final List<String> finalAddedRules;

      if (replacementTarget?.isNotEmpty == true) {
        finalAddedRules = [];
        for (int i = 0; i < addedRules.length; i++) {
          final parsed = addedRules[i];
          if (parsed.ruleTarget?.toUpperCase() == 'MATCH') {
            finalAddedRules.add(
              parsed.copyWith(ruleTarget: replacementTarget).rawValue,
            );
          } else {
            finalAddedRules.add(addedRules[i].rawValue);
          }
        }
      } else {
        finalAddedRules = addedRules.map((e) => e.rawValue).toList();
      }
      rules = [...finalAddedRules, ...rules];
    }
  } else {
    rules = data.rules.map((item) => item.rawValue).toList();
  }
  if (data.proxyGroups.isNotEmpty) {
    rawConfig['proxy-groups'] = data.proxyGroups
        .map((item) => item.definition)
        .toList();
  }
  rawConfig['rules'] = rules;
  final yaml = await _encodeYaml(Map<String, dynamic>.from(rawConfig));
  return (
    yaml: yaml,
    md5: yaml.toMd5(),
    counts: configCountsOf(rawConfig),
    selectionPolicy: selectionPolicy,
  );
}

typedef ShakingStoreArgs = ({
  Iterable<int> profileIds,
  Iterable<int> scriptIds,
  Iterable<String> providerFileNames,
});

Future<List<String>> shakingProfileTask(ShakingStoreArgs data) async {
  return compute<
    ({ShakingStoreArgs args, RootIsolateToken token}),
    List<String>
  >(_shakingProfileTask, (args: data, token: RootIsolateToken.instance!));
}

Future<List<String>> _shakingProfileTask(
  ({ShakingStoreArgs args, RootIsolateToken token}) data,
) async {
  BackgroundIsolateBinaryMessenger.ensureInitialized(data.token);
  return shakeOrphanFiles(
    profileIds: data.args.profileIds,
    scriptIds: data.args.scriptIds,
    providerFileNames: data.args.providerFileNames,
    profilesDirPath: await appPath.profilesPath,
    providersDirPath: await appPath.getProvidersRootPath(),
    providerCacheDirPath: await appPath.providerCacheRootPath,
    scriptsDirPath: await appPath.scriptsDirPath,
  );
}

@visibleForTesting
List<String> shakeOrphanFiles({
  required Iterable<int> profileIds,
  required Iterable<int> scriptIds,
  required Iterable<String> providerFileNames,
  required String profilesDirPath,
  required String providersDirPath,
  required String providerCacheDirPath,
  required String scriptsDirPath,
}) {
  final List<String> targets = [];
  void scanDirectory(
    Directory dir,
    bool Function(String baseName) isLive, {
    bool includeDirectories = false,
  }) {
    if (!dir.existsSync()) return;
    final entities = dir.listSync(recursive: false, followLinks: false);

    for (final entity in entities) {
      final selected =
          entity is File || (includeDirectories && entity is Directory);
      if (!selected) {
        continue;
      }
      if (!isLive(basenameWithoutExtension(entity.path))) {
        targets.add(entity.path);
      }
    }
  }

  bool Function(String) isLiveId(Iterable<int> ids) =>
      (baseName) => ids.contains(int.tryParse(baseName));

  scanDirectory(Directory(profilesDirPath), isLiveId(profileIds));
  scanDirectory(
    Directory(providersDirPath),
    isLiveId(profileIds),
    includeDirectories: true,
  );
  scanDirectory(
    Directory(join(providerCacheDirPath, rulesProviderDirectoryName)),
    providerFileNames.toSet().contains,
  );
  scanDirectory(Directory(scriptsDirPath), isLiveId(scriptIds));
  return targets;
}

// Best-effort: a legacy url shared by two providers, or any rename failure,
// just leaves the file to be re-downloaded under the new path.
void _migrateLegacyProviderFile({
  required String legacyPath,
  required String newPath,
}) {
  if (legacyPath == newPath || File(newPath).existsSync()) {
    return;
  }
  try {
    final legacyFile = File(legacyPath);
    if (legacyFile.existsSync()) {
      Directory(dirname(newPath)).createSync(recursive: true);
      legacyFile.renameSync(newPath);
    }
  } catch (_) {}
}

Future<String> encodeLogsTask(List<Log> data) async {
  return compute<List<Log>, String>(_encodeLogsTask, data);
}

Future<String> _encodeLogsTask(List<Log> data) async {
  final logsRaw = data.map((item) => item.toString());
  final logsRawString = logsRaw.join('\n');
  return logsRawString;
}

Future<MigrationData> oldToNowTask(Map<String, Object?> data) async {
  final homeDir = await appPath.homeDirPath;
  return compute<
    ({Map<String, Object?> configMap, String sourcePath, String targetPath}),
    MigrationData
  >(_oldToNowTask, (configMap: data, sourcePath: homeDir, targetPath: homeDir));
}

Future<MigrationData> _oldToNowTask(
  ({Map<String, Object?> configMap, String sourcePath, String targetPath}) data,
) {
  return migrateLegacyConfig(
    configMap: data.configMap,
    sourcePath: data.sourcePath,
    targetPath: data.targetPath,
  );
}

Future<MigrationData> migrateLegacyConfig({
  required Map<String, Object?> configMap,
  required String sourcePath,
  required String targetPath,
}) async {
  final accessControlMap = configMap['accessControl'];
  final isAccessControl = configMap['isAccessControl'];
  if (accessControlMap != null) {
    (accessControlMap as Map)['enable'] = isAccessControl;
    if (configMap['vpnProps'] != null) {
      final vpnPropsRaw = configMap['vpnProps'] as Map;
      vpnPropsRaw['accessControl'] = accessControlMap;
    }
  }
  if (configMap['vpnProps'] != null) {
    final vpnPropsRaw = configMap['vpnProps'] as Map;
    vpnPropsRaw['accessControlProps'] = vpnPropsRaw['accessControl'];
  }
  configMap['davProps'] = configMap['dav'];
  final appSettingProps =
      configMap['appSetting'] as Map<String, dynamic>? ?? {};
  appSettingProps['restoreStrategy'] = appSettingProps['recoveryStrategy'];
  configMap['appSettingProps'] = appSettingProps;
  configMap['proxiesStyleProps'] = configMap['proxiesStyle'];
  List rawScripts = configMap['scripts'] as List<dynamic>? ?? [];
  if (rawScripts.isEmpty) {
    final scriptPropsJson = configMap['scriptProps'] as Map<String, dynamic>?;
    if (scriptPropsJson != null) {
      rawScripts = scriptPropsJson['scripts'] as List<dynamic>? ?? [];
    }
  }
  final Map<String, int> idMap = {};
  final List<Script> scripts = [];
  for (final rawScript in rawScripts) {
    final id = rawScript['id'] as String?;
    final content = rawScript['content'] as String?;
    final label = rawScript['label'] as String?;
    if (id == null || content == null || label == null) {
      continue;
    }
    final newId = idMap.updateCacheValue(rawScript['id'], () => snowflake.id);
    final path = BackupEntries.resolve(targetPath, BackupEntries.script(newId));
    final file = File(path);
    await file.safeWriteAsString(content);
    scripts.add(
      Script(id: newId, label: label, lastUpdateTime: DateTime.now()),
    );
  }
  final List rawRules = configMap['rules'] as List<dynamic>? ?? [];
  final List<Rule> rules = [];
  final List<DisabledRule> disabledRules = [];
  for (final rawRule in rawRules) {
    final id = idMap.updateCacheValue(rawRule['id'], () => snowflake.id);
    rawRule['id'] = id;
    final value = rawRule['value'] ?? '';
    rules.add(Rule.parse(value, id: id));
  }
  final List rawProfiles = configMap['profiles'] as List<dynamic>? ?? [];
  final List<Profile> profiles = [];
  for (final rawProfile in rawProfiles) {
    final rawId = rawProfile['id'] as String?;
    if (rawId == null) {
      continue;
    }
    final profileId = idMap.updateCacheValue(rawId, () => snowflake.id);
    rawProfile['id'] = profileId;
    final overwrite = rawProfile['overwrite'] as Map?;
    if (overwrite != null) {
      final standardOverwrite = overwrite['standardOverwrite'] as Map?;
      if (standardOverwrite != null) {
        final addedRules = standardOverwrite['addedRules'] as List? ?? [];
        for (final addRule in addedRules) {
          final id = idMap.updateCacheValue(addRule['id'], () => snowflake.id);
          final value = addRule['value'] ?? '';
          rules.add(Rule.parse(value, id: id).copyWith(profileId: profileId));
        }
        final disabledRuleIds = standardOverwrite['disabledRuleIds'] as List?;
        if (disabledRuleIds != null) {
          for (final disabledRuleId in disabledRuleIds) {
            final newDisabledRuleId = idMap[disabledRuleId];
            if (newDisabledRuleId != null) {
              disabledRules.add(
                DisabledRule(profileId: profileId, ruleId: newDisabledRuleId),
              );
            }
          }
        }
      }
      final scriptOverwrite = overwrite['scriptOverwrite'] as Map?;
      if (scriptOverwrite != null) {
        final scriptId = scriptOverwrite['scriptId'] as String?;
        rawProfile['scriptId'] = scriptId != null ? idMap[scriptId] : null;
      }
      rawProfile['extendType'] = overwrite['type'] == ExtendType.script.name
          ? ExtendType.script.name
          : ExtendType.standard.name;
    }
    final rawUrl = rawProfile['url'];
    rawProfile['type'] = rawUrl is String && rawUrl.isNotEmpty ? 'url' : 'file';

    final sourceFile = File(
      BackupEntries.resolve(sourcePath, BackupEntries.profile(rawId)),
    );
    final targetFilePath = BackupEntries.resolve(
      targetPath,
      BackupEntries.profile(profileId),
    );
    await sourceFile.safeCopy(targetFilePath);
    profiles.add(Profile.fromJson(rawProfile));
  }
  final currentProfileId = configMap['currentProfileId'];
  configMap['currentProfileId'] = currentProfileId != null
      ? idMap[currentProfileId]
      : null;
  return MigrationData(
    configMap: configMap,
    profiles: profiles,
    rules: rules,
    scripts: scripts,
    disabledRules: disabledRules,
  );
}
