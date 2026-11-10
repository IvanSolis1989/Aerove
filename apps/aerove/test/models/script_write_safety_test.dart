import 'dart:async';
import 'dart:io';

import 'package:fl_clash/common/request.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../helpers/app_paths.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  late HttpServer server;
  late Completer<void> entered;
  late Completer<void> release;
  late Script script;

  setUp(() async {
    final container = ProviderContainer();
    globalState.container = container;
    addTearDown(container.dispose);
    final original = HttpOverrides.current;
    HttpOverrides.global = null;
    addTearDown(() => HttpOverrides.global = original);
    globalState.packageInfo = PackageInfo(
      appName: 'Aerove',
      packageName: 'dev.aerove.test',
      version: '1.0.0',
      buildNumber: '1',
    );
    home = await Directory.systemTemp.createTemp('script-write-safety-');
    await useAppPaths(home);
    entered = Completer<void>();
    release = Completer<void>();
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      if (!entered.isCompleted) entered.complete();
      await release.future;
      try {
        request.response.write('function main(c) { return {remote:true}; }');
        await request.response.close();
      } on IOException {
        // The superseded request is closed before the server responds.
      }
    });
    script = Script.create(
      label: 'script',
      url: 'http://127.0.0.1:${server.port}/script.js',
    );
    script = await script.save('function main(c) { return c; }');
  });
  tearDown(() async {
    if (!release.isCompleted) release.complete();
    await server.close(force: true);
    await home.delete(recursive: true);
  });

  test('a delayed script update cannot replace a newer editor save', () async {
    final pending = script.update();
    final rejected = expectLater(
      pending,
      throwsA(isA<UpdateCancelledException>()),
    );
    await entered.future;
    const edited = 'function main(c) { return {edited:true}; }';
    await script.save(edited);
    release.complete();
    await rejected;
    expect(await script.content, edited);
  });

  test('cancellation before publish preserves the committed body', () async {
    final before = await script.content;
    var checks = 0;
    await expectLater(
      script.save('cancelled-body', isCurrent: () => ++checks < 4),
      throwsA(isA<UpdateCancelledException>()),
    );
    expect(await script.content, before);
    expect(
      home.listSync(recursive: true).where((f) => f.path.endsWith('.update')),
      isEmpty,
    );
  });
  test('publication is the terminal boundary for cancellation', () async {
    const body = 'function main(c) { return {committed:true}; }';
    final path = await script.path;
    final saved = await script.save(
      body,
      isCurrent: () => File(path).readAsStringSync() != body,
    );
    expect(await saved.content, body);
  });

  test('deletion cancels a download without recreating its file', () async {
    final pending = script.update();
    final rejected = expectLater(
      pending,
      throwsA(isA<UpdateCancelledException>()),
    );
    await entered.future;
    cancelScriptWrite(script.id);
    await File(await script.path).delete();
    release.complete();
    await rejected;
    expect(await File(await script.path).exists(), isFalse);
  });

  test('UTF-8 byte limits preserve the old script', () async {
    final before = await script.content;
    await expectLater(
      script.save('界' * (2 * 1024 * 1024)),
      throwsA(isA<DownloadSizeException>()),
    );
    expect(await script.content, before);
  });
}
