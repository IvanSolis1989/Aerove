import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/models/native_usage.dart';

class RecordedUsage {
  const RecordedUsage(this.up, this.down);
  final BigInt up;
  final BigInt down;
  RecordedUsage add(int up, int down) =>
      RecordedUsage(this.up + BigInt.from(up), this.down + BigInt.from(down));
  Map<String, String> toJson() => {
    'up': up.toString(),
    'down': down.toString(),
  };
}

String _owner(NativeUsageSnapshot value) =>
    '${value.processInstance}/${value.counterEpoch}';

/// Confirmed native period prefixes plus same-period deltas, not a billing total.
class LocalUsageRecord {
  LocalUsageRecord({
    required this.all,
    required this.proxy,
    this.active,
    Set<String> retiredOwners = const {},
    Set<String> retiredProcesses = const {},
  }) : retiredOwners = Set.unmodifiable(retiredOwners),
       retiredProcesses = Set.unmodifiable(retiredProcesses);

  factory LocalUsageRecord.empty() => LocalUsageRecord(
    all: RecordedUsage(BigInt.zero, BigInt.zero),
    proxy: RecordedUsage(BigInt.zero, BigInt.zero),
  );
  final RecordedUsage all;
  final RecordedUsage proxy;
  final NativeUsageSnapshot? active;
  final Set<String> retiredOwners;
  final Set<String> retiredProcesses;
  bool get incomplete => true;

  LocalUsageRecord advance(NativeUsageSnapshot next) {
    final key = _owner(next);
    if (retiredProcesses.contains(next.processInstance) ||
        retiredOwners.contains(key))
      return this;
    final before = active;
    if (before != null && before.processInstance == next.processInstance) {
      if (next.completedNanos <= before.completedNanos ||
          next.startedNanos < before.completedNanos)
        return this;
    }
    final same = before?.sameCounterOwner(next) ?? false;
    final au = next.all.up - (same ? before!.all.up : 0);
    final ad = next.all.down - (same ? before!.all.down : 0);
    final pu = next.proxy.up - (same ? before!.proxy.up : 0);
    final pd = next.proxy.down - (same ? before!.proxy.down : 0);
    if ([au, ad, pu, pd].any((value) => value < 0)) {
      throw StateError('Native usage regressed without a new counter epoch');
    }
    final owners = {...retiredOwners};
    final processes = {...retiredProcesses};
    if (before != null && !same) {
      if (before.processInstance != next.processInstance) {
        processes.add(before.processInstance);
        owners.removeWhere(
          (key) => key.startsWith('${before.processInstance}/'),
        );
      } else {
        owners.add(_owner(before));
      }
    }
    return LocalUsageRecord(
      all: all.add(au, ad),
      proxy: proxy.add(pu, pd),
      active: next,
      retiredOwners: owners,
      retiredProcesses: processes,
    );
  }

  String encode() {
    final value = active;
    final owners = retiredOwners.toList()..sort();
    final processes = retiredProcesses.toList()..sort();
    return jsonEncode({
      'schemaVersion': 1,
      'basis': 'native-period-prefixes',
      'incomplete': true,
      'all': all.toJson(),
      'proxy': proxy.toJson(),
      'retiredOwners': owners,
      'retiredProcesses': processes,
      'active': value == null
          ? null
          : {
              'schemaVersion': 1,
              'processInstance': value.processInstance,
              'counterEpoch': value.counterEpoch,
              'kind': 'sequential-atomic-cumulative',
              'startedNanos': value.startedNanos.toString(),
              'completedNanos': value.completedNanos.toString(),
              'final': false,
              'all': {
                'up': value.all.up.toString(),
                'down': value.all.down.toString(),
              },
              'proxy': {
                'up': value.proxy.up.toString(),
                'down': value.proxy.down.toString(),
              },
            },
    });
  }

  factory LocalUsageRecord.decode(String payload) {
    final row = jsonDecode(payload) as Map<String, dynamic>;
    if (row['schemaVersion'] is! int ||
        row['schemaVersion'] != 1 ||
        row['basis'] != 'native-period-prefixes' ||
        row['incomplete'] != true) {
      throw const FormatException('Unsupported local usage ledger');
    }
    BigInt number(Object? value) {
      if (value is! String ||
          value.length > 64 ||
          !RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(value)) {
        throw const FormatException('Invalid recorded usage count');
      }
      return BigInt.parse(value);
    }

    RecordedUsage counts(Object? raw) {
      final value = raw as Map<String, dynamic>;
      return RecordedUsage(number(value['up']), number(value['down']));
    }

    Set<String> identities(Object? raw, String pattern) {
      final values = (raw as List).cast<String>();
      if (values.toSet().length != values.length ||
          values.any((v) => !RegExp(pattern).hasMatch(v))) {
        throw const FormatException('Invalid retired native usage identity');
      }
      return values.toSet();
    }

    final active = row['active'] == null
        ? null
        : NativeUsageSnapshot.fromJson(
            Map<String, dynamic>.from(row['active'] as Map),
          );
    final result = LocalUsageRecord(
      all: counts(row['all']),
      proxy: counts(row['proxy']),
      active: active,
      retiredOwners: identities(
        row['retiredOwners'],
        r'^[0-9a-f]{32}/[0-9a-f]{32}$',
      ),
      retiredProcesses: identities(row['retiredProcesses'], r'^[0-9a-f]{32}$'),
    );
    if (active == null ||
        result.retiredOwners.contains(_owner(active)) ||
        result.retiredProcesses.contains(active.processInstance) ||
        result.all.up < BigInt.from(active.all.up) ||
        result.all.down < BigInt.from(active.all.down) ||
        result.proxy.up < BigInt.from(active.proxy.up) ||
        result.proxy.down < BigInt.from(active.proxy.down)) {
      throw const FormatException('Inconsistent local usage baseline');
    }
    return result;
  }
}

class UsageStorageView {
  const UsageStorageView({
    required this.lease,
    required this.revision,
    required this.payload,
  });
  final String lease;
  final String? revision;
  final String? payload;
}

class UsageWriteAck {
  const UsageWriteAck({
    required this.acknowledged,
    this.revision,
    this.payload,
  });
  final bool acknowledged;
  final String? revision;
  final String? payload;
}

abstract interface class LocalUsageStorage {
  /// Retrieve the host-captured activation's lease idempotently; this cannot elect a writer.
  Future<UsageStorageView> claim();
  Future<UsageStorageView> read(String lease);
  Future<UsageWriteAck> write(
    String lease,
    String? expectedRevision,
    String payload,
  );
}

class UsageWriterRetired implements Exception {}

/// One queue per Dart runtime, fenced across engines by the native writer lease.
class LocalUsageLedger {
  LocalUsageLedger(
    this.storage, {
    this.checkpointInterval = const Duration(seconds: 30),
  });
  final LocalUsageStorage storage;
  final Duration checkpointInterval;
  Future<void> _tail = Future<void>.value();
  UsageStorageView? _view;
  LocalUsageRecord? _confirmed;
  LocalUsageRecord? _working;
  LocalUsageRecord? _unconfirmedHandoff;
  String? _attempted;
  bool _dirty = false;
  bool _needsRead = false;
  Object? lastError;
  LocalUsageRecord? get confirmed => _confirmed;
  LocalUsageRecord? get unconfirmedHandoff => _unconfirmedHandoff;

  Future<void> _serial(Future<void> Function() operation) {
    final run = _tail.then((_) async {
      try {
        await operation();
        lastError = null;
      } catch (error) {
        lastError = error;
        if (error is UsageWriterRetired) {
          // Only a subsequent native-qualified foreground activation may grant a new claim.
          _view = null;
          _needsRead = false;
          _attempted = null;
        }
        rethrow;
      }
    });
    _tail = run.then<void>((_) {}, onError: (Object _) {});
    return run;
  }

  Future<void> _load() async {
    if (_view == null) {
      final view = await storage.claim();
      final state = view.payload == null
          ? null
          : LocalUsageRecord.decode(view.payload!);
      final prior = _confirmed;
      if (prior != null &&
          (state == null ||
              state.all.up < prior.all.up ||
              state.all.down < prior.all.down ||
              state.proxy.up < prior.proxy.up ||
              state.proxy.down < prior.proxy.down)) {
        throw StateError(
          'A new usage activation cannot erase confirmed recorded bytes',
        );
      }
      if (_dirty && _working != null && _working!.encode() != view.payload) {
        _unconfirmedHandoff = _working;
      }
      _view = view;
      _confirmed = state;
      _working = state;
      _dirty = false;
      _needsRead = false;
      _attempted = null;
    } else if (_needsRead) {
      final view = await storage.read(_view!.lease);
      if (view.payload == _attempted &&
          view.revision != null &&
          view.revision != _view!.revision) {
        _confirmed = view.payload == null
            ? null
            : LocalUsageRecord.decode(view.payload!);
        _working = _confirmed;
        _dirty = false;
      } else if (view.revision != _view!.revision ||
          view.payload != _view!.payload) {
        throw StateError('Local usage storage changed outside this writer');
      }
      _view = view;
      _needsRead = false;
      _attempted = null;
    }
  }

  Future<void> observe(
    NativeUsageSnapshot value, {
    bool Function()? isCurrent,
  }) => _serial(() async {
    if (!(isCurrent?.call() ?? true)) return;
    await _load();
    if (!(isCurrent?.call() ?? true)) return;
    final previous = _working ?? LocalUsageRecord.empty();
    final next = previous.advance(value);
    if (identical(previous, next)) {
      if (_dirty) await _commit();
      return;
    }
    final changedOwner = previous.active?.sameCounterOwner(value) != true;
    final added =
        next.all.up != previous.all.up ||
        next.all.down != previous.all.down ||
        next.proxy.up != previous.proxy.up ||
        next.proxy.down != previous.proxy.down;
    _working = next;
    _dirty = _dirty || changedOwner || added;
    final baseline = _confirmed?.active;
    final due =
        baseline == null ||
        !baseline.sameCounterOwner(value) ||
        value.completedNanos - baseline.completedNanos >=
            checkpointInterval.inMicroseconds * 1000;
    if (_dirty && due) await _commit();
  });

  Future<void> flush() => _serial(() async {
    if (_view == null && !_dirty) return;
    await _load();
    if (_dirty) await _commit();
  });

  Future<void> _commit() async {
    final payload = _working!.encode();
    _attempted = payload;
    try {
      final ack = await storage.write(_view!.lease, _view!.revision, payload);
      if (!ack.acknowledged ||
          ack.revision == null ||
          ack.revision!.isEmpty ||
          ack.revision == _view!.revision ||
          ack.payload != payload) {
        throw StateError('Local usage checkpoint was not confirmed');
      }
      _view = UsageStorageView(
        lease: _view!.lease,
        revision: ack.revision,
        payload: payload,
      );
      _confirmed = _working;
      _dirty = false;
      _attempted = null;
    } catch (_) {
      _needsRead = true;
      rethrow;
    }
  }
}
