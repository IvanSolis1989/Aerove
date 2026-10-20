import 'dart:async';
import 'dart:math' as math;

import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _Probe extends StatelessWidget {
  const _Probe(this.phase);

  final double phase;

  @override
  Widget build(BuildContext context) => const SizedBox.square(dimension: 100);
}

Widget _motion({
  bool moving = true,
  bool ticker = true,
  bool page = true,
  bool reduced = false,
  bool contrast = false,
  Widget Function(Widget child)? layout,
}) {
  final drift = OutboundDrift(
    moving: moving,
    builder: (_, phase, _) => _Probe(phase),
  );
  return ProviderScope(
    child: MaterialApp(
      home: MediaQuery(
        data: MediaQueryData(
          disableAnimations: reduced,
          highContrast: contrast,
        ),
        child: PageActivityScope(
          isActive: page,
          child: TickerMode(
            enabled: ticker,
            child: Align(
              alignment: Alignment.topLeft,
              child: layout?.call(drift) ?? drift,
            ),
          ),
        ),
      ),
    ),
  );
}

Widget _ring({required bool checking, bool reduced = false}) => ProviderScope(
  child: MaterialApp(
    home: MediaQuery(
      data: MediaQueryData(disableAnimations: reduced),
      child: Align(
        alignment: Alignment.topLeft,
        child: OutboundBubbleRing(
          size: 48,
          checking: checking,
          well: Colors.grey,
          tint: Colors.blue,
          finish: OutboundFinish.clear,
          child: const SizedBox.shrink(),
        ),
      ),
    ),
  ),
);

double _phase(WidgetTester tester) =>
    tester.widget<_Probe>(find.byType(_Probe)).phase;

T _painter<T extends CustomPainter>(WidgetTester tester) {
  final paint = tester.widget<CustomPaint>(
    find.byWidgetPredicate(
      (widget) => widget is CustomPaint && widget.painter is T,
    ),
  );
  return paint.painter! as T;
}

Future<void> _advance(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 200));
  await tester.pump(const Duration(milliseconds: 200));
}

Future<void> _vsyncs(WidgetTester tester, int refreshRate) async {
  final interval = Duration(
    microseconds: (Duration.microsecondsPerSecond / refreshRate).round(),
  );
  for (var vsync = 0; vsync < refreshRate; vsync++) {
    await tester.pump(interval);
  }
}

int Function() _countFrames(WidgetTester tester) {
  var frames = 0;
  var counting = true;
  void count(Duration _) {
    if (!counting) return;
    frames++;
    tester.binding.addPostFrameCallback(count);
  }

  tester.binding.addPostFrameCallback(count);
  return () {
    counting = false;
    return frames;
  };
}

void main() {
  test('droplets keep one convex outline that every ray crosses once', () {
    const center = Offset(50, 50);
    for (final phase in [0.0, 1.5, 3.0, 4.5, 6.0]) {
      for (final fullness in [-0.2, 0.0, 1.0, 1.2]) {
        for (final squeeze in [-0.6, 0.0, 1.0, 1.5]) {
          final path = OutboundDropletClipper(
            phase: phase,
            fullness: fullness,
            squeeze: squeeze,
          ).getClip(const Size.square(100));
          expect(path.contains(center), isTrue);
          final contours = path.computeMetrics().toList();
          expect(contours, hasLength(1));
          final contour = contours.single;
          expect(contour.isClosed, isTrue);
          final samples = [
            for (var index = 0; index <= 120; index++)
              contour.getTangentForOffset(contour.length * index / 120)!,
          ];
          var turned = 0.0;
          for (var index = 0; index < 120; index++) {
            final here = samples[index];
            final next = samples[index + 1];
            expect(
              const Rect.fromLTWH(0, 0, 100, 100).contains(here.position),
              isTrue,
            );
            final before = here.vector;
            final after = next.vector;
            expect(
              before.dx * after.dy - before.dy * after.dx,
              greaterThanOrEqualTo(-0.001),
            );
            final from = here.position - center;
            final to = next.position - center;
            final sweep = math.atan2(
              from.dx * to.dy - from.dy * to.dx,
              from.dx * to.dx + from.dy * to.dy,
            );
            expect(sweep, greaterThan(0));
            turned += sweep;
          }
          expect(turned, closeTo(2 * math.pi, 1e-6));
        }
      }
    }
  });

  test('the bubble ring rests in one place whatever the drift reached', () {
    final rest = OutboundBubbleRingPainter.bubblesAt(0, 1);
    expect(rest, hasLength(greaterThanOrEqualTo(6)));
    expect({for (final (_, radius) in rest) radius}.length, greaterThan(3));
    for (final phase in [0.4, 1.7, 3.3, 9.1]) {
      expect(OutboundBubbleRingPainter.bubblesAt(phase, 1), rest);
      final drifting = OutboundBubbleRingPainter.bubblesAt(phase, 0);
      expect(drifting, isNot(rest));
      for (final (center, _) in drifting) {
        expect(
          (center - const Offset(0.5, 0.5)).distance,
          inInclusiveRange(0.35, 0.5),
        );
      }
    }
  });

  testWidgets('checking drifts and holds still the moment it completes', (
    tester,
  ) async {
    await tester.pumpWidget(_motion());
    await _advance(tester);
    final moving = _phase(tester);
    expect(moving, greaterThan(0));
    await tester.pumpWidget(_motion(moving: false));
    final still = _countFrames(tester);
    await _advance(tester);
    expect(_phase(tester), moving);
    expect(tester.binding.hasScheduledFrame, isFalse);
    await tester.pump(const Duration(seconds: 1));
    expect(_phase(tester), moving);
    expect(still(), 0);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('drifting asks for fifteen frames a second, not every vsync', (
    tester,
  ) async {
    await tester.pumpWidget(_motion());
    await _advance(tester);
    for (final refreshRate in [60, 120]) {
      final before = _phase(tester);
      final frames = _countFrames(tester);
      await _vsyncs(tester, refreshRate);
      expect(
        frames(),
        inInclusiveRange(14, 16),
        reason: '$refreshRate Hz must not drive a frame per vsync',
      );
      expect(_phase(tester), greaterThan(before));
    }
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    final disposed = _countFrames(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(disposed(), 0);
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('drifts moving at once share the same fifteen frames', (
    tester,
  ) async {
    Widget pair({required bool both}) => _motion(
      layout: (child) => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          child,
          OutboundDrift(moving: both, builder: (_, phase, _) => _Probe(phase)),
        ],
      ),
    );
    await tester.pumpWidget(pair(both: false));
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pumpWidget(pair(both: true));
    final frames = _countFrames(tester);
    await _vsyncs(tester, 60);
    expect(frames(), inInclusiveRange(14, 16));
    final phases = [
      for (final probe in tester.widgetList<_Probe>(find.byType(_Probe)))
        probe.phase,
    ];
    expect(phases, hasLength(2));
    expect(phases.last, greaterThan(0));
    expect(phases.first, greaterThan(phases.last));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    final disposed = _countFrames(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(disposed(), 0);
    expect(tester.binding.transientCallbackCount, 0);
  });

  for (final gate in ['ticker', 'page', 'reduced', 'contrast']) {
    testWidgets('$gate pauses ambient motion and resumes without jumping', (
      tester,
    ) async {
      await tester.pumpWidget(_motion());
      await _advance(tester);
      final before = _phase(tester);
      await tester.pumpWidget(
        _motion(
          ticker: gate != 'ticker',
          page: gate != 'page',
          reduced: gate == 'reduced',
          contrast: gate == 'contrast',
        ),
      );
      final paused = _countFrames(tester);
      await _advance(tester);
      expect(paused(), 0);
      expect(_phase(tester), before);
      expect(tester.binding.hasScheduledFrame, isFalse);
      expect(tester.binding.transientCallbackCount, 0);
      await tester.pumpWidget(_motion());
      expect(_phase(tester), before);
      await _advance(tester);
      expect(_phase(tester), greaterThan(before));
      await tester.pumpWidget(const SizedBox.shrink());
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('hidden application and paused lifecycle stop the clock', (
    tester,
  ) async {
    await tester.pumpWidget(_motion());
    await _advance(tester);
    final container = ProviderScope.containerOf(
      tester.element(find.byType(OutboundDrift)),
    );
    container.read(appVisibleProvider.notifier).value = false;
    await tester.pump();
    final hidden = _phase(tester);
    final idle = _countFrames(tester);
    await _advance(tester);
    expect(idle(), 0);
    expect(_phase(tester), hidden);
    expect(tester.binding.hasScheduledFrame, isFalse);
    container.read(appVisibleProvider.notifier).value = true;
    await _advance(tester);
    expect(_phase(tester), greaterThan(hidden));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    final paused = _phase(tester);
    await _advance(tester);
    expect(_phase(tester), paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await _advance(tester);
    expect(_phase(tester), greaterThan(paused));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a sheet or dialog over the page holds it still', (tester) async {
    await tester.pumpWidget(_motion());
    await _advance(tester);
    final navigator = Navigator.of(tester.element(find.byType(_Probe)));
    final route = PageRouteBuilder<void>(
      opaque: false,
      pageBuilder: (_, _, _) => const SizedBox.shrink(),
    );
    unawaited(navigator.push(route));
    await tester.pump();
    final covered = _phase(tester);
    await tester.pump(const Duration(milliseconds: 400));
    await _advance(tester);
    expect(_phase(tester), covered);
    expect(tester.binding.hasScheduledFrame, isFalse);
    final idle = _countFrames(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(idle(), 0);
    navigator.pop();
    await tester.pump();
    await _advance(tester);
    expect(_phase(tester), greaterThan(covered));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('leaving the scroll viewport pauses until it is visible again', (
    tester,
  ) async {
    final scroll = ScrollController();
    await tester.pumpWidget(
      _motion(
        layout: (child) => SizedBox(
          width: 200,
          height: 120,
          child: SingleChildScrollView(
            controller: scroll,
            child: Column(children: [child, const SizedBox(height: 500)]),
          ),
        ),
      ),
    );
    await _advance(tester);
    scroll.jumpTo(250);
    await tester.pump();
    final hidden = _phase(tester);
    final idle = _countFrames(tester);
    await _advance(tester);
    expect(idle(), 0);
    expect(_phase(tester), hidden);
    expect(tester.binding.hasScheduledFrame, isFalse);
    scroll.jumpTo(0);
    await tester.pump();
    await _advance(tester);
    expect(_phase(tester), greaterThan(hidden));
    await tester.pumpWidget(const SizedBox.shrink());
    scroll.dispose();
  });

  testWidgets('bubbles drift while checking, then spring into a fixed ring', (
    tester,
  ) async {
    await tester.pumpWidget(_ring(checking: true));
    await _advance(tester);
    final drifting = _painter<OutboundBubbleRingPainter>(tester);
    expect(drifting.phase, greaterThan(0));
    expect(drifting.calm, 0);
    await tester.pumpWidget(_ring(checking: false));
    await tester.pump(const Duration(milliseconds: 100));
    expect(_painter<OutboundBubbleRingPainter>(tester).calm, greaterThan(0));
    await tester.pumpAndSettle();
    expect(tester.binding.hasScheduledFrame, isFalse);
    final rest = _painter<OutboundBubbleRingPainter>(tester);
    expect(rest.calm, 1);
    expect(
      OutboundBubbleRingPainter.bubblesAt(rest.phase, rest.calm),
      OutboundBubbleRingPainter.bubblesAt(0, 1),
    );
    await tester.pump(const Duration(seconds: 1));
    expect(_painter<OutboundBubbleRingPainter>(tester).phase, rest.phase);
  });

  testWidgets('reduced motion rests the bubbles until it is turned off', (
    tester,
  ) async {
    await tester.pumpWidget(_ring(checking: true, reduced: true));
    await _advance(tester);
    final still = _painter<OutboundBubbleRingPainter>(tester);
    expect(still.calm, 1);
    expect(still.phase, 0);
    expect(tester.binding.hasScheduledFrame, isFalse);

    await tester.pumpWidget(_ring(checking: true));
    await _advance(tester);
    final released = _painter<OutboundBubbleRingPainter>(tester);
    expect(released.calm, lessThan(1));
    expect(released.phase, greaterThan(0));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('each glass mode picks its own bubble finish', (tester) async {
    late OutboundFinish finish;
    Future<OutboundFinish> resolve({
      required bool glass,
      LiquidGlassQuality quality = LiquidGlassQuality.ultra,
      bool contrast = false,
    }) async {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(highContrast: contrast),
            child: LiquidGlassScope(
              enabled: glass,
              quality: quality,
              child: Builder(
                builder: (context) {
                  finish = OutboundFinish.of(context);
                  return const SizedBox.shrink();
                },
              ),
            ),
          ),
        ),
      );
      return finish;
    }

    expect(await resolve(glass: false), OutboundFinish.solid);
    expect(
      await resolve(glass: true, quality: LiquidGlassQuality.standard),
      OutboundFinish.frost,
    );
    expect(await resolve(glass: true), OutboundFinish.clear);
    expect(await resolve(glass: true, contrast: true), OutboundFinish.solid);
  });

  testWidgets('silver gauze spans the card and high contrast hides it', (
    tester,
  ) async {
    Widget ribbon(bool contrast) => ProviderScope(
      child: MaterialApp(
        home: MediaQuery(
          data: MediaQueryData(highContrast: contrast),
          child: const Align(
            alignment: Alignment.topLeft,
            child: SizedBox(
              width: 600,
              height: 208,
              child: OutboundRibbon(running: false),
            ),
          ),
        ),
      ),
    );
    Finder paint() => find.byWidgetPredicate(
      (widget) =>
          widget is CustomPaint && widget.painter is OutboundRibbonPainter,
    );
    await tester.pumpWidget(ribbon(false));
    expect(tester.getSize(paint()), const Size(600, 208));
    expect(_painter<OutboundRibbonPainter>(tester).visible, isTrue);
    await tester.pumpWidget(ribbon(true));
    expect(_painter<OutboundRibbonPainter>(tester).visible, isFalse);
  });
}
