// Explicit OS subprocess fixture: tooling/test_rule_sql_process.py owns its PID.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/file.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/common/rule_sql_journal.dart';
import 'package:fl_clash/common/snowflake.dart' show uuidV4;
import 'package:flutter_test/flutter_test.dart';

import 'rule_sql_fixture.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('owned OS process publication and recovery', () async {
    final env = Platform.environment;
    final home = Directory(env['AEROVE_RULE_HOME']!);
    final f = RuleDiskFixture(home);
    f.owner.onPhase = (at) async {
      if (at != env['AEROVE_RULE_PHASE']) return;
      await File('${home.path}/worker-ready.json').writeAsStringAtomically(
        jsonEncode({
          'nonce': env['AEROVE_RULE_NONCE'],
          'pid': pid,
          'phase': at,
        }),
      );
      await Completer<void>().future;
    };
    try {
      if (env['AEROVE_RULE_MODE'] == 'admission-restore' ||
          env['AEROVE_RULE_MODE'] == 'prepared-identity') {
        await f.seed();
        final second = RuleDiskFixture(home);
        final entered = Completer<void>(), release = Completer<void>();
        final beforeAdmission = env['AEROVE_RULE_MODE'] == 'admission-restore';
        final phases = <String>[];
        f.owner.onPhase = (at) async {
          phases.add(at);
          if (at == (beforeAdmission ? 'before-plan' : 'prepared')) {
            entered.complete();
            await release.future;
          }
        };
        final first = f.publish(
          captureRows: () async => [
            RuleSqlRowChange(
              'clash_providers',
              71,
              {'id': 71, 'label': await f.label()},
              {'id': 71, 'label': 'after'},
            ),
          ],
        );
        final rejected = expectLater(
          first,
          throwsA(
            beforeAdmission
                ? isA<StateError>()
                : isA<RuleSqlPublicationFailure>(),
          ),
        );
        try {
          await entered.future;
          if (beforeAdmission) {
            // A has passed its old I1 check, but has no plan/token. B performs
            // the actual restore API, including its physical identity change.
            await second.owner.exclusive(() async {
              await second.owner.recover();
              await second.owner.externalJournal(() async {
                await second.raw.writeAsString(
                  'restored.example\n',
                  flush: true,
                );
                await second.mrs.writeAsBytes([77, 82, 83, 0, 3], flush: true);
                await second.database.restore(
                  const [],
                  const [],
                  const [],
                  const [],
                  const [],
                  clashProviders: [
                    RuleDiskFixture.original.copyWith(label: 'restored'),
                  ],
                );
              });
            });
          } else {
            // An independent direct SQL writer changes the identity after the
            // PREPARED token. Even that third identity must not reach rename.
            await second.database.transaction(
              () => second.database.customStatement(
                'UPDATE rule_sql_identity SET incarnation = ? WHERE slot = 1',
                [uuidV4],
              ),
            );
          }
          final durable = await second.database.ruleSqlIncarnation();
          release.complete();
          await rejected;
          expect(phases.where((p) => p.startsWith('file-renamed:')), isEmpty);
          expect(await second.database.ruleSqlIncarnation(), durable);
          expect(await f.label(), beforeAdmission ? 'restored' : 'before');
          expect(
            await f.raw.readAsString(),
            beforeAdmission ? 'restored.example\n' : 'before.example\n',
          );
          expect(await f.mrs.readAsBytes(), [
            77,
            82,
            83,
            0,
            beforeAdmission ? 3 : 1,
          ]);
          if (beforeAdmission)
            await second.database.ruleSqlInfrastructureProof(resolved: true);
        } finally {
          if (!release.isCompleted) release.complete();
          await second.database.close();
        }
      } else if (env['AEROVE_RULE_MODE'] == 'parallel') {
        await f.seed();
        final second = RuleDiskFixture(home);
        final gate = Completer<void>();
        var arrivals = 0;
        Future<void> barrier(String at) async {
          if (at == 'before-plan') {
            if (++arrivals == 2) gate.complete();
            await gate.future;
          }
        }

        f.owner.onPhase = barrier;
        second.owner.onPhase = barrier;
        try {
          Future<Object> outcome(RuleDiskFixture source) async {
            try {
              return await source.publish();
            } catch (error) {
              return error;
            }
          }

          final outcomes = await Future.wait([outcome(f), outcome(second)]);
          expect(outcomes.whereType<RuleSqlPublicationResult>(), hasLength(1));
          expect(
            outcomes.whereType<Exception>().length +
                outcomes.whereType<Error>().length,
            1,
          );
          expect(await f.raw.readAsString(), 'after.example\n');
          expect(await f.mrs.readAsBytes(), [77, 82, 83, 0, 2]);
          expect(await f.label(), 'after');
          await f.database.ruleSqlInfrastructureProof(resolved: true);
        } finally {
          await second.database.close();
        }
      } else if (env['AEROVE_RULE_MODE'] == 'publish') {
        await f.seed();
        await f.publish();
      } else if (env['AEROVE_RULE_MODE'] == 'third') {
        await expectLater(f.resolve(), throwsStateError);
        expect(await f.raw.readAsString(), 'third.example\n');
        expect(await f.label(), 'before');
      } else {
        await f.resolve();
        final after = env['AEROVE_RULE_EXPECT'] == 'after';
        expect(
          await f.raw.readAsString(),
          after ? 'after.example\n' : 'before.example\n',
        );
        expect(await f.mrs.readAsBytes(), [77, 82, 83, 0, after ? 2 : 1]);
        expect(await f.label(), after ? 'after' : 'before');
        await f.database.ruleSqlInfrastructureProof(resolved: true);
      }
    } finally {
      await f.database.close();
    }
  }, timeout: const Timeout(Duration(minutes: 3)));
}
