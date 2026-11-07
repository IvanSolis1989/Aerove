import 'dart:io';
import 'package:fl_clash/common/rule_sql_journal.dart';
import 'package:fl_clash/database/database.dart';
import 'package:flutter_test/flutter_test.dart';
import '../helpers/rule_sql_fixture.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  late RuleDiskFixture f;
  setUp(() async {
    home = await Directory.systemTemp.createTemp('rule-durable-');
    f = RuleDiskFixture(home);
    await f.seed();
  });
  tearDown(() async {
    await f.database.close();
    await home.delete(recursive: true);
  });

  test(
    'commit keeps raw/MRS and business row together and cleans durable token after readback',
    () async {
      var admitted = false;
      final result = await f.publish(admit: () => admitted = true);
      expect(admitted, isTrue);
      expect(result.completionError, isNull);
      expect(await f.raw.readAsString(), 'after.example\n');
      expect(await f.mrs.readAsBytes(), [77, 82, 83, 0, 2]);
      expect(await f.label(), 'after');
      expect(
        (await f.database.ruleSqlInfrastructureProof(resolved: true))['tokens'],
        isEmpty,
      );
    },
  );
  for (final phase in [
    'after-final-stamp',
    'file-renamed:0',
    'file-renamed:1',
    'after-rename',
  ]) {
    test(
      'failure at $phase restores both files and leaves business SQL unchanged',
      () async {
        final fault = StateError('cancel at $phase');
        f.owner.onPhase = (at) async {
          if (at == phase) throw fault;
        };
        await expectLater(f.publish(), throwsA(same(fault)));
        expect(await f.raw.readAsString(), 'before.example\n');
        expect(await f.mrs.readAsBytes(), [77, 82, 83, 0, 1]);
        expect(await f.label(), 'before');
        await f.database.ruleSqlInfrastructureProof(resolved: true);
      },
    );
  }
  test(
    'lost application ACK after actual commit reads COMMITTED instead of undoing raw',
    () async {
      final result = await f.publish(ackLost: true);
      expect(result.completionError, isA<RuleAckLost>());
      expect(await f.label(), 'after');
      expect(await f.raw.readAsString(), 'after.example\n');
      await f.database.ruleSqlInfrastructureProof(resolved: true);
    },
  );
  test(
    'third value preserves original failure and immutable recovery material',
    () async {
      final cancellation = StateError('cancel');
      f.owner.onPhase = (at) async {
        if (at == 'after-rename') {
          await f.raw.writeAsString('third.example\n', flush: true);
          throw cancellation;
        }
      };
      await expectLater(
        f.publish(),
        throwsA(
          isA<RuleSqlPublicationFailure>().having(
            (e) => e.primary,
            'primary',
            same(cancellation),
          ),
        ),
      );
      expect(await f.raw.readAsString(), 'third.example\n');
      expect(await f.label(), 'before');
      final plans = await f.owner.directory
          .list()
          .where(
            (e) =>
                e is Directory &&
                !e.path.split(Platform.pathSeparator).last.startsWith('.'),
          )
          .toList();
      expect(plans, hasLength(1));
      expect(
        await File('${plans.single.path}/0.before').readAsString(),
        'before.example\n',
      );
      await expectLater(
        f.database.ruleSqlInfrastructureProof(resolved: true),
        throwsStateError,
      );
    },
  );
  test(
    'terminal cleanup interruption resumes on the same confirmed healthy owner',
    () async {
      var thrown = false;
      f.owner.onPhase = (at) async {
        if (at == 'terminal-cleanup' && !thrown) {
          thrown = true;
          throw StateError('metadata interrupted');
        }
      };
      await expectLater(f.publish(), throwsA(isA<RuleSqlPublicationFailure>()));
      expect(await f.label(), 'after');
      f.owner.onPhase = null;
      await f.resolve();
      expect(await f.raw.readAsString(), 'after.example\n');
      await f.database.ruleSqlInfrastructureProof(resolved: true);
    },
  );
  test(
    'new same PID connection cannot pretend previous physical owner died',
    () async {
      f.owner.onPhase = (at) async {
        if (at == 'terminal-cleanup') throw StateError('leave plan');
      };
      await expectLater(f.publish(), throwsA(isA<RuleSqlPublicationFailure>()));
      final second = RuleDiskFixture(home);
      try {
        await expectLater(second.resolve(), throwsStateError);
        expect(await f.raw.readAsString(), 'after.example\n');
      } finally {
        await second.database.close();
      }
      f.owner.onPhase = null;
      await f.resolve();
    },
  );
  test(
    'independent business journals cannot nest through borrowed source owner',
    () async {
      await f.owner.exclusive(
        () => f.owner.externalJournal(() async {
          await expectLater(f.publish(), throwsStateError);
        }),
      );
      expect(await f.label(), 'before');
      expect(await f.raw.readAsString(), 'before.example\n');
    },
  );
  test(
    'restore rotates incarnation without importing old tokens or changing business rows',
    () async {
      final before = await f.database.ruleSqlIncarnation();
      await f.owner.exclusive(() async {
        await f.owner.recover();
        await f.database.transaction(f.database.resetRuleSqlIncarnation);
      });
      expect(await f.database.ruleSqlIncarnation(), isNot(before));
      expect(await f.label(), 'before');
      await f.database.ruleSqlInfrastructureProof(resolved: true);
    },
  );
}
