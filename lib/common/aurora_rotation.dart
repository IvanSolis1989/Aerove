import 'dart:async';
import 'dart:math';

import 'package:fl_clash/models/models.dart';

int _monthOf(DateTime date) => date.year * 100 + date.month;

ThemeProps shuffleAuroraWallpaper(
  ThemeProps theme, {
  DateTime? now,
  Random? random,
}) {
  if (!theme.auroraEnabled) return theme;
  final month = _monthOf(now ?? DateTime.now());
  return theme.copyWith(
    auroraSeed:
        (theme.auroraSeed + 1 + (random ?? Random()).nextInt(0x3ffffffe)) %
        0x7fffffff,
    auroraRotationMonth: max(theme.auroraRotationMonth, month),
  );
}

ThemeProps rotateAuroraForMonth(
  ThemeProps theme,
  DateTime now, {
  Random? random,
}) {
  final month = _monthOf(now);
  if (theme.auroraRotationMonth == 0) {
    return theme.copyWith(auroraRotationMonth: month);
  }
  if (!theme.auroraEnabled || month <= theme.auroraRotationMonth) {
    return theme;
  }
  return shuffleAuroraWallpaper(theme, now: now, random: random);
}

class AuroraMonthlyRotation {
  AuroraMonthlyRotation({
    required this.onCheck,
    DateTime Function()? now,
    Timer Function(Duration, void Function())? schedule,
  }) : _now = now ?? DateTime.now,
       _schedule = schedule ?? Timer.new;

  final void Function(DateTime) onCheck;
  final DateTime Function() _now;
  final Timer Function(Duration, void Function()) _schedule;
  Timer? _timer;
  bool _active = false;
  bool _disposed = false;

  void setActive(bool active) {
    if (_disposed) return;
    _active = active;
    if (active) {
      check();
    } else {
      _timer?.cancel();
      _timer = null;
    }
  }

  void check() {
    if (!_active || _disposed) return;
    final now = _now();
    onCheck(now);
    _timer?.cancel();
    final nextMonth = DateTime(now.year, now.month + 1);
    _timer = _schedule(nextMonth.difference(now), check);
  }

  void dispose() {
    _disposed = true;
    _active = false;
    _timer?.cancel();
    _timer = null;
  }
}
