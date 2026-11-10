import 'package:fl_clash/product.dart';

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/download_redirect.dart';
import 'package:fl_clash/common/update_download.dart';
import 'package:fl_clash/common/update_feed.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/changelog.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:flutter/foundation.dart' show visibleForTesting;

class Request {
  late final Dio dio;
  String? userAgent;
  Future<Map<String, dynamic>?>? _pendingUpdateCheck;

  ProviderReader? _read;

  void attach(ProviderReader read) {
    _read = read;
  }

  Request() {
    dio = Dio(BaseOptions(headers: {'User-Agent': browserUa}));
  }

  Future<Response<T>> _download<T>(
    String url,
    ResponseType responseType, {
    CancelToken? cancellation,
    int? maxBytes,
  }) => _downloadJoined<T>(
    url,
    responseType,
    cancellation ?? CancelToken(),
    maxBytes: maxBytes,
  );

  Future<Response<T>> _downloadJoined<T>(
    String url,
    ResponseType responseType,
    CancelToken cancellation, {
    int? maxBytes,
  }) async {
    final target = downloadTarget(url);
    final adapter = _JoinedDownloadAdapter(
      maxBytes: maxBytes,
      createHttpClient: () {
        final client = HttpClient();
        client.findProxy = (uri) {
          client.userAgent = globalState.ua;
          final read = _read;
          return read == null
              ? 'DIRECT'
              : FlClashHttpOverrides.findProxyForReader(read, uri);
        };
        return client;
      },
    );
    final download = Dio()..httpClientAdapter = adapter;
    var timedOut = false;
    final timer = Timer(downloadTimeoutDuration, () {
      timedOut = true;
      if (!cancellation.isCancelled) cancellation.cancel();
    });
    unawaited(cancellation.whenCancel.then((_) => adapter.close(force: true)));
    try {
      return await download.getUri<T>(
        target.uri,
        options: Options(responseType: responseType, headers: target.headers),
        cancelToken: cancellation,
      );
    } catch (_) {
      if (timedOut) {
        throw DioException.receiveTimeout(
          timeout: downloadTimeoutDuration,
          requestOptions: RequestOptions(path: url),
        );
      }
      rethrow;
    } finally {
      timer.cancel();
      download.close(force: true);
      await adapter.join();
    }
  }

  Future<void> downloadRuleSetToOwnedFile(
    String url,
    File destination, {
    required CancelToken cancellation,
    required bool Function() isCurrent,
    required RuleDownloadOwner owner,
  }) async {
    _JoinedDownloadAdapter? adapter;
    Dio? download;
    Timer? timer;
    Future<void>? cancellationClose;
    IOSink? output;
    StreamIterator<Uint8List>? input;
    Object? primary;
    StackTrace? primaryStack;
    owner.begin();
    try {
      final read = _read;
      if (read == null)
        throw StateError('Shared request policy is not attached');
      final userAgent = globalState.ua;
      final checkCertificate = read(appSettingProvider).checkCertificate;
      final proxy = FlClashHttpOverrides.findProxyForReader(
        read,
        Uri.https('rule-policy.invalid', '/'),
      );
      final target = downloadTarget(url);
      final httpAdapter = _JoinedDownloadAdapter(
        createHttpClient: () {
          final client = HttpClient()..userAgent = userAgent;
          client.badCertificateCallback = (_, _, _) => !checkCertificate;
          client.findProxy = (uri) => uri.host == localhost ? 'DIRECT' : proxy;
          return client;
        },
      );
      adapter = httpAdapter;
      final http = Dio()..httpClientAdapter = httpAdapter;
      download = http;
      timer = Timer(
        downloadTimeoutDuration,
        () => cancellation.cancel('Rule download timed out'),
      );
      cancellationClose = cancellation.whenCancel.then<void>((_) {
        try {
          http.close(force: true);
        } catch (error, stack) {
          owner.recordFailure('cancellation-http-close', error, stack);
        }
      });
      if (!isCurrent() || cancellation.isCancelled)
        throw StateError('Rule source changed');
      final response = await http.getUri<ResponseBody>(
        target.uri,
        options: Options(
          responseType: ResponseType.stream,
          headers: {...target.headers, 'User-Agent': userAgent},
        ),
        cancelToken: cancellation,
      );
      final body = response.data;
      if (body == null) throw const FormatException('Rule response is empty');
      final declared = int.tryParse(
        response.headers.value(HttpHeaders.contentLengthHeader) ?? '',
      );
      if (declared != null && declared > 64 * 1024 * 1024)
        throw const FormatException('Rule response exceeds 67108864 bytes');
      output = destination.openWrite();
      var count = 0;
      final reader = StreamIterator(body.stream);
      input = reader;
      final cancelled = cancellation.whenCancel.then<bool>((_) => false);
      while (await Future.any([reader.moveNext(), cancelled])) {
        final chunk = reader.current;
        if (!isCurrent() || cancellation.isCancelled)
          throw StateError('Rule download was superseded');
        count += chunk.length;
        if (count > 64 * 1024 * 1024)
          throw const FormatException('Rule response exceeds 67108864 bytes');
        output.add(chunk);
        await output.flush();
      }
      if (count == 0) throw const FormatException('Remote rule set is empty');
      if (!isCurrent() || cancellation.isCancelled)
        throw StateError('Rule download was superseded');
    } catch (error, stack) {
      primary = error;
      primaryStack = stack;
    } finally {
      await owner.closeAndJoin(
        primary: primary,
        primaryStack: primaryStack,
        steps: [
          (name: 'timer-cancel', close: () => timer?.cancel()),
          (
            name: 'token-cancel',
            close: () => cancellation.cancel('Rule download finished'),
          ),
          (name: 'http-close', close: () => download?.close(force: true)),
          (
            name: 'cancellation-listener',
            close: () async {
              if (cancellation.isCancelled) await cancellationClose;
            },
          ),
          (
            name: 'reader-cancel',
            close: () async {
              await input?.cancel();
            },
          ),
          (
            name: 'http-join',
            close: () async {
              await adapter?.join();
            },
          ),
          (
            name: 'sink-close',
            close: () async {
              await output?.close();
            },
          ),
        ],
      );
    }
  }

  Future<Response<Uint8List>> getFileResponseForUrl(
    String url, {
    CancelToken? cancelToken,
    int maxBytes = maxSubscriptionBytes,
  }) async {
    try {
      return await _download<Uint8List>(
        url,
        ResponseType.bytes,
        cancellation: cancelToken,
        maxBytes: maxBytes,
      );
    } catch (e) {
      commonPrint.log(
        'getFileResponseForUrl error ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
      rethrow;
    }
  }

  Future<Response<String>> getTextResponseForUrl(
    String url, {
    CancelToken? cancelToken,
    int? maxBytes,
  }) async {
    try {
      return await _download<String>(
        url,
        ResponseType.plain,
        cancellation: cancelToken,
        maxBytes: maxBytes,
      );
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
          installedTag:
              '${ClientProduct.current.releaseTagPrefix}${packageInfo.version}+$buildNumber',
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
            'https://raw.githubusercontent.com/$repository/refs/heads/main/${ClientProduct.current.changelogPath}',
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

typedef RuleDownloadCleanupStep = ({
  String name,
  FutureOr<void> Function() close,
});
typedef RuleDownloadCleanupError = ({
  String step,
  Object error,
  StackTrace stack,
});

class RuleDownloadCleanupFailure implements Exception {
  RuleDownloadCleanupFailure(
    this.primary,
    this.primaryStack,
    this.failures, {
    this.candidatePath,
  });
  final String? candidatePath;
  final Object? primary;
  final StackTrace? primaryStack;
  final List<RuleDownloadCleanupError> failures;
  @override
  String toString() =>
      'Rule download cleanup unconfirmed; candidate: $candidatePath; primary: $primary; '
      'cleanup: ${failures.map((failure) => "${failure.step}: ${failure.error}").join("; ")}';
}

class RuleDownloadOwner {
  RuleDownloadOwner({this.candidatePath});
  final String? candidatePath;
  bool _started = false;
  bool _finished = false;
  bool _quiescent = false;
  final _failures = <RuleDownloadCleanupError>[];
  bool get isQuiescent => !_started || _quiescent;

  void begin() {
    if (_started) throw StateError('Rule download owner is already started');
    _started = true;
  }

  void recordFailure(String step, Object error, StackTrace stack) {
    _failures.add((step: step, error: error, stack: stack));
  }

  Future<void> closeAndJoin({
    Object? primary,
    StackTrace? primaryStack,
    required List<RuleDownloadCleanupStep> steps,
  }) async {
    if (!_started || _finished)
      throw StateError('Rule download cleanup owner is not active');
    _finished = true;
    await Future.wait([
      for (final step in steps)
        Future<void>.sync(() async {
          try {
            await step.close();
          } catch (error, stack) {
            recordFailure(step.name, error, stack);
          }
        }),
    ]);
    _quiescent = _failures.isEmpty;
    if (!_quiescent) {
      throw RuleDownloadCleanupFailure(
        primary,
        primaryStack,
        List.unmodifiable(_failures),
        candidatePath: candidatePath,
      );
    }
    if (primary != null)
      Error.throwWithStackTrace(primary, primaryStack ?? StackTrace.current);
  }
}

class _JoinedDownloadAdapter extends IOHttpClientAdapter {
  _JoinedDownloadAdapter({required super.createHttpClient, this.maxBytes});

  final int? maxBytes;

  final _fetches = <Future<void>>[];
  final _bodies = <_JoinedDownloadBody>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final done = Completer<void>();
    _fetches.add(done.future);
    try {
      var hop = options.copyWith(followRedirects: false);
      final policy = DownloadRedirectPolicy(
        options.uri,
        maxRedirects: options.maxRedirects,
      );
      final redirects = <RedirectRecord>[];
      while (true) {
        final cancelled = options.cancelToken?.cancelError;
        if (cancelled != null) throw cancelled;
        final response = await super.fetch(hop, requestStream, cancelFuture);
        final body = _JoinedDownloadBody(response.stream);
        _bodies.add(body);
        final locations = response.headers[HttpHeaders.locationHeader];
        final follow =
            options.followRedirects &&
            requestStream == null &&
            const {'GET', 'HEAD'}.contains(options.method) &&
            const {301, 302, 303, 307, 308}.contains(response.statusCode) &&
            locations != null;
        if (follow) {
          await body.close();
          if (locations.length != 1)
            throw const DownloadRedirectException('invalid destination');
          final target = policy.follow(locations.single, hop.headers);
          redirects.add(
            RedirectRecord(response.statusCode, hop.method, target.uri),
          );
          hop = hop.copyWith(
            path: target.uri.toString(),
            baseUrl: '',
            queryParameters: {},
            headers: target.headers,
          );
          continue;
        }
        final limit = maxBytes;
        if (limit != null) {
          final declared = int.tryParse(
            response.headers[HttpHeaders.contentLengthHeader]?.firstOrNull ??
                '',
          );
          if (declared != null && declared > limit)
            throw DownloadSizeException(limit);
        }
        return ResponseBody(
          limit == null ? body.stream : _boundedResponse(body.stream, limit),
          response.statusCode,
          headers: response.headers,
          statusMessage: response.statusMessage,
          isRedirect: response.isRedirect || redirects.isNotEmpty,
          redirects: [...redirects, ...?response.redirects],
        )..extra.addAll(response.extra);
      }
    } finally {
      done.complete();
    }
  }

  Future<void> join() async {
    Object? fetchFailure;
    StackTrace? fetchStack;
    final fetchOwner = RuleDownloadOwner()..begin();
    try {
      await fetchOwner.closeAndJoin(
        steps: [
          for (final (index, fetch) in _fetches.indexed)
            (name: 'fetch:$index', close: () => fetch),
        ],
      );
    } catch (error, stack) {
      fetchFailure = error;
      fetchStack = stack;
    }
    final bodyOwner = RuleDownloadOwner()..begin();
    await bodyOwner.closeAndJoin(
      primary: fetchFailure,
      primaryStack: fetchStack,
      steps: [
        for (final (index, body) in _bodies.indexed)
          (name: 'body:$index', close: body.close),
      ],
    );
  }
}

class _JoinedDownloadBody {
  _JoinedDownloadBody(this._source) {
    _controller = StreamController<Uint8List>(
      onListen: () {
        if (_closing != null) {
          unawaited(_controller.close());
          return;
        }
        _subscription = _source.listen(
          _controller.add,
          onError: _controller.addError,
          onDone: () => unawaited(_controller.close()),
        );
      },
      onPause: () => _subscription?.pause(),
      onResume: () => _subscription?.resume(),
      onCancel: close,
    );
  }

  final Stream<Uint8List> _source;
  late final StreamController<Uint8List> _controller;
  StreamSubscription<Uint8List>? _subscription;
  Future<void>? _closing;

  Stream<Uint8List> get stream => _controller.stream;

  Future<void> close() => _closing ??=
      (_subscription ?? _source.listen((_) {}, onError: (Object _) {}))
          .cancel();
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
    if (release['draft'] == true ||
        tag is! String ||
        !tag.startsWith(ClientProduct.current.releaseTagPrefix)) {
      continue;
    }
    try {
      if (compareVersions(_productVersion(tag), installed) <= 0) continue;
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
          compareVersions(
                _productVersion(tag),
                _productVersion(selected['tag_name'] as String),
              ) >
              0) {
        selected = {...release, 'assets': trusted};
      }
    } on FormatException {
      // Unrelated or malformed tags do not poison another product's feed.
      continue;
    }
  }
  return selected;
}

String _productVersion(String tag) {
  final prefix = ClientProduct.current.releaseTagPrefix;
  return tag.startsWith(prefix) ? tag.substring(prefix.length) : tag;
}

List<Map> _trustedStandardAssets(Map<String, dynamic> release, String tag) {
  final assets = release['assets'];
  if (assets is! List) return [];
  return assets.whereType<Map>().where((asset) {
    final name = asset['name'];
    final url = Uri.tryParse('${asset['browser_download_url']}');
    return name is String &&
        RegExp(
          ClientProduct.current.releaseAssetPattern,
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
  if (release['draft'] == true ||
      release['tag_name'] !=
          '${ClientProduct.current.releaseTagPrefix}${_productVersion(installedTag)}') {
    return false;
  }
  final trusted = _trustedStandardAssets(
    release,
    release['tag_name'] as String,
  );
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

const maxSubscriptionBytes = 16 * 1024 * 1024;
const maxScriptBytes = 4 * 1024 * 1024;

class DownloadSizeException implements Exception {
  const DownloadSizeException(this.limit);
  final int limit;
  @override
  String toString() => 'Download exceeds $limit bytes';
}

Stream<Uint8List> _boundedResponse(Stream<Uint8List> source, int limit) async* {
  var count = 0;
  await for (final chunk in source) {
    count += chunk.length;
    if (count > limit) throw DownloadSizeException(limit);
    yield chunk;
  }
}

class _BoundedBytes implements Sink<List<int>> {
  _BoundedBytes(this.limit);
  final int limit;
  final bytes = BytesBuilder(copy: false);
  @override
  void add(List<int> chunk) {
    if (bytes.length + chunk.length > limit) throw DownloadSizeException(limit);
    bytes.add(chunk);
  }

  @override
  void close() {}
}

Future<Uint8List> decompressUnlabeledTask(Uint8List bytes) =>
    Isolate.run(() => decompressUnlabeled(bytes));

/// Handles compressed subscription bodies that omitted Content-Encoding.
Uint8List decompressUnlabeled(
  Uint8List bytes, {
  int maxBytes = maxSubscriptionBytes,
}) {
  if (bytes.length > maxBytes) throw DownloadSizeException(maxBytes);
  final codec = switch (bytes) {
    [0x1f, 0x8b, ...] => gzip,
    [0x78, final flags, ...] when (0x78 << 8 | flags) % 31 == 0 => zlib,
    _ => null,
  };
  if (codec == null) return bytes;
  try {
    final output = _BoundedBytes(maxBytes);
    final decoder = codec.decoder.startChunkedConversion(output);
    for (var offset = 0; offset < bytes.length; offset += 16384) {
      decoder.add(
        Uint8List.sublistView(
          bytes,
          offset,
          offset + 16384 < bytes.length ? offset + 16384 : bytes.length,
        ),
      );
    }
    decoder.close();
    return output.bytes.takeBytes();
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
