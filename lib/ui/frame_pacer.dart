import 'dart:async';

import 'package:flutter/scheduler.dart';

/// Runs [onFrame] at a capped rate, scheduling a frame only when one is due.
class FramePacer {
  FramePacer(this.onFrame, {int framesPerSecond = 30})
    : assert(framesPerSecond > 0),
      _framesPerSecond = framesPerSecond;

  final void Function(Duration elapsed) onFrame;

  int _framesPerSecond;
  Duration? _origin;
  Duration _lastElapsed = Duration.zero;
  Duration _rateEpoch = Duration.zero;
  int _lastPublishedFrame = 0;
  Timer? _timer;
  int? _callbackId;
  bool _active = false;

  bool get isActive => _active;

  int get framesPerSecond => _framesPerSecond;

  set framesPerSecond(int value) {
    assert(value > 0);
    if (value == _framesPerSecond) return;
    _framesPerSecond = value;
    _rateEpoch = _lastElapsed;
    _lastPublishedFrame = 0;
    if (_timer == null) return;
    _timer!.cancel();
    _arm();
  }

  /// Restarts elapsed time at zero; a stopped pacer resumes this way.
  void start() {
    if (_active) return;
    _active = true;
    _lastElapsed = Duration.zero;
    _rateEpoch = Duration.zero;
    _lastPublishedFrame = 0;
    final binding = SchedulerBinding.instance;
    if (binding.schedulerPhase == SchedulerPhase.idle) {
      _origin = null;
      _request();
    } else {
      _origin = binding.currentFrameTimeStamp;
      _arm();
    }
  }

  void stop() {
    _active = false;
    _timer?.cancel();
    _timer = null;
    final id = _callbackId;
    if (id == null) return;
    _callbackId = null;
    SchedulerBinding.instance.cancelFrameCallbackWithId(id);
  }

  void dispose() => stop();

  void _arm() {
    final next = (_lastPublishedFrame + 1) * Duration.microsecondsPerSecond;
    final sinceEpoch = (next + _framesPerSecond - 1) ~/ _framesPerSecond;
    final due = _rateEpoch + Duration(microseconds: sinceEpoch);
    _timer = Timer((due - _lastElapsed) * timeDilation, _request);
  }

  void _request() {
    _timer = null;
    _callbackId = SchedulerBinding.instance.scheduleFrameCallback(_tick);
  }

  void _tick(Duration timeStamp) {
    _callbackId = null;
    final origin = _origin ??= timeStamp;
    _lastElapsed = timeStamp - origin;
    final frame =
        (_lastElapsed - _rateEpoch).inMicroseconds *
        _framesPerSecond ~/
        Duration.microsecondsPerSecond;
    if (frame > _lastPublishedFrame) {
      _lastPublishedFrame = frame;
      onFrame(_lastElapsed);
    }
    if (_active && _timer == null && _callbackId == null) _arm();
  }
}
