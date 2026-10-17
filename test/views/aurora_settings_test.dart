import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/aurora_settings.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer();
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(430, 900);
    container.listen(themeSettingProvider, (_, _) {});
  });

  tearDown(() => container.dispose());

  ThemeProps readTheme() => container.read(themeSettingProvider);

  Future<void> pumpSettings(
    WidgetTester tester, {
    Brightness brightness = Brightness.light,
  }) async {
    tester.view.physicalSize = const Size(430, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: Theme(
            data: ThemeData(brightness: brightness),
            child: const Scaffold(
              body: SingleChildScrollView(child: AuroraSettings()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('toggle preserves wallpaper colors and seed', (tester) async {
    await pumpSettings(tester);
    final original = readTheme();
    expect(find.byType(SeededAuroraWallpaper), findsOneWidget);

    await tester.tap(find.text('Random gradient wallpaper'));
    await tester.pumpAndSettle();
    expect(readTheme().auroraEnabled, isFalse);
    expect(find.byType(SeededAuroraWallpaper), findsNothing);

    await tester.tap(find.text('Random gradient wallpaper'));
    await tester.pumpAndSettle();
    expect(readTheme(), original);
  });

  for (final brightness in Brightness.values) {
    testWidgets('preview uses the selected palette and mode ($brightness)', (
      tester,
    ) async {
      const colors = [0xFF6BD5D9, 0xFFC6ADE4, 0xFFA9DAC6];
      final seed = readTheme().auroraSeed;
      for (var count = 1; count <= 3; count++) {
        final palette = colors.take(count).toList();
        container.read(themeSettingProvider.notifier).update(
          (state) => state.copyWith(auroraColors: palette),
        );
        await pumpSettings(tester, brightness: brightness);
        final preview = tester.widget<SeededAuroraWallpaper>(
          find.byType(SeededAuroraWallpaper),
        );
        expect(preview.brightness, brightness);
        expect(preview.colors.map((color) => color.toARGB32()), palette);
        expect(preview.seed, seed);
        expect(tester.getSize(find.byType(SeededAuroraWallpaper)).height,
            lessThan(160));
        expect(readTheme().auroraSeed, seed);
      }
    });
  }

  testWidgets('only a requested new wallpaper changes its seed', (
    tester,
  ) async {
    await pumpSettings(tester);
    final original = readTheme();
    await tester.pumpAndSettle();
    expect(readTheme().auroraSeed, original.auroraSeed);

    await tester.tap(find.text('New wallpaper'));
    await tester.pumpAndSettle();
    expect(readTheme().auroraSeed, isNot(original.auroraSeed));
    expect(readTheme().auroraColors, original.auroraColors);
    final seed = readTheme().auroraSeed;
    await tester.pumpAndSettle();
    expect(readTheme().auroraSeed, seed);
    expect(
      tester
          .widget<SeededAuroraWallpaper>(find.byType(SeededAuroraWallpaper))
          .seed,
      seed,
    );
  });

  testWidgets('adds a third color and hides the add action', (tester) async {
    await pumpSettings(tester);
    final seed = readTheme().auroraSeed;
    await tester.tap(find.byTooltip('Add'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(readTheme().auroraColors, hasLength(3));
    expect(readTheme().auroraSeed, seed);
    expect(find.byTooltip('Add'), findsNothing);
  });

  testWidgets('removes a color but cannot remove the last one', (tester) async {
    await pumpSettings(tester);
    final seed = readTheme().auroraSeed;
    await tester.tap(find.byTooltip('Edit wallpaper color 1'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();
    expect(readTheme().auroraColors, [0xFFC4BDD3]);
    expect(readTheme().auroraSeed, seed);

    await tester.tap(find.byTooltip('Edit wallpaper color 1'));
    await tester.pumpAndSettle();
    expect(find.text('Delete'), findsNothing);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
  });

  testWidgets('palette previews edits and cancel keeps the saved settings', (
    tester,
  ) async {
    await pumpSettings(tester);
    final original = readTheme();
    await tester.tap(find.byTooltip('Edit wallpaper color 1'));
    await tester.pumpAndSettle();
    final slider = tester.widget<Slider>(find.byType(Slider).first);
    slider.onChanged!(120);
    await tester.pump();
    final previews = tester.widgetList<SeededAuroraWallpaper>(
      find.byType(SeededAuroraWallpaper),
    );
    expect(
      previews.last.colors.first.toARGB32(),
      isNot(original.auroraColors.first),
    );
    expect(previews.last.seed, original.auroraSeed);
    expect(readTheme(), original);

    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(readTheme(), original);
  });
}
