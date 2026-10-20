import 'dart:math' as math;

import 'package:fl_clash/common/aurora_motion.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  testWidgets('all pointers must release before idle restoration', (
    tester,
  ) async {
    late ValueListenable<int> rate;
    var builds = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: AuroraMotionActivity(
          child: Builder(
            builder: (context) {
              builds++;
              rate = AuroraMotionActivity.rateOf(context)!;
              return const SizedBox.expand();
            },
          ),
        ),
      ),
    );
    final originalBuilds = builds;
    final first = await tester.createGesture(pointer: 1);
    final second = await tester.createGesture(pointer: 2);
    await first.down(const Offset(20, 20));
    expect(rate.value, 15);
    await second.down(const Offset(40, 20));
    await first.up();
    await tester.pump(const Duration(seconds: 1));
    expect(rate.value, 15);
    await second.cancel();
    await tester.pump(const Duration(milliseconds: 349));
    expect(rate.value, 15);
    await tester.pump(const Duration(milliseconds: 1));
    expect(rate.value, 30);
    expect(builds, originalBuilds);
  });

  for (final quality in <LiquidGlassQuality?>[null, LiquidGlassQuality.standard]) {
    final activeRate = quality == LiquidGlassQuality.standard ? 10 : 15;
    final idleRate = quality == LiquidGlassQuality.standard ? 15 : 30;
    testWidgets('wheel and Navigator scroll activity bubble without rebuilding quality=$quality', (
      tester,
    ) async {
      late ValueListenable<int> rate;
      late BuildContext routeContext;
      var notifications = 0;
      final navigator = GlobalKey<NavigatorState>();
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigator,
          builder: (context, child) => NotificationListener<ScrollNotification>(
            onNotification: (_) {
              notifications++;
              return false;
            },
            child: AuroraMotionActivity(quality: quality, child: child!),
          ),
          home: const SizedBox.expand(),
        ),
      );
      final route = MaterialPageRoute<void>(
        builder: (_) => Builder(
          builder: (context) {
            routeContext = context;
            rate = AuroraMotionActivity.rateOf(context)!;
            return const SizedBox.expand();
          },
        ),
      );
      navigator.currentState!.push(route);
      await tester.pumpAndSettle();
      await tester.sendEventToBinding(
        const PointerScrollEvent(
          position: Offset(20, 20),
          scrollDelta: Offset(0, 40),
        ),
      );
      expect(rate.value, activeRate);
      await tester.pump(const Duration(milliseconds: 349));
      expect(rate.value, activeRate);
      final metrics = FixedScrollMetrics(
        minScrollExtent: 0,
        maxScrollExtent: 1000,
        pixels: 40,
        viewportDimension: 300,
        axisDirection: AxisDirection.down,
        devicePixelRatio: 1,
      );
      ScrollStartNotification(
        metrics: metrics,
        context: routeContext,
      ).dispatch(routeContext);
      await tester.pump(const Duration(seconds: 1));
      expect(rate.value, activeRate);
      ScrollUpdateNotification(
        metrics: metrics,
        context: routeContext,
        scrollDelta: 40,
      ).dispatch(routeContext);
      ScrollEndNotification(
        metrics: metrics,
        context: routeContext,
      ).dispatch(routeContext);
      await tester.pump(const Duration(milliseconds: 349));
      expect(rate.value, activeRate);
      await tester.pump(const Duration(milliseconds: 1));
      expect(rate.value, idleRate);
      expect(notifications, 3);
      ScrollStartNotification(
        metrics: metrics,
        context: routeContext,
      ).dispatch(routeContext);
      expect(rate.value, activeRate);
      navigator.currentState!.removeRoute(route);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 350));
      expect(rate.value, idleRate);
    });

    testWidgets('ballistic scrolling stays active after pointer release quality=$quality', (
      tester,
    ) async {
      late ValueListenable<int> rate;
      final controller = ScrollController();
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: AuroraMotionActivity(
            quality: quality,
            child: Builder(
              builder: (context) {
                rate = AuroraMotionActivity.rateOf(context)!;
                return ListView.builder(
                  controller: controller,
                  itemCount: 200,
                  itemBuilder: (_, index) =>
                      SizedBox(height: 60, child: Text('$index')),
                );
              },
            ),
          ),
        ),
      );
      await tester.fling(find.byType(ListView), const Offset(0, -400), 1500);
      expect(rate.value, activeRate);
      await tester.pump(const Duration(milliseconds: 400));
      expect(controller.position.isScrollingNotifier.value, isTrue);
      expect(rate.value, activeRate);
      for (
        var frame = 0;
        frame < 300 && controller.position.isScrollingNotifier.value;
        frame++
      ) {
        await tester.pump(const Duration(milliseconds: 16));
        expect(rate.value, activeRate);
      }
      expect(controller.position.isScrollingNotifier.value, isFalse);
      await tester.pump(const Duration(milliseconds: 349));
      expect(rate.value, activeRate);
      await tester.pump(const Duration(milliseconds: 1));
      expect(rate.value, idleRate);
    });

  }

  for (final quality in <LiquidGlassQuality?>[
    null,
    LiquidGlassQuality.standard,
    LiquidGlassQuality.ultra,
  ]) {
    for (final refreshRate in [60, 120]) {
    testWidgets(
      'adaptive cadence preserves the motion clock at $refreshRate Hz quality=$quality',
      (tester) async {
        final idleRate = quality == LiquidGlassQuality.standard ? 15 : 30;
        final activeRate = quality == LiquidGlassQuality.standard ? 10 : 15;
        late ValueListenable<int> rate;
        var builds = 0;
        await tester.pumpWidget(
          MaterialApp(
            home: AuroraMotionActivity(
              quality: quality,
              child: Builder(
                builder: (context) {
                  builds++;
                  rate = AuroraMotionActivity.rateOf(context)!;
                  return const Center(
                    child: SizedBox(
                      width: 180,
                      height: 300,
                      child: SeededAuroraWallpaper(
                        colors: [Color(0xFF65BDD2), Color(0xFFC589CA)],
                        seed: 271828,
                        brightness: Brightness.light,
                        motion: true,
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        );
        late Duration start;
        tester.binding.addPostFrameCallback((timestamp) {
          start = timestamp;
        });
        await tester.pump();
        final originalBuilds = builds;
        final source = tester.renderObject<RenderBox>(
          find.byType(LiquidGlassColorSource),
        );
        final boundary = tester.renderObject<RenderRepaintBoundary>(
          find.descendant(
            of: find.byType(SeededAuroraWallpaper),
            matching: find.byType(RepaintBoundary),
          ),
        );
        Future<int> countPaints() async {
          boundary.debugResetMetrics();
          late Duration end;
          var lastPaintTime = start;
          var paints = 0;
          final interval = Duration(
            microseconds: (Duration.microsecondsPerSecond / refreshRate)
                .round(),
          );
          for (var frame = 0; frame < refreshRate; frame++) {
            tester.binding.addPostFrameCallback((timestamp) {
              end = timestamp;
            });
            await tester.pump(interval);
            final painted = boundary.debugSymmetricPaintCount +
                boundary.debugAsymmetricPaintCount;
            if (painted > paints) lastPaintTime = end;
            paints = painted;
          }
          final sampledTime = quality == LiquidGlassQuality.standard
              ? lastPaintTime
              : end;
          expect(
            end - lastPaintTime,
            lessThanOrEqualTo(Duration(
              microseconds: (Duration.microsecondsPerSecond / rate.value).ceil() +
                  interval.inMicroseconds,
            )),
          );
          final actual = source.getTransformTo(null).storage;
          final expected = AuroraMotion(
            271828,
          ).at(sampledTime - start, const Size(180, 300));
          final angle = math.atan2(actual[1], actual[0]);
          final difference = math.atan2(
            math.sin(angle - expected.angle),
            math.cos(angle - expected.angle),
          );
          expect(difference.abs(), lessThan(0.006));
          return boundary.debugSymmetricPaintCount +
              boundary.debugAsymmetricPaintCount;
        }

        expect(await countPaints(), inInclusiveRange(idleRate - 1, idleRate));
        final gesture = await tester.startGesture(
          tester.getCenter(find.byType(SeededAuroraWallpaper)),
        );
        expect(rate.value, activeRate);
        expect(await countPaints(), inInclusiveRange(activeRate - 1, activeRate));
        await gesture.up();
        await tester.pump(const Duration(milliseconds: 349));
        expect(rate.value, activeRate);
        await tester.pump(const Duration(milliseconds: 1));
        expect(rate.value, idleRate);
        expect(await countPaints(), inInclusiveRange(idleRate - 1, idleRate));
        expect(builds, originalBuilds);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        expect(tester.binding.hasScheduledFrame, isFalse);
      },
    );
  }
  }

  testWidgets('quality changes immediately retain activity and idle deadlines', (tester) async {
    LiquidGlassQuality? quality;
    var enabled = true;
    late StateSetter update;
    late ValueListenable<int> rate;
    await tester.pumpWidget(MaterialApp(
      home: StatefulBuilder(builder: (_, rebuild) {
        update = rebuild;
        return AuroraMotionActivity(
          quality: quality,
          enabled: enabled,
          child: Builder(builder: (context) {
            rate = AuroraMotionActivity.rateOf(context)!;
            return const SizedBox.expand();
          }),
        );
      }),
    ));
    expect(rate.value, 30);
    update(() => quality = LiquidGlassQuality.standard);
    await tester.pump();
    expect(rate.value, 15);
    final gesture = await tester.startGesture(const Offset(20, 20));
    expect(rate.value, 10);
    for (final next in [LiquidGlassQuality.ultra, null, LiquidGlassQuality.standard]) {
      update(() => quality = next);
      await tester.pump();
      expect(rate.value, next == LiquidGlassQuality.standard ? 10 : 15);
    }
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 349));
    expect(rate.value, 10);
    update(() => quality = LiquidGlassQuality.ultra);
    await tester.pump();
    expect(rate.value, 15);
    await tester.pump(const Duration(milliseconds: 1));
    expect(rate.value, 30);
    update(() => quality = LiquidGlassQuality.standard);
    await tester.pump();
    expect(rate.value, 15);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    addTearDown(() => tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed));
    for (final lifecycle in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.detached,
    ]) {
      final pointer = await tester.startGesture(const Offset(20, 20));
      expect(rate.value, 10);
      tester.binding.handleAppLifecycleStateChanged(lifecycle);
      expect(rate.value, 15);
      await pointer.cancel();
      await tester.pump(const Duration(minutes: 1));
      expect(tester.binding.hasScheduledFrame, isFalse);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(rate.value, 15);
    }
    final pointer = await tester.startGesture(const Offset(20, 20));
    expect(rate.value, 10);
    update(() => enabled = false);
    await tester.pump();
    expect(rate.value, 15);
    await pointer.cancel();
    update(() => enabled = true);
    await tester.pump();
    expect(rate.value, 15);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'disabled activity and lifecycle clear the idle timer and inputs',
    (tester) async {
      late ValueListenable<int> rate;
      Widget scene({
        bool enabled = true,
        bool reduced = false,
        bool ticker = true,
      }) => MediaQuery(
        data: MediaQueryData(disableAnimations: reduced),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: TickerMode(
            enabled: ticker,
            child: AuroraMotionActivity(
              enabled: enabled,
              child: Builder(
                builder: (context) {
                  rate = AuroraMotionActivity.rateOf(context)!;
                  return const SizedBox.expand();
                },
              ),
            ),
          ),
        ),
      );
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      addTearDown(() {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
      });
      for (final disabled in [
        scene(enabled: false),
        scene(reduced: true),
        scene(ticker: false),
      ]) {
        await tester.pumpWidget(scene());
        final gesture = await tester.startGesture(const Offset(20, 20));
        await gesture.up();
        expect(rate.value, 15);
        await tester.pumpWidget(disabled);
        expect(rate.value, 30);
        await tester.pump(const Duration(minutes: 1));
        expect(rate.value, 30);
        expect(tester.binding.hasScheduledFrame, isFalse);
      }
      await tester.pumpWidget(scene());
      for (final state in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.detached,
      ]) {
        final gesture = await tester.startGesture(const Offset(20, 20));
        expect(rate.value, 15);
        tester.binding.handleAppLifecycleStateChanged(state);
        expect(rate.value, 30);
        await gesture.cancel();
        await tester.pump(const Duration(minutes: 1));
        expect(tester.binding.hasScheduledFrame, isFalse);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pump();
        expect(rate.value, 30);
      }
      final gesture = await tester.startGesture(const Offset(20, 20));
      await gesture.up();
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 1));
      expect(tester.takeException(), isNull);
    },
  );
}
