import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tray/tray.dart';

const MethodChannel _channel = MethodChannel('tray');
const _asset = 'assets/images/tray/macos/status_1.png';

TraySpec _spec(String label, {bool isTemplate = true}) {
  return TraySpec(
    icon: TrayIcon.asset(_asset, isTemplate: isTemplate),
    menu: [TrayMenuAction(label: label)],
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  late List<String> loads;

  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.macOS;
    calls = [];
    loads = [];
    messenger.setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      return true;
    });
    messenger.setMockMessageHandler('flutter/assets', (message) async {
      final key = utf8.decode(message!.buffer.asUint8List());
      loads.add(key);
      return key == _asset
          ? ByteData.sublistView(Uint8List.fromList(const [1]))
          : null;
    });
    Tray.instance.resetForTesting();
  });

  tearDown(() {
    messenger
      ..setMockMethodCallHandler(_channel, null)
      ..setMockMessageHandler('flutter/assets', null);
    Tray.instance.resetForTesting();
    debugDefaultTargetPlatformOverride = null;
  });

  Map<Object?, Object?> iconOf(MethodCall call) {
    return (call.arguments as Map)['icon'] as Map<Object?, Object?>;
  }

  test('a menu-only change reuses the resolved icon', () async {
    await Tray.instance.show(_spec('a'));
    expect(loads, hasLength(Tray.variantScales.length));

    await Tray.instance.show(_spec('b'));

    expect(loads, hasLength(Tray.variantScales.length));
    final shows = calls.where((call) => call.method == 'show').toList();
    expect(shows, hasLength(2));
    expect(iconOf(shows.last), iconOf(shows.first));
  });

  test('another icon is resolved on its own', () async {
    await Tray.instance.show(_spec('a'));
    loads.clear();

    await Tray.instance.show(_spec('a', isTemplate: false));

    expect(loads, hasLength(Tray.variantScales.length));
    expect(iconOf(calls.last)['isTemplate'], isFalse);
  });
}
