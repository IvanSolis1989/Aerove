import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _scene = ValueKey('capture-work-scene');
const _red = Color(0xFFFF0000);
const _blue = Color(0xFF0000FF);
const _green = Color(0xFF00FF00);
const _clear = LiquidGlassStyle(
  tint: Colors.transparent,
  blur: 0,
  refraction: 0,
  bevel: 0,
  rim: 0,
);

Widget _pane(Rect rect) => Positioned.fromRect(
  rect: rect,
  child: const LiquidGlass(
    style: _clear,
    clipper: ShapeBorderClipper(shape: AppShape.none),
  ),
);

Widget _app(Widget child) => MaterialApp(
  home: RepaintBoundary(
    key: _scene,
    child: LiquidGlassScope(
      enabled: true,
      quality: LiquidGlassQuality.ultra,
      child: LiquidGlassHost(enabled: true, wallpaperOnly: true, child: child),
    ),
  ),
);

Widget _wallpaper({Color color = _red, double shift = 0}) =>
    LiquidGlassColorField(
      child: ClipRect(
        child: OverflowBox(
          alignment: Alignment.topLeft,
          minWidth: 640,
          maxWidth: 640,
          child: Transform.translate(
            offset: Offset(-shift, 0),
            child: LiquidGlassColorSource(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(child: ColoredBox(color: color)),
                  const Expanded(child: ColoredBox(color: _blue)),
                ],
              ),
            ),
          ),
        ),
      ),
    );

Widget _bands({Color color = _red, double shift = 0, double move = 0}) => _app(
  Stack(
    fit: StackFit.expand,
    children: [
      _wallpaper(color: color, shift: shift),
      for (var index = 0; index < 6; index++)
        _pane(
          Rect.fromLTWH(20 + (index == 0 ? move : 0), 16 + index * 34, 80, 24),
        ),
    ],
  ),
);

Map<String, int> _counts(LiquidGlassCapture capture) => {
  'snapshots': capture.snapshots,
  'keptBands': capture.keptBands,
  'bandPicturesRecorded': capture.bandPicturesRecorded,
  'wallpaperDrawsRecorded': capture.wallpaperDrawsRecorded,
  'coverageGroupings': capture.coverageGroupings,
  'coverageModeChecks': capture.coverageModeChecks,
  'entryInversions': capture.entryInversions,
  'invertedEntries': capture.invertedEntries,
  'clippedPanes': capture.clippedPanes,
  'occludedPanes': capture.occludedPanes,
  'panes': capture.panes.length,
};

Future<List<int>> _shot(
  WidgetTester tester,
  String tag,
  LiquidGlassCapture capture,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_scene),
  );
  final layer = boundary.debugLayer! as OffsetLayer;
  final size = boundary.size;
  return (await tester.runAsync(() async {
    final transform = Matrix4.translationValues(
      -layer.offset.dx,
      -layer.offset.dy,
      0,
    );
    final builder = ui.SceneBuilder()..pushTransform(transform.storage);
    final scene = layer.buildScene(builder);
    final ui.Image image;
    try {
      image = await scene.toImage(size.width.round(), size.height.round());
    } finally {
      scene.dispose();
    }
    try {
      final raw = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        File('$output/capture-work-$tag.rgba').writeAsBytesSync(raw);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/capture-work-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
        File('$output/capture-work-$tag.json').writeAsStringSync(
          jsonEncode({
            'width': image.width,
            'height': image.height,
            'impeller': ui.ImageFilter.isShaderFilterSupported,
            ..._counts(capture),
          }),
        );
      }
      return raw.toList();
    } finally {
      image.dispose();
    }
  }))!;
}

Future<void> _settle(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  for (var frame = 0; frame < 4; frame++) {
    tester.binding.scheduleForcedFrame();
    await tester.pump(const Duration(milliseconds: 16));
  }
}

List<int> _pixel(List<int> bytes, int x, int y) {
  final offset = (y * 320 + x) * 4;
  return bytes.sublist(offset, offset + 4);
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });
  setUp(() => LiquidGlassHost.debugLastCapture = null);

  for (final carry in [true, false]) {
    testWidgets('wallpaper band work follows complete keys carry=$carry', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 240);
      tester.view.devicePixelRatio = 1;
      LiquidGlassHost.debugCarryBandsOverride = carry;
      addTearDown(tester.view.reset);
      addTearDown(() => LiquidGlassHost.debugCarryBandsOverride = null);
      final captures = <String, LiquidGlassCapture>{};
      final pixels = <String, List<int>>{};
      Future<void> save(String tag) async {
        final capture = LiquidGlassHost.debugLastCapture!;
        captures[tag] = capture;
        pixels[tag] = await _shot(tester, 'bands-$carry-$tag', capture);
      }

      await tester.pumpWidget(_bands());
      await _settle(tester);
      await save('initial');
      tester.binding.scheduleForcedFrame();
      await tester.pump();
      await save('stable');
      await tester.pumpWidget(_bands(move: 16));
      await save('slot');
      await tester.pumpWidget(_bands(move: 16, shift: 320));
      await save('transform');
      await tester.pumpWidget(_bands(move: 16));
      await save('transform-restored');
      await tester.pumpWidget(_bands(move: 16, color: _green));
      await save('source');

      expect(pixels['stable'], pixels['initial']);
      expect(_pixel(pixels['initial']!, 60, 26), [255, 0, 0, 255]);
      expect(_pixel(pixels['transform']!, 60, 26), [0, 0, 255, 255]);
      expect(_pixel(pixels['source']!, 60, 26), [0, 255, 0, 255]);
      expect(captures['slot']!.snapshots, 1);
      expect(captures['slot']!.keptBands, carry ? 5 : 0);
      expect(captures['transform']!.snapshots, 1);
      expect(pixels['transform-restored'], pixels['slot']);
      expect(captures['source']!.snapshots, 1);
      expect(captures['stable']!.snapshots, 0);
      expect(captures['stable']!.bandPicturesRecorded, 0);
      expect(captures['stable']!.wallpaperDrawsRecorded, 0);
      expect(captures['slot']!.bandPicturesRecorded, carry ? 1 : 6);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('flat snapshot content invalidates its wallpaper band', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    Widget scene(Color color) => _app(
      Stack(
        fit: StackFit.expand,
        children: [
          _wallpaper(),
          Positioned.fill(
            child: RepaintBoundary(child: ColoredBox(color: color)),
          ),
          Positioned.fill(
            child: LiquidGlassOcclusion(
              shape: AppShape.none,
              child: Stack(
                fit: StackFit.expand,
                children: [_pane(const Rect.fromLTWH(40, 40, 100, 80))],
              ),
            ),
          ),
        ],
      ),
    );
    await tester.pumpWidget(scene(_blue));
    await _settle(tester);
    final initial = LiquidGlassHost.debugLastCapture!;
    final initialPixels = await _shot(tester, 'flat-initial', initial);
    tester.binding.scheduleForcedFrame();
    await tester.pump();
    final stable = LiquidGlassHost.debugLastCapture!;
    final stablePixels = await _shot(tester, 'flat-stable', stable);
    await tester.pumpWidget(scene(_green));
    final changed = LiquidGlassHost.debugLastCapture!;
    final changedPixels = await _shot(tester, 'flat-changed', changed);
    expect(stablePixels, initialPixels);
    expect(_pixel(initialPixels, 80, 80), [0, 0, 255, 255]);
    expect(_pixel(changedPixels, 80, 80), [0, 255, 0, 255]);
    expect(changed.snapshots, 1);
    expect(changed.bandPicturesRecorded, 1);
    expect(stable.snapshots, 0);
    expect(stable.bandPicturesRecorded, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('coverage groups once and inverts each affected entry once', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    Widget scene({
      double coverTop = 0,
      bool reverse = false,
      bool plain = true,
    }) {
      final covers = <Widget>[
        Positioned(
          left: 0,
          top: coverTop,
          width: 150,
          height: 42,
          child: LiquidGlassOcclusion(
            shape: AppShape.none,
            bottomFeather: 12,
            captureContent: false,
            mode: plain
                ? LiquidGlassOcclusionMode.plain
                : LiquidGlassOcclusionMode.freeze,
            child: const SizedBox.expand(),
          ),
        ),
        const Positioned(
          left: 150,
          top: 0,
          width: 170,
          height: 32,
          child: LiquidGlassOcclusion(
            shape: AppShape.none,
            bottomFeather: 16,
            captureContent: false,
            child: SizedBox.expand(),
          ),
        ),
      ];
      return _app(
        Stack(
          fit: StackFit.expand,
          children: [
            _wallpaper(),
            _pane(const Rect.fromLTWH(20, 20, 260, 80)),
            _pane(const Rect.fromLTWH(24, 24, 260, 80)),
            _pane(const Rect.fromLTWH(28, 28, 260, 80)),
            ...reverse ? covers.reversed : covers,
          ],
        ),
      );
    }

    final captures = <LiquidGlassCapture>[];
    for (final state in ['initial', 'moved', 'reordered', 'mode', 'restored']) {
      await tester.pumpWidget(
        scene(
          coverTop: state == 'moved' ? 48 : 0,
          reverse: state == 'reordered',
          plain: state != 'mode',
        ),
      );
      await _settle(tester);
      final capture = LiquidGlassHost.debugLastCapture!;
      captures.add(capture);
      await _shot(tester, 'coverage-$state', capture);
    }
    expect(captures.every((capture) => capture.clippedPanes == 3), isTrue);
    expect(captures.first.invertedEntries, 3);
    for (final capture in captures) {
      expect(capture.entryInversions, greaterThan(0));
      expect(capture.coverageGroupings, 1);
      expect(capture.coverageModeChecks, 4);
      expect(
        capture.entryInversions,
        lessThanOrEqualTo(capture.invertedEntries),
      );
    }
    expect(tester.takeException(), isNull);
  });
}
