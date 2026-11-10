import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart' show Variable;
import 'package:path/path.dart' as p;
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/core/desktop/process_probe.dart' show isProcessAlive;

import 'function.dart' show SerialTaskScheduler;
import 'snowflake.dart' show uuidV4;
import 'native_lite_migration.dart'
    show nativeLiteAtomicFile, nativeLitePrivateDestination;

typedef RuleSqlTransaction =
    Future<void> Function(
      Future<void> Function() beforePersist,
      Future<void> Function() finishSql,
    );

class RuleSqlPublicationResult {
  RuleSqlPublicationResult(
    this.transactionId,
    this.completionError,
    this.completionStack,
  );
  final String transactionId;
  final Object? completionError;
  final StackTrace? completionStack;
}

class RuleSqlFileChange {
  const RuleSqlFileChange(this.path, this.after);
  final String path;
  final String? after;
}

class RuleSqlRowChange {
  const RuleSqlRowChange(this.table, this.id, this.before, this.after);
  final String table;
  final int id;
  final Map<String, dynamic>? before;
  final Map<String, dynamic>? after;
  Map<String, dynamic> toJson() => {
    'table': table,
    'id': id,
    'before': before,
    'after': after,
  };
}

class _RuleOwnerLease {
  _RuleOwnerLease(this.owner);
  final RuleSqlJournalOwner owner;
  bool active = true;
}

class RuleSqlJournalOwner {
  factory RuleSqlJournalOwner(Database database, Directory home) {
    final existing = _owners[database];
    if (existing != null) {
      if (!p.equals(existing.home.absolute.path, home.absolute.path))
        throw StateError('Rule owner database/home changed');
      return existing;
    }
    return _owners[database] = RuleSqlJournalOwner._(database, home);
  }
  RuleSqlJournalOwner._(this.database, this.home);
  static final _owners = Expando<RuleSqlJournalOwner>();
  final Database database;
  final Directory home;
  final _scheduler = SerialTaskScheduler();
  final _zone = Object();
  final _live = <String, _RuleJournal>{};
  RandomAccessFile? _quarantinedLock;
  bool _businessJournal = false;
  bool _recovering = false;
  String? _qualifiedDatabasePath;
  Zone? _entryZone;
  bool _externalJournal = false;
  bool get inExternalJournal => _externalJournal;
  Future<void> Function(String phase)? onPhase;

  Directory get directory =>
      Directory(p.join(home.path, 'rule-sql-publications'));
  File get taintFile =>
      File(p.join(directory.path, 'connection-uncertain.json'));

  Future<T> exclusive<T>(Future<T> Function() action) {
    final inherited = Zone.current[_zone] as _RuleOwnerLease?;
    if (inherited != null) {
      if (!inherited.active || !identical(inherited.owner, this))
        throw StateError('Expired rule publication owner');
      return action();
    }
    _entryZone = Zone.current;
    return _scheduler.run(() async {
      database.ruleSqlFence.requireHealthy();
      if (_quarantinedLock != null)
        throw StateError(
          'Rule file owner retirement requires an OS process restart',
        );
      await home.create(recursive: true);
      final file = File(p.join(directory.path, '.owner.lock'));
      await nativeLitePrivateDestination(file, home);
      await directory.create(recursive: true);
      final handle = await file.open(mode: FileMode.append);
      var locked = false;
      final lease = _RuleOwnerLease(this);
      late T result;
      Object? primary;
      StackTrace? primaryStack;
      Object? cleanupError;
      try {
        await handle.lock(FileLock.blockingExclusive);
        locked = true;
        result = await runZoned(action, zoneValues: {_zone: lease});
      } catch (error, stack) {
        primary = error;
        primaryStack = stack;
      } finally {
        lease.active = false;
        if (database.ruleSqlFence.isUncertain) {
          _quarantinedLock = handle;
          primary ??= database.ruleSqlFence.uncertainty!;
          primaryStack ??= StackTrace.current;
          try {
            if (!await taintFile.exists())
              await _writeQuarantine(null, primary);
          } catch (error) {
            cleanupError = error;
          }
        } else {
          try {
            if (locked) await handle.unlock();
          } catch (error) {
            cleanupError = error;
          }
          try {
            await handle.close();
          } catch (error) {
            cleanupError ??= error;
            _quarantinedLock = handle;
          }
        }
      }
      if (cleanupError != null)
        throw RuleSqlPublicationFailure(
          primary,
          cleanupError,
          directory.path,
          null,
        );
      if (primary != null) Error.throwWithStackTrace(primary, primaryStack!);
      return result;
    });
  }

  Future<T> inspect<T>(Future<T> Function() read) {
    if (Zone.current[_zone] != null) {
      final result = Completer<T>();
      final entry = _entryZone;
      if (entry == null)
        return Future.error(
          StateError('Rule read has no independent entry context'),
        );
      entry.run(
        () =>
            exclusive(() async {
              await recover();
              return read();
            }).then(
              result.complete,
              onError: (Object error, StackTrace stack) =>
                  result.completeError(error, stack),
            ),
      );
      return result.future;
    }
    return exclusive(() async {
      await recover();
      return read();
    });
  }

  void _requireOwner() {
    final lease = Zone.current[_zone] as _RuleOwnerLease?;
    if (lease == null || !lease.active || !identical(lease.owner, this))
      throw StateError('Rule publication requires its file owner');
  }

  Future<T> externalJournal<T>(Future<T> Function() operation) async {
    _requireOwner();
    if (_businessJournal || _externalJournal || _recovering)
      throw StateError('Independent business journals cannot nest');
    _externalJournal = true;
    try {
      return await operation();
    } finally {
      _externalJournal = false;
    }
  }

  Future<void> _checkPreviousProcess(int previous) async {
    final current = database.ruleSqlOwnerPid;
    if (current == null)
      throw StateError('The SQLite physical OS owner is not qualified');
    if (previous == current)
      throw StateError('rule_sql_process_restart_required');
    if (await _strictAlive(previous))
      throw StateError('Previous rule writer is still alive');
  }

  Future<void> recover() async {
    _requireOwner();
    if (_businessJournal || _recovering || _externalJournal)
      throw StateError('Independent rule journals cannot nest');
    _recovering = true;
    try {
      final pendingMarker = File('${taintFile.path}.pending');
      if (await FileSystemEntity.type(pendingMarker.path, followLinks: false) !=
          FileSystemEntityType.notFound)
        throw StateError(
          'An incomplete connection quarantine requires its dedicated recovery; preserve all staging',
        );
      if (await taintFile.exists()) {
        await nativeLitePrivateDestination(taintFile, home);
        final marker = _map(jsonDecode(await taintFile.readAsString()));
        if (marker['schema'] != 1 || marker['pid'] is! int)
          throw StateError('Invalid rule connection quarantine');
        if (marker['host'] != Platform.localHostname)
          throw StateError('The prior SQLite OS host is not this host');
        final currentPath = await File(
          p.join(home.path, 'database.sqlite'),
        ).resolveSymbolicLinks();
        if (marker['databasePath'] != null &&
            !p.equals(marker['databasePath'] as String, currentPath))
          throw StateError('The quarantined physical SQLite path changed');
        await _checkPreviousProcess(marker['pid'] as int);
        if (marker['incarnation'] == null)
          throw StateError(
            'The non-rule publication has no durable file decision; preserve its staging and use its dedicated recovery owner',
          );
      }
      if (await directory.exists()) {
        for (final entry in await directory.list(followLinks: false).toList()) {
          final name = p.basename(entry.path);
          final match = RegExp(
            r'^\.(finished|discarded)-([0-9a-f-]{36})$',
          ).firstMatch(name);
          final id = match?.group(2) ?? name;
          if (!_uuid.hasMatch(id)) continue;
          if (entry is! Directory)
            throw StateError('Invalid rule journal directory');
          if (!await File(p.join(entry.path, 'plan.json')).exists() &&
              match != null &&
              await entry.list(followLinks: false).isEmpty)
            continue;
          final journal =
              _live[id] ??
              await _RuleJournal.open(this, id, directoryName: name);
          await journal.checkWriterEnded();
        }
      }
      await database.customSelect('PRAGMA user_version').get();
      await database.ruleSqlFence.publication(() async {
        final incarnation = await database.ruleSqlIncarnation();
        final infrastructure = await database.ruleSqlInfrastructureProof();
        if (!await directory.exists() &&
            (infrastructure['tokens'] as List).isEmpty)
          return;
        final activeEntries = await directory.list(followLinks: false).toList();
        for (final entry in activeEntries) {
          final match = RegExp(
            r'^\.preparing-([1-9][0-9]*)-([0-9a-f-]{36})$',
          ).firstMatch(p.basename(entry.path));
          if (match == null) continue;
          final previous = int.parse(match.group(1)!);
          if (previous == pid || await _strictAlive(previous)) continue;
          final id = match.group(2)!;
          if (!_uuid.hasMatch(id) || entry is! Directory)
            throw StateError('Invalid rule preparation owner');
          if ((await database
                  .customSelect(
                    'SELECT 1 FROM rule_source_publications WHERE transaction_id = ?',
                    variables: [Variable(id)],
                  )
                  .get())
              .isNotEmpty)
            throw StateError(
              'A durable rule token cannot belong to a preparing directory',
            );
          final children = await entry.list(followLinks: false).toList();
          if (children.any(
            (child) =>
                child is! File ||
                !RegExp(
                  r'^(plan\.json(\.pending)?|[0-9]+\.(before|after|apply))$',
                ).hasMatch(p.basename(child.path)),
          ))
            throw StateError('Foreign entry in an abandoned rule preparation');
          for (final child in children) {
            await nativeLitePrivateDestination(File(child.path), home);
            await child.delete();
          }
          await entry.delete();
        }
        final hasPlans = activeEntries.any(
          (entry) =>
              _uuid.hasMatch(p.basename(entry.path)) ||
              RegExp(
                r'^\.(finished|discarded)-',
              ).hasMatch(p.basename(entry.path)),
        );
        if (!hasPlans &&
            !await taintFile.exists() &&
            (infrastructure['tokens'] as List).isEmpty)
          return;
        await _checkDatabaseFile();
        if (await taintFile.exists()) {
          final marker = _map(jsonDecode(await taintFile.readAsString()));
          if (marker['incarnation'] != null &&
              marker['incarnation'] != incarnation)
            throw StateError('Quarantine belongs to a different database');
        }
        if (await directory.exists()) {
          final entries = await directory.list(followLinks: false).toList();
          for (final entry in entries) {
            final id = p.basename(entry.path);
            final match = RegExp(
              r'^\.(finished|discarded)-([0-9a-f-]{36})$',
            ).firstMatch(id);
            final transactionId = match?.group(2) ?? id;
            if (!_uuid.hasMatch(transactionId)) continue;
            if (entry is! Directory)
              throw StateError('Invalid rule journal entry');
            if (!await File(p.join(entry.path, 'plan.json')).exists() &&
                match != null &&
                await entry.list(followLinks: false).isEmpty) {
              final token = await database
                  .customSelect(
                    'SELECT decision FROM rule_source_publications WHERE transaction_id = ?',
                    variables: [Variable(transactionId)],
                  )
                  .get();
              if (token.isNotEmpty &&
                  !{
                    'COMMITTED',
                    'ABORTED',
                  }.contains(token.single.read<String>('decision')))
                throw StateError(
                  'An unfinished durable decision lost its plan',
                );
              await entry.delete();
              _live.remove(transactionId);
              continue;
            }
            final journal =
                _live[transactionId] ??
                await _RuleJournal.open(this, transactionId, directoryName: id);
            await journal.checkWriterEnded();
            if (journal.plan['incarnation'] != incarnation)
              throw StateError('Rule journal database incarnation changed');
            await journal.startRecovery();
            _live[transactionId] = journal;
            journal.live = true;
            if (match != null) {
              final decision = await journal.decision();
              if (match.group(1) == 'finished'
                  ? !{'COMMITTED', 'ABORTED'}.contains(decision)
                  : decision != null)
                throw StateError('Invalid terminal journal cleanup state');
              await journal.removeMetadata();
            } else {
              await journal.resolve();
            }
          }
        }
        final tokens = await database
            .customSelect(
              'SELECT transaction_id, decision FROM rule_source_publications',
            )
            .get();
        for (final token in tokens) {
          final id = token.read<String>('transaction_id');
          if (await Directory(p.join(directory.path, id)).exists())
            throw StateError('Rule publication cleanup is pending');
          if (!{
            'COMMITTED',
            'ABORTED',
          }.contains(token.read<String>('decision')))
            throw StateError('Prepared token has no immutable journal');
          await database.customStatement(
            'DELETE FROM rule_source_publications WHERE transaction_id = ? AND decision IN (?, ?)',
            [id, 'COMMITTED', 'ABORTED'],
          );
        }
        await database.ruleSqlInfrastructureProof(resolved: true);
        if (await taintFile.exists()) await taintFile.delete();
      });
    } finally {
      _recovering = false;
    }
  }

  Future<void> _checkDatabaseFile() async {
    if (database.ruleSqlOwnerPid == null)
      throw StateError(
        'Cannot infer an external SQLite backend owner from the UI process',
      );
    final rows = await database.customSelect('PRAGMA database_list').get();
    final path = rows
        .singleWhere((row) => row.read<String>('name') == 'main')
        .read<String>('file');
    final expected = File(p.join(home.path, 'database.sqlite'));
    await nativeLitePrivateDestination(expected, home);
    final actual = await File(path).resolveSymbolicLinks();
    if (!p.equals(actual, await expected.resolveSymbolicLinks()))
      throw StateError(
        'Rule journal is not bound to this physical SQLite file',
      );
    _qualifiedDatabasePath = actual;
  }

  Future<RuleSqlPublicationResult> publish({
    required int providerId,
    required String expectedIncarnation,
    required List<RuleSqlFileChange> files,
    required Future<List<RuleSqlRowChange>> Function() captureRows,
    required RuleSqlTransaction transaction,
  }) => _publish(
    providerId: providerId,
    expectedIncarnation: expectedIncarnation,
    files: files,
    captureRows: captureRows,
    transaction: transaction,
  );

  Future<RuleSqlPublicationResult> publishProfile({
    required int profileId,
    required List<RuleSqlFileChange> files,
    required RuleSqlTransaction transaction,
  }) async => _publish(
    providerId: profileId,
    profileId: profileId,
    expectedIncarnation: await database.ruleSqlIncarnation(),
    files: files,
    captureRows: () async => const [],
    transaction: transaction,
  );

  Future<RuleSqlPublicationResult> _publish({
    required int providerId,
    int? profileId,
    required String expectedIncarnation,
    required List<RuleSqlFileChange> files,
    required Future<List<RuleSqlRowChange>> Function() captureRows,
    required RuleSqlTransaction transaction,
  }) async {
    _requireOwner();
    if (_businessJournal || _recovering || _externalJournal)
      throw StateError(
        'Nested independent rule business journals are forbidden',
      );
    await recover();
    _businessJournal = true;
    try {
      await database.customSelect('PRAGMA user_version').get();
      return await database.ruleSqlFence.publication(() async {
        await _checkDatabaseFile();
        if ((await database.ruleSqlIncarnation()) != expectedIncarnation)
          throw StateError('Rule source predates a database restore');
        await onPhase?.call('before-plan');
        final journal = await _RuleJournal.prepare(
          this,
          providerId,
          expectedIncarnation,
          files,
          await captureRows(),
          profileId: profileId,
        );
        _live[journal.id] = journal;
        Object? failure;
        StackTrace? failureStack;
        var beforeCalled = false;
        var finishCalled = false;
        try {
          await database.transaction(() async {
            // SQLite, rather than the process-scoped POSIX file lock, arbitrates
            // separate physical connections in the same OS process as well.
            final inserted = await database.customUpdate(
              'INSERT INTO rule_source_publications (transaction_id, incarnation, provider_id, plan_sha256, decision, schema_version) SELECT ?, ?, ?, ?, ?, 1 WHERE EXISTS (SELECT 1 FROM rule_sql_identity WHERE slot = 1 AND incarnation = ?) AND NOT EXISTS (SELECT 1 FROM rule_source_publications)',
              variables: [
                Variable(journal.id),
                Variable(expectedIncarnation),
                Variable(providerId),
                Variable(journal.hash),
                Variable('PREPARED'),
                Variable(expectedIncarnation),
              ],
            );
            await journal.requireIncarnation();
            if (inserted != 1)
              throw StateError(
                'A rule publication already owns the durable admission',
              );
            await journal.checkRows(false);
          });
          await journal.requireDecision('PREPARED');
          await onPhase?.call('prepared');
          await journal.preparePublication();
          await transaction(
            () async {
              if (beforeCalled)
                throw StateError('Rule file publication invoked twice');
              beforeCalled = true;
              await journal.requireIncarnation();
              await journal.requireDecision('PREPARED');
              await journal.checkRows(false);
              await journal.publishFiles();
            },
            () async {
              if (!beforeCalled || finishCalled)
                throw StateError('Invalid rule SQL finish ordering');
              finishCalled = true;
              await journal.requireIncarnation();
              await journal.checkRows(true);
              final count = await database.customUpdate(
                "UPDATE rule_source_publications SET decision = 'COMMITTED' WHERE transaction_id = ? AND plan_sha256 = ? AND incarnation = ? AND decision = 'PREPARED' AND EXISTS (SELECT 1 FROM rule_sql_identity WHERE slot = 1 AND incarnation = ?)",
                variables: [
                  Variable(journal.id),
                  Variable(journal.hash),
                  Variable(expectedIncarnation),
                  Variable(expectedIncarnation),
                ],
              );
              if (count != 1) throw StateError('Rule commit token CAS failed');
            },
          );
          if (!beforeCalled || !finishCalled)
            throw StateError('Business transaction omitted its rule hooks');
        } catch (error, stack) {
          failure = error;
          failureStack = stack;
        }
        if (database.ruleSqlFence.isUncertain) {
          final primary = failure ?? database.ruleSqlFence.uncertainty!;
          try {
            await _quarantine(journal, primary);
          } catch (markerError, stack) {
            Error.throwWithStackTrace(
              RuleSqlPublicationFailure(
                primary,
                markerError,
                journal.directory.path,
                null,
              ),
              stack,
            );
          }
          Error.throwWithStackTrace(
            primary,
            failureStack ?? StackTrace.current,
          );
        }
        journal.live = true;
        final decision = await journal.decision();
        if (decision == null && !beforeCalled) {
          // This healthy executor confirmed admission never committed. No file
          // publish hook ran, so this losing attempt owns metadata only.
          await journal.cleanup();
          if (failure != null)
            Error.throwWithStackTrace(failure, failureStack!);
          throw StateError('Rule durable admission was not accepted');
        }
        await onPhase?.call('sql-ended');
        try {
          await journal.resolve();
        } catch (recovery, stack) {
          if (database.ruleSqlFence.isUncertain)
            await _quarantine(journal, recovery);
          Error.throwWithStackTrace(
            RuleSqlPublicationFailure(
              failure,
              recovery,
              journal.directory.path,
              decision,
            ),
            stack,
          );
        }
        if (decision == 'COMMITTED') {
          database.markTablesUpdated([
            database.clashProviders,
            database.rules,
            database.profiles,
          ]);
          return RuleSqlPublicationResult(journal.id, failure, failureStack);
        }
        if (failure != null) Error.throwWithStackTrace(failure, failureStack!);
        throw StateError('Rule business SQL did not commit');
      });
    } finally {
      _businessJournal = false;
    }
  }

  Future<void> _quarantine(_RuleJournal journal, Object? cause) async {
    try {
      await _writeQuarantine(journal.plan['incarnation'] as String, cause);
    } catch (markerError, stack) {
      Error.throwWithStackTrace(
        RuleSqlPublicationFailure(
          cause,
          markerError,
          journal.directory.path,
          null,
        ),
        stack,
      );
    }
  }

  Future<void> _writeQuarantine(String? incarnation, Object? cause) =>
      nativeLiteAtomicFile(
        taintFile,
        utf8.encode(
          jsonEncode({
            'schema': 1,
            'pid': database.ruleSqlOwnerPid,
            'incarnation': incarnation,
            'host': Platform.localHostname,
            'databasePath': _qualifiedDatabasePath,
            'error': '$cause',
          }),
        ),
        boundary: home,
      );
}

class _RuleJournal {
  _RuleJournal(this.owner, this.id, this.plan, this.hash);
  final RuleSqlJournalOwner owner;
  final String id;
  final Map<String, dynamic> plan;
  final String hash;
  bool live = false;
  String? _directoryName;
  final _publicationStamps = <String, Object>{};
  Object? _planStamp;
  Directory get directory =>
      Directory(p.join(owner.directory.path, _directoryName ?? id));
  Database get db => owner.database;
  List<Map<String, dynamic>> get files =>
      (plan['files'] as List).map(_map).toList();
  List<Map<String, dynamic>> get rows =>
      (plan['rows'] as List).map(_map).toList();

  static Future<_RuleJournal> prepare(
    RuleSqlJournalOwner owner,
    int providerId,
    String incarnation,
    List<RuleSqlFileChange> changes,
    List<RuleSqlRowChange> rows, {
    int? profileId,
  }) async {
    if (profileId != null &&
        (profileId != providerId || changes.length != 2 || rows.isNotEmpty))
      throw StateError('Invalid profile publication scope');
    final targets = {
      for (final change in changes)
        _relative(owner.home, change.path, profileId: profileId),
    };
    if (targets.length != changes.length)
      throw StateError('Duplicate publication target');
    final id = uuidV4;
    final staging = Directory(
      p.join(owner.directory.path, '.preparing-$pid-$id'),
    );
    await nativeLitePrivateDestination(
      File(p.join(staging.path, 'plan.json')),
      owner.home,
    );
    await staging.create();
    try {
      final records = <Map<String, dynamic>>[];
      for (final (index, change) in changes.indexed) {
        final relative = _relative(
          owner.home,
          change.path,
          profileId: profileId,
        );
        if (records.any((entry) => entry['path'] == relative))
          throw StateError('Duplicate rule target in one plan');
        final before = await _digest(owner.home, change.path);
        final after = change.after == null
            ? null
            : await _digest(owner.home, change.after!);
        if (change.after != null && after == null)
          throw StateError('Rule candidate is missing');
        if (before != null)
          await _copy(
            owner.home,
            change.path,
            p.join(staging.path, '$index.before'),
            before,
          );
        if (after != null) {
          await _copy(
            owner.home,
            change.after!,
            p.join(staging.path, '$index.after'),
            after,
          );
          await _copy(
            owner.home,
            change.after!,
            p.join(staging.path, '$index.apply'),
            after,
          );
        }
        records.add({'path': relative, 'before': before, 'after': after});
      }
      final plan = <String, dynamic>{
        'schema': 1,
        'id': id,
        'pid': owner.database.ruleSqlOwnerPid,
        'incarnation': incarnation,
        'host': Platform.localHostname,
        'databasePath': owner._qualifiedDatabasePath,
        'providerId': providerId,
        if (profileId != null) 'profileId': profileId,
        'files': records,
        'rows': rows.map((row) => row.toJson()).toList(),
      };
      final bytes = utf8.encode(jsonEncode(plan));
      if (bytes.length > 64 * 1024 * 1024)
        throw StateError('Rule journal plan exceeds its input budget');
      await nativeLiteAtomicFile(
        File(p.join(staging.path, 'plan.json')),
        bytes,
        boundary: owner.home,
      );
      final journal = _RuleJournal(
        owner,
        id,
        plan,
        sha256.convert(bytes).toString(),
      );
      await journal.checkRowShapes();
      await staging.rename(journal.directory.path);
      return journal;
    } catch (error, stack) {
      try {
        final entries = await staging.list(followLinks: false).toList();
        for (final entry in entries) {
          if (entry is! File ||
              !RegExp(
                r'^(plan\.json(\.pending)?|[0-9]+\.(before|after|apply))$',
              ).hasMatch(p.basename(entry.path)))
            throw StateError('Foreign entry in publication preparation');
          await nativeLitePrivateDestination(entry, owner.home);
        }
        for (final entry in entries) {
          await entry.delete();
        }
        await staging.delete();
      } catch (cleanup) {
        Error.throwWithStackTrace(
          RuleSqlPublicationFailure(error, cleanup, staging.path, null),
          stack,
        );
      }
      Error.throwWithStackTrace(error, stack);
    }
  }

  static Future<_RuleJournal> open(
    RuleSqlJournalOwner owner,
    String id, {
    String? directoryName,
  }) async {
    final file = File(
      p.join(owner.directory.path, directoryName ?? id, 'plan.json'),
    );
    await nativeLitePrivateDestination(file, owner.home);
    if (await file.length() > 64 * 1024 * 1024)
      throw StateError('Rule plan exceeds budget');
    final bytes = await file.readAsBytes();
    final plan = _map(jsonDecode(utf8.decode(bytes)));
    if (plan.keys.toSet().difference({
          'schema',
          'id',
          'pid',
          'incarnation',
          'providerId',
          'profileId',
          'files',
          'rows',
          'host',
          'databasePath',
        }).isNotEmpty ||
        plan['schema'] != 1 ||
        plan['id'] != id ||
        plan['pid'] is! int ||
        plan['providerId'] is! int ||
        (plan.containsKey('profileId') &&
            (plan['profileId'] is! int ||
                plan['profileId'] != plan['providerId'])) ||
        plan['host'] is! String ||
        plan['databasePath'] is! String ||
        !_uuid.hasMatch(plan['incarnation'] as String))
      throw StateError('Invalid immutable rule plan');
    final journal = _RuleJournal(
      owner,
      id,
      plan,
      sha256.convert(bytes).toString(),
    );
    journal._directoryName = directoryName;
    for (final entry in journal.files) {
      _relative(
        owner.home,
        p.join(owner.home.path, entry['path'] as String),
        profileId: plan['profileId'] as int?,
      );
      for (final key in ['before', 'after'])
        if (entry[key] != null && !_sha.hasMatch(entry[key] as String))
          throw StateError('Invalid rule blob identity');
    }
    await journal.checkRowShapes();
    return journal;
  }

  Future<void> checkWriterEnded() async {
    if (live) return;
    if (plan['host'] != Platform.localHostname ||
        !p.equals(
          plan['databasePath'] as String,
          await File(
            p.join(owner.home.path, 'database.sqlite'),
          ).resolveSymbolicLinks(),
        ))
      throw StateError('The SQLite physical owner namespace changed');
    await owner._checkPreviousProcess(plan['pid'] as int);
    final record = File(p.join(directory.path, 'recovering.json'));
    if (await record.exists()) {
      await nativeLitePrivateDestination(record, owner.home);
      final value = _map(jsonDecode(await record.readAsString()));
      if (value['schema'] != 1 || value['plan'] != hash || value['pid'] is! int)
        throw StateError('Invalid physical recovery owner');
      await owner._checkPreviousProcess(value['pid'] as int);
    }
  }

  Future<void> startRecovery() => nativeLiteAtomicFile(
    File(p.join(directory.path, 'recovering.json')),
    utf8.encode(
      jsonEncode({'schema': 1, 'plan': hash, 'pid': db.ruleSqlOwnerPid}),
    ),
    boundary: owner.home,
  );

  Future<Map<String, dynamic>?> token() async {
    final result = await db
        .customSelect(
          'SELECT * FROM rule_source_publications WHERE transaction_id = ?',
          variables: [Variable(id)],
        )
        .get();
    if (result.isEmpty) return null;
    final row = result.single.data;
    if (row['incarnation'] != plan['incarnation'] ||
        row['provider_id'] != plan['providerId'] ||
        row['plan_sha256'] != hash ||
        row['schema_version'] != 1 ||
        !{'PREPARED', 'COMMITTED', 'ABORTED'}.contains(row['decision']))
      throw StateError('Rule SQL token does not match immutable plan');
    return row;
  }

  Future<String?> decision() async => (await token())?['decision'] as String?;
  Future<void> requireIncarnation() async {
    if (await db.ruleSqlIncarnation() != plan['incarnation'])
      throw StateError(
        'Rule publication incarnation changed before file/SQL publication',
      );
  }

  Future<void> requireDecision(String expected) async {
    if (await decision() != expected)
      throw StateError('Rule durable decision differs from $expected');
  }

  Future<void> checkRowShapes() async {
    if (plan.containsKey('profileId') &&
        (files.length != 2 ||
            files.map((entry) => entry['path']).toSet().length != 2 ||
            rows.isNotEmpty))
      throw StateError('Invalid profile publication footprint');
    final identities = <String>{};
    for (final row in rows) {
      final allowed = _columns[row['table']];
      if (allowed == null || row['id'] is! int)
        throw StateError('Invalid rule SQL footprint');
      if (!identities.add('${row['table']}/${row['id']}'))
        throw StateError('Duplicate rule SQL footprint');
      for (final key in ['before', 'after']) {
        if (row[key] == null) continue;
        final fields = _map(row[key]);
        if (fields.isEmpty || !fields.keys.every(allowed.contains))
          throw StateError('Unexpected rule SQL footprint column');
        if (fields.values.any(
          (value) => value != null && value is! String && value is! num,
        ))
          throw StateError(
            'Rule SQL footprint values must be actual SQLite scalars',
          );
      }
    }
  }

  Future<void> checkRows(bool after) async {
    await checkRowShapes();
    for (final row in rows) {
      final expected = row[after ? 'after' : 'before'];
      final actual = await db
          .customSelect(
            'SELECT * FROM "${row['table']}" WHERE id = ?',
            variables: [Variable(row['id'] as int)],
          )
          .get();
      if (expected == null) {
        if (actual.isNotEmpty)
          throw StateError('Rule SQL footprint acquired a third row');
      } else {
        final fields = _map(expected);
        if (actual.length != 1 ||
            fields.keys.any((key) => actual.single.data[key] != fields[key]))
          throw StateError('Rule SQL footprint changed');
      }
    }
  }

  Future<void> checkFiles({
    required bool afterOnly,
    bool beforeOnly = false,
    bool blobs = true,
  }) async {
    for (final (index, entry) in files.indexed) {
      final target = p.join(owner.home.path, entry['path'] as String);
      final actual = await _digest(owner.home, target);
      if (afterOnly
          ? actual != entry['after']
          : beforeOnly
          ? actual != entry['before']
          : actual != entry['before'] && actual != entry['after'])
        throw StateError(
          'Rule publication encountered a third file value: ${entry['path']}',
        );
      if (blobs)
        for (final key in ['before', 'after']) {
          if (entry[key] != null &&
              await _digest(
                    owner.home,
                    p.join(directory.path, '$index.$key'),
                  ) !=
                  entry[key])
            throw StateError('Immutable rule recovery blob changed');
        }
    }
  }

  Future<void> publishFiles() async {
    if (await _stamp(owner.home, p.join(directory.path, 'plan.json')) !=
        _planStamp)
      throw StateError('Immutable publication plan changed');
    for (final entry in files) {
      final path = p.join(owner.home.path, entry['path'] as String);
      if (await _stamp(owner.home, path) != _publicationStamps[path])
        throw StateError('Rule source changed before publication');
    }
    await owner.onPhase?.call('after-final-stamp');
    for (final (index, entry) in files.indexed) {
      final path = p.join(owner.home.path, entry['path'] as String);
      if (await _stamp(owner.home, path) != _publicationStamps[path])
        throw StateError('Rule file CAS failed before rename');
      if (entry['after'] == entry['before']) continue;
      if (entry['after'] == null) {
        await File(path).delete();
      } else {
        await File(p.join(directory.path, '$index.apply')).rename(path);
      }
      await owner.onPhase?.call('file-renamed:$index');
    }
    await owner.onPhase?.call('after-rename');
  }

  Future<void> preparePublication() async {
    await _verifyPlan();
    for (final entry in files) {
      final path = p.join(owner.home.path, entry['path'] as String);
      _publicationStamps[path] = await _stamp(owner.home, path);
    }
    await checkFiles(afterOnly: false, beforeOnly: true);
    for (final (index, entry) in files.indexed) {
      final path = p.join(owner.home.path, entry['path'] as String);
      if (await _stamp(owner.home, path) != _publicationStamps[path])
        throw StateError('Rule source changed while taking publication proof');
      if (entry['after'] != null &&
          await _digest(owner.home, p.join(directory.path, '$index.apply')) !=
              entry['after'])
        throw StateError('Rule prepared rename input changed');
    }
  }

  Future<void> resolve() async {
    await _verifyPlan();
    if (await db.ruleSqlIncarnation() != plan['incarnation'])
      throw StateError('Rule database incarnation mismatch');
    var state = await decision();
    if (state == null) {
      await checkRows(false);
      await checkFiles(afterOnly: false, beforeOnly: true, blobs: false);
      await cleanup();
      return;
    }
    final after = state == 'COMMITTED';
    await checkRows(after);
    if (state == 'COMMITTED' || state == 'ABORTED') {
      var complete = true;
      for (final (index, entry) in files.indexed) {
        if (await _digest(
              owner.home,
              p.join(owner.home.path, entry['path'] as String),
            ) !=
            entry[after ? 'after' : 'before'])
          complete = false;
        for (final key in ['before', 'after']) {
          final blob = p.join(directory.path, '$index.$key');
          final digest = await _digest(owner.home, blob);
          if (digest != null && digest != entry[key])
            throw StateError('Terminal recovery blob was corrupted');
        }
      }
      if (complete) {
        await cleanup();
        await db.customStatement(
          "DELETE FROM rule_source_publications WHERE transaction_id = ? AND plan_sha256 = ? AND decision IN ('COMMITTED', 'ABORTED')",
          [id, hash],
        );
        return;
      }
    }
    await checkFiles(afterOnly: false);
    if (state == 'PREPARED') {
      try {
        await db.transaction(() async {
          final count = await db.customUpdate(
            "UPDATE rule_source_publications SET decision = 'ABORTED' WHERE transaction_id = ? AND plan_sha256 = ? AND decision = 'PREPARED'",
            variables: [Variable(id), Variable(hash)],
          );
          if (count != 1) throw StateError('Rule abort CAS failed');
        });
      } catch (error) {
        if (db.ruleSqlFence.isUncertain) await owner._quarantine(this, error);
        rethrow;
      }
      await requireDecision('ABORTED');
      state = 'ABORTED';
    }
    final desired = state == 'COMMITTED' ? 'after' : 'before';
    final currentDigests = <String, String?>{};
    final stamps = <String, Object>{};
    for (final (index, entry) in files.indexed) {
      if (entry[desired] != null)
        await _copy(
          owner.home,
          p.join(directory.path, '$index.$desired'),
          p.join(directory.path, '$index.recover'),
          entry[desired] as String,
          replaceScratch: true,
        );
      final target = p.join(owner.home.path, entry['path'] as String);
      final stamp = await _stamp(owner.home, target);
      currentDigests[target] = await _digest(owner.home, target);
      if (await _stamp(owner.home, target) != stamp)
        throw StateError('Rule recovery source changed during proof');
      stamps[target] = stamp;
    }
    try {
      await db.transaction(() async {
        await requireDecision(state!);
        await checkRows(state == 'COMMITTED');
        if (await _stamp(owner.home, p.join(directory.path, 'plan.json')) !=
            _planStamp)
          throw StateError('Immutable recovery plan changed');
        for (final (index, entry) in files.indexed) {
          final path = p.join(owner.home.path, entry['path'] as String);
          if (await _stamp(owner.home, path) != stamps[path])
            throw StateError('Rule recovery source changed before rename');
          final current = currentDigests[path];
          if (current != entry['before'] && current != entry['after'])
            throw StateError('Rule recovery CAS found third value');
          if (current == entry[desired]) continue;
          if (entry[desired] == null) {
            await File(path).delete();
          } else {
            await File(p.join(directory.path, '$index.recover')).rename(path);
          }
          await owner.onPhase?.call('recovered:$index');
        }
      });
    } catch (error) {
      if (db.ruleSqlFence.isUncertain) await owner._quarantine(this, error);
      rethrow;
    }
    await checkRows(state == 'COMMITTED');
    await checkFiles(
      afterOnly: state == 'COMMITTED',
      beforeOnly: state != 'COMMITTED',
    );
    await cleanup();
    await db.customStatement(
      "DELETE FROM rule_source_publications WHERE transaction_id = ? AND plan_sha256 = ? AND decision IN ('COMMITTED', 'ABORTED')",
      [id, hash],
    );
  }

  Future<void> _verifyPlan() async {
    final file = p.join(directory.path, 'plan.json');
    final before = await _stamp(owner.home, file);
    if (await _digest(owner.home, file) != hash ||
        await _stamp(owner.home, file) != before)
      throw StateError('Immutable rule plan changed');
    _planStamp = before;
  }

  Future<void> cleanup() async {
    final state = await decision();
    final name = '.${state == null ? 'discarded' : 'finished'}-$id';
    await directory.rename(p.join(owner.directory.path, name));
    _directoryName = name;
    await owner.onPhase?.call('terminal-cleanup');
    await removeMetadata();
  }

  Future<void> removeMetadata() async {
    final known = <String>{
      'plan.json',
      'plan.json.pending',
      'recovering.json',
      'recovering.json.pending',
      for (var i = 0; i < files.length; i++) ...[
        '$i.before',
        '$i.after',
        '$i.apply',
        '$i.recover',
      ],
    };
    final entries = await directory.list(followLinks: false).toList();
    if (entries.any(
      (entry) => entry is! File || !known.contains(p.basename(entry.path)),
    ))
      throw StateError('Foreign entry in rule recovery directory');
    for (final entry in entries.where(
      (entry) => p.basename(entry.path) != 'plan.json',
    )) {
      await nativeLitePrivateDestination(File(entry.path), owner.home);
      await entry.delete();
    }
    await File(p.join(directory.path, 'plan.json')).delete();
    await directory.delete();
    owner._live.remove(id);
  }
}

class RuleSqlPublicationFailure implements Exception {
  RuleSqlPublicationFailure(
    this.primary,
    this.recovery,
    this.journal,
    this.decision,
  );
  final Object? primary;
  final Object recovery;
  final String journal;
  final String? decision;
  @override
  String toString() =>
      'Rule publication decision=$decision; primary=$primary; recovery=$recovery; journal=$journal';
}

const _columns = <String, Set<String>>{
  'clash_providers': {
    'id',
    'kind',
    'label',
    'source_definition',
    'url',
    'behavior',
    'format',
    'order',
  },
  'rules': {'id', 'profile_id', 'rule_action', 'rule_provider', 'content'},
  'profiles': {'id', 'overrides'},
};
final _uuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
);
final _sha = RegExp(r'^[0-9a-f]{64}$');
Map<String, dynamic> _map(Object? value) =>
    Map<String, dynamic>.from(value as Map);
String _relative(Directory home, String path, {int? profileId}) {
  final relative = p.relative(path, from: home.path).replaceAll('\\', '/');
  final allowed = profileId == null
      ? RegExp(r'^providers/rules/[0-9a-f]{32}(\.mrs)?$').hasMatch(relative)
      : relative == 'profiles/$profileId.yaml' ||
            relative == 'profiles/$profileId.input';
  if (!allowed)
    throw StateError('Publication target is outside its source scope');
  return relative;
}

Future<String?> _digest(Directory home, String path) async {
  final file = File(path);
  await nativeLitePrivateDestination(file, home);
  final type = await FileSystemEntity.type(path, followLinks: false);
  if (type == FileSystemEntityType.notFound) return null;
  if (type != FileSystemEntityType.file ||
      await file.length() > 64 * 1024 * 1024)
    throw StateError('Invalid rule recovery input');
  final before = await _stamp(home, path);
  var bytes = 0;
  final digest = await sha256
      .bind(
        file.openRead().map((chunk) {
          bytes += chunk.length;
          if (bytes > 64 * 1024 * 1024)
            throw StateError('Rule recovery input grew beyond its budget');
          return chunk;
        }),
      )
      .first;
  if (await _stamp(home, path) != before)
    throw StateError('Rule input changed while hashing');
  return digest.toString();
}

Future<void> _copy(
  Directory home,
  String source,
  String target,
  String expected, {
  bool replaceScratch = false,
}) async {
  await nativeLitePrivateDestination(File(source), home);
  await nativeLitePrivateDestination(File(target), home);
  if (await _digest(home, source) != expected)
    throw StateError('Rule immutable source changed');
  if (await File(target).exists()) {
    if (await _digest(home, target) == expected) return;
    if (!replaceScratch) throw StateError('Immutable rule destination changed');
    await File(target).delete();
  }
  await File(target).create(exclusive: true);
  await File(source).copy(target);
  final handle = await File(target).open(mode: FileMode.append);
  try {
    await handle.flush();
  } finally {
    await handle.close();
  }
  if (await _digest(home, target) != expected)
    throw StateError('Rule recovery copy readback differs');
}

Future<Object> _stamp(Directory home, String path) async {
  await nativeLitePrivateDestination(File(path), home);
  final stat = await File(path).stat();
  return (
    stat.type,
    stat.size,
    stat.modified,
    stat.changed,
    stat.mode,
    await FileSystemEntity.type(path, followLinks: false),
  );
}

Future<bool> _strictAlive(int previous) async {
  if (previous <= 0 ||
      previous > (Platform.isWindows ? 0xffffffff : 0x7fffffff))
    throw StateError('Invalid previous OS process identity');
  if (Platform.isWindows) return isProcessAlive(previous);
  if (Platform.isLinux || Platform.isAndroid) {
    String stat;
    try {
      stat = await File('/proc/$previous/stat').readAsString();
    } on FileSystemException catch (error) {
      if (error.osError?.errorCode == 2) return false;
      rethrow;
    }
    final end = stat.lastIndexOf(')');
    if (end < 0 || stat.length <= end + 2)
      throw StateError('Cannot prove previous process state');
    final state = stat.substring(end + 1).trim();
    if (state.isEmpty) throw StateError('Cannot prove previous process state');
    return true;
  }
  final result = await Process.run('ps', ['-o', 'stat=', '-p', '$previous']);
  final state = (result.stdout as String).trim();
  if (result.exitCode == 1 &&
      state.isEmpty &&
      (result.stderr as String).trim().isEmpty)
    return false;
  if (result.exitCode != 0 || state.isEmpty)
    throw StateError('Cannot prove previous OS process ended');
  return true;
}
