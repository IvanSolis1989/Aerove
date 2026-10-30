import 'package:fl_clash/product.dart';

import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

String get officialReleaseFeed => ClientProduct.current.releaseFeed;
const _repository = 'IvanSolis1989/Aerove';
const _maxFeedBytes = 2 * 1024 * 1024;

class PublishedReleaseFeed {
  PublishedReleaseFeed(
    this.dio, {
    this.totalTimeout = const Duration(seconds: 30),
  });

  final Dio dio;
  final Duration totalTimeout;

  Future<List<Map<String, dynamic>>> fetch({
    bool Function(Map<String, dynamic>)? coversInstalledRelease,
    CancelToken? cancelToken,
  }) {
    final cancellation = cancelToken ?? CancelToken();
    return _fetch(coversInstalledRelease, cancellation).timeout(
      totalTimeout,
      onTimeout: () {
        cancellation.cancel('Update check timed out');
        throw DioException.receiveTimeout(
          timeout: totalTimeout,
          requestOptions: RequestOptions(path: officialReleaseFeed),
        );
      },
    );
  }

  Options _options(ResponseType type) => Options(
    responseType: type,
    followRedirects: false,
    receiveTimeout: const Duration(seconds: 20),
    sendTimeout: const Duration(seconds: 20),
    headers: {'Accept': 'application/vnd.github+json'},
  );

  Future<List<Map<String, dynamic>>> _fetch(
    bool Function(Map<String, dynamic>)? coversInstalledRelease,
    CancelToken cancellation,
  ) async {
    try {
      final releases = <Map<String, dynamic>>[];
      for (var page = 1; page <= 5; page++) {
        final response = await dio.get<List<dynamic>>(
          'https://api.github.com/repos/$_repository/releases',
          queryParameters: {'per_page': 100, 'page': page},
          options: _options(ResponseType.json),
          cancelToken: cancellation,
        );
        final rows = response.data;
        if (rows == null || rows.length > 100) {
          throw const FormatException('Invalid release list');
        }
        releases.addAll(rows.whereType<Map<String, dynamic>>());
        if (rows.length < 100) return releases;
      }
      throw StateError('Release feed scan limit reached');
    } on DioException catch (failure, trace) {
      if (failure.type == DioExceptionType.cancel ||
          !{403, 429}.contains(failure.response?.statusCode)) {
        rethrow;
      }
      try {
        final response = await dio.get<List<int>>(
          officialReleaseFeed,
          options: _options(ResponseType.bytes),
          cancelToken: cancellation,
        );
        final bytes = response.data;
        if (bytes == null || bytes.length > _maxFeedBytes) {
          throw const FormatException('Invalid official release feed size');
        }
        final rows = jsonDecode(utf8.decode(Uint8List.fromList(bytes)));
        if (rows is! List || rows.isEmpty || rows.length > 500) {
          throw const FormatException('Invalid official release feed');
        }
        final releases = <Map<String, dynamic>>[];
        for (final row in rows) {
          if (row is! Map<String, dynamic> ||
              row['tag_name'] is! String ||
              row['draft'] is! bool ||
              row['prerelease'] is! bool ||
              row['assets'] is! List) {
            throw const FormatException('Invalid official release entry');
          }
          releases.add(row);
        }
        if (coversInstalledRelease == null ||
            !releases.any(coversInstalledRelease)) {
          throw StateError('Official release feed does not cover this build');
        }
        return releases;
      } catch (fallback) {
        if (fallback is DioException &&
            fallback.type == DioExceptionType.cancel) {
          rethrow;
        }
        Error.throwWithStackTrace(failure, trace);
      }
    }
  }
}
