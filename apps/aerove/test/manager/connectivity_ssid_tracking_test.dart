import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:fl_clash/manager/connectivity_manager.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/on_demand.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

const _channel = MethodChannel('wifi_ssid');

class _Platform {
  final calls = <(String, Object?)>[];
  int permission = 1;

  List<bool> get tracking => [
    for (final (method, argument) in calls)
      if (method == 'setTracking') argument! as bool,
  ];

  List<String> get trackingAndReads => [
    for (final (method, argument) in calls)
      if (method == 'setTracking')
        'setTracking($argument)'
      else if (method == 'getSsid')
        method,
  ];

  Future<Object?> handle(MethodCall call) async {
    calls.add((call.method, call.arguments));
    return switch (call.method) {
      'checkPermission' || 'requestPermission' => permission,
      'getSsid' => 'Home',
      _ => null,
    };
  }
}

void main() {
  late ProviderContainer container;
  late StreamController<List<ConnectivityResult>> connectivity;
  late _Platform platform;

  Future<void> pumpManager(
    WidgetTester tester, {
    List<String> excludeSSIDs = const [],
    bool safeMode = false,
  }) async {
    platform = _Platform();
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(_channel, platform.handle);
    addTearDown(() => messenger.setMockMethodCallHandler(_channel, null));
    container = ProviderContainer(
      overrides: [if (safeMode) safeModeProvider.overrideWithValue(true)],
    );
    globalState.container = container;
    connectivity = StreamController<List<ConnectivityResult>>.broadcast();
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await connectivity.close();
      container.dispose();
    });
    container.read(excludeSSIDsProvider.notifier).value = excludeSSIDs;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: ConnectivityManager(
          connectivityStream: connectivity.stream,
          child: const SizedBox.shrink(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> exclude(WidgetTester tester, List<String> ssids) async {
    container.read(excludeSSIDsProvider.notifier).value = ssids;
    await tester.pumpAndSettle();
  }

  Future<void> checkPermission(WidgetTester tester, int permission) async {
    platform.permission = permission;
    await container.read(locationPermissionsProvider.notifier).refresh();
    await tester.pumpAndSettle();
  }

  testWidgets('no excluded network never turns tracking on', (tester) async {
    await pumpManager(tester);
    expect(platform.tracking, isEmpty, reason: 'nothing to switch at start');

    await checkPermission(tester, 0);

    expect(
      container.read(locationPermissionsProvider),
      WifiSsidPermission.granted,
    );
    expect(platform.tracking, [false]);
  });

  testWidgets('tracking follows the excluded list and the permission', (
    tester,
  ) async {
    await pumpManager(tester, excludeSSIDs: const ['Home']);
    expect(platform.tracking, [false], reason: 'excluded but not granted');

    await checkPermission(tester, 0);
    expect(platform.tracking, [false, true]);

    await exclude(tester, const []);
    expect(platform.tracking, [false, true, false]);

    await exclude(tester, const ['Office']);
    expect(platform.tracking, [false, true, false, true]);

    await exclude(tester, const ['Office', 'Home']);
    await checkPermission(tester, 0);
    expect(platform.tracking, [
      false,
      true,
      false,
      true,
    ], reason: 'neither half of the gate changed');
  });

  testWidgets('a lost permission stops tracking before the next read', (
    tester,
  ) async {
    await pumpManager(tester, excludeSSIDs: const ['Home']);
    connectivity.add([ConnectivityResult.wifi]);
    await checkPermission(tester, 0);
    platform.calls.clear();

    await checkPermission(tester, 1);

    expect(platform.trackingAndReads, ['setTracking(false)', 'getSsid']);
  });

  testWidgets('a safe mode build never switches tracking', (tester) async {
    await pumpManager(tester, excludeSSIDs: const ['Home'], safeMode: true);
    platform.permission = 0;
    await container.read(locationPermissionsProvider.notifier).request();
    await tester.pumpAndSettle();
    await exclude(tester, const []);

    expect(
      container.read(locationPermissionsProvider),
      WifiSsidPermission.granted,
    );
    expect(platform.tracking, isEmpty);
  });

  testWidgets(
    'the desktop never sends a tracking call',
    variant: TargetPlatformVariant.only(TargetPlatform.windows),
    (tester) async {
      await pumpManager(tester, excludeSSIDs: const ['Home']);
      await checkPermission(tester, 0);
      await exclude(tester, const []);

      expect(
        container.read(locationPermissionsProvider),
        WifiSsidPermission.granted,
      );
      expect(platform.tracking, isEmpty);
    },
  );
}
