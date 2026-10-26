import 'package:fl_clash/common/traffic_format.dart';
import 'package:test/test.dart';

/// The table the Lite edition's TrafficFormat test reads as well.
const _golden = <(int, String)>[
  (0, '0 B'),
  (999, '999 B'),
  (1000, '1.0 KB'),
  (1023, '1.0 KB'),
  (1024, '1.0 KB'),
  (1536, '1.5 KB'),
  (3686, '3.6 KB'),
  (10547, '10.3 KB'),
  (131379, '128.3 KB'),
  (393830, '384.6 KB'),
  (524800, '512.5 KB'),
  (1023948, '999.9 KB'),
  (1023949, '1.0 MB'),
  (1048575, '1.0 MB'),
  (1048576, '1.0 MB'),
  (1072668082, '1.0 GB'),
  (1073741824, '1.0 GB'),
  (1099511627776, '1.0 TB'),
  (1125899906842624, '1024 TB'),
  (9223372036854775807, '8388608 TB'),
];

void main() {
  test('amounts follow the shared table', () {
    for (final (bytes, shown) in _golden) {
      expect(trafficAmount(bytes), shown, reason: '$bytes');
    }
  });

  test('a rate is the amount per second in every language', () {
    for (final (bytes, shown) in _golden) {
      expect(trafficRate(bytes), '$shown/s', reason: '$bytes');
    }
    expect(trafficRate(10547), '10.3 KB/s');
  });

  test('negative, fractional and non-finite readings stay whole bytes', () {
    expect(trafficAmount(-5), '0 B');
    expect(trafficAmount(double.nan), '0 B');
    expect(trafficAmount(double.negativeInfinity), '0 B');
    expect(trafficAmount(999.4), '999 B');
    expect(trafficAmount(1536.0), '1.5 KB');
    expect(trafficAmount(double.infinity), '8388608 TB');
  });

  test('a unit holds three integer digits and keeps its decimal', () {
    for (var bytes = 0; bytes < 1 << 22; bytes += 97) {
      final parts = trafficParts(bytes);
      final integer = parts.value.split('.').first;
      expect(integer.length, lessThanOrEqualTo(3), reason: '$bytes');
      expect(parts.value.contains('.'), parts.unit != 'B', reason: '$bytes');
      expect(parts.value.contains(','), isFalse);
    }
  });

  test('no reading below a terabyte is wider than its unit template', () {
    for (final bytes in [999, 1023948, 1048064000, 1073217536000]) {
      final shown = trafficAmount(bytes);
      final template = trafficAmountTemplates.firstWhere(
        (item) => item.endsWith(' ${trafficParts(bytes).unit}'),
      );
      expect(shown.length, template.length, reason: shown);
    }
    expect(trafficRateTemplates, [
      for (final template in trafficAmountTemplates) '$template/s',
    ]);
  });
}
