import 'package:fl_clash/manager/window_manager.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:window/window.dart' show WindowListener;

import '../helpers/test_profiles.dart';

const _windowChannel = MethodChannel('window');

class _CountingStore extends StoreAction {
  static int saves = 0;

  @override
  void savePreferencesDebounce() {
    saves++;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('minimizing the window leaves the preferences unsaved', (
    tester,
  ) async {
    _CountingStore.saves = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_windowChannel, (call) async {
          return switch (call.method) {
            'isAlwaysOnTop' || 'isMaximized' || 'isFullScreen' => false,
            _ => null,
          };
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(_windowChannel, null),
    );
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        storeActionProvider.overrideWith(_CountingStore.new),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: WindowManager(child: SizedBox.shrink())),
      ),
    );
    await tester.pump(const Duration(milliseconds: 150));
    final listener = tester.state(find.byType(WindowManager)) as WindowListener;

    listener.onWindowMinimize();
    await tester.pump(const Duration(seconds: 1));

    expect(_CountingStore.saves, 0);
  });
}
