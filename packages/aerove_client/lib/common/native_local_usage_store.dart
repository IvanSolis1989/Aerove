import 'dart:io';

import 'package:flutter/services.dart';

import 'constant.dart' show packageName;
import 'local_usage_ledger.dart';

class NativeLocalUsageStorage implements LocalUsageStorage {
  const NativeLocalUsageStorage();
  static const _channel = MethodChannel('$packageName/app');

  Future<Map<String, dynamic>> _call(Map<String, Object?> request) async {
    if (!Platform.isAndroid) {
      throw UnsupportedError(
        'This platform has no qualified local usage commit owner',
      );
    }
    final result = await _channel
        .invokeMapMethod<String, dynamic>('localUsageLedger', {
          ...request,
          'activationVersion': 2,
        })
        .timeout(const Duration(seconds: 5));
    if (result == null)
      throw StateError('Local usage storage returned no acknowledgement');
    if (result['status'] == 'retired') throw UsageWriterRetired();
    if (result['activationVersion'] is! int ||
        result['activationVersion'] != 2) {
      throw UnsupportedError(
        'Native local usage activation protocol is unqualified',
      );
    }
    return result;
  }

  UsageStorageView _view(Map<String, dynamic> row) {
    if (row['status'] != 'confirmed' ||
        row['ack'] != true ||
        row['lease'] is! String ||
        row['activation'] is! String ||
        (row['activation'] as String).isEmpty ||
        (row['revision'] == null) != (row['payload'] == null) ||
        row['revision'] != null &&
            (row['revision'] is! String || row['payload'] is! String)) {
      throw StateError('Local usage storage has no confirmed record');
    }
    return UsageStorageView(
      lease: row['lease'] as String,
      revision: row['revision'] as String?,
      payload: row['payload'] as String?,
    );
  }

  @override
  Future<UsageStorageView> claim() async =>
      _view(await _call({'operation': 'claim'}));
  @override
  Future<UsageStorageView> read(String lease) async =>
      _view(await _call({'operation': 'read', 'lease': lease}));
  @override
  Future<UsageWriteAck> write(
    String lease,
    String? expectedRevision,
    String payload,
  ) async {
    final row = await _call({
      'operation': 'write',
      'lease': lease,
      'expectedRevision': expectedRevision,
      'payload': payload,
    });
    return UsageWriteAck(
      acknowledged: row['ack'] == true && row['status'] == 'committed',
      revision: row['revision'] as String?,
      payload: row['payload'] as String?,
    );
  }
}

final localUsageLedger = LocalUsageLedger(const NativeLocalUsageStorage());
