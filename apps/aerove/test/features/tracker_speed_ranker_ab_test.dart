import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:fl_clash/features/connection/tracker_speed_ranker.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/tracker_ranking_candidate.dart';

List<TrackerInfo> snapshot(int size, int tick, bool mixed) {
  final epoch = DateTime.utc(2026);
  return [
    for (var slot = 0; slot < size; slot++)
      if (!mixed || (slot + tick ~/ 4) % 11 != 0)
        (() {
          final index = mixed ? (slot + tick * 17) % size : slot;
          final rate = (index * 7919) % 65536;
          final elapsed = mixed && index % 7 == 0 ? tick % 40 : tick;
          final active = !mixed || index % 3 != 0;
          return TrackerInfo(
            id: index % 2 == 0 ? 'uuid:甲-$index' : '0$index',
            upload: active ? elapsed * rate : rate,
            download: active
                ? elapsed * (mixed ? (index * 3571) % 131072 : 65536 - rate)
                : rate * 10,
            start: epoch.add(Duration(milliseconds: index)),
            metadata: const Metadata(),
            chains: const [],
            rule: 'MATCH',
            rulePayload: '',
          );
        })(),
  ];
}

List<Object> signature(List<TrackerInfo> items) => [
  for (final item in items)
    (item.id, item.uploadSpeed, item.downloadSpeed),
];

Future<RankedTrackerInfos> isolatedRank(
  List<TrackerInfo> input,
  TrackerRanking previous,
  DateTime now,
) => Isolate.run(() => rankTrackerInfos(input, previous, now));

void main() {
  test('candidate and isolate preserve mixed snapshot behavior', () async {
    final baseline = TrackerSpeedRanker();
    var candidate = TrackerRanking.empty;
    var isolated = TrackerRanking.empty;
    final epoch = DateTime.utc(2026);
    for (var tick = 0; tick < 90; tick++) {
      final input = tick == 30 ? <TrackerInfo>[] : snapshot(75, tick, true);
      final now = epoch.add(Duration(
        milliseconds: tick * 1000 - (tick % 17 == 0 ? 1500 : 0),
      ));
      final expected = baseline.rank(input, now);
      final actual = rankTrackerInfos(input, candidate, now);
      final transferred = await isolatedRank(input, isolated, now);
      expect(signature(actual.trackerInfos), signature(expected));
      expect(signature(transferred.trackerInfos), signature(expected));
      candidate = actual.ranking;
      isolated = transferred.ranking;
    }
  });

  test('measures synchronous and transferred candidate total cost', () async {
    for (final mixed in [false, true]) {
      for (final size in [100, 1000, 10000]) {
        final baseline = TrackerSpeedRanker();
        var candidate = TrackerRanking.empty;
        var isolated = TrackerRanking.empty;
        final timings = List.generate(3, (_) => <int>[]);
        for (var tick = 0; tick < 100; tick++) {
          final input = snapshot(size, tick, mixed);
          final now = DateTime.utc(2026).add(Duration(seconds: tick));
          final results = <int, List<TrackerInfo>>{};
          for (var offset = 0; offset < 3; offset++) {
            final variant = (tick + offset) % 3;
            final watch = Stopwatch()..start();
            switch (variant) {
              case 0:
                results[variant] = baseline.rank(input, now);
              case 1:
                final result = rankTrackerInfos(input, candidate, now);
                candidate = result.ranking;
                results[variant] = result.trackerInfos;
              case 2:
                final result = await isolatedRank(input, isolated, now);
                isolated = result.ranking;
                results[variant] = result.trackerInfos;
            }
            watch.stop();
            if (tick >= 20) timings[variant].add(watch.elapsedMicroseconds);
          }
          expect(signature(results[1]!), signature(results[0]!));
          expect(signature(results[2]!), signature(results[0]!));
        }
        for (var variant = 0; variant < 3; variant++) {
          final times = timings[variant]..sort();
          stdout.writeln(jsonEncode({
            'benchmark': 'ranking-ab-total-cost',
            'runtime': Platform.version,
            'workload': mixed ? 'mixed-churn-idle-reset' : 'steady-equal-rate',
            'connectionsRequested': size,
            'variant': ['production', 'candidate-sync', 'candidate-isolate'][variant],
            'warmup': 20,
            'samples': times.length,
            'medianUs': times[times.length ~/ 2],
            'p95Us': times[(times.length * 0.95).ceil() - 1],
            'maxUs': times.last,
          }));
        }
      }
    }
  }, skip: !const bool.fromEnvironment('AEROVE_RANKING_BENCHMARK'));
}
