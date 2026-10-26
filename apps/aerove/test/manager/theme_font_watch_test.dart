import 'package:fl_clash/common/system_fonts.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _resumes = 5;

void main() {
  late Future<List<String>> Function() originalReader;
  var reads = 0;

  setUp(() {
    originalReader = systemFontFamiliesReader;
    reads = 0;
    systemFontFamiliesReader = () async {
      reads++;
      return const ['Alpha', 'Beta'];
    };
    debugAndroidSystemFontsOverride = true;
  });

  tearDown(() {
    systemFontFamiliesReader = originalReader;
    debugAndroidSystemFontsOverride = null;
  });

  Future<List<String?>> pumpApp(WidgetTester tester, String? font) async {
    final fonts = <String?>[];
    final container = ProviderContainer(
      overrides: [
        themeSettingProvider.overrideWithBuild(
          (_, _) => ThemeProps(fontFamily: font),
        ),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: ThemeManager(
            child: Consumer(
              builder: (_, ref, _) {
                final theme = watchAppTheme(ref, Brightness.light);
                fonts.add(theme.textTheme.bodyMedium?.fontFamily);
                return const SizedBox.shrink();
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return fonts;
  }

  Future<void> returnFromBackground(WidgetTester tester) async {
    for (final state in const [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pumpAndSettle();
  }

  test('only a chosen Android font needs the system catalog', () {
    expect(needsSystemFontCatalog(null, android: true), isFalse);
    expect(needsSystemFontCatalog('Beta', android: true), isTrue);
    expect(needsSystemFontCatalog('Beta', android: false), isFalse);
    expect(needsSystemFontCatalog(null, android: false), isFalse);
  });

  testWidgets('the default font never reads the Android font catalog', (
    tester,
  ) async {
    final fonts = await pumpApp(tester, null);
    expect(reads, 0, reason: 'cold start');

    for (var i = 0; i < _resumes; i++) {
      await returnFromBackground(tester);
    }

    expect(reads, 0, reason: '$_resumes returns from the background');
    expect(fonts.toSet(), {'sans-serif'});
    expect(tester.takeException(), isNull);
  });

  testWidgets('a chosen font is checked again on every return', (tester) async {
    final fonts = await pumpApp(tester, 'Beta');
    expect(reads, 1, reason: 'cold start');
    expect(fonts.last, 'Beta');
    final resolved = fonts.length;

    for (var i = 1; i <= _resumes; i++) {
      await returnFromBackground(tester);
      expect(reads, 1 + i);
    }

    expect(fonts.skip(resolved - 1).toSet(), {'Beta'});
    expect(tester.takeException(), isNull);
  });

  testWidgets('a chosen font missing from the catalog still falls back', (
    tester,
  ) async {
    final fonts = await pumpApp(tester, 'Gamma');
    await returnFromBackground(tester);

    expect(reads, 2);
    expect(fonts.toSet(), {'sans-serif'});
  });
}
