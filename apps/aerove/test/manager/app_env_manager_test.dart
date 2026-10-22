import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/state.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('release candidates do not carry an environment banner', () {
    for (final appEnv in ['pre', 'beta', 'dev']) {
      expect(
        appEnvBannerMessage(
          safeMode: false,
          isDebug: false,
          appEnv: appEnv,
        ),
        isNull,
      );
    }
  });

  test('stable builds carry no environment banner', () {
    expect(
      appEnvBannerMessage(
        safeMode: false,
        isDebug: false,
        appEnv: 'stable',
      ),
      isNull,
    );
  });

  test('debug environment banner and safe mode warning are preserved', () {
    expect(
      appEnvBannerMessage(
        safeMode: false,
        isDebug: true,
        appEnv: 'beta',
      ),
      'DEBUG',
    );
    expect(
      appEnvBannerMessage(
        safeMode: true,
        isDebug: false,
        appEnv: 'beta',
      ),
      'SAFE MODE',
    );
  });

  setUpAll(() {
    globalState.appEnv = 'stable';
  });

  Future<void> pump(WidgetTester tester, {required bool safeMode}) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [safeModeProvider.overrideWithValue(safeMode)],
        child: const Directionality(
          textDirection: TextDirection.ltr,
          child: AppEnvManager(child: SizedBox(key: Key('child'))),
        ),
      ),
    );
  }

  testWidgets('a stable build carries no banner', (tester) async {
    await pump(tester, safeMode: false);

    expect(find.byKey(const Key('child')), findsOneWidget);
    expect(find.byType(Banner), findsNothing);
  });

  testWidgets('a safe mode build is bannered on any channel', (tester) async {
    await pump(tester, safeMode: true);

    final banner = tester.widget<Banner>(find.byType(Banner));
    expect(banner.message, 'SAFE MODE');
    expect(find.byKey(const Key('child')), findsOneWidget);
  });
}
