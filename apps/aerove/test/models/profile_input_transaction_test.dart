import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import '../helpers/app_paths.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  const profile = Profile(id: 7, autoUpdateDuration: Duration.zero);
  Uint8List bytes(String value) => Uint8List.fromList(utf8.encode(value));
  Future<String> normalized(String path, String _) async {
    await File(
      path,
    ).writeAsString('proxies: [{name: all-original-nodes, type: mieru}]');
    return '';
  }

  Future<File> input() async => File(
    BackupEntries.resolve(
      await appPath.homeDirPath,
      BackupEntries.profileInput(7),
    ),
  );
  setUp(() async {
    home = await Directory.systemTemp.createTemp('profile-input-transaction-');
    await useAppPaths(home);
  });
  tearDown(() async {
    await home.delete(recursive: true);
  });
  for (final source in [
    'age-encryption.org/v1\nsynthetic-encrypted-input',
    'ss://synthetic-share-link',
  ]) {
    test(
      'converted input remains recoverable through publish and rollback: ${source.split('\n').first}',
      () async {
        await profile.saveFile(bytes('old-yaml'), validate: normalized);
        final oldYaml = await (await profile.file).readAsString();
        final oldInput = await (await input()).readAsBytes();
        final pending = await profile.prepareFile(
          bytes(source),
          validate: normalized,
        );
        try {
          pending.publish();
          expect(await (await input()).readAsBytes(), bytes(source));
          expect(await (await profile.file).readAsString(), contains('mieru'));
          pending.rollback();
          expect(await (await profile.file).readAsString(), oldYaml);
          expect(await (await input()).readAsBytes(), oldInput);
        } finally {
          await pending.dispose();
        }
      },
    );
  }
  test(
    'download decoding preserves the exact received compressed bytes',
    () async {
      const yaml = 'proxies: [{name: full-source, type: mieru}]';
      final received = Uint8List.fromList(gzip.encode(bytes(yaml)));
      await profile.saveFile(
        bytes(yaml),
        originalInputBytes: received,
        validate: (_, _) async => '',
      );
      expect(await (await profile.file).readAsString(), yaml);
      expect(await (await input()).readAsBytes(), received);
    },
  );
  test(
    'unchanged new input removes an obsolete archive but rollback restores it',
    () async {
      await profile.saveFile(bytes('converted-before'), validate: normalized);
      final oldInput = await (await input()).readAsBytes();
      final pending = await profile.prepareFile(
        bytes('ordinary-yaml'),
        validate: (_, _) async => '',
      );
      try {
        pending.publish();
        expect(await (await input()).exists(), false);
        pending.rollback();
        expect(await (await input()).readAsBytes(), oldInput);
      } finally {
        await pending.dispose();
      }
    },
  );
  test(
    'cancellation, validation failure and publish failure preserve both existing files',
    () async {
      await profile.saveFile(bytes('before'), validate: normalized);
      final beforeYaml = await (await profile.file).readAsBytes();
      final beforeInput = await (await input()).readAsBytes();
      var current = true;
      await expectLater(
        profile.prepareFile(
          bytes('cancelled'),
          validate: (path, key) async {
            await normalized(path, key);
            current = false;
            return '';
          },
          isCurrent: () => current,
        ),
        throwsA(isA<UpdateCancelledException>()),
      );
      await expectLater(
        profile.prepareFile(
          bytes('invalid'),
          validate: (_, _) async => 'invalid fixture',
        ),
        throwsA(isA<MessageException>()),
      );
      final pending = await profile.prepareFile(
        bytes('publish-failure'),
        validate: normalized,
      );
      try {
        await pending.stage.delete();
        expect(pending.publish, throwsA(isA<FileSystemException>()));
        expect(await (await profile.file).readAsBytes(), beforeYaml);
        expect(await (await input()).readAsBytes(), beforeInput);
      } finally {
        await pending.dispose();
      }
    },
  );
}
