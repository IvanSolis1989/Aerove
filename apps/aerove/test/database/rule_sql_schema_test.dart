import 'dart:io';
import 'package:drift/native.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite;

void main() {
  test(
    'fixed old22 declarations upgrade business data to23 with a new durable identity',
    () async {
      final home = await Directory.systemTemp.createTemp('rule-schema22-');
      final file = File('${home.path}/database.sqlite');
      final raw = sqlite.sqlite3.open(file.path);
      try {
        raw.execute(
          await File('test/fixtures/native_schema22.sql').readAsString(),
        );
        raw.execute(
          "INSERT INTO profiles (id,type,label,url,extend_type,auto_update_duration_millis,auto_update,selected_map,unfold_set) VALUES (71,'file','legacy','','standard',0,1,'{}','[]')",
        );
        expect(raw.select('PRAGMA user_version').single['user_version'], 22);
      } finally {
        raw.dispose();
      }
      final db = Database(NativeDatabase(file));
      try {
        expect((await db.profilesDao.get(71))!.label, 'legacy');
        expect(
          (await db.customSelect('PRAGMA user_version').get()).single.read<int>(
            'user_version',
          ),
          23,
        );
        final proof = await db.ruleSqlInfrastructureProof(resolved: true);
        expect(proof['incarnation'], matches(r'^[0-9a-f-]{36}$'));
        expect(proof['tokens'], isEmpty);
        expect(
          db.businessTables.map((t) => t.actualTableName).toSet(),
          ruleSqlBusinessTables,
        );
      } finally {
        await db.close();
        await home.delete(recursive: true);
      }
    },
  );
  test(
    'unknown version24 keeps its exact database bytes, version and business rows',
    () async {
      final home = await Directory.systemTemp.createTemp('rule-future-schema-');
      final file = File('${home.path}/database.sqlite');
      var db = Database(NativeDatabase(file));
      await db.profiles.put(
        const Profile(
          id: 71,
          label: 'future facts',
          autoUpdateDuration: Duration.zero,
        ).toCompanion(),
      );
      await db.close();
      final raw = sqlite.sqlite3.open(file.path);
      raw.execute('PRAGMA user_version=24');
      raw.dispose();
      final original = await file.readAsBytes();
      db = Database(NativeDatabase(file));
      try {
        await expectLater(
          db.customSelect('SELECT * FROM profiles').get(),
          throwsStateError,
        );
      } finally {
        await db.close();
      }
      try {
        expect(await file.readAsBytes(), original);
        final observer = sqlite.sqlite3.open(
          file.path,
          mode: sqlite.OpenMode.readOnly,
        );
        try {
          expect(
            observer.select('PRAGMA user_version').single['user_version'],
            24,
          );
          expect(
            observer
                .select('SELECT label FROM profiles WHERE id = 71')
                .single['label'],
            'future facts',
          );
        } finally {
          observer.dispose();
        }
      } finally {
        await home.delete(recursive: true);
      }
    },
  );
  test(
    'schema23 rejects an unknown table after an already cached successful proof',
    () async {
      final home = await Directory.systemTemp.createTemp(
        'rule-schema-unknown-',
      );
      final db = Database(NativeDatabase(File('${home.path}/database.sqlite')));
      try {
        await db.ruleSqlInfrastructureProof(resolved: true);
        await db.customStatement(
          'CREATE TABLE unexpected_business (id INTEGER)',
        );
        await expectLater(db.ruleSqlInfrastructureProof(), throwsStateError);
      } finally {
        await db.close();
        await home.delete(recursive: true);
      }
    },
  );
}
