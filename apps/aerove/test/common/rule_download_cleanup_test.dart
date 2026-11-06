import 'dart:async';

import 'package:fl_clash/common/request.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final failed in [
    'http-close',
    'reader-cancel',
    'http-join',
    'sink-close',
  ]) {
    test(
      'a $failed error preserves the primary and attempts every other owner',
      () async {
        final primary = StateError(
          'HTTP failed after headers and sink creation',
        );
        final cleanup = StateError('$failed failed');
        final owner = RuleDownloadOwner()..begin();
        final attempted = <String>[];
        final steps = [
          'http-close',
          'reader-cancel',
          'http-join',
          'sink-close',
        ];
        await expectLater(
          owner.closeAndJoin(
            primary: primary,
            primaryStack: StackTrace.current,
            steps: [
              for (final name in steps)
                (
                  name: name,
                  close: () async {
                    attempted.add(name);
                    if (name == failed) throw cleanup;
                  },
                ),
            ],
          ),
          throwsA(
            isA<RuleDownloadCleanupFailure>()
                .having(
                  (failure) => failure.primary,
                  'original primary',
                  same(primary),
                )
                .having(
                  (failure) => failure.failures.single.error,
                  'cleanup cause',
                  same(cleanup),
                ),
          ),
        );
        expect(attempted, steps);
        expect(owner.isQuiescent, isFalse);
      },
    );
  }
  test('a body join must finish before successful quiescence', () async {
    final owner = RuleDownloadOwner()..begin();
    final join = Completer<void>();
    final entered = Completer<void>();
    var sinkClosed = false;
    var returned = false;
    final closing = owner
        .closeAndJoin(
          steps: [
            (name: 'reader-cancel', close: () async {}),
            (
              name: 'http-join',
              close: () {
                entered.complete();
                return join.future;
              },
            ),
            (
              name: 'sink-close',
              close: () {
                sinkClosed = true;
              },
            ),
          ],
        )
        .then((_) => returned = true);
    await entered.future;
    expect(owner.isQuiescent, isFalse);
    expect(returned, isFalse);
    expect(sinkClosed, isTrue);
    join.complete();
    await closing;
    expect(sinkClosed, isTrue);
    expect(owner.isQuiescent, isTrue);
  });
  test('a fully joined HTTP failure preserves the original object', () async {
    final owner = RuleDownloadOwner()..begin();
    final original = const FormatException('broken body');
    await expectLater(
      owner.closeAndJoin(
        primary: original,
        primaryStack: StackTrace.current,
        steps: [(name: 'all-closed', close: () {})],
      ),
      throwsA(same(original)),
    );
    expect(owner.isQuiescent, isTrue);
  });
}
