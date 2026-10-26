import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('wifi_ssid');
  final manager = WifiSsidManager.instance;
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return null;
        });
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('Android switches the tracking callback on and off', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;

    await manager.setTracking(true);
    await manager.setTracking(false);

    expect(calls.map((call) => (call.method, call.arguments)), [
      ('setTracking', true),
      ('setTracking', false),
    ]);
  });

  for (final platform in [
    TargetPlatform.windows,
    TargetPlatform.macOS,
    TargetPlatform.linux,
  ]) {
    test('${platform.name} never sends a tracking call', () async {
      debugDefaultTargetPlatformOverride = platform;

      await manager.setTracking(true);
      await manager.setTracking(false);

      expect(calls, isEmpty);
    });
  }

  test('an Android platform error reaches the caller', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (_) async => throw PlatformException(code: 'UNAVAILABLE'),
        );

    await expectLater(
      manager.setTracking(true),
      throwsA(isA<PlatformException>()),
    );
  });
}
