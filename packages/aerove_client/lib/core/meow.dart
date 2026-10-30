import 'dart:async';

import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/services.dart';

import 'desktop/model.dart';
import 'interface.dart';
import 'method.dart';
import 'spill.dart';

class MeowCoreHandler extends CoreHandlerInterface {
  MeowCoreHandler() : this.scoped(Service());

  MeowCoreHandler.scoped(
    this._service, {
    Future<SharedState> Function()? readSharedState,
    this.connectionTimeout = const Duration(seconds: 10),
    this.requestTimeout = const Duration(minutes: 3),
  }) : _readSharedState = readSharedState ?? _sharedState;

  final Service _service;
  final Future<SharedState> Function() _readSharedState;
  final Duration connectionTimeout;
  final Duration requestTimeout;
  static int _nextSession = 0;
  final String _session =
      '${DateTime.now().microsecondsSinceEpoch}:${_nextSession++}';
  final Map<String, _MeowCall> _calls = {};
  Completer<void> _connection = Completer<void>();
  Future<void> _lifecycleTail = Future.value();
  Future<CoreLifecycleResult>? _closeOperation;
  int _epoch = 0;
  int _attachmentEpoch = 0;
  int _revision = 0;
  int _callId = 0;
  bool _attached = false;
  bool _closed = false;

  static Future<SharedState> _sharedState() async {
    await globalState.container.read(vpnOptionsProvider.future);
    return globalState.container.read(sharedStateProvider);
  }

  void _ensureOpen() {
    if (_closed) throw StateError('Core lifecycle is closed');
  }

  Future<T> _queue<T>(Future<T> Function() operation) {
    final result = _lifecycleTail.then((_) => operation());
    _lifecycleTail = result.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    return result;
  }

  CoreLifecycleResult _result(int revision, CoreLifecycleOutcome outcome) =>
      CoreLifecycleResult(revision: revision, outcome: outcome);

  @override
  Future<CoreLifecycleResult> start() async {
    _ensureOpen();
    final revision = ++_revision;
    final epoch = _attachmentEpoch;
    return _queue(() async {
      if (_closed || epoch != _attachmentEpoch) {
        return _result(revision, CoreLifecycleOutcome.superseded);
      }
      if (_attached) return _result(revision, CoreLifecycleOutcome.coalesced);
      final error = await _service.init();
      if (_closed || epoch != _attachmentEpoch) {
        return _result(revision, CoreLifecycleOutcome.superseded);
      }
      if (error.isNotEmpty) throw StateError(error);
      try {
        final state = await _readSharedState();
        if (_closed || epoch != _attachmentEpoch) {
          return _result(revision, CoreLifecycleOutcome.superseded);
        }
        final syncError = await _service.syncState(state);
        if (_closed || epoch != _attachmentEpoch) {
          return _result(revision, CoreLifecycleOutcome.superseded);
        }
        if (syncError.isNotEmpty) throw StateError(syncError);
        _attached = true;
        _connection.complete();
        return _result(revision, CoreLifecycleOutcome.applied);
      } catch (_) {
        if (!await _service.shutdown()) {
          throw StateError('Meow attachment cleanup failed');
        }
        rethrow;
      }
    });
  }

  Future<void> _invalidate({bool detach = true}) {
    _epoch++;
    if (detach) {
      _attachmentEpoch++;
      _attached = false;
      _connection = Completer<void>();
    }
    clearConnectionCache();
    return Future.wait(
      List.of(_calls.values).map(
        (call) => _cancel(
          call,
          const CoreMethodException(
            code: 'transport_disconnected',
            message: 'Meow attachment was superseded',
          ),
        ),
      ),
    );
  }

  @override
  Future<CoreLifecycleResult> stop() async {
    _ensureOpen();
    final revision = ++_revision;
    final cancellations = _invalidate();
    return _queue(() async {
      await cancellations;
      if (!await _service.shutdown()) {
        throw StateError('Meow service detach failed');
      }
      return _result(revision, CoreLifecycleOutcome.applied);
    });
  }

  @override
  Future<CoreLifecycleResult> restart() async {
    final stopped = await stop();
    if (_closed || stopped.revision != _revision) {
      return _result(stopped.revision, CoreLifecycleOutcome.superseded);
    }
    return start();
  }

  @override
  Future<CoreLifecycleResult> close() => _closeOperation ??= _close();

  Future<CoreLifecycleResult> _close() {
    _closed = true;
    final revision = ++_revision;
    final cancellations = _invalidate();
    return _queue(() async {
      await cancellations;
      if (!await _service.stop()) {
        throw StateError('Meow service stop was not confirmed');
      }
      if (!await _service.shutdown()) {
        throw StateError('Meow service detach failed');
      }
      return _result(revision, CoreLifecycleOutcome.applied);
    });
  }

  @override
  Future<bool> startListener() async {
    _ensureOpen();
    final cancellations = _invalidate(detach: false);
    final epoch = _epoch;
    final accepted = await _service.start();
    await cancellations;
    return !_closed && epoch == _epoch && accepted;
  }

  @override
  Future<bool> adoptListener() => startListener();

  @override
  Future<bool> stopListener() async {
    _ensureOpen();
    final cancellations = _invalidate(detach: false);
    final epoch = _epoch;
    final accepted = await _service.stop();
    await cancellations;
    return !_closed && epoch == _epoch && accepted;
  }

  @override
  Future<bool> parkListener() async {
    _ensureOpen();
    final cancellations = _invalidate(detach: false);
    final epoch = _epoch;
    final accepted = await _service.park();
    await cancellations;
    return !_closed && epoch == _epoch && accepted;
  }

  @override
  Future<void> setUiTelemetryActive(bool active) async {
    _ensureOpen();
    await _service.setUiTelemetryActive(active);
  }

  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) async {
    _ensureOpen();
    final id = '$_session:$_epoch:${++_callId}';
    final call = _MeowCall(id, _epoch, method);
    _calls[id] = call;
    final limit = timeout ?? requestTimeout;
    call.timer = Timer(limit, () {
      unawaited(
        _cancel(call, TimeoutException('Meow ${method.name} timed out', limit)),
      );
    });
    unawaited(_dispatch(call, arguments, _connection.future));
    try {
      final response = await call.response.future;
      try {
        return response.unwrap<T>();
      } on TypeError catch (error) {
        throw CoreMethodException(
          code: 'invalid_response',
          message: 'Invalid ${method.name} response: $error',
        );
      }
    } finally {
      call.timer?.cancel();
      _calls.remove(id);
    }
  }

  bool _current(_MeowCall call) =>
      !_closed &&
      call.epoch == _epoch &&
      !call.response.isCompleted &&
      call.cancellation == null;

  Future<void> _dispatch(
    _MeowCall call,
    Object? arguments,
    Future<void> connection,
  ) async {
    try {
      await Future.any([
        connection,
        call.response.future.then<void>((_) {}),
      ]).timeout(connectionTimeout);
      if (!_current(call)) return;
      call.sent = true;
      var response = await _service.invokeMethod(
        CoreMethodCall(id: call.id, method: call.method, arguments: arguments),
      );
      if (response == null || response.id != call.id) {
        throw const CoreMethodException(
          code: 'invalid_response',
          message: 'Meow response did not echo its request id',
        );
      }
      response = await resolveSpilledResponse(response, method: call.method);
      if (!_current(call)) {
        await _discard(call, response);
        return;
      }
      if (response.id != call.id) {
        throw const CoreMethodException(
          code: 'invalid_response',
          message: 'Meow spill response id mismatch',
        );
      }
      if (response.error == null &&
          response.result == null &&
          !_nullableResults.contains(call.method)) {
        throw CoreMethodException(
          code: 'invalid_response',
          message: 'Meow ${call.method.name} returned no result',
        );
      }
      call.response.complete(response);
    } catch (error, stack) {
      if (_current(call)) {
        call.response.completeError(_transportError(error), stack);
      }
    }
  }

  Future<void> _discard(_MeowCall call, CoreMethodResponse response) async {
    if (response.id != call.id) return;
    await discardSpilledFile(response.file);
    if (call.method == CoreMethod.getConfig ||
        call.method == CoreMethod.buildProfile) {
      await discardSpilledFile(response.result);
    }
  }

  Future<void> _cancel(_MeowCall call, Object reason) =>
      call.cancellation ??= _cancelCall(call, reason);

  Future<void> _cancelCall(_MeowCall call, Object reason) async {
    if (call.response.isCompleted) return;
    call.timer?.cancel();
    try {
      if (call.sent) await _service.cancelMethod(call.id);
      if (!call.response.isCompleted) call.response.completeError(reason);
    } catch (error, stack) {
      if (!call.response.isCompleted) {
        call.response.completeError(_transportError(error), stack);
      }
    }
  }

  Object _transportError(Object error) {
    if (error is CoreMethodException || error is TimeoutException) return error;
    return CoreMethodException(
      code: error is MissingPluginException
          ? 'transport_disconnected'
          : error is FormatException || error is TypeError
          ? 'invalid_response'
          : 'transport_error',
      message: '$error',
      details: error is PlatformException ? error.details : null,
    );
  }

  static const _nullableResults = {
    CoreMethod.getAppliedSetup,
    CoreMethod.getProxyDefinition,
    CoreMethod.getExternalProvider,
    CoreMethod.watchRoute,
    CoreMethod.checkRoutes,
    CoreMethod.getMemoryStats,
    CoreMethod.asyncTestDelay,
    CoreMethod.probe,
    CoreMethod.outboundIp,
    CoreMethod.resetTraffic,
    CoreMethod.startLog,
    CoreMethod.stopLog,
  };
}

class _MeowCall {
  _MeowCall(this.id, this.epoch, this.method);

  final String id;
  final int epoch;
  final CoreMethod method;
  final Completer<CoreMethodResponse> response = Completer();
  Timer? timer;
  Future<void>? cancellation;
  bool sent = false;
}
