import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/file_provider_source.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;

void main() {
  late Directory home;
  const relative =
      'profiles/providers/7/proxies/7a6fd7b88acffbc5596c6e8a877852b3';
  setUp(() async {
    home = await Directory.systemTemp.createTemp('file-source-marker-');
  });
  tearDown(() async {
    await home.delete(recursive: true);
  });
  void register() => registerFileProviderSource(
    homeDirPath: home.path,
    profileId: 7,
    section: 'proxy-providers',
    name: 'fixture',
    sourcePath: p.join(home.path, relative),
  );
  test(
    'existing file sources have relative recoverable markers and unmarked HTTP caches are excluded',
    () {
      expect(fileProviderSourcePath(7, 'proxy-providers', 'fixture'), relative);
      final source = File(p.join(home.path, relative))
        ..createSync(recursive: true)
        ..writeAsBytesSync([0, 255, 1]);
      File(
        p.join(source.parent.path, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
      ).writeAsStringSync('HTTP cache');
      register();
      final marker = File('${source.path}.source.json');
      expect(jsonDecode(marker.readAsStringSync())['relativePath'], relative);
      expect(fileProviderSourceBackupEntries(home.path, [7]), [
        relative,
        '$relative.source.json',
      ]);
      expect(fileProviderSourceBackupEntries(home.path, [8]), isEmpty);
      register();
      expect(source.readAsBytesSync(), [0, 255, 1]);
    },
  );
  test(
    'missing bytes do not create discoverable sources and forged marker paths fail closed',
    () {
      register();
      expect(fileProviderSourceBackupEntries(home.path, [7]), isEmpty);
      final source = File(p.join(home.path, relative))
        ..createSync(recursive: true)
        ..writeAsStringSync('source');
      register();
      final marker = File('${source.path}.source.json');
      final value =
          jsonDecode(marker.readAsStringSync()) as Map<String, dynamic>;
      value['relativePath'] = '../../outside';
      marker.writeAsStringSync(jsonEncode(value));
      expect(
        () => fileProviderSourceBackupEntries(home.path, [7]),
        throwsFormatException,
      );
      expect(
        () => checkFileProviderSourceRestorePaths(home.path, ['../outside']),
        throwsFormatException,
      );
    },
  );
  test('a linked source is never registered or followed for backup', () async {
    final outside = File(p.join(home.path, 'outside'))
      ..writeAsStringSync('external data');
    final source = File(p.join(home.path, relative));
    source.parent.createSync(recursive: true);
    try {
      await Link(source.path).create(outside.path);
    } on FileSystemException {
      if (!Platform.isWindows) rethrow;
      markTestSkipped('Windows host does not allow this symbolic-link fixture');
      return;
    }
    expect(register, throwsFormatException);
    expect(outside.readAsStringSync(), 'external data');
  });
}
