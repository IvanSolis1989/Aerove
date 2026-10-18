import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/sliver_glass_group.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _backdrop = Color(0xFF0000FF);
const _fill = Color(0xFFFF0000);
const _sceneKey = ValueKey('sliverGlassScene');
const _headerKey = ValueKey('sliverGlassHeader');

Future<({Uint8List bytes, int width})> _snapshot(
  WidgetTester tester,
  String name,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_sceneKey),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final rgba = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        final directory = Directory(output)..createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('${directory.path}/sliver-glass-$name.png').writeAsBytesSync(
          png.buffer.asUint8List(),
        );
      }
      return (bytes: rgba.buffer.asUint8List(), width: image.width);
    } finally {
      image.dispose();
    }
  }))!;
}

Color _pixel(({Uint8List bytes, int width}) shot, int x, int y) {
  final offset = (y * shot.width + x) * 4;
  return Color.fromARGB(
    shot.bytes[offset + 3],
    shot.bytes[offset],
    shot.bytes[offset + 1],
    shot.bytes[offset + 2],
  );
}

Future<void> _pump(
  WidgetTester tester,
  ScrollController controller, {
  required double bodyHeight,
  required LiquidGlassQuality quality,
}) async {
  tester.view.physicalSize = const Size(240, 320);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: LiquidGlassScope(
          enabled: true,
          quality: quality,
          child: LiquidGlassHost(
            enabled: true,
            child: RepaintBoundary(
              key: _sceneKey,
              child: ColoredBox(
                color: _backdrop,
                child: CustomScrollView(
                  controller: controller,
                  slivers: [
                    SliverPadding(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      sliver: SliverGlassGroup(
                        shape: AppShape.xl,
                        style: const LiquidGlassStyle(
                          tint: _fill,
                          blur: 0,
                          refraction: 0,
                          bevel: 0,
                          rim: 0,
                        ),
                        slivers: [
                          const PinnedHeaderSliver(
                            child: SizedBox(key: _headerKey, height: 80),
                          ),
                          SliverToBoxAdapter(child: SizedBox(height: bodyHeight)),
                        ],
                      ),
                    ),
                    const SliverToBoxAdapter(child: SizedBox(height: 400)),
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
}

void main() {
  for (final quality in LiquidGlassQuality.values) {
    testWidgets('the complete group paints rounded outer corners and no seam '
        '(${quality.name})', (tester) async {
      final controller = ScrollController();
      try {
        await _pump(tester, controller, bodyHeight: 120, quality: quality);
        final shot = await _snapshot(tester, '${quality.name}-whole');
        expect(_pixel(shot, 21, 1), _backdrop);
        expect(_pixel(shot, 218, 1), _backdrop);
        expect(_pixel(shot, 21, 198), _backdrop);
        expect(_pixel(shot, 218, 198), _backdrop);
        expect(_pixel(shot, 120, 198), _fill);
        expect(_pixel(shot, 21, 78), _fill);
        expect(_pixel(shot, 21, 81), _fill);
        expect(_pixel(shot, 120, 79), _fill);
        expect(_pixel(shot, 120, 81), _fill);

        await _pump(tester, controller, bodyHeight: 0, quality: quality);
        final empty = await _snapshot(tester, '${quality.name}-empty');
        expect(_pixel(empty, 21, 78), _backdrop);
        expect(_pixel(empty, 218, 78), _backdrop);
        expect(_pixel(empty, 120, 78), _fill);
        expect(_pixel(empty, 120, 81), _backdrop);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
      }
    });

    testWidgets('viewport clipping keeps straight sides until the real tail '
        '(${quality.name})', (tester) async {
      final controller = ScrollController();
      try {
        await _pump(tester, controller, bodyHeight: 800, quality: quality);
        final before = await _snapshot(tester, '${quality.name}-viewport-cut');
        expect(_pixel(before, 21, 318), _fill);
        expect(_pixel(before, 218, 318), _fill);
        final pinnedTop = tester.getTopLeft(find.byKey(_headerKey)).dy;

        controller.jumpTo(600);
        await tester.pumpAndSettle();
        expect(tester.getTopLeft(find.byKey(_headerKey)).dy, pinnedTop);
        final tail = await _snapshot(tester, '${quality.name}-tail');
        expect(_pixel(tail, 21, 278), _backdrop);
        expect(_pixel(tail, 218, 278), _backdrop);
        expect(_pixel(tail, 120, 278), _fill);
        expect(_pixel(tail, 21, 79), _fill);
        expect(_pixel(tail, 21, 81), _fill);

        controller.jumpTo(850);
        await tester.pumpAndSettle();
        expect(tester.getTopLeft(find.byKey(_headerKey)).dy, -50);
        final leaving = await _snapshot(tester, '${quality.name}-leaving');
        expect(_pixel(leaving, 21, 28), _backdrop);
        expect(_pixel(leaving, 120, 28), _fill);
        expect(_pixel(leaving, 120, 31), _backdrop);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
      }
    });
  }
}
