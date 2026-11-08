import 'package:fl_clash/models/provider_compatibility.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Map<String, dynamic> receipt(String filter) => {
    'sourceSha256': 'synthetic-source',
    'derivedSha256': 'synthetic-derived',
    'coreIdentity': 'synthetic-core',
    'filterIdentity': filter,
    'retained': 2,
    'removedByType': {'vmess': 1, 'ssh': 1},
  };

  test('v1 receipt without reasons stays readable', () {
    final model = ProviderCompatibility.fromJson(
      receipt('meow-provider-outbounds-v1'),
    );
    expect(model.removedByReason, isEmpty);
    expect(model.removedByType, {'vmess': 1, 'ssh': 1});
    expect(model.retained, 2);
  });

  test('v2 reason and provenance survive JSON round trip', () {
    final input = receipt('meow-provider-outbounds-v2')
      ..['removedByReason'] = {'vmessLegacyAlterId': 1};
    final model = ProviderCompatibility.fromJson(input);
    expect(model.toJson(), input);
    expect(ProviderCompatibility.fromJson(model.toJson()).toJson(), input);
    expect(
      () => model.removedByReason['vmessLegacyAlterId'] = 9,
      throwsUnsupportedError,
    );
  });

  test('display identifies legacy capability, not all VMess', () {
    final text = providerCompatibilityDetails(
      {'vmess': 1, 'ssh': 1},
      {'vmessLegacyAlterId': 1},
    );
    expect(text, 'VMess alterId > 0 (legacy): 1, ssh: 1');
    expect(text, isNot(contains('vmess: 1')));
    expect(text, isNot(contains('all VMess')));
    expect(providerCompatibilityDetails({'ssh': 1}, {}), 'ssh: 1');
    expect(providerCompatibilityDetails({}, {}), isEmpty);
  });
}
