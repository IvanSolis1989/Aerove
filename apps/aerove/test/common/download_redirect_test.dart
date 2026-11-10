import 'package:fl_clash/common/download_redirect.dart';
import 'package:test/test.dart';

void main() {
  DownloadRedirectPolicy policy(String url, {int limit = 5}) =>
      DownloadRedirectPolicy(Uri.parse(url), maxRedirects: limit);

  Matcher rejected(String reason) => throwsA(
    isA<DownloadRedirectException>().having(
      (error) => error.reason,
      'reason',
      reason,
    ),
  );

  for (final scheme in ['http', 'https']) {
    test('$scheme relative redirects preserve the origin and new query', () {
      final next = policy('$scheme://example.test/sub?secret=old').follow(
        '../next?value=new',
        {'Authorization': 'synthetic', 'Host': 'example.test'},
      );
      expect(next.uri.toString(), '$scheme://example.test/next?value=new');
      expect(next.headers, {'Authorization': 'synthetic'});
    });
  }

  test('HTTP may upgrade to HTTPS and HTTPS may change destinations', () {
    final redirects = policy('http://example.test/start');
    expect(
      redirects.follow('https://example.test/secure', {}).uri.scheme,
      'https',
    );
    expect(
      redirects.follow('https://other.test/end', {}).uri.host,
      'other.test',
    );
    expect(
      () => redirects.follow('http://other.test/end', {}),
      rejected('HTTPS downgrade'),
    );
  });

  for (final destination in [
    'https://other.test/end',
    'https://example.test:444/end',
    'https://child.example.test/end',
  ]) {
    test('credentials are stripped on origin change to $destination', () {
      final source = {
        'aUtHoRiZaTiOn': 'synthetic',
        'Cookie': 'synthetic',
        'Proxy-Authorization': 'synthetic',
        'WWW-Authenticate': 'synthetic',
        'Proxy-Authenticate': 'synthetic',
        'Host': 'example.test',
        'User-Agent': 'preserved',
      };
      final redirects = policy('https://example.test/start');
      final next = redirects.follow(destination, source);
      expect(next.headers, {'User-Agent': 'preserved'});
      expect(source, contains('aUtHoRiZaTiOn'));
      expect(
        redirects.follow('https://example.test/return', next.headers).headers,
        {'User-Agent': 'preserved'},
      );
    });
  }

  test('an HTTP upgrade does not forward cleartext-origin credentials', () {
    expect(
      policy('http://example.test/start').follow('https://example.test/end', {
        'Authorization': 'synthetic',
      }).headers,
      isEmpty,
    );
  });

  for (final destination in [
    '',
    'ftp://example.test/file',
    'file:///tmp/synthetic',
    'data:text/plain,synthetic',
    'https://synthetic:password@example.test/end',
    'https://[invalid',
    'https://example.test:65536/end',
  ]) {
    test('invalid redirect destination is rejected without echoing it', () {
      final redirects = policy('https://example.test/start');
      expect(
        () => redirects.follow(destination, {}),
        rejected('invalid destination'),
      );
    });
  }

  test('redirects have a finite exact hop budget', () {
    final redirects = policy('https://example.test/0');
    for (var hop = 1; hop <= 5; hop++) {
      expect(redirects.follow('/$hop', {}).uri.path, '/$hop');
    }
    expect(() => redirects.follow('/6', {}), rejected('too many redirects'));
  });

  test('loops are detected even when fragments differ', () {
    final redirects = policy('https://example.test/start#first');
    redirects.follow('/middle', {});
    expect(
      () => redirects.follow('/start#second', {}),
      rejected('redirect loop'),
    );
  });
}
