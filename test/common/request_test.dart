import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/request.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';

class _StalledHttpClient extends Fake implements HttpClient {
  @override
  Duration? connectionTimeout;

  @override
  String Function(Uri url)? findProxy;

  @override
  Future<HttpClientRequest> openUrl(String method, Uri url) =>
      Completer<HttpClientRequest>().future;
}

class _HostAdapter implements HttpClientAdapter {
  _HostAdapter(this.byHost);

  final Map<String, ResponseBody Function()> byHost;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return byHost[options.uri.host]!();
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('getTextResponseForUrl propagates the typed DioException', () async {
    // flutter_test's mocked HttpClient answers every request with HTTP 400,
    // which Dio surfaces as a badResponse DioException.
    await expectLater(
      request.getTextResponseForUrl('http://127.0.0.1/anything'),
      throwsA(
        isA<DioException>().having(
          (e) => e.type,
          'type',
          DioExceptionType.badResponse,
        ),
      ),
    );
  });

  test('getFileResponseForUrl propagates the typed DioException', () async {
    await expectLater(
      request.getFileResponseForUrl('http://127.0.0.1/anything'),
      throwsA(
        isA<DioException>().having(
          (e) => e.type,
          'type',
          DioExceptionType.badResponse,
        ),
      ),
    );
  });

  group('latestRelease', () {
    Request requestAnswering(Map<String, ResponseBody Function()> byHost) {
      return Request()..dio.httpClientAdapter = _HostAdapter(byHost);
    }

    test('reads the release from the API', () async {
      Uri? requested;
      final request = requestAnswering({
        'api.github.com': () => ResponseBody.fromString(
          '{"tag_name":"v1.2.3","body":"notes"}',
          200,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType],
          },
        ),
      });
      request.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            requested = options.uri;
            handler.next(options);
          },
        ),
      );

      expect(await request.latestRelease(), {
        'tag_name': 'v1.2.3',
        'body': 'notes',
      });
      expect(
        requested,
        Uri.parse(
          'https://api.github.com/repos/IvanSolis1989/Aerove/releases/latest',
        ),
      );
    });

    test(
      'falls back to the release page when the API is rate limited',
      () async {
        final request = requestAnswering({
          'api.github.com': () => ResponseBody.fromString('{}', 403),
          'github.com': () => ResponseBody.fromString(
            '',
            302,
            headers: {
              HttpHeaders.locationHeader: [
                'https://github.com/owner/repo/releases/tag/v1.2.4',
              ],
            },
          ),
        });

        expect(await request.latestRelease(), {'tag_name': 'v1.2.4'});
      },
    );

    test('fails instead of answering when neither endpoint does', () async {
      final request = requestAnswering({
        'api.github.com': () => ResponseBody.fromString('{}', 403),
        'github.com': () => ResponseBody.fromString('', 503),
      });

      await expectLater(request.latestRelease(), throwsA(isA<DioException>()));
    });
  });

  group('checkForUpdate', () {
    for (final (description, installedVersion, tag, hasUpdate) in [
      (
        'same version and build is current',
        '0.9.0',
        'v0.9.0+2026101402',
        false,
      ),
      ('a higher build is an update', '0.9.0', 'v0.9.0+2026101403', true),
      (
        'a lower build is already superseded',
        '0.9.0',
        'v0.9.0+2026101401',
        false,
      ),
      (
        'same upstream version without a build is current',
        '0.9.0',
        'v0.9.0',
        false,
      ),
      (
        'higher upstream version without a build updates',
        '0.9.0',
        'v0.9.1',
        true,
      ),
      (
        'the installed preview and build is current',
        '0.9.1-preview.4-X',
        'v0.9.1-preview.4-X+2026101402',
        false,
      ),
      (
        'the installed dev version and build is current',
        '0.9.1-dev.4-X',
        'v0.9.1-dev.4-X+2026101402',
        false,
      ),
      (
        'a higher preview identifier updates with the same build',
        '0.9.1-preview.4-X',
        'v0.9.1-preview.10-X+2026101402',
        true,
      ),
      (
        'a higher dev identifier updates with the same build',
        '0.9.1-dev.4-X',
        'v0.9.1-dev.10-X+2026101402',
        true,
      ),
    ]) {
      test(description, () async {
        globalState.packageInfo = PackageInfo(
          appName: 'Aerove',
          packageName: 'com.follow.clash',
          version: installedVersion,
          buildNumber: '2026101402',
        );
        final release = <String, dynamic>{'tag_name': tag};
        final request = Request()
          ..dio.httpClientAdapter = _HostAdapter({
            'api.github.com': () => ResponseBody.fromString(
              jsonEncode(release),
              200,
              headers: {
                Headers.contentTypeHeader: [Headers.jsonContentType],
              },
            ),
          });
        addTearDown(() => request.dio.close(force: true));

        expect(await request.checkForUpdate(), hasUpdate ? release : isNull);
      });
    }
  });

  group('downloadTarget', () {
    test('sends a URL without credentials unchanged', () {
      final target = downloadTarget('https://example.com/a.yaml?x=1');

      expect(target.uri.toString(), 'https://example.com/a.yaml?x=1');
      expect(target.headers, isEmpty);
    });

    test('moves decoded credentials into the Authorization header', () {
      for (final url in [
        'https://me@mail.com:p:w@dav.example.com/dav/a.yaml',
        'https://me%40mail.com:p%3Aw@dav.example.com/dav/a.yaml',
      ]) {
        final target = downloadTarget(url);

        expect(target.uri.toString(), 'https://dav.example.com/dav/a.yaml');
        expect(
          target.headers[HttpHeaders.authorizationHeader],
          'Basic ${base64.encode(utf8.encode('me@mail.com:p:w'))}',
        );
      }
    });
  });

  group('decompressUnlabeled', () {
    final yaml = utf8.encode('proxies: []\n');

    test('inflates a gzip or zlib body that arrived unlabeled', () {
      for (final codec in [gzip, zlib]) {
        final body = Uint8List.fromList(codec.encode(yaml));

        expect(decompressUnlabeled(body), yaml);
      }
    });

    test('leaves a plain body untouched', () {
      final body = Uint8List.fromList(yaml);

      expect(decompressUnlabeled(body), same(body));
    });

    test('keeps a body that only starts like gzip', () {
      final body = Uint8List.fromList([0x1f, 0x8b, 1, 2, 3]);

      expect(decompressUnlabeled(body), same(body));
    });
  });

  testWidgets('a stalled download fails at the shared download deadline', (
    tester,
  ) async {
    late final Future<Response<String>> pending;
    HttpOverrides.runZoned(() {
      pending = Request().getTextResponseForUrl('http://127.0.0.1/stalled');
    }, createHttpClient: (_) => _StalledHttpClient());
    var settled = false;
    final outcome = expectLater(
      pending.whenComplete(() => settled = true),
      throwsA(
        isA<DioException>().having(
          (e) => e.type,
          'type',
          DioExceptionType.receiveTimeout,
        ),
      ),
    );

    await tester.pump(downloadTimeoutDuration - const Duration(seconds: 1));
    expect(settled, isFalse);
    await tester.pump(const Duration(seconds: 1));
    await outcome;
  });
}
