typedef TrafficParts = ({String value, String unit});

const _units = ['B', 'KB', 'MB', 'GB', 'TB'];

/// Below a terabyte no reading of a unit is wider than its template.
const trafficAmountTemplates = [
  '999 B',
  '999.9 KB',
  '999.9 MB',
  '999.9 GB',
  '999.9 TB',
];

final trafficRateTemplates = [
  for (final template in trafficAmountTemplates) '$template/s',
];

/// Shared with the Lite edition; docs/shared/UI_CONTRACT.md holds the rule.
TrafficParts trafficParts(num bytes) {
  final whole = _wholeBytes(bytes);
  if (whole < 1000) return (value: '$whole', unit: _units.first);
  const shift = 10 * 4;
  if (whole < 1000 << shift) {
    for (var index = 1; index < _units.length; index++) {
      final bits = 10 * index;
      final tenths = (whole * 10 + (1 << (bits - 1))) >> bits;
      if (tenths < 10000) {
        return (value: '${tenths ~/ 10}.${tenths % 10}', unit: _units[index]);
      }
    }
  }
  final terabytes =
      (whole >> shift) + ((whole >> (shift - 1)) & 1 == 1 ? 1 : 0);
  return (value: '$terabytes', unit: _units.last);
}

String trafficAmount(num bytes) {
  final parts = trafficParts(bytes);
  return '${parts.value} ${parts.unit}';
}

String trafficRate(num bytes) => '${trafficAmount(bytes)}/s';

int _wholeBytes(num bytes) {
  if (bytes is int) return bytes < 0 ? 0 : bytes;
  if (bytes.isNaN || bytes <= 0) return 0;
  if (bytes >= 9.2e18) return 0x7FFFFFFFFFFFFFFF;
  return bytes.round();
}
