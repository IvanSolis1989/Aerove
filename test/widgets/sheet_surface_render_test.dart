import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/sheet_surface.dart';
import 'package:fl_clash/ui/snap_sheet.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/views/dashboard/widgets/connection_stats.dart';
import 'package:fl_clash/views/dns_queries.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

const _size = Size(390, 800);

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    final font = FontLoader('SheetSurfaceProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
  });

  testWidgets('sheet bases retain opaque None and high contrast fallbacks', (
    tester,
  ) async {
    for (final (enabled, highContrast) in [(false, false), (true, true)]) {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: const MediaQueryData().copyWith(highContrast: highContrast),
            child: LiquidGlassScope(
              enabled: enabled,
              child: const SheetSurface(
                color: Colors.orange,
                shape: AppShape.xxl,
                child: SizedBox(width: 200, height: 200),
              ),
            ),
          ),
        ),
      );
      expect(find.byType(LiquidGlass), findsNothing);
      final material = tester.widget<Material>(
        find
            .descendant(
              of: find.byType(SheetSurface),
              matching: find.byType(Material),
            )
            .first,
      );
      expect(material.color, Colors.orange);
      expect(material.clipBehavior, Clip.antiAlias);
      expect(tester.takeException(), isNull);
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets(
        'dashboard feed modal frosts its full surface $brightness $quality',
        (tester) async {
          tester.view.physicalSize = _size;
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final container = ProviderContainer(
            overrides: [profilesProvider.overrideWith(TestProfiles.new)],
          );
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = _size;
          final notifier = container.read(dnsQueriesProvider.notifier);
          for (var index = 0; index < 40; index++) {
            notifier.addQuery(
              DnsQuery(
                domain: 'sample-$index.example',
                type: 'A',
                answers: const ['192.0.2.1'],
                rcode: 'NOERROR',
                delay: 12,
                time: DateTime.utc(2026, 10, 18),
              ),
            );
          }
          final capture = GlobalKey();
          final ink = brightness == Brightness.light
              ? Colors.black
              : Colors.white;
          final base = ThemeData(
            brightness: brightness,
            fontFamily: 'SheetSurfaceProbe',
            platform: TargetPlatform.android,
          );
          final theme = base.copyWith(
            textTheme: base.textTheme.apply(bodyColor: ink, displayColor: ink),
          );
          try {
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: Directionality(
                  textDirection: TextDirection.ltr,
                  child: RepaintBoundary(
                    key: capture,
                    child: LiquidGlassScope(
                      enabled: true,
                      quality: quality,
                      child: LiquidGlassHost(
                        enabled: true,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            const CustomPaint(painter: _Backdrop()),
                            TestApp(
                              includeNavigatorKey: false,
                              theme: theme,
                              child: const Scaffold(
                                backgroundColor: Colors.transparent,
                                body: Align(
                                  alignment: Alignment.topCenter,
                                  child: Padding(
                                    padding: EdgeInsets.all(16),
                                    child: ConnectionStatsCard(),
                                  ),
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
            );
            await tester.pumpAndSettle();
            final prefix = 'modal-${brightness.name}-${quality.name}';
            final before = await _picture(tester, capture, '$prefix-before');
            final stat = find.descendant(
              of: find.byType(ConnectionStatsCard),
              matching: find.text('DNS queries'),
            );
            expect(stat, findsOneWidget);
            await tester.tap(stat);
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 160));
            await _checkCorners(tester, capture, before, '$prefix-enter');
            await tester.pumpAndSettle();
            expect(find.byType(DnsQueriesView), findsOneWidget);
            expect(find.byType(LiquidGlassHost), findsOneWidget);
            final surfaces = find.byType(SheetSurface);
            expect(surfaces, findsNWidgets(2));
            final sheet = tester.widget<SheetSurface>(surfaces.first);
            final rect = tester.getRect(surfaces.first);
            final bases = find
                .descendant(of: surfaces.first, matching: find.byType(LiquidGlass))
                .evaluate()
                .where((element) => identical(
                  element.findAncestorWidgetOfExactType<SheetSurface>(), sheet,
                ))
                .where((element) => tester.getRect(find.byWidget(element.widget)) == rect)
                .map((element) => element.widget as LiquidGlass)
                .toList();
            expect(bases, hasLength(1));
            expect(bases.single.style.blur, 16);
            expect(bases.single.style.refraction, 0);
            expect(bases.single.clipper, isA<ShapeBorderClipper>());
            expect((bases.single.clipper! as ShapeBorderClipper).shape, sheet.shape);
            final after = await _checkCorners(
              tester,
              capture,
              before,
              '$prefix-open',
            );
            final region = Rect.fromLTWH(2, rect.top + 100, 2, 120);
            final rawVariance = _variance(before, region);
            final frostedVariance = _variance(after, region);
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              await tester.runAsync(
                () => File('$output/$prefix-metrics.json').writeAsString(
                  jsonEncode({
                    'shaderFilterSupported':
                        ui.ImageFilter.isShaderFilterSupported,
                    'rawBackgroundVariance': rawVariance,
                    'sheetBackgroundVariance': frostedVariance,
                    'varianceRatio': frostedVariance / rawVariance,
                    'wholeSheetPanes': bases.length,
                  }),
                ),
              );
            }
            expect(rawVariance, greaterThan(0));
            expect(
              frostedVariance,
              lessThan(rawVariance * 0.1),
              reason:
                  'The whole-sheet frost must suppress background glyphs without a dimming barrier.',
            );
            await _checkTitle(tester, capture, after);
            final gesture = await tester.startGesture(
              Offset(195, rect.top + 20),
            );
            try {
              await gesture.moveBy(const Offset(0, -80));
              await tester.pump();
              await _checkCorners(tester, capture, before, '$prefix-drag');
            } finally {
              await gesture.up();
            }
            await tester.pumpAndSettle();
            await _checkCorners(tester, capture, before, '$prefix-expanded');
            await tester.drag(
              find
                  .descendant(
                    of: find.byType(DnsQueriesView),
                    matching: find.byType(Scrollable),
                  )
                  .first,
              const Offset(0, -100),
            );
            await tester.pumpAndSettle();
            await _checkCorners(tester, capture, before, '$prefix-scroll');
            Navigator.of(tester.element(find.byType(DnsQueriesView))).pop();
            await tester.pumpAndSettle();
            expect(find.byType(DnsQueriesView), findsNothing);
            expect(tester.takeException(), isNull);
          } finally {
            await tester.pumpWidget(const SizedBox.shrink());
            container.dispose();
          }
        },
      );
    }
  }
}

Future<Uint8List> _picture(
  WidgetTester tester,
  GlobalKey key,
  String name,
) async {
  expect(tester.takeException(), isNull);
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData())!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        await Directory(output).create(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        await File('$output/$name.png').writeAsBytes(
          png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
        );
      }
      return Uint8List.fromList(
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
      );
    } finally {
      image.dispose();
    }
  }))!;
}

Future<Uint8List> _checkCorners(
  WidgetTester tester,
  GlobalKey key,
  Uint8List before,
  String name,
) async {
  final image = await _picture(tester, key, name);
  final rect = tester.getRect(find.byType(SheetSurface).first);
  final path = tester
      .widget<SheetSurface>(find.byType(SheetSurface).first)
      .shape
      .getOuterPath(rect);
  expect(find.byType(AnimatedModalBarrier), findsNothing);
  final route = ModalRoute.of(tester.element(find.byType(SheetSurface).first))!;
  expect(route, isA<SnapSheetRoute<dynamic>>());
  expect(route.barrierDismissible, isTrue);
  expect(route.barrierColor?.a ?? 0, 0);
  final barrierFinder = find.byWidgetPredicate(
    (widget) =>
        widget is ModalBarrier &&
        widget.semanticsLabel == route.barrierLabel &&
        widget.onDismiss != null,
  );
  expect(barrierFinder, findsOneWidget);
  final barrier = tester.widget<ModalBarrier>(barrierFinder);
  expect(barrier.dismissible, isTrue);
  expect(barrier.color?.a ?? 0, 0);
  final y = rect.top.ceil() + 1;
  expect(y, inInclusiveRange(0, 799));
  for (final x in [1, 388]) {
    for (final dx in [-1.0, 0.0, 1.0]) {
      for (final dy in [-1.0, 0.0, 1.0]) {
        expect(
          path.contains(Offset(x + 0.5 + dx, y + 0.5 + dy)),
          isFalse,
          reason:
              'Cutout probes need a full pixel of clearance from the superellipse.',
        );
      }
    }
    final at = (y * 390 + x) * 4;
    for (var channel = 0; channel < 3; channel++) {
      expect(
        image[at + channel],
        closeTo(before[at + channel], 1),
        reason:
            'The superellipse cutout must show the undimmed page, including toolbar corners.',
      );
    }
  }
  return image;
}

double _variance(Uint8List pixels, Rect region) {
  var sum = 0.0;
  var square = 0.0;
  var count = 0;
  for (var y = region.top.ceil(); y < region.bottom.floor(); y++) {
    for (var x = region.left.ceil(); x < region.right.floor(); x++) {
      final at = (y * 390 + x) * 4;
      final value = (pixels[at] + pixels[at + 1] + pixels[at + 2]) / 3;
      sum += value;
      square += value * value;
      count++;
    }
  }
  return square / count - (sum / count) * (sum / count);
}

Future<void> _checkTitle(
  WidgetTester tester,
  GlobalKey key,
  Uint8List actual,
) async {
  final title = find.descendant(
    of: find.byType(DnsQueriesView),
    matching: find.text('DNS queries'),
  );
  final element = tester.element(title);
  final text = tester.widget<Text>(title);
  final defaults = DefaultTextStyle.of(element);
  final style = defaults.style.merge(text.style);
  expect(style.color!.a, 1);
  final ink = style.color!;
  final channels = [
    (ink.r * 255).round(),
    (ink.g * 255).round(),
    (ink.b * 255).round(),
  ];
  final rect = tester.getRect(title).shift(-tester.getTopLeft(find.byKey(key)));
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder)
    ..drawColor(
      ink.computeLuminance() < 0.5 ? Colors.white : Colors.black,
      BlendMode.src,
    );
  final painter = TextPainter(
    textDirection: TextDirection.ltr,
    textScaler: MediaQuery.textScalerOf(element),
    textHeightBehavior: text.textHeightBehavior ?? defaults.textHeightBehavior,
    text: TextSpan(
      text: text.data,
      style: style.copyWith(shadows: const []),
    ),
  );
  painter.layout(maxWidth: rect.width);
  painter.paint(canvas, rect.topLeft);
  painter.dispose();
  final picture = recorder.endRecording();
  final reference = (await tester.runAsync(() async {
    final mask = await picture.toImage(390, 800);
    try {
      final pixels = (await mask.toByteData())!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        final png = (await mask.toByteData(format: ui.ImageByteFormat.png))!;
        final brightness = Theme.of(element).brightness.name;
        final quality = LiquidGlassScope.qualityOf(element).name;
        await File(
          '$output/modal-$brightness-$quality-title-reference.png',
        ).writeAsBytes(
          png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
        );
      }
      return Uint8List.fromList(
        pixels.buffer.asUint8List(pixels.offsetInBytes, pixels.lengthInBytes),
      );
    } finally {
      mask.dispose();
      picture.dispose();
    }
  }))!;
  var sharp = 0;
  for (var i = 0; i < reference.length; i += 4) {
    if (reference[i] != channels[0] ||
        reference[i + 1] != channels[1] ||
        reference[i + 2] != channels[2])
      continue;
    sharp++;
    for (var channel = 0; channel < 3; channel++) {
      expect(
        actual[i + channel],
        closeTo(channels[channel], 1),
        reason:
            'Opaque title core at (${i ~/ 4 % 390}, ${i ~/ (390 * 4)}) '
            'must match its independent ink-polarity glyph reference.',
      );
    }
  }
  expect(sharp, greaterThan(0));
}

class _Backdrop extends CustomPainter {
  const _Backdrop();

  @override
  void paint(Canvas canvas, Size size) {
    for (var y = 0.0; y < size.height; y += 8) {
      canvas.drawRect(
        Rect.fromLTWH(0, y, size.width, 8),
        Paint()
          ..color = (y ~/ 8).isEven
              ? const Color(0xFF6B9DB7)
              : const Color(0xFFDBD2A4),
      );
    }
    final text = TextPainter(
      textDirection: TextDirection.ltr,
      text: const TextSpan(
        text: 'Background text 012345',
        style: TextStyle(
          fontFamily: 'SheetSurfaceProbe',
          fontSize: 15,
          color: Colors.black,
        ),
      ),
    );
    text.layout();
    for (var y = 0.0; y < size.height; y += 32)
      text.paint(canvas, Offset(0, y));
    text.dispose();
  }

  @override
  bool shouldRepaint(_Backdrop oldDelegate) => false;
}
