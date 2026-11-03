import 'dart:convert';

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';

import 'native_lite_mapping.dart';

Map<String, dynamic> _wire(Object value) =>
    Map<String, dynamic>.from(jsonDecode(jsonEncode(value)) as Map);

Map<String, dynamic> _dns(Object? raw) {
  if (raw == null)
    return {
      'dns': _wire(defaultDns),
      'keys': <DnsOverrideKey>{},
      'hosts': <String, List<String>>{},
    };
  final row = legacyObject(raw, 'dnsPreferences');
  final values = <String, dynamic>{
    'enable': true,
    'listen': '',
    'use-hosts': true,
    'ipv6': true,
    'respect-rules': true,
    'enhanced-mode': 'fake-ip',
    'fake-ip-range': '198.18.0.1/16',
    ...legacyObject(row['values'] ?? {}, 'dnsPreferences.values'),
  };
  final selected = legacyList(
    row['overridden'] ?? [],
    'dnsPreferences.overridden',
  );
  final keys = <DnsOverrideKey>{};
  for (final path in selected) {
    final matches = DnsOverrideKey.values.where((key) => key.path == path);
    if (matches.length != 1)
      legacyFailure('dnsPreferences.overridden', 'unsupported DNS field');
    keys.add(matches.single);
  }
  final nested = <String, dynamic>{};
  for (final entry in values.entries) {
    final parts = entry.key.split('.');
    if (parts.length == 1) {
      nested[entry.key] = entry.value;
    } else if (parts.length == 2) {
      (nested.putIfAbsent(parts.first, () => <String, dynamic>{})
              as Map)[parts.last] =
          entry.value;
    } else {
      legacyFailure('dnsPreferences.values', 'invalid nested DNS field');
    }
  }
  final hosts = <String, List<String>>{};
  for (final entry in legacyObject(
    row['hosts'] ?? {},
    'dnsPreferences.hosts',
  ).entries) {
    final targets = legacyList(entry.value, 'dnsPreferences.hosts');
    if (targets.any((value) => value is! String))
      legacyFailure('dnsPreferences.hosts', 'host address is not text');
    hosts[entry.key] = targets.cast<String>();
  }
  return {'dns': nested, 'keys': keys, 'hosts': hosts};
}

ProfileOverrides nativeProfileOverrides(Object? value) {
  final dns = _dns(value);

  return ProfileOverrides(
    hosts: dns['hosts'] as Map<String, List<String>>,
    dns: Dns.fromJson(Map<String, Object?>.from(dns['dns'] as Map)),
    dnsOverrideKeys: dns['keys'] as Set<DnsOverrideKey>,
  );
}

Config nativeLiteConfig(
  Map<String, dynamic>? native,
  Map<String, dynamic>? dav,
) {
  if (native == null)
    legacyFailure(
      'settings/settings.json',
      'Native preferences are missing; shared defaults cannot replace them',
    );
  if (native['schema'] != 1)
    legacyFailure('settings.schema', 'unsupported schema');
  final base = Config.realFromJson(null, coreId: 'meow-rs');
  final app = _wire(base.appSettingProps);
  final theme = _wire(base.themeProps);
  final vpn = _wire(base.vpnProps);
  final network = _wire(base.networkProps);
  final patch = _wire(base.patchClashConfig);
  void transfer(
    Map<String, dynamic> target,
    Map<String, dynamic> source,
    Map<String, String> fields,
  ) {
    for (final entry in fields.entries) {
      if (source.containsKey(entry.key))
        target[entry.value] = source[entry.key];
    }
  }

  transfer(app, native, {
    'locale': 'locale',
    'dashboardWidgets': 'dashboardWidgets',
    'floatingNavigationBar': 'floatingNavigationBar',
    'tabAnimation': 'tabAnimation',
    'autoCheckUpdate': 'autoCheckUpdate',
    'developerMode': 'developerMode',
    'openLogs': 'openLogs',
    'editorLineWrap': 'editorLineWrap',
    'serviceOrder': 'serviceOrder',
    'disabledServices': 'disabledServices',
    'historyTables': 'tableColumnWidths',
  });
  app['acceptedDisclaimerVersion'] = 0;
  final fontSize = native['editorFontSize'];
  if (fontSize != null) {
    app['editorFontSize'] = switch (fontSize) {
      16 => 'standard',
      18 => 'large',
      20 => 'extraLarge',
      _ => legacyFailure('settings.editorFontSize', 'unsupported size'),
    };
  }
  transfer(theme, native, {
    'themeColors': 'primaryColors',
    'aurora': 'auroraEnabled',
    'pureBlack': 'pureBlack',
    'colorStyle': 'colorStyle',
    'auroraColors': 'auroraColors',
    'auroraSeed': 'auroraSeed',
    'auroraRotationDay': 'auroraRotationDay',
    'fontFamily': 'systemFontFamily',
  });
  theme['primaryColor'] = native['followSystemColor'] == true
      ? null
      : native['themeColor'];
  theme['themeMode'] = switch (native['colorMode']) {
    'System' => 'system',
    'Light' => 'light',
    'Dark' => 'dark',
    _ => legacyFailure('settings.colorMode', 'unknown color mode'),
  };
  theme['liquidGlass'] = switch (native['material']) {
    'Solid' => 'none',
    'Frosted' => 'standard',
    'Clear' => 'ultra',
    _ => legacyFailure('settings.material', 'unknown material'),
  };
  theme['textScale'] = {
    'enable': native['textScaleEnabled'] ?? false,
    'scale': native['textScale'] ?? 1.0,
  };
  final background = legacyObject(
    native['backgroundPolicy'] ?? {},
    'settings.backgroundPolicy',
  );
  if (background.containsKey('autoRun') &&
      native['autoStart'] != background['autoRun'])
    legacyFailure(
      'settings.autoStart/backgroundPolicy.autoRun',
      'stored boot policy facts disagree',
    );
  transfer(app, background, {
    for (final key in [
      'autoRun',
      'minimizeOnExit',
      'hidden',
      'onlyStatisticsProxy',
      'closeConnections',
      'showNotificationStopAction',
    ])
      key: key,
  });
  if (!background.containsKey('autoRun'))
    app['autoRun'] = native['autoStart'] ?? false;
  final requests = legacyObject(native['requests'] ?? {}, 'settings.requests');
  transfer(app, requests, {
    'userAgents': 'userAgents',
    'checkCertificate': 'checkCertificate',
  });
  transfer(patch, requests, {'globalUserAgent': 'global-ua'});
  final presentation = legacyObject(
    native['proxyPresentation'] ?? {},
    'settings.proxyPresentation',
  );
  transfer(app, presentation, {
    'testUrl': 'testUrl',
    'timeoutMillis': 'delayTestTimeout',
  });
  final style = _wire(base.proxiesStyleProps);
  transfer(style, presentation, {
    'type': 'type',
    'sort': 'sortType',
    'layout': 'layout',
    'iconStyle': 'iconStyle',
    'cardType': 'cardType',
    'hideTimeoutProxies': 'hideTimeoutProxies',
  });
  final net = legacyObject(native['network'] ?? {}, 'settings.network');
  transfer(vpn, net, {
    'vpnEnabled': 'enable',
    'systemProxy': 'systemProxy',
    'allowBypass': 'allowBypass',
    'dnsHijacking': 'dnsHijacking',
  });
  if (native.containsKey('ipv6')) vpn['ipv6'] = native['ipv6'];
  transfer(network, net, {
    'systemProxy': 'systemProxy',
    'bypassDomains': 'bypassDomain',
    'bypassPrivateRoute': 'bypassPrivateRoute',
  });
  if (net['authentication'] != null) {
    final auth = legacyObject(
      net['authentication'],
      'settings.network.authentication',
    );
    network['authentication'] = {
      'enable': auth['enabled'],
      'username': auth['username'],
      'password': auth['password'],
    };
  }
  final ports = legacyObject(net['ports'] ?? {}, 'settings.network.ports');
  transfer(patch, ports, {
    'mixed': 'mixed-port',
    'http': 'port',
    'socks': 'socks-port',
  });
  transfer(patch, net, {'allowLan': 'allow-lan'});
  transfer(patch, native, {
    'ipv6': 'ipv6',
    'unifiedDelay': 'unified-delay',
    'tcpConcurrent': 'tcp-concurrent',
    'findProcessMode': 'find-process-mode',
  });
  patch['mode'] = switch (native['mode']) {
    'Rule' => 'rule',
    'Global' => 'global',
    'Direct' => 'direct',
    _ => legacyFailure('settings.mode', 'unknown mode'),
  };
  final tun = legacyObject(patch['tun-override'] ?? {}, 'target.tun');
  transfer(tun, net, {
    'mtu': 'mtu',
    'routeAddress': 'route-address',
    'routeExcludeAddress': 'route-exclude-address',
  });
  patch['tun-override'] = tun;
  patch['tun-override-keys'] = [
    if (net.containsKey('mtu')) 'mtu',
    if (net.containsKey('routeAddress')) 'route-address',
    if (net.containsKey('routeExcludeAddress')) 'route-exclude-address',
  ];
  final appAccess = native['appAccess'];
  if (appAccess != null) {
    final access = legacyObject(appAccess, 'settings.appAccess');
    vpn['accessControlProps'] = {
      'enable': access['enabled'],
      'mode': switch (access['mode']) {
        'Include' || 'include' => 'acceptSelected',
        'Exclude' || 'exclude' => 'rejectSelected',
        _ => legacyFailure('settings.appAccess.mode', 'unknown mode'),
      },
      'acceptList': access['includePackages'],
      'rejectList': access['excludePackages'],
      'sort': (access['sort'] as String?)?.toLowerCase(),
      'isFilterSystemApp': access['hideSystem'],
      'isFilterNonInternetApp': access['hideNoInternet'],
    };
  }
  final controller = legacyObject(
    native['externalController'] ?? {},
    'settings.externalController',
  );
  if (controller.containsKey('enabled'))
    patch['external-controller'] = controller['enabled'] == true
        ? '127.0.0.1:9090'
        : '';
  if (controller.containsKey('secret')) patch['secret'] = controller['secret'];
  final geo = legacyObject(
    native['geoResources'] ?? {},
    'settings.geoResources',
  );
  transfer(patch, geo, {
    'autoUpdate': 'geo-auto-update',
    'intervalHours': 'geo-update-interval',
    'urls': 'geox-url',
  });
  final dns = _dns(native['dnsPreferences']);
  patch['dns'] = dns['dns'];
  patch['dns-override-keys'] = (dns['keys'] as Set<DnsOverrideKey>)
      .map((key) => key.path)
      .toList();
  patch['hosts'] = (dns['hosts'] as Map<String, List<String>>).map(
    (key, values) => MapEntry(key, values.join(', ')),
  );
  final raw = _wire(base);
  raw.addAll({
    'appSettingProps': app,
    'themeProps': theme,
    'vpnProps': vpn,
    'networkProps': network,
    'patchClashConfig': patch,
    'proxiesStyleProps': style,
    'excludeSSIDs': background['excludeSSIDs'] ?? [],
  });
  final result = Config.strictFromJson(raw).copyWith(
    appSettingProps: AppSettingProps.fromJson(app),
    themeProps: ThemeProps.fromJson(theme),
    vpnProps: VpnProps.fromJson(vpn),
    networkProps: NetworkProps.fromJson(network),
    proxiesStyleProps: ProxiesStyleProps.fromJson(style),
    patchClashConfig: PatchClashConfig.fromJson(patch),
    davProps: dav == null
        ? null
        : DAVProps(
            uri: legacyText(dav['url'], 'backups.webdav.url'),
            user: legacyText(dav['user'], 'backups.webdav.user'),
            password: legacyText(dav['password'], 'backups.webdav.password'),
            fileName: legacyText(dav['fileName'], 'backups.webdav.fileName'),
          ),
  );
  return result;
}
