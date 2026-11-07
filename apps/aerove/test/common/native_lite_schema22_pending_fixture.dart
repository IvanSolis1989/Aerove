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

// Resolve only producer-owned relative members; the frozen JSON is never rewritten.
Future<String> frozenFixturePath(
  Directory root,
  Object? value, {
  bool directory = false,
}) async {
  if (value is! String ||
      value.isEmpty ||
      p.isAbsolute(value) ||
      p.windows.isAbsolute(value) ||
      value.contains('\\') ||
      value.contains(':') ||
      value
          .split('/')
          .any((part) => part.isEmpty || part == '.' || part == '..')) {
    throw StateError(
      'Old22 fixture requires a confined producer relative path; legacy absolute-only descriptors are unsupported',
    );
  }
  final base = p.normalize(root.absolute.path);
  if (!p.equals(await root.resolveSymbolicLinks(), base)) {
    throw StateError('Indirect old22 artifact root');
  }
  var target = base;
  final parts = value.split('/');
  for (final (index, part) in parts.indexed) {
    target = p.join(target, part);
    final actual = await FileSystemEntity.type(target, followLinks: false);
    final expected = index == parts.length - 1 && !directory
        ? FileSystemEntityType.file
        : FileSystemEntityType.directory;
    if (actual != expected)
      throw StateError('Missing or symbolic old22 artifact member: $value');
  }
  final canonical = directory
      ? await Directory(target).resolveSymbolicLinks()
      : await File(target).resolveSymbolicLinks();
  if (!p.isWithin(base, target) || !p.equals(canonical, target)) {
    throw StateError('Old22 artifact member escapes its descriptor root');
  }
  return target;
}

Future<void> copyFixture(Directory source, Directory target) async {
  await for (final entry in source.list(recursive: true, followLinks: false)) {
    if (entry is Link)
      throw StateError('Symbolic schema22 fixture is not accepted');
    final relative = p
        .relative(entry.path, from: source.path)
        .replaceAll('\\', '/');
    final checked = await frozenFixturePath(
      source,
      relative,
      directory: entry is Directory,
    );
    final path = p.join(target.path, p.relative(checked, from: source.path));
    if (entry is Directory) {
      await Directory(path).create(recursive: true);
    } else {
      await File(path).parent.create(recursive: true);
      await File(checked).copy(path);
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
      final descriptorFile = File(
        p.normalize(File(descriptorPath).absolute.path),
      );
      final artifactRoot = descriptorFile.parent;
      await frozenFixturePath(artifactRoot, p.basename(descriptorFile.path));
      final descriptorBytes = await descriptorFile.readAsBytes();
      final inventoryFile = File(
        await frozenFixturePath(artifactRoot, 'sha256-manifest.json'),
      );
      final inventory = jsonDecode(await inventoryFile.readAsString()) as Map;
      expect(inventory['algorithm'], 'SHA-256');
      expect(
        sha256.convert(descriptorBytes).toString(),
        (inventory['files'] as Map)[p.basename(descriptorFile.path)],
      );
      final descriptor = jsonDecode(utf8.decode(descriptorBytes)) as Map;
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
            if (entry['homeRelative'] != 'cases/${entry['phase']}/home' ||
                entry['manifestRelative'] != 'native-export/manifest.json') {
              throw StateError(
                'Old22 producer requires homeRelative/manifestRelative in the supported layout; absolute-only descriptors are unsupported',
              );
            }
            final original = Directory(
              await frozenFixturePath(
                artifactRoot,
                entry['homeRelative'],
                directory: true,
              ),
            );
            final originalDb = File(
              await frozenFixturePath(original, 'database.sqlite'),
            );
            final originalJournal = File(
              await frozenFixturePath(
                original,
                'native-lite-migration/journal.json',
              ),
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
              File(
                await frozenFixturePath(
                  artifactRoot,
                  entry['manifestRelative'],
                ),
              ),
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
