import 'dart:async';

import 'package:flutter/scheduler.dart';

/// Runs [onFrame] at a capped rate, scheduling a frame only when one is due;
/// pacers running together count from one grid, so their frames coincide.
class FramePacer {
  FramePacer(this.onFrame, {int framesPerSecond = 30})
    : assert(framesPerSecond > 0),
      _framesPerSecond = framesPerSecond;

  static final _running = <FramePacer>{};
  static Duration? _grid;

  final void Function(Duration elapsed) onFrame;

  int _framesPerSecond;
  Duration? _origin;
  Duration _last = Duration.zero;
  Duration _target = Duration.zero;
  int _lastPublishedFrame = 0;
  Timer? _timer;
  int? _callbackId;

  bool get isActive => _running.contains(this);

  int get framesPerSecond => _framesPerSecond;

  set framesPerSecond(int value) {
    assert(value > 0);
    if (value == _framesPerSecond) return;
    _framesPerSecond = value;
    if (!isActive || _origin == null) return;
    if (_running.length == 1) _grid = _last;
    _lastPublishedFrame = _frameAt(_last);
  }

  /// Restarts elapsed time at zero; a stopped pacer resumes this way.
  void start() {
    if (!_running.add(this)) return;
    final binding = SchedulerBinding.instance;
    if (binding.schedulerPhase == SchedulerPhase.idle) {
      _origin = null;
      _request();
    } else {
      _begin(binding.currentFrameTimeStamp);
      _wait(_due, from: _last);
    }
  }

  void stop() {
    if (_running.remove(this) && _running.isEmpty) _grid = null;
    _timer?.cancel();
    _timer = null;
    final id = _callbackId;
    if (id == null) return;
    _callbackId = null;
    SchedulerBinding.instance.cancelFrameCallbackWithId(id);
  }

  void dispose() => stop();

  void _begin(Duration timeStamp) {
    _origin = timeStamp;
    _last = timeStamp;
    _grid ??= timeStamp;
    _lastPublishedFrame = _frameAt(timeStamp);
  }

  int _frameAt(Duration timeStamp) =>
      (timeStamp - _grid!).inMicroseconds *
      _framesPerSecond ~/
      Duration.microsecondsPerSecond;

  Duration get _due {
    final next = (_lastPublishedFrame + 1) * Duration.microsecondsPerSecond;
    final sinceGrid = (next + _framesPerSecond - 1) ~/ _framesPerSecond;
    return _grid! + Duration(microseconds: sinceGrid);
  }

  void _wait(Duration target, {required Duration from}) {
    _target = target;
    _timer = Timer((target - from) * timeDilation, _fire);
  }

  void _fire() {
    _timer = null;
    final due = _due;
    if (due > _target) {
      _wait(due, from: _target);
    } else {
      _request();
    }
  }

  void _request() {
    _callbackId = SchedulerBinding.instance.scheduleFrameCallback(_tick);
  }

  void _tick(Duration timeStamp) {
    _callbackId = null;
    final origin = _origin;
    if (origin == null) {
      _begin(timeStamp);
    } else {
      _last = timeStamp;
      final frame = _frameAt(timeStamp);
      if (frame > _lastPublishedFrame) {
        _lastPublishedFrame = frame;
        onFrame(timeStamp - origin);
      }
    }
    if (isActive && _timer == null && _callbackId == null) {
      _wait(_due, from: _last);
    }
  }
}
