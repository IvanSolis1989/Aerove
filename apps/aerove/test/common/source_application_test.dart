import 'dart:async';

import 'package:fl_clash/common/exception.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('source completion waits for a successful runtime receipt', () async {
    final receipt = Completer<bool>();
    var completed = false;
    final pending = requireSourceApplication(
      () => receipt.future,
    ).then((_) => completed = true);
    await pumpEventQueue();
    expect(completed, isFalse);
    receipt.complete(true);
    await pending;
    expect(completed, isTrue);
  });

  test(
    'a false receipt reports saved source instead of successful apply',
    () async {
      await expectLater(
        requireSourceApplication(() async => false),
        throwsA(
          isA<SourceSavedNotAppliedException>().having(
            (e) => e.cause,
            'cause',
            isNull,
          ),
        ),
      );
    },
  );

  test(
    'the original failure is retained and wrappers are not duplicated',
    () async {
      final original = StateError('synthetic script failure');
      await expectLater(
        requireSourceApplication(() async => throw original),
        throwsA(
          isA<SourceSavedNotAppliedException>().having(
            (e) => e.cause,
            'cause',
            same(original),
          ),
        ),
      );
      final wrapped = SourceSavedNotAppliedException(original);
      await expectLater(
        requireSourceApplication(() async => throw wrapped),
        throwsA(same(wrapped)),
      );
    },
  );
}
