import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/update_feed.dart';
import 'package:test/test.dart';

class _Adapter implements HttpClientAdapter {
  _Adapter(this.respond);
  final FutureOr<ResponseBody> Function(RequestOptions) respond;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancelFuture,
  ) async => respond(options);
  @override
  void close({bool force = false}) {}
}

const installed = 'v0.11.0-beta.11+2026102264';
Map<String, dynamic> row(String tag) => {
  'tag_name': tag,
  'draft': false,
  'prerelease': true,
  'assets': [
    {
      'name': 'Aerove-android-arm64.apk',
      'browser_download_url':
          'https://github.com/IvanSolis1989/Aerove/releases/download/$tag/Aerove-android-arm64.apk',
    },
  ],
};
ResponseBody jsonBody(Object value, [int status = 200]) =>
    ResponseBody.fromString(
      jsonEncode(value),
      status,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
      },
    );

void main() {
  test('403 and 429 consult only the official full feed once', () async {
    for (final status in [403, 429]) {
      final paths = <String>[];
      final dio = Dio()
        ..httpClientAdapter = _Adapter((options) {
          paths.add(options.uri.toString());
          expect(options.followRedirects, isFalse);
          expect(options.headers.containsKey('Authorization'), isFalse);
          return options.uri.host == 'api.github.com'
              ? jsonBody({'message': 'denied'}, status)
              : jsonBody([row(installed), row('v0.12.0+2026102265')]);
        });
      try {
        expect(
          (await PublishedReleaseFeed(dio).fetch(
            coversInstalledRelease: (row) => row['tag_name'] == installed,
          )).length,
          2,
        );
        expect(paths.length, 2);
        expect(paths.last, officialReleaseFeed);
      } finally {
        dio.close(force: true);
      }
    }
  });

  test('successful API including empty list does not consult backup', () async {
    var calls = 0;
    final dio = Dio()
      ..httpClientAdapter = _Adapter((_) {
        calls++;
        return jsonBody([]);
      });
    try {
      expect(
        await PublishedReleaseFeed(
          dio,
        ).fetch(coversInstalledRelease: (row) => row['tag_name'] == installed),
        isEmpty,
      );
      expect(calls, 1);
    } finally {
      dio.close(force: true);
    }
  });

  test('non 403/429 API failures do not trigger fallback', () async {
    for (final status in [401, 404, 500]) {
      var calls = 0;
      final dio = Dio()
        ..httpClientAdapter = _Adapter((_) {
          calls++;
          return jsonBody({}, status);
        });
      try {
        await expectLater(
          PublishedReleaseFeed(dio).fetch(
            coversInstalledRelease: (row) => row['tag_name'] == installed,
          ),
          throwsA(isA<DioException>()),
        );
        expect(calls, 1);
      } finally {
        dio.close(force: true);
      }
    }
  });

  test(
    'empty malformed stale or failed backup preserves original API 403',
    () async {
      for (final backup in [
        jsonBody([]),
        jsonBody({}),
        jsonBody([42]),
        jsonBody([row('v0.11.0-beta.5+2026102249')]),
        jsonBody({}, 403),
        ResponseBody.fromString(
          '',
          302,
          headers: {
            'location': ['https://mirror.invalid/releases.json'],
          },
        ),
      ]) {
        final dio = Dio()
          ..httpClientAdapter = _Adapter(
            (options) => options.uri.host == 'api.github.com'
                ? jsonBody({}, 403)
                : backup,
          );
        try {
          await expectLater(
            PublishedReleaseFeed(dio).fetch(
              coversInstalledRelease: (row) => row['tag_name'] == installed,
            ),
            throwsA(
              isA<DioException>()
                  .having((e) => e.response?.statusCode, 'original status', 403)
                  .having(
                    (e) => e.requestOptions.uri.host,
                    'original host',
                    'api.github.com',
                  ),
            ),
          );
        } finally {
          dio.close(force: true);
        }
      }
    },
  );

  test('caller coverage rejection preserves original API failure', () async {
    final dio = Dio()
      ..httpClientAdapter = _Adapter(
        (options) => options.uri.host == 'api.github.com'
            ? jsonBody({}, 403)
            : jsonBody([row(installed)]),
      );
    try {
      await expectLater(
        PublishedReleaseFeed(dio).fetch(coversInstalledRelease: (_) => false),
        throwsA(
          isA<DioException>().having(
            (e) => e.response?.statusCode,
            'original status',
            403,
          ),
        ),
      );
    } finally {
      dio.close(force: true);
    }
  });

  test('partial API pages are discarded when falling back', () async {
    var apiCalls = 0;
    final dio = Dio()
      ..httpClientAdapter = _Adapter((options) {
        if (options.uri.host != 'api.github.com') {
          return jsonBody([row(installed)]);
        }
        return ++apiCalls == 1
            ? jsonBody(List.generate(100, (_) => row('lite-v99.0.0+99')))
            : jsonBody({}, 429);
      });
    try {
      final rows = await PublishedReleaseFeed(
        dio,
      ).fetch(coversInstalledRelease: (row) => row['tag_name'] == installed);
      expect(rows.single['tag_name'], installed);
      expect(apiCalls, 2);
    } finally {
      dio.close(force: true);
    }
  });

  test(
    'cancellation during fallback remains cancelled and never reports current',
    () async {
      final entered = Completer<void>();
      final token = CancelToken();
      final dio = Dio()
        ..httpClientAdapter = _Adapter((options) {
          if (options.uri.host == 'api.github.com') return jsonBody({}, 403);
          entered.complete();
          return Completer<ResponseBody>().future;
        });
      try {
        final pending = PublishedReleaseFeed(dio).fetch(
          coversInstalledRelease: (row) => row['tag_name'] == installed,
          cancelToken: token,
        );
        final assertion = expectLater(
          pending,
          throwsA(
            isA<DioException>().having(
              (e) => e.type,
              'type',
              DioExceptionType.cancel,
            ),
          ),
        );
        await entered.future;
        token.cancel();
        await assertion;
      } finally {
        dio.close(force: true);
      }
    },
  );

  test(
    'total check deadline cancels stalled transport without retry',
    () async {
      var calls = 0;
      final token = CancelToken();
      final dio = Dio()
        ..httpClientAdapter = _Adapter((_) {
          calls++;
          return Completer<ResponseBody>().future;
        });
      try {
        await expectLater(
          PublishedReleaseFeed(
            dio,
            totalTimeout: const Duration(milliseconds: 10),
          ).fetch(
            coversInstalledRelease: (row) => row['tag_name'] == installed,
            cancelToken: token,
          ),
          throwsA(
            isA<DioException>().having(
              (e) => e.type,
              'type',
              DioExceptionType.receiveTimeout,
            ),
          ),
        );
        expect(token.isCancelled, isTrue);
        expect(calls, 1);
      } finally {
        dio.close(force: true);
      }
    },
  );
}
