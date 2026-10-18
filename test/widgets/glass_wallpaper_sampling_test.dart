import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _key = ValueKey('wallpaper-sampling');
const _front = Rect.fromLTWH(80, 80, 160, 120);
const _dock = Rect.fromLTWH(110, 120, 44, 40);
const _pink = Color(0xFFEAD0E8);
const _blue = Color(0xFFBDE5EC);
const _clear = LiquidGlassStyle(
  tint: Colors.transparent,
  blur: 0,
  bevel: 0,
  refraction: 0,
  rim: 0,
);

Future<({Uint8List bytes, int width})> _shot(
  WidgetTester tester,
  String tag,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_key));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final bytes = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/glass-wallpaper-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
      }
      return (bytes: bytes.buffer.asUint8List(), width: image.width);
    } finally {
      image.dispose();
    }
  }))!;
}

Color _pixel(({Uint8List bytes, int width}) image, int x, int y) {
  final index = (y * image.width + x) * 4;
  return Color.fromARGB(
    image.bytes[index + 3],
    image.bytes[index],
    image.bytes[index + 1],
    image.bytes[index + 2],
  );
}

Widget _pane(
  Rect rect, {
  bool content = false,
  LiquidGlassStyle style = _clear,
}) => Positioned.fromRect(
  rect: rect,
  child: LiquidGlass(
    style: style,
    contentBackdrop: content,
    clipper: const ShapeBorderClipper(shape: AppShape.none),
  ),
);

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });
  for (final quality in LiquidGlassQuality.values) {
    testWidgets('wallpaper panes stay flat while dock opts into UI $quality', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(400, 300);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      Future<void> mount({
        required bool wallpaperOnly,
        double shift = 0,
        bool content = true,
        bool enabled = true,
      }) async {
        await tester.pumpWidget(
          MaterialApp(
            home: RepaintBoundary(
              key: _key,
              child: LiquidGlassScope(
                enabled: enabled,
                quality: quality,
                child: LiquidGlassHost(
                  enabled: enabled,
                  wallpaperOnly: wallpaperOnly,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      LiquidGlassColorField(
                        child: ClipRect(
                          child: OverflowBox(
                            alignment: Alignment.topLeft,
                            minWidth: 800,
                            maxWidth: 800,
                            child: Transform.translate(
                              offset: Offset(-shift, 0),
                              child: const LiquidGlassColorSource(
                                child: Row(
                                  crossAxisAlignment: CrossAxisAlignment.stretch,
                                  children: [
                                    Expanded(child: ColoredBox(color: _pink)),
                                    Expanded(child: ColoredBox(color: _blue)),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                      const Positioned(
                        left: 20,
                        top: 20,
                        width: 260,
                        height: 240,
                        child: ColoredBox(color: Colors.green),
                      ),
                      _pane(
                        const Rect.fromLTWH(40, 40, 200, 180),
                        style: _clear.copyWith(tint: const Color(0x66FF0000)),
                      ),
                      _pane(_front),
                      const Positioned(
                        left: 100,
                        top: 110,
                        width: 70,
                        height: 40,
                        child: ColoredBox(color: Colors.orange),
                      ),
                      _pane(_dock, content: content),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pump();
        for (final color in [_pink, _blue]) {
          final source = find.byWidgetPredicate((widget) =>
              widget is ColoredBox && widget.color == color);
          expect(tester.getSize(source), const Size(400, 300));
        }
      }

      await mount(wallpaperOnly: false);
      final generic = await _shot(tester, '${quality.name}-generic');
      expect(_pixel(generic, 10, 150), _pink);
      expect(_pixel(generic, 100, 100), isNot(_pink));
      final old = LiquidGlassHost.debugLastCapture!;
      expect(
        old.panes.firstWhere((pane) => pane.pane == _front).level,
        greaterThan(0),
      );
      expect(old.panes.every((pane) => !pane.wallpaper), isTrue);
      await mount(wallpaperOnly: true, content: false);
      final flat = await _shot(tester, '${quality.name}-flat');
      expect(_pixel(flat, 100, 100), _pink);
      expect(_pixel(flat, 130, 130), _pink);
      expect(LiquidGlassHost.debugLastCapture!.replayedPanes, 0);
      expect(
        LiquidGlassHost.debugLastCapture!.panes.every(
          (pane) => pane.wallpaper && pane.level == 0,
        ),
        isTrue,
      );
      await mount(wallpaperOnly: true);
      final dock = await _shot(tester, '${quality.name}-dock');
      final captured = LiquidGlassHost.debugLastCapture!;
      expect(_pixel(dock, 100, 100), _pink);
      expect(_pixel(dock, 130, 130).toARGB32(), Colors.orange.toARGB32());
      expect(
        captured.panes
            .where((pane) => pane.pane != _dock)
            .every((pane) => pane.wallpaper && pane.level == 0),
        isTrue,
      );
      expect(
        captured.panes.firstWhere((pane) => pane.pane == _dock).wallpaper,
        isFalse,
      );
      expect(captured.panes.firstWhere((pane) => pane.pane == _dock).level, 1);
      expect(captured.replayedPanes, greaterThan(0));
      await mount(wallpaperOnly: true, shift: 300);
      final moved = await _shot(tester, '${quality.name}-moved');
      expect(_pixel(moved, 390, 290), _blue);
      expect(_pixel(moved, 100, 100), _blue);
      expect(_pixel(moved, 130, 130).toARGB32(), Colors.orange.toARGB32());
      await tester.pump();
      final stable = await _shot(tester, '${quality.name}-stable');
      expect(stable.bytes, moved.bytes);
      expect(LiquidGlassHost.debugLastCapture!.snapshots, 0);
      await mount(wallpaperOnly: false);
      final restored = await _shot(tester, '${quality.name}-generic-restored');
      expect(restored.bytes, generic.bytes);
      await mount(wallpaperOnly: false, enabled: false);
      final none = await _shot(tester, '${quality.name}-none');
      await mount(wallpaperOnly: true, enabled: false);
      final disabled = await _shot(tester, '${quality.name}-disabled');
      expect(disabled.bytes, none.bytes);
      expect(tester.takeException(), isNull);
    });
  }
}
