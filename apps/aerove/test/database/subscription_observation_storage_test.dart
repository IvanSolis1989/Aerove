import 'dart:convert';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'real SQLite converter qualifies imported JSON before it can reach a meter',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'quota-json-storage-',
      );
      final file = File('${directory.path}/quota.sqlite');
      var database = Database(NativeDatabase(file));
      try {
        const profile = Profile(
          id: 7,
          type: ProfileType.url,
          url: 'https://example.test/sub',
          autoUpdateDuration: Duration.zero,
        );
        await database.profiles.put(profile.toCompanion());
        for (final value in [
          1.5,
          -1,
          9223372036854775808.0,
          9007199254740992.0,
        ]) {
          await database.customStatement(
            'UPDATE profiles SET subscription_info = ? WHERE id = ?',
            [
              jsonEncode({
                'observationVersion': 1,
                'upload': value,
                'download': 0,
                'total': 100,
              }),
              profile.id,
            ],
          );
          final read = (await database.profilesDao.get(
            profile.id,
          ))!.subscriptionInfo!;
          expect(read.upload, isNull);
          expect(read.used, isNull);
          expect(read.usage, isNull);
          expect(read.unqualifiedCounts['upload'], value);
          await database.profiles.put(
            profile.copyWith(subscriptionInfo: read).toCompanion(),
          );
          expect(
            (await database.profilesDao.get(profile.id))!.subscriptionInfo,
            read,
          );
        }
        for (final value in [0, 12, 9223372036854775807]) {
          await database.customStatement(
            'UPDATE profiles SET subscription_info = ? WHERE id = ?',
            [
              jsonEncode({'observationVersion': 1, 'upload': value}),
              profile.id,
            ],
          );
          final read = (await database.profilesDao.get(
            profile.id,
          ))!.subscriptionInfo!;
          expect(
            read.knownUpload,
            SubscriptionInfo.formHString('upload=$value').knownUpload,
          );
        }
        await database.customStatement(
          'UPDATE profiles SET subscription_info = ? WHERE id = ?',
          [
            jsonEncode({
              'observationVersion': 1.5,
              'upload': 10,
              'download': 20,
              'total': 100,
            }),
            profile.id,
          ],
        );
        final invalidVersion = (await database.profilesDao.get(
          profile.id,
        ))!.subscriptionInfo!;
        expect(invalidVersion.observationVersion, 0);
        expect(invalidVersion.usage, isNull);
        await database.customStatement(
          'UPDATE profiles SET subscription_info = ? WHERE id = ?',
          [
            jsonEncode({'upload': 0, 'download': -1, 'total': 1.5}),
            profile.id,
          ],
        );
        final legacy = (await database.profilesDao.get(
          profile.id,
        ))!.subscriptionInfo!;
        expect(legacy.upload, 0);
        expect(legacy.knownUpload, isNull);
        expect(legacy.unqualifiedCounts, {'download': -1, 'total': 1.5});
        expect(legacy.observedAt, isNull);
        expect(legacy.sourceKey, isNull);
        expect(legacy.usage, isNull);
        await database.profiles.put(
          profile.copyWith(subscriptionInfo: legacy).toCompanion(),
        );
        await database.close();
        database = Database(NativeDatabase(file));
        expect(
          (await database.profilesDao.get(profile.id))!.subscriptionInfo,
          legacy,
        );
      } finally {
        await database.close();
        await directory.delete(recursive: true);
      }
    },
  );
}
