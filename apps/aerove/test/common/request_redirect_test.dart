import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/download_redirect.dart';
import 'package:fl_clash/common/request.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';

class _TrustedLoopback extends HttpOverrides {
  _TrustedLoopback(this.context);
  final SecurityContext context;

  @override
  HttpClient createHttpClient(SecurityContext? context) =>
      super.createHttpClient(this.context);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late HttpServer plain;
  late HttpServer secure;
  late HttpServer other;
  late Completer<void> entered;
  late Completer<void> release;
  late int plainHits;
  late List<String> authorizations;
  String url(HttpServer server, String path, {String credentials = ''}) =>
      '${server == plain ? 'http' : 'https'}://$credentials${server.address.address}:${server.port}$path';

  Matcher rejected(String reason) => throwsA(
    isA<DioException>().having(
      (error) => error.error,
      'redirect rejection',
      isA<DownloadRedirectException>().having(
        (error) => error.reason,
        'reason',
        reason,
      ),
    ),
  );

  setUp(() async {
    final original = HttpOverrides.current;
    addTearDown(() => HttpOverrides.global = original);
    globalState.packageInfo = PackageInfo(
      appName: 'Aerove',
      packageName: 'dev.aerove.test',
      version: '1.0.0',
      buildNumber: '1',
    );
    const fixture = 'test/fixtures/download_redirect';
    final serverContext = SecurityContext()
      ..useCertificateChain('$fixture/cert.pem')
      ..usePrivateKey('$fixture/key.pem');
    HttpOverrides.global = _TrustedLoopback(
      SecurityContext()..setTrustedCertificates('$fixture/cert.pem'),
    );
    plain = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    secure = await HttpServer.bindSecure(
      InternetAddress.loopbackIPv4,
      0,
      serverContext,
    );
    other = await HttpServer.bindSecure(
      InternetAddress('127.0.0.2'),
      0,
      serverContext,
    );
    entered = Completer<void>();
    release = Completer<void>();
    plainHits = 0;
    authorizations = [];
    Future<void> serve(HttpRequest request, HttpServer server) async {
      if (server == plain) plainHits++;
      authorizations.add(
        request.headers.value(HttpHeaders.authorizationHeader) ?? '',
      );
      final path = request.uri.path;
      String? location;
      var status = 302;
      if (path.startsWith('/status/')) {
        status = int.parse(path.split('/').last);
        location = '/body';
      } else if (path.startsWith('/chain/')) {
        final remaining = int.parse(path.split('/').last);
        if (remaining > 0) location = '/chain/${remaining - 1}';
      } else {
        location = switch (path) {
          '/downgrade' => url(plain, '/body'),
          '/mixed' => url(secure, '/downgrade'),
          '/upgrade' => url(secure, '/body'),
          '/cross' => url(other, '/body'),
          '/same' => '/body',
          '/loop' => '/loop',
          '/invalid' => 'file:///synthetic',
          '/query' => '/echo-query?new=value',
          '/redirect-slow' => '/slow',
          _ => null,
        };
      }
      try {
        if (location != null) {
          request.response.statusCode = status;
          request.response.headers.set(HttpHeaders.locationHeader, location);
        } else {
          request.response.write(
            path == '/echo-query' ? request.uri.query : 'body',
          );
          if (path == '/slow') {
            await request.response.flush();
            entered.complete();
            await release.future;
          }
        }
        await request.response.close();
      } on IOException {
        // Cancellation closes the owned transport before the server finishes.
      }
    }

    plain.listen((request) => unawaited(serve(request, plain)));
    secure.listen((request) => unawaited(serve(request, secure)));
    other.listen((request) => unawaited(serve(request, other)));
  });

  tearDown(() async {
    if (!release.isCompleted) release.complete();
    await Future.wait([
      plain.close(force: true),
      secure.close(force: true),
      other.close(force: true),
    ]);
  });

  test('explicit HTTP and an HTTP to HTTPS upgrade remain allowed', () async {
    expect(
      (await Request().getTextResponseForUrl(url(plain, '/body'))).data,
      'body',
    );
    expect(
      (await Request().getTextResponseForUrl(url(plain, '/upgrade'))).data,
      'body',
    );
  });

  for (final status in [301, 302, 303, 307, 308]) {
    test(
      'HTTPS $status redirects preserve body and redirect metadata',
      () async {
        final response = await Request().getTextResponseForUrl(
          url(secure, '/status/$status'),
        );
        expect(response.data, 'body');
        expect(response.isRedirect, isTrue);
        expect(response.realUri.path, '/body');
        expect(response.redirects.single.statusCode, status);
      },
    );
  }

  test('downgrade is rejected before issuing the cleartext request', () async {
    await expectLater(
      Request().getFileResponseForUrl(url(secure, '/downgrade')),
      rejected('HTTPS downgrade'),
    );
    expect(plainHits, 0);
  });

  test('upgrading first does not permit a later downgrade', () async {
    await expectLater(
      Request().getTextResponseForUrl(url(plain, '/mixed')),
      rejected('HTTPS downgrade'),
    );
    expect(plainHits, 1);
  });

  test('same origin keeps credentials but cross origin strips them', () async {
    await Request().getTextResponseForUrl(
      url(secure, '/same', credentials: 'synthetic:password@'),
    );
    expect(authorizations, hasLength(2));
    expect(authorizations[0], isNotEmpty);
    expect(authorizations[1], authorizations[0]);
    authorizations.clear();
    await Request().getTextResponseForUrl(
      url(secure, '/cross', credentials: 'synthetic:password@'),
    );
    expect(authorizations[0], isNotEmpty);
    expect(authorizations[1], isEmpty);
  });

  test('redirect limits and loops fail without unbounded requests', () async {
    expect(
      (await Request().getTextResponseForUrl(url(secure, '/chain/5'))).data,
      'body',
    );
    await expectLater(
      Request().getTextResponseForUrl(url(secure, '/chain/6')),
      rejected('too many redirects'),
    );
    await expectLater(
      Request().getTextResponseForUrl(url(secure, '/loop')),
      rejected('redirect loop'),
    );
    await expectLater(
      Request().getTextResponseForUrl(url(secure, '/invalid')),
      rejected('invalid destination'),
    );
  });

  test('redirect query replaces the original query', () async {
    final response = await Request().getTextResponseForUrl(
      url(secure, '/query?secret=synthetic'),
    );
    expect(response.data, 'new=value');
  });

  test('cancellation after redirect joins the response body', () async {
    final cancellation = CancelToken();
    final pending = expectLater(
      Request().getFileResponseForUrl(
        url(secure, '/redirect-slow'),
        cancelToken: cancellation,
      ),
      throwsA(
        isA<DioException>().having(
          (error) => error.type,
          'type',
          DioExceptionType.cancel,
        ),
      ),
    );
    await entered.future;
    cancellation.cancel();
    await pending;
  });

  test('the size budget is still enforced after a redirect', () async {
    await expectLater(
      Request().getFileResponseForUrl(url(secure, '/same'), maxBytes: 2),
      throwsA(
        isA<DioException>().having(
          (error) => error.error,
          'size budget failure',
          isA<DownloadSizeException>(),
        ),
      ),
    );
  });

  test('pre-cancelled requests do not start a redirect chain', () async {
    final cancellation = CancelToken()..cancel();
    await expectLater(
      Request().getFileResponseForUrl(
        url(secure, '/same'),
        cancelToken: cancellation,
      ),
      throwsA(
        isA<DioException>().having(
          (error) => error.type,
          'type',
          DioExceptionType.cancel,
        ),
      ),
    );
    expect(authorizations, isEmpty);
  });

  test('the total download timeout is retained across redirects', () async {
    await expectLater(
      Request().getTextResponseForUrl(url(secure, '/redirect-slow')),
      throwsA(
        isA<DioException>().having(
          (error) => error.type,
          'type',
          DioExceptionType.receiveTimeout,
        ),
      ),
    );
    expect(authorizations, hasLength(2));
  }, timeout: const Timeout(Duration(seconds: 30)));

  test('an untrusted TLS certificate is still rejected', () async {
    HttpOverrides.global = null;
    await expectLater(
      Request().getTextResponseForUrl(url(secure, '/body')),
      throwsA(isA<DioException>()),
    );
    expect(authorizations, isEmpty);
  });
}
