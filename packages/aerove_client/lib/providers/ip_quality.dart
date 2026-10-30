import 'dart:async';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/ip_quality.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'app.dart';
import 'last_exit.dart';

part 'generated/ip_quality.g.dart';

Duration? _noRetry(int retryCount, Object error) => null;

@Riverpod(keepAlive: true)
Duration ipQualityFailureCooldown(Ref ref) => const Duration(minutes: 5);

@Riverpod(keepAlive: true)
Duration ipQualityMaxAge(Ref ref) => const Duration(days: 3);

/// Invalidating skips the stored record and asks the sources again.
@Riverpod(retry: _noRetry)
Future<IpQuality> ipQuality(Ref ref, String ip) async {
  final maxAge = ref.read(ipQualityMaxAgeProvider);
  if (!ref.isRefresh) {
    final record = await _orNull(
      'read',
      database.ipQualitiesDao.fresh(ip, maxAge),
    );
    if (record != null) {
      _expireAt(ref, record.checkedAt.add(maxAge));
      if (ref.mounted) {
        ref.read(lastExitProvider.notifier).rememberQuality(record.quality);
      }
      return record.quality;
    }
  }
  final cancelToken = CancelToken();
  final foreground = Completer<void>();
  bool settled = false;
  if (ref.read(appVisibleProvider)) foreground.complete();
  ref.onDispose(() {
    cancelToken.cancel();
    if (!foreground.isCompleted) foreground.complete();
  });
  ref.listen(appVisibleProvider, (_, visible) {
    if (visible) {
      if (!foreground.isCompleted) foreground.complete();
    } else if (!settled && foreground.isCompleted) {
      cancelToken.cancel();
      ref.invalidateSelf();
    }
  });
  await foreground.future;
  if (cancelToken.isCancelled) throw cancelToken.cancelError!;
  try {
    final quality = await lookupIpQuality(
      request.dio,
      ip,
      cancelToken: cancelToken,
    );
    settled = true;
    final checkedAt = DateTime.now();
    await _orNull(
      'write',
      database.ipQualitiesDao.put(quality, checkedAt, maxAge),
    );
    _expireAt(ref, checkedAt.add(maxAge));
    if (ref.mounted) {
      ref.read(lastExitProvider.notifier).rememberQuality(quality);
    }
    return quality;
  } on IpQualityLookupException catch (error) {
    settled = true;
    if (!ref.mounted) {
      rethrow;
    }
    commonPrint.log(
      'ip quality lookup failed for $ip: '
      '${error.failures.map((f) => '${f.source.label} ${f.status.name}').join(', ')}',
      logLevel: LogLevel.warning,
    );
    final link = ref.keepAlive();
    final timer = Timer(ref.read(ipQualityFailureCooldownProvider), link.close);
    ref.onDispose(timer.cancel);
    rethrow;
  }
}

Future<T?> _orNull<T>(String action, Future<T> future) async {
  try {
    return await future;
  } catch (error) {
    commonPrint.log(
      'ip quality $action failed: ${compactError(error)}',
      logLevel: LogLevel.warning,
    );
    return null;
  }
}

void _expireAt(Ref ref, DateTime expiry) {
  if (!ref.mounted) {
    return;
  }
  ref.keepAlive();
  final timer = Timer(expiry.difference(DateTime.now()), ref.invalidateSelf);
  ref.onDispose(timer.cancel);
}
