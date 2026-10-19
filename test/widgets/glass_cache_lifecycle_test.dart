import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _scene = ValueKey('cache-lifecycle-scene');

Widget _app(GlobalKey hostKey, {bool modal = false, bool moved = false, bool visible = true, bool enabled = true}) {
  final host = LiquidGlassHost(
    key: hostKey,
    enabled: enabled,
    wallpaperOnly: true,
    child: Stack(
      fit: StackFit.expand,
      children: [
        const LiquidGlassColorField(
          child: LiquidGlassColorSource(child: ColoredBox(color: Color(0xFF204060))),
        ),
        const Positioned(
          left: 110,
          top: 150,
          width: 60,
          height: 24,
          child: ColoredBox(color: Colors.yellow),
        ),
        const Positioned(
          left: 80,
          top: 100,
          width: 200,
          height: 140,
          child: LiquidGlass(
            style: LiquidGlassStyle(
              tint: Color(0x20FF0000),
              blur: 12,
              refraction: 0,
              bevel: 0,
              rim: 0,
            ),
            clipper: ShapeBorderClipper(shape: AppShape.none),
          ),
        ),
        if (modal)
          const Positioned(
            left: 60,
            top: 80,
            width: 240,
            height: 180,
            child: LiquidGlassOcclusion(
              shape: AppShape.none,
              child: LiquidGlass(
                style: LiquidGlassStyle(
                  tint: Color(0x10FFFFFF),
                  blur: 0,
                  refraction: 0,
                  bevel: 0,
                  rim: 0,
                ),
                clipper: ShapeBorderClipper(shape: AppShape.none),
              ),
            ),
          ),
      ],
    ),
  );
  return MaterialApp(
    home: RepaintBoundary(
      key: _scene,
      child: LiquidGlassScope(
        enabled: true,
        child: Offstage(
          offstage: !visible,
          child: moved ? Align(child: host) : Padding(padding: EdgeInsets.zero, child: host),
        ),
      ),
    ),
  );
}

Future<Uint8List> _shot(WidgetTester tester, String name) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('$output/glass-cache-$name.png').writeAsBytesSync(png.buffer.asUint8List());
      }
      return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  }))!;
}

Future<void> _frames(WidgetTester tester) async {
  for (var frame = 0; frame < 3; frame++) {
    tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void _expectFrame(Uint8List actual, Uint8List expected, {int tolerance = 0}) {
  expect(actual.length, expected.length);
  var maximum = 0;
  var worst = 0;
  for (var index = 0; index < actual.length; index++) {
    final difference = (actual[index] - expected[index]).abs();
    if (difference > maximum) {
      maximum = difference;
      worst = index;
    }
  }
  expect(maximum, lessThanOrEqualTo(tolerance), reason: 'frame byte $worst');
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
    }
  });

  testWidgets('hidden host releases caches after reparent and restores its frame', (tester) async {
    tester.view.physicalSize = const Size(320, 320);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    final host = GlobalKey();
    try {
      await tester.pumpWidget(_app(host));
      await tester.runAsync(() async {
        if (ui.ImageFilter.isShaderFilterSupported) {
          await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
        }
      });
      await _frames(tester);
      await tester.pumpWidget(_app(host, modal: true, moved: true));
      await _frames(tester);
      final before = await _shot(tester, 'before-hidden');
      expect(LiquidGlassHost.debugLastFlatCapture!.pixels, greaterThan(0));
      LiquidGlassHost.debugLastCacheTrim = null;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      expect(LiquidGlassHost.debugLastCacheTrim, isNull);
      _expectFrame(await _shot(tester, 'inactive'), before);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      final trim = LiquidGlassHost.debugLastCacheTrim!;
      expect(trim.releasedAtlasPixels, greaterThan(0));
      expect(trim.releasedFlatPixels, greaterThan(0));
      if (ui.ImageFilter.isShaderFilterSupported) {
        expect(trim.releasedSourceBlurPixels, greaterThan(0));
      }
      expect(trim.retainedAtlasPixels, 0);
      expect(trim.retainedFlatPixels, 0);
      expect(trim.retainedFrozenPixels, 0);
      expect(LiquidGlassHost.debugLiveSourceBlurPixels, 0);
      tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
      tester.binding.scheduleForcedFrame();
      await tester.pump(const Duration(milliseconds: 16));
      await _shot(tester, 'hidden-mounted');
      expect(LiquidGlassHost.debugLastCapture, isNull);
      expect(LiquidGlassHost.debugLiveSourceBlurPixels, 0);
      expect(LiquidGlassHost.debugLastCacheTrim, trim);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      final pausedTrim = LiquidGlassHost.debugLastCacheTrim;
      tester.renderObject(find.byType(LiquidGlassHost)).markNeedsPaint();
      tester.binding.scheduleForcedFrame();
      await tester.pump(const Duration(milliseconds: 16));
      await _shot(tester, 'paused-mounted');
      expect(LiquidGlassHost.debugLastCapture, isNull);
      expect(LiquidGlassHost.debugLiveSourceBlurPixels, 0);
      expect(LiquidGlassHost.debugLastCacheTrim, pausedTrim);
      await tester.pumpWidget(_app(host, modal: true, enabled: false));
      tester.binding.scheduleForcedFrame();
      await tester.pump();
      await tester.pumpWidget(_app(host, modal: true));
      tester.binding.scheduleForcedFrame();
      await tester.pump();
      await _shot(tester, 'paused-reenabled');
      expect(LiquidGlassHost.debugLastCapture, isNull);
      expect(LiquidGlassHost.debugLiveSourceBlurPixels, 0);
      await tester.pumpWidget(_app(host, modal: true, moved: true, visible: false));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pumpWidget(_app(host, modal: true, moved: true));
      await _frames(tester);
      _expectFrame(await _shot(tester, 'inactive-restored'), before, tolerance: 2);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await _frames(tester);
      _expectFrame(await _shot(tester, 'resumed'), before, tolerance: 2);
      expect(LiquidGlassHost.debugLastCapture!.panes, isNotEmpty);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
    }
  });

  testWidgets('memory pressure preserves visible modal caches until safe recapture', (tester) async {
    tester.view.physicalSize = const Size(320, 320);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    final host = GlobalKey();
    try {
      await tester.pumpWidget(_app(host));
      await _frames(tester);
      await tester.pumpWidget(_app(host, modal: true));
      await _frames(tester);
      final before = await _shot(tester, 'before-pressure');
      final atlas = LiquidGlassHost.debugLastCapture!.panes.first.atlas;
      tester.binding.handleMemoryPressure();
      final retained = atlas.clone();
      try {
        expect(await tester.runAsync(() => retained.toByteData()), isNotNull);
      } finally {
        retained.dispose();
      }
      final pending = LiquidGlassHost.debugLastCacheTrim!;
      expect(pending.releasedAtlasPixels, 0);
      expect(pending.retainedAtlasPixels, greaterThan(0));
      expect(pending.retainedFlatPixels, greaterThan(0));
      expect(pending.retainedFrozenPixels, greaterThan(0));
      final after = await _shot(tester, 'pressure-recapture');
      _expectFrame(after, before);
      final trimmed = LiquidGlassHost.debugLastCacheTrim!;
      expect(trimmed.reason, 'pressure-frame');
      expect(trimmed.releasedAtlasPixels, greaterThan(0));
      expect(trimmed.retainedFrozenPixels, pending.retainedFrozenPixels);
      expect(trimmed.retainedFlatPixels, pending.retainedFlatPixels);
      await _frames(tester);
      _expectFrame(await _shot(tester, 'pressure-settled'), before);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });
}
