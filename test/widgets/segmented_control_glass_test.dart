import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/segmented_control.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('segments refract the field in $brightness $quality', (
        tester,
      ) async {
        tester.view.physicalSize = const Size(320, 160);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        var selected = 0;
        for (final direction in TextDirection.values) {
          final samples = <List<int>>[];
          for (final background in [Colors.blue, Colors.red]) {
            final capture = GlobalKey();
            await tester.pumpWidget(
              MaterialApp(
                theme: ThemeData(brightness: brightness),
                home: Directionality(
                  textDirection: direction,
                  child: RepaintBoundary(
                    key: capture,
                    child: LiquidGlassScope(
                      enabled: true,
                      quality: quality,
                      child: LiquidGlassHost(
                        enabled: true,
                        wallpaperOnly: true,
                        child: Stack(
                          children: [
                            Positioned.fill(
                              child: LiquidGlassColorField(
                                child: LiquidGlassColorSource(
                                  child: ColoredBox(color: background),
                                ),
                              ),
                            ),
                            Center(
                              child: SizedBox(
                                width: 280,
                                child: StatefulBuilder(
                                  builder: (_, update) => SegmentedControl<int>(
                                    segments: const [
                                      (0, 'System'),
                                      (1, 'Custom'),
                                    ],
                                    value: selected,
                                    onChanged: (next) =>
                                        update(() => selected = next),
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
            expect(
              tester.getSize(find.byType(SegmentedControl<int>)).height,
              44,
            );
            final boundary = tester.renderObject<RenderRepaintBoundary>(
              find.byKey(capture),
            );
            await tester.runAsync(() async {
              final image = await boundary.toImage();
              try {
                final bytes = (await image.toByteData())!;
                final panes = LiquidGlassHost.debugLastCapture!.panes;
                expect(panes, hasLength(2));
                samples.add([
                  for (final pane in panes)
                    bytes.getUint8(
                      ((pane.pane.top + 8).floor() * image.width +
                              pane.pane.center.dx.floor()) *
                          4,
                    ),
                ]);
                final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
                if (output != null) {
                  Directory(output).createSync(recursive: true);
                  final png = (await image.toByteData(
                    format: ui.ImageByteFormat.png,
                  ))!;
                  File(
                    '$output/segments-${brightness.name}-${quality.name}-${direction.name}-${background.toARGB32()}.png',
                  ).writeAsBytesSync(png.buffer.asUint8List());
                }
              } finally {
                image.dispose();
              }
            });
            await tester.tap(find.text(selected == 0 ? 'Custom' : 'System'));
            await tester.pumpAndSettle();
            expect(selected, background == Colors.blue ? 1 : 0);
            expect(tester.takeException(), isNull);
          }
          for (var pane = 0; pane < 2; pane++) {
            expect(
              (samples[0][pane] - samples[1][pane]).abs(),
              greaterThan(30),
            );
          }
        }
      });
    }
  }

  testWidgets('None and high contrast retain the native segmented fills', (
    tester,
  ) async {
    for (final highContrast in [false, true]) {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(highContrast: highContrast),
            child: LiquidGlassScope(
              enabled: highContrast,
              child: Center(
                child: SizedBox(
                  width: 280,
                  child: SegmentedControl<int>(
                    segments: const [(0, 'System'), (1, 'Custom')],
                    value: 0,
                    onChanged: (_) {},
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(LiquidGlass), findsNothing);
      expect(tester.getSize(find.byType(SegmentedControl<int>)).height, 44);
      expect(find.text('System'), findsOneWidget);
      expect(find.text('Custom'), findsOneWidget);
    }
  });
}
