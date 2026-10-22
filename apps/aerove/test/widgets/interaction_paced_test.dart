import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:fl_clash/widgets/interaction_paced.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Widget _scene(ValueNotifier<int> source, {bool ticker = true}) =>
    Directionality(
      textDirection: TextDirection.ltr,
      child: AuroraMotionActivity(
        enabled: false,
        child: TickerMode(
          enabled: ticker,
          child: ValueListenableBuilder(
            valueListenable: source,
            builder: (_, value, _) => InteractionPaced(
              revision: value,
              builder: (_) => Text('$value'),
            ),
          ),
        ),
      ),
    );

String? _shown(WidgetTester tester) =>
    tester.widget<Text>(find.byType(Text, skipOffstage: false)).data;

void main() {
  testWidgets('a second-by-second value shows its first change, then every '
      'two seconds while the user interacts, and the latest at once after', (
    tester,
  ) async {
    final source = ValueNotifier(0);
    addTearDown(source.dispose);
    await tester.pumpWidget(_scene(source));
    for (final value in [1, 2]) {
      await tester.pump(const Duration(seconds: 1));
      source.value = value;
      await tester.pump();
      expect(_shown(tester), '$value');
    }

    final gesture = await tester.startGesture(const Offset(20, 20));
    final shown = <String?>[];
    for (var value = 3; value <= 8; value++) {
      await tester.pump(const Duration(seconds: 1));
      source.value = value;
      await tester.pump();
      shown.add(_shown(tester));
    }
    expect(shown, ['3', '3', '5', '5', '7', '7']);

    await gesture.up();
    await tester.pump(const Duration(milliseconds: 349));
    expect(_shown(tester), '7');
    await tester.pump(const Duration(milliseconds: 1));
    expect(_shown(tester), '8');
    expect(tester.binding.hasScheduledFrame, isFalse);

    source.value = 9;
    await tester.pump();
    expect(_shown(tester), '9');

    for (final (values, expected) in [
      ([10, 11, 12], ['10', '10', '12']),
      ([13, 14], ['13', '13']),
    ]) {
      final next = await tester.startGesture(const Offset(20, 20));
      shown.clear();
      for (final value in values) {
        source.value = value;
        await tester.pump();
        shown.add(_shown(tester));
      }
      expect(shown, expected);
      await next.up();
      await tester.pump(const Duration(milliseconds: 350));
      expect(_shown(tester), '${values.last}');
    }
  });

  testWidgets('a card mounted during an interaction shows its first change at '
      'once', (tester) async {
    final source = ValueNotifier(0);
    addTearDown(source.dispose);
    await tester.pumpWidget(
      const Directionality(
        textDirection: TextDirection.ltr,
        child: AuroraMotionActivity(enabled: false, child: SizedBox()),
      ),
    );
    final gesture = await tester.startGesture(const Offset(20, 20));
    await tester.pumpWidget(_scene(source));
    expect(_shown(tester), '0');
    source.value = 1;
    await tester.pump();
    expect(_shown(tester), '1');
    source.value = 2;
    await tester.pump();
    expect(_shown(tester), '1');
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 350));
    expect(_shown(tester), '2');
  });

  testWidgets('a value held off screen waits for the ticker, not a frame', (
    tester,
  ) async {
    final source = ValueNotifier(0);
    addTearDown(source.dispose);
    await tester.pumpWidget(_scene(source));
    final gesture = await tester.startGesture(const Offset(20, 20));
    for (final value in [1, 2]) {
      source.value = value;
      await tester.pump();
    }
    expect(_shown(tester), '1');
    await tester.pumpWidget(_scene(source, ticker: false));
    await gesture.up();
    await tester.pump(const Duration(seconds: 1));
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(_shown(tester), '1');

    await tester.pumpWidget(_scene(source));
    await tester.pump();
    expect(_shown(tester), '2');
    expect(tester.binding.hasScheduledFrame, isFalse);
  });
}
