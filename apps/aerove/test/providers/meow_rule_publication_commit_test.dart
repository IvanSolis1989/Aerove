import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart' hide isNull;
import 'package:drift/native.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/rule_sql_journal.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite;
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import '../../../aerove-lite/lib/lite_product.dart';
import '../helpers/app_paths.dart';

class _JoinedInspection implements CoreRequestQuiescence {
  @override
  bool get isQuiescent => true;
  @override
  Future<void> get whenQuiescent => Future.value();
}

class _UnjoinedInspection implements CoreRequestQuiescence {
  final _joined = Completer<void>();
  @override
  bool get isQuiescent => false;
  @override
  Future<void> get whenQuiescent => _joined.future;
}

class _InspectCore extends Mock implements CoreController {
  bool unjoined = false;
  String? candidate;
  var inspections = 0;
  @override
  Future<RuleSetInfo> compileRuleSet(
    String name, {
    String url = '',
    void Function(CoreRequestQuiescence state)? observeQuiescence,
    void Function(String digest)? inspectedDigest,
  }) async {
    inspections++;
    candidate = await appPath.getProviderCachePath(name);
    if (unjoined) {
      observeQuiescence?.call(_UnjoinedInspection());
      throw RuleDownloadCleanupFailure(
        const FormatException('interrupted body'),
        StackTrace.current,
        [
          (
            step: 'http-join',
            error: StateError('unconfirmed'),
            stack: StackTrace.current,
          ),
        ],
      );
    }
    observeQuiescence?.call(_JoinedInspection());
    final bytes = await File(candidate!).readAsBytes();
    inspectedDigest?.call(sha256.convert(bytes).toString());
    return (
      behavior: RuleProviderBehavior.domain,
      format: RuleProviderFormat.text,
    );
  }
}

class _QuietSetup extends SetupAction {
  @override
  void build() {}
}

class _SqlBarrier extends QueryInterceptor {
  String? phase;
  bool used = false;
  final entered = Completer<void>();
  final release = Completer<void>();
  Future<void> pause(String statement, String kind) async {
    if (used || phase == null) return;
    final matches =
        phase == 'provider-sql' && statement.contains('clash_providers') ||
        phase == 'profile-sql' && statement.contains('"profiles"') ||
        phase == 'rules-sql' &&
            kind == 'update' &&
            statement.contains('"rules"');
    if (matches) {
      used = true;
      entered.complete();
      await release.future;
    }
  }

  @override
  Future<int> runInsert(
    QueryExecutor executor,
    String statement,
    List<Object?> args,
  ) async {
    final result = await executor.runInsert(statement, args);
    await pause(statement, 'insert');
    return result;
  }

  @override
  Future<int> runUpdate(
    QueryExecutor executor,
    String statement,
    List<Object?> args,
  ) async {
    final result = await executor.runUpdate(statement, args);
    await pause(statement, 'update');
    return result;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  ClientProduct.configure(
    product: liteProduct,
    backendCoreId: 'meow-rs',
    backendFactory: () =>
        throw StateError('No native core in transaction regression'),
  );
  late Directory home;
  late db.Database database;
  late ProviderContainer container;
  late ClashProvidersAction action;
  late _InspectCore core;
  late _SqlBarrier barrier;
  const original = ClashProvider(
    id: 71,
    label: 'old',
    behavior: RuleProviderBehavior.domain,
    format: RuleProviderFormat.text,
  );
  const profile = Profile(
    id: 10,
    label: 'profile',
    type: ProfileType.custom,
    autoUpdateDuration: Duration(days: 1),
    overrides: ProfileOverrides(
      dns: Dns(nameserverPolicy: {'rule-set:old': '223.5.5.5'}),
      dnsOverrideKeys: {DnsOverrideKey.nameserverPolicy},
    ),
  );

  setUp(() async {
    home = await Directory.systemTemp.createTemp('meow-rule-commit-');
    await useAppPaths(home);
    barrier = _SqlBarrier();
    database = db.Database.interceptNative(
      NativeDatabase(File('${home.path}/database.sqlite')),
      barrier,
    );
    db.database = database;
    await database.clashProviders.put(original.toCompanion());
    await database.profiles.put(profile.toCompanion());
    final source = File(await original.path);
    await source.parent.create(recursive: true);
    await source.writeAsString('old.example\n');
    core = _InspectCore();
    container = ProviderContainer(
      overrides: [
        setupActionProvider.overrideWith(() => _QuietSetup()),
        coreHandlerProvider.overrideWithValue(core),
        currentProfileProvider.overrideWithValue(null),
      ],
    );
    container.listen(profilesProvider, (_, _) {});
    container.listen(clashProvidersProvider, (_, _) {});
    await container.read(clashProvidersProvider.future);
    await pumpEventQueue();
    action = container.read(clashProvidersActionProvider.notifier);
  });
  tearDown(() async {
    container.dispose();
    await database.close();
    await home.delete(recursive: true);
  });

  test(
    'an unjoined request keeps the actual stage candidate and never publishes raw or SQL',
    () async {
      core.unjoined = true;
      await expectLater(
        action.putProvider(original, content: utf8.encode('new.example\n')),
        throwsA(isA<RuleDownloadCleanupFailure>()),
      );
      expect(await File(core.candidate!).exists(), isTrue);
      expect(await File(await original.path).readAsString(), 'old.example\n');
      expect((await database.clashProvidersDao.query().get()).single, original);
    },
  );

  for (final phase in [
    'after-final-stamp',
    'after-rename',
    'provider-sql',
    'rules-sql',
    'profile-sql',
  ]) {
    test(
      'intent invalidation at $phase rolls back raw, SQL and optimistic UI',
      () async {
        var current = true;
        final next = original.copyWith(label: 'first');
        if (phase.startsWith('after-')) {
          action.meowRulePublicationBarrier = (at) async {
            if (at == phase) current = false;
          };
        } else {
          barrier.phase = phase;
        }
        final saving = action.putProvider(
          next,
          content: utf8.encode('new.example\n'),
          isCurrent: () => current,
        );
        final failure = expectLater(
          saving,
          throwsA(isA<UpdateCancelledException>()),
        );
        if (!phase.startsWith('after-')) {
          await barrier.entered.future;
          current = false;
          barrier.release.complete();
        }
        await failure;
        expect(await File(await original.path).readAsString(), 'old.example\n');
        expect(
          (await database.clashProvidersDao.query().get()).single,
          original,
        );
        expect(
          container.read(clashProvidersProvider).requireValue.single,
          original,
        );
        expect(container.read(profilesProvider).single, profile);
        expect(await database.profilesDao.get(profile.id), profile);
      },
    );
  }

  test('a third raw value survives failed intent rollback', () async {
    var current = true;
    barrier.phase = 'provider-sql';
    final saving = action.putProvider(
      original.copyWith(label: 'first'),
      content: utf8.encode('new.example\n'),
      isCurrent: () => current,
    );
    final failure = expectLater(
      saving,
      throwsA(
        isA<RuleSqlPublicationFailure>()
            .having(
              (e) => e.primary,
              'primary',
              isA<UpdateCancelledException>(),
            )
            .having((e) => e.recovery, 'recovery', isA<StateError>()),
      ),
    );
    await barrier.entered.future;
    await File(await original.path).writeAsString('third.example\n');
    current = false;
    barrier.release.complete();
    await failure;
    expect(await File(await original.path).readAsString(), 'third.example\n');
    expect((await database.clashProvidersDao.query().get()).single, original);
    final journals = await Directory(
      '${home.path}/rule-sql-publications',
    ).list().where((entry) => entry is Directory).toList();
    expect(journals, hasLength(1));
    expect(
      await File('${journals.single.path}/0.before').readAsString(),
      'old.example\n',
    );
  });

  test(
    'an independent caller Zone queues behind committed readback and cannot be overwritten by old completion',
    () async {
      final callerZone = Zone.current;
      final readbackEntered = Completer<void>();
      final readbackRelease = Completer<void>();
      final laterArrived = Completer<void>();
      final events = <String>[];
      var baselineEntries = 0;
      var current = true;
      var firstReturned = false;
      var laterReturned = false;
      Future<ClashProvider>? later;
      action.meowRulePublicationBarrier = (phase) async {
        if (phase == 'after-baseline') {
          baselineEntries++;
          events.add('baseline:$baselineEntries');
        }
        if (phase == 'before-commit-readback' && !readbackEntered.isCompleted) {
          events.add('old-readback-held');
          readbackEntered.complete();
          await readbackRelease.future;
          events.add('old-readback-released');
        }
      };
      action.meowRuleCommitAdmitted = () {
        action.meowRuleCommitAdmitted = null;
        current = false;
        later = callerZone
            .run(() {
              expect(Zone.current, same(callerZone));
              events.add('later-arrival');
              laterArrived.complete();
              return action.putProvider(
                original.copyWith(label: 'second'),
                content: utf8.encode('second.example\n'),
              );
            })
            .whenComplete(() => laterReturned = true);
      };
      final first = action
          .putProvider(
            original.copyWith(label: 'first'),
            content: utf8.encode('first.example\n'),
            isCurrent: () => current,
          )
          .whenComplete(() => firstReturned = true);
      await laterArrived.future;
      await readbackEntered.future;
      await pumpEventQueue();
      expect(baselineEntries, 1);
      expect(core.inspections, 1);
      expect(firstReturned, isFalse);
      expect(laterReturned, isFalse);
      expect(await File(await original.path).readAsString(), 'first.example\n');
      // Independent physical read proves commit without entering the held fence.
      final observer = sqlite.sqlite3.open(
        '${home.path}/database.sqlite',
        mode: sqlite.OpenMode.readOnly,
      );
      try {
        expect(
          observer
              .select('SELECT label FROM clash_providers WHERE id = 71')
              .single['label'],
          'first',
        );
      } finally {
        observer.dispose();
      }
      expect(
        container.read(clashProvidersProvider).requireValue.single.label,
        'first',
      );
      readbackRelease.complete();
      expect((await first).label, 'first');
      expect((await later!).label, 'second');
      expect(
        events.indexOf('baseline:2'),
        greaterThan(events.indexOf('old-readback-released')),
      );
      expect(
        await File(await original.path).readAsString(),
        'second.example\n',
      );
      expect(
        (await database.clashProvidersDao.query().get()).single.label,
        'second',
      );
      expect(
        container.read(clashProvidersProvider).requireValue.single.label,
        'second',
      );
      expect(
        container.read(profilesProvider).single.overrides.dns.nameserverPolicy,
        {'rule-set:second': '223.5.5.5'},
      );
      expect(
        (await database.profilesDao.get(
          profile.id,
        ))!.overrides.dns.nameserverPolicy,
        {'rule-set:second': '223.5.5.5'},
      );
    },
  );
}
