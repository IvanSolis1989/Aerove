import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:collection/collection.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/migration.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/models/models.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

import 'native_lite_mapping.dart';
import 'file_provider_source.dart';
import 'rule_sql_journal.dart';
import 'task.dart' show meowGlobalGeoDownloadUrls;

abstract interface class NativeLiteAuthority {
  Future<void> validateSource(String sourceDigest);
  Future<void> commit(File receipt, String receiptDigest);
  Future<void> prepareFiles(File plan, String digest);
  Future<void> confirmTransferred(String sourceDigest, String receiptDigest);
}

Future<void> nativeLitePrivateDestination(
  File target,
  Directory boundary,
) async {
  final root = boundary.absolute.path;
  final absolute = target.absolute.path;
  if (!p.isWithin(root, absolute) ||
      p.split(absolute).any((part) => part == '.' || part == '..'))
    legacyFailure('target.files', 'destination escapes the private data root');
  var cursor = target.absolute;
  while (p.isWithin(root, cursor.path)) {
    if (await FileSystemEntity.type(cursor.path, followLinks: false) ==
        FileSystemEntityType.link)
      legacyFailure('target.files', 'symbolic destination or ancestor');
    cursor = File(p.dirname(cursor.path));
  }
  final canonical = await boundary.resolveSymbolicLinks();
  var parent = target.parent;
  while (!await parent.exists()) {
    parent = parent.parent;
  }
  if (!p.equals(
    await parent.resolveSymbolicLinks(),
    p.join(canonical, p.relative(parent.path, from: root)),
  ))
    legacyFailure('target.files', 'indirect destination ancestor');
}

Future<void> nativeLiteAtomicFile(
  File target,
  List<int> bytes, {
  required Directory boundary,
}) async {
  await nativeLitePrivateDestination(target, boundary);
  await target.parent.create(recursive: true);
  final pending = File('${target.path}.pending');
  await nativeLitePrivateDestination(pending, boundary);
  await pending.writeAsBytes(bytes, flush: true);
  if (sha256.convert(await pending.readAsBytes()) != sha256.convert(bytes)) {
    legacyFailure('target.files', 'private staging read-back failed');
  }
  await pending.rename(target.path);
  if (sha256.convert(await target.readAsBytes()) != sha256.convert(bytes)) {
    legacyFailure('target.files', 'atomic publication read-back failed');
  }
}

class NativeLiteMigration {
  NativeLiteMigration({
    required this.home,
    required this.database,
    required this.preferences,
    required this.defaults,
    required this.authority,
    required this.createId,
  });
  final Directory home;
  final Database database;
  final SharedPreferences preferences;
  final XClashDefaults defaults;
  final NativeLiteAuthority authority;
  final int Function() createId;
  Directory get _directory =>
      Directory(p.join(home.path, 'native-lite-migration'));
  File get _journal => File(p.join(_directory.path, 'journal.json'));

  Future<void> _save(Map<String, dynamic> state) => nativeLiteAtomicFile(
    _journal,
    utf8.encode(jsonEncode(state)),
    boundary: home,
  );

  Future<void> run(NativeLiteExport incoming) async {
    final owner = RuleSqlJournalOwner(database, home);
    return owner.exclusive(() async {
      await owner.recover();
      await owner.externalJournal(() => _runSource(incoming));
    });
  }

  Future<void> _runSource(NativeLiteExport incoming) async {
    await nativeLitePrivateDestination(_journal, home);
    Map<String, dynamic> state;
    NativeLiteExport source;
    if (await _journal.exists()) {
      state = legacyObject(
        jsonDecode(await _journal.readAsString()),
        'migration.journal',
      );
      if (state['schema'] != 1 || state['sourceDigest'] != incoming.digest) {
        legacyFailure(
          'migration.journal.sourceDigest',
          'another legacy source owns the migration',
        );
      }
      source = await NativeLiteExport.open(
        File(p.join(_directory.path, 'source', 'manifest.json')),
        incoming.digest,
      );
      if (state['phase'] == 'complete') {
        await authority.confirmTransferred(
          source.digest,
          state['receiptSha256'] as String,
        );
        await _verifyCommitted(state, verifyTarget: false);
        return;
      }
    } else {
      for (final table in database.businessTables) {
        final rows = await database
            .customSelect('SELECT 1 FROM "${table.actualTableName}" LIMIT 1')
            .get();
        if (rows.isNotEmpty)
          legacyFailure('target.database', 'Native and shared data both exist');
      }
      if (preferences.getString(configKey) != null)
        legacyFailure(
          'target.config',
          'existing shared configuration has no migration journal',
        );
      await authority.validateSource(incoming.digest);
      final savedSource = Directory(p.join(_directory.path, 'source'));
      await nativeLitePrivateDestination(
        File(p.join(savedSource.path, 'manifest.json')),
        home,
      );
      if (!await savedSource.exists()) {
        final staging = Directory(p.join(_directory.path, 'source.pending'));
        await nativeLitePrivateDestination(
          File(p.join(staging.path, 'manifest.json')),
          home,
        );
        if (await staging.exists()) {
          await for (final entry in staging.list(
            recursive: true,
            followLinks: false,
          )) {
            if (entry is Link)
              legacyFailure(
                'migration.source.pending',
                'symbolic staging input',
              );
          }
          await staging.delete(recursive: true);
        }
        await staging.create(recursive: true);
        for (final path in incoming.records.keys) {
          final original = await incoming.source(path);
          final target = File(p.join(staging.path, 'payload', path));
          await nativeLitePrivateDestination(target, home);
          await target.parent.create(recursive: true);
          await original.copy(target.path);
        }
        await File(
          p.join(incoming.directory.path, 'manifest.json'),
        ).copy(p.join(staging.path, 'manifest.json'));
        await NativeLiteExport.open(
          File(p.join(staging.path, 'manifest.json')),
          incoming.digest,
        );
        await staging.rename(savedSource.path);
      }
      source = await NativeLiteExport.open(
        File(p.join(_directory.path, 'source', 'manifest.json')),
        incoming.digest,
      );
      state = {
        'schema': 1,
        'sourceDigest': incoming.digest,
        'phase': 'prepared',
        'ids': <String, int>{},
        'databaseSchema': database.schemaVersion,
        'beforeDatabase': await nativeLiteDatabaseProof(database),
        'beforePreferences': {
          for (final key in [configKey, 'version', 'upgradeVersion'])
            key: preferences.get(key),
        },
        'beforeDefaults':
            await File(p.join(home.path, 'xclash-defaults.json')).exists()
            ? base64Encode(
                await File(
                  p.join(home.path, 'xclash-defaults.json'),
                ).readAsBytes(),
              )
            : null,
      };
      await _save(state);
    }
    final ids = NativeLiteIds(
      Map<String, int>.from(legacyObject(state['ids'], 'migration.ids')),
      createId,
    );
    final plan = await mapNativeLite(source, ids);
    state['ids'] = ids.values;
    state['legacyConsent'] = plan.legacyConsent;
    await _save(state);
    final planned = <Map<String, dynamic>>[];
    for (final item in plan.files) {
      final bytes = item.bytes ?? await item.source!.readAsBytes();
      planned.add({
        'path': item.path,
        'sha256': sha256.convert(bytes).toString(),
        'bytes': bytes.length,
      });
    }
    final filePlan = File(p.join(_directory.path, 'target-files.json'));
    await nativeLiteAtomicFile(
      filePlan,
      utf8.encode(
        jsonEncode({
          'schema': 1,
          'sourceDigest': source.digest,
          'files': planned,
        }),
      ),
      boundary: home,
    );
    await authority.prepareFiles(
      filePlan,
      sha256.convert(await filePlan.readAsBytes()).toString(),
    );
    final installed = <Map<String, dynamic>>[];
    for (final item in plan.files) {
      final bytes = item.bytes ?? await item.source!.readAsBytes();
      final path = p.join(home.path, item.path);
      final target = File(path);
      final hash = sha256.convert(bytes).toString();
      if (await target.exists() &&
          sha256.convert(await target.readAsBytes()).toString() != hash) {
        legacyFailure(
          'target.files',
          'existing file conflicts with the durable ID map',
        );
      }
      if (!await target.exists() && state.containsKey('expectedDatabase'))
        legacyFailure(
          'target.files',
          'installed target disappeared during an incomplete transfer',
        );
      if (!await target.exists())
        await nativeLiteAtomicFile(target, bytes, boundary: home);
      installed.add({'path': item.path, 'sha256': hash, 'bytes': bytes.length});
    }
    state['files'] = installed;
    state['phase'] = 'filesInstalled';
    await _save(state);
    await authority.validateSource(source.digest);
    final currentProof = await nativeLiteDatabaseProof(database);
    final legacy22Allowed =
        !state.containsKey('databaseSchema') || state['databaseSchema'] == 22;
    if (state.containsKey('databaseSchema') &&
        state['databaseSchema'] != 22 &&
        state['databaseSchema'] != 23)
      legacyFailure(
        'migration.databaseSchema',
        'unsupported persistent database proof version',
      );
    final matchesBefore = _nativeMigrationDatabaseProofMatches(
      currentProof,
      state['beforeDatabase'],
      legacy22Allowed: legacy22Allowed,
    );
    final matchesExpected = _nativeMigrationDatabaseProofMatches(
      currentProof,
      state['expectedDatabase'],
      legacy22Allowed: legacy22Allowed,
    );
    if (!matchesBefore && !matchesExpected) {
      legacyFailure(
        'target.database',
        'shared data changed during an incomplete transfer',
      );
    }
    if (!matchesExpected) {
      await database.transaction(() async {
        await database.restore(
          plan.data.profiles,
          plan.data.scripts,
          plan.data.rules,
          plan.data.disabledRules,
          plan.data.proxyGroups,
          customProxies: plan.data.customProxies,
          clashProviders: plan.data.clashProviders,
          proxyDialers: plan.data.proxyDialers,
        );
        await database.iconSetsDao.putAll(
          plan.iconSets.map((item) => item.toCompanion()),
        );
        void sameRows<T>(Iterable<T> actual, List<T> expected, String field) {
          final rows = actual.toList();
          if (rows.length != expected.length || !rows.every(expected.contains))
            legacyFailure(field, 'structured database read-back failed');
        }

        sameRows(
          (await database.select(database.rules).get()).map(
            (row) => row.toRule(),
          ),
          plan.data.rules,
          'target.rules',
        );
        sameRows(
          (await database.select(database.disabledRules).get()).map(
            (row) => row.toDisabledRule(),
          ),
          plan.data.disabledRules,
          'target.disabledRules',
        );
        sameRows(
          (await database.select(database.proxyGroups).get()).map(
            (row) => row.toProxyGroup(),
          ),
          plan.data.proxyGroups,
          'target.proxyGroups',
        );
        sameRows(
          await database.customProxiesDao.query().get(),
          plan.data.customProxies,
          'target.customProxies',
        );
        sameRows(
          await database.clashProvidersDao.query().get(),
          plan.data.clashProviders,
          'target.clashProviders',
        );
        sameRows(
          await database.iconSetsDao.query().get(),
          plan.iconSets,
          'target.iconSets',
        );
        sameRows(
          (await database.select(database.proxyDialers).get()).map(
            (row) => row.toProxyDialer(),
          ),
          plan.data.proxyDialers,
          'target.proxyDialers',
        );
        final violations = await database
            .customSelect('PRAGMA foreign_key_check')
            .get();
        if (violations.isNotEmpty)
          legacyFailure('target.database', 'foreign-key read-back failed');
        final profiles = await database.profilesDao.query().get();
        final scripts = await database.scriptsDao.query().get();
        if (profiles.length != plan.data.profiles.length ||
            scripts.length != plan.data.scripts.length ||
            !profiles.every((row) => plan.data.profiles.contains(row)) ||
            !scripts.every((row) => plan.data.scripts.contains(row))) {
          legacyFailure('target.database', 'profile/script read-back failed');
        }
        // Publish the intended database proof before SQLite commits. After a crash
        // the durable database must match either the original or this exact state.
        state['expectedDatabase'] = await nativeLiteDatabaseProof(database);
        await _save(state);
      });
    }
    state['phase'] = 'databaseCommitted';
    await _save(state);
    // DAV serialization deliberately uses a fresh nonce. Reuse the durable
    // intended bytes on retries after checking the decoded editable model.
    final savedConfig = state['expectedConfig'] as String?;
    if (savedConfig != null &&
        Config.strictFromJson(
              Map<String, Object?>.from(jsonDecode(savedConfig) as Map),
            ) !=
            plan.config) {
      legacyFailure(
        'migration.expectedConfig',
        'source mapping changed during an incomplete transfer',
      );
    }
    final encodedConfig = savedConfig ?? jsonEncode(plan.config);
    state['expectedConfig'] = encodedConfig;
    await _save(state);
    final beforePreferences = legacyObject(
      state['beforePreferences'],
      'migration.beforePreferences',
    );
    for (final entry in <String, Object?>{
      configKey: encodedConfig,
      'version': Migration.currentVersion,
      'upgradeVersion': Migration.upgradeVersion,
    }.entries) {
      final current = preferences.get(entry.key);
      if (current != beforePreferences[entry.key] && current != entry.value) {
        legacyFailure(
          'target.preferences.${entry.key}',
          'shared setting changed during an incomplete transfer',
        );
      }
    }
    if (!await preferences.setString(configKey, encodedConfig) ||
        !await preferences.setInt('version', Migration.currentVersion) ||
        !await preferences.setInt('upgradeVersion', Migration.upgradeVersion) ||
        preferences.getString(configKey) != encodedConfig) {
      legacyFailure(
        'target.config',
        'durable preferences save/read-back failed',
      );
    }
    await defaults.adoptNativeLibrary(
      sourceDigest: source.digest,
      scriptId: plan.bundledScriptId,
      nativeBundledScriptInitialized: plan.bundledScriptInitialized,
    );
    state['phase'] = 'preferencesCommitted';
    await _save(state);
    final receipt = <String, dynamic>{
      'schema': 1,
      'databaseSchema': database.schemaVersion,
      'sourceDigest': source.digest,
      'configSha256': sha256
          .convert(utf8.encode(preferences.getString(configKey)!))
          .toString(),
      'version': preferences.getInt('version'),
      'upgradeVersion': preferences.getInt('upgradeVersion'),
      'defaultsSha256': sha256
          .convert(
            await File(p.join(home.path, 'xclash-defaults.json')).readAsBytes(),
          )
          .toString(),
      'files': installed,
      'tables': await nativeLiteDatabaseProof(database),
    };
    final receiptFile = File(p.join(_directory.path, 'target-receipt.json'));
    await nativeLiteAtomicFile(
      receiptFile,
      utf8.encode(jsonEncode(receipt)),
      boundary: home,
    );
    state['receiptSha256'] = sha256
        .convert(await receiptFile.readAsBytes())
        .toString();
    await _save(state);
    await authority.commit(receiptFile, state['receiptSha256'] as String);
    state['phase'] = 'complete';
    await _save(state);
    await _verifyCommitted(state);
  }

  Future<void> _verifyCommitted(
    Map<String, dynamic> state, {
    bool verifyTarget = true,
  }) async {
    final receiptFile = File(p.join(_directory.path, 'target-receipt.json'));
    if (sha256.convert(await receiptFile.readAsBytes()).toString() !=
        state['receiptSha256']) {
      legacyFailure('migration.receipt', 'committed receipt changed');
    }
    if (!verifyTarget) return;
    final receipt = legacyObject(
      jsonDecode(await receiptFile.readAsString()),
      'migration.receipt',
    );
    if (sha256
                .convert(utf8.encode(preferences.getString(configKey) ?? ''))
                .toString() !=
            receipt['configSha256'] ||
        !await _nativeReceiptTablesMatch(database, receipt)) {
      legacyFailure(
        'migration.receipt',
        'target no longer matches the prepared transfer',
      );
    }
  }
}

Future<Map<String, dynamic>> nativeLiteDatabaseProof(Database database) async {
  await database.ruleSqlInfrastructureProof(resolved: true);
  final result = <String, dynamic>{};
  final names =
      database.allTables.map((table) => table.actualTableName).toList()..sort();
  for (final name in names) {
    final columns =
        (await database.customSelect('PRAGMA table_info("$name")').get())
            .map((row) => row.data['name'] as String)
            .toList();
    final rows = await database
        .customSelect('SELECT * FROM "$name" ORDER BY rowid')
        .get();
    final content = StringBuffer();
    String cell(Object? value) {
      if (value == null) return 'n;';
      if (value is int) return 'i$value;';
      if (value is double) {
        final bytes = ByteData(8)..setFloat64(0, value);
        return 'd${bytes.getUint64(0).toRadixString(16).padLeft(16, '0')};';
      }
      if (value is List<int>) return 'b${base64Encode(value)};';
      if (value is String) return 't${utf8.encode(value).length}:$value;';
      legacyFailure('target.database', 'unsupported SQLite value');
    }

    for (final column in columns) {
      content.write(cell(column));
    }
    for (final row in rows) {
      content.write('r;');
      for (final column in columns) {
        content.write(cell(row.data[column]));
      }
    }
    result[name] = {
      'rows': rows.length,
      'sha256': sha256.convert(utf8.encode(content.toString())).toString(),
    };
  }
  return result;
}

Object? nativeLiteCanonical(Object? value) {
  if (value is String) return 's:${base64Encode(utf8.encode(value))}';
  if (value is int) return 'i:$value';
  if (value is num) {
    if (value == value.truncateToDouble())
      return "i:$value".replaceFirst(RegExp(r"\.0$"), "");
    final bytes = ByteData(8)..setFloat64(0, value.toDouble());
    return "d:${bytes.getUint64(0).toRadixString(16).padLeft(16, '0')}";
  }
  if (value is Map) {
    final keys = value.keys.cast<String>().toList()..sort();
    return {
      for (final key in keys)
        base64Encode(utf8.encode(key)): nativeLiteCanonical(value[key]),
    };
  }
  if (value is List) return value.map(nativeLiteCanonical).toList();
  return value;
}

String nativeLiteJsonHash(Object? value) => sha256
    .convert(utf8.encode(jsonEncode(nativeLiteCanonical(value))))
    .toString();

Map<String, dynamic> nativeLiteRuntimeConfig(
  Map<String, dynamic> rawConfig,
  Set<int> filterIds,
) {
  final config = Map<String, dynamic>.from(
    jsonDecode(jsonEncode(rawConfig)) as Map,
  );
  final app = config['appSettingProps'] as Map;
  return {
    for (final key in [
      'currentProfileId',
      'patchClashConfig',
      'networkProps',
      'vpnProps',
      'excludeSSIDs',
    ])
      key: config[key],
    'appSettingProps': {
      for (final key in ['testUrl', 'checkCertificate']) key: app[key],
      'filters': [
        for (final filter in app['filters'] as List)
          if (filterIds.contains((filter as Map)['id'])) filter,
      ],
    },
  };
}

const nativeLiteLiveKeys = [
  'tun',
  'mixed-port',
  'allow-lan',
  'find-process-mode',
  'mode',
  'log-level',
  'ipv6',
  'tcp-concurrent',
  'external-controller',
  'secret',
  'unified-delay',
  'geo-auto-update',
  'geo-update-interval',
  'geox-url',
];

Map<String, dynamic> nativeLiteSetupConfig(Map<String, dynamic> runtime) {
  final original = Map<String, dynamic>.from(
    runtime['patchClashConfig'] as Map,
  );
  final patch = {...original}
    ..removeWhere((key, _) => nativeLiteLiveKeys.contains(key));
  patch['tun'] = Map<String, dynamic>.from(original['tun'] as Map)
    ..removeWhere((key, _) => ['enable', 'stack'].contains(key));
  final network = Map<String, dynamic>.from(runtime['networkProps'] as Map)
    ..remove('authentication');
  final app = Map<String, dynamic>.from(runtime['appSettingProps'] as Map)
    ..remove('checkCertificate');
  return {
    ...runtime,
    'patchClashConfig': patch,
    'networkProps': network,
    'appSettingProps': app,
  };
}

/// The verifier rebuilds this same closure from SQLite, never caller-selected IDs.
Map<String, List<Map<String, dynamic>>> nativeLiteSelectedRows(
  Map<String, List<Map<String, dynamic>>> all,
  int? id,
) {
  if (id == null) {
    return {for (final table in all.keys) table: <Map<String, dynamic>>[]};
  }
  final selected = all['profiles']!.where((row) => row['id'] == id).toList();
  if (selected.length != 1)
    legacyFailure('runtime.profile', 'selected profile is missing');
  final profile = selected.single;
  final custom = profile['type'] == 'custom';
  Map<String, dynamic> object(Object? value) => value == null
      ? {}
      : Map<String, dynamic>.from(jsonDecode(value as String) as Map);
  final overrides = object(profile['overrides']);
  final standard = profile['extend_type'] == 'standard';
  final globalRules =
      standard && (!custom || overrides['inheritGlobalRules'] == true);
  final rules = all['rules']!
      .where(
        (row) =>
            (custom || standard) &&
            (row['profile_id'] == id ||
                globalRules && row['profile_id'] == null),
      )
      .toList();
  final groups = custom
      ? all['proxy_groups']!.where((row) => row['profile_id'] == id).toList()
      : <Map<String, dynamic>>[];
  final usedProfiles = <String>{
    for (final group in groups)
      ...List<String>.from(jsonDecode(group['use'] as String? ?? '[]') as List),
  };
  String label(Map<String, dynamic> row) {
    final label = row['label'] as String;
    if (label.isNotEmpty) return label;
    final url = Uri.tryParse(row['url'] as String);
    return url?.host.isNotEmpty == true ? url!.host : '${row['id']}';
  }

  final ordered = [...all['profiles']!]
    ..sort((a, b) {
      final x = a['order'] as int?;
      final y = b['order'] as int?;
      return x == y
          ? (a['id'] as int).compareTo(b['id'] as int)
          : x == null
          ? 1
          : y == null
          ? -1
          : x.compareTo(y);
    });
  final providers = {
    for (final row in ordered)
      if (row['type'] != 'custom') label(row): row,
  };
  final profileIds = <int>{
    id,
    for (final name in usedProfiles)
      if (providers[name] != null) providers[name]!['id'] as int,
  };
  final scope = overrides['scopedCustomProxyIds'] as List?;
  final owned = {
    for (final row in all['profiles']!)
      ...?object(row['overrides'])['ownedCustomProxyIds'] as List?,
  };
  final proxies = custom
      ? all['custom_proxies']!
            .where(
              (row) => scope == null
                  ? !owned.contains(row['id'])
                  : scope.contains(row['id']),
            )
            .toList()
      : <Map<String, dynamic>>[];
  final ruleLabels = <String>{
    for (final row in rules)
      if (row['rule_provider'] is String) row['rule_provider'] as String,
  };
  final bindings = overrides['ruleProviderBindings'] as Map? ?? {};
  ruleLabels.addAll(bindings.keys.cast<String>());
  // DNS rule-set dependencies and nested sub-rules remain visible in their source definitions.
  void references(Object? value) {
    if (value is Map) {
      for (final entry in value.entries) {
        if (entry.key.toString().startsWith('rule-set:'))
          ruleLabels.addAll(entry.key.toString().substring(9).split(','));
        references(entry.value);
      }
    }
    if (value is List) {
      for (final child in value) {
        references(child);
      }
    }
    if (value is String) {
      for (final match in RegExp(
        r'(?:^|\()\s*RULE-SET\s*,\s*([^,()]+)',
        caseSensitive: false,
      ).allMatches(value)) {
        ruleLabels.add(match[1]!.trim());
      }
      if (value.startsWith('rule-set:'))
        ruleLabels.addAll(value.substring(9).split(':').first.split(','));
    }
  }

  references(overrides);
  for (final row in rules) {
    references(row['content']);
  }
  final iconIds = {
    for (final item in (overrides['iconBindings'] as Map? ?? {}).values)
      (item as Map)['set'],
  };
  final scriptId =
      profile['extend_type'] == 'script' &&
          (!custom || overrides['applyExtensionToCustom'] == true)
      ? profile['script_id']
      : null;
  return {
    'profiles': all['profiles']!
        .where((row) => profileIds.contains(row['id']))
        .toList(),
    'scripts': all['scripts']!.where((row) => row['id'] == scriptId).toList(),
    'rules': rules,
    'disabled_rules': all['disabled_rules']!
        .where((row) => (custom || standard) && row['profile_id'] == id)
        .toList(),
    'proxy_groups': groups,
    'custom_proxies': proxies,
    'proxy_dialers': all['proxy_dialers']!
        .where((row) => custom && row['profile_id'] == id)
        .toList(),
    'clash_providers': all['clash_providers']!
        .where(
          (row) =>
              custom &&
              (bindings.values.contains(row['id']) ||
                  ruleLabels.contains(row['label']) &&
                      !(overrides['sourceRuleProviders'] as Map? ?? {})
                          .containsKey(row['label'])),
        )
        .toList(),
    'icon_sets': all['icon_sets']!
        .where((row) => custom && iconIds.contains(row['id']))
        .toList(),
  };
}

Future<Map<String, dynamic>> _nativeLiteBusinessSource(
  Database db,
  Directory home,
  SharedPreferences storage,
  Config? expected,
) async {
  await storage.reload();
  var encoded = storage.getString(configKey);
  if (expected != null &&
      (encoded == null ||
          Config.strictFromJson(
                Map<String, Object?>.from(jsonDecode(encoded) as Map),
              ) !=
              expected)) {
    encoded = jsonEncode(expected);
    if (!await storage.setString(configKey, encoded))
      legacyFailure('runtime.config', 'durable preference save failed');
    await storage.reload();
    if (storage.getString(configKey) != encoded)
      legacyFailure('runtime.config', 'durable preference read-back failed');
  }
  if (encoded == null)
    legacyFailure('runtime.config', 'shared configuration is missing');
  final config = Map<String, dynamic>.from(jsonDecode(encoded) as Map);
  if (!config.containsKey('currentProfileId') ||
      config['currentProfileId'] != null &&
          config['currentProfileId'] is! int) {
    legacyFailure('runtime.profile', 'invalid profile selection');
  }
  final selectedId = config['currentProfileId'] as int?;
  final rows = await db.transaction(() async {
    final all = <String, List<Map<String, dynamic>>>{};
    for (final name in [
      'profiles',
      'scripts',
      'rules',
      'disabled_rules',
      'proxy_groups',
      'custom_proxies',
      'proxy_dialers',
      'clash_providers',
      'icon_sets',
    ]) {
      all[name] =
          (await db.customSelect('SELECT * FROM "$name" ORDER BY rowid').get())
              .map((row) => row.data)
              .toList();
    }
    return nativeLiteSelectedRows(all, selectedId);
  });
  final profile = selectedId == null
      ? null
      : rows['profiles']!.singleWhere((row) => row['id'] == selectedId);
  final overrides = jsonDecode(profile?['overrides'] as String? ?? '{}') as Map;
  final filterIds = {
    for (final value in (overrides['filterBindings'] as Map? ?? {}).values)
      ...List<int>.from(value as List),
  };
  final paths = <String>{
    for (final profile in rows['profiles']!)
      if (profile['type'] != 'custom') 'profiles/${profile['id']}.yaml',
    for (final script in rows['scripts']!) 'scripts/${script['id']}.js',
    for (final provider in rows['clash_providers']!)
      'providers/rules/${md5.convert(utf8.encode('${provider['id']}@${provider['url']}'))}',
  };
  void resources(Object? value) {
    if (value is Map) {
      for (final entry in value.entries) {
        if (entry.key == 'nativeResourceInputs') {
          for (final input in (entry.value as Map).values) {
            for (final key in ['file', 'receiptFile']) {
              if ((input as Map)[key] case final String path) paths.add(path);
            }
          }
        } else if (entry.key == 'localProviderSources') {
          // Only the owned source is active. originalDefinition is historical
          // lineage and may refer to an intentionally detached old resource.
          for (final binding in (entry.value as Map).values) {
            paths.add((binding as Map)['relativePath'] as String);
          }
        } else if ([
              'path',
              'tls-cert',
              'tls-key',
              'ca',
              'mmdb-path',
              'asn-path',
              'geosite-path',
            ].contains(entry.key) &&
            entry.value is String) {
          final raw = entry.value as String;
          if (raw.isNotEmpty) {
            final relative = p.isAbsolute(raw)
                ? p.relative(raw, from: home.path).replaceAll('\\', '/')
                : raw;
            paths.add(relative);
          }
        } else {
          resources(entry.value);
        }
      }
    }
    if (value is List) {
      for (final item in value) {
        resources(item);
      }
    }
  }

  for (final row in rows['profiles']!) {
    resources(jsonDecode(row['overrides'] as String? ?? '{}'));
  }
  paths.addAll(
    await fileProviderSourceBackupEntriesTask(
      home.path,
      rows['profiles']!.map((row) => row['id'] as int),
    ),
  );
  resources(config['patchClashConfig']);
  for (final row in rows['custom_proxies']!) {
    resources(jsonDecode(row['definition'] as String));
  }
  final files = <Map<String, Object>>[];
  final canonicalHome = await home.resolveSymbolicLinks();
  for (final path in paths.toList()..sort()) {
    final file = File(p.join(home.path, path));
    if (!p.isWithin(home.path, file.path) ||
        !await file.exists() ||
        !p.equals(
          await file.resolveSymbolicLinks(),
          p.join(canonicalHome, path),
        ))
      legacyFailure('runtime.files', 'business input is missing or indirect');
    final bytes = await file.readAsBytes();
    files.add({
      'path': path,
      'bytes': bytes.length,
      'sha256': sha256.convert(bytes).toString(),
    });
  }
  final runtime = nativeLiteRuntimeConfig(config, filterIds);
  final model = Config.strictFromJson(config);
  final live = model.patchClashConfig
      .toUpdateParams(
        coreId: 'meow-rs',
        authentication: model.networkProps.authentication.credentials,
        skipCertVerify: !model.appSettingProps.checkCertificate,
      )
      .copyWith(geoXUrl: meowGlobalGeoDownloadUrls(model.patchClashConfig))
      .toJson();
  return {
    'schema': 3,
    'ruleSql': await db.ruleSqlInfrastructureProof(resolved: true),
    'setupSha256': nativeLiteJsonHash(nativeLiteSetupConfig(runtime)),
    'liveSha256': nativeLiteJsonHash(live),
    'databaseSchema': db.schemaVersion,
    'configSha256': nativeLiteJsonHash(
      nativeLiteRuntimeConfig(config, filterIds),
    ),
    'tables': {
      for (final entry in rows.entries)
        entry.key: {
          'rows': entry.value.length,
          'sha256': nativeLiteJsonHash(entry.value),
        },
    },
    'files': files,
  };
}

Future<void> finishNativeLiteTransfer(
  Directory home,
  String sourceDigest,
  String receiptDigest,
) async {
  final directory = Directory(p.join(home.path, 'native-lite-migration'));
  final journal = File(p.join(directory.path, 'journal.json'));
  if (!await journal.exists())
    legacyFailure(
      'migration.journal',
      'Native authority transferred without the recovery journal',
    );
  final state = legacyObject(
    jsonDecode(await journal.readAsString()),
    'migration.journal',
  );
  final receipt = File(p.join(directory.path, 'target-receipt.json'));
  if (state['sourceDigest'] != sourceDigest ||
      state['receiptSha256'] != receiptDigest ||
      !await receipt.exists() ||
      sha256.convert(await receipt.readAsBytes()).toString() != receiptDigest) {
    legacyFailure(
      'migration.receipt',
      'Native committed identity does not match the private recovery journal',
    );
  }
  if (state['phase'] != 'complete') {
    state['phase'] = 'complete';
    await nativeLiteAtomicFile(
      journal,
      utf8.encode(jsonEncode(state)),
      boundary: home,
    );
  }
}

/// A request owns a bounded immutable file; active config.yaml is never overwritten.
Future<MeowGeoSource> nativeLiteWriteGeoSource(
  ({String home, String transactionId, String body}) request,
) async {
  if (!RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
  ).hasMatch(request.transactionId)) {
    throw const FormatException('Invalid Geo request identity');
  }
  final bytes = utf8.encode(request.body);
  if (bytes.isEmpty || bytes.length > 4 * 1024 * 1024)
    throw const FormatException('Geo producer body exceeds its budget');
  final boundary = Directory(request.home);
  final directory = Directory(p.join(request.home, 'geo-requests'));
  final file = File(p.join(directory.path, '${request.transactionId}.yaml'));
  await nativeLitePrivateDestination(file, boundary);
  await directory.create(recursive: true);
  await file.create(exclusive: true);
  try {
    await file.writeAsBytes(bytes, flush: true);
    final actual = await file.readAsBytes();
    final digest = sha256.convert(bytes).toString();
    if (actual.length != bytes.length ||
        sha256.convert(actual).toString() != digest)
      throw StateError('Geo producer readback differs');
    return MeowGeoSource(path: file.path, sha256: digest, bytes: bytes.length);
  } catch (_) {
    await file.delete();
    rethrow;
  }
}

Future<bool> _nativeReceiptTablesMatch(
  Database database,
  Map<String, dynamic> receipt,
) async {
  final actual = await nativeLiteDatabaseProof(database);
  final expected = Map<String, dynamic>.from(receipt['tables'] as Map);
  if (receipt['databaseSchema'] == 22 && database.schemaVersion == 23) {
    if (!const SetEquality<String>().equals(
      expected.keys.toSet(),
      ruleSqlBusinessTables,
    ))
      return false;
    return nativeLiteJsonHash({
          for (final name in ruleSqlBusinessTables) name: actual[name],
        }) ==
        nativeLiteJsonHash(expected);
  }
  return receipt['databaseSchema'] == 23 &&
      nativeLiteJsonHash(actual) == nativeLiteJsonHash(expected);
}

Future<Map<String, dynamic>> nativeLiteBusinessSource(
  Database db,
  Directory home,
  SharedPreferences storage,
  Config? expected,
) async {
  final owner = RuleSqlJournalOwner(db, home);
  return owner.exclusive(() async {
    if (!owner.inExternalJournal)
      await owner.recover();
    else
      await db.ruleSqlInfrastructureProof(resolved: true);
    return _nativeLiteBusinessSource(db, home, storage, expected);
  });
}

// Old schema1 migration journals predate databaseSchema. Their exact eleven-table
// proof identifies schema22; schema23 infrastructure is independently checked by
// nativeLiteDatabaseProof before this bounded projection is permitted.
bool _nativeMigrationDatabaseProofMatches(
  Map<String, dynamic> actual,
  Object? value, {
  required bool legacy22Allowed,
}) {
  if (value is! Map) return false;
  final expected = Map<String, dynamic>.from(value);
  final keys = expected.keys.toSet();
  final old = const SetEquality<String>().equals(keys, ruleSqlBusinessTables);
  final current = const SetEquality<String>().equals(keys, {
    ...ruleSqlBusinessTables,
    ...ruleSqlInfrastructureTables,
  });
  if (!current && !(legacy22Allowed && old)) return false;
  for (final proof in expected.values) {
    if (proof is! Map ||
        !const SetEquality<String>().equals(proof.keys.cast<String>().toSet(), {
          'rows',
          'sha256',
        }) ||
        proof['rows'] is! int ||
        (proof['rows'] as int) < 0 ||
        proof['sha256'] is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(proof['sha256'] as String))
      return false;
  }
  return nativeLiteJsonHash(expected) ==
      nativeLiteJsonHash({for (final name in keys) name: actual[name]});
}
