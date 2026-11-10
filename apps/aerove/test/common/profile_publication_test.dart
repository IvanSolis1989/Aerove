import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:fl_clash/common/profile_update.dart';
import 'package:fl_clash/common/rule_sql_journal.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:flutter_test/flutter_test.dart';
import '../helpers/profile_publication_fixture.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  late ProfilePublicationFixture f;
  setUp(() async {
    home = await Directory.systemTemp.createTemp('profile-publication-');
    f = ProfilePublicationFixture(home);
    await f.seed();
  });
  tearDown(() async {
    await f.close();
    await home.delete(recursive: true);
  });

  test(
    'production notifier commits metadata and both files together',
    () async {
      await f.publish();
      await f.expectVersion(true);
      expect(
        f.container!.read(profilesProvider).single,
        ProfilePublicationFixture.after,
      );
    },
  );

  for (final phase in [
    'prepared',
    'file-renamed:0',
    'file-renamed:1',
    'after-rename',
  ]) {
    test('failure at $phase restores files and metadata', () async {
      final failure = StateError('synthetic publication failure');
      f.owner.onPhase = (at) async {
        if (at == phase) throw failure;
      };
      await expectLater(f.publish(), throwsA(same(failure)));
      await f.expectVersion(false);
    });
  }

  test('replacement can remove original input with durable rollback', () async {
    final prepared = await f.prepare(distinctInput: false);
    f.owner.onPhase = (at) async {
      if (at == 'after-rename') throw StateError('cancel');
    };
    await expectLater(f.publish(prepared: prepared), throwsStateError);
    await f.expectVersion(false);
    f.owner.onPhase = null;
    await f.publish(prepared: await f.prepare(distinctInput: false));
    await f.expectVersion(true, distinctInput: false);
  });

  test('new import rollback removes both new files and row', () async {
    final fresh = ProfilePublicationFixture.before.copyWith(id: 99);
    final candidate = await fresh.prepareFile(
      Uint8List.fromList(utf8.encode('new-yaml')),
      originalInputBytes: Uint8List.fromList(utf8.encode('new-input')),
      validate: (_, _) async => '',
    );
    f.owner.onPhase = (at) async {
      if (at == 'file-renamed:1') throw StateError('cancel');
    };
    try {
      await expectLater(
        f.notifier.putCommitted(fresh, preparedFile: candidate),
        throwsStateError,
      );
      expect(await f.database.profilesDao.get(99), isNull);
      expect(await candidate.target.exists(), isFalse);
      expect(await candidate.original!.exists(), isFalse);
      await f.database.ruleSqlInfrastructureProof(resolved: true);
    } finally {
      await candidate.dispose();
    }
  });

  test(
    'cancel before admission cannot publish, cancel after admission joins',
    () async {
      var intent = ProfileUpdateIntent(
        isSourceCurrent: () => true,
        automatic: false,
      );
      f.owner.onPhase = (at) async {
        if (at == 'prepared') intent.cancel();
      };
      await expectLater(
        f.publish(
          isCurrent: () => intent.isCurrent,
          admission: intent.beginCommit,
        ),
        throwsA(isA<UpdateCancelledException>()),
      );
      await f.expectVersion(false);
      intent = ProfileUpdateIntent(
        isSourceCurrent: () => true,
        automatic: false,
      );
      f.owner.onPhase = (at) async {
        if (at == 'file-renamed:0') intent.cancel();
      };
      await f.publish(
        isCurrent: () => intent.isCurrent,
        admission: intent.beginCommit,
      );
      await f.expectVersion(true);
      expect(intent.cancelled, isFalse);
    },
  );

  test('later source wins over an earlier prepared snapshot', () async {
    final first = await f.prepare();
    final second = await f.prepare();
    await f.publish(prepared: first);
    await expectLater(
      f.publish(prepared: second),
      throwsA(isA<UpdateCancelledException>()),
    );
    await f.expectVersion(true);
  });

  test(
    'parallel preparations serialize without restoring a stale backup',
    () async {
      final first = await f.prepare();
      final second = await f.prepare();
      final entered = Completer<void>();
      final release = Completer<void>();
      f.owner.onPhase = (at) async {
        if (at == 'prepared' && !entered.isCompleted) {
          entered.complete();
          await release.future;
        }
      };
      final firstRun = f.publish(prepared: first);
      await entered.future;
      final rejected = expectLater(
        f.publish(prepared: second),
        throwsA(isA<UpdateCancelledException>()),
      );
      release.complete();
      await Future.wait([firstRun, rejected]);
      await f.expectVersion(true);
    },
  );

  test(
    'profile publication cannot invert the rule owner and profile queue',
    () async {
      final prepared = await f.prepare();
      final ownerHeld = Completer<void>();
      final enterProfileQueue = Completer<void>();
      final ruleRename = f.owner.exclusive(() async {
        ownerHeld.complete();
        await enterProfileQueue.future;
        await f.notifier.withRulePublicationSerial(
          () => f.database.transaction(() async {
            await f.database.profilesDao.get(71);
          }),
        );
      });
      await ownerHeld.future;
      final publication = f.publish(prepared: prepared);
      await pumpEventQueue();
      enterProfileQueue.complete();
      await Future.wait([
        ruleRename,
        publication,
      ]).timeout(const Duration(seconds: 5));
      await f.expectVersion(true);
    },
  );

  test(
    'committed cleanup failure retains replay and never uses stale rollback',
    () async {
      f.owner.onPhase = (at) async {
        if (at == 'terminal-cleanup') throw StateError('cleanup interruption');
      };
      await expectLater(f.publish(), throwsA(isA<RuleSqlPublicationFailure>()));
      expect(
        await f.database.profilesDao.get(71),
        ProfilePublicationFixture.after,
      );
      expect(await f.yaml.readAsString(), 'new-yaml');
      f.owner.onPhase = null;
      await f.recover();
      await f.expectVersion(true);
    },
  );

  test(
    'journal metadata excludes source URL, age key and subscription data',
    () async {
      f.owner.onPhase = (at) async {
        if (at != 'prepared') return;
        final plans = await f.owner.directory
            .list(recursive: true)
            .where((e) => e.path.endsWith('/plan.json'))
            .toList();
        final text = await File(plans.single.path).readAsString();
        expect(text, isNot(contains('private-token')));
        expect(text, isNot(contains('private-age-key')));
        expect((jsonDecode(text) as Map)['rows'], isEmpty);
      };
      await f.publish();
      await f.expectVersion(true);
    },
  );

  test(
    'oversized staging is rejected without retaining partial private copies',
    () async {
      final prepared = await f.prepare();
      final handle = await prepared.stage.open(mode: FileMode.append);
      await handle.truncate(64 * 1024 * 1024 + 1);
      await handle.close();
      await expectLater(f.publish(prepared: prepared), throwsStateError);
      await f.expectVersion(false);
      expect(
        await f.owner.directory.list().where((e) => e is Directory).toList(),
        isEmpty,
      );
    },
  );

  test('a symbolic profile destination is never followed', () async {
    final prepared = await f.prepare();
    final outside = File('${home.path}/untouched')
      ..writeAsStringSync('private');
    await f.input.delete();
    final link = await Link(f.input.path).create(outside.path);
    try {
      await expectLater(
        f.publish(prepared: prepared),
        throwsA(isA<Exception>()),
      );
      expect(await outside.readAsString(), 'private');
    } finally {
      await link.delete();
      await f.input.writeAsString('old-input');
    }
    await f.expectVersion(false);
  });

  test('profile publication rejects another profile target', () async {
    final prepared = await f.prepare();
    try {
      await expectLater(
        f.owner.exclusive(
          () => f.owner.publishProfile(
            profileId: 72,
            files: [
              RuleSqlFileChange(prepared.target.path, prepared.stage.path),
              RuleSqlFileChange(
                prepared.original!.path,
                prepared.originalStage!.path,
              ),
            ],
            transaction: (_, _) async => fail('must not enter SQL'),
          ),
        ),
        throwsStateError,
      );
      await f.expectVersion(false);
    } finally {
      await prepared.dispose();
    }
  });
}
