import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';

class _Field extends CustomPainter {
  const _Field(this.moved, this.dark);
  final bool moved;
  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final colors = dark
        ? const [Color(0xFF173940), Color(0xFF3B203B)]
        : const [Color(0xFFBDE5EC), Color(0xFFEAD0E8)];
    canvas.drawRect(Offset.zero & size, Paint()..color = colors[moved ? 1 : 0]);
  }

  @override
  bool shouldRepaint(_Field oldDelegate) =>
      moved != oldDelegate.moved || dark != oldDelegate.dark;
}

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });
  for (final brightness in Brightness.values) {
    for (final (name, glass, quality) in [
      ('solid', false, LiquidGlassQuality.ultra),
      ('frost', true, LiquidGlassQuality.standard),
      ('clear', true, LiquidGlassQuality.ultra),
    ]) {
      testWidgets('the two arrows and both complete rates share moving ink '
          '$name ${brightness.name}', (tester) async {
        tester.view.physicalSize = const Size(960, 480);
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        final capture = GlobalKey();
        final mask = GlobalKey();
        final colors = auroraColorScheme(brightness);
        Future<(List<int>, int)> pixels(GlobalKey key) async {
          final boundary =
              key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          return (await tester.runAsync(() async {
            final image = await boundary.toImage(pixelRatio: 3);
            final bytes = (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!.buffer.asUint8List();
            final result = (List<int>.of(bytes), image.width);
            image.dispose();
            return result;
          }))!;
        }

        Future<void> show(bool moved) async {
          await tester.pumpWidget(
            TestApp(
              includeNavigatorKey: false,
              theme: ThemeData(colorScheme: colors),
              darkTheme: ThemeData(colorScheme: colors),
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              overrides: [
                trafficsProvider.overrideWithValue(FixedList<Traffic>(30)),
              ],
              child: RepaintBoundary(
                key: capture,
                child: LiquidGlassScope(
                  enabled: glass,
                  foregroundEnabled: true,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: true,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Positioned.fill(
                          child: LiquidGlassColorField(
                            child: CustomPaint(
                              painter: _Field(
                                moved,
                                brightness == Brightness.dark,
                              ),
                            ),
                          ),
                        ),
                        Positioned(
                          left: 80,
                          top: 40,
                          child: RepaintBoundary(
                            key: mask,
                            child: const OutboundTrafficFigures(),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
        }

        await show(false);
        final figures = find.byType(OutboundTrafficFigures);
        final inks = tester.widgetList<GlassInk>(
          find.descendant(of: figures, matching: find.byType(GlassInk)),
        );
        final group = inks
            .where((ink) => ink.role == GlassInkRole.small)
            .toList();
        expect(group, hasLength(1));
        expect(group.single.source, colors.onSurface);
        final origin = tester.getTopLeft(find.byKey(mask));
        final rootOrigin = tester.getTopLeft(find.byKey(capture));
        final regions = [
          for (final element
              in find
                  .descendant(of: figures, matching: find.byType(GlyphIcon))
                  .evaluate())
            (element.renderObject! as RenderBox).localToGlobal(Offset.zero) &
                (element.renderObject! as RenderBox).size,
          for (final element
              in find
                  .descendant(of: figures, matching: find.byType(RichText))
                  .evaluate())
            (element.renderObject! as RenderBox).localToGlobal(Offset.zero) &
                (element.renderObject! as RenderBox).size,
        ];
        expect(regions, hasLength(4));
        final (coverage, maskWidth) = await pixels(mask);
        final (first, rootWidth) = await pixels(capture);
        await show(true);
        final (moved, _) = await pixels(capture);
        for (final region in regions) {
          var opaque = 0;
          var followed = 0;
          final local = region.shift(-origin);
          for (
            var y = (local.top * 3).ceil();
            y < (local.bottom * 3).floor();
            y++
          ) {
            for (
              var x = (local.left * 3).ceil();
              x < (local.right * 3).floor();
              x++
            ) {
              if (coverage[(y * maskWidth + x) * 4 + 3] < 250) continue;
              opaque++;
              final rx = x + ((origin.dx - rootOrigin.dx) * 3).round();
              final ry = y + ((origin.dy - rootOrigin.dy) * 3).round();
              final index = (ry * rootWidth + rx) * 4;
              final delta =
                  (first[index] - moved[index]).abs() +
                  (first[index + 1] - moved[index + 1]).abs() +
                  (first[index + 2] - moved[index + 2]).abs();
              if (delta > 12) followed++;
            }
          }
          expect(
            opaque,
            greaterThan(2),
            reason: '$region contains no actual glyph ink',
          );
          expect(
            followed,
            greaterThan(opaque / 2),
            reason: '$region is fixed-colour instead of sampling its backdrop',
          );
        }
        expect(tester.takeException(), isNull);
      }, skip: !ui.ImageFilter.isShaderFilterSupported);
    }
  }
}
