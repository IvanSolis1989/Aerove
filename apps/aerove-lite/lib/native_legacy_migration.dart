import 'dart:io';

import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/path.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/common/snowflake.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;

const _migrationChannel = MethodChannel('dev.aerove.lite/native-migration');

class _PlatformAuthority implements NativeLiteAuthority {
  _PlatformAuthority(this.lease);
  final String lease;
  @override
  Future<void> prepareFiles(File plan, String digest) =>
      _migrationChannel.invokeMethod<void>('prepareFiles', {
        'leaseId': lease,
        'plan': plan.path,
        'sha256': digest,
      });
  @override
  Future<void> confirmTransferred(String source, String receipt) =>
      _migrationChannel.invokeMethod<void>('confirm', {
        'sourceDigest': source,
        'receiptSha256': receipt,
      });
  @override
  Future<void> validateSource(String digest) => _migrationChannel
      .invokeMethod<void>('validate', {'leaseId': lease, 'sha256': digest});
  @override
  Future<void> commit(File receipt, String receiptDigest) =>
      _migrationChannel.invokeMethod<void>('commit', {
        'leaseId': lease,
        'receipt': receipt.path,
        'sha256': receiptDigest,
      });
}

Future<void>? _preparing;
Future<void> prepareNativeLegacyData() {
  return _preparing ??= _prepareNativeLegacyData().catchError((
    Object error,
    StackTrace stack,
  ) {
    _preparing = null;
    Error.throwWithStackTrace(error, stack);
  });
}

Future<void> _prepareNativeLegacyData() async {
  final status = await _migrationChannel.invokeMapMethod<String, dynamic>(
    'begin',
  );
  if (status == null) throw StateError('Native migration owner did not reply');
  if (status['status'] == 'absent') return;
  if (status['status'] == 'shared') {
    final home = Directory(
      await Directory(await appPath.homeDirPath).resolveSymbolicLinks(),
    );
    await finishNativeLiteTransfer(
      home,
      status['sourceDigest'] as String,
      status['receiptSha256'] as String,
    );
    return;
  }
  if (status['status'] != 'pending' ||
      status['leaseId'] is! String ||
      status['manifest'] is! String ||
      status['sha256'] is! String) {
    throw StateError('Invalid Native migration lease');
  }
  final lease = status['leaseId'] as String;
  try {
    final home = Directory(
      await Directory(await appPath.homeDirPath).resolveSymbolicLinks(),
    );
    final manifest = File(status['manifest'] as String).absolute;
    if (!p.isWithin(home.absolute.path, manifest.path))
      throw StateError('Native export is outside the private data root');
    final source = await NativeLiteExport.open(
      manifest,
      status['sha256'] as String,
    );
    final storage = await preferences.sharedPreferences;
    final defaults = await XClashDefaults.open();
    if (storage == null || defaults == null)
      throw StateError('Shared persistent storage is unavailable');
    await NativeLiteMigration(
      home: home,
      database: database,
      preferences: storage,
      defaults: defaults,
      authority: _PlatformAuthority(lease),
      createId: () => snowflake.id,
    ).run(source);
  } finally {
    await _migrationChannel.invokeMethod<void>('release', {'leaseId': lease});
  }
}
