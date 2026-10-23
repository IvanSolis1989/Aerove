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

Map<String, dynamic> releaseFixture(String tag) => {
  'tag_name': tag,
  'body': '- notes',
  'assets': [
    {
      'name': 'Aerove-android-arm64.apk',
      'browser_download_url':
          'https://github.com/$repository/releases/download/$tag/Aerove-android-arm64.apk',
    },
  ],
};

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

  group('publishedReleases', () {
    test('requests all channels from the paginated list endpoint', () async {
      final pages = <int>[];
      final request = Request();
      request.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            expect(options.uri.path, '/repos/$repository/releases');
            expect(options.queryParameters['per_page'], 100);
            final page = options.queryParameters['page'] as int;
            pages.add(page);
            handler.resolve(
              Response(
                requestOptions: options,
                data: page == 1
                    ? List.generate(100, (_) => {'tag_name': 'lite-v1.0.0+1'})
                    : [releaseFixture('v1.0.0-beta.10+3')],
              ),
            );
          },
        ),
      );
      addTearDown(() => request.dio.close(force: true));
      expect((await request.publishedReleases()).length, 101);
      expect(pages, [1, 2]);
    });

    test(
      'API failure never consults stable latest or reports current',
      () async {
        globalState.packageInfo = PackageInfo(
          appName: 'Aerove',
          packageName: 'com.follow.clash',
          version: '0.9.2-beta.4',
          buildNumber: '2026102301',
        );
        final hosts = <String>[];
        final request = Request()
          ..dio.httpClientAdapter = _HostAdapter({
            'api.github.com': () => ResponseBody.fromString('{}', 403),
          });
        request.dio.interceptors.add(
          InterceptorsWrapper(
            onRequest: (options, handler) {
              hosts.add(options.uri.host);
              handler.next(options);
            },
          ),
        );
        addTearDown(() => request.dio.close(force: true));
        await expectLater(
          request.checkForUpdate(),
          throwsA(isA<DioException>()),
        );
        expect(hosts, ['api.github.com']);
      },
    );

    test(
      'a truncated scan is an error even with a usable earlier page',
      () async {
        final request = Request();
        var calls = 0;
        request.dio.interceptors.add(
          InterceptorsWrapper(
            onRequest: (options, handler) {
              calls++;
              handler.resolve(
                Response(
                  requestOptions: options,
                  data: List.generate(
                    100,
                    (_) => releaseFixture('v1.0.0-beta.10+3'),
                  ),
                ),
              );
            },
          ),
        );
        addTearDown(() => request.dio.close(force: true));
        await expectLater(request.publishedReleases(), throwsStateError);
        expect(calls, 5);
      },
    );
  });

  group('all-channel update selection', () {
    test('published beta4 encoded tag and historical XClash assets remain valid', () {
      const tag = 'v0.10.0-beta.4+2026102220';
      final row = releaseFixture(tag);
      (row['assets'] as List).first['browser_download_url'] =
          'https://github.com/$repository/releases/download/v0.10.0-beta.4%2B2026102220/Aerove-android-arm64.apk';
      row['html_url'] =
          'https://github.com/$repository/releases/tag/v0.10.0-beta.4%2B2026102220';
      expect(
        selectStandardUpdate(
          [row],
          version: '0.10.0-beta.1',
          buildNumber: 2026102208,
          androidAbis: ['arm64-v8a'],
        )?['tag_name'],
        tag,
      );
      expect(standardReleasePage(row).toString(), row['html_url']);
      final old = releaseFixture('v0.9.1-X+2026101741');
      (old['assets'] as List).first['name'] = 'XClash-android-arm64.apk';
      (old['assets'] as List).first['browser_download_url'] =
          'https://github.com/$repository/releases/download/v0.9.1-X%2B2026101741/XClash-android-arm64.apk';
      expect(
        selectStandardUpdate(
          [old],
          version: '0.9.1-X',
          buildNumber: 2026101737,
          androidAbis: ['arm64-v8a'],
        )?['tag_name'],
        old['tag_name'],
      );
    });
    test('old stable latest cannot hide a newer beta in the list', () {
      final rows = [
        releaseFixture('v0.9.1+1'),
        releaseFixture('v0.9.2-beta.2+3'),
        releaseFixture('v0.9.2-beta.10+4'),
      ];
      expect(
        selectStandardUpdate(
          rows,
          version: '0.9.2-beta.1',
          buildNumber: 2,
          androidAbis: ['arm64-v8a'],
        )?['tag_name'],
        'v0.9.2-beta.10+4',
      );
    });
    test(
      'RC and stable order, equal semver higher build and no code rollback',
      () {
        for (final (tag, expected) in [
          ('v0.9.2-rc.1+3', true),
          ('v0.9.2+3', true),
          ('v0.9.2-beta.2+3', true),
          ('v0.9.2-beta.2+2', false),
          ('v0.9.2-beta.10+1', false),
          ('v0.9.1+9', false),
          ('v0.9.3+0', false),
          ('v0.9.3+2100000001', false),
        ]) {
          expect(
            selectStandardUpdate(
                  [releaseFixture(tag)],
                  version: '0.9.2-beta.2',
                  buildNumber: 2,
                  androidAbis: ['arm64-v8a'],
                ) !=
                null,
            expected,
            reason: tag,
          );
        }
        expect(
          selectStandardUpdate(
            [releaseFixture('v0.9.2-rc.99+9')],
            version: '0.9.2',
            buildNumber: 2,
            androidAbis: ['arm64-v8a'],
          ),
          isNull,
        );
      },
    );
    test('drafts, Lite, invalid tags and wrong assets are excluded', () {
      final good = releaseFixture('v0.9.2-beta.10+4');
      final foreign = releaseFixture('v99.0.0+99');
      (foreign['assets'] as List).first['browser_download_url'] =
          'https://github.com/other/repo/releases/download/v99.0.0+99/Aerove-android-arm64.apk';
      final lite = releaseFixture('v98.0.0+98');
      (lite['assets'] as List).first['name'] = 'AEROVE-Lite-android-arm64.apk';
      expect(
        selectStandardUpdate(
          [
            {...releaseFixture('v97.0.0+97'), 'draft': true},
            releaseFixture('lite-v99.0.0+99'),
            releaseFixture('invalid'),
            foreign,
            lite,
            good,
          ],
          version: '0.9.2-beta.1',
          buildNumber: 2,
          androidAbis: ['arm64-v8a'],
        ),
        equals(good),
      );
    });
    test(
      'desktop opens the selected page and never latest or a foreign URL',
      () {
        final row = releaseFixture('v0.9.2-beta.10+4');
        final exact =
            'https://github.com/$repository/releases/tag/v0.9.2-beta.10+4';
        expect(
          standardReleasePage({...row, 'html_url': exact}).toString(),
          exact,
        );
        expect(
          standardReleasePage({
            ...row,
            'html_url': 'https://evil.test/latest',
          }).toString(),
          exact,
        );
      },
    );
  });

  group('checkForUpdate', () {
    test(
      'loads legacy notes for the exact tag while preserving APK assets',
      () async {
        globalState.packageInfo = PackageInfo(
          appName: 'Aerove',
          packageName: 'com.follow.clash',
          version: '0.9.1-X',
          buildNumber: '2026101804',
        );
        const tag = 'v0.9.1-X+2026101815';
        final assets = [
          {
            'name': 'Aerove-android-arm64.apk',
            'browser_download_url':
                'https://github.com/$repository/releases/download/$tag/Aerove-android-arm64.apk',
          },
        ];
        final request = Request()
          ..dio.httpClientAdapter = _HostAdapter({
            'api.github.com': () => ResponseBody.fromString(
              jsonEncode([
                {
                  ...releaseFixture(tag),
                  'body': '# Aerove Android\n模板',
                  'assets': assets,
                },
              ]),
              200,
              headers: {
                Headers.contentTypeHeader: [Headers.jsonContentType],
              },
            ),
            'raw.githubusercontent.com': () => ResponseBody.fromString(
              '## 待发布\n- 下一版\n'
              '## 0.9.1-X+2026101815 — 已发布\n- 本次真正的修复\n'
              '## 0.9.1-X+2026101804 — 已发布\n- 上一版\n',
              200,
            ),
          });
        addTearDown(() => request.dio.close(force: true));
        final result = await request.checkForUpdate();
        expect(result?['tag_name'], tag);
        expect(result?['assets'], assets);
        expect(result?['body'], contains('本次真正的修复'));
        expect(result?['body'], isNot(contains('上一版')));
        expect(result?['body'], isNot(contains('下一版')));
      },
    );

    test(
      'notes failure keeps the target release and does not use other versions',
      () async {
        globalState.packageInfo = PackageInfo(
          appName: 'Aerove',
          packageName: 'com.follow.clash',
          version: '0.9.1-X',
          buildNumber: '2026101804',
        );
        final release = {
          ...releaseFixture('v0.9.1-X+2026101815'),
          'body': '# Aerove Android\n模板',
        };
        for (final response in [
          ResponseBody.fromString('## 0.9.1-X+2026101804 — 已发布\n- 旧记录', 200),
          ResponseBody.fromString('', 503),
        ]) {
          final request = Request()
            ..dio.httpClientAdapter = _HostAdapter({
              'api.github.com': () => ResponseBody.fromString(
                jsonEncode([release]),
                200,
                headers: {
                  Headers.contentTypeHeader: [Headers.jsonContentType],
                },
              ),
              'raw.githubusercontent.com': () => response,
            });
          addTearDown(() => request.dio.close(force: true));
          expect(await request.checkForUpdate(), release);
        }
      },
    );

    for (final (description, installedVersion, tag, hasUpdate) in [
      (
        'same version and build is current',
        '0.9.0',
        'v0.9.0+2026101402',
        false,
      ),
      ('a higher build is an update', '0.9.0', 'v0.9.0+2026101403', true),
      (
        'an independent product build updates a historical branded build',
        '0.9.1-X',
        'v0.9.1+2026101403',
        true,
      ),
      (
        'removing the historical suffix alone is not an update',
        '0.9.1-X',
        'v0.9.1+2026101402',
        false,
      ),
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
        final release = releaseFixture(tag);
        final request = Request()
          ..dio.httpClientAdapter = _HostAdapter({
            'api.github.com': () => ResponseBody.fromString(
              jsonEncode([release]),
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
