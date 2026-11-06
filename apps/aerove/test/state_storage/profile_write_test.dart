import 'dart:async';
import 'package:drift/drift.dart' hide isNull;
import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class WriteGate extends QueryInterceptor {
  Completer<void>? selectionEntered;
  Completer<void>? selectionRelease;

  @override
  Future<int> runUpdate(
    QueryExecutor executor,
    String statement,
    List<Object?> args,
  ) async {
    if (statement.contains('selected_map') && selectionRelease != null) {
      selectionEntered!.complete();
      await selectionRelease!.future;
    }
    return executor.runUpdate(statement, args);
  }

  var attempts = 0;
  int? failAt;
  Completer<void>? entered;
  Completer<void>? release;
  void arm() {
    attempts = 0;
    entered = Completer();
    release = Completer();
  }

  @override
  Future<int> runInsert(
    QueryExecutor executor,
    String statement,
    List<Object?> args,
  ) async {
    if (statement.contains('"profiles"')) {
      attempts++;
      final wait = release;
      if (wait != null && !entered!.isCompleted) {
        entered!.complete();
        await wait.future;
      }
      if (attempts == failAt) throw StateError('held profile write rejected');
    }
    return executor.runInsert(statement, args);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late WriteGate gate;
  late db.Database database;
  late ProviderContainer c;
  late Profiles n;
  Profile current([int id = 1]) =>
      c.read(profilesProvider).firstWhere((p) => p.id == id);
  setUpAll(() => AppLocalizations.load(const Locale('en')));
  setUp(() async {
    gate = WriteGate();
    database = db.Database(NativeDatabase.memory().interceptWith(gate));
    db.database = database;
    await database.profiles.put(
      const Profile(
        id: 1,
        label: 'One',
        currentGroupName: 'old',
        autoUpdateDuration: Duration.zero,
      ).toCompanion(),
    );
    await database.profiles.put(
      const Profile(
        id: 2,
        label: 'Two',
        autoUpdateDuration: Duration.zero,
      ).toCompanion(),
    );
    c = ProviderContainer();
    c.listen(profilesProvider, (_, _) {});
    await pumpEventQueue();
    n = c.read(profilesProvider.notifier);
    gate.attempts = 0;
  });
  tearDown(() async {
    c.dispose();
    await database.close();
  });
  Future<void> settled() => pumpEventQueue(times: 100);
  Future<void> failure() {
    final done = Completer<void>();
    final previous = debugPrint;
    debugPrint = (message, {wrapWidth}) {
      if (message?.contains('Optimistic database write failed') ?? false) {
        if (!done.isCompleted) done.complete();
      }
    };
    addTearDown(() => debugPrint = previous);
    return done.future.timeout(const Duration(seconds: 5));
  }

  test('an equal full row does not publish or write', () async {
    final before = c.read(profilesProvider);
    n.put(current());
    await settled();
    expect(gate.attempts, 0);
    expect(c.read(profilesProvider), same(before));
  });
  test(
    'selection completion waits for the selection column to reach SQLite',
    () async {
      gate.selectionEntered = Completer<void>();
      gate.selectionRelease = Completer<void>();
      var completed = false;
      final writing = n.setSelectedProxy(1, 'group', 'chosen').then((value) {
        completed = true;
        return value;
      });
      await gate.selectionEntered!.future;
      expect(completed, false);
      gate.selectionRelease!.complete();
      expect(await writing, true);
      expect((await database.profilesDao.get(1))!.selectedMap, {
        'group': 'chosen',
      });
    },
  );
  test(
    'selection merges the persisted row without replacing newer source overrides',
    () async {
      final stale = current();
      final isolated = ProviderContainer(
        overrides: [
          profilesStreamProvider.overrideWith((ref) => Stream.value([stale])),
        ],
      );
      addTearDown(isolated.dispose);
      isolated.listen(profilesProvider, (_, _) {});
      await isolated.read(profilesStreamProvider.future);
      final source = stale.copyWith(
        overrides: const ProfileOverrides(
          sourceProxyProviders: {
            'new-source': {'type': 'file', 'path': 'owned-source'},
          },
        ),
        selectedMap: const {'other': 'kept'},
      );
      await database.profiles.put(source.toCompanion());
      expect(
        await isolated
            .read(profilesProvider.notifier)
            .setSelectedProxy(1, 'group', 'chosen'),
        true,
      );
      final stored = (await database.profilesDao.get(1))!;
      expect(stored.overrides, source.overrides);
      expect(stored.selectedMap, {'other': 'kept', 'group': 'chosen'});
      expect(
        isolated.read(profilesProvider).single.overrides,
        source.overrides,
      );
    },
  );
  test(
    'a selection queued behind a delete does not recreate the profile',
    () async {
      gate.arm();
      n.put(current().copyWith(currentGroupName: 'held'));
      await gate.entered!.future;
      final deletion = n.del(1);
      final selection = n.setSelectedProxy(1, 'group', 'chosen');
      gate.release!.complete();
      await deletion;
      expect(await selection, false);
      expect(await database.profilesDao.get(1), isNull);
      expect(c.read(profilesProvider).map((profile) => profile.id), [2]);
    },
  );
  test(
    'an in-flight UI write coalesces a burst into one latest write',
    () async {
      gate.arm();
      n.put(current().copyWith(currentGroupName: 'first'));
      await gate.entered!.future;
      for (var i = 0; i < 20; i++) {
        n.put(current().copyWith(currentGroupName: 'g$i', unfoldSet: {'u$i'}));
      }
      expect(current().currentGroupName, 'g19');
      expect(gate.attempts, 1);
      gate.release!.complete();
      await settled();
      expect(gate.attempts, 2);
      final row = (await database.profilesDao.query().get()).firstWhere(
        (p) => p.id == 1,
      );
      expect(row.currentGroupName, 'g19');
      expect(row.unfoldSet, {'u19'});
      expect(current(), row);
    },
  );
  test(
    'coalesced profiles retain a concurrent full-row edit and both ids',
    () async {
      gate.arm();
      n.put(current().copyWith(currentGroupName: 'first'));
      await gate.entered!.future;
      n.put(current().copyWith(url: 'https://new.example/config'));
      n.put(current().copyWith(currentGroupName: 'last', unfoldSet: {'last'}));
      n.put(current(2).copyWith(currentGroupName: 'two', unfoldSet: {'two'}));
      gate.release!.complete();
      await settled();
      final rows = await database.profilesDao.query().get();
      expect(
        rows.firstWhere((p) => p.id == 1).url,
        'https://new.example/config',
      );
      expect(rows.firstWhere((p) => p.id == 1).currentGroupName, 'last');
      expect(rows.firstWhere((p) => p.id == 1).unfoldSet, {'last'});
      expect(rows.firstWhere((p) => p.id == 2).currentGroupName, 'two');
      expect(current(), rows.firstWhere((p) => p.id == 1));
    },
  );
  for (final rejected in [1, 2]) {
    test(
      'failure on write $rejected restores only uncommitted UI fields and preserves a queued edit',
      () async {
        gate.arm();
        gate.failAt = rejected;
        final failed = failure();
        n.put(current().copyWith(currentGroupName: 'first'));
        await gate.entered!.future;
        n.put(
          current().copyWith(currentGroupName: 'last', unfoldSet: {'last'}),
        );
        n.put(current().copyWith(url: 'https://kept.example/config'));
        gate.release!.complete();
        await failed;
        await settled();
        final row = (await database.profilesDao.query().get()).firstWhere(
          (p) => p.id == 1,
        );
        expect(row.url, 'https://kept.example/config');
        expect(row.currentGroupName, rejected == 1 ? 'old' : 'first');
        expect(row.unfoldSet, isEmpty);
        expect(current(), row);
      },
    );
  }
  test('a queued delete cannot be resurrected by a pending UI write', () async {
    gate.arm();
    n.put(current().copyWith(currentGroupName: 'first'));
    await gate.entered!.future;
    n.put(current().copyWith(currentGroupName: 'last'));
    final deletion = n.del(1);
    gate.release!.complete();
    await deletion;
    await settled();
    expect(c.read(profilesProvider).map((p) => p.id), [2]);
    expect((await database.profilesDao.query().get()).map((p) => p.id), [2]);
  });
}
