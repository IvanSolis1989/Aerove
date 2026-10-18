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

Future<List<int>> _snapshot(
  WidgetTester tester,
  GlobalKey key, {
  String? name,
}) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && name != null) {
        Directory(output).createSync(recursive: true);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        File(
          '$output/ink-cache-$name.png',
        ).writeAsBytesSync(png!.buffer.asUint8List());
      }
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

typedef _FieldProbe = ({
  List<int> bytes,
  int width,
  int height,
  Size size,
  Matrix4 inkToField,
  Matrix4 inkToRoot,
});

Future<_FieldProbe> _fieldProbe(WidgetTester tester) async {
  LiquidGlassInkLayer? ink;
  Matrix4? inkToRoot;
  void visit(ContainerLayer parent, Matrix4 toRoot) {
    for (
      Layer? child = parent.firstChild;
      child != null;
      child = child.nextSibling
    ) {
      if (child is! ContainerLayer) continue;
      final next = toRoot.clone();
      if (child is OffsetLayer) {
        next.translateByDouble(child.offset.dx, child.offset.dy, 0, 1);
      }
      if (child is TransformLayer && child.transform != null) {
        next.multiply(child.transform!);
      }
      if (child is LiquidGlassInkLayer && ink == null) {
        ink = child;
        inkToRoot = next;
      }
      visit(child, next);
    }
  }

  visit(tester.binding.renderViews.first.debugLayer!, Matrix4.identity());
  final field = ink!.debugField!;
  final bytes = (await tester.runAsync(
    () => field.$1.toByteData(format: ui.ImageByteFormat.rawRgba),
  ))!;
  return (
    bytes: bytes.buffer.asUint8List().toList(),
    width: field.$1.width,
    height: field.$1.height,
    size: field.$2,
    inkToField: field.$3,
    inkToRoot: inkToRoot!,
  );
}

Offset _sourcePoint(_FieldProbe field, Offset point) {
  final local = MatrixUtils.transformPoint(
    Matrix4.inverted(field.inkToRoot),
    point,
  );
  return MatrixUtils.transformPoint(field.inkToField, local);
}

List<double> _bilinear(_FieldProbe field, Offset source) {
  final x =
      (source.dx / field.size.width).clamp(0.0, 1.0).toDouble() * field.width -
      0.5;
  final y =
      (source.dy / field.size.height).clamp(0.0, 1.0).toDouble() *
          field.height -
      0.5;
  final left = x.floor(), top = y.floor();
  final dx = x - left, dy = y - top;
  double at(int column, int row, int channel) => field
      .bytes[((row.clamp(0, field.height - 1).toInt() * field.width +
                  column.clamp(0, field.width - 1).toInt()) *
              4) +
          channel]
      .toDouble();
  return [
    for (var channel = 0; channel < 4; channel++)
      at(left, top, channel) * (1 - dx) * (1 - dy) +
          at(left + 1, top, channel) * dx * (1 - dy) +
          at(left, top + 1, channel) * (1 - dx) * dy +
          at(left + 1, top + 1, channel) * dx * dy,
  ];
}

Map<String, Object> _sampleField(_FieldProbe field, Offset point) {
  final source = _sourcePoint(field, point);
  return {
    'logicalPoint': [source.dx, source.dy],
    'imageSize': [field.width, field.height],
    'rgba': _bilinear(field, source),
  };
}

Matrix4 _referenceSourceMapping(Matrix4 pose) {
  final toRoot = Matrix4.translationValues(-220, -160, 0)
    ..translateByDouble(400, 400, 0, 1)
    ..multiply(pose)
    ..translateByDouble(-400, -400, 0, 1);
  return Matrix4.inverted(toRoot);
}

List<double> _referenceMesh(Offset source, Brightness brightness) {
  final colors = brightness == Brightness.dark
      ? <List<double>>[
          [23, 64, 74],
          [73, 32, 73],
          [35, 75, 63],
        ]
      : <List<double>>[
          [192, 230, 236],
          [233, 206, 232],
          [198, 232, 212],
        ];
  final u = source.dx / 800, v = source.dy / 800;
  return [
    for (var channel = 0; channel < 3; channel++)
      u + v <= 1
          ? colors[0][channel] * (1 - u - v) +
                colors[1][channel] * u +
                colors[2][channel] * v
          : colors[1][channel] * (1 - v) +
                colors[0][channel] * (u + v - 1) +
                colors[2][channel] * (1 - u),
  ];
}

double _luminance(List<double> rgb) {
  double linear(double value) => value <= 0.04045
      ? value / 12.92
      : math.pow((value + 0.055) / 1.055, 2.4).toDouble();
  return linear(rgb[0]) * 0.2126 +
      linear(rgb[1]) * 0.7152 +
      linear(rgb[2]) * 0.0722;
}

double _contrast(List<double> a, List<double> b) {
  final x = _luminance(a), y = _luminance(b);
  return (math.max(x, y) + 0.05) / (math.min(x, y) + 0.05);
}

List<int> _referenceBodyInk(List<double> rgba, Brightness brightness) {
  final background = [for (var i = 0; i < 3; i++) rgba[i] / 255];
  final high = background.reduce(math.max), low = background.reduce(math.min);
  final dark = brightness == Brightness.dark;
  List<double> readable(List<double> rgb) {
    if (_contrast(rgb, background) >= 4.7) return rgb;
    final light = _luminance(background);
    final pole = (light + 0.05) / 0.05 > 1.05 / (light + 0.05) ? 0.0 : 1.0;
    var from = 0.0, to = 1.0;
    for (var step = 0; step < 8; step++) {
      final amount = (from + to) / 2;
      final candidate = [
        for (final value in rgb) value + (pole - value) * amount,
      ];
      if (_contrast(candidate, background) >= 4.7) {
        to = amount;
      } else {
        from = amount;
      }
    }
    return [for (final value in rgb) value + (pole - value) * to];
  }

  var color = background;
  if (high - low >= 0.015) {
    final saturation = math.min(
      dark ? 0.92 : 0.97,
      (high - low) / math.max(high, 0.001) * (dark ? 2.70 : 3.1) +
          (dark ? 0.175 : 0.22),
    );
    final peak = dark ? 0.95 : 0.97;
    color = [
      for (final value in background)
        peak * (1 - saturation + (value - low) / (high - low) * saturation),
    ];
  }
  color = readable(color);
  color = readable([
    for (final value in color) (dark ? 0.08 : 0.0) + value * 0.92,
  ]);
  return [for (final value in color) (value * 255).round()];
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
      final bodyRects = [
        for (var i = 0; i < 40; i += 2) tester.getRect(find.text('H$i')),
      ];
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
      final moved = await _snapshot(
        tester,
        key,
        name: '${brightness.name}-source',
      );
      final sourceField = await _fieldProbe(tester);
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
      final fallback = await _snapshot(
        tester,
        key,
        name: '${brightness.name}-viewport',
      );
      final viewportField = await _fieldProbe(tester);
      var maximumError = 0;
      var squaredError = 0.0;
      var worstIndex = 0;
      var frameAlphaDifferences = 0;
      for (var i = 0; i < fallback.length; i++) {
        final error = (fallback[i] - moved[i]).abs();
        if (error > maximumError) {
          maximumError = error;
          worstIndex = i;
        }
        squaredError += error * error;
        if (i % 4 == 3 && error != 0) frameAlphaDifferences++;
      }
      final rms = math.sqrt(squaredError / fallback.length);
      final expectedMapping = _referenceSourceMapping(pose.value);
      final actualMapping = sourceField.inkToField.clone()
        ..multiply(Matrix4.inverted(sourceField.inkToRoot));
      final viewportMapping = viewportField.inkToField.clone()
        ..multiply(Matrix4.inverted(viewportField.inkToRoot));
      var maximumUvError = 0.0, maximumRawError = 0.0;
      var maximumInputDifference = 0.0;
      var sourceError = 0, viewportError = 0;
      var sourceSquared = 0.0, viewportSquared = 0.0;
      var sourceContrast = double.infinity, viewportContrast = double.infinity;
      var supported = 0, alphaLoss = 0, missingSource = 0, missingViewport = 0;
      final seen = <int>{};
      for (final rect in bodyRects) {
        for (var y = rect.top.ceil(); y < rect.bottom.floor(); y++) {
          for (var x = rect.left.ceil(); x < rect.right.floor(); x++) {
            final index = (y * 360 + x) * 4;
            final sample = Offset(x + 0.5, y + 0.5);
            if (!seen.add(index) || _pixel(none, sample) != sourceColor)
              continue;
            supported++;
            final expectedSource = MatrixUtils.transformPoint(
              expectedMapping,
              sample,
            );
            final actualSource = MatrixUtils.transformPoint(
              actualMapping,
              sample,
            );
            final actualViewport = MatrixUtils.transformPoint(
              viewportMapping,
              sample,
            );
            maximumUvError = math.max(
              maximumUvError,
              math.max(
                (actualSource - expectedSource).distance,
                (actualViewport - sample).distance,
              ),
            );
            final cachedInput = _bilinear(sourceField, expectedSource);
            final viewportInput = _bilinear(viewportField, sample);
            final analytic = _referenceMesh(expectedSource, brightness);
            final sourceExpected = _referenceBodyInk(cachedInput, brightness);
            final viewportExpected = _referenceBodyInk(
              viewportInput,
              brightness,
            );
            final cachedActual = <double>[], viewportActual = <double>[];
            var sourceDistance = 0.0, viewportDistance = 0.0;
            for (var channel = 0; channel < 3; channel++) {
              maximumRawError = math.max(
                maximumRawError,
                math.max(
                  (cachedInput[channel] - analytic[channel]).abs(),
                  (viewportInput[channel] - analytic[channel]).abs(),
                ),
              );
              maximumInputDifference = math.max(
                maximumInputDifference,
                (cachedInput[channel] - viewportInput[channel]).abs(),
              );
              final a = (moved[index + channel] - sourceExpected[channel])
                  .abs();
              final b = (fallback[index + channel] - viewportExpected[channel])
                  .abs();
              sourceError = math.max(sourceError, a);
              viewportError = math.max(viewportError, b);
              sourceSquared += a * a;
              viewportSquared += b * b;
              cachedActual.add(moved[index + channel] / 255);
              viewportActual.add(fallback[index + channel] / 255);
              sourceDistance = math.max(
                sourceDistance,
                (moved[index + channel] - cachedInput[channel]).abs() / 255,
              );
              viewportDistance = math.max(
                viewportDistance,
                (fallback[index + channel] - viewportInput[channel]).abs() /
                    255,
              );
            }
            if (moved[index + 3] != none[index + 3] ||
                fallback[index + 3] != none[index + 3])
              alphaLoss++;
            if (sourceDistance < 0.05) missingSource++;
            if (viewportDistance < 0.05) missingViewport++;
            sourceContrast = math.min(
              sourceContrast,
              _contrast(cachedActual, [
                for (var i = 0; i < 3; i++) cachedInput[i] / 255,
              ]),
            );
            viewportContrast = math.min(
              viewportContrast,
              _contrast(viewportActual, [
                for (var i = 0; i < 3; i++) viewportInput[i] / 255,
              ]),
            );
          }
        }
      }
      final sourceRms = math.sqrt(sourceSquared / (supported * 3));
      final viewportRms = math.sqrt(viewportSquared / (supported * 3));
      _record('${brightness.name}-reference', {
        'maxError': maximumError,
        'rms': rms,
        'comparison':
            'cross-representation report; each input has its own strict CPU oracle',
        'sameInputSource': {
          'maximum': sourceError,
          'rms': sourceRms,
          'minimumContrast': sourceContrast,
        },
        'sameInputViewport': {
          'maximum': viewportError,
          'rms': viewportRms,
          'minimumContrast': viewportContrast,
        },
        'sourceUvMaximumErrorDp': maximumUvError,
        'meshRawMaximumErrorLevels': maximumRawError,
        'rawRepresentationsMaximumDifferenceLevels': maximumInputDifference,
        'opaqueBodyPixels': supported,
        'alphaLoss': alphaLoss,
        'frameAlphaDifferences': frameAlphaDifferences,
        'missingSource': missingSource,
        'missingViewport': missingViewport,
        'sourceField': _sampleField(
          sourceField,
          Offset((worstIndex ~/ 4) % 360 + 0.5, worstIndex ~/ (360 * 4) + 0.5),
        ),
        'viewportField': _sampleField(
          viewportField,
          Offset((worstIndex ~/ 4) % 360 + 0.5, worstIndex ~/ (360 * 4) + 0.5),
        ),
        'point': [(worstIndex ~/ 4) % 360, worstIndex ~/ (360 * 4)],
        'sourceRgba': moved.sublist(
          worstIndex - worstIndex % 4,
          worstIndex - worstIndex % 4 + 4,
        ),
        'viewportRgba': fallback.sublist(
          worstIndex - worstIndex % 4,
          worstIndex - worstIndex % 4 + 4,
        ),
      });
      expect(sourceField.size, const Size(800, 800));
      expect(viewportField.size, const Size(360, 480));
      expect(supported, greaterThan(100));
      expect(maximumUvError, lessThanOrEqualTo(1e-6));
      expect(maximumRawError, lessThanOrEqualTo(1));
      expect(maximumInputDifference, lessThanOrEqualTo(2));
      expect(sourceError, lessThanOrEqualTo(6));
      expect(viewportError, lessThanOrEqualTo(6));
      expect(sourceRms, lessThanOrEqualTo(1.5));
      expect(viewportRms, lessThanOrEqualTo(1.5));
      expect(alphaLoss, 0);
      expect(frameAlphaDifferences, 0);
      expect(missingSource, 0);
      expect(missingViewport, 0);
      expect(sourceContrast, greaterThanOrEqualTo(4.7));
      expect(viewportContrast, greaterThanOrEqualTo(4.7));
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
