import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart' show DoNothing, Variable;
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/path.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/common/snowflake.dart';
import 'package:fl_clash/common/xclash_script_url.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

export 'xclash_script_url.dart' show xclashDefaultScriptUrl;

const xclashDefaultScriptAsset = 'assets/data/smart-config-kit-flclash.js';
const xclashDefaultScriptSha256 =
    '0f8c6d3c6385e2a22b6435a5ade37c6236f376531749bf446642ce481977f6ec';

XClashDefaults? xclashDefaults;

class XClashDefaults {
  XClashDefaults({
    required SharedPreferences storage,
    required Database database,
    required Directory directory,
    required Future<Uint8List> Function() loadScript,
    required int Function() createId,
    Future<bool> Function(Config)? saveConfig,
  }) : _storage = storage,
       _database = database,
       _directory = directory,
       _loadScript = loadScript,
       _createId = createId,
       _saveConfig = saveConfig ?? preferences.saveConfig;

  final SharedPreferences _storage;
  final Database _database;
  final Directory _directory;
  final Future<Uint8List> Function() _loadScript;
  final int Function() _createId;
  final Future<bool> Function(Config) _saveConfig;
  Map<String, dynamic>? _record;

  int? get recommendedScriptId =>
      _record?['complete'] == true ? _record!['scriptId'] as int : null;

  static Future<XClashDefaults?> open() async {
    final storage = await preferences.sharedPreferences;
    if (storage == null) {
      return null;
    }
    return XClashDefaults(
      storage: storage,
      database: database,
      directory: Directory(await appPath.homeDirPath),
      loadScript: () async {
        final data = await rootBundle.load(xclashDefaultScriptAsset);
        return data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
      },
      createId: () => snowflake.id,
    );
  }

  File get _journal => File(p.join(_directory.path, 'xclash-defaults.json'));

  File _scriptFile(int id) =>
      File(p.join(_directory.path, 'scripts', '$id.js'));

  // This journal stays outside backup entries: deletion and installation state
  // belong to this device, whereas restored profiles retain their own scripts.
  Future<void> prepare({required bool isAndroid}) async {
    if (await _journal.exists()) {
      final record = jsonDecode(await _journal.readAsString());
      if (record is! Map<String, dynamic> ||
          record['scriptId'] is! int ||
          record['freshAndroid'] is! bool ||
          record['complete'] is! bool) {
        throw const FormatException('Invalid XClash initialization journal');
      }
      _record = record;
      return;
    }
    final hasFiles =
        await _directory.exists() &&
        await _directory.list().any(
          (entry) => entry.path != '${_journal.path}.pending',
        );
    final freshAndroid = isAndroid && _storage.getKeys().isEmpty && !hasFiles;
    _record = {
      'scriptId': _createId(),
      'freshAndroid': freshAndroid,
      'legacyDns': _hasLegacyDnsConfig(),
      'complete': false,
    };
    await _writeJournal();
  }

  bool _hasLegacyDnsConfig() {
    try {
      final raw = _storage.getString(configKey);
      if (raw == null) return false;
      final json = jsonDecode(raw);
      if (json is! Map<String, dynamic>) return false;
      final patch = json['patchClashConfig'];
      if (patch is! Map || patch['dns'] is! Map) return false;
      final dns = Dns.fromJson(Map<String, Object?>.from(patch['dns'] as Map));
      final config = Config.strictFromJson(json);
      return dns == defaultDns &&
          config.patchClashConfig.dns == defaultDns &&
          config.patchClashConfig.dnsOverrideKeys.isEmpty;
    } catch (_) {
      return false;
    }
  }

  Future<bool> _hasRows({int? ignoringScriptId}) async {
    for (final table in _database.allTables) {
      final ignore =
          table.actualTableName == 'scripts' && ignoringScriptId != null;
      final rows = await _database
          .customSelect(
            'SELECT 1 FROM "${table.actualTableName}"'
            '${ignore ? ' WHERE id != ?' : ''} LIMIT 1',
            variables: [if (ignore) Variable<int>(ignoringScriptId)],
          )
          .get();
      if (rows.isNotEmpty) {
        return true;
      }
    }
    return false;
  }

  Future<Config> initialize(Config config) async {
    final record = _record;
    if (record == null) {
      throw StateError('XClash defaults must be prepared before migration');
    }
    if (record['complete'] == true) {
      await _database.transaction(
        _database.scriptsDao.migrateLegacyDefaultUrls,
      );
      return config;
    }
    final id = record['scriptId'] as int;
    final existing = await _database.scriptsDao.query().get();
    final installed = existing.get(id);
    final script =
        installed ??
        Script(
          id: id,
          label: existing.uniqueLabel(
            'Smart-Config-Kit',
            fallback: 'Smart-Config-Kit',
          ),
          url: xclashDefaultScriptUrl,
          lastUpdateTime: DateTime.now(),
        );
    final applyPreset =
        record['freshAndroid'] == true && !await _hasRows(ignoringScriptId: id);
    final replaceLegacyDns =
        record['legacyDns'] == true &&
        config.patchClashConfig.dns == defaultDns &&
        config.patchClashConfig.dnsOverrideKeys.isEmpty;
    final next = applyPreset
        ? _androidPreset(config)
        : replaceLegacyDns
        ? config.copyWith.patchClashConfig(
            dns: xclashDefaultDns,
            dnsOverrideKeys: {
              ...xclashDefaultDnsOverrideKeys,
              DnsOverrideKey.defaultNameserver,
            },
          )
        : config;
    if (installed == null && !await _scriptFile(id).exists()) {
      final bytes = utf8.encode(
        utf8.decode(await _loadScript()).replaceAll('\r\n', '\n'),
      );
      if (sha256.convert(bytes).toString() != xclashDefaultScriptSha256) {
        throw StateError(
          'Bundled Smart-Config-Kit script integrity check failed',
        );
      }
      await _writeAtomic(_scriptFile(id), bytes);
    }
    await _database.transaction(() async {
      if (installed == null) {
        await _database
            .into(_database.scripts)
            .insert(
              script.toCompanion(),
              onConflict: DoNothing(target: [_database.scripts.id]),
            );
      }
      await _database.scriptsDao.migrateLegacyDefaultUrls();
      if ((applyPreset || replaceLegacyDns) && !await _saveConfig(next)) {
        throw StateError('Failed to save XClash initial network preferences');
      }
    });
    record['complete'] = true;
    try {
      await _writeJournal();
    } catch (_) {
      record['complete'] = false;
      rethrow;
    }
    return next;
  }

  Future<Profile> associateNewProfile(Profile profile) async {
    final record = _record;
    if (record?['complete'] != true ||
        profile.type == ProfileType.custom ||
        profile.extendType != ExtendType.standard ||
        profile.scriptId != null) {
      return profile;
    }
    final saved = await (_database.select(
      _database.profiles,
    )..where((row) => row.id.equals(profile.id))).getSingleOrNull();
    if (saved != null) {
      return profile;
    }
    final id = record!['scriptId'] as int;
    final script = await _database.scriptsDao.get(id).getSingleOrNull();
    if (script == null || !await _scriptFile(id).exists()) {
      return profile;
    }
    return profile.copyWith(extendType: ExtendType.script, scriptId: id);
  }

  Config _androidPreset(Config config) => config.copyWith(
    networkProps: config.networkProps.copyWith(appendSystemDns: false),
    vpnProps: config.vpnProps.copyWith(
      enable: true,
      systemProxy: true,
      ipv6: true,
      dnsHijacking: false,
      allowBypass: true,
      accessControlProps: config.vpnProps.accessControlProps.copyWith(
        enable: false,
      ),
    ),
    patchClashConfig: config.patchClashConfig.copyWith(
      mode: Mode.rule,
      ipv6: true,
      dns: xclashDefaultDns,
      dnsOverrideKeys: const {
        ...xclashDefaultDnsOverrideKeys,
        DnsOverrideKey.defaultNameserver,
      },
      unifiedDelay: true,
      tcpConcurrent: true,
      keepAliveIdle: 30,
      keepAliveInterval: 15,
    ),
  );

  Future<void> _writeJournal() =>
      _writeAtomic(_journal, utf8.encode(jsonEncode(_record)));

  Future<void> _writeAtomic(File target, List<int> bytes) async {
    await target.parent.create(recursive: true);
    final staging = File('${target.path}.pending');
    await staging.writeAsBytes(bytes, flush: true);
    await staging.rename(target.path);
  }
}
