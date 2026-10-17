import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/ui/card.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  test('standard superellipse clippers preserve exact corner parameters', () {
    const size = Size(400, 56);
    for (final shape in [
      AppShape.none,
      AppShape.lg,
      AppShape.top(AppCorner.xl),
      AppShape.vertical(bottom: AppCorner.xl),
      AppShape.full,
    ]) {
      final glassShape = LiquidGlassShape.fromClipper(
        ShapeBorderClipper(shape: shape, textDirection: TextDirection.ltr),
        size,
      );
      expect(glassShape, isA<LiquidGlassCorners>());
      expect(
        (glassShape as LiquidGlassCorners).radius,
        shape.borderRadius.resolve(TextDirection.ltr),
      );
    }
    expect(
      LiquidGlassShape.fromClipper(const _Diamond(), size),
      isA<LiquidGlassOutline>(),
    );
    expect(
      (LiquidGlassShape.fromClipper(null, size) as LiquidGlassCorners).radius,
      BorderRadius.circular(28),
    );
  });

  test('a profile reads how far an outline reaches each way', () {
    final box = liquidGlassProfile(
      Path()..addRect(const Rect.fromLTWH(0, 0, 40, 20)),
      const Offset(20, 10),
    );
    expect(box[0], moreOrLessEquals(20, epsilon: 1e-6));
    expect(box[16], moreOrLessEquals(10, epsilon: 1e-6));
    expect(box[32], moreOrLessEquals(20, epsilon: 1e-6));
    expect(box[48], moreOrLessEquals(10, epsilon: 1e-6));
    expect(box[8], moreOrLessEquals(10 * math.sqrt2, epsilon: 1e-6));

    final round = liquidGlassProfile(
      Path()..addOval(Rect.fromCircle(center: Offset.zero, radius: 30)),
      Offset.zero,
    );
    expect(round, everyElement(moreOrLessEquals(30, epsilon: 0.05)));
  });

  test('corner curves lie on the superellipse the engine clips to', () {
    for (final (size, radius) in [
      (const Size(338, 62), 31.0),
      (const Size(84, 54), 27.0),
      (const Size(44, 44), 22.0),
      (const Size(270, 160), 34.0),
      (const Size(60, 200), 30.0),
    ]) {
      final corners = BorderRadius.circular(radius);
      final path = Path()
        ..addRSuperellipse(corners.toRSuperellipse(Offset.zero & size));
      final outline = [
        for (final metric in path.computeMetrics())
          for (var at = 0.0; at < metric.length; at += 0.02)
            metric.getTangentForOffset(at)!.position,
      ];
      double offPath(Offset point) =>
          outline.map((on) => (on - point).distance).reduce(math.min);

      final curves = liquidGlassCornerCurves(size, corners);
      final half = size.center(Offset.zero);
      final shift = half.dx - half.dy;
      final octants = [
        (
          a: half.dx,
          curve: curves.sublist(0, 4),
          center: Offset(curves[8], curves[9]),
          place: (Offset w) => half - Offset(w.dx, w.dy - shift),
        ),
        (
          a: half.dy,
          curve: curves.sublist(4, 8),
          center: Offset(curves[10], curves[11]),
          place: (Offset w) => half - Offset(w.dy + shift, w.dx),
        ),
      ];
      for (final (:a, :curve, :center, :place) in octants) {
        final [n, jx, jy, arc] = curve;
        for (var x = 0.0; x <= jx; x += jx / 16) {
          final y = math.pow(1 - math.pow(x / a, n), 1 / n) * a;
          expect(offPath(place(Offset(x, y))), lessThan(0.06));
        }
        final from = (Offset(jx, jy) - center).direction;
        for (var step = 0; step <= 8; step++) {
          final turn = from - (from - math.pi / 4) * step / 8;
          final point = center + Offset.fromDirection(turn, arc);
          expect(offPath(place(point)), lessThan(0.06));
        }
      }
    }
    expect(
      liquidGlassCornerCurves(const Size(40, 20), BorderRadius.zero),
      everyElement(0),
    );
  });

  testWidgets('a pane shaped by a clipper keeps to its path', (tester) async {
    final key = GlobalKey();
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: RepaintBoundary(
            key: key,
            child: const SizedBox.square(
              dimension: 40,
              child: LiquidGlass(
                style: LiquidGlassStyle(tint: Colors.blue),
                clipper: _Diamond(),
              ),
            ),
          ),
        ),
      ),
    );
    final boundary =
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final image = boundary.toImageSync();
    addTearDown(image.dispose);

    await tester.runAsync(() async {
      final bytes = (await image.toByteData())!;
      int alphaAt(int x, int y) =>
          bytes.getUint8((y * image.width + x) * 4 + 3);
      expect(alphaAt(5, 5), 0);
      expect(alphaAt(34, 34), 0);
      expect(alphaAt(20, 20), greaterThan(0));
      expect(alphaAt(20, 3), greaterThan(0));
    });
  });

  testWidgets('active card glass follows grouped corners when disabled too', (
    tester,
  ) async {
    const size = Size(240, 64);
    for (final (shape, roundTop, roundBottom) in [
      (AppShape.top(AppCorner.xl), true, false),
      (AppShape.vertical(bottom: AppCorner.xl), false, true),
      (AppShape.none, false, false),
    ]) {
      for (final inert in [false, true]) {
        await tester.pumpWidget(
          MaterialApp(
            theme: ThemeData(
              filledButtonTheme: FilledButtonThemeData(
                style: LiquidGlass.buttonStyle(
                  (_) => const LiquidGlassStyle(tint: Colors.blue),
                ),
              ),
            ),
            home: LiquidGlassHost(
              enabled: true,
              child: LiquidGlassScope(
                enabled: true,
                child: Center(
                  child: SizedBox.fromSize(
                    size: size,
                    child: CommonCard(
                      shape: shape,
                      inert: inert,
                      onPressed: () {},
                      child: const Text('Card'),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        final glass = tester.widget<LiquidGlass>(
          find.descendant(
            of: find.byType(CommonCard),
            matching: find.byType(LiquidGlass),
          ),
        );
        final clipper = glass.clipper! as ShapeBorderClipper;
        final path = clipper.getClip(size);
        expect(clipper.shape, shape);
        expect(path.contains(const Offset(1, 1)), !roundTop);
        expect(path.contains(const Offset(239, 1)), !roundTop);
        expect(path.contains(const Offset(1, 63)), !roundBottom);
        expect(path.contains(const Offset(239, 63)), !roundBottom);
        expect(find.byType(LiquidGlassHost), findsOneWidget);
        final button = tester.widget<FilledButton>(find.byType(FilledButton));
        expect(button.onPressed, inert ? isNull : isNotNull);
        expect(
          button.style!.backgroundColor!.resolve({WidgetState.disabled}),
          Colors.transparent,
        );
      }
    }
  });

  testWidgets('glass error cards retain a transparent semantic error tint', (
    tester,
  ) async {
    final colorScheme = ColorScheme.fromSeed(seedColor: Colors.blue);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(
          colorScheme: colorScheme,
          filledButtonTheme: FilledButtonThemeData(
            style: LiquidGlass.buttonStyle(
              (_) => LiquidGlassStyle.regular(colorScheme),
            ),
          ),
        ),
        home: const LiquidGlassScope(
          enabled: true,
          child: Center(
            child: CommonCard(
              shape: AppShape.none,
              isError: true,
              inert: true,
              child: Text('Error'),
            ),
          ),
        ),
      ),
    );
    expect(find.byType(LiquidGlass), findsNothing);
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
    for (final states in [
      <WidgetState>{},
      {WidgetState.disabled},
    ]) {
      expect(
        button.style!.backgroundColor!.resolve(states),
        colorScheme.errorContainer.withValues(alpha: 0.24),
      );
      expect(
        button.style!.foregroundColor!.resolve(states),
        colorScheme.onErrorContainer,
      );
    }
  });

  testWidgets('uncaptured glass preserves tint transparency over its backdrop', (
    tester,
  ) async {
    final tint = const Color(0xFF00FF00).withValues(alpha: 0.82);
    final tintedPixels = <Color>[];
    for (final backdrop in [const Color(0xFFFF0000), const Color(0xFF0000FF)]) {
      for (final fill in [tint, Colors.transparent]) {
        final key = GlobalKey();
        await tester.pumpWidget(
          MaterialApp(
            home: Center(
              child: RepaintBoundary(
                key: key,
                child: SizedBox.square(
                  dimension: 40,
                  child: ColoredBox(
                    color: backdrop,
                    child: LiquidGlass(style: LiquidGlassStyle(tint: fill)),
                  ),
                ),
              ),
            ),
          ),
        );
        expect(find.byType(LiquidGlassHost), findsNothing);
        final boundary = tester.renderObject<RenderRepaintBoundary>(
          find.byKey(key),
        );
        final image = boundary.toImageSync();
        try {
          await tester.runAsync(() async {
            final bytes = (await image.toByteData())!;
            final at = ((image.height ~/ 2) * image.width + image.width ~/ 2) * 4;
            final actual = Color.fromARGB(
              bytes.getUint8(at + 3),
              bytes.getUint8(at),
              bytes.getUint8(at + 1),
              bytes.getUint8(at + 2),
            );
            if (fill == Colors.transparent) {
              expect(actual, backdrop);
            } else {
              final expected = Color.alphaBlend(fill, backdrop);
              expect(actual.r, closeTo(expected.r, 2 / 255));
              expect(actual.g, closeTo(expected.g, 2 / 255));
              expect(actual.b, closeTo(expected.b, 2 / 255));
              tintedPixels.add(actual);
            }
          });
        } finally {
          image.dispose();
        }
      }
    }
    expect(tintedPixels[0].r - tintedPixels[1].r, greaterThan(0.15));
    expect(tintedPixels[1].b - tintedPixels[0].b, greaterThan(0.15));
  });

  testWidgets('a snapshot taken without panes holds only what stands on them', (
    tester,
  ) async {
    final key = GlobalKey();
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: RepaintBoundary(
            key: key,
            child: const SizedBox.square(
              dimension: 40,
              child: LiquidGlass(style: LiquidGlassStyle(tint: Colors.blue)),
            ),
          ),
        ),
      ),
    );
    final boundary =
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final bare = LiquidGlass.withoutPanes(boundary.toImageSync);
    final glass = boundary.toImageSync();
    addTearDown(bare.dispose);
    addTearDown(glass.dispose);

    Future<int> centerAlpha(ui.Image image) async {
      final bytes = (await image.toByteData())!;
      return bytes.getUint8(
        ((image.height ~/ 2) * image.width + image.width ~/ 2) * 4 + 3,
      );
    }

    await tester.runAsync(() async {
      expect(await centerAlpha(bare), 0);
      expect(await centerAlpha(glass), greaterThan(0));
    });
  });
}

class _Diamond extends CustomClipper<Path> {
  const _Diamond();

  @override
  Path getClip(Size size) => Path()
    ..moveTo(size.width / 2, 0)
    ..lineTo(size.width, size.height / 2)
    ..lineTo(size.width / 2, size.height)
    ..lineTo(0, size.height / 2)
    ..close();

  @override
  bool shouldReclip(_Diamond oldClipper) => false;
}
