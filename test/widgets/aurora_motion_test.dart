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

const _colors = [Color(0xFF65BDD2), Color(0xFFC589CA), Color(0xFF75CCA8)];
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
  test('motion preserves phase at 2.25 times the original tempo', () {
    final motion = AuroraMotion(832838199);
    for (final (time, angle, x, y) in [
      (
        Duration.zero,
        4.739799584346775,
        -3.5765689743074005,
        -18.430129919413606,
      ),
      (
        const Duration(seconds: 2),
        4.860840862640008,
        7.7716651819280065,
        -10.753262087413878,
      ),
      (
        const Duration(seconds: 5),
        5.031947763559606,
        25.100219194938262,
        3.3405496007086675,
      ),
    ]) {
      final pose = motion.at(time, const Size(390, 844));
      expect(pose.angle, closeTo(angle, 1e-10));
      expect(pose.translation.dx, closeTo(x, 1e-10));
      expect(pose.translation.dy, closeTo(y, 1e-10));
      expect(pose.side, closeTo(1065.5201878294001, 1e-9));
    }
  });

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
        expect(shift.distance, lessThan(0.219));
        expect(_angleDistance(current.angle, next.angle), lessThan(0.001677));
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
      expect(
        (before.translation - after.translation).distance,
        lessThan(0.219),
      );
      expect(_angleDistance(before.angle, after.angle), lessThan(0.001677));
      expect(
        AuroraMotion(seed + 1).at(Duration.zero, size),
        isNot(motion.at(Duration.zero, size)),
      );
    }
  });

  test('motion is visible within five seconds and remains bounded', () async {
    final metrics = <String, Object>{};
    const interval = Duration(milliseconds: 100);
    for (final (size, translationLimit) in [
      (const Size(390, 844), 13.605),
      (const Size(720, 720), 25.11),
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
            inInclusiveRange(1.9575, 6.0),
            reason: 'seed=$seed size=$size second=$second degrees/s',
          );
        }
        for (final second in [0, 15, 30, 60]) {
          final current = motion.at(Duration(seconds: second), size);
          final next = motion.at(Duration(seconds: second + 5), size);
          var maximumDrift = 0.0;
          for (var step = 1; step <= 50; step++) {
            final sample = motion.at(
              Duration(milliseconds: second * 1000 + step * 100),
              size,
            );
            maximumDrift = math.max(
              maximumDrift,
              (sample.translation - current.translation).distance,
            );
          }
          expect(
            maximumDrift,
            greaterThan(4.5 * size.shortestSide / 390),
            reason:
                'seed=$seed second=$second must visibly drift in five seconds',
          );
          expect(
            _angleDistance(next.angle, current.angle) * 180 / math.pi,
            greaterThan(9.78),
            reason:
                'seed=$seed second=$second must visibly rotate in five seconds',
          );
        }
      }
      expect(maxTranslation, greaterThan(translationLimit * 0.6));
      expect(maxRotation, greaterThan(4.725));
      metrics['${size.width.toInt()}x${size.height.toInt()}'] = {
        'maxTranslationDpPerSecond': maxTranslation,
        'maxRotationDegreesPerSecond': maxRotation,
        'translationLimitDpPerSecond': translationLimit,
        'rotationMinimumDegreesPerSecond': 1.9575,
        'rotationLimitDegreesPerSecond': 6.0,
        'baseRotationPeriodSeconds': [160 / 2.25, 280 / 2.25],
        'motionSpeedMultiplier': 2.25,
      };
    }
    final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (directory != null && directory.isNotEmpty) {
      await Directory(directory).create(recursive: true);
      await File(
        '$directory/aurora-motion-speed-metrics.json',
      ).writeAsString(const JsonEncoder.withIndent('  ').convert(metrics));
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

  testWidgets('display refresh cadences repaint the background at thirty fps', (
    tester,
  ) async {
    for (final refreshRate in [60, 120]) {
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
      final frames = _countFrames(tester);
      final interval = Duration(
        microseconds: (Duration.microsecondsPerSecond / refreshRate).round(),
      );
      for (var frame = 0; frame < refreshRate; frame++) {
        await tester.pump(interval);
      }
      final paints =
          boundary.debugSymmetricPaintCount +
          boundary.debugAsymmetricPaintCount;
      expect(
        paints,
        inInclusiveRange(14, 15),
        reason: '$refreshRate Hz must retain the accumulated 15 fps cadence',
      );
      expect(
        frames(),
        inInclusiveRange(14, 16),
        reason: '$refreshRate Hz must schedule 15 frames, not one per vsync',
      );
      expect(builds, originalBuilds);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      final disposed = _countFrames(tester);
      await tester.pump(const Duration(seconds: 1));
      expect(disposed(), 0);
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.binding.transientCallbackCount, 0);
      expect(tester.takeException(), isNull);
    }
  });

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
      final frames = _countFrames(tester);
      await tester.pump(const Duration(minutes: 5));
      expect(frames(), 0);
      expect(await _pixels(tester, key), paused);
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.binding.transientCallbackCount, 0);
    }
    await tester.pumpWidget(_scene(key));
    await tester.pump();
    final resumed = await _pixels(tester, key);
    await tester.pump(const Duration(seconds: 30));
    expect(await _pixels(tester, key), isNot(resumed));
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.detached,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
      await tester.pump();
      final background = await _pixels(tester, key);
      final frames = _countFrames(tester);
      await tester.pump(const Duration(minutes: 5));
      expect(frames(), 0);
      expect(await _pixels(tester, key), background);
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.binding.transientCallbackCount, 0);
    }
    final background = await _pixels(tester, key);
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
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final key = GlobalKey();
    final metrics = <String, Object>{};
    final failures = <String>[];
    for (final brightness in Brightness.values) {
      for (final seed in _seeds) {
        Uint8List? previous;
        Uint8List? first;
        for (final seconds in [0, 3, 5]) {
          await tester.pumpWidget(
            _scene(
              key,
              seed: seed,
              brightness: brightness,
              size: const Size(390, 844),
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
          final difference = first == null
              ? 0.0
              : _pixelDifference(first, pixels);
          if (first != null) {
            final minimumDifference = seconds == 3 ? 0.25 : 0.5;
            if (difference <= minimumDifference) {
              failures.add(
                '$name mean RGB difference $difference must exceed '
                '$minimumDifference within $seconds seconds',
              );
            }
          }
          first ??= pixels;
          if (previous != null) expect(pixels, isNot(previous));
          previous = pixels;
          metrics[name] = {
            'seed': seed,
            'seconds': seconds,
            'minimumLuminance': minimumLuminance,
            'maximumValue': maximumValue,
            'meanRgbDifferenceFromZero': difference,
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
      await tester.runAsync(
        () => File('$directory/aurora-motion-validation.json').writeAsString(
          const JsonEncoder.withIndent('  ').convert({
            'renderedFrames': metrics.length,
            'minimumMeanRgbDifference': {'3': 0.25, '5': 0.5},
            'failedChecks': failures,
          }),
        ),
      );
    }
    expect(metrics, hasLength(60));
    expect(failures, isEmpty, reason: failures.join('\n'));
  });
}

double _angleDistance(double a, double b) =>
    math.atan2(math.sin(a - b), math.cos(a - b)).abs();

double _pixelDifference(Uint8List first, Uint8List next) {
  var difference = 0;
  var count = 0;
  for (var at = 0; at < first.length; at += 64) {
    for (var channel = 0; channel < 3; channel++) {
      difference += (first[at + channel] - next[at + channel]).abs();
      count++;
    }
  }
  return difference / count;
}

int Function() _countFrames(WidgetTester tester) {
  var frames = 0;
  var counting = true;
  void count(Duration _) {
    if (!counting) return;
    frames++;
    tester.binding.addPostFrameCallback(count);
  }

  tester.binding.addPostFrameCallback(count);
  return () {
    counting = false;
    return frames;
  };
}

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
