import 'dart:async';
import 'dart:io';

import 'package:drift/backends.dart';
import 'package:drift/drift.dart'
    show
        ApplyInterceptor,
        CouldNotRollBackException,
        OpeningDetails,
        QueryInterceptor;
import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart' as app;
import 'package:fl_clash/database/rule_sql_executor.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite;

class _CommitCounter extends QueryInterceptor {
  int calls = 0;
  @override
  Future<void> commitTransaction(TransactionExecutor inner) {
    calls++;
    return inner.send();
  }
}

class _SqlFaultDelegate extends DatabaseDelegate {
  _SqlFaultDelegate(this.native);
  final DatabaseDelegate native;
  bool failCommit = false;
  bool failRollback = false;
  int? failSavepointRollback;
  Completer<void>? commitEntered;
  Completer<void>? commitRelease;
  final statements = <String>[];
  final reachedLater = <String>[];
  @override
  TransactionDelegate get transactionDelegate =>
      const NoTransactionDelegate(start: 'BEGIN IMMEDIATE');
  @override
  DbVersionDelegate get versionDelegate => native.versionDelegate;
  @override
  FutureOr<bool> get isOpen => native.isOpen;
  @override
  Future<void> open(QueryExecutorUser user) => native.open(user);
  @override
  Future<void> close() => native.close();
  @override
  void notifyDatabaseOpened(OpeningDetails details) =>
      native.notifyDatabaseOpened(details);
  void record(String statement) {
    statements.add(statement);
    if (statement.contains('later-')) reachedLater.add(statement);
  }

  @override
  Future<QueryResult> runSelect(String statement, List<Object?> args) {
    record(statement);
    return native.runSelect(statement, args);
  }

  @override
  Future<int> runInsert(String statement, List<Object?> args) {
    record(statement);
    return native.runInsert(statement, args);
  }

  @override
  Future<int> runUpdate(String statement, List<Object?> args) {
    record(statement);
    return native.runUpdate(statement, args);
  }

  @override
  Future<void> runBatched(BatchedStatements statements) =>
      native.runBatched(statements);
  @override
  Future<void> runCustom(String statement, List<Object?> args) async {
    record(statement);
    if (statement == 'COMMIT TRANSACTION' && commitEntered != null) {
      commitEntered!.complete();
      await commitRelease!.future;
    }
    final fail =
        failCommit && statement == 'COMMIT TRANSACTION' ||
        failRollback && statement == 'ROLLBACK TRANSACTION' ||
        failSavepointRollback != null &&
            statement == 'ROLLBACK TO s$failSavepointRollback';
    await native.runCustom(
      fail ? 'INVALID_RULE_FENCE_TEST $statement' : statement,
      args,
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory directory;
  late sqlite.Database raw;
  late app.Database database;
  late _SqlFaultDelegate delegate;
  late _CommitCounter counter;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp(
      'aerove-sql-unit-regression-',
    );
    final native = NativeDatabase(
      File('${directory.path}/database.sqlite'),
      setup: (handle) => raw = handle,
    );
    delegate = _SqlFaultDelegate(native.delegate);
    counter = _CommitCounter();
    database = app.Database(
      DelegatedDatabase(delegate, isSequential: true).interceptWith(counter),
    );
    await database.customStatement(
      'CREATE TABLE decisions (id TEXT PRIMARY KEY, decision TEXT NOT NULL)',
    );
    await database.customStatement(
      "INSERT INTO decisions VALUES ('publication', 'PREPARED')",
    );
    delegate.statements.clear();
    counter.calls = 0;
  });
  tearDown(() async {
    await database.close();
    await directory.delete(recursive: true);
  });

  test(
    'same-publication root SQL, tx and exclusive stay outside inner backlog after real rollback-finally release',
    () async {
      final entered = Completer<void>();
      final release = Completer<void>();
      delegate.failCommit = true;
      delegate.failRollback = true;
      final publication = database.ruleSqlFence.publication(() async {
        final writing = database.transaction(() async {
          await database.customStatement(
            "UPDATE decisions SET decision = 'COMMITTED'",
          );
          expect(delegate.isInTransaction, isTrue);
          expect(raw.autocommit, isFalse);
          entered.complete();
          await release.future;
        });
        final original = expectLater(
          writing,
          throwsA(
            isA<CouldNotRollBackException>()
                .having(
                  (error) => error.cause,
                  'real COMMIT SQL error',
                  isA<sqlite.SqliteException>(),
                )
                .having(
                  (error) => error.exception,
                  'real ROLLBACK SQL error',
                  isA<sqlite.SqliteException>(),
                ),
          ),
        );
        await entered.future;
        final rootWrite = database.customStatement(
          "INSERT INTO decisions VALUES ('later-root', 'COMMITTED')",
        );
        final rootRead = database
            .customSelect('SELECT decision FROM decisions /* later-select */')
            .get();
        final transaction = database.executor.beginTransaction();
        final transactionOpen = transaction.ensureOpen(database);
        final exclusive = database.executor.beginExclusive();
        final exclusiveOpen = exclusive.ensureOpen(database);
        final denied = [
          expectLater(rootWrite, throwsA(isA<RuleSqlConnectionUncertain>())),
          expectLater(rootRead, throwsA(isA<RuleSqlConnectionUncertain>())),
          expectLater(
            transactionOpen,
            throwsA(isA<RuleSqlConnectionUncertain>()),
          ),
          expectLater(
            exclusiveOpen,
            throwsA(isA<RuleSqlConnectionUncertain>()),
          ),
        ];
        await pumpEventQueue();
        expect(delegate.reachedLater, isEmpty);
        release.complete();
        await original;
        await Future.wait(denied);
        expect(delegate.statements, contains('ROLLBACK TRANSACTION'));
        expect(delegate.isInTransaction, isFalse);
        expect(raw.autocommit, isFalse);
        expect(delegate.reachedLater, isEmpty);
        expect(database.ruleSqlFence.isUncertain, isTrue);
      });
      await expectLater(
        publication,
        throwsA(isA<RuleSqlConnectionUncertain>()),
      );
    },
  );

  test(
    'same-publication waiting units run normally after a healthy first transaction',
    () async {
      await database.ruleSqlFence.publication(() async {
        final root = database.executor.beginTransaction();
        await root.ensureOpen(database);
        await root.runCustom("UPDATE decisions SET decision = 'FIRST'");
        final later = database.customStatement(
          "INSERT INTO decisions VALUES ('later-healthy', 'COMMITTED')",
        );
        await pumpEventQueue();
        expect(delegate.reachedLater, isEmpty);
        await root.send();
        await later;
        expect(delegate.reachedLater, hasLength(1));
      });
    },
  );

  test(
    'a terminal send never reaches inner send or commits a second live transaction',
    () async {
      await database.ruleSqlFence.publication(() async {
        final first = database.executor.beginTransaction();
        await first.ensureOpen(database);
        await first.send();
        final second = database.executor.beginTransaction();
        await second.ensureOpen(database);
        final before = counter.calls;
        await expectLater(first.send(), throwsStateError);
        expect(counter.calls, before);
        expect(raw.autocommit, isFalse);
        await second.rollback();
      });
    },
  );

  test('a transaction cannot be rebound as a fresh connection root', () async {
    final transaction = database.executor.beginTransaction();
    expect(() => database.ruleSqlFence.wrap(transaction), throwsStateError);
    await transaction.close();
  });

  test(
    'a concurrent rollback rejection cannot end or taint a pending commit',
    () async {
      await database.ruleSqlFence.publication(() async {
        final transaction = database.executor.beginTransaction();
        await transaction.ensureOpen(database);
        delegate.commitEntered = Completer<void>();
        delegate.commitRelease = Completer<void>();
        final sending = transaction.send();
        await delegate.commitEntered!.future;
        await expectLater(transaction.rollback(), throwsStateError);
        expect(delegate.statements, isNot(contains('ROLLBACK TRANSACTION')));
        expect(database.ruleSqlFence.isUncertain, isFalse);
        delegate.commitRelease!.complete();
        await sending;
        expect(raw.autocommit, isTrue);
        expect(database.ruleSqlFence.isUncertain, isFalse);
      });
    },
  );

  test(
    'publication cannot hide a nested transaction behind an exclusive wrapper',
    () async {
      await database.ruleSqlFence.publication(() async {
        final root = database.executor.beginTransaction();
        await root.ensureOpen(database);
        final exclusive = root.beginExclusive();
        await exclusive.ensureOpen(database);
        try {
          expect(exclusive.beginTransaction, throwsStateError);
        } finally {
          await exclusive.close();
          await root.rollback();
        }
        expect(
          delegate.statements.where((sql) => sql.startsWith('SAVEPOINT')),
          isEmpty,
        );
      });
    },
  );

  test(
    'savepoint rollback through exclusive lineage does not clear root uncertainty',
    () async {
      final root = database.executor.beginTransaction();
      await root.ensureOpen(database);
      final firstExclusive = root.beginExclusive();
      await firstExclusive.ensureOpen(database);
      final middle = firstExclusive.beginTransaction();
      await middle.ensureOpen(database);
      final secondExclusive = middle.beginExclusive();
      await secondExclusive.ensureOpen(database);
      final deepest = secondExclusive.beginTransaction();
      await deepest.ensureOpen(database);
      delegate.failSavepointRollback = 2;
      await expectLater(
        deepest.rollback(),
        throwsA(isA<sqlite.SqliteException>()),
      );
      expect(database.ruleSqlFence.isUncertain, isTrue);
      await secondExclusive.close();
      await middle.rollback();
      expect(database.ruleSqlFence.isUncertain, isTrue);
      expect(raw.autocommit, isFalse);
      await firstExclusive.close();
      await root.rollback();
      expect(database.ruleSqlFence.isUncertain, isFalse);
      expect(raw.autocommit, isTrue);
    },
  );
}
