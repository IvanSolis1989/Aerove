import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/event.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/core/spill.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

final _utf8JsonDecoder = utf8.decoder.fuse(json.decoder);

Object? _decodeUtf8Json(Uint8List data) => _utf8JsonDecoder.convert(data);

Map<String, Object?> _decodeResponseOf((Uint8List, CoreMethod) request) {
  final (data, method) = request;
  final decoded = _decodeUtf8Json(data);
  return withResultModel(method, Map<String, Object?>.from(decoded as Map));
}

Future<Map<String, Object?>> _decodeResponse(
  Uint8List data,
  CoreMethod method,
) async {
  if (data.length < 51200) {
    return _decodeResponseOf((data, method));
  }
  return compute(_decodeResponseOf, (data, method));
}

/// The run the Android service holds and the options it runs with.
typedef ServiceRunTime = ({
  DateTime startedAt,
  VpnOptions? vpnOptions,
  bool parked,
});

abstract mixin class ServiceListener {
  void onServiceEvent(CoreEvent event) {}

  void onServiceStopped() {}
}

class Service {
  static Service? _instance;
  late MethodChannel methodChannel;
  static const _coreChannel = MethodChannel('$packageName/core');

  final ObserverList<ServiceListener> _listeners =
      ObserverList<ServiceListener>();

  int _commandRevision = 0;
  bool _startRequested = false;

  factory Service() {
    _instance ??= Service._internal();
    return _instance!;
  }

  Service._internal() {
    methodChannel = const MethodChannel('$packageName/service');
    methodChannel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'event':
          final methodCall = CoreMethodCall.fromJson(
            Map<String, Object?>.from(
              _decodeUtf8Json(call.arguments as Uint8List) as Map,
            ),
          );
          for (final event in coreEventsFromData(methodCall.arguments)) {
            _dispatch(
              'Core event ${event.type.name}',
              (listener) => listener.onServiceEvent(event),
            );
          }
          break;
        case 'stopped':
          // A report stamped before the newest command no longer describes it.
          if (call.arguments != _commandRevision || !_startRequested) {
            break;
          }
          _startRequested = false;
          _dispatch('stop report', (listener) => listener.onServiceStopped());
          break;
        default:
          throw MissingPluginException();
      }
    });
  }

  Future<CoreMethodResponse?> invokeMethod(CoreMethodCall call) async {
    final data = await _coreChannel.invokeMethod<Uint8List>(
      'invokeMethod',
      json.encode(call),
    );
    if (data == null) {
      return null;
    }
    return resolveSpilledResponse(
      CoreMethodResponse.fromJson(await _decodeResponse(data, call.method)),
      method: call.method,
    );
  }

  void _dispatch(String label, void Function(ServiceListener) deliver) {
    for (final listener in List.of(_listeners)) {
      try {
        deliver(listener);
      } catch (error) {
        commonPrint.log(
          'Unable to dispatch Android $label: $error',
          logLevel: LogLevel.error,
        );
      }
    }
  }

  Future<bool> start() async {
    _startRequested = true;
    return await methodChannel.invokeMethod<bool>(
          'start',
          ++_commandRevision,
        ) ??
        false;
  }

  Future<bool> park() async {
    _startRequested = true;
    return await methodChannel.invokeMethod<bool>('park', ++_commandRevision) ??
        false;
  }

  Future<bool> stop() async {
    _startRequested = false;
    return await methodChannel.invokeMethod<bool>('stop', ++_commandRevision) ??
        false;
  }

  Future<String> init() async {
    return await methodChannel.invokeMethod<String>('init') ?? '';
  }

  Future<void> setUiTelemetryActive(bool active) async {
    await methodChannel.invokeMethod<void>('setUiTelemetryActive', active);
  }

  Future<String> syncState(SharedState state) async {
    return await methodChannel.invokeMethod<String>(
          'syncState',
          json.encode(state),
        ) ??
        '';
  }

  Future<bool> shutdown() async {
    return await methodChannel.invokeMethod<bool>('shutdown') ?? true;
  }

  Future<ServiceRunTime?> getRunTime() async {
    final data = await methodChannel.invokeMethod<String>('getRunTime');
    if (data == null || data.isEmpty) {
      return null;
    }
    final report = Map<String, Object?>.from(json.decode(data) as Map);
    final startedAt = report['startedAt'];
    if (startedAt is! int || startedAt == 0) {
      return null;
    }
    final options = report['vpnOptions'];
    return (
      startedAt: DateTime.fromMillisecondsSinceEpoch(startedAt),
      vpnOptions: options is Map ? _runningVpnOptionsOf(options) : null,
      parked: report['parked'] == true,
    );
  }

  // Gson fills the service's options from preferences an older build may have
  // written, and leaves a field that build lacked null, which toJson omits.
  VpnOptions? _runningVpnOptionsOf(Map<Object?, Object?> options) {
    try {
      return VpnOptions.fromJson(Map<String, Object?>.from(options));
    } catch (e) {
      commonPrint.log(
        'getRunTime vpnOptions ===> ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
      return null;
    }
  }

  bool get hasListeners {
    return _listeners.isNotEmpty;
  }

  void addListener(ServiceListener listener) {
    _listeners.add(listener);
  }

  void removeListener(ServiceListener listener) {
    _listeners.remove(listener);
  }
}

Service? get service => system.isAndroid ? Service() : null;
