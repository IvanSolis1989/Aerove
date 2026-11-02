import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_settings.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';

import '../../../aerove-lite/lib/lite_product.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

class FixtureAuthority implements NativeLiteAuthority {
  FixtureAuthority(this.source, this.home);
  final NativeLiteExport source;
  final Directory home;
  final commits = <String>[];
  bool failCommit = false;
  @override
  Future<void> prepareFiles(File plan, String digest) async {
    expect(sha256.convert(await plan.readAsBytes()).toString(), digest);
    expect(
      (jsonDecode(await plan.readAsString()) as Map)['sourceDigest'],
      source.digest,
    );
  }

  @override
  Future<void> confirmTransferred(
    String sourceDigest,
    String receiptDigest,
  ) async {
    expect(sourceDigest, source.digest);
    expect(commits, contains(receiptDigest));
  }

  @override
  Future<void> validateSource(String digest) async {
    expect(digest, source.digest);
    await source.verify();
  }

  @override
  Future<void> commit(File receipt, String digest) async {
    expect(sha256.convert(await receipt.readAsBytes()).toString(), digest);
    final proof = jsonDecode(await receipt.readAsString()) as Map;
    expect(proof['sourceDigest'], source.digest);
    for (final raw in proof['files'] as List) {
      final row = raw as Map;
      expect(
        sha256
            .convert(
              await File(
                p.join(home.path, row['path'] as String),
              ).readAsBytes(),
            )
            .toString(),
        row['sha256'],
      );
    }
    if (failCommit)
      throw StateError('fixture authority interrupted before transfer');
    commits.add(digest);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});
  try {
    ClientProduct.current;
  } on StateError {
    ClientProduct.configure(
      product: liteProduct,
      backendCoreId: 'meow-rs',
      backendFactory: () => throw StateError('fixture does not start a core'),
    );
  }
  test(
    'tampered export records and missing or conflicting old facts refuse source validation',
    () async {
      const fixture = String.fromEnvironment('NATIVE_LITE_FIXTURE');
      final descriptor = jsonDecode(await File(fixture).readAsString()) as Map;
      final source = await NativeLiteExport.open(
        File(descriptor['manifest'] as String),
        descriptor['sha256'] as String,
      );
      final marker = 'runtime/resources/bundles/fixture/manifest.json';
      // This post-open mutation is not a valid exported record. Check the
      // source-integrity boundary before mapping or authority preparation.
      source.records[marker] = {};
      try {
        await expectLater(
          source.verify(),
          throwsA(
            isA<NativeLiteMigrationException>().having(
              (error) => error.field,
              'old path',
              marker,
            ),
          ),
        );
      } finally {
        source.records.remove(marker);
      }
      await source.verify();
      final settings = await source.json('settings/settings.json');
      expect(
        () => nativeLiteConfig(null, null),
        throwsA(
          isA<NativeLiteMigrationException>().having(
            (error) => error.field,
            'old path',
            'settings/settings.json',
          ),
        ),
      );
      final conflict = {
        ...settings!,
        'autoStart': false,
        'backgroundPolicy': {
          ...((settings['backgroundPolicy'] as Map?) ?? {}),
          'autoRun': true,
        },
      };
      expect(
        () => nativeLiteConfig(conflict, null),
        throwsA(isA<NativeLiteMigrationException>()),
      );
      expect(
        () => legacyJson(
          '{"password":PRIVATE_FIXTURE_SECRET',
          'settings/test.json',
        ),
        throwsA(
          isA<NativeLiteMigrationException>().having(
            (error) => error.toString(),
            'sanitized message',
            isNot(contains('PRIVATE_FIXTURE_SECRET')),
          ),
        ),
      );
    },
  );

  test(
    'actual Kotlin repository export imports into real Drift and keeps deletion, private bytes and IDs',
    () async {
      const fixture = String.fromEnvironment('NATIVE_LITE_FIXTURE');
      final descriptor = jsonDecode(await File(fixture).readAsString()) as Map;
      final source = await NativeLiteExport.open(
        File(descriptor['manifest'] as String),
        descriptor['sha256'] as String,
      );
      final home = await Directory.systemTemp.createTemp(
        'native-lite-drift-fixture-',
      );
      final db = Database(
        NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
      );
      final prefs = await SharedPreferences.getInstance();
      await prefs.clear();
      final authority = FixtureAuthority(source, home);
      final defaults = XClashDefaults(
        storage: prefs,
        database: db,
        directory: home,
        loadScript: () async =>
            throw StateError('deleted recommendation must not be reseeded'),
        createId: () => 999999,
      );
      var next = 100;
      final importer = NativeLiteMigration(
        home: home,
        database: db,
        preferences: prefs,
        defaults: defaults,
        authority: authority,
        createId: () => ++next,
      );
      try {
        await importer.run(source);
        final profiles = await db.profilesDao.query().get();
        final scripts = await db.scriptsDao.query().get();
        expect(profiles.map((row) => row.label), [
          'Fixture subscription',
          'Fixture local',
        ]);
        expect(scripts.single.label, 'Edited script');
        final config = Config.strictFromJson(
          Map<String, Object?>.from(
            jsonDecode(prefs.getString(configKey)!) as Map,
          ),
        );
        expect(config.currentProfileId, profiles.first.id);
        expect(config.appSettingProps.acceptedDisclaimerVersion, 0);
        expect(config.davProps!.password, 'fixture-only');
        expect(
          profiles.first.ageSecretKey,
          '# fixture identity\nAGE-SECRET-KEY-FIXTURE\n',
        );
        expect(
          await File(
            p.join(home.path, 'scripts', '${scripts.single.id}.js'),
          ).readAsString(),
          '// retained\r\nfunction main(c) { return c; }',
        );
        expect(
          (await db.customProxiesDao.query().get()).single.name,
          'Fixture SS',
        );
        expect(defaults.recommendedScriptId, isNull);
        await defaults.prepare(isAndroid: true);
        await defaults.initialize(config);
        expect((await db.scriptsDao.query().get()).length, 1);
        final ids = profiles.map((row) => row.id).toList();
        await importer.run(source);
        expect((await db.profilesDao.query().get()).map((row) => row.id), ids);
        expect((await db.scriptsDao.query().get()).length, 1);
        expect(authority.commits.length, 1);
        final preserved =
            jsonDecode(
                  await File(
                    p.join(
                      home.path,
                      'native-lite-migration/source/payload/profiles/index.json',
                    ),
                  ).readAsString(),
                )
                as Map;
        expect(preserved['fixtureUnknown'], {'preserve': true});
        await source.verify();
      } finally {
        await db.close();
        await home.delete(recursive: true);
      }
    },
  );
  for (final mutate in [false, true]) {
    test(
      'authority failure retains pending; retry ${mutate ? "rejects changed target" : "reuses IDs and rows"}',
      () async {
        const fixture = String.fromEnvironment('NATIVE_LITE_FIXTURE');
        final descriptor =
            jsonDecode(await File(fixture).readAsString()) as Map;
        final source = await NativeLiteExport.open(
          File(descriptor['manifest'] as String),
          descriptor['sha256'] as String,
        );
        final home = await Directory.systemTemp.createTemp(
          'native-lite-retry-fixture-',
        );
        final db = Database(
          NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
        );
        final prefs = await SharedPreferences.getInstance();
        await prefs.clear();
        final authority = FixtureAuthority(source, home)..failCommit = true;
        final defaults = XClashDefaults(
          storage: prefs,
          database: db,
          directory: home,
          loadScript: () async => throw StateError('must not seed'),
          createId: () => 999999,
        );
        var next = 200;
        final importer = NativeLiteMigration(
          home: home,
          database: db,
          preferences: prefs,
          defaults: defaults,
          authority: authority,
          createId: () => ++next,
        );
        try {
          await expectLater(importer.run(source), throwsStateError);
          final journal = File(
            p.join(home.path, 'native-lite-migration/journal.json'),
          );
          expect(
            (jsonDecode(await journal.readAsString()) as Map)['phase'],
            'preferencesCommitted',
          );
          expect(authority.commits, isEmpty);
          final before = await nativeLiteDatabaseProof(db);
          final ids = (await db.profilesDao.query().get())
              .map((r) => r.id)
              .toList();
          authority.failCommit = false;
          if (mutate) {
            await db.customStatement(
              "UPDATE profiles SET label='edited during interruption' WHERE id=?",
              [ids.first],
            );
            await expectLater(
              importer.run(source),
              throwsA(isA<NativeLiteMigrationException>()),
            );
            expect(
              (await db.profilesDao.get(ids.first))!.label,
              'edited during interruption',
            );
            expect(authority.commits, isEmpty);
          } else {
            await importer.run(source);
            expect(await nativeLiteDatabaseProof(db), before);
            expect((await db.profilesDao.query().get()).map((r) => r.id), ids);
            expect(next, lessThan(300));
            expect(authority.commits.length, 1);
          }
        } finally {
          await db.close();
          await home.delete(recursive: true);
        }
      },
    );
  }
}
