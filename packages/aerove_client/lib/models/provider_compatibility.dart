class ProviderCompatibility {
  final String sourceSha256;
  final String derivedSha256;
  final String coreIdentity;
  final String filterIdentity;
  final int retained;
  final Map<String, int> removedByType;
  final Map<String, int> removedByReason;

  const ProviderCompatibility({
    required this.sourceSha256,
    required this.derivedSha256,
    required this.coreIdentity,
    required this.filterIdentity,
    required this.retained,
    required this.removedByType,
    this.removedByReason = const {},
  });

  factory ProviderCompatibility.fromJson(Map<String, dynamic> json) =>
      ProviderCompatibility(
        removedByReason: Map<String, int>.unmodifiable(
          (json['removedByReason'] as Map<String, dynamic>? ?? {}).map(
            (key, value) => MapEntry(key, value as int),
          ),
        ),
        sourceSha256: json['sourceSha256'] as String,
        derivedSha256: json['derivedSha256'] as String,
        coreIdentity: json['coreIdentity'] as String,
        filterIdentity: json['filterIdentity'] as String,
        retained: json['retained'] as int,
        removedByType: Map<String, int>.unmodifiable(
          (json['removedByType'] as Map<String, dynamic>).map(
            (type, count) => MapEntry(type, count as int),
          ),
        ),
      );

  Map<String, dynamic> toJson() => {
    'sourceSha256': sourceSha256,
    'derivedSha256': derivedSha256,
    'coreIdentity': coreIdentity,
    'filterIdentity': filterIdentity,
    'retained': retained,
    'removedByType': removedByType,
    'removedByReason': removedByReason,
  };
}

String providerCompatibilityDetails(
  Map<String, int> types,
  Map<String, int> reasons,
) => types.entries
    .map((entry) {
      final legacy = reasons['vmessLegacyAlterId'] ?? 0;
      return entry.key == 'vmess' && legacy > 0
          ? 'VMess alterId > 0 (legacy): $legacy'
          : '${entry.key}: ${entry.value}';
    })
    .join(', ');
