import 'dart:io';

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('$packageName/app');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('passes the selected APK direct URL and filename to Android', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return call.method == 'getSupportedAbis' ? ['arm64-v8a'] : 42;
    });
    final app = App();
    expect(await app.getSupportedAbis(), ['arm64-v8a']);
    final url = Uri.parse(
      'https://github.com/IvanSolis1989/Aerove/releases/download/v1/Aerove-android-arm64.apk',
    );
    expect(await app.downloadUpdate(url, 'Aerove-android-arm64.apk'), 42);
    expect(calls.last.method, 'downloadUpdate');
    expect(calls.last.arguments, {
      'url': url.toString(),
      'fileName': 'Aerove-android-arm64.apk',
    });
    final native = File(
      'android/app/src/main/kotlin/com/follow/clash/plugins/AppPlugin.kt',
    ).readAsStringSync();
    expect(native, contains('"getSupportedAbis"'));
    expect(native, contains('"downloadUpdate"'));
    expect(native, contains('call.argument<String>("url")'));
    expect(native, contains('call.argument<String>("fileName")'));
  });

  test('propagates rejection and permits a later retry', () async {
    var attempts = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (++attempts == 1) throw PlatformException(code: 'DOWNLOAD_FAILED');
      return 7;
    });
    final url = Uri.parse(
      'https://github.com/owner/repo/releases/download/v1/a.apk',
    );
    await expectLater(
      App().downloadUpdate(url, 'a.apk'),
      throwsA(isA<PlatformException>()),
    );
    expect(await App().downloadUpdate(url, 'a.apk'), 7);
  });

  test('does not report an invalid or missing download ID as success', () async {
    for (final id in [null, -1, 0]) {
      messenger.setMockMethodCallHandler(channel, (_) async => id);
      await expectLater(
        App().downloadUpdate(Uri.parse('https://github.com/a.apk'), 'a.apk'),
        throwsStateError,
      );
    }
  });
}
