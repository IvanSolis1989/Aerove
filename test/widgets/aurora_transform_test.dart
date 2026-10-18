import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/common/aurora_motion.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _palette = [Color(0xFF65BDD2), Color(0xFFC589CA), Color(0xFF75CCA8)];

void main() {
  testWidgets('wallpaper matches the original canvas motion at fixed times', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1000, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final key = GlobalKey();
    final metrics = <String, Object>{};
    for (final brightness in Brightness.values) {
      for (final size in [
        const Size(390, 844),
        const Size(844, 390),
        const Size(720, 720),
      ]) {
        for (final seconds in [0, 3, 5]) {
          final time = Duration(seconds: seconds);
          final name =
              'aurora-transform-${brightness.name}-'
              '${size.width.toInt()}x${size.height.toInt()}-$seconds';
          await tester.pumpWidget(
            _scene(
              key,
              size: size,
              child: SeededAuroraWallpaper(
                colors: _palette,
                seed: 832838199,
                brightness: brightness,
                motionTime: time,
              ),
            ),
          );
          final actual = await _pixels(tester, key, '$name-actual');
          await tester.pumpWidget(
            _scene(
              key,
              size: size,
              child: _CanvasReference(brightness: brightness, time: time),
            ),
          );
          final expected = await _pixels(tester, key, '$name-reference');
          var maximum = 0;
          var total = 0;
          var samples = 0;
          for (var i = 0; i < actual.length; i++) {
            if (i % 4 == 3) {
              expect(actual[i], 255);
            } else {
              final difference = (actual[i] - expected[i]).abs();
              maximum = math.max(maximum, difference);
              total += difference;
              samples++;
            }
          }
          metrics[name] = {
            'maximumChannelDifference': maximum,
            'meanChannelDifference': total / samples,
          };
          expect(maximum, lessThanOrEqualTo(2), reason: name);
          expect(tester.binding.hasScheduledFrame, isFalse);
          expect(tester.takeException(), isNull);
        }
      }
    }
    await tester.runAsync(
      () => _export('aurora-transform-pixels.json', metrics),
    );
  });

  testWidgets('motion retains its static picture without rebuilding content', (
    tester,
  ) async {
    final key = GlobalKey();
    var builds = 0;
    Widget wallpaper() => Builder(
      builder: (_) {
        builds++;
        return const SeededAuroraWallpaper(
          colors: _palette,
          seed: 832838199,
          brightness: Brightness.light,
          motion: true,
        );
      },
    );
    await tester.pumpWidget(_scene(key, child: wallpaper()));
    await tester.pump();
    final originalBuilds = builds;
    final original = await _pixels(tester, key, 'aurora-transform-live-0');
    final pictures = Set<ui.Picture>.identity()..addAll(_pictures(tester));
    final initialPictures = pictures.length;
    for (var frame = 0; frame < 125; frame++) {
      await tester.pump(const Duration(milliseconds: 8));
      pictures.addAll(_pictures(tester));
    }
    final changed = await _pixels(tester, key, 'aurora-transform-live-1');
    expect(changed, isNot(original));
    expect(builds, originalBuilds);
    expect(initialPictures, greaterThan(0));
    expect(pictures.length - initialPictures, 0);
    await tester.runAsync(
      () => _export('aurora-transform-picture-cadence.json', {
        'initialPictures': initialPictures,
        'newPicturesInOneSecond': pictures.length - initialPictures,
        'backgroundBuildsDuringMotion': builds - originalBuilds,
      }),
    );
    for (final (reduced, ticker) in [(true, true), (false, false)]) {
      await tester.pumpWidget(
        _scene(key, reduced: reduced, ticker: ticker, child: wallpaper()),
      );
      final paused = await _pixels(tester, key, null);
      final heldPictures = _pictures(tester);
      await tester.pump(const Duration(seconds: 5));
      expect(await _pixels(tester, key, null), paused);
      expect(_pictures(tester), heldPictures);
      expect(tester.binding.hasScheduledFrame, isFalse);
    }
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.takeException(), isNull);
  });

  testWidgets('the square source survives poses and replaces changed content', (
    tester,
  ) async {
    final key = GlobalKey();
    Future<void> pump({
      Size size = const Size(180, 300),
      int seed = 832838199,
      Brightness brightness = Brightness.light,
      List<Color> colors = _palette,
      Duration time = Duration.zero,
    }) => tester.pumpWidget(
      _scene(
        key,
        size: size,
        child: SeededAuroraWallpaper(
          colors: colors,
          seed: seed,
          brightness: brightness,
          motionTime: time,
        ),
      ),
    );

    await pump();
    expect(
      tester.getSize(find.byType(LiquidGlassColorSource)),
      const Size.square(300),
    );
    final original = _pictures(tester);
    await pump(time: const Duration(seconds: 5));
    expect(_pictures(tester), original);
    await pump(size: const Size(300, 180));
    expect(_pictures(tester), original);
    var previous = await _pixels(tester, key, null);
    for (final (seed, brightness, colors) in [
      (624302977, Brightness.light, _palette),
      (624302977, Brightness.dark, _palette),
      (624302977, Brightness.dark, _palette.reversed.toList()),
    ]) {
      final oldPictures = _pictures(tester);
      await pump(seed: seed, brightness: brightness, colors: colors);
      final pixels = await _pixels(tester, key, null);
      expect(pixels, isNot(previous));
      expect(_pictures(tester), isNot(oldPictures));
      previous = pixels;
    }
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.takeException(), isNull);
  });
}

List<ui.Picture> _pictures(WidgetTester tester) {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.descendant(
      of: find.byType(SeededAuroraWallpaper),
      matching: find.byType(RepaintBoundary),
    ),
  );
  final pictures = <ui.Picture>[];
  void visit(ContainerLayer parent) {
    for (
      Layer? layer = parent.firstChild;
      layer != null;
      layer = layer.nextSibling
    ) {
      if (layer is PictureLayer && layer.picture != null) {
        pictures.add(layer.picture!);
      }
      if (layer is ContainerLayer) visit(layer);
    }
  }

  visit(boundary.debugLayer!);
  return pictures;
}

Widget _scene(
  GlobalKey key, {
  Size size = const Size(180, 300),
  bool reduced = false,
  bool ticker = true,
  required Widget child,
}) => MediaQuery(
  data: MediaQueryData(disableAnimations: reduced),
  child: Directionality(
    textDirection: TextDirection.ltr,
    child: TickerMode(
      enabled: ticker,
      child: Center(
        child: RepaintBoundary(
          key: key,
          child: SizedBox.fromSize(size: size, child: child),
        ),
      ),
    ),
  ),
);

Future<Uint8List> _pixels(
  WidgetTester tester,
  GlobalKey key,
  String? name,
) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData())!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && name != null) {
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

Future<void> _export(String name, Map<String, Object> data) async {
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output == null) return;
  await Directory(output).create(recursive: true);
  await File(
    '$output/$name',
  ).writeAsString(const JsonEncoder.withIndent('  ').convert(data));
}

class _CanvasReference extends StatefulWidget {
  const _CanvasReference({required this.brightness, required this.time});
  final Brightness brightness;
  final Duration time;

  @override
  State<_CanvasReference> createState() => _CanvasReferenceState();
}

class _CanvasReferenceState extends State<_CanvasReference> {
  late final _CanvasPainter _painter = _CanvasPainter(
    widget.brightness,
    widget.time,
  );

  @override
  Widget build(BuildContext context) =>
      CustomPaint(painter: _painter, child: const SizedBox.expand());

  @override
  void dispose() {
    _painter.vertices.dispose();
    super.dispose();
  }
}

class _CanvasPainter extends CustomPainter {
  _CanvasPainter(Brightness brightness, this.time) {
    final mesh = generateAuroraMesh(
      832838199,
      colors: _palette,
      brightness: brightness,
    );
    vertices = ui.Vertices(
      ui.VertexMode.triangles,
      mesh.positions,
      colors: mesh.colors,
      indices: mesh.indices,
    );
  }

  late final ui.Vertices vertices;
  final Duration time;

  @override
  void paint(Canvas canvas, Size size) {
    final pose = AuroraMotion(832838199).at(time, size);
    canvas
      ..save()
      ..clipRect(Offset.zero & size)
      ..translate(
        size.width / 2 + pose.translation.dx,
        size.height / 2 + pose.translation.dy,
      )
      ..rotate(pose.angle)
      ..scale(pose.side, pose.side)
      ..translate(-0.5, -0.5)
      ..drawVertices(vertices, BlendMode.dst, Paint()..isAntiAlias = false)
      ..restore();
  }

  @override
  bool shouldRepaint(_CanvasPainter oldDelegate) => false;
}
