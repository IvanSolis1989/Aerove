import 'dart:convert';
import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/common/file_provider_source.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/source_plan.dart';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/common/yaml.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart'
    show profilesProvider, profilesStreamProvider;
import 'package:fl_clash/providers/state.dart' show setupStateProvider;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  late Database db;
  late SharedPreferences storage;
  late File original;
  const id = '12345678-1234-4234-8234-123456789abc';
  const key = 'proxy-providers/fixture';
  late Map<String, dynamic> definition;
  String getRelative() =>
      fileProviderSourcePath(7, 'proxy-providers', 'fixture', detached: true);
  File target() => File(p.join(home.path, getRelative()));
  Future<Map<String, dynamic>> proof() =>
      nativeLiteBusinessSource(db, home, storage, null);
  Future<FileProviderSourceJournal> prepare({
    String contents = 'proxies: [{name: local, type: direct}]',
    String transactionId = id,
  }) async => FileProviderSourceJournal.prepareDetached(
    database: db,
    home: home,
    id: transactionId,
    profileId: 7,
    namespace: 'proxy-providers',
    name: 'fixture',
    expectedRevision: 'actual-prior-revision',
    expected: {'digest': 'prior'},
    beforeProof: await proof(),
    bytes: utf8.encode(contents),
    originalDefinition: definition,
  );
  Future<Map<String, dynamic>> rawRow() async => Map<String, dynamic>.from(
    (await db.customSelect('SELECT * FROM profiles WHERE id = 7').getSingle())
        .data,
  );
  Map<String, dynamic> reply(
    FileProviderSourceJournal journal,
    String decision,
  ) => {
    'schemaVersion': 1,
    'transactionId': journal.id,
    'planSha256': journal.planSha256,
    'state': decision == 'UNDECIDED' ? 'ABORT_REQUIRED' : decision,
    'decision': decision,
    'sourceAction': decision == 'UNDECIDED' ? 'restore-old' : 'retain-new',
  };
  setUp(() async {
    home = await Directory.systemTemp.createTemp('detached-provider-source-');
    db = Database(NativeDatabase(File(p.join(home.path, 'database.sqlite'))));
    SharedPreferences.setMockInitialValues({});
    storage = await SharedPreferences.getInstance();
    original = File(p.join(home.path, 'profiles', '8.yaml'));
    await original.create(recursive: true);
    await original.writeAsString('proxies: [{name: immutable, type: direct}]');
    definition = {'type': 'file', 'path': original.path};
    await db.profilesDao.putAll([
      Profile(
        id: 7,
        label: 'Selected',
        type: ProfileType.custom,
        autoUpdateDuration: Duration.zero,
        overrides: ProfileOverrides(
          sourceProxyProviders: {'fixture': definition},
        ),
      ).toCompanion(),
    ]);
    await nativeLiteBusinessSource(
      db,
      home,
      storage,
      Config(
        currentProfileId: 7,
        themeProps: defaultThemeProps,
        patchClashConfig: newPatchClashConfig('meow-rs'),
      ),
    );
  });
  tearDown(() async {
    await db.close();
    await home.delete(recursive: true);
  });

  test(
    'new binding and raw source survive lost acknowledgement, DB reopen and the real next profile producer',
    () async {
      final beforeRow = await rawRow();
      final beforeProof = await proof();
      final originalBytes = await original.readAsBytes();
      final journal = await prepare();
      expect(await target().exists(), false);
      expect(await rawRow(), beforeRow);
      await journal.publish(database: db);
      final published = await rawRow();
      expect(
        {...published}..remove('overrides'),
        {...beforeRow}..remove('overrides'),
      );
      expect(
        nativeLiteJsonHash(await proof()),
        isNot(nativeLiteJsonHash(beforeProof)),
      );
      await db.close();
      db = Database(NativeDatabase(File(p.join(home.path, 'database.sqlite'))));
      final recovered = (await FileProviderSourceJournal.pending(home)).single;
      final operations = <String>[];
      await recovered.recover(
        database: db,
        invoke: (request) async {
          operations.add(request['operation'] as String);
          return reply(journal, 'COMMITTED');
        },
        readProof: () =>
            throw StateError('COMMITTED must retain its new business row'),
      );
      expect(operations, ['query', 'query']);
      expect(await rawRow(), published);
      final loaded = (await db.profilesDao.get(7))!;
      // Exercise the noncustom SetupState factory, which used to drop this map.
      await File(
        p.join(home.path, 'profiles', '7.yaml'),
      ).writeAsString('rules: ["MATCH,DIRECT"]');
      await db.customStatement(
        "UPDATE profiles SET type = 'file' WHERE id = 7",
      );
      final previousDatabase = database;
      database = db;
      final container = ProviderContainer(
        overrides: buildConfigOverrides(
          Config(
            currentProfileId: 7,
            themeProps: defaultThemeProps,
            patchClashConfig: newPatchClashConfig('meow-rs'),
          ),
        ),
      );
      final hold = container.listen(profilesProvider, (_, _) {});
      late SetupState state;
      try {
        await container.read(profilesStreamProvider.future);
        state = await container.read(setupStateProvider(7).future);
        expect(
          state.overrides.localProviderSources,
          loaded.overrides.localProviderSources,
        );
      } finally {
        hold.close();
        container.dispose();
        database = previousDatabase;
      }
      final built = await makeRealProfileTask(
        MakeRealProfileState(
          profilesPath: p.join(home.path, 'profiles'),
          profileId: 7,
          rawConfig: {
            'proxy-providers': {'fixture': definition},
            'proxy-groups': [
              {
                'name': 'choice',
                'type': 'select',
                'use': ['fixture'],
              },
            ],
            'rules': ['MATCH,choice'],
          },
          overrides: state.overrides,
          realPatchConfig: newPatchClashConfig('meow-rs'),
          appendSystemDns: false,
          rules: [],
          proxyGroups: [],
          addedRules: [],
          defaultUA: 'detached fixture',
          safeMode: true,
        ),
        coreId: 'meow-rs',
      );
      final compiled = loadPlainYaml(built.yaml) as Map;
      expect(
        ((compiled['proxy-providers'] as Map)['fixture'] as Map)['path'],
        target().path,
      );
      expect(
        loaded.overrides.localProviderSources[key]!.originalDefinition,
        definition,
      );
      expect(await original.readAsBytes(), originalBytes);
      expect(
        fileProviderSourceBackupEntries(home.path, [7]),
        containsAll([getRelative(), '${getRelative()}.source.json']),
      );
      expect(await FileProviderSourceJournal.pending(home), isEmpty);
    },
  );

  test(
    'existing binding update cancellation restores its exact DB row and raw bytes, preserving original lineage',
    () async {
      final first = await prepare();
      await first.publish(database: db);
      await first.recover(
        database: db,
        invoke: (_) async => reply(first, 'COMMITTED'),
        readProof: proof,
      );
      final oldRow = await rawRow(), oldProof = await proof();
      final oldBytes = await target().readAsBytes();
      definition = {
        'type': 'file',
        'path': 'must-not-replace-original-lineage',
      };
      final update = await prepare(
        contents: 'proxies: [{name: edited, type: direct}]',
        transactionId: '12345678-1234-4234-8234-123456789abd',
      );
      await update.publish(database: db);
      expect(
        (await db.profilesDao.get(
          7,
        ))!.overrides.localProviderSources[key]!.originalDefinition['path'],
        original.path,
      );
      var aborted = false;
      await update.recover(
        database: db,
        invoke: (request) async {
          if (request['operation'] == 'abort') {
            expect(await rawRow(), oldRow);
            expect(await target().readAsBytes(), oldBytes);
            expect((request['restored'] as Map)['proof'], oldProof);
            aborted = true;
          }
          return reply(update, aborted ? 'ABORTED' : 'UNDECIDED');
        },
        readProof: proof,
      );
      expect(await rawRow(), oldRow);
      expect(await target().readAsBytes(), oldBytes);
      expect(await proof(), oldProof);
    },
  );

  for (final deferred in [false, true])
    test(
      'real ${deferred ? 'deferred COMMIT' : 'UPDATE'} rejection restores file and profile binding together',
      () async {
        final beforeRow = await rawRow(), beforeProof = await proof();
        final journal = await prepare();
        if (deferred) {
          await db.customStatement('PRAGMA foreign_keys = ON');
          await db.customStatement(
            'CREATE TABLE source_parent (id INTEGER PRIMARY KEY)',
          );
          await db.customStatement(
            'CREATE TABLE source_child (parent_id INTEGER REFERENCES source_parent(id) DEFERRABLE INITIALLY DEFERRED)',
          );
          await db.customStatement(
            'CREATE TRIGGER reject_source AFTER UPDATE OF overrides ON profiles BEGIN INSERT INTO source_child VALUES (999); END',
          );
        } else {
          await db.customStatement(
            "CREATE TRIGGER reject_source BEFORE UPDATE OF overrides ON profiles BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END",
          );
        }
        await expectLater(
          journal.publish(database: db),
          throwsA(isA<Exception>()),
        );
        expect(await rawRow(), beforeRow);
        expect(await target().exists(), false);
        expect(await File('${target().path}.source.json').exists(), false);
        // Remove only the fault-injection DDL before the independent source
        // proof. The failed UPDATE/COMMIT and retained rows/files are asserted above.
        await db.customStatement('DROP TRIGGER reject_source');
        if (deferred) {
          await db.customStatement('DROP TABLE source_child');
          await db.customStatement('DROP TABLE source_parent');
        }
        var aborted = false;
        await journal.recover(
          database: db,
          invoke: (request) async {
            if (request['operation'] == 'abort') {
              expect((request['restored'] as Map)['proof'], beforeProof);
              aborted = true;
            }
            return reply(journal, aborted ? 'ABORTED' : 'UNDECIDED');
          },
          readProof: proof,
        );
        expect(await FileProviderSourceJournal.pending(home), isEmpty);
      },
    );

  test(
    'later profile metadata edit conflicts before source restoration and unknown Native id changes nothing',
    () async {
      final journal = await prepare();
      await journal.publish(database: db);
      final published = await target().readAsBytes();
      await db.customStatement(
        "UPDATE profiles SET label = 'Later user label' WHERE id = 7",
      );
      final later = await rawRow();
      await expectLater(
        journal.recover(
          database: db,
          invoke: (_) async => reply(journal, 'UNDECIDED'),
          readProof: proof,
        ),
        throwsFormatException,
      );
      expect(await rawRow(), later);
      expect(await target().readAsBytes(), published);
      await expectLater(
        journal.recover(
          database: db,
          invoke: (_) => throw StateError('unknown id'),
          readProof: proof,
        ),
        throwsStateError,
      );
      expect(await rawRow(), later);
      expect(await target().readAsBytes(), published);
      expect(await FileProviderSourceJournal.pending(home), hasLength(1));
    },
  );

  test(
    'pre-begin cancellation publishes nothing and restart reconciles a precommit mixed file/row crash',
    () async {
      final beforeRow = await rawRow(), beforeProof = await proof();
      final unbegun = await prepare();
      await unbegun.discardUnbegun(database: db);
      expect(await target().exists(), false);
      expect(await rawRow(), beforeRow);
      final crash = await prepare();
      // A crash can leave a flushed source while SQLite rolled back its uncommitted row.
      await target().parent.create(recursive: true);
      await target().writeAsBytes(
        await File(p.join(crash.directory.path, '0.after')).readAsBytes(),
      );
      await db.close();
      db = Database(NativeDatabase(File(p.join(home.path, 'database.sqlite'))));
      final reopened = (await FileProviderSourceJournal.pending(home)).single;
      var aborted = false;
      await reopened.recover(
        database: db,
        invoke: (request) async {
          if (request['operation'] == 'abort') aborted = true;
          return reply(crash, aborted ? 'ABORTED' : 'UNDECIDED');
        },
        readProof: proof,
      );
      expect(await rawRow(), beforeRow);
      expect(await proof(), beforeProof);
      expect(await target().exists(), false);
    },
  );

  for (final decision in ['COMMITTED', 'ABORTED'])
    test(
      '$decision source finalization after a crash preserves a later selection without replaying historical runtime proof',
      () async {
        final before = await rawRow();
        final originalBytes = await original.readAsBytes();
        final journal = await prepare();
        await journal.publish(database: db);
        final published = await rawRow();
        final publishedBytes = await target().readAsBytes();
        const selected = '{"choice":"later-user-selection"}';
        await db.customStatement(
          'UPDATE profiles SET selected_map = ? WHERE id = 7',
          [selected],
        );
        await db.close();
        db = Database(
          NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
        );
        final reopened = (await FileProviderSourceJournal.pending(home)).single;
        await reopened.recover(
          database: db,
          invoke: (request) async {
            expect(request['operation'], isNot('abort'));
            return reply(reopened, decision);
          },
          readProof: () => throw StateError(
            'A durable terminal decision does not reconstruct the prior graph',
          ),
        );
        expect(await rawRow(), {
          ...(decision == 'COMMITTED' ? published : before),
          'selected_map': selected,
        });
        if (decision == 'COMMITTED') {
          expect(await target().readAsBytes(), publishedBytes);
        } else {
          expect(await target().exists(), false);
        }
        expect(await original.readAsBytes(), originalBytes);
        expect(await FileProviderSourceJournal.pending(home), isEmpty);
      },
    );

  for (final decision in ['UNDECIDED', 'COMMITTED', 'ABORTED'])
    test(
      'a third raw source blocks $decision finalization of both the binding row and bytes',
      () async {
        final journal = await prepare();
        await journal.publish(database: db);
        final publishedRow = await rawRow();
        await target().writeAsString('newer source writer');
        await expectLater(
          journal.recover(
            database: db,
            invoke: (_) async => reply(journal, decision),
            readProof: proof,
          ),
          throwsFormatException,
        );
        expect(await rawRow(), publishedRow);
        expect(await target().readAsString(), 'newer source writer');
        expect(await FileProviderSourceJournal.pending(home), hasLength(1));
      },
    );
}
