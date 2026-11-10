import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:drift/native.dart';
import 'package:fl_clash/common/rule_sql_journal.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'app_paths.dart';

class ProfilePublicationFixture {
  ProfilePublicationFixture(this.home)
    : database = db.Database(
        NativeDatabase(File('${home.path}/database.sqlite')),
      );

  final Directory home;
  final db.Database database;
  ProviderContainer? container;
  late final owner = RuleSqlJournalOwner(database, home);
  static final before = Profile(
    id: 71,
    type: ProfileType.url,
    label: 'profile',
    url: 'https://before.invalid/private-token',
    ageSecretKey: 'private-age-key',
    lastUpdateDate: DateTime.fromMillisecondsSinceEpoch(1000),
    autoUpdateDuration: Duration.zero,
  );
  static final after = before.copyWith(
    url: 'https://after.invalid/private-token',
    lastUpdateDate: DateTime.fromMillisecondsSinceEpoch(2000),
  );

  File get yaml => File('${home.path}/profiles/71.yaml');
  File get input => File('${home.path}/profiles/71.input');
  Profiles get notifier => container!.read(profilesProvider.notifier);

  Future<void> seed({bool existing = true}) async {
    await useAppPaths(home);
    if (existing) {
      await database.profiles.put(before.toCompanion());
      await yaml.parent.create(recursive: true);
      await yaml.writeAsString('old-yaml', flush: true);
      await input.writeAsString('old-input', flush: true);
    }
    await attach();
  }

  Future<void> attach() async {
    await useAppPaths(home);
    await AppLocalizations.load(const Locale('en'));
    db.database = database;
    container = ProviderContainer();
    container!.listen(profilesProvider, (_, _) {});
    await pumpEventQueue();
    await database.profilesDao.query().get();
    await pumpEventQueue();
  }

  Future<PreparedProfileFile> prepare({bool distinctInput = true}) =>
      after.prepareFile(
        Uint8List.fromList(utf8.encode('new-yaml')),
        originalInputBytes: distinctInput
            ? Uint8List.fromList(utf8.encode('new-input'))
            : null,
        validate: (_, _) async => '',
      );

  Future<Profile> publish({
    PreparedProfileFile? prepared,
    bool Function()? isCurrent,
    void Function()? admission,
  }) async {
    final file = prepared ?? await prepare();
    try {
      return await notifier.publishUpdate(
        after,
        original: before,
        isCurrent: isCurrent ?? () => true,
        preparedFile: file,
        publishFile: admission ?? () {},
        rollbackFile: () => throw StateError('Legacy rollback must not run'),
      );
    } finally {
      await file.dispose();
    }
  }

  Future<void> recover() => owner.exclusive(owner.recover);

  Future<void> expectVersion(
    bool committed, {
    bool distinctInput = true,
  }) async {
    expect(await database.profilesDao.get(71), committed ? after : before);
    expect(await yaml.readAsString(), committed ? 'new-yaml' : 'old-yaml');
    if (committed && !distinctInput) {
      expect(await input.exists(), isFalse);
    } else {
      expect(await input.readAsString(), committed ? 'new-input' : 'old-input');
    }
    await database.ruleSqlInfrastructureProof(resolved: true);
  }

  Future<void> close() async {
    container?.dispose();
    await database.close();
  }
}
