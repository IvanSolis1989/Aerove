import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Color _pixel(List<int> bytes, Offset point) {
  final index = (point.dy.floor() * 360 + point.dx.floor()) * 4;
  return Color.fromARGB(255, bytes[index], bytes[index + 1], bytes[index + 2]);
}

Future<List<int>> _snapshot(WidgetTester tester, GlobalKey key, {String? name}) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && name != null) {
        Directory(output).createSync(recursive: true);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        File('$output/ink-cache-$name.png').writeAsBytesSync(png!.buffer.asUint8List());
      }
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

void _record(String name, Map<String, Object?> values) {
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output == null) return;
  Directory(output).createSync(recursive: true);
  File('$output/ink-cache-$name.json').writeAsStringSync(jsonEncode(values));
}

Widget _scene({
  required GlobalKey capture,
  required ValueNotifier<Matrix4> pose,
  bool source = true,
  bool enabled = true,
  bool crossfade = false,
  Brightness brightness = Brightness.dark,
  int palette = 0,
  int seed = 0,
  double side = 800,
  Key? sourceKey,
}) {
  Widget mesh(int palette, {Key? key}) => SizedBox.square(
    dimension: side,
    child: source
        ? LiquidGlassColorSource(
            key: key,
            child: CustomPaint(painter: _Mesh(palette, seed, brightness)),
          )
        : RepaintBoundary(
            child: CustomPaint(painter: _Mesh(palette, seed, brightness)),
          ),
  );
  final background = crossfade
      ? Stack(
          fit: StackFit.expand,
          children: [
            mesh(palette),
            Opacity(opacity: 0.5, child: mesh(palette + 1)),
          ],
        )
      : mesh(palette, key: sourceKey);
  return MaterialApp(
    theme: ThemeData(brightness: brightness, fontFamily: 'Ahem'),
    themeAnimationStyle: AnimationStyle.noAnimation,
    home: RepaintBoundary(
      key: capture,
      child: LiquidGlassScope(
        enabled: enabled,
        child: LiquidGlassHost(
          enabled: enabled,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Positioned.fill(
                child: LiquidGlassColorField(
                  child: OverflowBox(
                    maxWidth: side,
                    maxHeight: side,
                    child: ValueListenableBuilder<Matrix4>(
                      valueListenable: pose,
                      child: SizedBox.square(
                        dimension: side,
                        child: background,
                      ),
                      builder: (context, matrix, child) => Transform(
                        alignment: Alignment.center,
                        transform: matrix,
                        child: child,
                      ),
                    ),
                  ),
                ),
              ),
              Positioned.fill(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Wrap(
                    spacing: 4,
                    runSpacing: 4,
                    children: [
                      for (var i = 0; i < 40; i++)
                        SizedBox(
                          width: 56,
                          height: 32,
                          child: i.isEven
                              ? GlassText(
                                  'H$i',
                                  inkRole: GlassInkRole.body,
                                  style: TextStyle(
                                    fontSize: 20,
                                    color: brightness == Brightness.dark
                                        ? Colors.white
                                        : Colors.black,
                                  ),
                                )
                              : GlyphIcon(
                                  AppGlyphs.dashboard,
                                  size: 24,
                                  color: brightness == Brightness.dark
                                      ? Colors.white
                                      : Colors.black,
                                ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare();
    }
  });

  for (final brightness in Brightness.values) {
    testWidgets('40 ink masks reuse the static source across 30 motion frames '
        '${brightness.name}', (tester) async {
      tester.view.physicalSize = const Size(360, 480);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final pose = ValueNotifier(Matrix4.identity());
      addTearDown(pose.dispose);
      final key = GlobalKey();
      await tester.pumpWidget(
        _scene(
          capture: key,
          pose: pose,
          brightness: brightness,
          enabled: false,
        ),
      );
      await tester.pumpAndSettle();
      final none = await _snapshot(tester, key);
      Offset? point;
      final sourceColor = brightness == Brightness.dark
          ? Colors.white
          : Colors.black;
      for (var y = 14; y < 38 && point == null; y++) {
        for (var x = 14; x < 40; x++) {
          final candidate = Offset(x + 0.5, y + 0.5);
          if (_pixel(none, candidate) == sourceColor &&
              _pixel(none, candidate + const Offset(1, 1)) == sourceColor) {
            point = candidate;
            break;
          }
        }
      }
      expect(point, isNotNull);
      await tester.pumpWidget(
        _scene(capture: key, pose: pose, brightness: brightness),
      );
      await tester.pumpAndSettle();
      final first = LiquidGlassHost.debugLastInkCapture!;
      expect(first.masks, 40);
      final initial = await _snapshot(tester, key);
      for (var frame = 1; frame <= 30; frame++) {
        pose.value = Matrix4.identity()
          ..translateByDouble(frame * 2.0, frame * 0.3, 0, 1)
          ..rotateZ(frame * 0.001);
        await tester.pump(const Duration(milliseconds: 16));
      }
      final after = LiquidGlassHost.debugLastInkCapture!;
      final moved = await _snapshot(tester, key, name: '${brightness.name}-source');
      _record(brightness.name, {
        'masks': after.masks,
        'sourceCaptureDelta': after.sourceCaptures - first.sourceCaptures,
        'fallbackCaptureDelta': after.fallbackCaptures - first.fallbackCaptures,
        'filterUpdateDelta': after.filterUpdates - first.filterUpdates,
        'maskPixels': after.maskPixels,
        'texturePixels': after.texturePixels,
        'sample': [point!.dx, point.dy],
        'initial': _pixel(initial, point).toARGB32(),
        'moved': _pixel(moved, point).toARGB32(),
      });
      expect(after.sourceCaptures, first.sourceCaptures);
      expect(after.fallbackCaptures, first.fallbackCaptures);
      expect(after.generation, first.generation);
      expect(after.filterUpdates, greaterThan(first.filterUpdates));
      expect(_pixel(initial, point), isNot(_pixel(none, point)));
      expect(_pixel(moved, point), isNot(_pixel(initial, point)));
      await tester.pump();
      final stable = LiquidGlassHost.debugLastInkCapture!;
      expect(stable.filterUpdates, after.filterUpdates);
      expect(await _snapshot(tester, key), moved);

      await tester.pumpWidget(
        _scene(capture: key, pose: pose, brightness: brightness, source: false),
      );
      await tester.pumpAndSettle();
      final fallback = await _snapshot(tester, key, name: '${brightness.name}-viewport');
      var maximumError = 0;
      var squaredError = 0.0;
      var worstIndex = 0;
      for (var i = 0; i < fallback.length; i++) {
        final error = (fallback[i] - moved[i]).abs();
        if (error > maximumError) {
          maximumError = error;
          worstIndex = i;
        }
        squaredError += error * error;
      }
      final rms = math.sqrt(squaredError / fallback.length);
      _record('${brightness.name}-reference', {
        'maxError': maximumError,
        'rms': rms,
        'point': [(worstIndex ~/ 4) % 360, worstIndex ~/ (360 * 4)],
        'sourceRgba': moved.sublist(worstIndex - worstIndex % 4, worstIndex - worstIndex % 4 + 4),
        'viewportRgba': fallback.sublist(worstIndex - worstIndex % 4, worstIndex - worstIndex % 4 + 4),
      });
      expect(maximumError, lessThanOrEqualTo(6));
      expect(rms, lessThanOrEqualTo(1.5));
      final beforeFallbackMotion = LiquidGlassHost.debugLastInkCapture!;
      pose.value = Matrix4.identity()..translateByDouble(72, 12, 0, 1);
      await tester.pump();
      expect(
        LiquidGlassHost.debugLastInkCapture!.fallbackCaptures,
        greaterThan(beforeFallbackMotion.fallbackCaptures),
      );
      expect(tester.takeException(), isNull);
    }, skip: !ui.ImageFilter.isShaderFilterSupported);
  }

  testWidgets(
    'source changes recapture and crossfade falls back without stale ink',
    (tester) async {
      tester.view.physicalSize = const Size(360, 480);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final pose = ValueNotifier(Matrix4.identity());
      addTearDown(pose.dispose);
      final key = GlobalKey();
      Future<List<int>> render({
        int palette = 0,
        int seed = 0,
        Brightness brightness = Brightness.dark,
        double side = 800,
        bool crossfade = false,
        Key? sourceKey,
      }) async {
        await tester.pumpWidget(
          _scene(
            capture: key,
            pose: pose,
            palette: palette,
            seed: seed,
            brightness: brightness,
            side: side,
            crossfade: crossfade,
            sourceKey: sourceKey,
          ),
        );
        await tester.pumpAndSettle();
        return _snapshot(tester, key);
      }

      var pixels = await render();
      var stats = LiquidGlassHost.debugLastInkCapture!;
      for (final change in [
        () => render(palette: 1),
        () => render(seed: 2),
        () => render(brightness: Brightness.light),
        () => render(side: 900),
        () => render(sourceKey: const ValueKey('replacement')),
      ]) {
        final next = await change();
        final nextStats = LiquidGlassHost.debugLastInkCapture!;
        expect(nextStats.sourceCaptures, greaterThan(stats.sourceCaptures));
        expect(nextStats.generation, greaterThan(stats.generation));
        expect(next, isNot(pixels));
        pixels = next;
        stats = nextStats;
      }
      final crossfade = await render(crossfade: true);
      final blended = LiquidGlassHost.debugLastInkCapture!;
      expect(blended.fallbackCaptures, greaterThan(stats.fallbackCaptures));
      expect(crossfade, isNot(pixels));
      await render();
      expect(
        LiquidGlassHost.debugLastInkCapture!.sourceCaptures,
        greaterThan(blended.sourceCaptures),
      );
      expect(tester.takeException(), isNull);
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );
}

class _Mesh extends CustomPainter {
  const _Mesh(this.palette, this.seed, this.brightness);
  final int palette;
  final int seed;
  final Brightness brightness;

  @override
  void paint(Canvas canvas, Size size) {
    final dark = brightness == Brightness.dark;
    final colors = dark
        ? [
            const Color(0xFF17404A),
            const Color(0xFF492049),
            const Color(0xFF234B3F),
          ]
        : [
            const Color(0xFFC0E6EC),
            const Color(0xFFE9CEE8),
            const Color(0xFFC6E8D4),
          ];
    final shift = (palette + seed) % colors.length;
    final vertices = ui.Vertices(
      ui.VertexMode.triangles,
      [
        Offset.zero,
        Offset(size.width, 0),
        Offset(0, size.height),
        Offset(size.width, 0),
        Offset(size.width, size.height),
        Offset(0, size.height),
      ],
      colors: [
        for (final i in [0, 1, 2, 1, 0, 2]) colors[(i + shift) % colors.length],
      ],
    );
    canvas.drawVertices(vertices, BlendMode.dst, Paint());
    vertices.dispose();
  }

  @override
  bool shouldRepaint(_Mesh oldDelegate) =>
      oldDelegate.palette != palette ||
      oldDelegate.seed != seed ||
      oldDelegate.brightness != brightness;
}
