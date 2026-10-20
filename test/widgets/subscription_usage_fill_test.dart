import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/subscription_usage_fill.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _scene = ValueKey('usageScene');
const _label = ValueKey('usageLabel');
const _info = SubscriptionInfo(download: 84, total: 100);
const _usedUp = SubscriptionInfo(download: 100, total: 100);
const _unused = SubscriptionInfo(total: 100);

Color _pixel(Uint8List bytes, int x, int y, {int scale = 1}) {
  final offset = (y * scale * 400 * scale + x * scale) * 4;
  return Color.fromARGB(
    bytes[offset + 3],
    bytes[offset],
    bytes[offset + 1],
    bytes[offset + 2],
  );
}

Color _meanPixel(Uint8List bytes, int x, int y, {required int scale}) {
  final half = scale * 3 ~/ 2;
  final channels = List<double>.filled(4, 0);
  var count = 0;
  for (var py = y * scale - half; py <= y * scale + half; py++) {
    for (var px = x * scale - half; px <= x * scale + half; px++) {
      final offset = (py * 400 * scale + px) * 4;
      for (var channel = 0; channel < channels.length; channel++) {
        channels[channel] += bytes[offset + channel];
      }
      count++;
    }
  }
  return Color.from(
    alpha: channels[3] / (255 * count),
    red: channels[0] / (255 * count),
    green: channels[1] / (255 * count),
    blue: channels[2] / (255 * count),
  );
}

double _distance(Color a, Color b) =>
    (a.r - b.r).abs() + (a.g - b.g).abs() + (a.b - b.b).abs();

double _contrast(Color a, Color b) {
  final first = a.computeLuminance();
  final second = b.computeLuminance();
  return first > second
      ? (first + 0.05) / (second + 0.05)
      : (second + 0.05) / (first + 0.05);
}

void _expectFallbackFill(
  Uint8List filled,
  Uint8List empty, {
  required Color color,
  required Brightness brightness,
  int scale = 1,
}) {
  final behind = _meanPixel(empty, 45, 80, scale: scale);
  final actual = _meanPixel(filled, 45, 80, scale: scale);
  final opacity = brightness == Brightness.dark ? 0.25 : 0.17;
  final expected = Color.alphaBlend(color.withValues(alpha: opacity), behind);
  expect(_distance(actual, expected), lessThan(0.04));
}

void _expectDryRim(Uint8List actual, Uint8List empty, {int scale = 1}) {
  for (var y = 28; y < 132; y++) {
    for (var x = 20; x < 380; x++) {
      if (x < 25 || x >= 375 || y < 33 || y >= 127) {
        expect(
          _pixel(actual, x, y, scale: scale),
          _pixel(empty, x, y, scale: scale),
          reason: 'The glass tube wall at ($x, $y) must remain dry',
        );
      }
    }
  }
}

Future<Uint8List> _snapshot(
  WidgetTester tester,
  String name, {
  Key boundaryKey = _scene,
  double pixelRatio = 1,
}) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(boundaryKey),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: pixelRatio);
    try {
      final rgba = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        final directory = Directory(output)..createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '${directory.path}/subscription-usage-$name.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
      }
      return rgba.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

Future<void> _pump(
  WidgetTester tester, {
  required Brightness brightness,
  required LiquidGlassMode mode,
  SubscriptionInfo? info = _info,
  Duration? time = Duration.zero,
  bool text = false,
  bool reduced = false,
  TextDirection direction = TextDirection.ltr,
  Size cardSize = const Size(360, 104),
  Color seedColor = Colors.blue,
  List<Color>? backdrop,
  bool highContrast = false,
}) async {
  final colors = ColorScheme.fromSeed(
    seedColor: seedColor,
    brightness: brightness,
  );
  await tester.pumpWidget(
    TestApp(
      theme: ThemeData(colorScheme: colors, fontFamily: 'UsagePreview'),
      child: Directionality(
        textDirection: direction,
        child: MediaQuery(
          data: MediaQueryData.fromView(
            tester.view,
          ).copyWith(disableAnimations: reduced, highContrast: highContrast),
          child: RepaintBoundary(
            key: _scene,
            child: LiquidGlassScope(
              enabled: mode != LiquidGlassMode.none,
              quality: mode == LiquidGlassMode.ultra
                  ? LiquidGlassQuality.ultra
                  : LiquidGlassQuality.standard,
              child: LiquidGlassHost(
                enabled: mode != LiquidGlassMode.none,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Positioned.fill(
                      child: LiquidGlassColorField(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              colors:
                                  backdrop ??
                                  (brightness == Brightness.light
                                      ? const [
                                          Color(0xFFEABEA7),
                                          Color(0xFF9DCDDF),
                                        ]
                                      : const [
                                          Color(0xFF11252D),
                                          Color(0xFF493026),
                                        ]),
                            ),
                          ),
                        ),
                      ),
                    ),
                    Positioned(
                      left: 20,
                      top: 28,
                      width: cardSize.width,
                      height: cardSize.height,
                      child: SubscriptionUsageFill(
                        info: info,
                        motionTime: time,
                        child: SizedBox.expand(
                          child: text
                              ? Padding(
                                  padding: const EdgeInsets.all(16),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Builder(
                                        builder: (context) => GlassText(
                                          'Scientific',
                                          key: _label,
                                          style: AppTextStyle.headline.of(
                                            context,
                                          ),
                                        ),
                                      ),
                                      const SizedBox(height: 6),
                                      Builder(
                                        builder: (context) => GlassText(
                                          '1.3 TB / 8 TB · ${SubscriptionUsageFill.percentageOf(context, info)} ${context.appLocalizations.usedShort}',
                                          style: AppTextStyle.footnote.of(
                                            context,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                )
                              : const SizedBox.shrink(),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  test('the meniscus has flat center, smooth contacts and conserved fill', () {
    const size = Size(350, 94);
    for (final share in [0.001, 0.16, 0.5, 1.0]) {
      for (final time in [Duration.zero, const Duration(seconds: 3)]) {
        for (final rtl in [false, true]) {
          final surface = subscriptionUsageMeniscus(
            size: size,
            share: share,
            time: time,
            rtl: rtl,
          );
          final metric = surface.path.computeMetrics().single;
          final points = <ui.Tangent>[];
          for (var i = 0; i <= 2000; i++) {
            points.add(metric.getTangentForOffset(metric.length * i / 2000)!);
          }
          final first = points.first;
          final last = points.last;
          expect(first.position.dy, closeTo(0, 0.001));
          expect(last.position.dy, closeTo(size.height, 0.001));
          if (share < 1) {
            expect(first.vector.dy.abs(), lessThan(0.001));
            expect(last.vector.dy.abs(), lessThan(0.001));
          }
          var area = 0.0;
          var centerTurn = 0.0;
          var edgeTurn = 0.0;
          for (var i = 0; i < points.length; i++) {
            final point = points[i].position;
            final actualX = rtl
                ? size.width - surface.width + point.dx
                : point.dx;
            expect(actualX, inInclusiveRange(0, size.width));
            expect(point.dy, inInclusiveRange(0, size.height));
            if (i == 0) continue;
            final previous = points[i - 1];
            final turn = (points[i].vector - previous.vector).distance;
            if ((point.dy / size.height - 0.22).abs() < 0.005 ||
                (point.dy / size.height - 0.78).abs() < 0.005) {
              expect(points[i].vector.dx.abs(), lessThan(0.01));
            }
            if (point.dy > size.height * 0.3 && point.dy < size.height * 0.7) {
              centerTurn += turn;
              expect(points[i].vector.dx.abs(), lessThan(0.001));
            } else if (point.dy < size.height * 0.22 ||
                point.dy > size.height * 0.78) {
              edgeTurn += turn;
            }
            double filledX(Offset position) =>
                rtl ? surface.width - position.dx : position.dx;
            area +=
                (filledX(point) + filledX(previous.position)) *
                (point.dy - previous.position.dy) /
                2;
          }
          if (share < 1) expect(centerTurn, lessThan(edgeTurn));
          expect(area / (size.width * size.height), closeTo(share, 0.0001));
        }
      }
    }
  });

  setUpAll(() async {
    final font = FontLoader('UsagePreview')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  setUp(() => LiquidGlassHost.debugLastCapture = null);

  for (final (brightness, mode) in [
    for (final brightness in Brightness.values)
      for (final mode in LiquidGlassMode.values) (brightness, mode),
  ]) {
    testWidgets(
      '${brightness.name} fill stays restrained and follows its backdrop '
      '(${mode.name})',
      (tester) async {
        tester.view.physicalSize = const Size(1200, 480);
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        final backdrop = brightness == Brightness.light
            ? const [Color(0xFFD9EADF), Color(0xFFEFE6EC)]
            : const [Color(0xFF1C3142), Color(0xFF493426)];
        final changedBackdrop = brightness == Brightness.light
            ? const [Color(0xFFB8D8A8), Color(0xFFD4B8D2)]
            : const [Color(0xFF315840), Color(0xFF50345A)];
        try {
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            backdrop: backdrop,
            info: _usedUp,
          );
          final empty = await _snapshot(
            tester,
            '${brightness.name}-${mode.name}-3x-empty',
            pixelRatio: 3,
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            backdrop: backdrop,
          );
          final filled = await _snapshot(
            tester,
            '${brightness.name}-${mode.name}-3x-16',
            pixelRatio: 3,
          );
          expect(filled.length, 1200 * 480 * 4);
          // A 3dp patch averages backdrop dithering for a stable fill sample.
          final behind = _meanPixel(empty, 45, 80, scale: 3);
          final fill = _meanPixel(filled, 45, 80, scale: 3);
          if (mode == LiquidGlassMode.none) {
            final scheme = Theme.of(
              tester.element(find.byType(SubscriptionUsageFill)),
            ).colorScheme;
            _expectFallbackFill(
              filled,
              empty,
              color: scheme.primary,
              brightness: brightness,
              scale: 3,
            );
          } else {
            expect(_distance(fill, behind), inExclusiveRange(0.015, 0.15));
          }
          if (mode != LiquidGlassMode.none) {
            _expectDryRim(filled, empty, scale: 3);
          }
          expect(
            _pixel(filled, 200, 80, scale: 3),
            _pixel(empty, 200, 80, scale: 3),
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            backdrop: backdrop,
            text: true,
          );
          final readable = await _snapshot(
            tester,
            '${brightness.name}-${mode.name}-3x-readable',
            pixelRatio: 3,
          );
          final bounds = tester.getRect(find.byKey(_label));
          final contrasts = <double>[];
          for (var y = bounds.top.ceil(); y < bounds.bottom.floor(); y++) {
            for (var x = bounds.left.ceil(); x < bounds.right.floor(); x++) {
              final ink = _pixel(readable, x, y, scale: 3);
              final background = _pixel(filled, x, y, scale: 3);
              if (_distance(ink, background) > 0.3) {
                contrasts.add(_contrast(ink, background));
              }
            }
          }
          contrasts.sort();
          expect(contrasts.length, greaterThan(20));
          expect(
            contrasts[(contrasts.length * 0.9).floor()],
            greaterThanOrEqualTo(4.5),
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            backdrop: changedBackdrop,
            info: _usedUp,
          );
          final changedEmpty = await _snapshot(
            tester,
            '${brightness.name}-${mode.name}-3x-changed-empty',
            pixelRatio: 3,
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            backdrop: changedBackdrop,
          );
          final changedFill = await _snapshot(
            tester,
            '${brightness.name}-${mode.name}-3x-changed-fill',
            pixelRatio: 3,
          );
          final changedBehind = _meanPixel(changedEmpty, 45, 80, scale: 3);
          final changedFillPixel = _meanPixel(changedFill, 45, 80, scale: 3);
          if (mode == LiquidGlassMode.none) {
            final scheme = Theme.of(
              tester.element(find.byType(SubscriptionUsageFill)),
            ).colorScheme;
            _expectFallbackFill(
              changedFill,
              changedEmpty,
              color: scheme.primary,
              brightness: brightness,
              scale: 3,
            );
          } else {
            expect(
              _distance(changedFillPixel, changedBehind),
              inExclusiveRange(0.015, 0.15),
            );
          }
          expect(_distance(changedFillPixel, fill), greaterThan(0.04));
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump(Duration.zero);
        }
      },
      skip:
          mode != LiquidGlassMode.none &&
          !ui.ImageFilter.isShaderFilterSupported,
    );
  }

  for (final brightness in Brightness.values) {
    for (final mode in LiquidGlassMode.values) {
      testWidgets('16 percent remaining fills the quota meniscus '
          '(${brightness.name}, ${mode.name})', (tester) async {
        tester.view.physicalSize = const Size(400, 160);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final name = '${brightness.name}-${mode.name}';
        try {
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            info: _usedUp,
          );
          final empty = await _snapshot(tester, '$name-empty');
          await _pump(tester, brightness: brightness, mode: mode);
          final filled = await _snapshot(tester, '$name-16');
          final colors = Theme.of(
            tester.element(find.byType(SubscriptionUsageFill)),
          ).colorScheme;
          var changed = 0;
          final glass = mode != LiquidGlassMode.none;
          final cavityLeft = glass ? 25 : 20;
          final cavityRight = glass ? 375 : 380;
          for (var x = cavityLeft; x < cavityRight; x++) {
            if (_distance(_pixel(empty, x, 108), _pixel(filled, x, 108)) >
                0.025)
              changed++;
          }
          expect(
            changed / (cavityRight - cavityLeft),
            inInclusiveRange(0.13, 0.18),
          );
          if (glass) _expectDryRim(filled, empty);
          expect(_pixel(filled, 21, 29), _pixel(empty, 21, 29));
          expect(_pixel(filled, 350, 108), _pixel(empty, 350, 108));
          int edgeAt(int y) {
            var edge = 20;
            for (var x = 20; x < 110; x++) {
              if (_distance(_pixel(empty, x, y), _pixel(filled, x, y)) > 0.025)
                edge = x;
            }
            return edge;
          }

          expect(
            edgeAt(36),
            mode == LiquidGlassMode.none ? edgeAt(80) : greaterThan(edgeAt(80)),
          );
          if (mode != LiquidGlassMode.none) {
            expect(LiquidGlassHost.debugLastCapture!.panes, isNotEmpty);
          }

          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            time: const Duration(seconds: 3),
          );
          final moved = await _snapshot(tester, '$name-moving');
          var edgeChanges = 0;
          for (var y = 32; y < 128; y++) {
            for (var x = 70; x < 88; x++) {
              if (_distance(_pixel(filled, x, y), _pixel(moved, x, y)) > 0.01)
                edgeChanges++;
            }
          }
          expect(
            edgeChanges,
            mode == LiquidGlassMode.none ? 0 : greaterThan(0),
          );

          await _pump(tester, brightness: brightness, mode: mode, text: true);
          final readable = await _snapshot(tester, '$name-readable');
          final bounds = tester.getRect(find.byKey(_label));
          final contrasts = <double>[];
          for (var y = bounds.top.ceil(); y < bounds.bottom.floor(); y++) {
            for (var x = bounds.left.ceil(); x < bounds.right.floor(); x++) {
              final ink = _pixel(readable, x, y);
              final behind = _pixel(filled, x, y);
              if (_distance(ink, behind) > 0.3)
                contrasts.add(_contrast(ink, behind));
            }
          }
          contrasts.sort();
          expect(contrasts.length, greaterThan(20));
          expect(
            contrasts[(contrasts.length * 0.9).floor()],
            greaterThanOrEqualTo(4.5),
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            info: const SubscriptionInfo(download: 50, total: 100),
          );
          final half = await _snapshot(tester, '$name-50');
          var halfChanged = 0;
          for (var x = cavityLeft; x < cavityRight; x++) {
            if (_distance(_pixel(empty, x, 80), _pixel(half, x, 80)) > 0.025) {
              halfChanged++;
            }
          }
          expect(
            halfChanged / (cavityRight - cavityLeft),
            inInclusiveRange(0.47, 0.52),
          );
          if (glass) {
            _expectDryRim(half, empty);
          }
          for (final (info, label, color, glassAlpha) in [
            (
              const SubscriptionInfo(download: 92, total: 100),
              'warning',
              colors.warning,
              0.16,
            ),
          ]) {
            await _pump(tester, brightness: brightness, mode: mode, info: info);
            final semanticFill = await _snapshot(tester, '$name-$label');
            final delta = _distance(
              _pixel(semanticFill, 40, 80),
              _pixel(filled, 40, 80),
            );
            expect(
              delta,
              greaterThan(0.025),
              reason: '$label keeps its status tint',
            );
            final opacity = mode == LiquidGlassMode.none
                ? brightness == Brightness.dark
                      ? 0.25
                      : 0.17
                : glassAlpha;
            expect(
              _distance(
                _pixel(semanticFill, 40, 80),
                Color.alphaBlend(
                  color.withValues(alpha: opacity),
                  _pixel(empty, 40, 80),
                ),
              ),
              lessThan(0.12),
              reason: '$label uses its semantic color',
            );
          }
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            info: const SubscriptionInfo(download: 125, total: 100),
          );
          expect(
            await _snapshot(tester, '$name-overrun'),
            orderedEquals(empty),
            reason: 'an overrun quota leaves no water in the tube',
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            info: _unused,
          );
          final full = await _snapshot(tester, '$name-full');
          if (glass) {
            _expectDryRim(full, empty);
            for (final point in const [
              Offset(30, 80),
              Offset(370, 80),
              Offset(200, 38),
              Offset(200, 122),
            ]) {
              expect(
                _distance(
                  _pixel(full, point.dx.toInt(), point.dy.toInt()),
                  _pixel(empty, point.dx.toInt(), point.dy.toInt()),
                ),
                greaterThan(0.025),
              );
            }
          }
          expect(_pixel(full, 21, 29), _pixel(empty, 21, 29));
          expect(
            _distance(_pixel(full, 350, 108), _pixel(empty, 350, 108)),
            greaterThan(0.025),
          );
          if (glass) {
            await _pump(
              tester,
              brightness: brightness,
              mode: mode,
              direction: TextDirection.rtl,
            );
            final rtl = await _snapshot(tester, '$name-rtl');
            _expectDryRim(rtl, empty);
            expect(
              _distance(_pixel(rtl, 350, 80), _pixel(empty, 350, 80)),
              greaterThan(0.025),
            );
            expect(_pixel(rtl, 40, 80), _pixel(empty, 40, 80));
          }
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump(Duration.zero);
        }
      });
    }
  }

  for (final brightness in Brightness.values) {
    for (final mode in [LiquidGlassMode.standard, LiquidGlassMode.ultra]) {
      testWidgets('high contrast keeps the translucent quota fallback '
          '(${brightness.name}, ${mode.name})', (tester) async {
        tester.view.physicalSize = const Size(400, 160);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        try {
          await _pump(
            tester,
            brightness: brightness,
            mode: mode,
            highContrast: true,
          );
          final highContrast = await _snapshot(
            tester,
            'high-contrast-${brightness.name}-${mode.name}',
          );
          await _pump(
            tester,
            brightness: brightness,
            mode: LiquidGlassMode.none,
          );
          final fallback = await _snapshot(
            tester,
            'fallback-${brightness.name}-${mode.name}',
          );
          expect(highContrast, orderedEquals(fallback));
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump(Duration.zero);
        }
      });
    }
  }

  testWidgets('tiny glass cavities stay finite and inside their tube', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 160);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    try {
      for (final size in const [Size(1, 1), Size(4, 3), Size(10, 8)]) {
        await _pump(
          tester,
          brightness: Brightness.dark,
          mode: LiquidGlassMode.ultra,
          cardSize: size,
          info: _usedUp,
        );
        final empty = await _snapshot(tester, 'tiny-${size.width}-empty');
        await _pump(
          tester,
          brightness: Brightness.dark,
          mode: LiquidGlassMode.ultra,
          cardSize: size,
          info: _unused,
        );
        final full = await _snapshot(tester, 'tiny-${size.width}-full');
        for (var y = 27; y < 29 + size.height.ceil(); y++) {
          for (var x = 19; x < 21 + size.width.ceil(); x++) {
            if (x < 20 ||
                y < 28 ||
                x >= 20 + size.width ||
                y >= 28 + size.height) {
              expect(_pixel(full, x, y), _pixel(empty, x, y));
            }
          }
        }
        expect(tester.takeException(), isNull);
        for (final capture in LiquidGlassHost.debugLastCapture!.panes) {
          expect(capture.pane.isFinite, isTrue);
          expect(capture.pane.width, greaterThan(0));
          expect(capture.pane.height, greaterThan(0));
        }
      }
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(Duration.zero);
    }
  });

  testWidgets('invalid totals do not invent progress, and overruns keep their '
      'percentage over an empty tube', (tester) async {
    tester.view.physicalSize = const Size(400, 160);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    try {
      for (final info in <SubscriptionInfo?>[
        null,
        const SubscriptionInfo(download: 16),
        const SubscriptionInfo(download: 16, total: -1),
        const SubscriptionInfo(upload: -1, total: 100),
      ]) {
        await _pump(
          tester,
          brightness: Brightness.light,
          mode: LiquidGlassMode.ultra,
          info: info,
        );
        expect(
          SubscriptionUsageFill.percentageOf(
            tester.element(find.byType(SubscriptionUsageFill)),
            info,
          ),
          isNull,
        );
        expect(LiquidGlassHost.debugLastCapture?.panes ?? [], isEmpty);
      }
      await _pump(
        tester,
        brightness: Brightness.light,
        mode: LiquidGlassMode.none,
        info: _usedUp,
      );
      final empty = await _snapshot(tester, 'used-up');
      expect(
        SubscriptionUsageFill.percentageOf(
          tester.element(find.byType(SubscriptionUsageFill)),
          _usedUp,
        ),
        '100%',
      );
      await _pump(
        tester,
        brightness: Brightness.light,
        mode: LiquidGlassMode.none,
        info: _unused,
      );
      final full = await _snapshot(tester, 'unused');
      expect(
        SubscriptionUsageFill.percentageOf(
          tester.element(find.byType(SubscriptionUsageFill)),
          _unused,
        ),
        '0%',
      );
      expect(_pixel(full, 21, 29), _pixel(empty, 21, 29));
      expect(
        _distance(_pixel(full, 40, 80), _pixel(empty, 40, 80)),
        greaterThan(0.02),
      );
      expect(
        _distance(_pixel(full, 350, 80), _pixel(empty, 350, 80)),
        greaterThan(0.02),
      );
      await _pump(
        tester,
        brightness: Brightness.light,
        mode: LiquidGlassMode.none,
        info: const SubscriptionInfo(download: 125, total: 100),
      );
      expect(
        SubscriptionUsageFill.percentageOf(
          tester.element(find.byType(SubscriptionUsageFill)),
          const SubscriptionInfo(download: 125, total: 100),
        ),
        '125%',
      );
      expect(await _snapshot(tester, 'overrun'), orderedEquals(empty));
      await _pump(
        tester,
        brightness: Brightness.light,
        mode: LiquidGlassMode.none,
        direction: TextDirection.rtl,
      );
      final rtl = await _snapshot(tester, 'rtl');
      expect(
        _distance(_pixel(rtl, 350, 80), _pixel(empty, 350, 80)),
        greaterThan(0.02),
      );
      expect(_pixel(rtl, 40, 80), _pixel(empty, 40, 80));
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(Duration.zero);
    }
  });

  testWidgets('reduced motion stops scheduling frames', (tester) async {
    tester.view.physicalSize = const Size(400, 160);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    try {
      await _pump(
        tester,
        brightness: Brightness.light,
        mode: LiquidGlassMode.ultra,
        time: null,
        reduced: true,
      );
      await tester.pump();
      final still = await _snapshot(tester, 'reduced');
      await tester.pump(const Duration(seconds: 3));
      expect(await _snapshot(tester, 'reduced-later'), orderedEquals(still));
      expect(tester.binding.hasScheduledFrame, isFalse);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(Duration.zero);
    }
  });

  testWidgets(
    'cached grid repaint boundaries stop and resume their liquid clock',
    (tester) async {
      tester.view.physicalSize = const Size(400, 160);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      try {
        await tester.pumpWidget(
          TestApp(
            child: LiquidGlassScope(
              enabled: true,
              child: LiquidGlassHost(
                enabled: true,
                child: GridView.builder(
                  controller: controller,
                  cacheExtent: 1000,
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 1,
                    mainAxisExtent: 104,
                  ),
                  itemCount: 12,
                  itemBuilder: (_, index) => index == 0
                      ? const RepaintBoundary(
                          key: ValueKey('cachedLiquidCard'),
                          child: SubscriptionUsageFill(
                            info: _info,
                            child: SizedBox.expand(),
                          ),
                        )
                      : const SizedBox.shrink(),
                ),
              ),
            ),
          ),
        );
        await tester.pump(const Duration(milliseconds: 100));
        expect(tester.binding.hasScheduledFrame, isTrue);
        final cachedState = tester.state(find.byType(SubscriptionUsageFill));
        controller.jumpTo(300);
        await tester.pump(const Duration(milliseconds: 100));
        await tester.pump();
        final cachedFill = find.byType(
          SubscriptionUsageFill,
          skipOffstage: false,
        );
        expect(cachedFill, findsOneWidget);
        expect(tester.state(cachedFill), same(cachedState));
        expect(
          find.byKey(const ValueKey('cachedLiquidCard'), skipOffstage: false),
          findsOneWidget,
        );
        expect(tester.binding.hasScheduledFrame, isFalse);
        controller.jumpTo(0);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(tester.binding.hasScheduledFrame, isTrue);
        expect(
          tester.state(find.byType(SubscriptionUsageFill)),
          same(cachedState),
        );
        final resumed = await _snapshot(
          tester,
          'grid-resumed',
          boundaryKey: const ValueKey('cachedLiquidCard'),
        );
        await tester.pump(const Duration(seconds: 3));
        final moved = await _snapshot(
          tester,
          'grid-resumed-later',
          boundaryKey: const ValueKey('cachedLiquidCard'),
        );
        expect(moved, isNot(orderedEquals(resumed)));
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
        await tester.pump(Duration.zero);
      }
    },
  );
}
