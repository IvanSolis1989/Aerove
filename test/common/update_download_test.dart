import 'package:fl_clash/common/update_download.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> _asset(String name, {String? url}) => {
  'name': name,
  'browser_download_url':
      url ?? 'https://github.com/IvanSolis1989/Aerove/releases/download/v1/$name',
};

void main() {
  test('selects current Aerove APK using device ABI order', () {
    final selected = selectAndroidUpdateAsset(
      {
        'assets': [
          _asset('Aerove-android-arm.apk'),
          _asset('Aerove-android-arm64.apk'),
          _asset('SHA256SUMS'),
        ],
      },
      ['arm64-v8a', 'armeabi-v7a'],
    );
    expect(selected?.name, 'Aerove-android-arm64.apk');
    expect(selected?.url.pathSegments.last, selected?.name);
  });

  test('recognizes upstream ABI names and falls back to a compatible ABI', () {
    for (final (abis, name) in [
      (['arm64-v8a'], 'FlClash-0.9.0-android-arm64-v8a.apk'),
      (['arm64-v8a', 'armeabi-v7a'], 'FlClash-0.9.0-android-armeabi-v7a.apk'),
      (['x86_64', 'x86'], 'FlClash-0.9.0-android-amd64.apk'),
      (['x86_64'], 'FlClash-0.9.0-android-x86_64.apk'),
    ]) {
      expect(
        selectAndroidUpdateAsset({
          'assets': [_asset(name)],
        }, abis)?.name,
        name,
      );
    }
  });

  test('does not guess an asset when API fallback only carries a tag', () {
    expect(selectAndroidUpdateAsset({'tag_name': 'v1'}, ['arm64-v8a']), isNull);
    expect(
      selectAndroidUpdateAsset({
        'assets': [_asset('Aerove-android-arm64.apk')],
      }, ['x86_64']),
      isNull,
    );
  });

  test('preserves the published URL including its encoded build tag', () {
    const url =
        'https://github.com/IvanSolis1989/Aerove/releases/download/v0.9.1-X%2B2026101794/Aerove-android-arm64.apk';
    final selected = selectAndroidUpdateAsset({
      'tag_name': 'v0.9.1-X+2026101794',
      'assets': [_asset('Aerove-android-arm64.apk', url: url)],
    }, ['arm64-v8a']);
    expect(selected?.url.toString(), url);
  });

  test('rejects release pages, mismatched names and non HTTPS assets', () {
    for (final url in [
      'https://github.com/IvanSolis1989/Aerove/releases/latest',
      'http://github.com/IvanSolis1989/Aerove/releases/download/v1/Aerove-android-arm64.apk',
      'https://example.com/releases/download/v1/Aerove-android-arm64.apk',
      'https://github.com/IvanSolis1989/Aerove/releases/download/v1/other.apk',
    ]) {
      expect(
        selectAndroidUpdateAsset({
          'assets': [_asset('Aerove-android-arm64.apk', url: url)],
        }, ['arm64-v8a']),
        isNull,
      );
    }
  });
}
