import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  for (final mode in LiquidGlassMode.values) {
    testWidgets(
      'inactive retains the frame; hidden UI stops rendering ($mode)',
      (tester) async {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        addTearDown(() {
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.resumed,
          );
        });
        if (ui.ImageFilter.isShaderFilterSupported) {
          await tester.runAsync(GlassInk.prepare);
        }
        final key = GlobalKey<_ProbeState>();
        final capture = GlobalKey();
        final container = ProviderContainer(
          overrides: [
            themeSettingProvider.overrideWithBuild(
              (_, _) => ThemeProps(auroraEnabled: true, liquidGlass: mode),
            ),
          ],
        );
        addTearDown(container.dispose);
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              theme: ThemeData(platform: TargetPlatform.android),
              home: RepaintBoundary(
                key: capture,
                child: AuroraAppSurface(
                  child: Material(
                    type: MaterialType.transparency,
                    child: _Probe(key: key),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        final state = key.currentState!;
        expect(AuroraMotionActivity.rateOf(state.context)!.value, 15);
        final surfaceState = tester.state(find.byType(AuroraAppSurface));
        final wallpaperState = tester.state(find.byType(SeededAuroraWallpaper));
        expect(state.ticks, greaterThan(0));
        expect(state.paints, greaterThan(0));
        if (mode != LiquidGlassMode.none) {
          expect(find.byType(GlassInk), findsOneWidget);
          if (ui.ImageFilter.isShaderFilterSupported) {
            expect(LiquidGlassHost.debugLastInkCapture!.masks, greaterThan(0));
          }
        }
        final seed = container.read(themeSettingProvider).auroraSeed;
        final surfaceOffstage = find.descendant(
          of: find.byType(AuroraAppSurface),
          matching: find.byType(Offstage, skipOffstage: false),
          skipOffstage: false,
        );
        expect(surfaceOffstage, findsOneWidget);
        final visibleFrame = await _pixels(tester, capture);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.inactive,
        );
        await tester.pump();
        expect(await _pixels(tester, capture), visibleFrame);
        expect(tester.widget<Offstage>(surfaceOffstage).offstage, isFalse);
        state.context.findRenderObject()!.markNeedsPaint();
        await tester.pump();
        expect(await _pixels(tester, capture), visibleFrame);
        final inactiveTicks = state.ticks;
        final inactivePaints = state.paints;
        final inactiveBuilds = state.builds;
        LiquidGlassHost.debugLastCapture = null;
        LiquidGlassHost.debugLastInkCapture = null;
        await tester.pump(const Duration(minutes: 3));
        expect(state.ticks, inactiveTicks);
        expect(state.paints, inactivePaints);
        expect(state.builds, inactiveBuilds);
        expect(LiquidGlassHost.debugLastCapture, isNull);
        expect(LiquidGlassHost.debugLastInkCapture, isNull);
        expect(tester.binding.hasScheduledFrame, isFalse);
        expect(await _pixels(tester, capture), visibleFrame);
        expect(key.currentState, same(state));
        expect(container.read(themeSettingProvider).auroraSeed, seed);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(state.ticks, greaterThan(inactiveTicks));
        expect(state.paints, greaterThan(inactivePaints));
        expect(key.currentState, same(state));
        expect(tester.state(find.byType(AuroraAppSurface)), same(surfaceState));
        expect(
          tester.state(find.byType(SeededAuroraWallpaper)),
          same(wallpaperState),
        );

        for (final lifecycle in [
          AppLifecycleState.hidden,
          AppLifecycleState.paused,
          AppLifecycleState.detached,
        ]) {
          tester.binding.handleAppLifecycleStateChanged(lifecycle);
          tester.binding.scheduleForcedFrame();
          await tester.pump();
          expect(tester.widget<Offstage>(surfaceOffstage).offstage, isTrue);
          final ticks = state.ticks;
          final paints = state.paints;
          final builds = state.builds;
          LiquidGlassHost.debugLastCapture = null;
          LiquidGlassHost.debugLastInkCapture = null;
          state.context.findRenderObject()!.markNeedsPaint();
          await tester.pump(const Duration(minutes: 3));
          expect(state.ticks, ticks, reason: '$lifecycle must mute UI tickers');
          expect(state.paints, paints, reason: '$lifecycle must not paint UI');
          expect(state.builds, builds);
          expect(LiquidGlassHost.debugLastCapture, isNull);
          expect(LiquidGlassHost.debugLastInkCapture, isNull);
          expect(tester.binding.hasScheduledFrame, isFalse);
          expect(key.currentState, same(state));
          expect(state.mounted, isTrue);
          expect(container.read(themeSettingProvider).auroraSeed, seed);

          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.resumed,
          );
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          expect(state.ticks, greaterThan(ticks));
          expect(state.paints, greaterThan(paints));
          expect(
            tester.state(find.byType(AuroraAppSurface)),
            same(surfaceState),
          );
          expect(
            tester.state(find.byType(SeededAuroraWallpaper)),
            same(wallpaperState),
          );
          if (mode != LiquidGlassMode.none) {
            expect(LiquidGlassHost.debugLastCapture, isNotNull);
            if (ui.ImageFilter.isShaderFilterSupported) {
              expect(
                LiquidGlassHost.debugLastInkCapture!.masks,
                greaterThan(0),
              );
            }
          }
        }
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        expect(state.mounted, isFalse);
        expect(tester.binding.hasScheduledFrame, isFalse);
        expect(tester.takeException(), isNull);
      },
    );
  }
}

Future<List<int>> _pixels(WidgetTester tester, GlobalKey capture) async {
  final boundary =
      capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  late List<int> bytes;
  await tester.runAsync(() async {
    final image = boundary.toImageSync();
    try {
      bytes = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
    } finally {
      image.dispose();
    }
  });
  return bytes;
}

class _Probe extends StatefulWidget {
  const _Probe({super.key});

  @override
  State<_Probe> createState() => _ProbeState();
}

class _ProbeState extends State<_Probe> with SingleTickerProviderStateMixin {
  late final AnimationController _animation;
  var ticks = 0;
  var paints = 0;
  var builds = 0;

  @override
  void initState() {
    super.initState();
    _animation =
        AnimationController(vsync: this, duration: const Duration(seconds: 1))
          ..addListener(() => ticks++)
          ..repeat();
  }

  @override
  void dispose() {
    _animation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    builds++;
    return CustomPaint(
      painter: _PaintProbe(_animation, () => paints++),
      child: const Center(
        child: LiquidGlass(
          style: LiquidGlassStyle(tint: Colors.transparent, blur: 0),
          child: SizedBox(
            width: 180,
            height: 80,
            child: Center(child: GlassText('Proxy stays mounted')),
          ),
        ),
      ),
    );
  }
}

class _PaintProbe extends CustomPainter {
  _PaintProbe(Listenable repaint, this.onPaint) : super(repaint: repaint);

  final VoidCallback onPaint;

  @override
  void paint(Canvas canvas, Size size) => onPaint();

  @override
  bool shouldRepaint(_PaintProbe oldDelegate) => false;
}
