import 'dart:async';

import 'package:fl_clash/common/local_usage_ledger.dart';
import 'package:fl_clash/models/native_usage.dart';
import 'package:flutter_test/flutter_test.dart';

String id(int value) => value.toRadixString(16).padLeft(32, '0');
NativeUsageSnapshot sample(
  int process,
  int epoch,
  int seconds,
  int up,
  int down, {
  int proxyUp = 0,
  int proxyDown = 0,
}) => NativeUsageSnapshot(
  processInstance: id(process),
  counterEpoch: id(epoch),
  startedNanos: seconds * 1000000000,
  completedNanos: seconds * 1000000000 + 1,
  all: NativeUsageCounters(up: up, down: down),
  proxy: NativeUsageCounters(up: proxyUp, down: proxyDown),
);

/// Storage protocol fixture only: no assertion below proves physical durability.
class ControlledStore implements LocalUsageStorage {
  String? payload;
  String? revision;
  String lease = '';
  int writes = 0;
  int claims = 0;
  bool failFalse = false;
  bool failThrow = false;
  bool durableButAckLost = false;
  Completer<void>? entered;
  Completer<void>? release;
  @override
  Future<UsageStorageView> claim() async {
    claims++;
    if (lease.isEmpty) lease = 'lease-$claims';
    return UsageStorageView(lease: lease, revision: revision, payload: payload);
  }

  @override
  Future<UsageStorageView> read(String value) async {
    if (value != lease) throw UsageWriterRetired();
    return UsageStorageView(lease: lease, revision: revision, payload: payload);
  }

  @override
  Future<UsageWriteAck> write(
    String value,
    String? expected,
    String data,
  ) async {
    writes++;
    entered?.complete();
    entered = null;
    if (release != null) {
      await release!.future;
      release = null;
    }
    if (value != lease) throw UsageWriterRetired();
    if (expected != revision || failFalse)
      return const UsageWriteAck(acknowledged: false);
    if (failThrow) throw StateError('controlled write failure');
    payload = data;
    revision = 'revision-$writes';
    if (durableButAckLost) throw StateError('controlled lost observer');
    return UsageWriteAck(acknowledged: true, revision: revision, payload: data);
  }
}

/// Models native-selected activations only; claim cannot change authority.
class ActivationBackend {
  Object? current;
  Object? granted;
  String? lease;
  String? payload;
  String? revision;
  int grants = 0;
  int writes = 0;
}

class ActivationStore implements LocalUsageStorage {
  ActivationStore(this.backend);
  final ActivationBackend backend;
  Object activation = Object();
  bool loseNextClaimAck = false;
  void nativeForeground() {
    activation = Object();
    backend.current = activation;
  }

  void check(Object captured) {
    if (!identical(captured, backend.current)) throw UsageWriterRetired();
  }

  UsageStorageView view() => UsageStorageView(
    lease: backend.lease!,
    revision: backend.revision,
    payload: backend.payload,
  );
  @override
  Future<UsageStorageView> claim() async {
    final captured = activation;
    check(captured);
    if (!identical(backend.granted, captured)) {
      backend.granted = captured;
      backend.lease = 'qualified-${++backend.grants}';
    }
    if (loseNextClaimAck) {
      loseNextClaimAck = false;
      throw StateError('controlled claim ACK loss');
    }
    return view();
  }

  @override
  Future<UsageStorageView> read(String lease) async {
    check(activation);
    if (lease != backend.lease) throw UsageWriterRetired();
    return view();
  }

  @override
  Future<UsageWriteAck> write(
    String lease,
    String? expected,
    String payload,
  ) async {
    check(activation);
    if (lease != backend.lease) throw UsageWriterRetired();
    if (expected != backend.revision)
      return const UsageWriteAck(acknowledged: false);
    backend.payload = payload;
    backend.revision = 'write-${++backend.writes}';
    return UsageWriteAck(
      acknowledged: true,
      revision: backend.revision,
      payload: payload,
    );
  }
}

void main() {
  test(
    'zero first baseline records subsequent bytes; scopes and directions stay separate',
    () async {
      final store = ControlledStore();
      final ledger = LocalUsageLedger(store);
      expect(ledger.confirmed, isNull);
      await ledger.observe(sample(1, 1, 0, 0, 0));
      expect(ledger.confirmed!.all.up, BigInt.zero);
      await ledger.observe(sample(1, 1, 31, 11, 22, proxyUp: 3, proxyDown: 4));
      expect(ledger.confirmed!.all.up, BigInt.from(11));
      expect(ledger.confirmed!.all.down, BigInt.from(22));
      expect(ledger.confirmed!.proxy.up, BigInt.from(3));
      expect(ledger.confirmed!.proxy.down, BigInt.from(4));
      expect(ledger.confirmed!.incomplete, isTrue);
    },
  );

  test(
    'new native periods add known prefixes, and retired owners/processes never replay',
    () {
      var record = LocalUsageRecord.empty().advance(
        sample(1, 1, 1, 100, 200, proxyUp: 20, proxyDown: 30),
      );
      record = record.advance(
        sample(1, 1, 31, 150, 260, proxyUp: 21, proxyDown: 32),
      );
      record = record.advance(sample(1, 2, 40, 7, 8, proxyUp: 4, proxyDown: 5));
      expect(record.all.up, BigInt.from(157));
      expect(record.all.down, BigInt.from(268));
      expect(record.proxy.up, BigInt.from(25));
      expect(record.proxy.down, BigInt.from(37));
      expect(
        identical(record.advance(sample(1, 1, 50, 900, 900)), record),
        isTrue,
      );
      record = record.advance(sample(2, 1, 0, 10, 20));
      expect(record.all.up, BigInt.from(167));
      expect(record.all.down, BigInt.from(288));
      expect(
        identical(record.advance(sample(1, 99, 100, 900, 900)), record),
        isTrue,
      );
      expect(LocalUsageRecord.decode(record.encode()).all.up, BigInt.from(167));
    },
  );

  test(
    'duplicate out-of-order overlap and regression do not invent a reset',
    () {
      final record = LocalUsageRecord.empty().advance(sample(1, 1, 10, 50, 60));
      expect(
        identical(record.advance(sample(1, 1, 10, 50, 60)), record),
        isTrue,
      );
      expect(
        identical(record.advance(sample(1, 1, 9, 80, 90)), record),
        isTrue,
      );
      expect(() => record.advance(sample(1, 1, 11, 1, 1)), throwsStateError);
      expect(record.all.up, BigInt.from(50));
    },
  );

  test('recorded totals across native periods do not overflow int64', () {
    var record = LocalUsageRecord.empty().advance(
      sample(1, 1, 1, 9223372036854775807, 0),
    );
    record = record.advance(sample(1, 2, 2, 9223372036854775807, 0));
    expect(record.all.up, BigInt.parse('18446744073709551614'));
    expect(
      LocalUsageRecord.decode(record.encode()).all.up,
      BigInt.parse('18446744073709551614'),
    );
  });

  test(
    'false and thrown commit acknowledgements do not advance confirmed baseline',
    () async {
      for (final throwing in [false, true]) {
        final store = ControlledStore();
        final ledger = LocalUsageLedger(store);
        await ledger.observe(sample(1, 1, 0, 0, 0));
        store.failThrow = throwing;
        store.failFalse = !throwing;
        await expectLater(
          ledger.observe(sample(1, 1, 31, 5, 7)),
          throwsStateError,
        );
        expect(ledger.confirmed!.all.up, BigInt.zero);
        store.failThrow = false;
        store.failFalse = false;
        await ledger.observe(sample(1, 1, 32, 9, 13));
        expect(ledger.confirmed!.all.up, BigInt.from(9));
        expect(ledger.confirmed!.all.down, BigInt.from(13));
      }
    },
  );

  test(
    'lost ack settles only through confirmed store read; reopen does not add twice',
    () async {
      final store = ControlledStore();
      final first = LocalUsageLedger(store);
      await first.observe(sample(1, 1, 0, 0, 0));
      store.durableButAckLost = true;
      await expectLater(
        first.observe(sample(1, 1, 31, 5, 7)),
        throwsStateError,
      );
      expect(first.confirmed!.all.up, BigInt.zero);
      store.durableButAckLost = false;
      await first.observe(sample(1, 1, 31, 5, 7));
      expect(first.confirmed!.all.up, BigInt.from(5));
      final second = LocalUsageLedger(store);
      await second.observe(sample(1, 1, 31, 5, 7));
      expect(second.confirmed!.all.up, BigInt.from(5));
      expect(
        store.claims,
        2,
        reason: 'same native activation reopens its stable claim',
      );
    },
  );

  test(
    'unknown claim cannot steal B; only a real new native activation can rebind A',
    () async {
      final backend = ActivationBackend();
      final a = ActivationStore(backend)..nativeForeground();
      final writerA = LocalUsageLedger(a, checkpointInterval: Duration.zero);
      a.loseNextClaimAck = true;
      await expectLater(
        writerA.observe(sample(1, 1, 0, 0, 0)),
        throwsStateError,
      );
      final b = ActivationStore(backend)..nativeForeground();
      final writerB = LocalUsageLedger(b, checkpointInterval: Duration.zero);
      await writerB.observe(sample(1, 1, 1, 5, 7));
      final leaseB = backend.lease;
      await expectLater(
        writerA.observe(sample(1, 1, 2, 8, 9)),
        throwsA(isA<UsageWriterRetired>()),
      );
      await expectLater(
        writerA.observe(sample(1, 1, 3, 9, 10)),
        throwsA(isA<UsageWriterRetired>()),
      );
      expect(backend.lease, leaseB);
      await writerB.observe(sample(1, 1, 4, 10, 12));
      expect(writerB.confirmed!.all.up, BigInt.from(10));
      a.nativeForeground();
      await writerA.observe(sample(1, 1, 5, 13, 15));
      expect(writerA.confirmed!.all.up, BigInt.from(13));
      expect(writerA.confirmed!.all.down, BigInt.from(15));
    },
  );

  test(
    'retirement preserves pending data and a qualified handoff never merges it twice',
    () async {
      final backend = ActivationBackend();
      final a = ActivationStore(backend)..nativeForeground();
      final writerA = LocalUsageLedger(a);
      await writerA.observe(sample(1, 1, 0, 1, 2));
      await writerA.observe(sample(1, 1, 1, 3, 4));
      final b = ActivationStore(backend)..nativeForeground();
      final writerB = LocalUsageLedger(b);
      await writerB.observe(sample(1, 1, 2, 5, 7));
      await writerB.flush();
      await expectLater(writerA.flush(), throwsA(isA<UsageWriterRetired>()));
      expect(writerA.confirmed!.all.up, BigInt.one);
      a.nativeForeground();
      await writerA.observe(sample(1, 1, 3, 9, 11));
      expect(writerA.unconfirmedHandoff!.all.up, BigInt.from(3));
      await writerA.flush();
      expect(writerA.confirmed!.all.up, BigInt.from(9));
      expect(writerA.confirmed!.all.down, BigInt.from(11));
    },
  );

  test(
    'one writer serializes operations and checkpoint cadence adds no timer writes',
    () async {
      final store = ControlledStore();
      final ledger = LocalUsageLedger(store);
      final entered = Completer<void>();
      final release = Completer<void>();
      store.entered = entered;
      store.release = release;
      final first = ledger.observe(sample(1, 1, 0, 1, 2));
      await entered.future;
      final second = ledger.observe(sample(1, 1, 1, 3, 4));
      expect(store.writes, 1);
      expect(ledger.confirmed, isNull);
      release.complete();
      await first;
      await second;
      expect(store.writes, 1);
      expect(ledger.confirmed!.all.up, BigInt.one);
      await ledger.flush();
      expect(store.writes, 2);
      expect(ledger.confirmed!.all.up, BigInt.from(3));
      await ledger.observe(sample(1, 1, 35, 3, 4));
      expect(store.writes, 2, reason: 'unchanged bytes need no periodic fsync');
    },
  );

  test(
    'a stale collector cannot claim or write a newer engine lease',
    () async {
      final store = ControlledStore();
      final ledger = LocalUsageLedger(store);
      await ledger.observe(sample(1, 1, 0, 1, 1), isCurrent: () => false);
      expect(store.claims, 0);
      expect(store.writes, 0);
      expect(ledger.confirmed, isNull);
    },
  );
}
