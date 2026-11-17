import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/features/connection/tracker_speed_ranker.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('records production connection ranking baseline', () {
    final epoch = DateTime.utc(2026);
    for (final size in [100, 1000, 10000]) {
      final ranker = TrackerSpeedRanker();
      final samples = <int>[];
      var checksum = 0;
      for (var iteration = 0; iteration < 120; iteration++) {
        final input = List.generate(size, (index) {
          final rate = (index * 7919) % 65536;
          return TrackerInfo(
            id: 'connection:$index',
            upload: iteration * rate,
            download: iteration * (65536 - rate),
            start: epoch.add(Duration(milliseconds: index)),
            metadata: const Metadata(),
            chains: const [],
            rule: 'MATCH',
            rulePayload: '',
          );
        });
        final watch = Stopwatch()..start();
        final output = ranker.rank(
          input,
          epoch.add(Duration(seconds: iteration)),
        );
        watch.stop();
        expect(output.length, size);
        checksum += output.first.downloadSpeed ?? 0;
        if (iteration >= 20) {
          samples.add(watch.elapsedMicroseconds);
        }
      }
      samples.sort();
      stdout.writeln(jsonEncode({
        'benchmark': 'tracker-speed-ranker-baseline',
        'runtime': Platform.version,
        'connections': size,
        'warmup': 20,
        'samples': samples.length,
        'medianUs': samples[samples.length ~/ 2],
        'p95Us': samples[(samples.length * 0.95).ceil() - 1],
        'maxUs': samples.last,
        'checksum': checksum,
      }));
    }
  }, skip: !const bool.fromEnvironment('AEROVE_RANKING_BENCHMARK'));
}
