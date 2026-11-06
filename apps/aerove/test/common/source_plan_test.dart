import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/file_provider_source.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/source_plan.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  late File source;
  const id = '12345678-1234-4234-8234-123456789abc';
  const before = <String, dynamic>{'schema': 2, 'tables': {}, 'files': []};
  setUp(() async {
    home = await Directory.systemTemp.createTemp('provider-source-journal-');
    source = File(
      p.join(
        home.path,
        fileProviderSourcePath(7, 'proxy-providers', 'fixture'),
      ),
    );
    await source.create(recursive: true);
    await source.writeAsString('original raw source');
    registerFileProviderSource(
      homeDirPath: home.path,
      profileId: 7,
      section: 'proxy-providers',
      name: 'fixture',
      sourcePath: source.path,
    );
  });
  tearDown(() async {
    await home.delete(recursive: true);
  });
  Future<FileProviderSourceJournal> prepare() =>
      FileProviderSourceJournal.prepare(
        home: home,
        id: id,
        profileId: 7,
        namespace: 'proxy-providers',
        name: 'fixture',
        expectedRevision: 'prior-actual-revision',
        expected: {'digest': 'prior'},
        beforeProof: before,
        bytes: utf8.encode('edited raw source'),
      );
  Map<String, dynamic> reply(
    FileProviderSourceJournal journal,
    String decision,
  ) => {
    'schemaVersion': 1,
    'transactionId': id,
    'planSha256': journal.planSha256,
    'state': decision == 'UNDECIDED' ? 'ABORT_REQUIRED' : decision,
    'decision': decision,
    'sourceAction': decision == 'UNDECIDED' ? 'restore-old' : 'retain-new',
  };

  test(
    'prepare changes neither raw bytes nor registration; interrupted publication restores both before actual abort',
    () async {
      final marker = File('${source.path}.source.json');
      final markerBefore = await marker.readAsBytes();
      final journal = await prepare();
      expect(await source.readAsString(), 'original raw source');
      expect(await marker.readAsBytes(), markerBefore);
      await journal.publish();
      final reopened = (await FileProviderSourceJournal.pending(home)).single;
      expect(reopened.journalSha256, journal.journalSha256);
      final operations = <String>[];
      await reopened.recover(
        invoke: (request) async {
          operations.add(request['operation'] as String);
          if (request['operation'] == 'abort') {
            expect(await source.readAsString(), 'original raw source');
            expect(await marker.readAsBytes(), markerBefore);
            expect((request['restored'] as Map)['proof'], before);
            return reply(journal, 'ABORTED');
          }
          return reply(
            journal,
            operations.length == 1 ? 'UNDECIDED' : 'ABORTED',
          );
        },
        readProof: () async => before,
      );
      expect(operations, ['query', 'abort', 'query']);
      expect(await FileProviderSourceJournal.pending(home), isEmpty);
    },
  );

  test(
    'lost apply acknowledgement keeps committed source and never asks for abort',
    () async {
      final journal = await prepare();
      await journal.publish();
      final operations = <String>[];
      await journal.recover(
        invoke: (request) async {
          operations.add(request['operation'] as String);
          return reply(journal, 'COMMITTED');
        },
        readProof: () =>
            throw StateError('Committed source must not be restored'),
      );
      expect(operations, ['query', 'query']);
      expect(await source.readAsString(), 'edited raw source');
      expect(await FileProviderSourceJournal.pending(home), isEmpty);
    },
  );

  test(
    'cancellation before invoking begin removes only private staging and never publishes a source',
    () async {
      final journal = await prepare();
      await journal.discardUnbegun();
      expect(await source.readAsString(), 'original raw source');
      expect(await FileProviderSourceJournal.pending(home), isEmpty);
    },
  );

  test(
    'a newer business writer or unknown native identity cannot be overwritten or called successful',
    () async {
      final journal = await prepare();
      await journal.publish();
      await source.writeAsString('newer separately committed source');
      await expectLater(
        journal.recover(
          invoke: (request) async => reply(journal, 'UNDECIDED'),
          readProof: () async => before,
        ),
        throwsFormatException,
      );
      expect(await source.readAsString(), 'newer separately committed source');
      expect(await FileProviderSourceJournal.pending(home), hasLength(1));
      await expectLater(
        journal.recover(
          invoke: (_) => throw StateError('unknown native id'),
          readProof: () async => before,
        ),
        throwsStateError,
      );
      expect(await source.readAsString(), 'newer separately committed source');
    },
  );

  test(
    'first producer registration admits only its complete exact pair, preserving all old proof fields',
    () {
      final initial = <String, dynamic>{
        'schema': 2,
        'tables': {'profiles': 'unchanged'},
        'files': [
          {'path': 'profiles/7.yaml', 'bytes': 1, 'sha256': 'old'},
        ],
      };
      final relative = fileProviderSourcePath(7, 'proxy-providers', 'fixture');
      final additions = [relative, '$relative.source.json'];
      final complete = {
        ...initial,
        'files': [
          ...initial['files'] as List,
          for (final path in additions)
            {'path': path, 'bytes': 3, 'sha256': 'captured'},
        ],
      };
      expect(
        admitProviderSourceRegistration(initial, complete, additions),
        complete,
      );
      expect(
        () => admitProviderSourceRegistration(initial, complete, [relative]),
        throwsFormatException,
      );
      expect(
        () => admitProviderSourceRegistration(initial, {
          ...complete,
          'tables': {},
        }, additions),
        throwsFormatException,
      );
      expect(
        () => admitProviderSourceRegistration(initial, {
          ...complete,
          'files': [
            {'path': 'profiles/7.yaml', 'bytes': 2, 'sha256': 'changed'},
            ...(complete['files'] as List).skip(1),
          ],
        }, additions),
        throwsFormatException,
      );
      expect(
        () => admitProviderSourceRegistration(initial, {
          ...complete,
          'files': [
            ...complete['files'] as List,
            {'path': 'unwitnessed', 'bytes': 1, 'sha256': 'new'},
          ],
        }, additions),
        throwsFormatException,
      );
    },
  );

  test(
    'detached binding is physically separate and retains historical definition without treating it as active path',
    () async {
      final prepared = prepareDetachedProviderSource(
        profileId: 7,
        key: 'proxy-providers/fixture',
        bytes: utf8.encode('local edited source'),
        originalDefinition: {'type': 'file', 'path': 'original-owner-source'},
      );
      expect(
        prepared.binding.relativePath,
        isNot(fileProviderSourcePath(7, 'proxy-providers', 'fixture')),
      );
      final target = File(p.join(home.path, prepared.binding.relativePath));
      await target.writeAsBytes(prepared.bytes);
      registerFileProviderSource(
        homeDirPath: home.path,
        profileId: 7,
        section: 'proxy-providers',
        name: 'fixture',
        sourcePath: target.path,
        detached: true,
      );
      final compiled = applyLocalProviderSources(
        {},
        profileId: 7,
        homeDirPath: home.path,
        bindings: {'proxy-providers/fixture': prepared.binding},
      );
      expect((compiled['proxy-providers'] as Map)['fixture'], {
        'type': 'file',
        'path': target.path,
      });
      expect(
        prepared.binding.originalDefinition['path'],
        'original-owner-source',
      );
      expect(await source.readAsString(), 'original raw source');
      expect(nativeLiteJsonHash(prepared.binding.toJson()), isNotEmpty);
    },
  );

  test(
    'real selected SQLite proof includes registered source pairs and detached bytes, excluding historical definition paths',
    () async {
      SharedPreferences.setMockInitialValues({});
      final storage = await SharedPreferences.getInstance();
      final db = Database(NativeDatabase.memory());
      try {
        final detached = prepareDetachedProviderSource(
          profileId: 7,
          key: 'proxy-providers/fixture',
          bytes: utf8.encode('detached bytes'),
          originalDefinition: {
            'type': 'file',
            'path': 'absent-historical-source',
          },
        );
        final target = File(p.join(home.path, detached.binding.relativePath));
        await target.writeAsBytes(detached.bytes);
        registerFileProviderSource(
          homeDirPath: home.path,
          profileId: 7,
          section: 'proxy-providers',
          name: 'fixture',
          sourcePath: target.path,
          detached: true,
        );
        await db.profilesDao.putAll([
          const Profile(
            id: 7,
            type: ProfileType.custom,
            label: 'Selected',
            autoUpdateDuration: Duration.zero,
          ).toCompanion(),
        ]);
        await db.customStatement(
          'UPDATE profiles SET overrides = ? WHERE id = 7',
          [
            jsonEncode({
              'localProviderSources': {
                'proxy-providers/fixture': detached.binding.toJson(),
              },
            }),
          ],
        );
        final proof = await nativeLiteBusinessSource(
          db,
          home,
          storage,
          Config(
            currentProfileId: 7,
            themeProps: defaultThemeProps,
            patchClashConfig: newPatchClashConfig('meow-rs'),
          ),
        );
        final paths = (proof['files'] as List)
            .cast<Map>()
            .map((file) => file['path'])
            .toSet();
        expect(paths, {
          fileProviderSourcePath(7, 'proxy-providers', 'fixture'),
          '${fileProviderSourcePath(7, 'proxy-providers', 'fixture')}.source.json',
          detached.binding.relativePath,
          '${detached.binding.relativePath}.source.json',
        });
        await target.writeAsString('modified detached source');
        final changed = await nativeLiteBusinessSource(db, home, storage, null);
        expect(nativeLiteJsonHash(changed), isNot(nativeLiteJsonHash(proof)));
      } finally {
        await db.close();
      }
    },
  );
}
