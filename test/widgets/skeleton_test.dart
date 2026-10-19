import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/skeleton.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  const style = TextStyle(fontSize: 14, height: 20 / 14);

  Widget line({double textScale = 1}) => TestApp(
    child: MediaQuery(
      data: MediaQueryData(textScaler: TextScaler.linear(textScale)),
      child: const Material(
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('203.0.113.7', style: style),
              SkeletonText(width: 104, style: style),
            ],
          ),
        ),
      ),
    ),
  );

  testWidgets('takes the scaled height of the line it stands in for', (
    tester,
  ) async {
    for (final scale in [1.0, 1.8]) {
      await tester.pumpWidget(line(textScale: scale));
      expect(
        tester.getSize(find.byType(SkeletonText)),
        Size(104, tester.getSize(find.text('203.0.113.7')).height),
      );
    }
  });

  testWidgets('reserves its dimensions without scheduling animation frames', (
    tester,
  ) async {
    await tester.pumpWidget(line());
    await tester.pumpAndSettle();
    final before = tester.getRect(find.byType(SkeletonText));

    var paints = 0;
    var rebuilds = 0;
    debugProfilePaintsEnabled = true;
    debugOnProfilePaint = (_) => paints++;
    debugOnRebuildDirtyWidget = (_, _) => rebuilds++;
    try {
      for (var frame = 0; frame < 30; frame++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
    } finally {
      debugProfilePaintsEnabled = false;
      debugOnProfilePaint = null;
      debugOnRebuildDirtyWidget = null;
    }

    expect(tester.getRect(find.byType(SkeletonText)), before);
    expect(tester.hasRunningAnimations, isFalse);
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(paints, 0);
    expect(rebuilds, 0);
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      for (final enabled in [false, true]) {
        testWidgets(
          'loading space is transparent in $brightness glass=$enabled $quality',
          (tester) async {
            for (final highContrast in [false, true]) {
              for (final disableAnimations in [false, true]) {
                final capture = GlobalKey();
                Future<Uint8List> render(bool loading) async {
                  await tester.pumpWidget(
                    MaterialApp(
                      theme: ThemeData(brightness: brightness),
                      home: MediaQuery(
                        data: MediaQueryData(
                          highContrast: highContrast,
                          disableAnimations: disableAnimations,
                        ),
                        child: LiquidGlassScope(
                          enabled: enabled,
                          quality: quality,
                          child: Center(
                            child: RepaintBoundary(
                              key: capture,
                              child: SizedBox(
                                width: 240,
                                height: 120,
                                child: Stack(
                                  fit: StackFit.expand,
                                  children: [
                                    const Row(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        Expanded(
                                          child: ColoredBox(
                                            color: Color(0xFF286C85),
                                          ),
                                        ),
                                        Expanded(
                                          child: ColoredBox(
                                            color: Color(0xFFBB5276),
                                          ),
                                        ),
                                      ],
                                    ),
                                    if (loading)
                                      const Positioned(
                                        left: 16,
                                        top: 16,
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: [
                                            SkeletonText(
                                              width: 208,
                                              style: style,
                                            ),
                                            SkeletonPill(width: 160, height: 24),
                                          ],
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
                  await tester.pumpAndSettle();
                  final boundary = tester.renderObject<RenderRepaintBoundary>(
                    find.byKey(capture),
                  );
                  return (await tester.runAsync(() async {
                    final image = await boundary.toImage();
                    try {
                      return (await image.toByteData(
                        format: ui.ImageByteFormat.rawRgba,
                      ))!.buffer.asUint8List();
                    } finally {
                      image.dispose();
                    }
                  }))!;
                }

                final background = await render(false);
                expect(await render(true), background);
                final pill = find.byType(SkeletonPill).last;
                expect(tester.getSize(pill), const Size(160, 24));
                expect(
                  tester.widgetList<ExcludeSemantics>(
                    find.descendant(
                      of: find.byType(SkeletonText),
                      matching: find.byType(ExcludeSemantics),
                    ),
                  ).every((widget) => widget.excluding),
                  isTrue,
                );
                expect(tester.hasRunningAnimations, isFalse);
                expect(tester.takeException(), isNull);
              }
            }
          },
        );
      }
    }
  }
}
