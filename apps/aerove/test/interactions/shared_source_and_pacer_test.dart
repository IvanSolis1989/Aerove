import 'dart:async';
import 'dart:convert';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class _Core extends Mock implements CoreHandlerInterface {}

TrackerInfo _item(int bytes) => TrackerInfo(
  id: 'a',
  upload: bytes,
  start: DateTime.utc(2026),
  metadata: const Metadata(host: 'a'),
  chains: const ['Proxy'],
  rule: 'MATCH',
  rulePayload: '',
);

void main() {
  testWidgets(
    'concurrent views share one read and a one-second snapshot; close invalidates it',
    (tester) async {
      final core = _Core();
      final gate = Completer<List<TrackerInfo>>();
      when(() => core.getConnections()).thenAnswer((_) => gate.future);
      when(() => core.closeConnections()).thenAnswer((_) async => true);
      final source = CoreController.scoped(core);
      final first = source.getConnections();
      final second = source.getConnections();
      verify(() => core.getConnections()).called(1);
      gate.complete([_item(1)]);
      expect((await first).single.upload, 1);
      expect((await second).single.upload, 1);
      await source.getConnections();
      verifyNever(() => core.getConnections());
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 1050)),
      );
      when(() => core.getConnections()).thenAnswer((_) async => [_item(2)]);
      expect((await source.getConnections()).single.upload, 2);
      verify(() => core.getConnections()).called(1);
      await source.closeConnections();
      expect((await source.getConnections()).single.upload, 2);
      verify(() => core.getConnections()).called(1);
    },
  );

  testWidgets(
    'a failed shared read is retried and an invalidated late read cannot seed the cache',
    (tester) async {
      final core = _Core();
      when(() => core.closeConnections()).thenAnswer((_) async => true);
      when(
        () => core.getConnections(),
      ).thenAnswer((_) async => throw StateError('read'));
      final source = CoreController.scoped(core);
      await expectLater(source.getConnections(), throwsStateError);
      final old = Completer<List<TrackerInfo>>();
      when(() => core.getConnections()).thenAnswer((_) => old.future);
      final pending = source.getConnections();
      await source.closeConnections();
      when(() => core.getConnections()).thenAnswer((_) async => [_item(9)]);
      expect((await source.getConnections()).single.upload, 9);
      old.complete([_item(1)]);
      await pending;
      expect((await source.getConnections()).single.upload, 9);
    },
  );

  test(
    'tracker identity cache keeps metadata, updates counters, drops closed ids and replaces reused ids',
    () {
      final cache = TrackerInfoCache();
      Map<String, Object?> json(int bytes) => Map<String, Object?>.from(
        jsonDecode(jsonEncode(_item(bytes))) as Map,
      );
      final first = cache.decode({
        'connections': [json(1)],
      }).single;
      final unchanged = cache.decode({
        'connections': [json(1)],
      }).single;
      expect(identical(first, unchanged), isTrue);
      final updated = cache.decode({
        'connections': [json(2)],
      }).single;
      expect(updated.upload, 2);
      expect(identical(first.metadata, updated.metadata), isTrue);
      expect(cache.decode({'connections': []}), isEmpty);
      final reopened = cache.decode({
        'connections': [json(2)],
      }).single;
      expect(identical(updated, reopened), isFalse);
      final replacement = _item(3).copyWith(
        start: DateTime.utc(2027),
        metadata: const Metadata(host: 'new'),
      );
      expect(cache.decode([replacement]).single.metadata.host, 'new');
    },
  );

  testWidgets(
    'pending updates use the next paced frame, coalesce and flush at last stop',
    (tester) async {
      final pacer = FramePacer((_) {}, framesPerSecond: 10);
      addTearDown(pacer.dispose);
      pacer.start();
      await tester.pump();
      var value = 0;
      final owner = Object();
      FramePacer.publish(owner, () => value = 1);
      FramePacer.publish(owner, () => value = 2);
      expect(value, 0);
      await tester.pump(const Duration(milliseconds: 100));
      expect(value, 2);
      FramePacer.publish(owner, () => value = 3);
      pacer.stop();
      expect(value, 3);
      FramePacer.publish(owner, () => value = 4);
      expect(value, 4);
      expect(tester.binding.transientCallbackCount, 0);
    },
  );
}
