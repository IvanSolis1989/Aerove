part of 'database.dart';

const ruleSqlBusinessTables = <String>{
  'profiles',
  'scripts',
  'rules',
  'disabled_rules',
  'proxy_groups',
  'icon_records',
  'icon_sets',
  'clash_providers',
  'custom_proxies',
  'proxy_dialers',
  'ip_qualities',
};
const ruleSqlInfrastructureTables = <String>{
  'rule_sql_identity',
  'rule_source_publications',
};

class RuleSqlIdentities extends Table {
  @override
  String get tableName => 'rule_sql_identity';
  IntColumn get slot => integer()();
  TextColumn get incarnation => text()();
  @override
  Set<Column> get primaryKey => {slot};
  @override
  List<String> get customConstraints => ['CHECK (slot = 1)'];
}

class RuleSourcePublications extends Table {
  TextColumn get transactionId => text()();
  TextColumn get incarnation => text()();
  IntColumn get providerId => integer()();
  TextColumn get planSha256 => text()();
  TextColumn get decision => text()();
  IntColumn get schemaVersion => integer().withDefault(const Constant(1))();
  @override
  Set<Column> get primaryKey => {transactionId};
  @override
  List<String> get customConstraints => [
    "CHECK (decision IN ('PREPARED', 'COMMITTED', 'ABORTED'))",
    'CHECK (schema_version = 1)',
  ];
}

extension RuleSqlInfrastructure on Database {
  Iterable<TableInfo> get businessTables => allTables.where(
    (table) => ruleSqlBusinessTables.contains(table.actualTableName),
  );

  Future<String> ruleSqlIncarnation() async {
    final rows = await customSelect(
      'SELECT slot, incarnation FROM rule_sql_identity',
    ).get();
    if (rows.length != 1 || rows.single.read<int>('slot') != 1)
      throw StateError('rule_sql_identity: expected one singleton');
    final value = rows.single.read<String>('incarnation');
    if (!_ruleUuid.hasMatch(value))
      throw StateError('rule_sql_identity: invalid durable identity');
    return value;
  }

  Future<void> resetRuleSqlIncarnation({String? replacement}) async {
    if (replacement != null && !_ruleUuid.hasMatch(replacement))
      throw StateError('Invalid replacement database incarnation');
    final pending = await customSelect(
      "SELECT 1 FROM rule_source_publications WHERE decision = 'PREPARED' LIMIT 1",
    ).get();
    if (pending.isNotEmpty)
      throw StateError(
        'Cannot replace an incarnation with a pending rule publication',
      );
    await customStatement('DELETE FROM rule_source_publications');
    await customStatement(
      'UPDATE rule_sql_identity SET incarnation = ? WHERE slot = 1',
      [replacement ?? uuidV4],
    );
  }

  Future<Map<String, dynamic>> ruleSqlInfrastructureProof({
    bool resolved = false,
    bool initializing = false,
  }) async {
    if (!initializing &&
        (await customSelect(
              'PRAGMA user_version',
            ).get()).single.read<int>('user_version') !=
            23)
      throw StateError('Rule SQL schema version changed');
    final cookie = (await customSelect(
      'PRAGMA schema_version',
    ).get()).single.read<int>('schema_version');
    if (_ruleSchemaCookies[this] != cookie) {
      if ((await customSelect(
        "SELECT name FROM sqlite_master WHERE type IN ('view','trigger')",
      ).get()).isNotEmpty)
        throw StateError('Unexpected SQL views or triggers');
      final names = (await customSelect(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
      ).get()).map((row) => row.read<String>('name')).toSet();
      if (!const SetEquality<String>().equals(names, {
        ...ruleSqlBusinessTables,
        ...ruleSqlInfrastructureTables,
      }))
        throw StateError('schema23: unknown or missing table');
      for (final entry in ruleSqlBusinessColumns.entries) {
        final columns = await customSelect(
          'PRAGMA table_info("${entry.key}")',
        ).get();
        for (final column in columns) {
          final name = column.read<String>('name');
          final flags = ruleSqlBusinessColumnFlags[entry.key]?[name];
          if (flags == null ||
              column.read<int>('notnull') != flags[0] ||
              column.read<int>('pk') != flags[1])
            throw StateError('schema23: unknown business column flags');
        }
        final actual = {
          for (final column in columns)
            column.read<String>('name'): column
                .read<String>('type')
                .toUpperCase(),
        };
        if (!const MapEquality<String, String>().equals(actual, entry.value))
          throw StateError('schema23: unknown business column schema');
      }
      const expected = <String, Map<String, String>>{
        'rule_sql_identity': {'slot': 'INTEGER', 'incarnation': 'TEXT'},
        'rule_source_publications': {
          'transaction_id': 'TEXT',
          'incarnation': 'TEXT',
          'provider_id': 'INTEGER',
          'plan_sha256': 'TEXT',
          'decision': 'TEXT',
          'schema_version': 'INTEGER',
        },
      };
      for (final entry in expected.entries) {
        final columns = await customSelect(
          'PRAGMA table_info("${entry.key}")',
        ).get();
        if (columns.length != entry.value.length)
          throw StateError('${entry.key}: unexpected columns');
        for (final column in columns) {
          final name = column.read<String>('name');
          final key =
              name ==
              (entry.key == 'rule_sql_identity' ? 'slot' : 'transaction_id');
          if (entry.value[name] != column.read<String>('type').toUpperCase() ||
              column.read<int>('notnull') != 1 ||
              column.read<int>('pk') != (key ? 1 : 0))
            throw StateError('${entry.key}: unexpected column $name');
        }
      }
      _ruleSchemaCookies[this] = cookie;
    }
    final incarnation = await ruleSqlIncarnation();
    final rows = await customSelect(
      'SELECT * FROM rule_source_publications ORDER BY transaction_id LIMIT 4097',
    ).get();
    if (rows.length > 4096)
      throw StateError('Too many unresolved rule publications');
    for (final row in rows) {
      if (!_ruleUuid.hasMatch(row.read<String>('transaction_id')) ||
          row.read<String>('incarnation') != incarnation ||
          !RegExp(
            r'^[0-9a-f]{64}$',
          ).hasMatch(row.read<String>('plan_sha256')) ||
          !{
            'PREPARED',
            'COMMITTED',
            'ABORTED',
          }.contains(row.read<String>('decision')) ||
          row.read<int>('schema_version') != 1)
        throw StateError('Invalid rule publication infrastructure');
    }
    if (resolved && rows.isNotEmpty)
      throw StateError('rule_publication_recovery_required');
    return {
      'schema': 1,
      'incarnation': incarnation,
      'tokens': [for (final row in rows) row.data],
    };
  }
}

final _ruleUuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
);

const ruleSqlBusinessColumns = <String, Map<String, String>>{
  'profiles': {
    'id': 'INTEGER',
    'type': 'TEXT',
    'label': 'TEXT',
    'current_group_name': 'TEXT',
    'url': 'TEXT',
    'age_secret_key': 'TEXT',
    'last_update_date': 'INTEGER',
    'last_update_date_millis': 'INTEGER',
    'extend_type': 'TEXT',
    'script_id': 'INTEGER',
    'match_target': 'TEXT',
    'auto_update_duration_millis': 'INTEGER',
    'subscription_info': 'TEXT',
    'auto_update': 'INTEGER',
    'selected_map': 'TEXT',
    'unfold_set': 'TEXT',
    'order': 'INTEGER',
    'overrides': 'TEXT',
  },
  'scripts': {
    'id': 'INTEGER',
    'label': 'TEXT',
    'last_update_time': 'INTEGER',
    'last_update_time_millis': 'INTEGER',
    'url': 'TEXT',
    'order': 'INTEGER',
  },
  'rules': {
    'id': 'INTEGER',
    'profile_id': 'INTEGER',
    'rule_action': 'TEXT',
    'content': 'TEXT',
    'rule_target': 'TEXT',
    'rule_provider': 'TEXT',
    'sub_rule': 'TEXT',
    'no_resolve': 'INTEGER',
    'src': 'INTEGER',
    'order': 'TEXT',
  },
  'disabled_rules': {'profile_id': 'INTEGER', 'rule_id': 'INTEGER'},
  'proxy_groups': {
    'id': 'INTEGER',
    'profile_id': 'INTEGER',
    'name': 'TEXT',
    'type': 'TEXT',
    'proxies': 'TEXT',
    'use': 'TEXT',
    'definition': 'TEXT',
    'order': 'TEXT',
  },
  'icon_records': {'url': 'TEXT', 'last_accessed': 'INTEGER'},
  'icon_sets': {
    'id': 'INTEGER',
    'name': 'TEXT',
    'url': 'TEXT',
    'icons': 'TEXT',
    'last_update_time': 'INTEGER',
    'last_update_time_millis': 'INTEGER',
    'order': 'INTEGER',
  },
  'clash_providers': {
    'id': 'INTEGER',
    'kind': 'TEXT',
    'label': 'TEXT',
    'source_definition': 'TEXT',
    'url': 'TEXT',
    'behavior': 'TEXT',
    'format': 'TEXT',
    'order': 'INTEGER',
  },
  'custom_proxies': {
    'id': 'INTEGER',
    'definition': 'TEXT',
    'order': 'TEXT',
    'empty_options': 'TEXT',
  },
  'proxy_dialers': {
    'profile_id': 'INTEGER',
    'proxy_id': 'INTEGER',
    'target': 'TEXT',
  },
  'ip_qualities': {'ip': 'TEXT', 'quality': 'TEXT', 'checked_at': 'INTEGER'},
};

final _ruleSchemaCookies = Expando<int>();

const ruleSqlBusinessColumnFlags = <String, Map<String, List<int>>>{
  'profiles': {
    'id': [1, 1],
    'type': [1, 0],
    'label': [1, 0],
    'current_group_name': [0, 0],
    'url': [1, 0],
    'age_secret_key': [0, 0],
    'last_update_date': [0, 0],
    'last_update_date_millis': [0, 0],
    'extend_type': [1, 0],
    'script_id': [0, 0],
    'match_target': [0, 0],
    'auto_update_duration_millis': [1, 0],
    'subscription_info': [0, 0],
    'auto_update': [1, 0],
    'selected_map': [1, 0],
    'unfold_set': [1, 0],
    'order': [0, 0],
    'overrides': [0, 0],
  },
  'scripts': {
    'id': [1, 1],
    'label': [1, 0],
    'last_update_time': [1, 0],
    'last_update_time_millis': [0, 0],
    'url': [0, 0],
    'order': [0, 0],
  },
  'rules': {
    'id': [1, 1],
    'profile_id': [0, 0],
    'rule_action': [1, 0],
    'content': [0, 0],
    'rule_target': [0, 0],
    'rule_provider': [0, 0],
    'sub_rule': [0, 0],
    'no_resolve': [1, 0],
    'src': [1, 0],
    'order': [0, 0],
  },
  'disabled_rules': {
    'profile_id': [1, 1],
    'rule_id': [1, 2],
  },
  'proxy_groups': {
    'id': [1, 1],
    'profile_id': [1, 0],
    'name': [1, 0],
    'type': [1, 0],
    'proxies': [0, 0],
    'use': [0, 0],
    'definition': [1, 0],
    'order': [0, 0],
  },
  'icon_records': {
    'url': [1, 1],
    'last_accessed': [1, 0],
  },
  'icon_sets': {
    'id': [1, 1],
    'name': [1, 0],
    'url': [1, 0],
    'icons': [1, 0],
    'last_update_time': [0, 0],
    'last_update_time_millis': [0, 0],
    'order': [0, 0],
  },
  'clash_providers': {
    'id': [1, 1],
    'kind': [1, 0],
    'label': [1, 0],
    'source_definition': [0, 0],
    'url': [1, 0],
    'behavior': [0, 0],
    'format': [0, 0],
    'order': [0, 0],
  },
  'custom_proxies': {
    'id': [1, 1],
    'definition': [1, 0],
    'order': [0, 0],
    'empty_options': [0, 0],
  },
  'proxy_dialers': {
    'profile_id': [1, 1],
    'proxy_id': [1, 2],
    'target': [1, 0],
  },
  'ip_qualities': {
    'ip': [1, 1],
    'quality': [1, 0],
    'checked_at': [1, 0],
  },
};
