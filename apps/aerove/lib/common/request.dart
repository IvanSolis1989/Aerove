import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/update_download.dart';
import 'package:fl_clash/common/update_feed.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/changelog.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/foundation.dart' show visibleForTesting;

class Request {
  late final Dio dio;
  late final Dio _clashDio;
  String? userAgent;
  Future<Map<String, dynamic>?>? _pendingUpdateCheck;

  ProviderReader? _read;

  void attach(ProviderReader read) {
    _read = read;
  }

  Request() {
    dio = Dio(BaseOptions(headers: {'User-Agent': browserUa}));
    _clashDio = Dio();
    _clashDio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final client = HttpClient();
        client.findProxy = (Uri uri) {
          client.userAgent = globalState.ua;
          final read = _read;
          if (read == null) {
            return 'DIRECT';
          }
          return FlClashHttpOverrides.findProxyForReader(read, uri);
        };
        return client;
      },
    );
  }

  Future<Response<T>> _download<T>(String url, ResponseType responseType) {
    final target = downloadTarget(url);
    final cancelToken = CancelToken();
    return _clashDio
        .getUri<T>(
          target.uri,
          options: Options(responseType: responseType, headers: target.headers),
          cancelToken: cancelToken,
        )
        .timeout(
          downloadTimeoutDuration,
          onTimeout: () {
            cancelToken.cancel();
            throw DioException.receiveTimeout(
              timeout: downloadTimeoutDuration,
              requestOptions: RequestOptions(path: url),
            );
          },
        );
  }

  Future<Response<Uint8List>> getFileResponseForUrl(String url) async {
    try {
      return await _download<Uint8List>(url, ResponseType.bytes);
    } catch (e) {
      commonPrint.log(
        'getFileResponseForUrl error ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
      rethrow;
    }
  }

  Future<Response<String>> getTextResponseForUrl(String url) async {
    try {
      return await _download<String>(url, ResponseType.plain);
    } catch (e) {
      commonPrint.log(
        'getTextResponseForUrl error ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
      rethrow;
    }
  }

  Future<Map<String, dynamic>?> checkForUpdate() {
    return _pendingUpdateCheck ??= _checkForUpdate().whenComplete(() {
      _pendingUpdateCheck = null;
    });
  }

  Future<Map<String, dynamic>?> _checkForUpdate() async {
    final packageInfo = globalState.packageInfo;
    final buildNumber = int.tryParse(packageInfo.buildNumber) ?? 0;
    final androidAbis = Platform.isAndroid
        ? await app!.getSupportedAbis()
        : null;
    final release = selectStandardUpdate(
      await publishedReleases(
        coversInstalledRelease: (release) => standardReleaseCoversInstalled(
          release,
          installedTag: 'v${packageInfo.version}+$buildNumber',
          androidAbis: androidAbis,
          desktopPlatform: Platform.isAndroid ? null : Platform.operatingSystem,
        ),
      ),
      version: packageInfo.version,
      buildNumber: buildNumber,
      androidAbis: androidAbis,
    );
    return release == null ? null : await _withReleaseNotes(release);
  }

  Future<Map<String, dynamic>> _withReleaseNotes(
    Map<String, dynamic> release,
  ) async {
    final tag = release['tag_name'] as String;
    final body = release['body'] as String?;
    if (parseReleaseChangelog(body, tagName: tag)?.isEmpty == false ||
        releaseNotesText(body, tagName: tag).isNotEmpty) {
      return release;
    }
    try {
      final cancelToken = CancelToken();
      final response = await dio
          .get<String>(
            'https://raw.githubusercontent.com/$repository/refs/heads/main/docs/aerove/CHANGELOG.md',
            options: Options(responseType: ResponseType.plain),
            cancelToken: cancelToken,
          )
          .timeout(
            const Duration(seconds: 10),
            onTimeout: () {
              cancelToken.cancel();
              throw TimeoutException('Release notes request timed out');
            },
          );
      final notes = aeroveNotesForTag(response.data ?? '', tag);
      if (notes != null) {
        return {
          ...release,
          'body': '$releaseNotesBeginMarker\n$notes\n$releaseNotesEndMarker',
        };
      }
    } catch (error) {
      commonPrint.log(
        'Release notes for $tag unavailable: ${compactError(error)}',
        logLevel: LogLevel.warning,
      );
    }
    return release;
  }

  /// A failed or incomplete list cannot establish that this build is current.
  @visibleForTesting
  Future<List<Map<String, dynamic>>> publishedReleases({
    bool Function(Map<String, dynamic>)? coversInstalledRelease,
    CancelToken? cancelToken,
  }) => PublishedReleaseFeed(dio).fetch(
    coversInstalledRelease: coversInstalledRelease,
    cancelToken: cancelToken,
  );
}

final request = Request();

/// Reuses the product comparator, with Android's independent install-code gate.
@visibleForTesting
Map<String, dynamic>? selectStandardUpdate(
  List<Map<String, dynamic>> releases, {
  required String version,
  required int buildNumber,
  List<String>? androidAbis,
}) {
  if (androidAbis != null && (buildNumber <= 0 || buildNumber > 2100000000)) {
    throw StateError('Invalid installed Android version code');
  }
  Map<String, dynamic>? selected;
  final installed = '$version+$buildNumber';
  for (final release in releases) {
    final tag = release['tag_name'];
    if (release['draft'] == true || tag is! String || !tag.startsWith('v')) {
      continue;
    }
    try {
      if (compareVersions(tag, installed) <= 0) continue;
      if (androidAbis != null) {
        final code = int.tryParse(tag.split('+').skip(1).firstOrNull ?? '');
        if (code == null || code <= buildNumber || code > 2100000000) {
          continue;
        }
      }
      final trusted = _trustedStandardAssets(release, tag);
      if (trusted.isEmpty ||
          (androidAbis != null &&
              selectAndroidUpdateAsset({'assets': trusted}, androidAbis) ==
                  null)) {
        continue;
      }
      if (selected == null ||
          compareVersions(tag, selected['tag_name'] as String) > 0) {
        selected = {...release, 'assets': trusted};
      }
    } on FormatException {
      // Unrelated or malformed tags do not poison another product's feed.
      continue;
    }
  }
  return selected;
}

List<Map> _trustedStandardAssets(Map<String, dynamic> release, String tag) {
  final assets = release['assets'];
  if (assets is! List) return [];
  return assets.whereType<Map>().where((asset) {
    final name = asset['name'];
    final url = Uri.tryParse('${asset['browser_download_url']}');
    return name is String &&
        RegExp(
          r'^(?:Aerove|XClash)-(?:android-[\w-]+\.apk|windows-[\w.-]+\.(?:exe|zip|msix)|linux-[\w.-]+\.(?:deb|rpm|AppImage|tar\.gz)|macos-[\w.-]+\.(?:dmg|zip))$',
          caseSensitive: false,
        ).hasMatch(name) &&
        url != null &&
        url.scheme == 'https' &&
        url.host == 'github.com' &&
        url.port == 443 &&
        url.userInfo.isEmpty &&
        !url.hasQuery &&
        !url.hasFragment &&
        url.pathSegments.join('/') ==
            '$repository/releases/download/$tag/$name';
  }).toList();
}

@visibleForTesting
bool standardReleaseCoversInstalled(
  Map<String, dynamic> release, {
  required String installedTag,
  List<String>? androidAbis,
  String? desktopPlatform,
}) {
  if (release['draft'] == true || release['tag_name'] != installedTag) {
    return false;
  }
  final trusted = _trustedStandardAssets(release, installedTag);
  if (androidAbis != null) {
    return selectAndroidUpdateAsset({'assets': trusted}, androidAbis) != null;
  }
  if (!{'windows', 'linux', 'macos'}.contains(desktopPlatform)) return false;
  return trusted.any(
    (asset) => RegExp(
      '^(?:Aerove|XClash)-$desktopPlatform-',
      caseSensitive: false,
    ).hasMatch(asset['name'] as String),
  );
}

Uri standardReleasePage(Map<String, dynamic> release) {
  final tag = release['tag_name'] as String;
  final exact = Uri.https('github.com', '/$repository/releases/tag/$tag');
  final supplied = Uri.tryParse('${release['html_url']}');
  return supplied != null &&
          supplied.scheme == 'https' &&
          supplied.host == 'github.com' &&
          supplied.port == 443 &&
          supplied.userInfo.isEmpty &&
          !supplied.hasQuery &&
          !supplied.hasFragment &&
          supplied.pathSegments.join('/') == '$repository/releases/tag/$tag'
      ? supplied
      : exact;
}

/// Some subscription servers send a gzip or zlib body without saying so in
/// Content-Encoding, which is the only signal dart:io decompresses on.
Uint8List decompressUnlabeled(Uint8List bytes) {
  final codec = switch (bytes) {
    [0x1f, 0x8b, ...] => gzip,
    [0x78, final flags, ...] when (0x78 << 8 | flags) % 31 == 0 => zlib,
    _ => null,
  };
  if (codec == null) {
    return bytes;
  }
  try {
    return Uint8List.fromList(codec.decode(bytes));
  } on FormatException {
    return bytes;
  }
}

/// dart:io turns [Uri.userInfo] into Basic credentials still percent-encoded.
@visibleForTesting
({Uri uri, Map<String, Object> headers}) downloadTarget(String url) {
  final uri = url.webUri ?? Uri.parse(url);
  if (uri.userInfo.isEmpty) {
    return (uri: uri, headers: const {});
  }
  final credentials = uri.userInfo.split(':').map(_decodeUserInfo).join(':');
  return (
    uri: uri.replace(userInfo: ''),
    headers: {
      HttpHeaders.authorizationHeader:
          'Basic ${base64.encode(utf8.encode(credentials))}',
    },
  );
}

String _decodeUserInfo(String value) {
  try {
    return Uri.decodeComponent(value);
  } on ArgumentError {
    return value;
  }
}

String? getFileNameForDisposition(String? disposition) {
  if (disposition == null) return null;
  final parseValue = HeaderValue.parse(disposition);
  final parameters = parseValue.parameters;
  final fileNamePointKey = parameters.keys.firstWhere(
    (key) => key == 'filename*',
    orElse: () => '',
  );
  if (fileNamePointKey.isNotEmpty) {
    final res = parameters[fileNamePointKey]?.split("''") ?? [];
    if (res.length >= 2) {
      return Uri.decodeComponent(res[1]);
    }
  }
  final fileNameKey = parameters.keys.firstWhere(
    (key) => key == 'filename',
    orElse: () => '',
  );
  if (fileNameKey.isEmpty) return null;
  return parameters[fileNameKey];
}
