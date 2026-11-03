import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqlite3/sqlite3.dart' as sqlite;

void main() {
  test(
    'real schema21 upgrade keeps old seconds and null companions; paired writes preserve milliseconds',
    () async {
      final home = await Directory.systemTemp.createTemp('native-schema21-');
      final file = File(p.join(home.path, 'database.sqlite'));
      var db = Database(NativeDatabase(file));
      final old = DateTime.fromMillisecondsSinceEpoch(1700000000000);
      try {
        await db.profilesDao.setAll([
          Profile(
            id: 1,
            label: 'old',
            autoUpdateDuration: const Duration(milliseconds: 1234),
            lastUpdateDate: old,
          ),
        ]);
        await db.scriptsDao.setAll([
          Script(id: 2, label: 'old script', lastUpdateTime: old),
        ]);
        await db.iconSetsDao.putAll([
          IconSet(id: 3, name: 'old icons', lastUpdateTime: old).toCompanion(),
        ]);
        await db.clashProvidersDao.putAll([
          const ClashProvider(
            id: 4,
            label: 'old provider',
            url: 'https://example.invalid/rules',
          ).toCompanion(),
        ]);
        await db.close();
        final raw = sqlite.sqlite3.open(file.path);
        final indexes = raw
            .select(
              "SELECT name,sql FROM sqlite_master WHERE type='index' ORDER BY name",
            )
            .map((r) => r.values.toList())
            .toList();
        for (final (table, column) in [
          ('profiles', 'last_update_date_millis'),
          ('scripts', 'last_update_time_millis'),
          ('icon_sets', 'last_update_time_millis'),
          ('clash_providers', 'source_definition'),
        ]) {
          raw.execute('ALTER TABLE $table DROP COLUMN $column');
        }
        raw.execute('PRAGMA user_version=21');
        raw.dispose();
        db = Database(NativeDatabase(file));
        expect((await db.profilesDao.query().get()).single.lastUpdateDate, old);
        expect((await db.scriptsDao.query().get()).single.lastUpdateTime, old);
        expect((await db.iconSetsDao.query().get()).single.lastUpdateTime, old);
        for (final (table, column) in [
          ('profiles', 'last_update_date_millis'),
          ('scripts', 'last_update_time_millis'),
          ('icon_sets', 'last_update_time_millis'),
          ('clash_providers', 'source_definition'),
        ]) {
          expect(
            (await db.customSelect('SELECT $column FROM $table').getSingle())
                .data[column],
            isNull,
          );
        }
        expect(
          (await db
                  .customSelect(
                    "SELECT name,sql FROM sqlite_master WHERE type='index' ORDER BY name",
                  )
                  .get())
              .map((r) => r.data.values.toList())
              .toList(),
          indexes,
        );
        expect(
          (await db.clashProvidersDao.query().get()).single.sourceDefinition,
          isNull,
        );
        final precise = DateTime.fromMillisecondsSinceEpoch(1700000000789);
        await db.profilesDao.setAll([
          Profile(
            id: 1,
            label: 'old',
            autoUpdateDuration: const Duration(milliseconds: 1234),
            lastUpdateDate: precise,
          ),
        ]);
        await db.scriptsDao.setAll([
          Script(id: 2, label: 'old script', lastUpdateTime: precise),
        ]);
        await db.iconSetsDao.putAll([
          IconSet(
            id: 3,
            name: 'old icons',
            lastUpdateTime: precise,
          ).toCompanion(),
        ]);
        await db.close();
        db = Database(NativeDatabase(file));
        expect(
          (await db.profilesDao.query().get()).single.lastUpdateDate,
          precise,
        );
        expect(
          (await db.scriptsDao.query().get()).single.lastUpdateTime,
          precise,
        );
        expect(
          (await db.iconSetsDao.query().get()).single.lastUpdateTime,
          precise,
        );
      } finally {
        await db.close();
        await home.delete(recursive: true);
      }
    },
  );
}
