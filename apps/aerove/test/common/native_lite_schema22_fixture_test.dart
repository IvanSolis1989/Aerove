import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/product.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

import '../../../aerove-lite/lib/lite_product.dart';
import 'native_lite_migration_test.dart' show FixtureAuthority;

const _authorityCommit = 'caf40edfdd957af5324bb5ee524c3bbaa0c42065';
const _sourceHashes = <String, String>{
  'packages/aerove_client/lib/database/database.dart':
      '7b0c146a37489092caeabea75d0097dee3ade7d28f1b04d1d76f448b66409a45',
  'packages/aerove_client/lib/database/generated/database.g.dart':
      '3fe538f94560867dd50dfb97901def59bed281c0edab141ab4680e97c508d05c',
  'packages/aerove_client/lib/common/native_lite_migration.dart':
      'ab36eaa04b3f668f5cc38f6163ab753c2bfef915b6c0309fdff2c98b947b6408',
  'packages/aerove_client/lib/common/native_lite_mapping.dart':
      '4afe8b349d76beca6aee54fc63bc41a31c2fb0cd30f439af3f6f0f5a280ecd5f',
  'packages/aerove_client/lib/common/xclash_defaults.dart':
      '728b3eb1f5c92ea50aa28e884efe0efdedd83b0d5217b50ca0f4ad4da0ffa398',
  'packages/aerove_client/lib/common/native_lite_settings.dart':
      '6fc651cd31c32877e0ed4129b42eaf44828795add450651eb719c64358cdea91',
  'packages/aerove_client/lib/product.dart':
      '2380cf5fb6df391868f4a2eccc48427cd86f635594529a568be1ebd1bec8082a',
  'apps/aerove-lite/lib/lite_product.dart':
      '77ae5bb54a3cb4f52f70cab620df87e242a555c50d74c3fcfe2ba4f5647bd264',
  'apps/aerove/test/common/native_lite_migration_test.dart':
      'c303fb823af5f8c6df37dcc6526cbe6b02b5c4c4b422fbc875947432539440c6',
  'apps/aerove/test/common/native_lite_source_proof89_test.dart':
      '299da9aa85d43b194df031de268bf43521d022674421d835e4d6edf715525d1c',
  'apps/aerove-lite/app/src/test/java/dev/aerove/lite/fixtures/NativeMigrationInteropFixtureTest.kt':
      '0d99d2d9559af6e002d193b17ff03d1f439a53077d122f978919fff63a41d3bc',
  'apps/aerove-lite/build-logic/src/main/kotlin/aerove.lite-app-common.gradle.kts':
      '893289bf3210254fe3aaa5b953558b2718f24fd97b2c1f3fe919ef4dbf8060bf',
  'packages/aerove_client/lib/common/native_lite_builder.dart':
      '3531256032e0c5c82bc462918297d4dc152144441ba17766c542cb543031a163',
  'packages/aerove_client/lib/common/native_lite_resources.dart':
      'b8af412bcb2e5a73b56adda0978e1b27811c50c1f4d629180310d73492eb9d3c',
  'packages/aerove_client/lib/common/indexing.dart':
      '226a5fe44e379c31149e40a4e86c21a9eb9f3775575a128ad45732b177c89b9f',
  'packages/aerove_client/lib/database/clash_providers.dart':
      '68bc6753f192d07949c6c14f071263767b51e88247f54da1c9f014fa76398787',
  'packages/aerove_client/lib/database/converter.dart':
      'ed58365a0848cabd29595754cd81afa0ad93f4ff49b9be73dc652748b951c4a8',
  'packages/aerove_client/lib/database/custom_proxies.dart':
      '847452cf4dd1829fa12bfb48a2e016e07a48c1c7ec68416bd2897d4c54279c1b',
  'packages/aerove_client/lib/database/dialers.dart':
      'e8f0516e2beed3cab92bdaba1bb6d9599d91a3c68287c7ec5270358f0704e2c7',
  'packages/aerove_client/lib/database/groups.dart':
      '9e24eeb54b188d2a4d7e9e309754ed3421695676b29002ac7e945d1fd1fae959',
  'packages/aerove_client/lib/database/icons.dart':
      '3dde18d5f4060c27e6e974bceea309bb8fd7e3faaa929fb7f068cd9ba919ee3e',
  'packages/aerove_client/lib/database/ip_qualities.dart':
      '7fb4df1dcf4ce2dad933b37e5a0819877598c2202eac3ef97487f1c127b8a602',
  'packages/aerove_client/lib/database/profiles.dart':
      '20f2d812d2f9ff3c75cc5bb5cb2722b76a23c155e79b3473f7f35e559eba176c',
  'packages/aerove_client/lib/database/rule_sql_executor.dart':
      '32a4f86d47021c19bb8a59a8f7ee109eb3b976b80452f0be867f5a56d5411f16',
  'packages/aerove_client/lib/database/rules.dart':
      '72ac8b8abc12616e7abf5cf4b04187b6f3410536edbc0ba4760c60d7de2c3fbd',
  'packages/aerove_client/lib/database/scripts.dart':
      '8ce2e6472aebf3fc57396dddfc0e1cf541c8496115a36c8fa836fd9518c772c8',
};
Future<String> _sha256Source(File file) async {
  final input = await file.readAsBytes();
  final normalized = <int>[];
  for (var index = 0; index < input.length; index++) {
    if (input[index] == 13 &&
        index + 1 < input.length &&
        input[index + 1] == 10) {
      normalized.add(10);
      index += 1;
    } else {
      normalized.add(input[index]);
    }
  }
  return sha256.convert(normalized).toString();
}

Future<void> _verifyFixedSources(Directory repositoryRoot) async {
  for (final entry in _sourceHashes.entries) {
    final file = File(
      p.joinAll([repositoryRoot.path, ...entry.key.split('/')]),
    );
    expect(
      await _sha256Source(file),
      entry.value,
      reason: 'Old schema22 source differs from fixed Git object: ${entry.key}',
    );
  }
}

class _FailSecondValidationAuthority extends FixtureAuthority {
  _FailSecondValidationAuthority(super.source, super.home);

  int validationCalls = 0;

  @override
  Future<void> validateSource(String digest) async {
    validationCalls += 1;
    if (validationCalls == 2) {
      throw StateError(
        'controlled fixture failure before database transaction',
      );
    }
    await super.validateSource(digest);
  }
}

Future<String> _sha256(File file) async =>
    sha256.convert(await file.readAsBytes()).toString();

Future<Map<String, String>> _treeSha256(Directory root) async {
  final hashes = <String, String>{};
  await for (final entry in root.list(recursive: true, followLinks: false)) {
    if (entry is Link) {
      throw StateError('Fixture tree contains a symbolic link');
    }
    if (entry is File) {
      hashes[p.relative(entry.path, from: root.path)] = await _sha256(entry);
    }
  }
  return Map<String, String>.fromEntries(
    hashes.entries.toList()
      ..sort((left, right) => left.key.compareTo(right.key)),
  );
}

Future<void> _copyTree(Directory source, Directory target) async {
  if (await target.exists()) {
    throw StateError('Fixture target must be new: ${target.path}');
  }
  await target.create(recursive: true);
  await for (final entry in source.list(recursive: true, followLinks: false)) {
    if (entry is Link) {
      throw StateError('Fixture tree contains a symbolic link');
    }
    final relative = p.relative(entry.path, from: source.path);
    final destination = p.join(target.path, relative);
    if (entry is Directory) {
      await Directory(destination).create(recursive: true);
    } else if (entry is File) {
      await File(destination).parent.create(recursive: true);
      await entry.copy(destination);
    }
  }
}

Future<Map<String, Object?>> _preferencesSnapshot(
  SharedPreferences preferences,
) async {
  final result = <String, Object?>{};
  final keys = preferences.getKeys().toList()..sort();
  for (final key in keys) {
    final value = preferences.get(key);
    result[key] = value is Set<String> ? (value.toList()..sort()) : value;
  }
  return result;
}

Future<void> _writeJson(File file, Object value) async {
  await file.parent.create(recursive: true);
  await file.writeAsString(
    '${const JsonEncoder.withIndent('  ').convert(value)}\n',
    flush: true,
  );
}

Future<Map<String, dynamic>> _resumePendingOnOld22Copy({
  required NativeLiteExport source,
  required Directory pendingHome,
  required Map<String, Object?> preferencesSnapshot,
  required Map<String, dynamic> pendingJournal,
}) async {
  final proofHome = await Directory.systemTemp.createTemp(
    'native-lite-schema22-success-proof-',
  );
  expect(proofHome.path, isNot(pendingHome.path));
  await proofHome.delete();
  await _copyTree(pendingHome, proofHome);
  SharedPreferences.setMockInitialValues(
    Map<String, Object>.from(preferencesSnapshot),
  );
  final preferences = await SharedPreferences.getInstance();
  final database = Database(
    NativeDatabase(File(p.join(proofHome.path, 'database.sqlite'))),
  );
  try {
    expect(database.schemaVersion, 22);
    final authority = FixtureAuthority(source, proofHome);
    final defaults = XClashDefaults(
      storage: preferences,
      database: database,
      directory: proofHome,
      loadScript: () async =>
          throw StateError('fixture must not reseed migrated defaults'),
      createId: () => 999999,
    );
    final migration = NativeLiteMigration(
      home: proofHome,
      database: database,
      preferences: preferences,
      defaults: defaults,
      authority: authority,
      createId: () => throw StateError('fixture must reuse its durable ID map'),
    );
    await migration.run(source);
    final journal =
        jsonDecode(
              await File(
                p.join(proofHome.path, 'native-lite-migration', 'journal.json'),
              ).readAsString(),
            )
            as Map<String, dynamic>;
    expect(journal['phase'], 'complete');
    expect(journal['sourceDigest'], pendingJournal['sourceDigest']);
    expect(journal['ids'], pendingJournal['ids']);
    expect(authority.commits, hasLength(1));
    final version = await database
        .customSelect('PRAGMA user_version')
        .getSingle();
    expect(version.data['user_version'], 22);
    final proof = await nativeLiteDatabaseProof(database);
    expect(journal['expectedDatabase'], proof);
    return proof;
  } finally {
    await database.close();
    await proofHome.delete(recursive: true);
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
      backendFactory: () => throw StateError('fixture must not start a core'),
    );
  }

  const fixtureDescriptorPath = String.fromEnvironment('NATIVE_LITE_FIXTURE');
  const outputPath = String.fromEnvironment('NATIVE_LITE_SCHEMA22_OUTPUT');

  test('exports real old schema22 pending migration states', () async {
    if (fixtureDescriptorPath.isEmpty || outputPath.isEmpty) {
      throw StateError(
        'Set NATIVE_LITE_FIXTURE and NATIVE_LITE_SCHEMA22_OUTPUT explicitly',
      );
    }
    final runnerTemp = Platform.environment['RUNNER_TEMP'];
    final commit = Platform.environment['GITHUB_SHA'];
    final runId = Platform.environment['GITHUB_RUN_ID'];
    final attempt = Platform.environment['GITHUB_RUN_ATTEMPT'];
    if (runnerTemp == null ||
        commit == null ||
        runId == null ||
        attempt == null) {
      throw StateError(
        'A GitHub Actions RUNNER_TEMP and run identity are required',
      );
    }
    final repositoryRoot = Directory(
      p.normalize(p.join(Directory.current.path, '..', '..')),
    );
    await _verifyFixedSources(repositoryRoot);
    final output = p.normalize(p.absolute(outputPath));
    final runnerRoot = p.normalize(p.absolute(runnerTemp));
    if (!p.isWithin(runnerRoot, output) || output == runnerRoot) {
      throw StateError('Output must be a new child of RUNNER_TEMP');
    }
    if (await Directory(output).exists()) {
      throw StateError('Output directory already exists: $output');
    }
    final inputDescriptor =
        jsonDecode(await File(fixtureDescriptorPath).readAsString()) as Map;
    final originalSource = await NativeLiteExport.open(
      File(inputDescriptor['manifest'] as String),
      inputDescriptor['sha256'] as String,
    );
    final originalManifest =
        jsonDecode(
              await File(inputDescriptor['manifest'] as String).readAsString(),
            )
            as Map;
    expect(originalManifest['purpose'], 'source-recovery-candidate');
    final originalInventory = await _treeSha256(originalSource.directory);
    await Directory(output).create(recursive: true);
    final retainedExport = Directory(p.join(output, 'native-export'));
    await _copyTree(originalSource.directory, retainedExport);
    final source = await NativeLiteExport.open(
      File(p.join(retainedExport.path, 'manifest.json')),
      originalSource.digest,
    );
    expect(await _treeSha256(retainedExport), originalInventory);

    final cases = <Map<String, Object?>>[];
    for (final phase in [
      'beforeDatabaseCommit',
      'databaseCommittedBeforeComplete',
    ]) {
      SharedPreferences.setMockInitialValues({});
      final preferences = await SharedPreferences.getInstance();
      final home = await Directory.systemTemp.createTemp(
        'native-lite-schema22-pending-',
      );
      final database = Database(
        NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
      );
      expect(database.schemaVersion, 22);
      final authority =
          phase == 'beforeDatabaseCommit'
                ? _FailSecondValidationAuthority(source, home)
                : FixtureAuthority(source, home)
            ..failCommit = true;
      final defaults = XClashDefaults(
        storage: preferences,
        database: database,
        directory: home,
        loadScript: () async =>
            throw StateError('fixture must not reseed migrated defaults'),
        createId: () => 999999,
      );
      var nextId = 200;
      final migration = NativeLiteMigration(
        home: home,
        database: database,
        preferences: preferences,
        defaults: defaults,
        authority: authority,
        createId: () => ++nextId,
      );
      late Map<String, dynamic> pendingJournal;
      late Map<String, Object?> preferencesSnapshot;
      late Map<String, dynamic> expectedBusinessProof;
      try {
        final expectedFailure = phase == 'beforeDatabaseCommit'
            ? 'controlled fixture failure before database transaction'
            : 'fixture authority interrupted before transfer';
        await expectLater(
          migration.run(source),
          throwsA(
            isA<StateError>().having(
              (error) => error.message,
              'controlled interruption',
              expectedFailure,
            ),
          ),
        );
        final journalFile = File(
          p.join(home.path, 'native-lite-migration', 'journal.json'),
        );
        pendingJournal =
            jsonDecode(await journalFile.readAsString())
                as Map<String, dynamic>;
        expect(pendingJournal['schema'], 1);
        expect(pendingJournal['sourceDigest'], source.digest);
        expect(
          pendingJournal['phase'],
          phase == 'beforeDatabaseCommit'
              ? 'filesInstalled'
              : 'preferencesCommitted',
        );
        expect(pendingJournal['beforeDatabase'], isA<Map>());
        expect(pendingJournal.containsKey('databaseSchema'), isFalse);
        final oldTableNames = database.allTables
            .map((table) => table.actualTableName)
            .toSet();
        expect(
          (pendingJournal['beforeDatabase'] as Map).keys.toSet(),
          oldTableNames,
        );
        final userVersion = await database
            .customSelect('PRAGMA user_version')
            .getSingle();
        expect(userVersion.data['user_version'], 22);
        final currentProof = await nativeLiteDatabaseProof(database);
        if (phase == 'beforeDatabaseCommit') {
          expect(pendingJournal['expectedDatabase'], isNull);
          expect(currentProof, pendingJournal['beforeDatabase']);
          expect(authority, isA<_FailSecondValidationAuthority>());
          expect(
            (authority as _FailSecondValidationAuthority).validationCalls,
            2,
          );
        } else {
          expect(authority.failCommit, isTrue);
          expect(pendingJournal['expectedDatabase'], isA<Map>());
          expect(
            (pendingJournal['expectedDatabase'] as Map).keys.toSet(),
            oldTableNames,
          );
          expect(currentProof, pendingJournal['expectedDatabase']);
          expect(authority.commits, isEmpty);
        }
        preferencesSnapshot = await _preferencesSnapshot(preferences);
        expectedBusinessProof = phase == 'databaseCommittedBeforeComplete'
            ? Map<String, dynamic>.from(
                pendingJournal['expectedDatabase'] as Map,
              )
            : <String, dynamic>{};
      } finally {
        await database.close();
      }

      final caseRoot = Directory(p.join(output, 'cases', phase));
      final retainedHome = Directory(p.join(caseRoot.path, 'home'));
      await _copyTree(home, retainedHome);
      await home.delete(recursive: true);
      if (phase == 'beforeDatabaseCommit') {
        expectedBusinessProof = await _resumePendingOnOld22Copy(
          source: source,
          pendingHome: retainedHome,
          preferencesSnapshot: preferencesSnapshot,
          pendingJournal: pendingJournal,
        );
      }
      final preferencesFile = File(p.join(caseRoot.path, 'preferences.json'));
      await _writeJson(preferencesFile, preferencesSnapshot);
      final retainedJournal = File(
        p.join(retainedHome.path, 'native-lite-migration', 'journal.json'),
      );
      final retainedDatabase = File(
        p.join(retainedHome.path, 'database.sqlite'),
      );
      final retainedJournalJson =
          jsonDecode(await retainedJournal.readAsString()) as Map;
      expect(retainedJournalJson['phase'], pendingJournal['phase']);
      expect(
        retainedJournalJson['beforeDatabase'],
        pendingJournal['beforeDatabase'],
      );
      expect(
        retainedJournalJson['expectedDatabase'],
        pendingJournal['expectedDatabase'],
      );
      cases.add({
        'phase': phase,
        'journalPhase': pendingJournal['phase'],
        'home': retainedHome.path,
        'homeRelative': p.relative(retainedHome.path, from: output),
        'homeDatabaseSha256': await _sha256(retainedDatabase),
        'journal': retainedJournal.path,
        'journalSha256': await _sha256(retainedJournal),
        'manifest': p.join(retainedExport.path, 'manifest.json'),
        'manifestRelative': p.relative(
          p.join(retainedExport.path, 'manifest.json'),
          from: output,
        ),
        'sourceDigest': source.digest,
        'preferences': preferencesSnapshot,
        'preferencesFile': preferencesFile.path,
        'expectedBusinessProof': expectedBusinessProof,
        'expectedBusinessProofSource': phase == 'beforeDatabaseCommit'
            ? 'successful old-schema22 resume of a copy with identical durable IDs'
            : 'exact preferencesCommitted journal expectedDatabase map',
        'proofHomeWasDifferent': phase == 'beforeDatabaseCommit',
        'interruption': phase == 'beforeDatabaseCommit'
            ? 'Second validateSource call throws after filesInstalled; controlled exception, not process kill.'
            : 'FixtureAuthority.commit throws after database and preferences commit; controlled exception, not process kill.',
      });
    }
    expect(cases.map((item) => item['phase']).toSet(), {
      'beforeDatabaseCommit',
      'databaseCommittedBeforeComplete',
    });
    expect(await _treeSha256(originalSource.directory), originalInventory);
    expect(await _treeSha256(retainedExport), originalInventory);
    final descriptor = <String, Object?>{
      'schema': 1,
      'producer': {
        'commit': commit,
        'producerTestSha256': await _sha256Source(
          File(
            p.join(
              repositoryRoot.path,
              'apps',
              'aerove',
              'test',
              'common',
              'native_lite_schema22_fixture_test.dart',
            ),
          ),
        ),
        'databaseSchema': 22,
        'sourceAuthorityCommit': _authorityCommit,
        'sourceHashes': _sourceHashes,
        'workflowRun': runId,
        'runAttempt': int.parse(attempt),
        'interruptionIsProcessKill': false,
        'testFixturePurpose': originalManifest['purpose'],
      },
      'cases': cases,
    };
    await _writeJson(File(p.join(output, 'producer.json')), descriptor);
    final inventory = await _treeSha256(Directory(output));
    await _writeJson(File(p.join(output, 'sha256-manifest.json')), {
      'algorithm': 'SHA-256',
      'scope':
          'all regular files under this output except sha256-manifest.json itself',
      'files': inventory,
    });
  });
}
