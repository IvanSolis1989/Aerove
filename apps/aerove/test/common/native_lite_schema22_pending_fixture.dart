// Explicit Root-produced fixture entry point. No fixture means failure, not skip.
// flutter test this_file --dart-define=NATIVE_LITE_SCHEMA22_PENDING_FIXTURE=...
import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';
import '../../../aerove-lite/lib/lite_product.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite;
import 'native_lite_migration_test.dart' show FixtureAuthority;

Future<void> copyFixture(Directory source, Directory target) async {
  await for (final entry in source.list(recursive: true, followLinks: false)) {
    if (entry is Link)
      throw StateError('Symbolic schema22 fixture is not accepted');
    final path = p.join(target.path, p.relative(entry.path, from: source.path));
    if (entry is Directory) {
      await Directory(path).create(recursive: true);
    } else {
      await File(path).parent.create(recursive: true);
      await File(entry.path).copy(path);
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  try {
    ClientProduct.current;
  } on StateError {
    ClientProduct.configure(
      product: liteProduct,
      backendCoreId: 'meow-rs',
      backendFactory: () =>
          throw StateError('No runtime core in old22 fixture'),
    );
  }
  const descriptorPath = String.fromEnvironment(
    'NATIVE_LITE_SCHEMA22_PENDING_FIXTURE',
  );
  test(
    'actual old schema22 pending producer resumes through23 and rejects third business values',
    () async {
      if (descriptorPath.isEmpty)
        throw StateError('Root old22 producer artifact has not been supplied');
      final descriptor =
          jsonDecode(await File(descriptorPath).readAsString()) as Map;
      expect(descriptor['schema'], 1);
      expect((descriptor['producer'] as Map)['databaseSchema'], 22);
      final provenance =
          jsonDecode(
                await File(
                  'test/fixtures/native_schema22.source.json',
                ).readAsString(),
              )
              as Map;
      final produced = (descriptor['producer'] as Map)['sourceHashes'] as Map;
      for (final entry in (provenance['sourceHashes'] as Map).entries) {
        expect(
          produced[entry.key],
          entry.value,
          reason:
              'Actual old22 producer source must match fixed declarations/proof',
        );
      }
      final cases = (descriptor['cases'] as List).cast<Map>();
      expect(cases.map((e) => e['phase']).toSet(), {
        'beforeDatabaseCommit',
        'databaseCommittedBeforeComplete',
      });
      for (final entry in cases) {
        for (final mutate in [false, true]) {
          final home = await Directory.systemTemp.createTemp(
            'actual-old22-pending-',
          );
          Database? db;
          try {
            final original = Directory(entry['home'] as String);
            final originalDb = File(p.join(original.path, 'database.sqlite'));
            final originalJournal = File(
              p.join(original.path, 'native-lite-migration/journal.json'),
            );
            expect(
              sha256.convert(await originalDb.readAsBytes()).toString(),
              entry['homeDatabaseSha256'],
            );
            expect(
              sha256.convert(await originalJournal.readAsBytes()).toString(),
              entry['journalSha256'],
            );
            final raw = sqlite.sqlite3.open(
              originalDb.path,
              mode: sqlite.OpenMode.readOnly,
            );
            try {
              expect(
                raw.select('PRAGMA user_version').single['user_version'],
                22,
              );
            } finally {
              raw.dispose();
            }
            final old = jsonDecode(await originalJournal.readAsString()) as Map;
            expect(old['schema'], 1);
            expect(old['phase'], isNot('complete'));
            expect(
              (old['beforeDatabase'] as Map).keys.toSet(),
              ruleSqlBusinessTables,
            );
            if (old['expectedDatabase'] != null)
              expect(
                (old['expectedDatabase'] as Map).keys.toSet(),
                ruleSqlBusinessTables,
              );
            expect(old.containsKey('databaseSchema'), isFalse);
            final expected = Map<String, dynamic>.from(
              entry['expectedBusinessProof'] as Map,
            );
            expect(expected.keys.toSet(), ruleSqlBusinessTables);
            await copyFixture(original, home);
            SharedPreferences.setMockInitialValues(
              Map<String, Object>.from(entry['preferences'] as Map),
            );
            final prefs = await SharedPreferences.getInstance();
            final source = await NativeLiteExport.open(
              File(entry['manifest'] as String),
              entry['sourceDigest'] as String,
            );
            db = Database(
              NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
            );
            await db.ruleSqlInfrastructureProof(resolved: true);
            final identity = await db.ruleSqlIncarnation();
            if (mutate)
              await db.profiles.put(
                const Profile(
                  id: 987654,
                  label: 'third independent value',
                  autoUpdateDuration: Duration.zero,
                ).toCompanion(),
              );
            final authority = FixtureAuthority(source, home);
            final importer = NativeLiteMigration(
              home: home,
              database: db,
              preferences: prefs,
              defaults: XClashDefaults(
                storage: prefs,
                database: db,
                directory: home,
                loadScript: () async => throw StateError('must not seed'),
                createId: () => 999999,
              ),
              authority: authority,
              createId: () =>
                  throw StateError('durable old ID map must be reused'),
            );
            if (mutate) {
              await expectLater(
                importer.run(source),
                throwsA(isA<NativeLiteMigrationException>()),
              );
              expect(
                (await db.profilesDao.get(987654))!.label,
                'third independent value',
              );
              expect(authority.commits, isEmpty);
            } else {
              await importer.run(source);
              final actual = await nativeLiteDatabaseProof(db);
              expect({
                for (final name in ruleSqlBusinessTables) name: actual[name],
              }, expected);
              expect(actual.keys.toSet(), {
                ...ruleSqlBusinessTables,
                ...ruleSqlInfrastructureTables,
              });
              expect(authority.commits, hasLength(1));
              if (entry['phase'] == 'databaseCommittedBeforeComplete')
                expect(await db.ruleSqlIncarnation(), identity);
            }
          } finally {
            await db?.close();
            await home.delete(recursive: true);
          }
        }
      }
    },
  );
}
