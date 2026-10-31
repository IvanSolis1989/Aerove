import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _sceneKey = ValueKey('ink-idle-work-scene');
const _colors = [
  Color(0xFF204060),
  Color(0xFF604020),
  Color(0xFF16353F),
  Color(0xFF38283D),
  Color(0xFF285834),
];
const LiquidGlassInkCapture _emptyCapture = (
  generation: 0,
  masks: 0,
  maskPixels: 0,
  texturePixels: 0,
  sourceCaptures: 0,
  fallbackCaptures: 0,
  filterUpdates: 0,
  frozenMasks: 0,
  skippedMasks: 0,
  skippedBackdrops: 0,
  sourceSearches: 0,
  sourceLayerVisits: 0,
  sourceMappingInversions: 0,
);

typedef _Observation = ({
  LiquidGlassInkCapture before,
  LiquidGlassInkCapture after,
  LiquidGlassCapture body,
  List<int>? fieldPixel,
  bool frozen,
  bool backdropPicture,
});

Widget _scene({
  required int phase,
  bool visible = true,
  bool cover = false,
  bool bodyPane = false,
  double backdropX = 420,
}) => MaterialApp(
  theme: ThemeData(brightness: Brightness.dark, fontFamily: 'Ahem'),
  themeAnimationStyle: AnimationStyle.noAnimation,
  home: RepaintBoundary(
    key: _sceneKey,
    child: LiquidGlassScope(
      enabled: true,
      quality: LiquidGlassQuality.ultra,
      child: LiquidGlassHost(
        enabled: true,
        wallpaperOnly: true,
        child: Stack(
          fit: StackFit.expand,
          clipBehavior: Clip.none,
          children: [
            LiquidGlassColorField(
              child: Transform.translate(
                offset: Offset.zero,
                child: ClipRect(
                  child: RepaintBoundary(
                    child: LiquidGlassColorSource(
                      child: ColoredBox(color: _colors[phase]),
                    ),
                  ),
                ),
              ),
            ),
            Positioned(
              left: 24,
              top: 48,
              width: 240,
              height: 96,
              child: ClipRect(
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Positioned(
                      left: visible ? 0 : 420,
                      top: 0,
                      width: 180,
                      height: 64,
                      child: const GlassText(
                        'HHHH',
                        inkRole: GlassInkRole.body,
                        style: TextStyle(fontSize: 28, color: Colors.white),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Positioned(
              left: backdropX,
              top: 160,
              width: 96,
              height: 40,
              child: const LiquidGlassBackdrop(),
            ),
            if (bodyPane)
              const Positioned(
                left: 24,
                top: 152,
                width: 160,
                height: 64,
                child: LiquidGlass(
                  style: LiquidGlassStyle(
                    tint: Color(0x18FF0000),
                    blur: 3,
                    refraction: 4,
                    bevel: 4,
                    rim: 0,
                  ),
                  clipper: ShapeBorderClipper(shape: AppShape.none),
                ),
              ),
            if (cover)
              const Positioned.fill(
                child: LiquidGlassOcclusion(
                  shape: AppShape.none,
                  child: ColoredBox(color: Color(0x4038283D)),
                ),
              ),
          ],
        ),
      ),
    ),
  ),
);

Map<String, int> _counts(LiquidGlassInkCapture capture) => {
  'sourceSearches': capture.sourceSearches,
  'sourceLayerVisits': capture.sourceLayerVisits,
  'sourceMappingInversions': capture.sourceMappingInversions,
  'sourceCaptures': capture.sourceCaptures,
  'fallbackCaptures': capture.fallbackCaptures,
  'filterUpdates': capture.filterUpdates,
  'masks': capture.masks,
  'skippedMasks': capture.skippedMasks,
  'frozenMasks': capture.frozenMasks,
  'skippedBackdrops': capture.skippedBackdrops,
};

Map<String, int> _workDelta(_Observation observation) => {
  for (final entry in _counts(observation.after).entries)
    entry.key: entry.value - _counts(observation.before)[entry.key]!,
};

Map<String, int> _bodyCounts(LiquidGlassCapture capture) => {
  'sourceSearches': capture.bodySourceSearches,
  'panes': capture.panes.length,
  'snapshots': capture.snapshots,
  'skippedPanes': capture.skippedPanes,
};

Future<List<int>?> _fieldPixel(
  WidgetTester tester,
  LiquidGlassInkLayer ink,
) async {
  final field = ink.debugField;
  if (field == null) return null;
  return tester.runAsync(() async {
    final bytes = (await field.$1.toByteData(
      format: ui.ImageByteFormat.rawRgba,
    ))!.buffer.asUint8List();
    final index =
        ((field.$1.height ~/ 2) * field.$1.width + field.$1.width ~/ 2) * 4;
    return bytes.sublist(index, index + 4);
  });
}

Future<_Observation> _observe(
  WidgetTester tester,
  String tag,
  LiquidGlassInkCapture before,
) async {
  final after = LiquidGlassHost.debugLastInkCapture!;
  final body = LiquidGlassHost.debugLastCapture!;
  final ink = tester.layers.whereType<LiquidGlassInkLayer>().single;
  final backdrop = tester.layers.whereType<LiquidGlassBackdropLayer>().single;
  final observation = (
    before: before,
    after: after,
    body: body,
    fieldPixel: await _fieldPixel(tester, ink),
    frozen: ink.inkFrozen,
    backdropPicture: backdrop.backdropPicture != null,
  );
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_sceneKey),
  );
  final layer = boundary.debugLayer! as OffsetLayer;
  await tester.runAsync(() async {
    final builder = ui.SceneBuilder()
      ..pushTransform(
        Matrix4.translationValues(
          -layer.offset.dx,
          -layer.offset.dy,
          0,
        ).storage,
      );
    final scene = layer.buildScene(builder);
    final ui.Image image;
    try {
      image = await scene.toImage(
        boundary.size.width.round(),
        boundary.size.height.round(),
      );
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
        File('$output/ink-idle-work-$tag.rgba').writeAsBytesSync(raw);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/ink-idle-work-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
        File('$output/ink-idle-work-$tag.json').writeAsStringSync(
          jsonEncode({
            'width': image.width,
            'height': image.height,
            'impeller': ui.ImageFilter.isShaderFilterSupported,
            'before': _counts(before),
            'after': _counts(after),
            'delta': _workDelta(observation),
            'body': _bodyCounts(body),
            'fieldPixel': observation.fieldPixel,
            'frozen': observation.frozen,
            'backdropPicture': observation.backdropPicture,
          }),
        );
      }
    } finally {
      image.dispose();
    }
  });
  return observation;
}

Future<_Observation> _idleWindow(WidgetTester tester, String tag) async {
  final before = LiquidGlassHost.debugLastInkCapture!;
  for (var frame = 0; frame < 3; frame++) {
    tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
    tester.binding.scheduleForcedFrame();
    await tester.pump(const Duration(milliseconds: 16));
  }
  return _observe(tester, tag, before);
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    await GlassInk.prepare();
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });

  testWidgets(
    'ink source work follows visible consumers and resumes on the first frame',
    (tester) async {
      tester.view.physicalSize = const Size(320, 240);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(_scene(phase: 0));
      final initial = await _idleWindow(tester, 'visible');

      await tester.pumpWidget(_scene(phase: 1, visible: false));
      final clipped = await _idleWindow(tester, 'clipped');

      var before = LiquidGlassHost.debugLastInkCapture!;
      await tester.pumpWidget(_scene(phase: 2));
      final returned = await _observe(tester, 'return-first-frame', before);

      before = LiquidGlassHost.debugLastInkCapture!;
      await tester.pumpWidget(_scene(phase: 2, cover: true));
      final firstFreeze = await _observe(tester, 'freeze-first-frame', before);
      await tester.pumpWidget(_scene(phase: 3, cover: true));
      final frozen = await _idleWindow(tester, 'frozen-source-changed');

      before = LiquidGlassHost.debugLastInkCapture!;
      await tester.pumpWidget(_scene(phase: 3));
      final thawed = await _observe(tester, 'thaw-first-frame', before);

      before = LiquidGlassHost.debugLastInkCapture!;
      await tester.pumpWidget(_scene(phase: 4, visible: false, backdropX: 280));
      final backdrop = await _observe(tester, 'partial-backdrop', before);
      final backdropIdle = await _idleWindow(tester, 'partial-backdrop-idle');

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpWidget(_scene(phase: 1, cover: true));
      final initiallyCovered = await _observe(
        tester,
        'initially-covered-first-frame',
        _emptyCapture,
      );

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpWidget(_scene(phase: 0, visible: false));
      final bodyEmpty = await _idleWindow(tester, 'body-empty');
      await tester.pumpWidget(_scene(phase: 4, visible: false, bodyPane: true));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      final bodyVisible = await _idleWindow(tester, 'body-visible-pane');

      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        File('$output/ink-idle-work-summary.json').writeAsStringSync(
          jsonEncode({
            for (final entry in {
              'visible': initial,
              'clipped': clipped,
              'returnFirstFrame': returned,
              'firstFreeze': firstFreeze,
              'frozen': frozen,
              'thawFirstFrame': thawed,
              'partialBackdrop': backdrop,
              'partialBackdropIdle': backdropIdle,
              'initiallyCovered': initiallyCovered,
              'bodyEmpty': bodyEmpty,
              'bodyVisiblePane': bodyVisible,
            }.entries)
              entry.key: {
                'inkDelta': _workDelta(entry.value),
                'body': _bodyCounts(entry.value.body),
              },
          }),
        );
      }

      expect(initial.after.masks, 1);
      expect(initial.fieldPixel, [32, 64, 96, 255]);
      expect(clipped.after.skippedMasks, 1);
      expect(clipped.after.skippedBackdrops, 1);
      expect(clipped.fieldPixel, isNull);
      expect(returned.fieldPixel, [22, 53, 63, 255]);
      expect(_workDelta(returned)['filterUpdates'], greaterThan(0));
      expect(firstFreeze.frozen, isTrue);
      expect(firstFreeze.fieldPixel, returned.fieldPixel);
      expect(frozen.after.frozenMasks, 1);
      expect(frozen.fieldPixel, firstFreeze.fieldPixel);
      expect(_workDelta(frozen)['sourceCaptures'], 0);
      expect(_workDelta(frozen)['filterUpdates'], 0);
      expect(thawed.frozen, isFalse);
      expect(thawed.fieldPixel, [56, 40, 61, 255]);
      expect(_workDelta(thawed)['filterUpdates'], greaterThan(0));
      expect(backdrop.after.skippedMasks, 1);
      expect(backdrop.after.skippedBackdrops, 0);
      expect(backdrop.backdropPicture, isTrue);
      expect(_workDelta(backdrop)['sourceCaptures'], greaterThan(0));
      expect(_workDelta(backdropIdle)['sourceSearches'], greaterThan(0));
      expect(_workDelta(backdropIdle)['sourceLayerVisits'], greaterThan(0));
      expect(
        _workDelta(backdropIdle)['sourceMappingInversions'],
        greaterThan(0),
      );
      expect(initiallyCovered.frozen, isTrue);
      expect(initiallyCovered.fieldPixel, [96, 64, 32, 255]);
      expect(initiallyCovered.after.sourceCaptures, greaterThan(0));
      expect(initiallyCovered.after.filterUpdates, greaterThan(0));
      expect(bodyEmpty.body.panes, isEmpty);
      expect(bodyVisible.body.panes, hasLength(1));
      expect(bodyVisible.body.panes.single.wallpaper, isTrue);
      expect(bodyVisible.body.bodySourceSearches, greaterThan(0));
      expect(tester.takeException(), isNull);

      expect(
        {
          'clippedSearches': _workDelta(clipped)['sourceSearches'],
          'clippedLayerVisits': _workDelta(clipped)['sourceLayerVisits'],
          'clippedInversions': _workDelta(clipped)['sourceMappingInversions'],
          'frozenSearches': _workDelta(frozen)['sourceSearches'],
          'frozenLayerVisits': _workDelta(frozen)['sourceLayerVisits'],
          'frozenInversions': _workDelta(frozen)['sourceMappingInversions'],
          'bodySearchesWithoutPane': bodyEmpty.body.bodySourceSearches,
        },
        {
          'clippedSearches': 0,
          'clippedLayerVisits': 0,
          'clippedInversions': 0,
          'frozenSearches': 0,
          'frozenLayerVisits': 0,
          'frozenInversions': 0,
          'bodySearchesWithoutPane': 0,
        },
      );
    },
    skip: !ui.ImageFilter.isShaderFilterSupported,
  );
}
