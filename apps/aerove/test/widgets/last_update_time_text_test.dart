import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/last_update_time_text.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

/// Advances the clock without drawing, drawing only the frames something
/// asked for, and returns how many that was.
Future<int> _framesOver(
  WidgetTester tester,
  Duration span, {
  Duration step = const Duration(seconds: 1),
}) async {
  var frames = 0;
  for (var elapsed = Duration.zero; elapsed < span; elapsed += step) {
    await tester.binding.delayed(step);
    if (tester.binding.hasScheduledFrame) {
      frames++;
      await tester.pump();
    }
  }
  return frames;
}

/// A label dated [age] before the test clock, which it reads instead of the
/// wall clock, so time only moves as the test advances it.
Widget _label(
  WidgetTester tester,
  Duration age, {
  ValueListenable<bool>? active,
}) {
  final clock = tester.binding.clock;
  final label = LastUpdateTimeText(
    lastUpdateDate: clock.now().subtract(age),
    now: clock.now,
  );
  return TestApp(
    homeBuilder: (child) => Scaffold(body: child),
    child: active == null
        ? label
        : ValueListenableBuilder(
            valueListenable: active,
            builder: (_, isActive, child) =>
                PageActivityScope(isActive: isActive, child: child!),
            child: label,
          ),
  );
}

const _justBeforeAMinute = Duration(seconds: 58);

String? _shown(WidgetTester tester) =>
    tester.widget<Text>(find.byType(GlassText)).data;

void main() {
  setUp(() {
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
  });

  group('lastUpdateTextLifetime', () {
    final now = DateTime(2026, 10, 26, 12);

    Duration lifetime(Duration age) =>
        lastUpdateTextLifetime(now.subtract(age), now);

    test('waits for the next minute while it counts minutes', () {
      expect(
        lifetime(const Duration(seconds: 30)),
        const Duration(seconds: 30),
      );
      expect(
        lifetime(const Duration(minutes: 5, seconds: 20)),
        const Duration(seconds: 40),
      );
      expect(lifetime(const Duration(minutes: 5)), const Duration(minutes: 1));
      expect(
        lifetime(const Duration(minutes: 59, seconds: 59)),
        const Duration(seconds: 1),
      );
    });

    test('waits for the next hour while it counts hours', () {
      expect(
        lifetime(const Duration(hours: 2, minutes: 10)),
        const Duration(minutes: 50),
      );
      expect(
        lifetime(const Duration(hours: 23, minutes: 59, seconds: 30)),
        const Duration(seconds: 30),
      );
    });

    test('waits at most an hour once it counts days', () {
      expect(
        lifetime(const Duration(days: 3, hours: 5)),
        const Duration(hours: 1),
      );
      expect(
        lifetime(const Duration(days: 3, hours: 23, minutes: 50)),
        const Duration(minutes: 10),
      );
      expect(lifetime(const Duration(days: 400)), const Duration(hours: 1));
    });

    test('never waits less than a second', () {
      expect(
        lifetime(const Duration(minutes: 5, seconds: 59, milliseconds: 999)),
        const Duration(seconds: 1),
      );
    });

    test('checks a time in the future again in a minute', () {
      expect(
        lastUpdateTextLifetime(now.add(const Duration(hours: 3)), now),
        const Duration(minutes: 1),
      );
    });
  });

  testWidgets('a label counting hours asks for no frame until the hour '
      'turns, then one', (tester) async {
    await tester.pumpWidget(
      _label(tester, const Duration(hours: 2, seconds: 30)),
    );
    await tester.pump();
    expect(_shown(tester), '2 hours ago');

    expect(await _framesOver(tester, const Duration(minutes: 59)), 0);
    expect(_shown(tester), '2 hours ago');
    expect(await _framesOver(tester, const Duration(minutes: 1)), 1);
    expect(_shown(tester), '3 hours ago');
  });

  testWidgets('a label counting days asks for no frame within hours', (
    tester,
  ) async {
    await tester.pumpWidget(_label(tester, const Duration(days: 3)));
    await tester.pump();
    expect(_shown(tester), '3 days ago');

    expect(await _framesOver(tester, const Duration(minutes: 59)), 0);
    expect(await _framesOver(tester, const Duration(hours: 2)), 0);
    expect(_shown(tester), '3 days ago');
  });

  testWidgets('a label counting minutes changes as the minute turns', (
    tester,
  ) async {
    await tester.pumpWidget(_label(tester, _justBeforeAMinute));
    await tester.pump();
    expect(_shown(tester), 'Just now');

    expect(
      await _framesOver(
        tester,
        const Duration(seconds: 3),
        step: const Duration(milliseconds: 100),
      ),
      1,
    );
    expect(_shown(tester), '1 minute ago');
    expect(await _framesOver(tester, const Duration(seconds: 50)), 0);
  });

  testWidgets('a label on a hidden page does not tick', (tester) async {
    final active = ValueNotifier(false);
    addTearDown(active.dispose);
    await tester.pumpWidget(_label(tester, _justBeforeAMinute, active: active));
    await tester.pump();

    expect(await _framesOver(tester, const Duration(minutes: 2)), 0);
    expect(_shown(tester), 'Just now');

    active.value = true;
    await tester.pump();
    expect(_shown(tester), '2 minutes ago');
  });

  testWidgets('a label catches up once as the app returns', (tester) async {
    await tester.pumpWidget(_label(tester, _justBeforeAMinute));
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);

    expect(await _framesOver(tester, const Duration(minutes: 2)), 0);
    expect(_shown(tester), 'Just now');

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(_shown(tester), '2 minutes ago');
  });
}
