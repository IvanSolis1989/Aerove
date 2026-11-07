/// Native cumulative counters; these scopes are independent views, never summed.
class NativeUsageCounters {
  const NativeUsageCounters({required this.up, required this.down});
  final int up;
  final int down;
}

/// One native owner's sequential atomic reads, fenced against real counter reset.
/// Observation bounds are monotonic nanoseconds local to processInstance.
class NativeUsageSnapshot {
  const NativeUsageSnapshot({
    required this.processInstance,
    required this.counterEpoch,
    required this.startedNanos,
    required this.completedNanos,
    required this.all,
    required this.proxy,
  });

  final String processInstance;
  final String counterEpoch;
  final int startedNanos;
  final int completedNanos;
  final NativeUsageCounters all;
  final NativeUsageCounters proxy;
  bool get isFinal => false;

  bool sameCounterOwner(NativeUsageSnapshot other) =>
      processInstance == other.processInstance &&
      counterEpoch == other.counterEpoch;

  factory NativeUsageSnapshot.fromJson(Map<String, dynamic> json) {
    void keys(Map value, Set<String> expected) {
      if (value.length != expected.length ||
          !value.keys.every(expected.contains)) {
        throw const FormatException('Invalid native usage snapshot fields');
      }
    }

    int count(Object? value) {
      if (value is! String || !RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(value)) {
        throw const FormatException(
          'Native usage count must be an exact decimal string',
        );
      }
      final result = int.tryParse(value);
      if (result == null || result < 0 || result > 9223372036854775807) {
        throw const FormatException('Native usage count is out of range');
      }
      return result;
    }

    String identity(Object? value) {
      if (value is! String || !RegExp(r'^[0-9a-f]{32}$').hasMatch(value)) {
        throw const FormatException(
          'Native usage owner identity is unavailable',
        );
      }
      return value;
    }

    NativeUsageCounters counters(Object? value) {
      if (value is! Map)
        throw const FormatException('Native usage scope is unavailable');
      keys(value, {'up', 'down'});
      return NativeUsageCounters(
        up: count(value['up']),
        down: count(value['down']),
      );
    }

    keys(json, {
      'schemaVersion',
      'processInstance',
      'counterEpoch',
      'kind',
      'startedNanos',
      'completedNanos',
      'final',
      'all',
      'proxy',
    });
    if (json['schemaVersion'] is! int ||
        json['schemaVersion'] != 1 ||
        json['kind'] != 'sequential-atomic-cumulative' ||
        json['final'] != false) {
      throw const FormatException('Unsupported native usage observation');
    }
    final started = count(json['startedNanos']);
    final completed = count(json['completedNanos']);
    if (completed < started)
      throw const FormatException(
        'Native usage observation bounds are reversed',
      );
    return NativeUsageSnapshot(
      processInstance: identity(json['processInstance']),
      counterEpoch: identity(json['counterEpoch']),
      startedNanos: started,
      completedNanos: completed,
      all: counters(json['all']),
      proxy: counters(json['proxy']),
    );
  }
}
