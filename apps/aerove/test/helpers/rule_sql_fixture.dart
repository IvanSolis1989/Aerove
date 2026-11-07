import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/common/rule_sql_journal.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:path/path.dart' as p;

class RuleDiskFixture {
  RuleDiskFixture(this.home)
    : database = db.Database(
        NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
      );
  final Directory home;
  final db.Database database;
  late final owner = RuleSqlJournalOwner(database, home);
  static const original = ClashProvider(
    id: 71,
    label: 'before',
    behavior: RuleProviderBehavior.domain,
    format: RuleProviderFormat.text,
  );
  File get raw => File(
    p.join(home.path, 'providers/rules/11111111111111111111111111111111'),
  );
  File get mrs => File('${raw.path}.mrs');
  File get candidate => File(p.join(home.path, 'candidate.raw'));
  File get compiled => File(p.join(home.path, 'candidate.mrs'));
  Future<void> seed() async {
    await database.clashProviders.put(original.toCompanion());
    await raw.parent.create(recursive: true);
    await raw.writeAsString('before.example\n', flush: true);
    await mrs.writeAsBytes([77, 82, 83, 0, 1], flush: true);
    await candidate.writeAsString('after.example\n', flush: true);
    await compiled.writeAsBytes([77, 82, 83, 0, 2], flush: true);
  }

  Future<RuleSqlPublicationResult> publish({
    bool ackLost = false,
    void Function()? admit,
    Future<List<RuleSqlRowChange>> Function()? captureRows,
  }) => owner.exclusive(() async {
    await owner.recover();
    final incarnation = await database.ruleSqlIncarnation();
    return owner.publish(
      providerId: 71,
      expectedIncarnation: incarnation,
      files: [
        RuleSqlFileChange(raw.path, candidate.path),
        RuleSqlFileChange(mrs.path, compiled.path),
      ],
      captureRows:
          captureRows ??
          () async => [
            const RuleSqlRowChange(
              'clash_providers',
              71,
              {'id': 71, 'label': 'before'},
              {'id': 71, 'label': 'after'},
            ),
          ],
      transaction: (before, finish) async {
        await database.transaction(() async {
          await before();
          await database.customStatement(
            'UPDATE clash_providers SET label = ? WHERE id = ?',
            ['after', 71],
          );
          await finish();
          // Last synchronous admission; no await afterwards.
          admit?.call();
        });
        if (ackLost) throw const RuleAckLost();
      },
    );
  });
  Future<String> label() async =>
      (await database
              .customSelect('SELECT label FROM clash_providers WHERE id = 71')
              .get())
          .single
          .read<String>('label');
  Future<void> resolve() => owner.exclusive(owner.recover);
}

class RuleAckLost implements Exception {
  const RuleAckLost();
}
