import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/status_layout.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });
  for (final (name, glass, quality) in [
    ('solid', false, LiquidGlassQuality.ultra),
    ('frost', true, LiquidGlassQuality.standard),
    ('clear', true, LiquidGlassQuality.ultra),
  ]) {
    for (final aurora in [false, true]) {
      for (final brightness in Brightness.values) {
        testWidgets(
          'foreground marks never punch panels into continuous curve fill '
          '$name aurora=$aurora ${brightness.name}',
          (tester) async {
            tester.view.physicalSize = const Size(400, 400);
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.reset);
            final capture = GlobalKey();
            final colors = aurora
                ? auroraColorScheme(brightness)
                : ColorScheme.fromSeed(
                    seedColor: const Color(0xFF6750A4),
                    brightness: brightness,
                  );
            final theme = ThemeData(colorScheme: colors);
            Future<(List<int>, int)> render(bool marked) async {
              const text = SizedBox(
                width: 288,
                height: 80,
                child: Text(
                  'I      I\n00:12:34\nExample',
                  style: TextStyle(
                    color: Colors.black,
                    fontSize: 20,
                    height: 1,
                  ),
                ),
              );
              final card = SizedBox(
                width: 320,
                child: RepaintBoundary(
                  key: capture,
                  child: DashboardCard(
                    padding: EdgeInsets.zero,
                    child: StatusCardLayout(
                      padding: const EdgeInsets.all(16),
                      shape: AppShape.xl,
                      baselineRatio: 1,
                      backdrop: LineChart(
                        values: List.filled(20, 100.0),
                        revision: 20,
                        capacity: 20,
                        minScale: 100,
                        color: colors.primary,
                        baselineRatio: 1,
                        strokeOpacity: 0.34,
                        fillOpacity: 0.14 * 0.34,
                        fillToBaseline: true,
                      ),
                      body: marked ? const StatusForeground(child: text) : text,
                      figures: const SizedBox.shrink(),
                    ),
                  ),
                ),
              );
              await tester.pumpWidget(
                TestApp(
                  theme: theme,
                  darkTheme: theme,
                  themeMode: brightness == Brightness.dark
                      ? ThemeMode.dark
                      : ThemeMode.light,
                  child: AuroraAppearance(
                    enabled: aurora,
                    child: LiquidGlassScope(
                      enabled: glass,
                      foregroundEnabled: aurora,
                      quality: quality,
                      child: LiquidGlassHost(
                        enabled: true,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            const Positioned.fill(
                              child: LiquidGlassColorField(
                                child: ColoredBox(color: Color(0xFFBDE5EC)),
                              ),
                            ),
                            Align(alignment: Alignment.topLeft, child: card),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              );
              await tester.pump();
              final boundary = tester.renderObject<RenderRepaintBoundary>(
                find.byKey(capture),
              );
              final masks = <ShaderMaskLayer>[];
              void visit(Layer layer) {
                if (layer is ShaderMaskLayer) masks.add(layer);
                if (layer is ContainerLayer) {
                  for (
                    var child = layer.firstChild;
                    child != null;
                    child = child.nextSibling
                  ) {
                    visit(child);
                  }
                }
              }

              visit(boundary.debugLayer!);
              expect(masks, isEmpty);
              return (await tester.runAsync(() async {
                final image = await boundary.toImage();
                final bytes = (await image.toByteData(
                  format: ui.ImageByteFormat.rawRgba,
                ))!.buffer.asUint8List();
                final width = image.width;
                image.dispose();
                return (List<int>.of(bytes), width);
              }))!;
            }

            final plain = await render(false);
            final marked = await render(true);
            expect(marked.$2, plain.$2);
            expect(
              marked.$1,
              plain.$1,
              reason:
                  'foreground bounds must never alter any background or fill pixel',
            );
            List<int> pixel(int x, int y) => marked.$1.sublist(
              (y * marked.$2 + x) * 4,
              (y * marked.$2 + x) * 4 + 4,
            );
            expect(
              pixel(65, 26),
              pixel(260, 26),
              reason:
                  'the gap between glyphs keeps the same continuous area fill',
            );
            expect(
              pixel(65, 26),
              isNot([255, 255, 255, 255]),
              reason: 'the fill must remain visible inside the text bounds',
            );
            expect(tester.takeException(), isNull);
          },
          skip: glass && !ui.ImageFilter.isShaderFilterSupported,
        );
      }
    }
  }
}
