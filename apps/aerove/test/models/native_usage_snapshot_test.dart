import 'package:fl_clash/models/native_usage.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> frame() => {
  'schemaVersion': 1,
  'processInstance': '0123456789abcdef0123456789abcdef',
  'counterEpoch': 'fedcba9876543210fedcba9876543210',
  'kind': 'sequential-atomic-cumulative',
  'startedNanos': '100',
  'completedNanos': '110',
  'final': false,
  'all': {'up': '9007199254740993', 'down': '2'},
  'proxy': {'up': '1', 'down': '0'},
};

void main() {
  test('exact scoped counters retain native owner and bounded observation', () {
    final value = NativeUsageSnapshot.fromJson(frame());
    expect(value.all.up, 9007199254740993);
    expect(value.proxy.down, 0);
    expect(value.isFinal, isFalse);
    expect(value.completedNanos - value.startedNanos, 10);
    expect(
      value.sameCounterOwner(NativeUsageSnapshot.fromJson(frame())),
      isTrue,
    );
    expect(
      value.sameCounterOwner(
        NativeUsageSnapshot.fromJson({
          ...frame(),
          'counterEpoch': '11111111111111111111111111111111',
        }),
      ),
      isFalse,
    );
    expect(
      value.sameCounterOwner(
        NativeUsageSnapshot.fromJson({
          ...frame(),
          'processInstance': '11111111111111111111111111111111',
        }),
      ),
      isFalse,
    );
  });

  test('unknown malformed or rounded values never become a zero snapshot', () {
    for (final bad in [
      null,
      0,
      1.5,
      '-1',
      '01',
      '1e3',
      '9223372036854775808',
    ]) {
      expect(
        () => NativeUsageSnapshot.fromJson({
          ...frame(),
          'all': {'up': bad, 'down': '0'},
        }),
        throwsFormatException,
      );
    }
    for (final patch in [
      {'processInstance': ''},
      {'counterEpoch': 'ui-nonce'},
      {'completedNanos': '99'},
      {'final': true},
      {'schemaVersion': 1.5},
      {'proxy': null},
    ]) {
      expect(
        () => NativeUsageSnapshot.fromJson({...frame(), ...patch}),
        throwsFormatException,
      );
    }
  });
}
