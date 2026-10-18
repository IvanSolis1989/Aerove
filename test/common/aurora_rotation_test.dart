import 'dart:async';
import 'dart:math';

import 'package:fl_clash/common/aurora_rotation.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('fresh and legacy themes keep their first wallpaper', () {
    for (final theme in [const ThemeProps(), ThemeProps.fromJson({})]) {
      expect(theme.auroraRotationMonth, 0);
      final initialized = rotateAuroraForMonth(theme, DateTime(2026, 10, 18));
      expect(initialized, theme.copyWith(auroraRotationMonth: 202610));
      expect(
        rotateAuroraForMonth(initialized, DateTime(2026, 10, 31)),
        same(initialized),
      );
    }
  });

  test('only the first check in a later local month changes the seed', () {
    const theme = ThemeProps(auroraRotationMonth: 202610);
    final changed = rotateAuroraForMonth(
      theme,
      DateTime(2026, 11),
      random: Random(7),
    );
    expect(changed.auroraSeed, isNot(theme.auroraSeed));
    expect(changed.auroraSeed, inInclusiveRange(0, 0x7ffffffe));
    expect(changed.auroraRotationMonth, 202611);
    expect(
      changed.copyWith(
        auroraSeed: theme.auroraSeed,
        auroraRotationMonth: theme.auroraRotationMonth,
      ),
      theme,
    );
    expect(
      rotateAuroraForMonth(changed, DateTime(2026, 11, 30)),
      same(changed),
    );
    expect(rotateAuroraForMonth(changed, DateTime(2026, 10)), same(changed));
  });

  test(
    'missed months catch up once and disabled wallpaper waits for enable',
    () {
      const disabled = ThemeProps(
        auroraEnabled: false,
        auroraRotationMonth: 202610,
      );
      final now = DateTime(2027, 3, 12);
      expect(rotateAuroraForMonth(disabled, now), same(disabled));
      expect(shuffleAuroraWallpaper(disabled, now: now), same(disabled));
      final enabled = disabled.copyWith(auroraEnabled: true);
      final changed = rotateAuroraForMonth(enabled, now, random: Random(9));
      expect(
        changed,
        shuffleAuroraWallpaper(enabled, now: now, random: Random(9)),
      );
      expect(changed.auroraRotationMonth, 202703);
      expect(rotateAuroraForMonth(changed, now), same(changed));
    },
  );

  test('manual shuffle remains repeatable and satisfies this month', () {
    const theme = ThemeProps(auroraRotationMonth: 202610);
    final now = DateTime(2026, 11, 1);
    final first = shuffleAuroraWallpaper(theme, now: now, random: Random(3));
    final second = shuffleAuroraWallpaper(first, now: now, random: Random(4));
    expect(first.auroraSeed, isNot(theme.auroraSeed));
    expect(second.auroraSeed, isNot(first.auroraSeed));
    expect(second.auroraRotationMonth, 202611);
    expect(rotateAuroraForMonth(second, now), same(second));
  });

  test('seed and month survive the existing config storage together', () async {
    await mockPreferences();
    final changed = rotateAuroraForMonth(
      const ThemeProps(auroraRotationMonth: 202609),
      DateTime(2026, 10, 18),
      random: Random(11),
    );
    expect(await preferences.saveConfig(Config(themeProps: changed)), isTrue);
    final restored = (await preferences.getConfig())!.themeProps;
    expect(restored, changed);
    expect(
      rotateAuroraForMonth(restored, DateTime(2026, 10, 31)),
      same(restored),
    );
  });

  testWidgets('foreground checks exactly at local month boundaries', (
    tester,
  ) async {
    var now = DateTime(2026, 12, 31, 23, 59, 59);
    var theme = const ThemeProps(auroraRotationMonth: 202612);
    final delays = <Duration>[];
    var checks = 0;
    final rotation = AuroraMonthlyRotation(
      now: () => now,
      schedule: (delay, callback) {
        delays.add(delay);
        return Timer(delay, callback);
      },
      onCheck: (date) {
        checks++;
        theme = rotateAuroraForMonth(theme, date, random: Random(1));
      },
    );
    try {
      rotation.setActive(true);
      expect(checks, 1);
      expect(delays, [const Duration(seconds: 1)]);
      final seed = theme.auroraSeed;
      now = DateTime(2027);
      await tester.pump(const Duration(seconds: 1));
      expect(checks, 2);
      expect(theme.auroraSeed, isNot(seed));
      expect(theme.auroraRotationMonth, 202701);
      expect(delays.last, const Duration(days: 31));
      await tester.pump(const Duration(hours: 1));
      expect(checks, 2);
    } finally {
      rotation.dispose();
    }
  });

  testWidgets('background cancels its timer and resume catches up once', (
    tester,
  ) async {
    var now = DateTime(2028, 2, 28);
    var theme = const ThemeProps(auroraRotationMonth: 202802);
    var checks = 0;
    Timer? timer;
    final rotation = AuroraMonthlyRotation(
      now: () => now,
      schedule: (delay, callback) => timer = Timer(delay, callback),
      onCheck: (date) {
        checks++;
        theme = rotateAuroraForMonth(theme, date);
      },
    );
    addTearDown(rotation.dispose);
    rotation.setActive(true);
    rotation.setActive(false);
    expect(timer!.isActive, isFalse);
    now = DateTime(2028, 5, 3);
    await tester.pump(const Duration(days: 65));
    expect(checks, 1);
    rotation.setActive(true);
    expect(checks, 2);
    expect(theme.auroraRotationMonth, 202805);
    final seed = theme.auroraSeed;
    rotation.check();
    expect(theme.auroraSeed, seed);
    rotation.dispose();
    expect(timer!.isActive, isFalse);
    rotation.setActive(true);
    rotation.check();
    expect(checks, 3);
  });
}
