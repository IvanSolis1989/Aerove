import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/native_lite_mapping.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/database/database.dart';
import 'package:fl_clash/product.dart';
import '../../../aerove-lite/lib/lite_product.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

class _NativeBridge implements NativeLiteAuthority {
  _NativeBridge(this.process, this.lines, this.home, this.preferences);
  final Process process;
  final StreamIterator<String> lines;
  final Directory home;
  final SharedPreferences preferences;
  late String lease;
  bool failCommit = false;
  Future<dynamic> call(
    String method, [
    Map<String, Object?> args = const {},
  ]) async {
    process.stdin.writeln(jsonEncode({'method': method, ...args}));
    await process.stdin.flush();
    if (!await lines.moveNext())
      throw StateError('Native fixture owner exited');
    final result = jsonDecode(lines.current) as Map;
    if (result['ok'] != true)
      throw StateError('Native fixture rejected $method ${result['code']}');
    return result['result'];
  }

  @override
  Future<void> prepareFiles(File plan, String digest) async {
    await call('prepareFiles', {
      'lease': lease,
      'path': plan.path,
      'sha256': digest,
    });
  }

  @override
  Future<void> validateSource(String digest) async {
    await call('validate', {'lease': lease, 'sha256': digest});
  }

  @override
  Future<void> confirmTransferred(String source, String digest) async {
    await call('confirm', {'source': source, 'sha256': digest});
  }

  @override
  Future<void> commit(File receipt, String digest) async {
    await File(p.join(home.path, 'fixture-preferences.json')).writeAsString(
      jsonEncode({
        'config': preferences.getString(configKey),
        'version': preferences.getInt('version'),
        'upgradeVersion': preferences.getInt('upgradeVersion'),
      }),
      flush: true,
    );
    if (failCommit)
      throw StateError('fixture interruption before authority transfer');
    await call('commit', {
      'lease': lease,
      'path': receipt.path,
      'sha256': digest,
    });
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});
  try {
    ClientProduct.current;
  } on StateError {
    ClientProduct.configure(
      product: liteProduct,
      backendCoreId: 'meow-rs',
      backendFactory: () => throw StateError('fixture must not start core'),
    );
  }
  for (final interrupted in [false, true]) {
    test(
      'same-root Native owner and real SQLite transfer; process restart=$interrupted',
      () async {
        const descriptorPath = String.fromEnvironment(
          'NATIVE_LITE_BUILDER_FIXTURE',
        );
        const commandPath = String.fromEnvironment('NATIVE_LITE_BRIDGE');
        final descriptor =
            jsonDecode(await File(descriptorPath).readAsString()) as Map;
        final original = await NativeLiteExport.open(
          File(descriptor['manifest'] as String),
          descriptor['sha256'] as String,
        );
        final command =
            jsonDecode(await File(commandPath).readAsString()) as Map;
        final home = await Directory.systemTemp.createTemp('native-same-root-');
        final before = <String, String>{};
        for (final path in original.records.keys) {
          final source = await original.source(path);
          final target = File(p.join(home.path, path));
          await target.parent.create(recursive: true);
          await source.copy(target.path);
          before[path] = sha256.convert(await target.readAsBytes()).toString();
        }
        // Reconstruct the fixture's existing advisory lock; export deliberately
        // excludes lock artifacts and its read-only owner never creates them.
        await File(
          p.join(command['source'] as String, '.domain.lock'),
        ).copy(p.join(home.path, '.domain.lock'));
        var process = await Process.start(command['executable'] as String, [
          ...(command['args'] as List).cast<String>(),
          home.path,
          command['python'] as String,
          command['verifier'] as String,
        ]);
        var stderr = process.stderr.transform(utf8.decoder).join();
        var bridge = _NativeBridge(
          process,
          StreamIterator(
            process.stdout
                .transform(utf8.decoder)
                .transform(const LineSplitter()),
          ),
          home,
          await SharedPreferences.getInstance(),
        );
        var database = Database(
          NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
        );
        await bridge.preferences.clear();
        try {
          final opened = Map<String, dynamic>.from(
            await bridge.call('begin') as Map,
          );
          bridge.lease = opened['leaseId'] as String;
          expect(opened['sha256'], original.digest);
          var source = await NativeLiteExport.open(
            File(opened['manifest'] as String),
            opened['sha256'] as String,
          );
          var defaults = XClashDefaults(
            storage: bridge.preferences,
            database: database,
            directory: home,
            loadScript: () async => throw StateError('must not reseed'),
            createId: () => 99999,
          );
          var next = 700;
          var migration = NativeLiteMigration(
            home: home,
            database: database,
            preferences: bridge.preferences,
            defaults: defaults,
            authority: bridge,
            createId: () => ++next,
          );
          if (interrupted) {
            bridge.failCommit = true;
            await expectLater(migration.run(source), throwsStateError);
            expect(
              (jsonDecode(
                    await File(
                      p.join(
                        home.path,
                        'native-lite-migration-native/state.json',
                      ),
                    ).readAsString(),
                  )
                  as Map)['phase'],
              'pending',
            );
            expect(
              (jsonDecode(
                    await File(
                      p.join(home.path, 'native-lite-migration/journal.json'),
                    ).readAsString(),
                  )
                  as Map)['phase'],
              'preferencesCommitted',
            );
            final savedPreferences = {
              for (final key in bridge.preferences.getKeys())
                key: bridge.preferences.get(key)!,
            };
            await bridge.call('close');
            await bridge.lines.cancel();
            await process.stdin.close();
            expect(await process.exitCode, 0);
            expect(await stderr, isEmpty);
            await database.close();
            SharedPreferences.setMockInitialValues(savedPreferences);
            database = Database(
              NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
            );
            process = await Process.start(command['executable'] as String, [
              ...(command['args'] as List).cast<String>(),
              home.path,
              command['python'] as String,
              command['verifier'] as String,
            ]);
            stderr = process.stderr.transform(utf8.decoder).join();
            bridge = _NativeBridge(
              process,
              StreamIterator(
                process.stdout
                    .transform(utf8.decoder)
                    .transform(const LineSplitter()),
              ),
              home,
              await SharedPreferences.getInstance(),
            );
            final reopened = Map<String, dynamic>.from(
              await bridge.call('begin') as Map,
            );
            bridge.lease = reopened['leaseId'] as String;
            expect(reopened['sha256'], source.digest);
            source = await NativeLiteExport.open(
              File(reopened['manifest'] as String),
              reopened['sha256'] as String,
            );
            defaults = XClashDefaults(
              storage: bridge.preferences,
              database: database,
              directory: home,
              loadScript: () async => throw StateError('must not reseed'),
              createId: () => 99999,
            );
            migration = NativeLiteMigration(
              home: home,
              database: database,
              preferences: bridge.preferences,
              defaults: defaults,
              authority: bridge,
              createId: () => ++next,
            );
          }
          await migration.run(source);
          final authority =
              jsonDecode(
                    await File(
                      p.join(
                        home.path,
                        'native-lite-migration-native/state.json',
                      ),
                    ).readAsString(),
                  )
                  as Map;
          expect(authority['phase'], 'shared');
          expect(authority['targetPlanSha256'], isA<String>());
          expect((await database.profilesDao.query().get()).length, 4);
          expect((await database.customProxiesDao.query().get()).length, 3);
          for (final entry in before.entries) {
            expect(
              sha256
                  .convert(
                    await File(p.join(home.path, entry.key)).readAsBytes(),
                  )
                  .toString(),
              entry.value,
            );
          }
          await migration.run(source);
          expect((await database.profilesDao.query().get()).length, 4);
          await database.customStatement(
            "UPDATE profiles SET label='shared user edit' WHERE id=(SELECT id FROM profiles LIMIT 1)",
          );
          await migration.run(source);
          expect(
            (await database.profilesDao.query().get()).any(
              (row) => row.label == 'shared user edit',
            ),
            isTrue,
          );
          final completed = Map<String, dynamic>.from(
            await bridge.call('begin') as Map,
          );
          expect(completed['status'], 'shared');
          await finishNativeLiteTransfer(
            home,
            completed['sourceDigest'] as String,
            completed['receiptSha256'] as String,
          );
          await bridge.call('release', {'lease': bridge.lease});
        } finally {
          await bridge.call('close');
          await bridge.lines.cancel();
          await process.stdin.close();
          expect(await process.exitCode, 0);
          expect(await stderr, isEmpty);
          await database.close();
          await home.delete(recursive: true);
        }
      },
    );
  }
}
