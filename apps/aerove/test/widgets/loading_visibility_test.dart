import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

class _CountingPainter extends CustomPainter {
  _CountingPainter(this.onPaint);

  final VoidCallback onPaint;

  @override
  void paint(Canvas canvas, Size size) => onPaint();

  @override
  bool shouldRepaint(_CountingPainter oldDelegate) => false;
}

class _KeptAlive extends StatefulWidget {
  const _KeptAlive({required this.child});

  final Widget child;

  @override
  State<_KeptAlive> createState() => _KeptAliveState();
}

class _KeptAliveState extends State<_KeptAlive>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}

int get _tickers => SchedulerBinding.instance.transientCallbackCount;

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  group('CommonCircleLoading', () {
    Future<ScrollController> pumpBelow(
      WidgetTester tester, {
      required ValueListenable<double> above,
    }) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: CustomScrollView(
            controller: controller,
            slivers: [
              SliverToBoxAdapter(
                child: Column(
                  children: [
                    ValueListenableBuilder<double>(
                      valueListenable: above,
                      builder: (_, height, _) => SizedBox(height: height),
                    ),
                    // A dashboard card keeps its own layer, so moving it
                    // does not paint the ring again.
                    const RepaintBoundary(child: CommonCircleLoading()),
                    const SizedBox(height: 1600),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
      return controller;
    }

    testWidgets('a ring laid out below the view stops after its first frame '
        'and turns again once scrolled into view', (tester) async {
      final above = ValueNotifier(1600.0);
      addTearDown(above.dispose);
      final controller = await pumpBelow(tester, above: above);
      await tester.pump();

      expect(_tickers, 0);
      expect(tester.binding.hasScheduledFrame, isFalse);

      controller.jumpTo(1200);
      await tester.pump();

      expect(_tickers, 1);
      await tester.pump(const Duration(milliseconds: 250));
      expect(tester.binding.hasScheduledFrame, isTrue);
    });

    testWidgets('a ring the layout brings into view without a scroll turns '
        'again', (tester) async {
      final above = ValueNotifier(1600.0);
      addTearDown(above.dispose);
      await pumpBelow(tester, above: above);
      await tester.pump();
      expect(_tickers, 0);

      above.value = 100;
      await tester.pump();

      expect(_tickers, 1);
    });

    testWidgets('a ring in a lazy list cache stops without ever painting', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        MaterialApp(
          home: ListView(
            scrollCacheExtent: const ScrollCacheExtent.pixels(2000),
            children: const [SizedBox(height: 900), CommonCircleLoading()],
          ),
        ),
      );
      await tester.pump();

      expect(_tickers, 0);
    });

    testWidgets('a ring kept alive out of a lazy list stops and turns again '
        'once scrolled back', (tester) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: ListView.builder(
            controller: controller,
            itemCount: 60,
            itemExtent: 100,
            itemBuilder: (_, index) => index == 2
                ? const _KeptAlive(child: Center(child: CommonCircleLoading()))
                : const SizedBox.shrink(),
          ),
        ),
      );
      await tester.pump();
      expect(_tickers, 1);

      controller.jumpTo(4000);
      await tester.pump();
      await tester.pump();
      expect(_tickers, 0);

      controller.jumpTo(0);
      await tester.pump();
      await tester.pump();
      expect(_tickers, 1);
    });

    testWidgets('a tab flip rebuilds no ring', (tester) async {
      final shown = ValueNotifier(true);
      addTearDown(shown.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<bool>(
            valueListenable: shown,
            builder: (_, enabled, child) =>
                TickerMode(enabled: enabled, child: child!),
            child: const Column(
              children: [
                CommonCircleLoading(),
                CommonCircleLoading(),
                CommonCircleLoading(),
              ],
            ),
          ),
        ),
      );
      await tester.pump();
      var rebuilds = 0;
      debugOnRebuildDirtyWidget = (element, _) {
        if (element.widget is CommonCircleLoading) rebuilds++;
      };
      addTearDown(() => debugOnRebuildDirtyWidget = null);

      for (var flip = 0; flip < 3; flip++) {
        shown.value = false;
        await tester.pump();
        shown.value = true;
        await tester.pump();
      }
      debugOnRebuildDirtyWidget = null;

      expect(rebuilds, 0);
      expect(_tickers, 3);
    });

    testWidgets('a ring stopped below the view through a tab flip turns once '
        'the layout brings it into view', (tester) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final above = ValueNotifier(1600.0);
      final shown = ValueNotifier(true);
      addTearDown(above.dispose);
      addTearDown(shown.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<bool>(
            valueListenable: shown,
            builder: (_, enabled, child) =>
                TickerMode(enabled: enabled, child: child!),
            child: SingleChildScrollView(
              child: Column(
                children: [
                  ValueListenableBuilder<double>(
                    valueListenable: above,
                    builder: (_, height, _) => SizedBox(height: height),
                  ),
                  const RepaintBoundary(child: CommonCircleLoading()),
                  const SizedBox(height: 1600),
                ],
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(_tickers, 0);

      shown.value = false;
      await tester.pump();
      shown.value = true;
      await tester.pump();
      await tester.pump();
      expect(_tickers, 0);

      above.value = 100;
      await tester.pump();
      expect(_tickers, 1);
    });

    testWidgets('a ring outside any scrolling view keeps turning', (
      tester,
    ) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Stack(
            children: [
              Positioned(left: 0, top: 5000, child: CommonCircleLoading()),
              Center(child: CommonCircleLoading()),
            ],
          ),
        ),
      );
      for (var frame = 0; frame < 5; frame++) {
        await tester.pump(const Duration(milliseconds: 100));
      }

      expect(_tickers, 2);
    });
  });

  group('CommonLinearLoading', () {
    for (final glass in [false, true]) {
      for (final sheet in [false, true]) {
        testWidgets('keeps the header and body still while it runs '
            '(glass: $glass, sheet: $sheet)', (tester) async {
          tester.view.physicalSize = const Size(400, 800);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          var titlePaints = 0;
          var bodyPaints = 0;
          Widget scaffold = CommonScaffold(
            title: 'Loading',
            titleWidget: CustomPaint(
              painter: _CountingPainter(() => titlePaints++),
              size: const Size(80, 20),
            ),
            isLoading: true,
            body: CustomPaint(
              painter: _CountingPainter(() => bodyPaints++),
              size: const Size(200, 200),
            ),
          );
          if (sheet) {
            scaffold = SheetProvider(
              type: SheetType.bottomSheet,
              child: scaffold,
            );
          }
          await tester.pumpWidget(
            ProviderScope(
              child: TestApp(
                child: LiquidGlassScope(
                  enabled: glass,
                  child: LiquidGlassHost(enabled: glass, child: scaffold),
                ),
              ),
            ),
          );
          await tester.pump();
          titlePaints = 0;
          bodyPaints = 0;
          var frames = 0;
          for (var ms = 0; ms < 1000; ms += 16) {
            await tester.binding.delayed(const Duration(milliseconds: 16));
            if (tester.binding.hasScheduledFrame) {
              frames++;
              await tester.pump();
            }
          }

          expect(find.byType(LinearProgressIndicator), findsOneWidget);
          expect(frames, greaterThan(50));
          expect(titlePaints, 0);
          expect(bodyPaints, 0);
          await tester.pumpWidget(const SizedBox.shrink());
        });
      }
    }
  });
}
