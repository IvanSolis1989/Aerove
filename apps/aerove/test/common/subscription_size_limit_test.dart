import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/request.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('unlabelled gzip cannot expand beyond the subscription budget', () {
    final compressed = Uint8List.fromList(
      gzip.encode(Uint8List(17 * 1024 * 1024)),
    );
    expect(
      () => decompressUnlabeled(compressed),
      throwsA(isA<DownloadSizeException>()),
    );
  });

  test('small gzip and zlib bodies retain their exact bytes', () {
    final input = Uint8List.fromList([0, 1, 2, 127, 255]);
    for (final codec in [gzip, zlib]) {
      expect(
        decompressUnlabeled(Uint8List.fromList(codec.encode(input))),
        input,
      );
    }
  });

  test('plain bytes at the budget are accepted and larger bytes fail', () {
    final input = Uint8List(1024);
    expect(decompressUnlabeled(input, maxBytes: 1024), same(input));
    expect(
      () => decompressUnlabeled(Uint8List(1025), maxBytes: 1024),
      throwsA(isA<DownloadSizeException>()),
    );
  });

  for (final declared in [true, false]) {
    test(
      'subscription download is bounded with content length $declared',
      () async {
        final original = HttpOverrides.current;
        HttpOverrides.global = null;
        addTearDown(() => HttpOverrides.global = original);
        globalState.packageInfo = PackageInfo(
          appName: 'Aerove',
          packageName: 'dev.aerove.test',
          version: '1.0.0',
          buildNumber: '1',
        );
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        addTearDown(() => server.close(force: true));
        server.listen((request) async {
          if (declared) request.response.contentLength = 17 * 1024 * 1024;
          try {
            for (var i = 0; i < 17; i++) {
              request.response.add(Uint8List(1024 * 1024));
              await request.response.flush();
            }
            await request.response.close();
          } on IOException {
            // Oversized bodies are stopped and joined before returning failure.
          }
        });
        await expectLater(
          Request().getFileResponseForUrl(
            'http://127.0.0.1:${server.port}/subscription',
          ),
          throwsA(
            isA<DioException>().having(
              (error) => error.error,
              'size budget failure',
              isA<DownloadSizeException>(),
            ),
          ),
        );
      },
    );
  }
}
