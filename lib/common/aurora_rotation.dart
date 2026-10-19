import 'dart:async';
import 'dart:math';

import 'package:fl_clash/models/models.dart';
import 'package:flutter/widgets.dart' show AppLifecycleState;

int auroraRotationDayOf(DateTime now) {
  final local = now.toLocal();
  final day = local.hour < 5
      ? DateTime(local.year, local.month, local.day - 1)
      : local;
  return day.year * 10000 + day.month * 100 + day.day;
}

ThemeProps shuffleAuroraWallpaper(
  ThemeProps theme, {
  DateTime? now,
  Random? random,
}) {
  if (!theme.auroraEnabled) return theme;
  final day = auroraRotationDayOf(now ?? DateTime.now());
  return theme.copyWith(
    auroraSeed:
        (theme.auroraSeed + 1 + (random ?? Random()).nextInt(0x3ffffffe)) %
        0x7fffffff,
    auroraRotationDay: max(theme.auroraRotationDay, day),
  );
}

ThemeProps rotateAuroraForDay(
  ThemeProps theme,
  DateTime now, {
  Random? random,
}) {
  if (!theme.auroraEnabled ||
      auroraRotationDayOf(now) <= theme.auroraRotationDay) {
    return theme;
  }
  return shuffleAuroraWallpaper(theme, now: now, random: random);
}

class AuroraDailyRotation {
  AuroraDailyRotation({
    required this.onCheck,
    DateTime Function()? now,
    Timer Function(Duration, void Function())? schedule,
  }) : _now = now ?? DateTime.now,
       _schedule = schedule ?? Timer.new;

  final void Function(DateTime) onCheck;
  final DateTime Function() _now;
  final Timer Function(Duration, void Function()) _schedule;
  Timer? _timer;
  bool _background = false;
  bool _backgroundReady = false;
  bool _detached = false;
  bool _disposed = false;

  void initialize(AppLifecycleState? state) {
    if (_disposed) return;
    _setLifecycle(state);
    _backgroundReady = false;
    check();
  }

  void _setLifecycle(AppLifecycleState? state) {
    _background =
        state == AppLifecycleState.hidden || state == AppLifecycleState.paused;
    _detached = state == AppLifecycleState.detached;
  }

  void setLifecycle(AppLifecycleState? state) {
    if (_disposed) return;
    final previousBackground = _background;
    _setLifecycle(state);
    if (!_background) {
      _backgroundReady = false;
    } else if (!previousBackground) {
      _backgroundReady = true;
    }
    check();
  }

  void _onTimer() {
    if (_background) _backgroundReady = true;
    check();
  }

  void check() {
    if (_disposed) return;
    _timer?.cancel();
    _timer = null;
    if (_detached) return;
    final now = _now().toLocal();
    if (_background && _backgroundReady) onCheck(now);
    final today = DateTime(now.year, now.month, now.day, 5);
    final next = now.isBefore(today)
        ? today
        : DateTime(now.year, now.month, now.day + 1, 5);
    _timer = _schedule(next.difference(now), _onTimer);
  }

  void dispose() {
    _disposed = true;
    _background = false;
    _timer?.cancel();
    _timer = null;
  }
}
