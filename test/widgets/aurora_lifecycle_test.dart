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
    testWidgets('non-resumed UI stops ticks, paint, and captures ($mode)', (
      tester,
    ) async {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      addTearDown(() {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
      });
      if (ui.ImageFilter.isShaderFilterSupported) {
        await tester.runAsync(GlassInk.prepare);
      }
      final key = GlobalKey<_ProbeState>();
      final container = ProviderContainer(
        overrides: [
          themeSettingProvider.overrideWithBuild(
            (_, _) => ThemeProps(
              auroraEnabled: mode != LiquidGlassMode.none,
              liquidGlass: mode,
            ),
          ),
        ],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            theme: ThemeData(platform: TargetPlatform.android),
            home: AuroraAppSurface(child: _Probe(key: key)),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final state = key.currentState!;
      expect(state.ticks, greaterThan(0));
      expect(state.paints, greaterThan(0));
      final seed = container.read(themeSettingProvider).auroraSeed;

      for (final lifecycle in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.detached,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(lifecycle);
        await tester.pump();
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
        if (mode != LiquidGlassMode.none) {
          expect(LiquidGlassHost.debugLastCapture, isNotNull);
          if (ui.ImageFilter.isShaderFilterSupported) {
            expect(LiquidGlassHost.debugLastInkCapture!.masks, greaterThan(0));
          }
        }
      }
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(state.mounted, isFalse);
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.takeException(), isNull);
    });
  }
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
      child: Center(
        child: LiquidGlass(
          style: const LiquidGlassStyle(tint: Colors.transparent, blur: 0),
          child: const SizedBox(
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
