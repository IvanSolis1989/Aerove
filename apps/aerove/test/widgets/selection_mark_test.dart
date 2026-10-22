import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/selection_mark.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Color _pixel(List<int> bytes, Offset point) {
  final index = (point.dy.floor() * 240 + point.dx.floor()) * 4;
  return Color.fromARGB(255, bytes[index], bytes[index + 1], bytes[index + 2]);
}

double _contrast(Color first, Color second) {
  final a = first.computeLuminance();
  final b = second.computeLuminance();
  return a > b ? (a + 0.05) / (b + 0.05) : (b + 0.05) / (a + 0.05);
}

void main() {
  setUpAll(() {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
  });
  testWidgets('None preserves badge geometry colors and preferred state', (
    tester,
  ) async {
    final theme = ThemeData();
    await tester.pumpWidget(
      MaterialApp(
        theme: theme,
        home: const LiquidGlassScope(
          enabled: false,
          child: Row(
            children: [
              SelectionBadge(size: 32),
              SelectionBadge.preferred(size: 32),
              SelectionBadge.preferred(size: 32, inUse: true),
            ],
          ),
        ),
      ),
    );
    final decorations = tester
        .widgetList<DecoratedBox>(
          find.descendant(
            of: find.byType(SelectionBadge),
            matching: find.byType(DecoratedBox),
          ),
        )
        .map((widget) => (widget.decoration as ShapeDecoration).color)
        .toList();
    expect(decorations, [
      theme.colorScheme.primary,
      theme.colorScheme.primary.withValues(alpha: 0.14),
      theme.colorScheme.primary,
    ]);
    for (final element in find.byType(SelectionBadge).evaluate()) {
      expect(tester.getSize(find.byWidget(element.widget)), const Size(32, 32));
    }
    final icons = tester.widgetList<GlyphIcon>(find.byType(GlyphIcon)).toList();
    expect(icons.map((icon) => icon.glyph), [
      AppGlyphs.check,
      AppGlyphs.pin,
      AppGlyphs.pin,
    ]);
    expect(icons.map((icon) => icon.color), [
      theme.colorScheme.onPrimary,
      theme.colorScheme.primary,
      theme.colorScheme.onPrimary,
    ]);
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('badge ink follows local moving field $brightness $quality', (
        tester,
      ) async {
        tester.view.physicalSize = const Size(240, 140);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        await tester.runAsync(GlassInk.prepare);
        final capture = GlobalKey();
        final theme = ThemeData(brightness: brightness);
        final backgrounds = brightness == Brightness.dark
            ? const [Color(0xFF173940), Color(0xFF3B203B)]
            : const [Color(0xFFBDE5EC), Color(0xFFEAD0E8)];
        Future<List<int>> render(bool enabled, bool moved) async {
          await tester.pumpWidget(
            MaterialApp(
              theme: theme,
              themeAnimationStyle: AnimationStyle.noAnimation,
              builder: (context, child) => RepaintBoundary(
                key: capture,
                child: LiquidGlassScope(
                  enabled: enabled,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: enabled,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Positioned.fill(
                          child: LiquidGlassColorField(
                            child: CustomPaint(
                              painter: _Fields(backgrounds, moved),
                            ),
                          ),
                        ),
                        child!,
                      ],
                    ),
                  ),
                ),
              ),
              home: const Scaffold(
                backgroundColor: Colors.transparent,
                body: Stack(
                  children: [
                    Positioned(
                      left: 40,
                      top: 40,
                      child: SelectionBadge(size: 48),
                    ),
                    Positioned(
                      left: 160,
                      top: 40,
                      child: SelectionBadge(size: 48),
                    ),
                  ],
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final bytes = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!.buffer.asUint8List();
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                Directory(output).createSync(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '$output/badge-${brightness.name}-${quality.name}-'
                  '$enabled-$moved.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return bytes;
            } finally {
              image.dispose();
            }
          }))!;
        }

        final ordinary = await render(false, false);
        final first = await render(true, false);
        final moved = await render(true, true);
        final samples = <Map<String, Object?>>[];
        for (var side = 0; side < 2; side++) {
          final point = Offset(64 + 120.0 * side, 45);
          expect(_pixel(ordinary, point), theme.colorScheme.primary);
          final ink = _pixel(first, point);
          expect(ink, isNot(theme.colorScheme.primary));
          expect(ink, isNot(_pixel(moved, point)));
          expect(_contrast(ink, backgrounds[side]), greaterThanOrEqualTo(3));
          final other = _pixel(first, Offset(64 + 120.0 * (1 - side), 45));
          expect(ink, isNot(other));
          var cutout = 0;
          for (var y = 52; y < 78; y++) {
            for (var x = 51; x < 77; x++) {
              if (_pixel(first, Offset(x + 120.0 * side, y.toDouble())) ==
                  backgrounds[side]) {
                cutout++;
              }
            }
          }
          expect(cutout, greaterThan(20));
          samples.add({
            'point': [point.dx, point.dy],
            'under': backgrounds[side].toARGB32(),
            'actual': ink.toARGB32(),
            'moved': _pixel(moved, point).toARGB32(),
            'contrast': _contrast(ink, backgrounds[side]),
            'cutoutPixels': cutout,
          });
        }
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          File(
            '$output/badge-${brightness.name}-${quality.name}-samples.json',
          ).writeAsStringSync(jsonEncode(samples));
        }
        expect(await render(false, false), ordinary);
        expect(tester.takeException(), isNull);
      }, skip: !ui.ImageFilter.isShaderFilterSupported);
    }
  }
}

class _Fields extends CustomPainter {
  const _Fields(this.colors, this.moved);
  final List<Color> colors;
  final bool moved;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = colors[moved ? 1 : 0]);
    canvas.drawRect(
      Rect.fromLTWH(size.width / 2, 0, size.width / 2, size.height),
      Paint()..color = colors[moved ? 0 : 1],
    );
  }

  @override
  bool shouldRepaint(_Fields oldDelegate) => oldDelegate.moved != moved;
}
