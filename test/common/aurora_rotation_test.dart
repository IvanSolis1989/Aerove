import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:fl_clash/common/aurora_rotation.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/widgets.dart' show AppLifecycleState;
import 'package:flutter_test/flutter_test.dart';

import '../helpers/preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('05:00 local periods cross month, year and leap-day boundaries', () {
    final dates = {
      DateTime(2026, 10, 18, 4, 59, 59, 999): 20261017,
      DateTime(2026, 10, 18, 5): 20261018,
      DateTime(2026, 10, 18, 23, 59): 20261018,
      DateTime(2027, 1, 1, 4, 59): 20261231,
      DateTime(2027, 1, 1, 5): 20270101,
      DateTime(2028, 3, 1, 4, 59): 20280229,
      DateTime(2028, 3, 1, 5): 20280301,
      DateTime(2027, 3, 1, 4, 59): 20270228,
    };
    for (final entry in dates.entries) {
      expect(auroraRotationDayOf(entry.key), entry.value);
      expect(auroraRotationDayOf(entry.key.toUtc()), entry.value);
    }
  });

  test('one seed/day update preserves all other settings and deduplicates', () {
    const theme = ThemeProps(
      auroraColors: [0xFF65BDD2, 0xFFC589CA],
      auroraRotationDay: 20261017,
      liquidGlass: LiquidGlassMode.none,
    );
    final changed = rotateAuroraForDay(
      theme,
      DateTime(2026, 10, 18, 5),
      random: Random(7),
    );
    expect(changed.auroraSeed, isNot(theme.auroraSeed));
    expect(changed.auroraSeed, inInclusiveRange(0, 0x7ffffffe));
    expect(changed.auroraRotationDay, 20261018);
    expect(
      changed.copyWith(
        auroraSeed: theme.auroraSeed,
        auroraRotationDay: theme.auroraRotationDay,
      ),
      theme,
    );
    for (final now in [
      DateTime(2026, 10, 18, 5),
      DateTime(2026, 10, 19, 4, 59),
      DateTime(2026, 10, 17, 23),
    ]) {
      expect(rotateAuroraForDay(changed, now), same(changed));
    }
  });

  test('missed days catch up once while ordinary/None stays unchanged', () {
    const disabled = ThemeProps(
      auroraEnabled: false,
      liquidGlass: LiquidGlassMode.none,
      auroraRotationDay: 20261018,
    );
    final now = DateTime(2027, 3, 12, 10);
    expect(rotateAuroraForDay(disabled, now), same(disabled));
    expect(shuffleAuroraWallpaper(disabled, now: now), same(disabled));
    final enabled = disabled.copyWith(auroraEnabled: true);
    final changed = rotateAuroraForDay(enabled, now, random: Random(9));
    expect(changed.auroraSeed, isNot(enabled.auroraSeed));
    expect(changed.auroraRotationDay, 20270312);
    expect(rotateAuroraForDay(changed, now), same(changed));
  });

  test('manual shuffle counts its local period and never lowers the marker', () {
    const theme = ThemeProps(auroraRotationDay: 20261231);
    final now = DateTime(2027, 1, 1, 4);
    final first = shuffleAuroraWallpaper(theme, now: now, random: Random(3));
    final second = shuffleAuroraWallpaper(first, now: now, random: Random(4));
    expect(first.auroraSeed, isNot(theme.auroraSeed));
    expect(second.auroraSeed, isNot(first.auroraSeed));
    expect(second.auroraRotationDay, 20261231);
    expect(second.auroraColors, theme.auroraColors);
    expect(rotateAuroraForDay(second, now), same(second));
    final next = rotateAuroraForDay(second, DateTime(2027, 1, 1, 5));
    expect(next.auroraRotationDay, 20270101);
    expect(next.auroraSeed, isNot(second.auroraSeed));
    expect(
      shuffleAuroraWallpaper(next, now: DateTime(2026, 12, 30))
          .auroraRotationDay,
      20270101,
    );
  });

  test('seed and day survive config storage; legacy month preserves palette', () async {
    await mockPreferences();
    final changed = rotateAuroraForDay(
      const ThemeProps(auroraRotationDay: 20261017),
      DateTime(2026, 10, 18, 5),
      random: Random(11),
    );
    expect(await preferences.saveConfig(Config(themeProps: changed)), isTrue);
    final restored = (await preferences.getConfig())!.themeProps;
    expect(restored, changed);
    expect(
      rotateAuroraForDay(restored, DateTime(2026, 10, 19, 4)),
      same(restored),
    );
    final legacy = ThemeProps.fromJson({
      'auroraRotationMonth': 202610,
      'auroraSeed': 42,
      'auroraColors': [0xFF65BDD2],
    });
    expect(legacy.auroraSeed, 42);
    expect(legacy.auroraRotationDay, 0);
    expect(legacy.auroraColors, [0xFF65BDD2]);
  });

  testWidgets('foreground 05:00 timer, inactive and resume never shuffle', (
    tester,
  ) async {
    var now = DateTime(2026, 12, 31, 4, 59, 59);
    var theme = const ThemeProps(auroraRotationDay: 20261230);
    final original = theme;
    final delays = <Duration>[];
    var changes = 0;
    final rotation = AuroraDailyRotation(
      now: () => now,
      schedule: (delay, callback) {
        delays.add(delay);
        return Timer(delay, callback);
      },
      onCheck: (date) {
        final next = rotateAuroraForDay(theme, date);
        if (next != theme) changes++;
        theme = next;
      },
    );
    addTearDown(rotation.dispose);
    rotation.setLifecycle(null);
    rotation.setLifecycle(AppLifecycleState.resumed);
    expect(delays.last, const Duration(seconds: 1));
    now = DateTime(2026, 12, 31, 5);
    await tester.pump(const Duration(seconds: 1));
    expect(theme, original);
    expect(delays.last, const Duration(days: 1));
    rotation.setLifecycle(AppLifecycleState.inactive);
    rotation.check();
    rotation.setLifecycle(AppLifecycleState.resumed);
    expect(theme, original);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(theme.auroraRotationDay, 20261231);
    expect(changes, 1);
    rotation.setLifecycle(AppLifecycleState.paused);
    rotation.setLifecycle(AppLifecycleState.paused);
    rotation.check();
    expect(changes, 1);
  });

  testWidgets('live background timer rotates once per 05:00 period', (
    tester,
  ) async {
    var now = DateTime(2026, 10, 18, 4, 59, 59);
    var theme = const ThemeProps(auroraRotationDay: 20261017);
    final original = theme;
    var changes = 0;
    final rotation = AuroraDailyRotation(
      now: () => now,
      onCheck: (date) {
        final next = rotateAuroraForDay(theme, date);
        if (next != theme) changes++;
        theme = next;
      },
    );
    addTearDown(rotation.dispose);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(theme, original);
    now = DateTime(2026, 10, 18, 5);
    await tester.pump(const Duration(seconds: 1));
    expect(changes, 1);
    expect(theme.auroraRotationDay, 20261018);
    now = DateTime(2026, 10, 19, 5);
    await tester.pump(const Duration(days: 1));
    expect(changes, 2);
    expect(theme.auroraRotationDay, 20261019);
  });

  testWidgets('restart catches up on first true background, never on launch', (
    tester,
  ) async {
    var now = DateTime(2027, 1, 3, 10);
    var theme = const ThemeProps(auroraRotationDay: 20261231);
    final original = theme;
    AuroraDailyRotation controller() => AuroraDailyRotation(
      now: () => now,
      onCheck: (date) => theme = rotateAuroraForDay(theme, date),
    );
    var rotation = controller();
    rotation.setLifecycle(AppLifecycleState.resumed);
    expect(theme, original);
    rotation.setLifecycle(AppLifecycleState.inactive);
    expect(theme, original);
    rotation.setLifecycle(AppLifecycleState.paused);
    expect(theme.auroraRotationDay, 20270103);
    final persisted = jsonEncode(theme);
    rotation.dispose();
    theme = ThemeProps.fromJson(jsonDecode(persisted));
    now = DateTime(2027, 1, 4, 4, 59);
    final restored = theme;
    rotation = controller();
    addTearDown(rotation.dispose);
    rotation.setLifecycle(AppLifecycleState.resumed);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(theme, restored);
    now = DateTime(2027, 1, 4, 5);
    rotation.setLifecycle(AppLifecycleState.resumed);
    rotation.check();
    rotation.setLifecycle(AppLifecycleState.inactive);
    expect(theme, restored);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(theme.auroraRotationDay, 20270104);
  });

  testWidgets('fresh and legacy wallpapers wait for true background', (
    tester,
  ) async {
    for (final initial in [const ThemeProps(), ThemeProps.fromJson({})]) {
      var theme = initial;
      final rotation = AuroraDailyRotation(
        now: () => DateTime(2026, 10, 18, 10),
        onCheck: (date) => theme = rotateAuroraForDay(theme, date),
      );
      try {
        rotation.setLifecycle(AppLifecycleState.resumed);
        rotation.setLifecycle(AppLifecycleState.inactive);
        expect(theme, initial);
        rotation.setLifecycle(AppLifecycleState.hidden);
        expect(theme.auroraSeed, isNot(initial.auroraSeed));
        expect(theme.auroraRotationDay, 20261018);
      } finally {
        rotation.dispose();
      }
    }
  });

  testWidgets('enabling wallpaper in front waits for background', (
    tester,
  ) async {
    var theme = const ThemeProps(
      auroraEnabled: false,
      liquidGlass: LiquidGlassMode.none,
      auroraRotationDay: 20261017,
    );
    final rotation = AuroraDailyRotation(
      now: () => DateTime(2026, 10, 19, 8),
      onCheck: (date) => theme = rotateAuroraForDay(theme, date),
    );
    addTearDown(rotation.dispose);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(theme.auroraRotationDay, 20261017);
    rotation.setLifecycle(AppLifecycleState.resumed);
    theme = theme.copyWith(auroraEnabled: true);
    final enabled = theme;
    rotation.check();
    expect(theme, enabled);
    rotation.setLifecycle(AppLifecycleState.inactive);
    expect(theme, enabled);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(theme.auroraRotationDay, 20261019);
    expect(theme.auroraSeed, isNot(enabled.auroraSeed));
    expect(theme.liquidGlass, LiquidGlassMode.none);
  });

  for (final initial in [AppLifecycleState.hidden, AppLifecycleState.paused]) {
    testWidgets('initial $initial waits for a real background transition', (
      tester,
    ) async {
      var theme = const ThemeProps(auroraRotationDay: 20261017);
      final original = theme;
      final rotation = AuroraDailyRotation(
        now: () => DateTime(2026, 10, 19, 8),
        onCheck: (date) => theme = rotateAuroraForDay(theme, date),
      );
      addTearDown(rotation.dispose);
      rotation.initialize(initial);
      rotation.check();
      rotation.setLifecycle(AppLifecycleState.hidden);
      rotation.setLifecycle(AppLifecycleState.paused);
      expect(theme, original);
      rotation.setLifecycle(AppLifecycleState.inactive);
      rotation.setLifecycle(AppLifecycleState.resumed);
      expect(theme, original);
      rotation.setLifecycle(AppLifecycleState.hidden);
      expect(theme.auroraRotationDay, 20261019);
      expect(theme.auroraSeed, isNot(original.auroraSeed));
    });

    testWidgets('initial $initial can rotate when its timer reaches 05:00', (
      tester,
    ) async {
      var now = DateTime(2026, 10, 19, 4, 59, 59);
      var theme = const ThemeProps(auroraRotationDay: 20261017);
      final original = theme;
      final rotation = AuroraDailyRotation(
        now: () => now,
        onCheck: (date) => theme = rotateAuroraForDay(theme, date),
      );
      addTearDown(rotation.dispose);
      rotation.initialize(initial);
      expect(theme, original);
      now = DateTime(2026, 10, 19, 5);
      await tester.pump(const Duration(seconds: 1));
      expect(theme.auroraRotationDay, 20261019);
      expect(theme.auroraSeed, isNot(original.auroraSeed));
    });
  }

  testWidgets('local clock changes rearm, rollback and disposal cannot reroll', (
    tester,
  ) async {
    var now = DateTime(2027, 1, 2, 12);
    var theme = const ThemeProps(auroraRotationDay: 20270102);
    final original = theme;
    final delays = <Duration>[];
    Timer? timer;
    final rotation = AuroraDailyRotation(
      now: () => now,
      schedule: (delay, callback) {
        delays.add(delay);
        return timer = Timer(delay, callback);
      },
      onCheck: (date) => theme = rotateAuroraForDay(theme, date),
    );
    rotation.setLifecycle(AppLifecycleState.resumed);
    expect(delays.last, const Duration(hours: 17));
    now = DateTime(2027, 1, 1, 23);
    rotation.setLifecycle(AppLifecycleState.hidden);
    expect(delays.last, const Duration(hours: 6));
    expect(theme, original);
    now = DateTime(2027, 1, 3, 6);
    rotation.setLifecycle(AppLifecycleState.resumed);
    expect(theme, original);
    rotation.setLifecycle(AppLifecycleState.paused);
    expect(theme.auroraRotationDay, 20270103);
    rotation.setLifecycle(AppLifecycleState.detached);
    expect(timer!.isActive, isFalse);
    rotation.dispose();
    rotation.setLifecycle(AppLifecycleState.paused);
    rotation.check();
    expect(timer!.isActive, isFalse);
  });
}
