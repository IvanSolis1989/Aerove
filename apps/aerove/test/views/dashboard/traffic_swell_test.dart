import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';

class _Flag extends Notifier<bool> {
  _Flag(this.initial);

  final bool initial;

  @override
  bool build() => initial;

  void set(bool value) => state = value;
}

final _running = NotifierProvider<_Flag, bool>(() => _Flag(false));

const _half = Duration(milliseconds: 110);

/// Tracks presses and scrolls as the home page does, when [paced].
class _Paced extends StatelessWidget {
  const _Paced({required this.paced, required this.child});

  final bool paced;
  final Widget child;

  @override
  Widget build(BuildContext context) =>
      paced ? AuroraMotionActivity(child: child) : child;
}

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer();
    globalState.container = container;
  });
  tearDown(() => container.dispose());

  Future<void> pump(
    WidgetTester tester, {
    bool reduced = false,
    bool highContrast = false,
    bool ticker = true,
    bool paced = false,
    Widget? child,
  }) async {
    tester.view.physicalSize = const Size(400, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          locale: const Locale('en'),
          child: Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(context).copyWith(
                disableAnimations: reduced,
                highContrast: highContrast,
              ),
              child: TickerMode(
                enabled: ticker,
                child: _Paced(
                  paced: paced,
                  child: Align(
                    alignment: Alignment.topLeft,
                    child: SizedBox(
                      width: 360,
                      child: DashboardDensityScope(
                        density: DashboardDensity.mobile,
                        scale: 1,
                        child:
                            child ??
                            SizedBox(
                              height: 200,
                              child: Consumer(
                                builder: (_, ref, _) =>
                                    OutboundTrafficBackground(
                                      running: ref.watch(_running),
                                    ),
                              ),
                            ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  void sample(int up, int down) => container
      .read(trafficsProvider.notifier)
      .addTraffic(Traffic(up: up, down: down));

  void setRunning(bool value) => container.read(_running.notifier).set(value);

  Iterable<LineChart> charts(WidgetTester tester) =>
      tester.widgetList<LineChart>(find.byType(LineChart));

  /// How far the curves stand; every chart of a backdrop stands together.
  double lift(WidgetTester tester) {
    final lifts = {for (final chart in charts(tester)) chart.lift!.value};
    expect(lifts, hasLength(1));
    return lifts.single;
  }

  List<double> values(WidgetTester tester) => charts(tester).first.values;

  void expectIdle(WidgetTester tester) {
    expect(tester.binding.transientCallbackCount, 0);
    expect(tester.binding.hasScheduledFrame, isFalse);
  }

  testWidgets('stopped lines lie flat and ask for no frames', (tester) async {
    await pump(tester);
    expect(charts(tester), hasLength(4));
    for (final chart in charts(tester)) {
      expect(chart.values, isEmpty);
    }
    expect(lift(tester), 0);
    expectIdle(tester);
    await tester.pump(const Duration(seconds: 1));
    expectIdle(tester);
  });

  testWidgets('a start grows the curve out of the flat lines as samples '
      'arrive', (tester) async {
    await pump(tester);
    setRunning(true);
    await tester.pump();
    expect(lift(tester), 0);
    expectIdle(tester);
    sample(1024, 4096);
    await tester.pump();
    await tester.pump(_half);
    final rising = lift(tester);
    expect(rising, inExclusiveRange(0.05, 0.95));
    expect(values(tester), [1024]);
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 1);
    expectIdle(tester);
    sample(2048, 8192);
    await tester.pump();
    expect(lift(tester), 1);
    expect(values(tester), [1024, 2048]);
    expectIdle(tester);
  });

  testWidgets('a first sample that comes with the start grows at once', (
    tester,
  ) async {
    await pump(tester);
    setRunning(true);
    sample(1024, 4096);
    await tester.pump();
    expect(values(tester), [1024]);
    await tester.pump(_half);
    expect(lift(tester), inExclusiveRange(0.05, 0.95));
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 1);
    expectIdle(tester);
  });

  testWidgets('a stop settles the last curve flat before clearing it', (
    tester,
  ) async {
    setRunning(true);
    sample(1024, 4096);
    sample(2048, 8192);
    await pump(tester);
    expect(lift(tester), 1);
    final shown = values(tester);
    setRunning(false);
    await tester.pump();
    container.read(trafficsProvider.notifier).clear();
    await tester.pump();
    await tester.pump(_half);
    expect(lift(tester), inExclusiveRange(0.05, 0.95));
    expect(values(tester), shown, reason: 'the stopped curve settles whole');
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 0);
    expect(values(tester), isEmpty);
    expectIdle(tester);
  });

  testWidgets('a toggle midway turns back from where the curve is', (
    tester,
  ) async {
    await pump(tester);
    setRunning(true);
    await tester.pump();
    sample(1024, 4096);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    final midway = lift(tester);
    expect(midway, inExclusiveRange(0.05, 0.95));
    setRunning(false);
    await tester.pump();
    expect(lift(tester), closeTo(midway, 0.08));
    await tester.pump(const Duration(milliseconds: 40));
    final lower = lift(tester);
    expect(lower, lessThan(midway));
    // A run that begins again is a new run: the old curve keeps settling
    // from where it is, and the new one grows from the flat lines.
    setRunning(true);
    await tester.pump();
    expect(lift(tester), closeTo(lower, 0.08));
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 0);
    expect(values(tester), isEmpty);
    sample(2048, 8192);
    await tester.pump();
    await tester.pump(_half);
    expect(lift(tester), inExclusiveRange(0.05, 0.95));
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 1);
    expectIdle(tester);
  });

  testWidgets('a new run waits for the old curve to settle, then grows', (
    tester,
  ) async {
    setRunning(true);
    sample(1024, 4096);
    sample(2048, 8192);
    await pump(tester);
    final old = values(tester);
    setRunning(false);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 60));
    final settling = lift(tester);
    container.read(trafficsProvider.notifier).clear();
    setRunning(true);
    await tester.pump();
    sample(4096, 4096);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 40));
    expect(values(tester), old);
    expect(lift(tester), lessThan(settling));
    await tester.pump(fadeTabDuration);
    await tester.pump(_half);
    expect(values(tester), [4096]);
    expect(lift(tester), inExclusiveRange(0.05, 0.95));
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 1);
    expectIdle(tester);
  });

  for (final gate in ['reduced motion', 'high contrast', 'ticker off']) {
    testWidgets('$gate lands each change at once without a ticker', (
      tester,
    ) async {
      await pump(
        tester,
        reduced: gate == 'reduced motion',
        highContrast: gate == 'high contrast',
        ticker: gate != 'ticker off',
      );
      setRunning(true);
      await tester.pump();
      sample(1024, 4096);
      await tester.pump();
      if (gate != 'ticker off') expect(lift(tester), 1);
      expect(tester.binding.transientCallbackCount, 0);
      setRunning(false);
      await tester.pump();
      if (gate != 'ticker off') expect(lift(tester), 0);
      expect(tester.binding.transientCallbackCount, 0);
    });
  }

  testWidgets('a page that comes back shows the end state at once', (
    tester,
  ) async {
    setRunning(true);
    sample(1024, 4096);
    await pump(tester);
    expect(lift(tester), 1);
    await pump(tester, ticker: false);
    setRunning(false);
    container.read(trafficsProvider.notifier).clear();
    await tester.pump();
    await pump(tester);
    expect(lift(tester), 0);
    expect(values(tester), isEmpty);
    expectIdle(tester);
  });

  /// Follows the curves frame by frame for [seconds], calling [each] at the
  /// start of every second: the lift they are first drawn at with samples,
  /// and the largest step between two frames from then on.
  Future<(double, double)> follow(
    WidgetTester tester, {
    int seconds = 2,
    void Function(int second)? each,
  }) async {
    double? first;
    double? last;
    var step = 0.0;
    for (var second = 0; second < seconds; second++) {
      each?.call(second);
      for (var frame = 0; frame < 60; frame++) {
        await tester.pump(const Duration(microseconds: 16667));
        final now = lift(tester);
        if (first == null && values(tester).isNotEmpty) first = now;
        if (first == null) continue;
        if (last != null && (now - last).abs() > step) {
          step = (now - last).abs();
        }
        last = now;
      }
    }
    expect(first, isNotNull, reason: 'no sample was ever drawn');
    return (first!, step);
  }

  // A tap on the page, as the start button's own is, paces the samples for
  // the moment after it; the curve still grows out of the flat lines.
  for (final delay in const [0, 100, 300]) {
    testWidgets('a start right after a tap grows from the flat lines when the '
        'first sample comes ${delay}ms later', (tester) async {
      await pump(tester, paced: true);
      await tester.tapAt(const Offset(180, 100));
      await tester.pump(const Duration(milliseconds: 16));
      setRunning(true);
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pump(Duration(milliseconds: delay));
      sample(1024, 4096);
      final (first, step) = await follow(tester);
      expect(first, lessThan(0.2), reason: 'the curve appeared at $first');
      expect(step, lessThanOrEqualTo(0.15));
      expect(lift(tester), 1);
      expectIdle(tester);
    });
  }

  testWidgets('a start while the page is held grows from the flat lines', (
    tester,
  ) async {
    await pump(tester, paced: true);
    final gesture = await tester.startGesture(const Offset(180, 100));
    await tester.pump(const Duration(milliseconds: 16));
    setRunning(true);
    await tester.pump(const Duration(milliseconds: 16));
    final (first, step) = await follow(
      tester,
      seconds: 4,
      each: (second) => sample(1024 * (second + 1), 4096),
    );
    expect(first, lessThan(0.2), reason: 'the curve appeared at $first');
    expect(step, lessThanOrEqualTo(0.15));
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump(fadeTabDuration);
    expect(lift(tester), 1);
  });
}
