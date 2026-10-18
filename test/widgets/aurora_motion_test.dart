import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/aurora_motion.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _colors = [Color(0xFF6BD5D9), Color(0xFFC6ADE4), Color(0xFFA9DAC6)];
const _seeds = [
  832838199,
  2379623510,
  624302977,
  2671861087,
  3374210312,
  1374901759,
  3079535385,
  3413751917,
  3209466732,
  3919277682,
];

void main() {
  test('square overscan covers every viewport corner throughout motion', () {
    for (final size in [
      const Size(390, 844),
      const Size(844, 390),
      const Size(720, 720),
      const Size(1200, 900),
    ]) {
      for (final seed in _seeds) {
        final motion = AuroraMotion(seed);
        for (var second = 0; second <= 7200; second += 37) {
          final pose = motion.at(Duration(seconds: second), size);
          final cosine = math.cos(pose.angle);
          final sine = math.sin(pose.angle);
          for (final corner in [
            Offset.zero,
            Offset(size.width, 0),
            Offset(0, size.height),
            Offset(size.width, size.height),
          ]) {
            final point = corner - size.center(Offset.zero) - pose.translation;
            expect(
              (point.dx * cosine + point.dy * sine).abs(),
              lessThan(pose.side / 2),
            );
            expect(
              (-point.dx * sine + point.dy * cosine).abs(),
              lessThan(pose.side / 2),
            );
          }
        }
      }
    }
  });

  test('seeded motion varies speed and travels in both axes without jumps', () {
    const size = Size(390, 844);
    for (final seed in _seeds) {
      final motion = AuroraMotion(seed);
      final repeated = AuroraMotion(seed);
      final xDirections = <bool>{};
      final yDirections = <bool>{};
      final speeds = <double>[];
      for (var second = 0; second < 3600; second += 15) {
        final time = Duration(seconds: second);
        final current = motion.at(time, size);
        expect(repeated.at(time, size), current);
        final next = motion.at(time + const Duration(milliseconds: 16), size);
        final shift = next.translation - current.translation;
        xDirections.add(shift.dx > 0);
        yDirections.add(shift.dy > 0);
        speeds.add(shift.distance / 0.016);
        expect(shift.distance, lessThan(0.04));
        expect(_angleDistance(current.angle, next.angle), lessThan(0.0001));
      }
      expect(xDirections, hasLength(2));
      expect(yDirections, hasLength(2));
      expect(speeds.reduce(math.max), greaterThan(speeds.reduce(math.min) * 2));
      final longTime = const Duration(days: 100000);
      final before = motion.at(longTime, size);
      final after = motion.at(
        longTime + const Duration(milliseconds: 16),
        size,
      );
      expect((before.translation - after.translation).distance, lessThan(0.04));
      expect(_angleDistance(before.angle, after.angle), lessThan(0.0001));
      expect(
        AuroraMotion(seed + 1).at(Duration.zero, size),
        isNot(motion.at(Duration.zero, size)),
      );
    }
  });

  test('motion stays slow in logical pixels and degrees per second', () async {
    final metrics = <String, Object>{};
    const interval = Duration(milliseconds: 100);
    for (final (size, translationLimit) in [
      (const Size(390, 844), 1.21),
      (const Size(720, 720), 2.24),
    ]) {
      var maxTranslation = 0.0;
      var maxRotation = 0.0;
      for (final seed in _seeds) {
        final motion = AuroraMotion(seed);
        for (var second = 0; second <= 7200; second += 3) {
          final time = Duration(seconds: second);
          final current = motion.at(time, size);
          final next = motion.at(time + interval, size);
          final translation =
              (next.translation - current.translation).distance / 0.1;
          final rotation =
              _angleDistance(next.angle, current.angle) * 180 / math.pi / 0.1;
          maxTranslation = math.max(maxTranslation, translation);
          maxRotation = math.max(maxRotation, rotation);
          expect(
            translation,
            lessThanOrEqualTo(translationLimit),
            reason: 'seed=$seed size=$size second=$second dp/s',
          );
          expect(
            rotation,
            lessThanOrEqualTo(0.234),
            reason: 'seed=$seed size=$size second=$second degrees/s',
          );
        }
      }
      expect(maxTranslation, greaterThan(0));
      expect(maxRotation, greaterThan(0));
      metrics['${size.width.toInt()}x${size.height.toInt()}'] = {
        'maxTranslationDpPerSecond': maxTranslation,
        'maxRotationDegreesPerSecond': maxRotation,
        'translationLimitDpPerSecond': translationLimit,
        'rotationLimitDegreesPerSecond': 0.234,
      };
    }
    final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (directory != null && directory.isNotEmpty) {
      await Directory(directory).create(recursive: true);
      await File('$directory/aurora-motion-speed-metrics.json').writeAsString(
        const JsonEncoder.withIndent('  ').convert(metrics),
      );
    }
  });

  testWidgets(
    'motion only repaints and reuses its mesh across time and resize',
    (tester) async {
      final key = GlobalKey();
      var builds = 0;
      await tester.pumpWidget(_scene(key, foregroundBuild: () => builds++));
      await tester.pump();
      final first = await _pixels(tester, key);
      final painter = _painter(tester);
      final originalBuilds = builds;
      await tester.pump(const Duration(seconds: 90));
      expect(await _pixels(tester, key), isNot(first));
      expect(_painter(tester), same(painter));
      expect(builds, originalBuilds);
      await tester.pumpWidget(_scene(key, size: const Size(300, 180)));
      expect(_painter(tester), same(painter));
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'high refresh pumps repaint the background at most thirty times',
    (tester) async {
      final key = GlobalKey();
      var builds = 0;
      await tester.pumpWidget(_scene(key, foregroundBuild: () => builds++));
      await tester.pump();
      final boundary = tester.renderObject<RenderRepaintBoundary>(
        find.descendant(
          of: find.byType(SeededAuroraWallpaper),
          matching: find.byType(RepaintBoundary),
        ),
      );
      boundary.debugResetMetrics();
      final originalBuilds = builds;
      for (var frame = 0; frame < 125; frame++) {
        await tester.pump(const Duration(milliseconds: 8));
      }
      final paints =
          boundary.debugSymmetricPaintCount +
          boundary.debugAsymmetricPaintCount;
      expect(paints, inInclusiveRange(20, 30));
      expect(builds, originalBuilds);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('reduced motion, ticker mode, and background pause active time', (
    tester,
  ) async {
    final key = GlobalKey();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    addTearDown(() {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    });
    await tester.pumpWidget(_scene(key));
    await tester.pump();
    await tester.pump(const Duration(seconds: 30));
    for (final (reduced, ticker) in [(true, true), (false, false)]) {
      await tester.pumpWidget(_scene(key, reduced: reduced, ticker: ticker));
      final paused = await _pixels(tester, key);
      await tester.pump(const Duration(minutes: 5));
      expect(await _pixels(tester, key), paused);
      expect(tester.binding.hasScheduledFrame, isFalse);
    }
    await tester.pumpWidget(_scene(key));
    await tester.pump();
    final resumed = await _pixels(tester, key);
    await tester.pump(const Duration(seconds: 30));
    expect(await _pixels(tester, key), isNot(resumed));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    final background = await _pixels(tester, key);
    await tester.pump(const Duration(minutes: 5));
    expect(await _pixels(tester, key), background);
    expect(tester.binding.hasScheduledFrame, isFalse);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(await _pixels(tester, key), background);
    await tester.pump(const Duration(seconds: 30));
    expect(await _pixels(tester, key), isNot(background));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('fixed-time snapshots are deterministic and schedule no ticker', (
    tester,
  ) async {
    final key = GlobalKey();
    await tester.pumpWidget(_scene(key, time: Duration.zero));
    final first = await _pixels(tester, key);
    final painter = _painter(tester);
    await tester.pumpWidget(_scene(key, time: const Duration(seconds: 180)));
    expect(await _pixels(tester, key), isNot(first));
    expect(_painter(tester), same(painter));
    await tester.pumpWidget(_scene(key, time: Duration.zero));
    expect(await _pixels(tester, key), first);
    await tester.pump(const Duration(days: 1));
    expect(await _pixels(tester, key), first);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('ten seeds render covered square-motion frames in both themes', (
    tester,
  ) async {
    final key = GlobalKey();
    final metrics = <String, Object>{};
    for (final brightness in Brightness.values) {
      for (final seed in _seeds) {
        Uint8List? previous;
        for (final seconds in [0, 60, 180]) {
          await tester.pumpWidget(
            _scene(
              key,
              seed: seed,
              brightness: brightness,
              time: Duration(seconds: seconds),
            ),
          );
          final name = 'aurora-motion-${brightness.name}-$seed-$seconds';
          final pixels = await _pixels(tester, key, name: name);
          var minimumLuminance = 1.0;
          var maximumValue = 0.0;
          for (var at = 0; at < pixels.length; at += 4) {
            expect(pixels[at + 3], 255);
            if (at % 64 != 0) continue;
            final color = Color.fromARGB(
              255,
              pixels[at],
              pixels[at + 1],
              pixels[at + 2],
            );
            minimumLuminance = math.min(
              minimumLuminance,
              color.computeLuminance(),
            );
            maximumValue = math.max(
              maximumValue,
              HSVColor.fromColor(color).value,
            );
          }
          if (brightness == Brightness.light) {
            expect(minimumLuminance, greaterThan(0.73));
          } else {
            expect(maximumValue, lessThan(0.51));
          }
          if (previous != null) expect(pixels, isNot(previous));
          previous = pixels;
          metrics[name] = {
            'seed': seed,
            'seconds': seconds,
            'minimumLuminance': minimumLuminance,
            'maximumValue': maximumValue,
          };
          expect(tester.binding.hasScheduledFrame, isFalse);
          expect(tester.takeException(), isNull);
        }
      }
    }
    final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (directory != null) {
      await tester.runAsync(
        () => File(
          '$directory/aurora-motion-metrics.json',
        ).writeAsString(const JsonEncoder.withIndent('  ').convert(metrics)),
      );
    }
    expect(metrics, hasLength(60));
  });
}

double _angleDistance(double a, double b) =>
    math.atan2(math.sin(a - b), math.cos(a - b)).abs();

CustomPainter? _painter(WidgetTester tester) => tester
    .widget<CustomPaint>(
      find.descendant(
        of: find.byType(SeededAuroraWallpaper),
        matching: find.byType(CustomPaint),
      ),
    )
    .painter;

Widget _scene(
  GlobalKey key, {
  Size size = const Size(180, 300),
  bool reduced = false,
  bool ticker = true,
  int seed = 271828,
  Brightness brightness = Brightness.light,
  Duration? time,
  VoidCallback? foregroundBuild,
}) => MediaQuery(
  data: MediaQueryData(disableAnimations: reduced),
  child: Directionality(
    textDirection: TextDirection.ltr,
    child: TickerMode(
      enabled: ticker,
      child: Center(
        child: Stack(
          children: [
            RepaintBoundary(
              key: key,
              child: SizedBox.fromSize(
                size: size,
                child: SeededAuroraWallpaper(
                  colors: _colors,
                  seed: seed,
                  brightness: brightness,
                  motion: true,
                  motionTime: time,
                ),
              ),
            ),
            Builder(
              builder: (_) {
                foregroundBuild?.call();
                return const SizedBox.shrink();
              },
            ),
          ],
        ),
      ),
    ),
  ),
);

Future<Uint8List> _pixels(
  WidgetTester tester,
  GlobalKey key, {
  String? name,
}) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData())!;
      final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (name != null && directory != null) {
        await Directory(directory).create(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        await File('$directory/$name.png').writeAsBytes(
          png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
        );
      }
      return data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
    } finally {
      image.dispose();
    }
  }))!;
}
