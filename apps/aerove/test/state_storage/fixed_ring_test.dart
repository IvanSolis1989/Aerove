import 'dart:math';
import 'package:fl_clash/common/fixed.dart';
import 'package:test/test.dart';

void main() {
  for (final cap in [1, 2, 3, 30, 2000]) {
    test(
      'ring agrees with a bounded list across wrap, replace and clear (cap $cap)',
      () {
        final random = Random(1701 + cap);
        final expected = List.generate(cap, (index) => -index - 1);
        var actual = FixedList<int>(cap, list: List.of(expected));
        for (var step = 0; step < 5000; step++) {
          final before = actual.list;
          final saved = List.of(expected);
          final frozen = actual.frozen();
          final operation = random.nextInt(30);
          if (operation == 0) {
            actual.clear();
            expected.clear();
          } else if (operation < 5 && expected.isNotEmpty) {
            final index = random.nextInt(expected.length);
            actual = actual.replace({index: step});
            expected[index] = step;
          } else {
            actual = actual.append(step);
            expected.add(step);
            if (expected.length > cap) expected.removeAt(0);
          }
          expect(actual.length, expected.length);
          expect(actual.list, expected);
          expect(identical(actual.list, actual.list), isTrue);
          expect(before, saved);
          expect(frozen.list, saved);
          for (var index = 0; index < actual.length; index++) {
            expect(actual[index], expected[index]);
          }
        }
      },
    );
  }
  test('a wrapped ring keeps nulls, bounds and independent copies', () {
    var list = FixedList<int?>(2, list: [null, 1, 2]);
    list = list.append(null);
    expect(list.list, [2, null]);
    final copy = list.copyWith();
    final frozen = list.frozen();
    list = list.replace({0: null, 1: 9});
    expect(list.list, [null, 9]);
    expect(copy.list, [2, null]);
    expect(frozen.list, [2, null]);
    expect(() => list[-1], throwsRangeError);
    expect(() => list[2], throwsRangeError);
    expect(() => list.replace({2: 0}), throwsRangeError);
    list.clear();
    list.add(null);
    expect(list.list, [null]);
  });
}
