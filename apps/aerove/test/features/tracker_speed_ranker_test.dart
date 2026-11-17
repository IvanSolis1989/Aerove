import 'package:fl_clash/features/connection/tracker_speed_ranker.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

TrackerInfo _tracker(
  String id, {
  int upload = 0,
  int download = 0,
  DateTime? start,
}) {
  return TrackerInfo(
    id: id,
    upload: upload,
    download: download,
    start: start ?? DateTime.utc(2026),
    metadata: const Metadata(),
    chains: const [],
    rule: 'MATCH',
    rulePayload: '',
  );
}

void main() {
  final t0 = DateTime.utc(2026, 1, 1, 12);
  DateTime at(double seconds) =>
      t0.add(Duration(milliseconds: (seconds * 1000).round()));
  List<String> ids(List<TrackerInfo> trackerInfos) =>
      trackerInfos.map((trackerInfo) => trackerInfo.id).toList();

  test('reports no speed on the first sample and ranks by traffic', () {
    final ranked = TrackerSpeedRanker().rank([
      _tracker('a', download: 100),
      _tracker('b', download: 900),
    ], t0);

    expect(ids(ranked), ['b', 'a']);
    expect(ranked.map((trackerInfo) => trackerInfo.downloadSpeed), [0, 0]);
  });

  test('derives speed from the delta over the elapsed time', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a', upload: 100, download: 1000)], t0);

    final ranked = ranker.rank([
      _tracker('a', upload: 1100, download: 5000),
    ], at(2));

    expect(ranked.single.uploadSpeed, 500);
    expect(ranked.single.downloadSpeed, 2000);
  });

  test('puts an active connection above a larger idle one', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('idle', download: 1 << 30), _tracker('active')], t0);

    final ranked = ranker.rank([
      _tracker('idle', download: 1 << 30),
      _tracker('active', download: 4096),
    ], at(1));

    expect(ids(ranked), ['active', 'idle']);
  });

  test('keeps the order of connections with similar speeds', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a'), _tracker('b')], t0);
    final first = ranker.rank([
      _tracker('a', download: 5000),
      _tracker('b', download: 4500),
    ], at(1));
    expect(ids(first), ['a', 'b']);

    final second = ranker.rank([
      _tracker('a', download: 10000),
      _tracker('b', download: 10200),
    ], at(2));

    expect(ids(second), ['a', 'b']);
    expect(second.last.downloadSpeed, greaterThan(second.first.downloadSpeed!));
  });

  test('ignores a sample taken too soon after the previous one', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a')], t0);
    ranker.rank([_tracker('a', download: 1000)], at(1));

    final ranked = ranker.rank([_tracker('a', download: 1100)], at(1.05));

    expect(ranked.single.downloadSpeed, 1000);
  });

  test('keeps opaque string identities distinct', () {
    final ranked = TrackerSpeedRanker().rank([
      _tracker('01', download: 1),
      _tracker('1', download: 2),
      _tracker('mihomo:uuid-甲', download: 3),
    ], t0);

    expect(ids(ranked), ['mihomo:uuid-甲', '1', '01']);
  });

  test('clamps counter resets and resumes from the reset counters', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a', upload: 1000, download: 1000)], t0);
    final reset = ranker.rank([_tracker('a', upload: 10, download: 20)], at(1));
    final resumed = ranker.rank([
      _tracker('a', upload: 110, download: 220),
    ], at(2));

    expect(reset.single.uploadSpeed, 0);
    expect(reset.single.downloadSpeed, 0);
    expect(resumed.single.uploadSpeed, 100);
    expect(resumed.single.downloadSpeed, 200);
  });

  test('forgets a closed connection before the same id returns', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a')], t0);
    ranker.rank([_tracker('a', download: 1000)], at(1));
    expect(ranker.rank([], at(2)), isEmpty);
    final reopened = ranker.rank([_tracker('a', download: 9000)], at(3));

    expect(reopened.single.downloadSpeed, 0);
  });

  test('does not replace the sample on a backwards clock', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a')], t0);
    ranker.rank([_tracker('a', download: 1000)], at(1));
    final backwards = ranker.rank([_tracker('a', download: 2000)], at(-1));
    final resumed = ranker.rank([_tracker('a', download: 3000)], at(2));

    expect(backwards.single.downloadSpeed, 1000);
    expect(resumed.single.downloadSpeed, 2000);
  });

  test('accepts a sample exactly at the minimum interval', () {
    final ranker = TrackerSpeedRanker();
    ranker.rank([_tracker('a')], t0);
    final ranked = ranker.rank([_tracker('a', download: 1000)], at(0.5));

    expect(ranked.single.downloadSpeed, 2000);
  });

  test('breaks idle ties by newest start then opaque id', () {
    final ranked = TrackerSpeedRanker().rank([
      _tracker('old', start: at(-1)),
      _tracker('b', start: t0),
      _tracker('a', start: t0),
    ], t0);

    expect(ids(ranked), ['a', 'b', 'old']);
  });

  test('does not mutate the caller snapshot or its speeds', () {
    final input = [_tracker('a', download: 1), _tracker('b', download: 2)];
    final originalSpeeds = input.map((item) => item.downloadSpeed).toList();
    final ranked = TrackerSpeedRanker().rank(input, t0);

    expect(ids(input), ['a', 'b']);
    expect(input.map((item) => item.downloadSpeed), originalSpeeds);
    expect(ids(ranked), ['b', 'a']);
  });

}
