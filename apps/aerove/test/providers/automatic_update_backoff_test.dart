import 'package:fl_clash/providers/action.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('uses the shared twenty minute capped schedule and clears edits', () {
    var now = DateTime.fromMillisecondsSinceEpoch(0);
    final backoff = AutomaticUpdateBackoff(now: () => now);
    for (final minutes in [20, 40, 80, 160, 180, 180]) {
      backoff.failed('one', 'source', const Duration(hours: 3));
      expect(backoff.ready('one', 'source'), isFalse);
      now = now.add(
        Duration(minutes: minutes) - const Duration(milliseconds: 1),
      );
      expect(backoff.ready('one', 'source'), isFalse);
      now = now.add(const Duration(milliseconds: 1));
      expect(backoff.ready('one', 'source'), isTrue);
    }
    backoff.failed('one', 'source', const Duration(hours: 3));
    expect(backoff.ready('one', 'edited-source'), isTrue);
    backoff.failed('one', 'edited-source', const Duration(hours: 3));
    backoff.reset('one');
    expect(backoff.ready('one', 'edited-source'), isTrue);
    backoff.failed('one', 'source', const Duration(hours: 3));
    backoff.reset();
    expect(backoff.ready('one', 'source'), isTrue);
  });
}
