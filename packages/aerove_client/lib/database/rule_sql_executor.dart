import 'dart:async';

import 'package:drift/backends.dart';
import 'package:drift/drift.dart' show OpeningDetails;

class RuleSqlConnectionUncertain implements Exception {
  RuleSqlConnectionUncertain(
    this.phase,
    this.cause,
    this.stack, {
    this.commitFailure,
  });
  final String phase;
  final Object cause;
  final StackTrace stack;
  final Object? commitFailure;
  @override
  String toString() =>
      'SQLite connection termination unconfirmed ($phase): $cause; commit: $commitFailure';
}

class _SqlTurn {
  _SqlTurn(this.done);
  final Completer<void> done;
  void release() {
    if (!done.isCompleted) done.complete();
  }
}

class _SqlLane {
  Future<void> _tail = Future.value();
  Future<_SqlTurn> acquire() async {
    final prior = _tail;
    final done = Completer<void>();
    _tail = prior.then((_) => done.future);
    await prior;
    return _SqlTurn(done);
  }
}

class RuleSqlConnectionFence {
  static final _owners = Expando<RuleSqlConnectionFence>();
  final _zoneKey = Object();
  final _connectionLane = _SqlLane();
  final _unitZones = <Zone, int>{};
  RuleSqlConnectionUncertain? _uncertain;
  _SqlLease? _uncertainLease;
  bool _closed = false;

  bool get isUncertain => _uncertain != null;
  RuleSqlConnectionUncertain? get uncertainty => _uncertain;
  static RuleSqlConnectionFence? attachedTo(QueryExecutor executor) =>
      executor is _GuardedExecutor ? executor.fence : _owners[executor];

  QueryExecutor wrap(QueryExecutor executor) {
    if (executor is TransactionExecutor || executor is _GuardedExclusive) {
      throw StateError(
        'A scoped SQL executor cannot be rebound as a physical connection root',
      );
    }
    if (executor is _GuardedExecutor) {
      if (!identical(executor.fence, this))
        throw StateError('SQLite executor already has a different owner');
      return executor;
    }
    final registered = _owners[executor];
    if (registered != null && !identical(registered, this))
      throw StateError('SQLite executor ownership cannot be replaced');
    _owners[executor] = this;
    return _GuardedExecutor(executor, this);
  }

  void requireHealthy() {
    if (_uncertain case final error?) throw error;
    if (_closed) throw StateError('SQLite connection was closed');
  }

  _SqlLease? get _inherited {
    final lease = Zone.current[_zoneKey] as _SqlLease?;
    if (lease != null) lease.check();
    return lease;
  }

  Future<_SqlLease> _acquire({bool allowUncertain = false}) async {
    final turn = await _connectionLane.acquire();
    try {
      if (!allowUncertain) requireHealthy();
      return _SqlLease(this, turn);
    } catch (_) {
      turn.release();
      rethrow;
    }
  }

  void _register(Zone zone) =>
      _unitZones.update(zone, (count) => count + 1, ifAbsent: () => 1);
  void _unregister(Zone zone) {
    final count = _unitZones[zone]!;
    if (count == 1) {
      _unitZones.remove(zone);
    } else {
      _unitZones[zone] = count - 1;
    }
  }

  Future<T> _inUnitZone<T>(_SqlUnit unit, Future<T> Function() action) =>
      runZoned(() async {
        final zone = Zone.current;
        _register(zone);
        try {
          return await action();
        } finally {
          _unregister(zone);
        }
      }, zoneValues: {_zoneKey: unit.lease});

  Future<T> _execute<T>(
    Future<T> Function(_SqlUnit) operation, {
    _SqlUnit? parent,
    bool allowUncertain = false,
  }) async {
    final inherited = parent?.lease ?? _inherited;
    final lease = inherited ?? await _acquire(allowUncertain: allowUncertain);
    try {
      final unit = await lease.createUnit(
        parent: parent,
        sqlDepth: parent?.sqlDepth ?? 0,
        allowUncertain: allowUncertain,
      );
      try {
        return await _inUnitZone(unit, () => operation(unit));
      } finally {
        await unit.end();
      }
    } finally {
      if (inherited == null) await lease.release();
    }
  }

  Future<T> publication<T>(Future<T> Function() operation) async {
    for (Zone? zone = Zone.current; zone != null; zone = zone.parent) {
      if ((_unitZones[zone] ?? 0) != 0)
        throw StateError('Rule publication cannot begin inside a SQL unit');
    }
    final inherited = _inherited;
    if (inherited != null) {
      if (!inherited.publication)
        throw StateError(
          'Rule publication cannot reuse an unrelated SQL owner',
        );
      return operation();
    }
    final lease = await _acquire();
    lease.publication = true;
    late T result;
    try {
      result = await runZoned(operation, zoneValues: {_zoneKey: lease});
    } finally {
      await lease.release();
    }
    requireHealthy();
    return result;
  }

  void _taint(
    _SqlLease lease,
    String phase,
    Object error,
    StackTrace stack, {
    Object? commitFailure,
  }) {
    _uncertain ??= RuleSqlConnectionUncertain(
      phase,
      error,
      stack,
      commitFailure: commitFailure,
    );
    _uncertainLease ??= lease;
  }

  void _confirmedRootEnd(_SqlUnit unit) {
    if (unit.sqlDepth != 1)
      throw StateError('A savepoint cannot confirm physical root termination');
    if (identical(_uncertainLease, unit.lease)) {
      _uncertain = null;
      _uncertainLease = null;
    }
  }
}

class _SqlLease {
  _SqlLease(this.fence, this._connectionTurn);
  final RuleSqlConnectionFence fence;
  final _SqlTurn _connectionTurn;
  final root = _SqlLane();
  final _released = Completer<void>();
  bool active = true;
  bool closing = false;
  bool publication = false;
  int _admitted = 0;

  void check({bool allowClosing = false, bool allowUncertain = false}) {
    if (!active || closing && !allowClosing)
      throw StateError('Expired SQLite owner');
    if (!allowUncertain) fence.requireHealthy();
  }

  Future<_SqlUnit> createUnit({
    required _SqlUnit? parent,
    required int sqlDepth,
    bool allowUncertain = false,
  }) async {
    parent?.admitChild();
    check(allowClosing: parent != null, allowUncertain: allowUncertain);
    _admitted++;
    _SqlTurn? turn;
    try {
      turn = await (parent?.children ?? root).acquire();
      check(allowClosing: true, allowUncertain: allowUncertain);
      if (parent != null && !parent.active)
        throw StateError('SQL ancestor already ended');
      return _SqlUnit(this, turn, sqlDepth);
    } catch (_) {
      turn?.release();
      unitEnded();
      rethrow;
    }
  }

  void unitEnded() {
    _admitted--;
    _tryRelease();
  }

  void _tryRelease() {
    if (closing && active && _admitted == 0) {
      active = false;
      _connectionTurn.release();
      _released.complete();
    }
  }

  Future<void> release() {
    closing = true;
    _tryRelease();
    return _released.future;
  }
}

class _SqlUnit {
  _SqlUnit(this.lease, this._parentTurn, this.sqlDepth);
  final _SqlLease lease;
  final _SqlTurn _parentTurn;
  final int sqlDepth;
  final children = _SqlLane();
  bool active = true;
  bool _finishing = false;
  bool _awaitingRollback = false;
  Future<void>? _ending;

  void admitChild() {
    if (!active || _finishing || _awaitingRollback)
      throw StateError('SQL unit no longer accepts work');
  }

  void claimControl({required bool rollback}) {
    if (!active || _finishing || _awaitingRollback && !rollback)
      throw StateError('SQL unit already finished or finishing');
    _finishing = true;
  }

  Future<void> control(
    Future<void> Function() action, {
    required bool rollback,
  }) async {
    final turn = await children.acquire();
    try {
      lease.check(allowClosing: true, allowUncertain: rollback);
      await action();
    } catch (_) {
      _awaitingRollback = true;
      _finishing = false;
      rethrow;
    } finally {
      turn.release();
    }
  }

  Future<void> end() => _ending ??= _end();
  Future<void> _end() async {
    _finishing = true;
    final turn = await children.acquire();
    active = false;
    turn.release();
    _parentTurn.release();
    lease.unitEnded();
  }
}

class _OpeningUser implements QueryExecutorUser {
  _OpeningUser(this.user, this.unit);
  final QueryExecutorUser user;
  final _SqlUnit unit;
  @override
  int get schemaVersion => user.schemaVersion;
  @override
  Future<void> beforeOpen(QueryExecutor executor, OpeningDetails details) =>
      unit.lease.fence._inUnitZone(
        unit,
        () => user.beforeOpen(
          _GuardedExecutor(executor, unit.lease.fence, bound: unit),
          details,
        ),
      );
}

class _GuardedExecutor extends QueryExecutor {
  _GuardedExecutor(this.inner, this.fence, {this.bound});
  final QueryExecutor inner;
  final RuleSqlConnectionFence fence;
  final _SqlUnit? bound;
  _SqlUnit? get ancestor => bound;
  int get sqlDepth => bound?.sqlDepth ?? 0;
  _SqlLease? get owner => bound?.lease ?? fence._inherited;
  Future<T> run<T>(
    Future<T> Function(_SqlUnit) action, {
    bool allowUncertain = false,
  }) =>
      fence._execute(action, parent: ancestor, allowUncertain: allowUncertain);
  @override
  SqlDialect get dialect => inner.dialect;
  @override
  Future<bool> ensureOpen(QueryExecutorUser user) =>
      run((unit) => inner.ensureOpen(_OpeningUser(user, unit)));
  @override
  TransactionExecutor beginTransaction() {
    final lease = owner;
    if (sqlDepth != 0 && lease?.publication == true)
      throw StateError(
        'Rule publication requires one top-level SQL transaction',
      );
    return _GuardedTransaction(
      inner.beginTransaction(),
      fence,
      parent: ancestor,
      hint: lease,
      depth: sqlDepth + 1,
    );
  }

  @override
  QueryExecutor beginExclusive() => _GuardedExclusive(
    inner.beginExclusive(),
    fence,
    parent: ancestor,
    hint: owner,
    depth: sqlDepth,
  );
  @override
  Future<void> runBatched(BatchedStatements statements) =>
      run((_) => inner.runBatched(statements));
  @override
  Future<void> runCustom(String statement, [List<Object?>? args]) =>
      run((_) => inner.runCustom(statement, args));
  @override
  Future<int> runDelete(String statement, List<Object?> args) =>
      run((_) => inner.runDelete(statement, args));
  @override
  Future<int> runInsert(String statement, List<Object?> args) =>
      run((_) => inner.runInsert(statement, args));
  @override
  Future<int> runUpdate(String statement, List<Object?> args) =>
      run((_) => inner.runUpdate(statement, args));
  @override
  Future<List<Map<String, Object?>>> runSelect(
    String statement,
    List<Object?> args,
  ) => run((_) => inner.runSelect(statement, args));
  @override
  Future<void> close() => run((_) async {
    fence._closed = true;
    await inner.close();
  }, allowUncertain: true);
}

class _GuardedExclusive extends _GuardedExecutor {
  _GuardedExclusive(
    super.inner,
    super.fence, {
    required this.parent,
    required this.hint,
    required this.depth,
  });
  final _SqlUnit? parent;
  final _SqlLease? hint;
  final int depth;
  _SqlUnit? unit;
  _SqlLease? lease;
  bool _ownsLease = false;
  bool _terminal = false;
  Future<bool>? _open;
  Zone? _unitZone;
  @override
  int get sqlDepth => depth;
  @override
  _SqlUnit get ancestor {
    if (_terminal || unit == null) throw StateError('SQL unit is not open');
    return unit!;
  }

  @override
  _SqlLease get owner => ancestor.lease;

  @override
  Future<bool> ensureOpen(QueryExecutorUser user) {
    if (_terminal) return Future.error(StateError('SQL unit already ended'));
    if (_open case final opened?) return opened;
    _unitZone = Zone.current;
    fence._register(_unitZone!);
    return _open = _ensure(user);
  }

  void _unregister() {
    final zone = _unitZone;
    if (zone != null) {
      fence._unregister(zone);
      _unitZone = null;
    }
  }

  Future<bool> _ensure(QueryExecutorUser user) async {
    try {
      final borrowed = parent?.lease ?? hint ?? fence._inherited;
      _ownsLease = borrowed == null;
      final selected = lease = borrowed ?? await fence._acquire();
      unit = await selected.createUnit(parent: parent, sqlDepth: depth);
    } catch (_) {
      _terminal = true;
      _unregister();
      if (_ownsLease && lease != null) await lease!.release();
      rethrow;
    }
    try {
      return await inner.ensureOpen(_OpeningUser(user, unit!));
    } catch (error, stack) {
      fence._taint(lease!, 'begin', error, stack);
      await _finished();
      rethrow;
    }
  }

  Future<void> _finished() async {
    _terminal = true;
    _unregister();
    await unit!.end();
    if (_ownsLease) await lease!.release();
  }

  @override
  Future<void> close() async {
    if (_terminal) return;
    if (_open == null) {
      _terminal = true;
      return;
    }
    await _open;
    if (_terminal) return;
    unit!.claimControl(rollback: true);
    try {
      await unit!.control(inner.close, rollback: true);
    } catch (error, stack) {
      fence._taint(lease!, 'exclusive-end', error, stack);
      rethrow;
    } finally {
      await _finished();
    }
  }
}

class _GuardedTransaction extends _GuardedExclusive
    implements TransactionExecutor {
  _GuardedTransaction(
    TransactionExecutor inner,
    RuleSqlConnectionFence fence, {
    required super.parent,
    required super.hint,
    required super.depth,
  }) : super(inner, fence);
  Object? _commitFailure;
  TransactionExecutor get transaction => inner as TransactionExecutor;
  @override
  bool get supportsNestedTransactions => transaction.supportsNestedTransactions;
  @override
  Future<void> close() => rollback();
  @override
  Future<void> send() async {
    if (_terminal) throw StateError('SQL transaction already ended');
    if (_open == null) return;
    await _open;
    if (_terminal) throw StateError('SQL transaction already ended');
    unit!.claimControl(rollback: false);
    try {
      await unit!.control(transaction.send, rollback: false);
    } catch (error) {
      _commitFailure = error;
      rethrow;
    }
    if (depth == 1) fence._confirmedRootEnd(unit!);
    await _finished();
  }

  @override
  Future<void> rollback() async {
    if (_terminal) return;
    if (_open == null) {
      _terminal = true;
      return;
    }
    await _open;
    if (_terminal) return;
    unit!.claimControl(rollback: true);
    try {
      await unit!.control(transaction.rollback, rollback: true);
      if (depth == 1) fence._confirmedRootEnd(unit!);
    } catch (error, stack) {
      fence._taint(
        lease!,
        'rollback',
        error,
        stack,
        commitFailure: _commitFailure,
      );
      rethrow;
    } finally {
      await _finished();
    }
  }
}
