import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:fl_clash/common/request.dart';

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/core/desktop/model.dart';
import 'package:fl_clash/core/meow.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart' show ValueNotifier;
import 'package:flutter_test/flutter_test.dart';

import '../helpers/app_paths.dart';

const _state = SharedState(
  stopTip: 'stop',
  startTip: 'start',
  localNetworkTip: 'network',
  currentProfileName: 'profile',
  stopText: 'stop',
  onlyStatisticsProxy: false,
);

class _CancellationSignal extends ValueNotifier<bool> {
  _CancellationSignal(super.value);
  bool get isObserved => hasListeners;
}

class _Service implements Service {
  final calls = <String>[];
  final requests = <CoreMethodCall>[];
  final cancelled = <String>[];
  final sent = Completer<CoreMethodCall>();
  Future<CoreMethodResponse?> Function(CoreMethodCall)? response;
  Future<String> Function()? initialize;
  Future<void> Function(String)? cancel;
  String syncError = '';
  bool stopResult = true;
  Future<bool> Function()? stopping;

  @override
  Future<String> init() async {
    calls.add('init');
    return initialize == null ? '' : await initialize!();
  }

  @override
  Future<String> syncState(SharedState state) async {
    calls.add('syncState');
    return syncError;
  }

  @override
  Future<bool> shutdown() async {
    calls.add('shutdown');
    return true;
  }

  @override
  Future<bool> start() async {
    calls.add('start');
    return true;
  }

  @override
  Future<bool> stop() async {
    calls.add('stop');
    return stopping == null ? stopResult : await stopping!();
  }

  @override
  Future<bool> park() async {
    calls.add('park');
    return true;
  }

  @override
  Future<void> setUiTelemetryActive(bool active) async {
    calls.add('telemetry:$active');
  }

  @override
  Future<CoreMethodResponse?> invokeMethod(CoreMethodCall call) async {
    requests.add(call);
    if (!sent.isCompleted) sent.complete(call);
    return response == null
        ? CoreMethodResponse(id: call.id, result: true)
        : await response!(call);
  }

  @override
  Future<void> cancelMethod(String id) async {
    cancelled.add(id);
    await cancel?.call(id);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _IncompleteRuleDownload extends Request {
  _IncompleteRuleDownload({this.failCancel = true});
  final bool failCancel;
  final entered = Completer<void>();
  final joinEntered = Completer<void>();
  final joinRelease = Completer<void>();
  bool sinkClosed = false;
  @override
  Future<void> downloadRuleSetToOwnedFile(
    String url,
    File destination, {
    required CancelToken cancellation,
    required bool Function() isCurrent,
    required RuleDownloadOwner owner,
  }) async {
    owner.begin();
    entered.complete();
    await cancellation.whenCancel;
    await owner.closeAndJoin(
      primary: const FormatException('body interrupted'),
      primaryStack: StackTrace.current,
      steps: [
        (
          name: 'reader-cancel',
          close: () {
            if (failCancel) throw StateError('cancel failed');
          },
        ),
        (
          name: 'http-join',
          close: () {
            joinEntered.complete();
            return joinRelease.future;
          },
        ),
        (
          name: 'sink-close',
          close: () {
            sinkClosed = true;
          },
        ),
      ],
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Service service;
  late MeowCoreHandler core;

  setUp(() {
    service = _Service();
    core = MeowCoreHandler.scoped(service, readSharedState: () async => _state);
  });

  test('real Meow setup stages before listener invalidation', () async {
    await core.start();
    final staged = Completer<void>();
    service.response = (call) async {
      await staged.future;
      return CoreMethodResponse(id: call.id, result: '');
    };
    final controller = CoreController.scoped(core);
    final running = controller.setupConfig(
      params: const SetupParams(
        selectedMap: {},
        testUrl: 'https://example.com',
      ),
      preloadInvoke: () async {
        expect(await core.startListener(), isTrue);
      },
    );
    final checked = expectLater(running, completion(''));
    await Future<void>.delayed(Duration.zero);
    expect(service.calls, isNot(contains('start')));
    expect(service.cancelled, isEmpty);
    staged.complete();
    await checked;
    expect(service.calls.where((call) => call == 'start'), hasLength(1));
    await core.close();
  });

  test('Meow rejected setup never starts listener', () async {
    await core.start();
    service.response = (call) async =>
        CoreMethodResponse(id: call.id, result: 'invalid setup');
    expect(
      await CoreController.scoped(core).setupConfig(
        params: const SetupParams(
          selectedMap: {},
          testUrl: 'https://example.com',
        ),
        preloadInvoke: () async {
          await core.startListener();
        },
      ),
      'invalid setup',
    );
    expect(service.calls, isNot(contains('start')));
    await core.close();
  });

  test('cancelled Meow staged setup never starts listener', () async {
    await core.start();
    final cancellation = ValueNotifier(false);
    addTearDown(cancellation.dispose);
    final staged = Completer<void>();
    service.response = (call) async {
      await staged.future;
      return CoreMethodResponse(id: call.id, result: '');
    };
    final running = CoreController.scoped(core).setupConfig(
      params: const SetupParams(
        selectedMap: {},
        testUrl: 'https://example.com',
      ),
      cancellation: cancellation,
      preloadInvoke: () async {
        await core.startListener();
      },
    );
    final checked = expectLater(running, throwsA(isA<CoreMethodException>()));
    await service.sent.future;
    cancellation.value = true;
    staged.complete();
    await checked;
    expect(service.calls, isNot(contains('start')));
    await core.close();
  });

  test(
    'failed HTTP cleanup never becomes quiescent or reaches native inspect',
    () async {
      final home = await Directory.systemTemp.createTemp('meow-rule-unjoined-');
      addTearDown(() => home.delete(recursive: true));
      await useAppPaths(home);
      final download = _IncompleteRuleDownload();
      core = MeowCoreHandler.scoped(
        service,
        readSharedState: () async => _state,
        ruleSetRequest: download,
      );
      await core.start();
      final signal = ValueNotifier(false);
      addTearDown(signal.dispose);
      CoreRequestQuiescence? state;
      final name = List.filled(32, 'a').join() + '.1.new';
      final file = File('${home.path}/providers/rules/$name');
      await file.parent.create(recursive: true);
      await file.writeAsString('owned candidate');
      final pending = core.invokeCancellableMethod<Map>(
        method: CoreMethod.compileRuleSet,
        arguments: {'name': name, 'url': 'https://example.invalid/rules'},
        cancellation: signal,
        observeQuiescence: (value) => state = value,
      );
      final failed = expectLater(
        pending,
        throwsA(isA<RuleDownloadCleanupFailure>()),
      );
      await download.entered.future;
      signal.value = true;
      await download.joinEntered.future;
      expect(state!.isQuiescent, isFalse);
      expect(await file.exists(), isTrue);
      expect(service.requests, isEmpty);
      download.joinRelease.complete();
      await failed;
      expect(download.sinkClosed, isTrue);
      expect(state!.isQuiescent, isFalse);
      expect(await file.exists(), isTrue);
      await expectLater(core.stop(), throwsA(isA<StateError>()));
    },
  );

  for (final operation in ['start', 'stop', 'park']) {
    test(
      '$operation listener rejects unjoined HTTP without skipping safe stop or park',
      () async {
        final home = await Directory.systemTemp.createTemp(
          'meow-listener-unjoined-',
        );
        addTearDown(() => home.delete(recursive: true));
        await useAppPaths(home);
        final download = _IncompleteRuleDownload();
        core = MeowCoreHandler.scoped(
          service,
          readSharedState: () async => _state,
          ruleSetRequest: download,
        );
        final controller = CoreController.scoped(core);
        await core.start();
        final signal = ValueNotifier(false);
        addTearDown(signal.dispose);
        final name = List.filled(32, 'a').join() + '.1.new';
        final file = File('${home.path}/providers/rules/$name');
        await file.parent.create(recursive: true);
        await file.writeAsString('owned listener candidate');
        CoreRequestQuiescence? state;
        final request = core.invokeCancellableMethod<Map>(
          method: CoreMethod.compileRuleSet,
          arguments: {'name': name, 'url': 'https://example.invalid/rules'},
          cancellation: signal,
          observeQuiescence: (value) => state = value,
        );
        final requestFailure = expectLater(
          request,
          throwsA(isA<RuleDownloadCleanupFailure>()),
        );
        await download.entered.future;
        var returned = false;
        final lifecycle = switch (operation) {
          'start' => controller.startListener(),
          'stop' => controller.stopListener(),
          _ => controller.parkListener(),
        };
        final lifecycleFailure = expectLater(
          lifecycle.whenComplete(() => returned = true),
          throwsA(
            isA<StateError>().having(
              (error) => error.message,
              'unconfirmed HTTP cleanup',
              contains('Rule download cleanup is unconfirmed'),
            ),
          ),
        );
        await download.joinEntered.future;
        if (operation == 'start') {
          expect(service.calls, isNot(contains('start')));
        } else {
          expect(service.calls, contains(operation));
        }
        expect(returned, isFalse);
        expect(state!.isQuiescent, isFalse);
        expect(await file.exists(), isTrue);
        expect(service.requests, isEmpty);
        download.joinRelease.complete();
        await requestFailure;
        await lifecycleFailure;
        expect(download.sinkClosed, isTrue);
        expect(state!.isQuiescent, isFalse);
        expect(await file.exists(), isTrue);
        expect(service.requests, isEmpty);
      },
    );
  }

  test(
    'a newer stop supersedes a start waiting for joined HTTP cleanup',
    () async {
      final home = await Directory.systemTemp.createTemp(
        'meow-start-preflight-',
      );
      addTearDown(() => home.delete(recursive: true));
      await useAppPaths(home);
      final download = _IncompleteRuleDownload(failCancel: false);
      core = MeowCoreHandler.scoped(
        service,
        readSharedState: () async => _state,
        ruleSetRequest: download,
      );
      final controller = CoreController.scoped(core);
      await core.start();
      final signal = ValueNotifier(false);
      addTearDown(signal.dispose);
      final name = List.filled(32, 'a').join() + '.2.new';
      final file = File('${home.path}/providers/rules/$name');
      await file.parent.create(recursive: true);
      await file.writeAsString('owned preflight candidate');
      final request = core.invokeCancellableMethod<Map>(
        method: CoreMethod.compileRuleSet,
        arguments: {'name': name, 'url': 'https://example.invalid/rules'},
        cancellation: signal,
      );
      final requestFailure = expectLater(
        request,
        throwsA(isA<CoreMethodException>()),
      );
      await download.entered.future;
      final starting = controller.startListener();
      await download.joinEntered.future;
      final stopping = controller.stopListener();
      expect(service.calls, contains('stop'));
      expect(service.calls, isNot(contains('start')));
      download.joinRelease.complete();
      await requestFailure;
      expect(await starting, isFalse);
      expect(await stopping, isTrue);
      expect(service.calls, isNot(contains('start')));
    },
  );

  test('attach and restart never start or stop the VPN', () async {
    expect((await core.start()).outcome, CoreLifecycleOutcome.applied);
    expect((await core.start()).outcome, CoreLifecycleOutcome.coalesced);
    await core.restart();
    expect(service.calls, [
      'init',
      'syncState',
      'shutdown',
      'init',
      'syncState',
    ]);
    expect(service.requests, isEmpty);
  });

  test(
    'one request cancellation waits for native join and leaves other calls attached',
    () async {
      await core.start();
      final signal = _CancellationSignal(false);
      addTearDown(signal.dispose);
      final late = Completer<CoreMethodResponse?>();
      final cancelled = Completer<String>();
      final joined = Completer<void>();
      service.response = (call) async => call.method == CoreMethod.setupConfig
          ? await late.future
          : CoreMethodResponse(id: call.id, result: true);
      service.cancel = (id) async {
        cancelled.complete(id);
        await joined.future;
      };
      var finished = false;
      final pending = core.invokeCancellableMethod<String>(
        method: CoreMethod.setupConfig,
        cancellation: signal,
      );
      final checked = expectLater(
        pending.whenComplete(() => finished = true),
        throwsA(
          isA<CoreMethodException>().having(
            (error) => error.code,
            'code',
            'cancelled',
          ),
        ),
      );
      final request = await service.sent.future;
      signal.value = true;
      expect(await cancelled.future, request.id);
      expect(finished, false);
      expect(await core.invokeMethod<bool>(method: CoreMethod.getIsInit), true);
      expect(service.calls, ['init', 'syncState']);
      joined.complete();
      await checked;
      expect(service.cancelled, [request.id]);
      late.complete(CoreMethodResponse(id: request.id, result: ''));
      await pumpEventQueue();
      expect(signal.isObserved, false);
    },
  );

  test('a pre-cancelled scoped request never reaches native', () async {
    await core.start();
    final signal = _CancellationSignal(true);
    try {
      await expectLater(
        core.invokeCancellableMethod<String>(
          method: CoreMethod.setupConfig,
          cancellation: signal,
        ),
        throwsA(isA<CoreMethodException>()),
      );
      expect(service.requests, isEmpty);
      expect(service.cancelled, isEmpty);
      expect(signal.isObserved, false);
    } finally {
      signal.dispose();
    }
  });

  test(
    'failed cancellation is not quiescence until the actual native request returns',
    () async {
      await core.start();
      final signal = ValueNotifier(false);
      final late = Completer<CoreMethodResponse?>();
      service.response = (_) => late.future;
      service.cancel = (_) async =>
          throw PlatformException(code: 'termination_unconfirmed');
      CoreRequestQuiescence? native;
      final pending = core.invokeCancellableMethod<String>(
        method: CoreMethod.setupConfig,
        cancellation: signal,
        observeQuiescence: (state) => native = state,
      );
      final checked = expectLater(pending, throwsA(isA<CoreMethodException>()));
      final request = await service.sent.future;
      signal.value = true;
      await checked;
      expect(native!.isQuiescent, false);
      late.complete(CoreMethodResponse(id: request.id, result: ''));
      await native!.whenQuiescent;
      expect(native!.isQuiescent, true);
      signal.dispose();
    },
  );

  test(
    'scoped cancellation discards a matching late spill after joined cancellation',
    () async {
      final root = await Directory.systemTemp.createTemp('scoped-meow-spill-');
      addTearDown(() => root.delete(recursive: true));
      await useAppPaths(root);
      final directory = await Directory('${root.path}/responses').create();
      final own = await File('${directory.path}/late.json').writeAsString('{}');
      final foreign = await File(
        '${directory.path}/foreign.json',
      ).writeAsString('{}');
      final late = Completer<CoreMethodResponse?>();
      service.response = (_) => late.future;
      await core.start();
      final signal = ValueNotifier(false);
      final pending = core.invokeCancellableMethod<String>(
        method: CoreMethod.getConfig,
        cancellation: signal,
      );
      final checked = expectLater(pending, throwsA(isA<CoreMethodException>()));
      final request = await service.sent.future;
      signal.value = true;
      await checked;
      late.complete(CoreMethodResponse(id: request.id, result: own.path));
      await pumpEventQueue();
      expect(await own.exists(), false);
      expect(await foreign.exists(), true);
      signal.dispose();
    },
  );

  test(
    'each listener intent uses one native command and no core RPC',
    () async {
      await core.start();
      service.calls.clear();
      expect(await core.startListener(), isTrue);
      expect(await core.adoptListener(), isTrue);
      expect(await core.parkListener(), isTrue);
      expect(await core.stopListener(), isTrue);
      expect(service.calls, ['start', 'start', 'park', 'stop']);
      expect(service.requests, isEmpty);
    },
  );

  test(
    'late successful stop cannot acknowledge a newer listener intent',
    () async {
      await core.start();
      final reply = Completer<bool>();
      service.stopping = () => reply.future;
      final stopping = core.stopListener();
      final checked = expectLater(
        stopping,
        throwsA(
          isA<CoreStopException>().having(
            (error) => error.code,
            'code',
            'stop_superseded',
          ),
        ),
      );
      expect(await core.startListener(), isTrue);
      reply.complete(true);
      await checked;
      expect(service.calls.where((call) => call == 'stop'), hasLength(1));
    },
  );

  test(
    'false stop remains a failure but does not claim a missing Core reply',
    () async {
      service.stopResult = false;
      await expectLater(
        core.stopListener(),
        throwsA(
          isA<CoreStopException>().having(
            (error) => error.code,
            'code',
            'termination_unconfirmed',
          ),
        ),
      );
    },
  );

  test('real stop wire retains only bounded native code locations', () async {
    const channel = MethodChannel('$packageName/service');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    const safe =
        'service.stop: IllegalStateException@dev.aerove.lite.flutter.MeowParkedGraphOwner.applyStaged:91{graph_epoch_mismatch}';
    Object? details = {
      'stopRestoreLocation': safe,
      'url': 'https://private.invalid/token',
    };
    messenger.setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(
        code: 'stop_graph_restore_failed',
        message: 'private original',
        details: details,
      );
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final controller = CoreController.scoped(
      MeowCoreHandler.scoped(Service(), readSharedState: () async => _state),
    );
    await expectLater(
      controller.stopListener(),
      throwsA(
        isA<CoreStopException>()
            .having((e) => e.code, 'code', 'stop_graph_restore_failed')
            .having((e) => e.message, 'location', contains(safe))
            .having((e) => e.toString(), 'privacy', isNot(contains('private'))),
      ),
    );
    for (final unsafe in [
      'service.stop: https://private.invalid/token',
      'service.stop: /private/path',
      'service.stop: ' + 'x' * 1025,
    ]) {
      details = {'stopRestoreLocation': unsafe};
      await expectLater(
        controller.stopListener(),
        throwsA(
          isA<CoreStopException>().having(
            (e) => e.message,
            'fixed safe fallback',
            'Service stopped; parked query graph restoration failed',
          ),
        ),
      );
    }
  });

  for (final reply in [true, false, null]) {
    test(
      'real Service channel stop reply $reply retains its outcome',
      () async {
        const channel = MethodChannel('$packageName/service');
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        messenger.setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'stop');
          expect(call.arguments, isA<int>());
          return reply;
        });
        addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
        final controller = CoreController.scoped(
          MeowCoreHandler.scoped(
            Service(),
            readSharedState: () async => _state,
          ),
        );
        if (reply == true) {
          expect(await controller.stopListener(), isTrue);
        } else {
          await expectLater(
            controller.stopListener(),
            throwsA(
              isA<CoreStopException>()
                  .having(
                    (error) => error.code,
                    'code',
                    'termination_unconfirmed',
                  )
                  .having(
                    (error) => error.message.contains('did not answer'),
                    'old error',
                    isFalse,
                  ),
            ),
          );
        }
      },
    );
  }

  for (final code in [
    'stop_superseded',
    'termination_unconfirmed',
    'stop_graph_restore_failed',
    'stop_graph_restore_cancelled',
  ]) {
    test(
      'native $code is preserved without private platform details',
      () async {
        service.stopping = () async => throw PlatformException(
          code: code,
          message: 'private example',
          details: {'secret': 'private'},
        );
        await expectLater(
          core.stopListener(),
          throwsA(
            isA<CoreStopException>()
                .having((error) => error.code, 'code', code)
                .having((error) => error.details, 'details', isNull)
                .having(
                  (error) => error.toString(),
                  'safe message',
                  isNot(contains('private')),
                ),
          ),
        );
      },
    );
  }

  for (final failCleanup in [false, true]) {
    test(
      'native stop rejection joins cancellation; cleanup fails=$failCleanup',
      () async {
        final home = await Directory.systemTemp.createTemp(
          'meow-stop-rejection-',
        );
        addTearDown(() => home.delete(recursive: true));
        await useAppPaths(home);
        final download = _IncompleteRuleDownload(failCancel: failCleanup);
        core = MeowCoreHandler.scoped(
          service,
          readSharedState: () async => _state,
          ruleSetRequest: download,
        );
        await core.start();
        final signal = ValueNotifier(false);
        addTearDown(signal.dispose);
        final name = List.filled(32, 'a').join() + '.8.new';
        final file = File('${home.path}/providers/rules/$name');
        await file.parent.create(recursive: true);
        await file.writeAsString('anonymous candidate');
        CoreRequestQuiescence? state;
        final request = core.invokeCancellableMethod<Map>(
          method: CoreMethod.compileRuleSet,
          arguments: {'name': name, 'url': 'https://example.invalid/rules'},
          cancellation: signal,
          observeQuiescence: (value) => state = value,
        );
        final failedRequest = expectLater(
          request,
          throwsA(
            failCleanup
                ? isA<RuleDownloadCleanupFailure>()
                : isA<CoreMethodException>(),
          ),
        );
        await download.entered.future;
        service.stopping = () async =>
            throw PlatformException(code: 'termination_unconfirmed');
        var returned = false;
        final failedStop = expectLater(
          core.stopListener().whenComplete(() => returned = true),
          throwsA(
            failCleanup
                ? isA<CoreStopCleanupException>()
                      .having(
                        (error) => error.stopFailure,
                        'original stop',
                        isA<PlatformException>(),
                      )
                      .having(
                        (error) => error.cleanupFailure,
                        'cleanup failure',
                        isA<StateError>(),
                      )
                : isA<CoreStopException>().having(
                    (error) => error.code,
                    'code',
                    'termination_unconfirmed',
                  ),
          ),
        );
        await download.joinEntered.future;
        await pumpEventQueue();
        expect(returned, isFalse);
        expect(state!.isQuiescent, isFalse);
        expect(await file.exists(), isTrue);
        download.joinRelease.complete();
        await failedRequest;
        await failedStop;
        expect(state!.isQuiescent, !failCleanup);
        expect(download.sinkClosed, isTrue);
      },
    );
  }

  test('failed sync detaches and can retry', () async {
    service.syncError = 'bad runtime projection';
    await expectLater(core.start(), throwsStateError);
    expect(service.calls, ['init', 'syncState', 'shutdown']);
    service.syncError = '';
    expect((await core.start()).outcome, CoreLifecycleOutcome.applied);
  });

  test('close supersedes an attachment that has not finished', () async {
    final ready = Completer<String>();
    service.initialize = () => ready.future;
    final starting = core.start();
    await Future<void>.delayed(Duration.zero);
    final closing = core.close();
    ready.complete('');
    expect((await starting).outcome, CoreLifecycleOutcome.superseded);
    await closing;
    expect(service.calls, ['init', 'stop', 'shutdown']);
  });

  test(
    'terminal close confirms stop, detaches once, and rejects future work',
    () async {
      await core.start();
      service.calls.clear();
      final first = core.close();
      expect(identical(first, core.close()), isTrue);
      await first;
      expect(service.calls, ['stop', 'shutdown']);
      await expectLater(core.start(), throwsStateError);
      await expectLater(core.stop(), throwsStateError);
      await expectLater(core.startListener(), throwsStateError);
      await expectLater(
        core.invokeMethod<bool>(method: CoreMethod.getIsInit),
        throwsStateError,
      );
    },
  );

  test('unconfirmed stop leaves close failed and cannot be replaced', () async {
    service.stopResult = false;
    final closing = core.close();
    await expectLater(closing, throwsStateError);
    expect(identical(closing, core.close()), isTrue);
    expect(service.calls, ['stop']);
    await expectLater(core.start(), throwsStateError);
  });

  test(
    'request IDs are unique across handlers and attachment epochs',
    () async {
      final other = MeowCoreHandler.scoped(
        service,
        readSharedState: () async => _state,
      );
      await core.start();
      await core.invokeMethod<bool>(method: CoreMethod.getIsInit);
      await core.restart();
      await core.invokeMethod<bool>(method: CoreMethod.getIsInit);
      await other.start();
      await other.invokeMethod<bool>(method: CoreMethod.getIsInit);
      expect(service.requests.map((call) => call.id).toSet(), hasLength(3));
    },
  );

  test(
    'null, wrong ID and invalid result types never become success',
    () async {
      await core.start();
      for (final response in <CoreMethodResponse?>[
        null,
        const CoreMethodResponse(id: 'another-request', result: true),
      ]) {
        service.response = (_) async => response;
        await expectLater(
          core.invokeMethod<bool>(method: CoreMethod.getIsInit),
          throwsA(isA<CoreMethodException>()),
        );
      }
      service.response = (call) async => CoreMethodResponse(id: call.id);
      await expectLater(
        core.invokeMethod<bool>(method: CoreMethod.getIsInit),
        throwsA(isA<CoreMethodException>()),
      );
      service.response = (call) async =>
          CoreMethodResponse(id: call.id, result: 'wrong type');
      await expectLater(
        core.invokeMethod<bool>(method: CoreMethod.getIsInit),
        throwsA(isA<CoreMethodException>()),
      );
    },
  );

  test('structured native errors retain code and details', () async {
    await core.start();
    service.response = (call) async => CoreMethodResponse(
      id: call.id,
      error: const CoreMethodError(
        code: 'bad_argument',
        message: 'timeout',
        details: {'field': 'timeout'},
      ),
    );
    await expectLater(
      core.invokeMethod<bool>(method: CoreMethod.getIsInit),
      throwsA(
        isA<CoreMethodException>()
            .having((e) => e.code, 'code', 'bad_argument')
            .having((e) => e.details, 'details', {'field': 'timeout'}),
      ),
    );
  });

  test('timeout waits for native cancellation acknowledgement', () async {
    await core.start();
    final reply = Completer<CoreMethodResponse?>();
    final acknowledged = Completer<void>();
    final cancellation = Completer<String>();
    service.response = (_) => reply.future;
    service.cancel = (id) {
      cancellation.complete(id);
      return acknowledged.future;
    };
    var finished = false;
    final pending = core.invokeMethod<bool>(
      method: CoreMethod.getIsInit,
      timeout: const Duration(milliseconds: 1),
    );
    final checked = expectLater(
      pending.whenComplete(() => finished = true),
      throwsA(isA<TimeoutException>()),
    );
    final id = await cancellation.future;
    expect(finished, isFalse);
    acknowledged.complete();
    await checked;
    reply.complete(CoreMethodResponse(id: id, result: true));
    await Future<void>.delayed(Duration.zero);
    expect(service.cancelled, [id]);
  });

  test(
    'detach cancels pending RPC and a late old response cannot affect a new epoch',
    () async {
      await core.start();
      final reply = Completer<CoreMethodResponse?>();
      service.response = (_) => reply.future;
      final pending = core.invokeMethod<bool>(method: CoreMethod.getIsInit);
      final checked = expectLater(pending, throwsA(isA<CoreMethodException>()));
      final old = await service.sent.future;
      await core.stop();
      await checked;
      expect(service.cancelled, [old.id]);
      expect(service.calls.last, 'shutdown');
      await core.start();
      service.response = (call) async =>
          CoreMethodResponse(id: call.id, result: false);
      reply.complete(CoreMethodResponse(id: old.id, result: true));
      expect(
        await core.invokeMethod<bool>(method: CoreMethod.getIsInit),
        isFalse,
      );
    },
  );

  test(
    'a matching late text spill is deleted, an unrelated ID file is preserved',
    () async {
      final root = await Directory.systemTemp.createTemp('meow-spill-');
      addTearDown(() => root.delete(recursive: true));
      await useAppPaths(root);
      final directory = await Directory('${root.path}/responses').create();
      final own = await File('${directory.path}/own.json').writeAsString('{}');
      final unrelated = await File(
        '${directory.path}/unrelated.json',
      ).writeAsString('{}');
      await core.start();
      final reply = Completer<CoreMethodResponse?>();
      service.response = (_) => reply.future;
      final checked = expectLater(
        core.invokeMethod<String>(
          method: CoreMethod.getConfig,
          timeout: const Duration(milliseconds: 1),
        ),
        throwsA(isA<TimeoutException>()),
      );
      final call = await service.sent.future;
      await checked;
      final deleted = directory
          .watch(events: FileSystemEvent.delete)
          .firstWhere((event) => event.path.endsWith('own.json'))
          .timeout(const Duration(seconds: 5));
      reply.complete(CoreMethodResponse(id: call.id, result: own.path));
      await deleted;
      expect(await own.exists(), isFalse);
      service.response = (_) async =>
          CoreMethodResponse(id: 'not-owned', file: unrelated.path);
      await expectLater(
        core.invokeMethod<String>(method: CoreMethod.getConfig),
        throwsA(isA<CoreMethodException>()),
      );
      expect(await unrelated.exists(), isTrue);
    },
  );

  test(
    'outside spill path is an error and the file remains untouched',
    () async {
      final root = await Directory.systemTemp.createTemp('meow-spill-scope-');
      addTearDown(() => root.delete(recursive: true));
      await useAppPaths(root);
      final outside = await File(
        '${root.path}/outside.json',
      ).writeAsString('{}');
      await core.start();
      service.response = (call) async =>
          CoreMethodResponse(id: call.id, file: outside.path);
      await expectLater(
        core.invokeMethod<String>(method: CoreMethod.getConfig),
        throwsA(
          isA<CoreMethodException>().having(
            (e) => e.code,
            'code',
            'spill_error',
          ),
        ),
      );
      expect(await outside.exists(), isTrue);
    },
  );

  test(
    'malformed owned envelope spill reports error and removes only that file',
    () async {
      final root = await Directory.systemTemp.createTemp('meow-spill-invalid-');
      addTearDown(() => root.delete(recursive: true));
      await useAppPaths(root);
      final directory = await Directory('${root.path}/responses').create();
      final malformed = await File(
        '${directory.path}/bad.json',
      ).writeAsString('{');
      await core.start();
      service.response = (call) async =>
          CoreMethodResponse(id: call.id, file: malformed.path);
      await expectLater(
        core.invokeMethod<String>(method: CoreMethod.getConfig),
        throwsA(
          isA<CoreMethodException>().having(
            (error) => error.code,
            'code',
            'spill_error',
          ),
        ),
      );
      expect(await malformed.exists(), isFalse);
    },
  );

  test(
    'listener stop cancels old RPC while keeping the transport attached',
    () async {
      await core.start();
      final late = Completer<CoreMethodResponse?>();
      service.response = (_) => late.future;
      final pending = core.invokeMethod<bool>(method: CoreMethod.getIsInit);
      final checked = expectLater(pending, throwsA(isA<CoreMethodException>()));
      final call = await service.sent.future;
      await core.stopListener();
      await checked;
      expect(service.cancelled, [call.id]);
      expect(service.calls, ['init', 'syncState', 'stop']);
      service.response = (call) async =>
          CoreMethodResponse(id: call.id, result: true);
      expect(
        await core.invokeMethod<bool>(method: CoreMethod.getIsInit),
        isTrue,
      );
      late.complete(CoreMethodResponse(id: call.id, result: false));
    },
  );

  test('failed cancellation remains a transport error', () async {
    await core.start();
    final late = Completer<CoreMethodResponse?>();
    service.response = (_) => late.future;
    service.cancel = (_) async =>
        throw PlatformException(code: 'cancel_failed');
    await expectLater(
      core.invokeMethod<bool>(
        method: CoreMethod.getIsInit,
        timeout: const Duration(milliseconds: 1),
      ),
      throwsA(
        isA<CoreMethodException>().having(
          (error) => error.code,
          'code',
          'transport_error',
        ),
      ),
    );
    late.complete(
      CoreMethodResponse(id: service.requests.single.id, result: true),
    );
  });

  test(
    'new stop supersedes an in-flight restart instead of reattaching',
    () async {
      await core.start();
      final restarting = core.restart();
      final stopping = core.stop();
      expect((await restarting).outcome, CoreLifecycleOutcome.superseded);
      await stopping;
      expect(service.calls.where((call) => call == 'init'), hasLength(1));
    },
  );

  test(
    'connection timeout does not dispatch or cancel a nonexistent native call',
    () async {
      core = MeowCoreHandler.scoped(
        service,
        connectionTimeout: const Duration(milliseconds: 1),
      );
      await expectLater(
        core.invokeMethod<bool>(method: CoreMethod.getIsInit),
        throwsA(isA<TimeoutException>()),
      );
      expect(service.requests, isEmpty);
      expect(service.cancelled, isEmpty);
    },
  );

  test(
    'terminal close cancels pending RPC before owned stop and detach',
    () async {
      await core.start();
      final late = Completer<CoreMethodResponse?>();
      service.response = (_) => late.future;
      final pending = core.invokeMethod<bool>(method: CoreMethod.getIsInit);
      final checked = expectLater(pending, throwsA(isA<CoreMethodException>()));
      final call = await service.sent.future;
      await core.close();
      await checked;
      expect(service.cancelled, [call.id]);
      expect(service.calls, ['init', 'syncState', 'stop', 'shutdown']);
      late.complete(CoreMethodResponse(id: call.id, result: true));
    },
  );

  test(
    'Service rejects mismatched IDs before touching envelope spill files',
    () async {
      final root = await Directory.systemTemp.createTemp('meow-wire-id-');
      addTearDown(() => root.delete(recursive: true));
      await useAppPaths(root);
      final directory = await Directory('${root.path}/responses').create();
      final foreign = await File(
        '${directory.path}/foreign.json',
      ).writeAsString('must not be parsed or deleted');
      final owned = await File(
        '${directory.path}/owned.json',
      ).writeAsString(jsonEncode({'id': 'owned:spill', 'result': true}));
      const channel = MethodChannel('$packageName/core');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      var mismatched = true;
      messenger.setMockMethodCallHandler(channel, (call) async {
        final request = jsonDecode(call.arguments as String) as Map;
        return Uint8List.fromList(
          utf8.encode(
            jsonEncode({
              'id': mismatched ? 'foreign:request' : request['id'],
              'file': mismatched ? foreign.path : owned.path,
            }),
          ),
        );
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final native = Service();
      await expectLater(
        native.invokeMethod(
          const CoreMethodCall(id: 'owned:spill', method: CoreMethod.getIsInit),
        ),
        throwsA(
          isA<CoreMethodException>().having(
            (error) => error.code,
            'code',
            'invalid_response',
          ),
        ),
      );
      expect(await foreign.readAsString(), 'must not be parsed or deleted');
      mismatched = false;
      final response = await native.invokeMethod(
        const CoreMethodCall(id: 'owned:spill', method: CoreMethod.getIsInit),
      );
      expect(response?.unwrap<bool>(), isTrue);
      expect(await owned.exists(), isFalse);
      expect(await foreign.exists(), isTrue);
    },
  );

  test(
    'Service preserves JSON String / UTF8 envelope and cancel ID wire',
    () async {
      const channel = MethodChannel('$packageName/core');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final wire = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        wire.add(call);
        if (call.method == 'cancel') return null;
        final request = jsonDecode(call.arguments as String) as Map;
        return Uint8List.fromList(
          utf8.encode(jsonEncode({'id': request['id'], 'result': true})),
        );
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final native = Service();
      final response = await native.invokeMethod(
        const CoreMethodCall(id: 'owned:1', method: CoreMethod.getIsInit),
      );
      expect(response?.unwrap<bool>(), isTrue);
      await native.cancelMethod('owned:1');
      expect(wire.map((call) => call.method), ['invokeMethod', 'cancel']);
      expect(wire.last.arguments, 'owned:1');
    },
  );
}
