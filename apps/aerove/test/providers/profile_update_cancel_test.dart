import 'dart:async';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../helpers/app_paths.dart';

class _Core extends Mock implements CoreHandlerInterface {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory home;
  late HttpServer server;
  late db.Database sql;
  late db.Database previousDatabase;
  late ProviderContainer container;
  late _Core core;
  late Future<void> Function(HttpRequest) respond;
  late Completer<void> entered;
  late Completer<void> disconnected;
  var requests = 0;
  final heldSockets = <Socket>{};
  Future<String> Function(String path)? validate;

  setUpAll(() async {
    await AppLocalizations.load(const Locale('en'));
    globalState.packageInfo = PackageInfo(
      appName: 'Aerove',
      packageName: 'com.follow.clash',
      version: '1.0.0',
      buildNumber: '1',
    );
  });

  setUp(() async {
    final bindingOverrides = HttpOverrides.current;
    HttpOverrides.global = null;
    addTearDown(() => HttpOverrides.global = bindingOverrides);
    home = await Directory.systemTemp.createTemp('profile-cancel118-');
    await useAppPaths(home);
    previousDatabase = db.database;
    sql = db.Database(NativeDatabase.memory());
    db.database = sql;
    core = _Core();
    validate = null;
    when(() => core.validateConfig(any())).thenAnswer((call) async {
      return await validate?.call(call.positionalArguments.single as String) ??
          '';
    });
    container = ProviderContainer(
      overrides: [
        currentProfileIdProvider.overrideWithBuild((_, _) => null),
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      ],
    );
    globalState.container = container;
    container.listen(profilesProvider, (_, _) {});
    requests = 0;
    entered = Completer<void>();
    disconnected = Completer<void>();
    respond = (request) async {
      request.response.headers.set('subscription-userinfo', 'total=123');
      request.response.write('proxies: []\nrules: [MATCH,DIRECT]\n');
      await request.response.close();
    };
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      requests++;
      if (!entered.isCompleted) entered.complete();
      try {
        await respond(request);
      } on HttpException {
        // An aborted client is the expected server-side transport outcome.
      } on SocketException {
        // Same outcome when the platform reports a reset instead of a close.
      }
    });
  });

  tearDown(() async {
    for (final socket in heldSockets.toList()) {
      socket.destroy();
    }
    await server.close(force: true);
    container.dispose();
    await waitForDatabaseWrites();
    await sql.close();
    db.database = previousDatabase;
    await home.delete(recursive: true);
  });

  ProfilesAction action() => container.read(profilesActionProvider.notifier);

  Future<void> holdResponse(
    HttpRequest request,
    Completer<void> started, {
    Completer<void>? gone,
  }) async {
    request.response.contentLength = 1000000;
    final socket = await request.response.detachSocket();
    heldSockets.add(socket);
    final closed = Completer<void>();
    void markClosed() {
      if (!closed.isCompleted) closed.complete();
    }

    final subscription = socket.listen(
      (_) {},
      onDone: markClosed,
      onError: (Object _) => markClosed(),
      cancelOnError: true,
    );
    try {
      socket.write('partial response body');
      await socket.flush();
      started.complete();
      await closed.future;
      if (gone != null && !gone.isCompleted) gone.complete();
    } finally {
      await subscription.cancel();
      heldSockets.remove(socket);
      socket.destroy();
    }
  }

  Future<Profile> seed(String path) async {
    final profile = Profile.normal(
      label: path,
      url: 'http://127.0.0.1:${server.port}/$path',
    ).copyWith(lastUpdateDate: DateTime(2020));
    await sql.profiles.put(profile.toCompanion());
    await pumpEventQueue();
    await (await profile.file).writeAsString('original-$path');
    return profile;
  }

  Future<void> expectUnchanged(Profile profile) async {
    final stored = await sql.profilesDao.get(profile.id);
    expect(stored?.lastUpdateDate, profile.lastUpdateDate);
    expect(stored?.subscriptionInfo, profile.subscriptionInfo);
    expect(
      await (await profile.file).readAsString(),
      'original-${profile.label}',
    );
    expect(
      (await home.list(recursive: true).toList()).where(
        (entry) =>
            entry.path.endsWith('.update') ||
            entry.path.endsWith('.previous') ||
            entry.uri.pathSegments.last.startsWith('temp'),
      ),
      isEmpty,
    );
  }

  test(
    'real refresh persists missing and partial observations separately from the last report',
    () async {
      final profile = await seed('quota-observation');
      respond = (request) async {
        request.response.headers.set(
          'subscription-userinfo',
          'upload=0;download=20;total=100',
        );
        request.response.write('proxies: []\n');
        await request.response.close();
      };
      await action().updateProfile(profile);
      final accepted = (await sql.profilesDao.get(profile.id))!;
      expect(accepted.subscriptionInfo?.used, 20);
      expect(accepted.subscriptionInfo?.usage, .2);
      expect(
        accepted.subscriptionInfo?.sourceKey,
        SubscriptionInfo.keyForUrl(profile.url),
      );
      respond = (request) async {
        request.response.write('proxies: []\n');
        await request.response.close();
      };
      await action().updateProfile(accepted);
      final missing = (await sql.profilesDao.get(profile.id))!;
      expect(missing.subscriptionInfo?.upload, isNull);
      expect(missing.subscriptionInfo?.total, isNull);
      expect(missing.subscriptionInfo?.usage, isNull);
      expect(missing.subscriptionInfo?.sameSourceLastReported?.used, 20);
      expect(
        missing.subscriptionInfo?.sameSourceLastReported?.observedAt,
        accepted.subscriptionInfo?.observedAt,
      );
      respond = (request) async {
        request.response.headers.set(
          'subscription-userinfo',
          'download=0;total=200',
        );
        request.response.write('proxies: []\n');
        await request.response.close();
      };
      await action().updateProfile(missing);
      final partial = (await sql.profilesDao.get(profile.id))!;
      expect(partial.subscriptionInfo?.knownDownload, 0);
      expect(partial.subscriptionInfo?.knownUpload, isNull);
      expect(partial.subscriptionInfo?.total, 200);
      expect(partial.subscriptionInfo?.usage, isNull);
      expect(partial.subscriptionInfo?.sameSourceLastReported?.used, 20);
    },
  );

  test(
    'refresh of an edited URL does not carry the previous account report',
    () async {
      final profile = await seed('quota-old-account');
      await action().updateProfile(profile);
      final previous = (await sql.profilesDao.get(profile.id))!;
      respond = (request) async {
        request.response.write('proxies: []\n');
        await request.response.close();
      };
      await action().updateProfile(
        previous.copyWith(url: '${profile.url}-new'),
      );
      final next = (await sql.profilesDao.get(profile.id))!;
      expect(next.subscriptionInfo?.total, isNull);
      expect(next.subscriptionInfo?.lastReported, isNull);
      expect(
        next.subscriptionInfo?.sourceKey,
        SubscriptionInfo.keyForUrl(next.url),
      );
    },
  );

  test(
    'user cancellation aborts real HTTP, joins, and a fresh update succeeds',
    () async {
      final profile = await seed('single');
      final streaming = Completer<void>();
      respond = (request) =>
          holdResponse(request, streaming, gone: disconnected);
      final pending = action().updateProfile(profile, showLoading: true);
      final cancelled = expectLater(
        pending,
        throwsA(isA<UpdateCancelledException>()),
      );
      await streaming.future.timeout(const Duration(seconds: 5));
      await action()
          .cancelProfileUpdate(profile.id)
          .timeout(const Duration(seconds: 5));
      await cancelled;
      await disconnected.future.timeout(const Duration(seconds: 5));
      await expectUnchanged(profile);
      expect(container.read(isUpdatingProvider(profile.updatingKey)), isFalse);
      verifyNever(() => core.validateConfig(any()));

      respond = (request) async {
        request.response.headers.set('subscription-userinfo', 'total=456');
        request.response.write('proxies: []\n');
        await request.response.close();
      };
      await action().updateProfile(profile, showLoading: true);
      final reopened = await sql.profilesDao.get(profile.id);
      expect(reopened?.subscriptionInfo?.total, 456);
      expect(reopened?.lastUpdateDate, isNot(profile.lastUpdateDate));
      expect(await (await profile.file).readAsString(), 'proxies: []\n');
      expect(requests, 2);
      expect(container.read(isUpdatingProvider(profile.updatingKey)), isFalse);
    },
  );

  test(
    'automatic visibility cancellation aborts the same real HTTP path',
    () async {
      final profile = await seed('automatic');
      final streaming = Completer<void>();
      respond = (request) =>
          holdResponse(request, streaming, gone: disconnected);
      final pending = action().autoUpdateProfiles();
      await streaming.future.timeout(const Duration(seconds: 5));
      container
          .read(maintenanceVisibleProvider.notifier)
          .setLifecycle(AppLifecycleState.hidden);
      await pending.timeout(const Duration(seconds: 2));
      await disconnected.future.timeout(const Duration(seconds: 5));
      await expectUnchanged(profile);
    },
  );

  test(
    'cancel during validation joins its reader and never publishes',
    () async {
      final profile = await seed('validating');
      final validating = Completer<void>();
      final release = Completer<void>();
      String? stage;
      validate = (path) async {
        stage = path;
        validating.complete();
        await release.future;
        expect(await File(path).exists(), isTrue);
        return '';
      };
      final pending = action().updateProfile(profile, showLoading: true);
      final assertion = expectLater(
        pending,
        throwsA(isA<UpdateCancelledException>()),
      );
      await validating.future;
      var joined = false;
      final cancelling = action().cancelProfileUpdate(profile.id).then((_) {
        joined = true;
      });
      await pumpEventQueue();
      expect(joined, isFalse);
      expect(await File(stage!).exists(), isTrue);
      release.complete();
      await cancelling;
      await assertion;
      await expectUnchanged(profile);
      expect(await File(stage!).exists(), isFalse);
    },
  );

  test('a newer update joins and replaces the old validation result', () async {
    final profile = await seed('superseded');
    final validating = Completer<void>();
    final release = Completer<void>();
    var validations = 0;
    validate = (_) async {
      if (++validations == 1) {
        validating.complete();
        await release.future;
      }
      return '';
    };
    final old = action().updateProfile(profile, showLoading: true);
    final oldAssertion = expectLater(
      old,
      throwsA(isA<UpdateCancelledException>()),
    );
    await validating.future;
    final newer = action().updateProfile(profile, showLoading: true);
    await pumpEventQueue();
    expect(requests, 1, reason: 'replacement waits for old cleanup');
    release.complete();
    await oldAssertion;
    await newer;
    expect(requests, 2);
    expect(validations, 2);
    expect(
      (await sql.profilesDao.get(profile.id))?.subscriptionInfo?.total,
      123,
    );
    expect(
      await (await profile.file).readAsString(),
      'proxies: []\nrules: [MATCH,DIRECT]\n',
    );
    expect(container.read(isUpdatingProvider(profile.updatingKey)), isFalse);
  });

  test(
    'batch cancellation retains finished rows and cancels active rows',
    () async {
      final finished = await seed('finished');
      final held = await seed('held');
      final heldEntered = Completer<void>();
      respond = (request) async {
        if (request.uri.path == '/held') {
          await holdResponse(request, heldEntered);
          return;
        }
        request.response.headers.set('subscription-userinfo', 'total=789');
        request.response.write('finished bytes');
        await request.response.close();
      };
      final first = action().updateProfile(finished, showLoading: true);
      final second = action().updateProfile(held, showLoading: true);
      final secondAssertion = expectLater(
        second,
        throwsA(isA<UpdateCancelledException>()),
      );
      await heldEntered.future;
      await first;
      final committed = await sql.profilesDao.get(finished.id);
      await action().cancelProfileUpdates([finished.id, held.id]);
      await secondAssertion;
      expect(await sql.profilesDao.get(finished.id), committed);
      expect(committed?.subscriptionInfo?.total, 789);
      expect(await (await finished.file).readAsString(), 'finished bytes');
      await expectUnchanged(held);
      expect(container.read(updatingKeysProvider), isEmpty);
    },
  );

  test(
    'a source URL edit aborts the old download without replacing its file',
    () async {
      final profile = await seed('edited');
      final streaming = Completer<void>();
      respond = (request) => holdResponse(request, streaming);
      final old = action().updateProfile(profile, showLoading: true);
      final assertion = expectLater(
        old,
        throwsA(isA<UpdateCancelledException>()),
      );
      await streaming.future;
      final edited = profile.copyWith(
        url: '${profile.url}-new',
        label: 'new label',
      );
      container.read(profilesProvider.notifier).put(edited);
      await assertion;
      await waitForDatabaseWrites();
      expect((await sql.profilesDao.get(profile.id))?.url, edited.url);
      expect((await sql.profilesDao.get(profile.id))?.subscriptionInfo, isNull);
      expect(await (await profile.file).readAsString(), 'original-edited');
      expect(container.read(updatingKeysProvider), isEmpty);
    },
  );

  test(
    'queued SQL publication rechecks cancellation before touching the file',
    () async {
      final profile = await seed('queued');
      final transactionEntered = Completer<void>();
      final release = Completer<void>();
      final holding = sql.transaction(() async {
        await sql.customSelect('SELECT 1').get();
        transactionEntered.complete();
        await release.future;
      });
      await transactionEntered.future;
      final validated = Completer<void>();
      validate = (_) async {
        validated.complete();
        return '';
      };
      final pending = action().updateProfile(profile, showLoading: true);
      final assertion = expectLater(
        pending,
        throwsA(isA<UpdateCancelledException>()),
      );
      await validated.future;
      await pumpEventQueue();
      final cancelling = action().cancelProfileUpdate(profile.id);
      release.complete();
      await holding;
      await cancelling;
      await assertion;
      await expectUnchanged(profile);
    },
  );

  test('a deferred SQL COMMIT failure restores the published file', () async {
    final profile = await seed('commit-failure');
    await sql.customStatement('PRAGMA foreign_keys = ON');
    await sql.customStatement(
      'CREATE TABLE cancel_parent (id INTEGER PRIMARY KEY)',
    );
    await sql.customStatement(
      'CREATE TABLE cancel_child ('
      'parent INTEGER REFERENCES cancel_parent(id) DEFERRABLE INITIALLY DEFERRED)',
    );
    await sql.customStatement(
      'CREATE TRIGGER reject_profile_commit '
      'AFTER UPDATE OF last_update_date_millis ON profiles '
      'WHEN NEW.last_update_date_millis != OLD.last_update_date_millis '
      'BEGIN INSERT INTO cancel_child VALUES (1); END',
    );
    await expectLater(
      action().updateProfile(profile, showLoading: true),
      throwsA(anything),
    );
    await expectUnchanged(profile);
    expect(container.read(updatingKeysProvider), isEmpty);
  });

  test(
    'an explicit new URL and age key are admitted before the synchronous put',
    () async {
      final profile = await seed('source-before');
      final edited = profile.copyWith(
        url: 'http://127.0.0.1:${server.port}/source-after',
        ageSecretKey: 'AGE-SECRET-KEY-EXPLICIT-EDIT',
      );
      final paths = <String>[];
      respond = (request) async {
        paths.add(request.uri.path);
        request.response.headers.set('subscription-userinfo', 'total=987');
        request.response.write('proxies: []\n');
        await request.response.close();
      };
      await action().updateProfile(edited, showLoading: true);
      expect(paths, ['/source-after']);
      expect(requests, 1);
      verify(() => core.validateConfig(any())).called(1);
      final stored = await sql.profilesDao.get(profile.id);
      expect(stored?.url, edited.url);
      expect(stored?.ageSecretKey, edited.ageSecretKey);
      expect(stored?.subscriptionInfo?.total, 987);
      expect(stored?.lastUpdateDate, isNot(profile.lastUpdateDate));
      expect(container.read(profilesProvider).getProfile(profile.id), stored);
      expect(await (await profile.file).readAsString(), 'proxies: []\n');
      expect(container.read(updatingKeysProvider), isEmpty);
    },
  );

  test(
    'a waiting successor preserves later edits and its own untouched intent',
    () async {
      final profile = await seed('join-edits');
      final validating = Completer<void>();
      final release = Completer<void>();
      var validations = 0;
      validate = (_) async {
        if (++validations == 1) {
          validating.complete();
          await release.future;
        }
        return '';
      };
      final old = action().updateProfile(profile, showLoading: true);
      final oldAssertion = expectLater(
        old,
        throwsA(isA<UpdateCancelledException>()),
      );
      await validating.future;
      final requested = profile.copyWith(label: 'requested label', order: 7);
      final next = action().updateProfile(requested, showLoading: true);
      final later = profile.copyWith(
        label: 'later label',
        currentGroupName: 'later group',
        autoUpdateDuration: const Duration(hours: 9),
        autoUpdate: false,
        selectedMap: {'later group': 'later proxy'},
        unfoldSet: {'later group'},
        extendType: ExtendType.script,
        scriptId: 42,
        matchTarget: 'later target',
        overrides: profile.overrides.copyWith(
          hosts: {
            'later.example': ['192.0.2.118'],
          },
        ),
      );
      container.read(profilesProvider.notifier).put(later);
      await pumpEventQueue();
      expect(requests, 1);
      expect(container.read(profilesProvider).getProfile(profile.id), later);
      release.complete();
      await oldAssertion;
      await next;
      await waitForDatabaseWrites();
      expect(requests, 2);
      expect(validations, 2);
      final stored = await sql.profilesDao.get(profile.id);
      final expected = later.copyWith(order: requested.order);
      expect(
        stored?.copyWith(
          lastUpdateDate: profile.lastUpdateDate,
          subscriptionInfo: profile.subscriptionInfo,
        ),
        expected,
      );
      expect(container.read(profilesProvider).getProfile(profile.id), stored);
      expect(stored?.subscriptionInfo?.total, 123);
      expect(stored?.lastUpdateDate, isNot(profile.lastUpdateDate));
      expect(
        await (await profile.file).readAsString(),
        'proxies: []\nrules: [MATCH,DIRECT]\n',
      );
      expect(container.read(updatingKeysProvider), isEmpty);
    },
  );

  test(
    'a source edit during predecessor join invalidates the waiting request',
    () async {
      final profile = await seed('join-source-edit');
      final validating = Completer<void>();
      final release = Completer<void>();
      validate = (_) async {
        validating.complete();
        await release.future;
        return '';
      };
      final old = action().updateProfile(profile, showLoading: true);
      final oldAssertion = expectLater(
        old,
        throwsA(isA<UpdateCancelledException>()),
      );
      await validating.future;
      final next = action().updateProfile(profile, showLoading: true);
      final nextAssertion = expectLater(
        next,
        throwsA(isA<UpdateCancelledException>()),
      );
      final edited = profile.copyWith(
        url: '${profile.url}-external',
        ageSecretKey: 'AGE-SECRET-KEY-EXTERNAL-EDIT',
      );
      container.read(profilesProvider.notifier).put(edited);
      await pumpEventQueue();
      expect(requests, 1);
      release.complete();
      await oldAssertion;
      await nextAssertion;
      await waitForDatabaseWrites();
      expect(requests, 1, reason: 'invalidated waiter never downloads');
      expect(await sql.profilesDao.get(profile.id), edited);
      expect(container.read(profilesProvider).getProfile(profile.id), edited);
      expect(
        await (await profile.file).readAsString(),
        'original-join-source-edit',
      );
      expect(container.read(updatingKeysProvider), isEmpty);
    },
  );
}
