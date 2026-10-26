import 'package:fl_clash/common/fixed.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

TrackerInfo item(String id, [int bytes = 0]) => TrackerInfo(
  id: id,
  start: DateTime.utc(2026),
  metadata: const Metadata(),
  chains: const [],
  rule: '',
  rulePayload: '',
  upload: bytes,
);

class ReadCountingList extends FixedList<TrackerInfo> {
  ReadCountingList(List<TrackerInfo> items) : super(items.length, list: items);
  var reads = 0;
  @override
  TrackerInfo operator [](int index) {
    reads++;
    return super[index];
  }
}

void main() {
  late ProviderContainer c;
  setUp(() => c = ProviderContainer());
  tearDown(() => c.dispose());
  test(
    'unknown ids do not scan a 2000-row history; a known id reads only its row',
    () {
      final items = ReadCountingList([
        for (var i = 0; i < 2000; i++) item('$i'),
      ]);
      final notifier = c.read(requestsProvider.notifier);
      notifier.value = items;
      items.reads = 0;
      notifier.updateTraffic({'gone': (upload: 1, download: 1)});
      expect(items.reads, 0);
      notifier.updateTraffic({
        '12': (upload: 7, download: 9),
        'missing': (upload: 8, download: 2),
      });
      expect(items.reads, 1);
      expect(c.read(requestsProvider)[12].upload, 7);
      expect(c.read(requestsProvider)[12].download, 9);
    },
  );
  test(
    'absolute indices survive wrap and only the newest duplicate is updated',
    () {
      final n = c.read(requestsProvider.notifier)..value = FixedList(3);
      n.addRequest(item('same', 1));
      n.addRequest(item('b', 2));
      n.addRequest(item('same', 3));
      n.updateTraffic({'same': (upload: 4, download: 5)});
      expect(c.read(requestsProvider).list.map((v) => v.upload), [1, 2, 4]);
      n.addRequest(item('c', 6));
      n.addRequest(item('d', 7));
      n.updateTraffic({
        'same': (upload: 8, download: 9),
        'b': (upload: 100, download: 100),
      });
      expect(c.read(requestsProvider).list.map((v) => (v.id, v.upload)), [
        ('same', 8),
        ('c', 6),
        ('d', 7),
      ]);
      n.addRequest(item('e', 10));
      final before = c.read(requestsProvider);
      n.updateTraffic({'same': (upload: 99, download: 99)});
      expect(c.read(requestsProvider), same(before));
    },
  );
  test(
    'external assignment, reset and legacy in-place edits rebuild the index',
    () {
      final n = c.read(requestsProvider.notifier)
        ..value = FixedList(2, list: [item('old')]);
      n.value = FixedList(2, list: [item('replacement')]);
      n.updateTraffic({
        'old': (upload: 100, download: 100),
        'replacement': (upload: 5, download: 6),
      });
      expect(c.read(requestsProvider).list.single.upload, 5);
      n.value.clear();
      n.value.add(item('in-place'));
      n.updateTraffic({
        'replacement': (upload: 99, download: 99),
        'in-place': (upload: 7, download: 8),
      });
      expect(c.read(requestsProvider).list.single.id, 'in-place');
      expect(c.read(requestsProvider).list.single.upload, 7);
      n.value = FixedList(1);
      n.addRequest(item('fresh'));
      n.updateTraffic({'fresh': (upload: 9, download: 10)});
      expect(c.read(requestsProvider).list.single.upload, 9);
    },
  );
}
