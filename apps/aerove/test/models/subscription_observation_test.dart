import 'dart:convert';

import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const url = 'https://example.test/sub?token=private';
  final firstTime = DateTime.utc(2026, 11, 7);
  SubscriptionInfo report(String? header, {SubscriptionInfo? previous}) =>
      SubscriptionInfo.observe(
        header,
        url: url,
        at: firstTime,
        previous: previous,
      );

  test('missing, invalid and explicit zero retain separate meanings', () {
    final absent = report(null);
    expect(absent.upload, isNull);
    expect(absent.total, isNull);
    expect(absent.usage, isNull);
    final zero = report('upload=0;download=0;total=0;expire=0');
    expect(zero.used, 0);
    expect(zero.knownTotal, 0);
    expect(zero.knownExpire, 0);
    expect(zero.usage, isNull);
    expect(zero.expireDate, isNull);
    final partial = report('upload=bad;download=23;total=100;expire=Infinity');
    expect(partial.knownDownload, 23);
    expect(partial.knownUpload, isNull);
    expect(partial.knownExpire, isNull);
    expect(partial.used, isNull);
    expect(partial.usage, isNull);
  });

  test('counts are exact integral nonnegative int64 values', () {
    for (final value in [
      '-1',
      '1.5',
      'NaN',
      'Infinity',
      '9223372036854775808',
      '1e1000',
    ]) {
      expect(report('upload=$value').upload, isNull, reason: value);
    }
    expect(report('upload=1.2e3').upload, 1200);
    expect(report('upload=9007199254740993.0').upload, 9007199254740993);
    expect(report('upload=9223372036854775807').upload, 9223372036854775807);
    expect(report('upload=1;upload=bad').upload, isNull);
    expect(
      report('upload=9223372036854775807;download=1;total=100').used,
      isNull,
    );
  });

  test('current partial observation never combines with previous counters', () {
    final first = report('upload=10;download=20;total=100');
    final next = report('total=200', previous: first);
    expect(next.total, 200);
    expect(next.upload, isNull);
    expect(next.download, isNull);
    expect(next.usage, isNull);
    expect(next.sameSourceLastReported?.used, 30);
    expect(next.sameSourceLastReported?.observedAt, firstTime);
    final absent = report(null, previous: next);
    final absentAgain = report(null, previous: absent);
    expect(absentAgain.lastReported?.total, 200);
    expect(absentAgain.lastReported?.lastReported, isNull);
    expect(absentAgain.usage, isNull);
  });

  test('URL changes do not inherit quota or disclose the URL in metadata', () {
    final first = report('upload=10;download=20;total=100');
    final changed = SubscriptionInfo.observe(
      null,
      url: '$url-new-account',
      at: firstTime,
      previous: first,
    );
    expect(changed.lastReported, isNull);
    expect(changed.usage, isNull);
    expect(jsonEncode(first.toJson()), isNot(contains('token=private')));
    final profile = Profile.normal(url: url).copyWith(subscriptionInfo: first);
    expect(profile.sourceSubscriptionInfo, first);
    expect(
      profile.copyWith(url: '$url-new-account').sourceSubscriptionInfo,
      isNull,
    );
  });

  test(
    'legacy zeros remain stored but have no asserted observation or source',
    () {
      final legacy = SubscriptionInfo.fromJson({
        'upload': 0,
        'download': 20,
        'total': 100,
        'expire': 0,
      });
      expect(legacy.upload, 0);
      expect(legacy.expire, 0);
      expect(legacy.knownUpload, isNull);
      expect(legacy.knownDownload, 20);
      expect(legacy.usage, isNull);
      expect(legacy.sourceKey, isNull);
      expect(legacy.observedAt, isNull);
      final next = report(null, previous: legacy);
      expect(
        next.lastReported?.upload,
        0,
        reason: 'retain the unbound original for recovery',
      );
      expect(
        next.sameSourceLastReported,
        isNull,
        reason: 'not a report for this account',
      );
      expect(SubscriptionInfo.fromJson(legacy.toJson()), legacy);
    },
  );

  test('JSON preserves observation metadata and caps retained history', () {
    final first = report('upload=0;download=20;total=100');
    final next = report(null, previous: first);
    final json = jsonDecode(jsonEncode(next.toJson())) as Map<String, dynamic>;
    expect(SubscriptionInfo.fromJson(json), next);
    json['lastReported']['lastReported'] = first.toJson();
    expect(SubscriptionInfo.fromJson(json).lastReported?.lastReported, isNull);
  });

  test(
    'JSON never truncates fractional, negative or imprecise counts into quota',
    () {
      for (final key in ['upload', 'download', 'total', 'expire']) {
        for (final value in [
          1.5,
          -1,
          9223372036854775808.0,
          9007199254740992.0,
          '12',
        ]) {
          final info = SubscriptionInfo.fromJson({
            'observationVersion': 1,
            'upload': 0,
            'download': 0,
            'total': 100,
            'expire': 0,
            key: value,
          });
          expect(info.toJson()[key], isNull, reason: '$key=$value');
          expect(info.unqualifiedCounts[key], value);
          if (key != 'expire') expect(info.usage, isNull);
          final restored = SubscriptionInfo.fromJson(
            jsonDecode(jsonEncode(info.toJson())) as Map<String, dynamic>,
          );
          expect(
            restored,
            info,
            reason: 'invalid originals remain recoverable',
          );
        }
      }
      final nonfinite = SubscriptionInfo.fromJson({
        'observationVersion': 1,
        'upload': double.infinity,
      });
      expect(nonfinite.knownUpload, isNull);
      expect(jsonEncode(nonfinite.toJson()), contains('Infinity'));
      for (final version in [1.5, '1', double.infinity]) {
        final info = SubscriptionInfo.fromJson({
          'observationVersion': version,
          'upload': 10,
          'download': 20,
          'total': 100,
        });
        expect(info.observationVersion, 0);
        expect(
          info.usage,
          isNull,
          reason: 'invalid version cannot grant current-observation status',
        );
      }
    },
  );

  test(
    'header and JSON accept exact integers and preserve legacy zero without freshness',
    () {
      for (final value in [0, 12, 9007199254740991, 9223372036854775807]) {
        final header = report('upload=$value');
        final json = SubscriptionInfo.fromJson({
          'observationVersion': 1,
          'upload': value,
        });
        expect(json.upload, header.upload);
        expect(json.knownUpload, header.knownUpload);
      }
      expect(
        SubscriptionInfo.fromJson({
          'observationVersion': 1,
          'upload': 12.0,
        }).upload,
        12,
      );
      final legacy = SubscriptionInfo.fromJson({
        'upload': 0,
        'download': -1,
        'total': 1.5,
      });
      expect(legacy.upload, 0);
      expect(legacy.knownUpload, isNull);
      expect(legacy.knownDownload, isNull);
      expect(legacy.unqualifiedCounts, {'download': -1, 'total': 1.5});
      expect(legacy.sourceKey, isNull);
      expect(legacy.observedAt, isNull);
      expect(legacy.usage, isNull);
    },
  );

  test('core fields do not manufacture missing upload or zero total', () {
    final partial = subscriptionInfoFormCore({'Download': 20, 'Total': 100});
    expect(partial?.upload, isNull);
    expect(partial?.usage, isNull);
    final zero = subscriptionInfoFormCore({
      'Upload': 0,
      'Download': 0,
      'Total': 0,
    });
    expect(
      zero?.used,
      isNull,
      reason: 'core zeros have no raw-header presence proof',
    );
    expect(zero?.total, 0);
    expect(zero?.usage, isNull);
  });
}
