import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/indexing.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:path/path.dart' as p;
import 'package:yaml/yaml.dart';

import 'native_lite_settings.dart';

part 'native_lite_builder.dart';
part 'native_lite_resources.dart';

class NativeLiteMigrationException implements Exception {
  const NativeLiteMigrationException(this.field, this.reason);
  final String field;
  final String reason;
  @override
  String toString() => 'Native Lite migration: $field: $reason';
}

Never legacyFailure(String field, String reason) =>
    throw NativeLiteMigrationException(field, reason);

dynamic legacyJson(String text, String field) {
  try {
    return jsonDecode(text);
  } catch (_) {
    legacyFailure(field, 'invalid JSON source');
  }
}

Map<String, dynamic> legacyObject(Object? value, String field) => value is Map
    ? Map<String, dynamic>.from(value)
    : legacyFailure(field, 'expected object');
List<dynamic> legacyList(Object? value, String field) =>
    value is List ? value : legacyFailure(field, 'expected list');
String legacyText(Object? value, String field) =>
    value is String ? value : legacyFailure(field, 'expected text');
int legacyInt(Object? value, String field) =>
    value is int ? value : legacyFailure(field, 'expected integer');
bool legacyBool(Object? value, String field) =>
    value is bool ? value : legacyFailure(field, 'expected boolean');

class NativeLiteExport {
  NativeLiteExport._(this.directory, this.digest, this.records);
  final Directory directory;
  final String digest;
  final Map<String, Map<String, dynamic>> records;

  static Future<NativeLiteExport> open(
    File manifest,
    String expectedDigest,
  ) async {
    final root = manifest.parent.absolute;
    if (await root.resolveSymbolicLinks() != p.normalize(root.path) ||
        await FileSystemEntity.isLink(manifest.path)) {
      legacyFailure('manifest', 'unsafe export path');
    }
    final bytes = await manifest.readAsBytes();
    if (bytes.length > 8 * 1024 * 1024 ||
        sha256.convert(bytes).toString() != expectedDigest) {
      legacyFailure('manifest', 'checksum mismatch');
    }
    final json = legacyObject(
      legacyJson(utf8.decode(bytes), 'manifest'),
      'manifest',
    );
    if (json['format'] != 'aerove-lite-native-source-export' ||
        json['schema'] != 1 ||
        json['migrationComplete'] != false) {
      legacyFailure('manifest', 'unsupported export identity');
    }
    final rows = legacyList(json['files'], 'manifest.files');
    if (rows.length > 12000)
      legacyFailure('manifest.files', 'too many records');
    final records = <String, Map<String, dynamic>>{};
    var total = 0;
    for (final value in rows) {
      final row = legacyObject(value, 'manifest.files');
      final path = legacyText(row['path'], 'manifest.files.path');
      if (p.isAbsolute(path) ||
          path.contains('\\') ||
          path.contains(':') ||
          path
              .split('/')
              .any((part) => part.isEmpty || part == '.' || part == '..') ||
          records.containsKey(path)) {
        legacyFailure('manifest.files.path', 'ambiguous relative path');
      }
      final size = legacyInt(row['bytes'], 'manifest.files.bytes');
      total += size;
      if (size < 0 || size > 64 * 1024 * 1024 || total > 512 * 1024 * 1024) {
        legacyFailure('manifest.files', 'size limit');
      }
      records[path] = row;
    }
    final result = NativeLiteExport._(root, expectedDigest, records);
    await result.verify();
    return result;
  }

  Future<File> source(String path) async {
    if (!records.containsKey(path))
      legacyFailure(path, 'missing source record');
    final file = File(p.join(directory.path, 'payload', path));
    final expected = p.normalize(file.absolute.path);
    if (await FileSystemEntity.type(file.path, followLinks: false) !=
        FileSystemEntityType.file)
      legacyFailure(path, 'source payload is missing or indirect');
    if (await FileSystemEntity.isLink(file.path) ||
        await file.resolveSymbolicLinks() != expected ||
        !p.isWithin(p.join(directory.path, 'payload'), expected)) {
      legacyFailure(path, 'unsafe source path');
    }
    return file;
  }

  Future<void> verify() async {
    for (final entry in records.entries) {
      final file = await source(entry.key);
      if (await file.length() != entry.value['bytes'] ||
          (await sha256.bind(file.openRead()).first).toString() !=
              entry.value['sha256']) {
        legacyFailure(entry.key, 'original payload changed');
      }
    }
  }

  Future<Map<String, dynamic>?> json(String path) async =>
      !records.containsKey(path)
      ? null
      : legacyObject(
          legacyJson(await (await source(path)).readAsString(), path),
          path,
        );
  @override
  String toString() => 'NativeLiteExport([private])';
}

class NativeLiteIds {
  NativeLiteIds(this.values, this.create);
  final Map<String, int> values;
  final int Function() create;
  int id(String key) => values.putIfAbsent(key, () {
    final next = create();
    if (next <= 0 || values.containsValue(next))
      legacyFailure('idMap', 'duplicate generated identity');
    return next;
  });
}

class NativeLiteFile {
  const NativeLiteFile.copy(this.path, this.source) : bytes = null;
  const NativeLiteFile.bytes(this.path, this.bytes) : source = null;
  final String path;
  final File? source;
  final Uint8List? bytes;
}

class NativeLitePlan {
  NativeLitePlan(
    this.data,
    this.config,
    this.files,
    this.bundledScriptId,
    this.legacyConsent,
    this.iconSets,
    this.bundledScriptInitialized,
  );
  final MigrationData data;
  final Config config;
  final List<NativeLiteFile> files;
  final int? bundledScriptId;
  final bool bundledScriptInitialized;
  final Object? legacyConsent;
  final List<IconSet> iconSets;
  @override
  String toString() => 'NativeLitePlan([private])';
}

Future<NativeLitePlan> mapNativeLite(
  NativeLiteExport source,
  NativeLiteIds ids,
) async {
  if (!source.records.containsKey('profiles/index.json') &&
      source.records.keys.any((path) => path.startsWith('profiles/')))
    legacyFailure(
      'profiles/index.json',
      'profile source exists without its identity index',
    );
  final index =
      await source.json('profiles/index.json') ??
      {'schema': 1, 'profiles': [], 'selected': null};
  if (index['schema'] != 1)
    legacyFailure('profiles/index.json.schema', 'unsupported schema');
  final library =
      await source.json('settings/overrides.json') ??
      {'version': 1, 'scripts': [], 'profiles': [], 'globalRules': []};
  if (library['version'] != 1)
    legacyFailure('settings/overrides.json.version', 'unsupported schema');
  final profileRows = legacyList(
    index['profiles'],
    'profiles/index.json.profiles',
  ).map((row) => legacyObject(row, 'profiles')).toList();
  final profileIds = <String, int>{};
  for (final row in profileRows) {
    final uuid = legacyText(row['id'], 'profiles.id');
    if (!RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ).hasMatch(uuid) ||
        profileIds.containsKey(uuid)) {
      legacyFailure('profiles.id', 'invalid or duplicate UUID');
    }
    profileIds[uuid] = ids.id('profile:$uuid');
  }
  final files = <NativeLiteFile>[];
  final scripts = <Script>[];
  final scriptIds = <String, int>{};
  for (final (position, raw) in legacyList(
    library['scripts'],
    'overrides.scripts',
  ).indexed) {
    final row = legacyObject(raw, 'overrides.scripts');
    final key = legacyText(row['id'], 'scripts.id');
    if (scriptIds.containsKey(key))
      legacyFailure('scripts.id', 'duplicate identity');
    final id = ids.id('script:$key');
    scriptIds[key] = id;
    final content = legacyText(row['content'], 'scripts.content');
    final bytes = Uint8List.fromList(utf8.encode(content));
    if (utf8.decode(bytes) != content)
      legacyFailure('scripts.content', 'text is not lossless UTF8');
    files.add(NativeLiteFile.bytes('scripts/$id.js', bytes));
    scripts.add(
      Script(
        id: id,
        label: legacyText(row['name'], 'scripts.name'),
        url: row['url'] == null ? null : legacyText(row['url'], 'scripts.url'),
        order: position,
        lastUpdateTime: DateTime.fromMillisecondsSinceEpoch(
          legacyInt(row['updatedAt'], 'scripts.updatedAt'),
        ),
      ),
    );
  }
  final overrides = <String, Map<String, dynamic>>{};
  for (final raw in legacyList(library['profiles'], 'overrides.profiles')) {
    final row = legacyObject(raw, 'overrides.profiles');
    final key = legacyText(row['profileId'], 'overrides.profileId');
    if (!profileIds.containsKey(key) || overrides.containsKey(key))
      legacyFailure(
        'overrides.profileId',
        'missing or duplicate profile binding',
      );
    overrides[key] = row;
  }
  final rules = <Rule>[];
  final ruleIds = <String, int>{};
  final globals = legacyList(library['globalRules'], 'overrides.globalRules');
  final globalOrder = indexing.generateNKeys(globals.length);
  Rule rule(String text, String key, int? profileId, String? order) {
    final decoded = decodeRuleList(text);
    if (decoded.length != 1) legacyFailure(key, 'expected one valid rule');
    final result = decoded.single.rule;
    final canonical = text.split(',').map((item) => item.trim()).join(',');
    if (result.rawValue != canonical)
      legacyFailure(key, 'rule parameters cannot round-trip');
    return result.copyWith(id: ids.id(key), profileId: profileId, order: order);
  }

  for (final (position, raw) in globals.indexed) {
    final row = legacyObject(raw, 'globalRules');
    final key = legacyText(row['id'], 'globalRules.id');
    if (ruleIds.containsKey(key))
      legacyFailure('globalRules.id', 'duplicate identity');
    final value = rule(
      legacyText(row['value'], 'globalRules.value'),
      'global-rule:$key',
      null,
      globalOrder[position],
    );
    ruleIds[key] = value.id;
    rules.add(value);
  }
  final customProxies = <CustomProxy>[];
  final builderFilters = <Filter>[];
  Map<String, dynamic> builderLibrary = {};
  final iconSets = <IconSet>[];
  final clashProviders = <ClashProvider>[];
  if (source.records.containsKey('settings/builder-resources.yaml')) {
    Map<String, dynamic> builder;
    try {
      builder = legacyObject(
        jsonDecode(
          jsonEncode(
            loadYaml(
              await (await source.source(
                'settings/builder-resources.yaml',
              )).readAsString(),
            ),
          ),
        ),
        'builder',
      );
    } catch (error) {
      if (error is NativeLiteMigrationException) rethrow;
      legacyFailure('settings/builder-resources.yaml', 'invalid Builder YAML');
    }
    builderLibrary = builder;
    if (builder['schema'] != 2)
      legacyFailure('builder.schema', 'unsupported Builder source schema');
    for (final (position, raw) in legacyList(
      builder['proxies'],
      'builder.proxies',
    ).indexed) {
      final row = legacyObject(raw, 'builder.proxies');
      final key = legacyText(row['id'], 'builder.proxies.id');
      customProxies.add(
        CustomProxy(
          id: ids.id('builder-proxy:$key'),
          definition: {
            ...legacyObject(row['fields'], 'builder.proxies.fields'),
            'name': legacyText(row['name'], 'builder.proxies.name'),
          },
          order: indexing.generateNKeys(
            (builder['proxies'] as List).length,
          )[position],
        ),
      );
    }
    for (final raw in legacyList(builder['filters'], 'builder.filters')) {
      final row = legacyObject(raw, 'builder.filters');
      builderFilters.add(
        Filter(
          id: ids.id(
            'builder-filter:${legacyText(row['id'], 'builder.filters.id')}',
          ),
          label: legacyText(row['label'], 'builder.filters.label'),
          regex: legacyText(row['regex'], 'builder.filters.regex'),
        ),
      );
    }

    for (final (position, raw) in legacyList(
      builder['icon-sets'],
      'builder.icon-sets',
    ).indexed) {
      final row = legacyObject(raw, 'builder.icon-sets');
      iconSets.add(
        IconSet(
          id: ids.id(
            'builder-icon:${legacyText(row['id'], 'builder.icon-sets.id')}',
          ),
          name: legacyText(row['name'], 'builder.icon-sets.name'),
          url: legacyText(row['url'], 'builder.icon-sets.url'),
          order: position,
          lastUpdateTime: row['updated-at'] == null
              ? null
              : DateTime.fromMillisecondsSinceEpoch(
                  legacyInt(row['updated-at'], 'builder.icon-sets.updated-at'),
                ),
          icons: [
            for (final icon in legacyList(
              row['icons'],
              'builder.icon-sets.icons',
            ))
              IconSetIcon.fromJson(
                legacyObject(icon, 'builder.icon-sets.icons'),
              ),
          ],
        ),
      );
    }
    for (final (position, raw) in legacyList(
      builder['rule-providers'],
      'builder.rule-providers',
    ).indexed) {
      final row = legacyObject(raw, 'builder.rule-providers');
      final fields = legacyObject(
        row['fields'],
        'builder.rule-providers.fields',
      );
      final name = legacyText(row['name'], 'builder.rule-providers.name');
      final behavior = fields['behavior'] ?? 'domain';
      final format = fields['format'] ?? 'yaml';
      if (!RuleProviderBehavior.values.any((v) => v.name == behavior) ||
          !RuleProviderFormat.values.any((v) => v.name == format))
        legacyFailure(
          'builder.rule-providers.behavior/format',
          'unsupported resource schema',
        );
      if (fields['type'] != 'http' && fields['type'] != 'file')
        legacyFailure(
          'builder.rule-providers.type',
          'unsupported resource kind',
        );
      final provider = ClashProvider(
        id: ids.id(
          'builder-provider:${legacyText(row['id'], 'builder.rule-providers.id')}',
        ),
        label: name,
        url: fields['type'] == 'http'
            ? legacyText(fields['url'], 'builder.rule-providers.url')
            : '',
        behavior: RuleProviderBehavior.values.byName(behavior as String),
        format: RuleProviderFormat.values.byName(format as String),
        sourceDefinition: fields,
        order: position,
      );
      clashProviders.add(provider);
      final matches = <Map<String, dynamic>>[];
      final localId =
          (fields['path'] as String?)?.startsWith('.library/') == true
          ? (fields['path'] as String).substring(9)
          : null;
      for (final path in source.records.keys.where(
        (path) => RegExp(
          r'^runtime/resources/library/[0-9a-f]{64}\.json$',
        ).hasMatch(path),
      )) {
        final cache = (await source.json(path))!;
        if (cache['kind'] == 'ruleset' &&
            cache['url'] == provider.url &&
            cache['behavior'] == behavior &&
            cache['format'] == format &&
            cache['localId'] == localId)
          matches.add(cache);
      }
      if (matches.length > 1)
        legacyFailure(
          'runtime.resources.library',
          'ambiguous source payload identity',
        );
      if (matches.isEmpty && provider.url.isEmpty)
        legacyFailure(
          'builder.rule-providers.path',
          'local original payload is missing',
        );
      if (matches.isNotEmpty) {
        final cache = matches.single;
        final payload = await source.source(
          'runtime/resources/library/${legacyText(cache['file'], 'runtime.library.file')}',
        );
        if (sha256.convert(await payload.readAsBytes()).toString() !=
                cache['sha256'] ||
            await payload.length() != cache['bytes'])
          legacyFailure(
            'runtime.library.payload',
            'original payload hash mismatch',
          );
        files.add(
          NativeLiteFile.copy('providers/rules/${provider.fileName}', payload),
        );
      }
    }
  }
  final profiles = <Profile>[];
  final proxyGroups = <ProxyGroup>[];
  final disabled = <DisabledRule>[];
  for (final (position, row) in profileRows.indexed) {
    final uuid = row['id'] as String;
    final id = profileIds[uuid]!;
    final override = overrides[uuid];
    final current = legacyText(row['current'], 'profiles.current');
    final original = await source.source('profiles/$current');
    final bytes = await original.readAsBytes();
    final metadata = row['sourceMetadata'] == null
        ? null
        : legacyObject(row['sourceMetadata'], 'profiles.sourceMetadata');
    if (metadata != null &&
        sha256.convert(bytes).toString() != metadata['logicalSourceSha256']) {
      legacyFailure(
        'profiles.sourceMetadata.logicalSourceSha256',
        'original source mismatch',
      );
    }
    String ageKey = '';
    if (metadata?['ageIdentity'] != null) {
      try {
        ageKey = utf8.decode(
          base64Decode(
            legacyText(
              metadata!['ageIdentity'],
              'profiles.sourceMetadata.ageIdentity',
            ),
          ),
        );
      } catch (_) {
        legacyFailure(
          'profiles.sourceMetadata.ageIdentity',
          'identity bytes are not strict UTF8',
        );
      }
    }
    final scriptKey = override?['scriptId'];
    if (scriptKey != null && !scriptIds.containsKey(scriptKey))
      legacyFailure('overrides.scriptId', 'missing script identity');
    final mode = override?['mode'] ?? 'Standard';
    if (mode != 'Standard' && mode != 'Script')
      legacyFailure('overrides.mode', 'unknown extension mode');
    var profileOverrides = nativeProfileOverrides(override?['dnsPreferences']);
    if (row['custom'] == true) {
      final built = await mapNativeLiteBuilder(
        source,
        ids,
        uuid,
        id,
        utf8.decode(bytes),
        profileOverrides,
        customProxies,
        clashProviders,
        builderLibrary,
        rule,
      );
      profileOverrides = built.overrides;
      customProxies.addAll(built.proxies);
      proxyGroups.addAll(built.groups);
      rules.addAll(built.rules);
      files.addAll(built.files);
    }
    final resources = await mapNativeLiteResources(
      source,
      uuid,
      id,
      row,
      bytes,
    );
    files.removeWhere(
      (file) => resources.files.any((other) => other.path == file.path),
    );
    files.addAll(resources.files);
    profileOverrides = profileOverrides.copyWith(
      nativeProfileUuid: uuid,
      nativeResourceInputs: resources.inputs,
      proxyProviders: resources.options,
    );
    profiles.add(
      Profile(
        id: id,
        type: row['custom'] == true
            ? ProfileType.custom
            : row['url'] == null
            ? ProfileType.file
            : ProfileType.url,
        label: legacyText(row['name'], 'profiles.name'),
        url: row['url'] as String? ?? '',
        ageSecretKey: ageKey,
        currentGroupName: row['currentProxyGroup'] as String?,
        unfoldSet: (row['unfoldedProxyGroups'] as List? ?? [])
            .cast<String>()
            .toSet(),
        autoUpdate: row['autoUpdate'] as bool? ?? true,
        autoUpdateDuration: Duration(
          milliseconds: legacyInt(
            row['updateIntervalMillis'] ?? 86400000,
            'profiles.updateIntervalMillis',
          ),
        ),
        lastUpdateDate: DateTime.fromMillisecondsSinceEpoch(
          legacyInt(
            row['lastSuccessfulUpdateAt'] ?? row['updatedAt'],
            'profiles.updatedAt',
          ),
        ),
        subscriptionInfo: row['subscriptionUserInfo'] == null
            ? null
            : SubscriptionInfo.formHString(
                row['subscriptionUserInfo'] as String,
              ).copyWith(observationVersion: 0),
        extendType: mode == 'Script' ? ExtendType.script : ExtendType.standard,
        scriptId: scriptKey == null ? null : scriptIds[scriptKey],
        matchTarget: override?['matchTarget'] as String?,
        order: position,
        overrides: profileOverrides,
      ),
    );
    files.add(NativeLiteFile.copy('profiles/$id.yaml', original));
    final additions = legacyList(
      override?['addedRules'] ?? [],
      'overrides.addedRules',
    );
    final order = indexing.generateNKeys(additions.length);
    for (final (offset, raw) in additions.indexed) {
      final added = rule(
        legacyText(raw, 'overrides.addedRules'),
        'profile-rule:$uuid:$offset',
        id,
        order[offset],
      );
      rules.add(added);
      if (row['custom'] == true) {
        final current = profiles.removeLast();
        profiles.add(
          current.copyWith.overrides(
            preRuleIds: {...current.overrides.preRuleIds, added.id},
          ),
        );
      }
    }
    for (final key in legacyList(
      override?['disabledGlobalRuleIds'] ?? [],
      'overrides.disabledGlobalRuleIds',
    )) {
      if (!ruleIds.containsKey(key))
        legacyFailure(
          'overrides.disabledGlobalRuleIds',
          'missing global rule binding',
        );
      disabled.add(DisabledRule(profileId: id, ruleId: ruleIds[key]!));
    }
  }
  final selected = index['selected'];
  if (selected != null && !profileIds.containsKey(selected))
    legacyFailure('profiles.selected', 'missing selected UUID');
  final rawSettings = await source.json('settings/settings.json');
  final rawDav = await source.json('backups/webdav.json');
  final config = nativeLiteConfig(rawSettings, rawDav).copyWith
      .appSettingProps(filters: builderFilters)
      .copyWith(
        currentProfileId: selected == null ? null : profileIds[selected],
      );
  // Database.restore assigns a single lexicographic sequence to each batch;
  // map that exact sequence while keeping each source profile's row order.
  final ruleOrder = indexing.generateNKeys(rules.length);
  final groupOrder = indexing.generateNKeys(proxyGroups.length);
  final orderedRules = [
    for (final (position, row) in rules.indexed)
      row.copyWith(order: ruleOrder[position]),
  ];
  final orderedGroups = [
    for (final (position, row) in proxyGroups.indexed)
      row.copyWith(order: groupOrder[position]),
  ];
  return NativeLitePlan(
    MigrationData(
      profiles: profiles,
      scripts: scripts,
      rules: orderedRules,
      disabledRules: disabled,
      customProxies: customProxies,
      clashProviders: clashProviders,
      proxyGroups: orderedGroups,
    ),
    config,
    files,
    scriptIds['smart-config-kit-aerove-lite'],
    rawSettings?['acceptedDisclaimerVersion'],
    iconSets,
    library['bundledScriptInitialized'] == true,
  );
}
