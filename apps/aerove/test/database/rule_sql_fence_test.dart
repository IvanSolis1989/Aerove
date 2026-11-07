import 'dart:async';
import 'dart:io';

import 'package:drift/backends.dart';
import 'package:drift/drift.dart'
    show ApplyInterceptor, OpeningDetails, QueryInterceptor;
import 'package:drift/native.dart';
import 'package:fl_clash/database/rule_sql_executor.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite;

class _User implements QueryExecutorUser {
  _User({this.opened});
  final Future<void> Function(QueryExecutor)? opened;
  @override
  int get schemaVersion => 1;
  @override
  Future<void> beforeOpen(
    QueryExecutor executor,
    OpeningDetails details,
  ) async {
    await executor.runCustom(
      'CREATE TABLE IF NOT EXISTS decisions (id TEXT PRIMARY KEY, decision TEXT NOT NULL)',
    );
    await opened?.call(executor);
  }
}

class _Faults extends QueryInterceptor {
  bool failCommit = false;
  bool loseCommitAck = false;
  bool failRollback = false;
  final commitError = StateError('injected commit failure');
  final rollbackError = StateError('injected rollback failure');
  @override
  Future<void> commitTransaction(TransactionExecutor inner) async {
    if (failCommit) throw commitError;
    await inner.send();
    if (loseCommitAck) throw commitError;
  }

  @override
  Future<void> rollbackTransaction(TransactionExecutor inner) async {
    if (failRollback) throw rollbackError;
    await inner.rollback();
  }
}

Future<void> _transaction(
  QueryExecutor executor,
  QueryExecutorUser user,
  Future<void> Function(TransactionExecutor) body,
) => runZoned(() async {
  final transaction = executor.beginTransaction();
  await transaction.ensureOpen(user);
  try {
    await body(transaction);
    await transaction.send();
  } catch (_) {
    await transaction.rollback();
    rethrow;
  }
});

void main() {
  late Directory directory;
  late File file;
  late NativeDatabase native;
  late sqlite.Database raw;
  late RuleSqlConnectionFence fence;
  late QueryExecutor executor;
  late _Faults faults;
  late _User user;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('aerove-rule-sql-fence-');
    file = File('${directory.path}/database.sqlite');
    faults = _Faults();
    fence = RuleSqlConnectionFence();
    native = NativeDatabase(file, setup: (database) => raw = database);
    executor = fence.wrap(native.interceptWith(faults));
    user = _User();
    await executor.ensureOpen(user);
    await executor.runInsert('INSERT INTO decisions VALUES (?, ?)', [
      'publication',
      'PREPARED',
    ]);
  });
  tearDown(() async {
    await native.close();
    await directory.delete(recursive: true);
  });

  test(
    'commit failure followed by confirmed rollback permits a healthy durable read',
    () async {
      faults.failCommit = true;
      await fence.publication(() async {
        await expectLater(
          _transaction(
            executor,
            user,
            (tx) => tx.runCustom('UPDATE decisions SET decision = ?', [
              'COMMITTED',
            ]),
          ),
          throwsA(same(faults.commitError)),
        );
        expect(raw.autocommit, isTrue);
        expect(fence.isUncertain, isFalse);
        expect(
          (await executor.runSelect(
            'SELECT decision FROM decisions',
            [],
          )).single['decision'],
          'PREPARED',
        );
      });
    },
  );

  test(
    'double failure blocks an already waiting writer before the delegate can run',
    () async {
      final finishing = Completer<void>();
      final release = Completer<void>();
      faults.failCommit = true;
      faults.failRollback = true;
      final publishing = fence.publication(() async {
        try {
          await _transaction(
            executor,
            user,
            (tx) => tx.runCustom('UPDATE decisions SET decision = ?', [
              'COMMITTED',
            ]),
          );
        } catch (error) {
          finishing.complete();
          await release.future;
          rethrow;
        }
      });
      final failed = expectLater(
        publishing,
        throwsA(same(faults.rollbackError)),
      );
      await finishing.future;
      final writing = executor.runInsert(
        'INSERT INTO decisions VALUES (?, ?)',
        ['later', 'COMMITTED'],
      );
      final blocked = expectLater(
        writing,
        throwsA(isA<RuleSqlConnectionUncertain>()),
      );
      expect(raw.autocommit, isFalse);
      expect(
        raw.select('SELECT decision FROM decisions').single['decision'],
        'COMMITTED',
      );
      final observer = sqlite.sqlite3.open(
        file.path,
        mode: sqlite.OpenMode.readOnly,
      );
      try {
        expect(
          observer.select('SELECT decision FROM decisions').single['decision'],
          'PREPARED',
        );
      } finally {
        observer.close();
      }
      release.complete();
      await failed;
      await blocked;
      expect(
        raw.select('SELECT 1 FROM decisions WHERE id = ?', ['later']),
        isEmpty,
      );
      expect(fence.uncertainty!.commitFailure, same(faults.commitError));
      expect(() => RuleSqlConnectionFence().wrap(executor), throwsStateError);
    },
  );

  test(
    'lost acknowledgement after real COMMIT is unknown, never an abort decision',
    () async {
      faults.loseCommitAck = true;
      faults.failRollback = true;
      await expectLater(
        fence.publication(
          () => _transaction(
            executor,
            user,
            (tx) => tx.runCustom('UPDATE decisions SET decision = ?', [
              'COMMITTED',
            ]),
          ),
        ),
        throwsA(same(faults.rollbackError)),
      );
      expect(raw.autocommit, isTrue);
      expect(fence.isUncertain, isTrue);
      await expectLater(
        executor.runSelect('SELECT decision FROM decisions', []),
        throwsA(isA<RuleSqlConnectionUncertain>()),
      );
      final observer = sqlite.sqlite3.open(
        file.path,
        mode: sqlite.OpenMode.readOnly,
      );
      try {
        expect(
          observer.select('SELECT decision FROM decisions').single['decision'],
          'COMMITTED',
        );
      } finally {
        observer.close();
      }
    },
  );

  test(
    'an existing transaction finishes before publication acquires the connection',
    () async {
      final entered = Completer<void>();
      final release = Completer<void>();
      var publicationEntered = false;
      final writing = _transaction(executor, user, (tx) async {
        await tx.runCustom('UPDATE decisions SET decision = ?', ['PRIOR']);
        entered.complete();
        await release.future;
      });
      await entered.future;
      final publishing = fence.publication(() async {
        publicationEntered = true;
        expect(
          (await executor.runSelect(
            'SELECT decision FROM decisions',
            [],
          )).single['decision'],
          'PRIOR',
        );
      });
      await pumpEventQueue();
      expect(publicationEntered, isFalse);
      release.complete();
      await writing;
      await publishing;
    },
  );

  test(
    'closing a transaction uses confirmed rollback rather than the default no-op close',
    () async {
      final transaction = executor.beginTransaction();
      await transaction.ensureOpen(user);
      await transaction.runCustom('UPDATE decisions SET decision = ?', [
        'COMMITTED',
      ]);
      await transaction.close();
      expect(raw.autocommit, isTrue);
      expect(
        (await executor.runSelect(
          'SELECT decision FROM decisions',
          [],
        )).single['decision'],
        'PREPARED',
      );
    },
  );

  test(
    'beforeOpen receives a bound guarded executor for migration work',
    () async {
      final otherFile = File('${directory.path}/migration.sqlite');
      final otherNative = NativeDatabase(otherFile);
      final otherFence = RuleSqlConnectionFence();
      final guarded = otherFence.wrap(otherNative);
      try {
        await guarded.ensureOpen(
          _User(
            opened: (opening) => _transaction(
              opening,
              _User(),
              (tx) => tx.runCustom('INSERT INTO decisions VALUES (?, ?)', [
                'migration',
                'COMMITTED',
              ]),
            ),
          ),
        );
        expect(
          (await guarded.runSelect(
            'SELECT decision FROM decisions',
            [],
          )).single['decision'],
          'COMMITTED',
        );
      } finally {
        await otherNative.close();
      }
    },
  );
}
