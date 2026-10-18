import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/feature.dart';
import 'package:fl_clash/common/system_fonts.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/font_family.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/aurora_settings.dart';
import 'package:fl_clash/views/theme_preview.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(1400, 2400);
  });

  tearDown(() => container.dispose());

  Future<void> pumpThemeView(
    WidgetTester tester, {
    Size size = const Size(1400, 2400),
    ThemeData? theme,
    double textScale = 1,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          theme: theme,
          homeBuilder: (child) => Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(textScale)),
              child: child,
            ),
          ),
          child: const ThemeView(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  ThemeProps readTheme() => container.read(themeSettingProvider);

  Future<void> revealThemeSetting(WidgetTester tester, Finder setting) async {
    final scrollables = find.descendant(
      of: find.byType(ThemeView),
      matching: find.byType(Scrollable),
    );
    final scrollable = scrollables.first;
    for (
      var attempt = 0;
      attempt < 50 && setting.evaluate().isEmpty;
      attempt++
    ) {
      await tester.drag(scrollable, const Offset(0, -300));
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(setting.evaluate(), isNotEmpty);
    await tester.ensureVisible(setting.first);
    await tester.pumpAndSettle();
  }

  group('theme mode', () {
    testWidgets('android exposes every mode before the wallpaper settings', (
      tester,
    ) async {
      await pumpThemeView(
        tester,
        size: const Size(390, 844),
        theme: ThemeData(platform: TargetPlatform.android),
        textScale: 1.5,
      );

      for (final (label, mode) in [
        ('Auto', ThemeMode.system),
        ('Light', ThemeMode.light),
        ('Dark', ThemeMode.dark),
      ]) {
        final choice = find.text(label).hitTestable();
        expect(choice, findsOneWidget);
        await tester.tap(choice);
        await tester.pumpAndSettle();
        expect(readTheme().themeMode, mode);
      }
      expect(
        tester.getTopLeft(find.text('Auto')).dy,
        lessThan(tester.getTopLeft(find.byType(ThemeLivePreview)).dy),
      );
    });

    testWidgets('defaults to following the system', (tester) async {
      await pumpThemeView(tester);

      expect(readTheme().themeMode, ThemeMode.system);
    });

    testWidgets('switches to light and back to dark', (tester) async {
      await pumpThemeView(tester);

      await tester.tap(find.text('Light'));
      await tester.pumpAndSettle();
      expect(readTheme().themeMode, ThemeMode.light);

      await tester.tap(find.text('Dark'));
      await tester.pumpAndSettle();
      expect(readTheme().themeMode, ThemeMode.dark);

      await tester.tap(find.text('Auto'));
      await tester.pumpAndSettle();
      expect(readTheme().themeMode, ThemeMode.system);
    });
  });

  group('primary color', () {
    setUp(() {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(auroraEnabled: false));
    });

    Finder swatch(int color) => find.byWidgetPredicate(
      (widget) =>
          widget is Semantics && widget.properties.label == Color(color).hex,
    );

    testWidgets('starts on blue among the system colors', (tester) async {
      container
          .read(themeSettingProvider.notifier)
          .update((_) => defaultThemeProps.copyWith(auroraEnabled: false));
      await pumpThemeView(tester);

      expect(readTheme().primaryColor, 0xFF007AFF);
      for (final color in defaultPrimaryColors) {
        expect(swatch(color), findsOneWidget);
      }
      expect(find.byTooltip('Reset'), findsNothing);
    });

    testWidgets('picks a color and the style of its scheme', (tester) async {
      await pumpThemeView(tester);

      await tester.tap(swatch(0xFFAF52DE));
      await tester.pumpAndSettle();
      expect(readTheme().primaryColor, 0xFFAF52DE);

      await tester.tap(
        find.byWidgetPredicate(
          (widget) =>
              widget is Semantics && widget.properties.label == 'Follow system',
        ),
      );
      await tester.pumpAndSettle();
      expect(readTheme().primaryColor, isNull);

      await tester.tap(find.text('Color style'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Soft').last);
      await tester.pumpAndSettle();
      expect(readTheme().colorStyle, ColorStyle.soft);
    });

    testWidgets('color modes are exclusive and restore their saved settings', (
      tester,
    ) async {
      container
          .read(themeSettingProvider.notifier)
          .update(
            (state) => state.copyWith(
              primaryColor: 0xFFAF52DE,
              colorStyle: ColorStyle.soft,
              auroraColors: const [0xFF88BBCC, 0xFFAABBDD, 0xFFDDEEFF],
              auroraSeed: 4242,
            ),
          );
      final saved = readTheme();
      await pumpThemeView(
        tester,
        theme: ThemeData(platform: TargetPlatform.android),
      );
      await tester.ensureVisible(find.text('Random gradient wallpaper'));
      await tester.tap(find.text('Random gradient wallpaper'));
      await tester.pumpAndSettle();
      expect(find.byType(AuroraSettings), findsOneWidget);
      expect(swatch(0xFFFF9500), findsNothing);
      expect(find.text('Color style'), findsNothing);
      expect(find.byTooltip('Reset'), findsNothing);
      expect(readTheme().primaryColor, saved.primaryColor);
      expect(readTheme().primaryColors, saved.primaryColors);
      expect(readTheme().colorStyle, saved.colorStyle);
      expect(readTheme().auroraSeed, saved.auroraSeed);
      expect(readTheme().auroraColors, saved.auroraColors);
      await tester.ensureVisible(find.text('Solid theme color'));
      await tester.tap(find.text('Solid theme color'));
      await tester.pumpAndSettle();
      expect(readTheme(), saved.copyWith(liquidGlass: LiquidGlassMode.ultra));
      expect(find.byType(AuroraSettings), findsNothing);
      expect(find.text('Color style'), findsOneWidget);
      await tester.ensureVisible(swatch(0xFFFF9500));
      await tester.tap(swatch(0xFFFF9500));
      await tester.pumpAndSettle();
      expect(readTheme().primaryColor, 0xFFFF9500);
      expect(readTheme().auroraSeed, saved.auroraSeed);
    });

    testWidgets('deletes a color from its context menu', (tester) async {
      await pumpThemeView(tester);
      await tester.tap(swatch(0xFFFF9500));
      await tester.pumpAndSettle();

      await tester.longPress(swatch(0xFFFF9500));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();

      expect(readTheme().primaryColors, isNot(contains(0xFFFF9500)));
      expect(readTheme().primaryColor, defaultPrimaryColor);
      expect(swatch(0xFFFF9500), findsNothing);
    });

    Future<void> editColor(WidgetTester tester, int color, String hex) async {
      await tester.longPress(swatch(color));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Edit'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.descendant(
          of: find.byType(HexColorField),
          matching: find.byType(TextField),
        ),
        hex,
      );
      await tester.pump();
      await tester.tap(find.byTooltip('Confirm'));
      await tester.pumpAndSettle();
    }

    testWidgets('edits a color in place from its context menu', (tester) async {
      await pumpThemeView(tester);
      await tester.tap(swatch(0xFFFF9500));
      await tester.pumpAndSettle();
      final index = readTheme().primaryColors.indexOf(0xFFFF9500);

      await editColor(tester, 0xFFFF9500, '123456');

      expect(readTheme().primaryColors, isNot(contains(0xFFFF9500)));
      expect(readTheme().primaryColors[index], 0xFF123456);
      expect(readTheme().primaryColor, 0xFF123456);
      expect(swatch(0xFF123456), findsOneWidget);
    });

    testWidgets('merges a color edited into one already listed', (
      tester,
    ) async {
      await pumpThemeView(tester);
      await tester.tap(swatch(0xFF007AFF));
      await tester.pumpAndSettle();
      final count = readTheme().primaryColors.length;

      await editColor(tester, 0xFFAF52DE, '007AFF');

      expect(readTheme().primaryColors, hasLength(count - 1));
      expect(readTheme().primaryColors, isNot(contains(0xFFAF52DE)));
      expect(readTheme().primaryColor, 0xFF007AFF);
      expect(swatch(0xFF007AFF), findsOneWidget);
    });

    testWidgets('adds a custom color once and selects it', (tester) async {
      await pumpThemeView(tester);
      final count = readTheme().primaryColors.length;

      for (var i = 0; i < 2; i++) {
        await tester.tap(find.byTooltip('Add'));
        await tester.pumpAndSettle();
        await tester.enterText(
          find.descendant(
            of: find.byType(HexColorField),
            matching: find.byType(TextField),
          ),
          '123456',
        );
        await tester.pump();
        await tester.tap(find.byTooltip('Confirm'));
        await tester.pumpAndSettle();
      }

      expect(readTheme().primaryColors, hasLength(count + 1));
      expect(readTheme().primaryColors.last, 0xFF123456);
      expect(readTheme().primaryColor, 0xFF123456);
      expect(swatch(0xFF123456), findsOneWidget);
    });

    testWidgets('keeps the custom color palette clear of the edge back '
        'swipe', (tester) async {
      const size = Size(400, 860);
      container.read(viewSizeProvider.notifier).value = size;
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      tester.view.systemGestureInsets = const FakeViewPadding(
        left: 30,
        right: 30,
      );
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const TestApp(child: ThemeView()),
        ),
      );
      await tester.pumpAndSettle();

      final add = find
          .descendant(of: find.byTooltip('Add'), matching: find.byType(InkWell))
          .hitTestable();
      await tester.scrollUntilVisible(
        add,
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(add);
      await tester.pumpAndSettle();
      expect(find.text('Custom color'), findsOneWidget);
      expect(find.byType(HexColorField), findsOneWidget);

      await tester.scrollUntilVisible(
        find.byType(Palette),
        200,
        scrollable: find
            .descendant(
              of: find
                  .ancestor(
                    of: find.byType(HexColorField),
                    matching: find.byType(ListView),
                  )
                  .first,
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.pumpAndSettle();
      final pad = tester.getRect(
        find.descendant(
          of: find.byType(Palette),
          matching: find.byType(AspectRatio),
        ),
      );
      expect(pad.left, 30);
      expect(pad.right, size.width - 30);
    });
  });

  group('sidebar blur', () {
    testWidgets('stays hidden while the feature is off', (tester) async {
      await pumpThemeView(tester);

      expect(find.text('Sidebar blur'), findsNothing);
    });

    testWidgets('shows a working toggle only on supported platforms', (
      tester,
    ) async {
      feature = const Feature(sidebarBlur: true);
      addTearDown(() => feature = const Feature());
      await pumpThemeView(tester);
      final toggle = find.text('Sidebar blur');

      expect(readTheme().sidebarBlur, isTrue);
      if (!Platform.isMacOS && !Platform.isWindows) {
        expect(toggle, findsNothing);
        return;
      }

      expect(toggle, findsOneWidget);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(readTheme().sidebarBlur, isFalse);
    });
  });

  group('liquid glass', () {
    setUp(() {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(auroraEnabled: false));
    });

    testWidgets('activating gradient starts at ultra from either saved mode', (
      tester,
    ) async {
      for (final mode in [LiquidGlassMode.none, LiquidGlassMode.standard]) {
        container
            .read(themeSettingProvider.notifier)
            .update(
              (state) =>
                  state.copyWith(auroraEnabled: false, liquidGlass: mode),
            );
        await pumpThemeView(
          tester,
          theme: ThemeData(platform: TargetPlatform.android),
        );
        await revealThemeSetting(
          tester,
          find.text('Random gradient wallpaper'),
        );
        await tester.tap(find.text('Random gradient wallpaper'));
        await tester.pumpAndSettle();
        expect(readTheme().auroraEnabled, isTrue);
        expect(readTheme().liquidGlass, LiquidGlassMode.ultra);
      }
    });

    testWidgets(
      'gradient disables only none and saves the selected glass tier',
      (tester) async {
        await pumpThemeView(
          tester,
          theme: ThemeData(platform: TargetPlatform.android),
        );
        final group = find.byType(PreviewChoiceGroup<LiquidGlassMode>);
        final seed = readTheme().auroraSeed;
        await tester.ensureVisible(find.text('Random gradient wallpaper'));
        await tester.tap(find.text('Random gradient wallpaper'));
        await tester.pumpAndSettle();
        await tester.ensureVisible(group);
        expect(
          tester.widget<PreviewChoiceGroup<LiquidGlassMode>>(group).value,
          LiquidGlassMode.ultra,
        );
        final choices = tester
            .widget<PreviewChoiceGroup<LiquidGlassMode>>(group)
            .choices;
        final cards = tester
            .widgetList<CommonCard>(
              find.descendant(of: group, matching: find.byType(CommonCard)),
            )
            .toList();
        expect(cards, hasLength(3));
        expect(cards.first.onPressed, isNull);
        expect(cards[1].onPressed, isNotNull);
        expect(cards.last.onPressed, isNotNull);
        expect(
          cards.first.shape,
          AppShape.of(AppRadius.vertical(top: AppCorner.xl)),
        );
        expect(cards[1].shape, AppShape.none);
        expect(
          cards.last.shape,
          AppShape.of(AppRadius.vertical(bottom: AppCorner.xl)),
        );
        expect(
          choices
              .singleWhere((choice) => choice.value == LiquidGlassMode.none)
              .enabled,
          isFalse,
        );
        expect(
          choices
              .where((choice) => choice.value != LiquidGlassMode.none)
              .every((choice) => choice.enabled),
          isTrue,
        );
        final none = find.descendant(of: group, matching: find.text('None'));
        await tester.tap(none, warnIfMissed: false);
        await tester.pumpAndSettle();
        expect(readTheme().liquidGlass, LiquidGlassMode.ultra);
        final ultra = find.descendant(of: group, matching: find.text('Ultra'));
        await tester.tap(ultra);
        await tester.pumpAndSettle();
        expect(readTheme().liquidGlass, LiquidGlassMode.ultra);
        final standard = find.descendant(
          of: group,
          matching: find.text('Standard'),
        );
        await tester.tap(standard);
        await tester.pumpAndSettle();
        expect(readTheme().liquidGlass, LiquidGlassMode.standard);
        final restored = ThemeProps.fromJson(
          jsonDecode(jsonEncode(readTheme())),
        );
        container.read(themeSettingProvider.notifier).update((_) => restored);
        await pumpThemeView(
          tester,
          theme: ThemeData(platform: TargetPlatform.android),
        );
        await revealThemeSetting(tester, group);
        expect(readTheme().liquidGlass, LiquidGlassMode.standard);
        expect(
          tester.widget<PreviewChoiceGroup<LiquidGlassMode>>(group).value,
          LiquidGlassMode.standard,
        );
        await revealThemeSetting(
          tester,
          find.text('Random gradient wallpaper'),
        );
        await tester.tap(find.text('Random gradient wallpaper'));
        await tester.pumpAndSettle();
        expect(readTheme().liquidGlass, LiquidGlassMode.standard);
        await revealThemeSetting(tester, group);
        await tester.tap(none, warnIfMissed: false);
        await tester.pumpAndSettle();
        expect(readTheme().liquidGlass, LiquidGlassMode.standard);
        expect(readTheme().auroraSeed, seed);
        await tester.ensureVisible(find.text('Solid theme color'));
        await tester.tap(find.text('Solid theme color'));
        await tester.pumpAndSettle();
        await tester.ensureVisible(group);
        expect(
          tester.widget<PreviewChoiceGroup<LiquidGlassMode>>(group).value,
          LiquidGlassMode.standard,
        );
        expect(
          tester
              .widget<PreviewChoiceGroup<LiquidGlassMode>>(group)
              .choices
              .singleWhere((choice) => choice.value == LiquidGlassMode.none)
              .enabled,
          isTrue,
        );
        await tester.tap(none);
        await tester.pumpAndSettle();
        expect(readTheme().liquidGlass, LiquidGlassMode.none);
      },
    );

    testWidgets('is off until a tier is chosen, and switches back', (
      tester,
    ) async {
      container.read(themeSettingProvider.notifier).update(
        (state) => state.copyWith(
          auroraEnabled: false,
          liquidGlass: LiquidGlassMode.none,
        ),
      );
      await pumpThemeView(tester);
      final group = find.ancestor(
        of: find.text('Liquid Glass').first,
        matching: find.byType(PreviewChoiceGroup<LiquidGlassMode>),
      );
      Finder choice(String label) =>
          find.descendant(of: group, matching: find.text(label)).last;

      expect(readTheme().liquidGlass, LiquidGlassMode.none);

      await tester.tap(choice('Standard'));
      await tester.pumpAndSettle();
      expect(readTheme().liquidGlass, LiquidGlassMode.standard);

      await tester.tap(choice('None'));
      await tester.pumpAndSettle();
      expect(readTheme().liquidGlass, LiquidGlassMode.none);
    });
  });

  group('pure black', () {
    testWidgets('switches both ways', (tester) async {
      await pumpThemeView(tester);

      expect(readTheme().pureBlack, isFalse);

      await tester.tap(find.text('Pure black'));
      await tester.pumpAndSettle();
      expect(readTheme().pureBlack, isTrue);

      await tester.tap(find.text('Standard').first);
      await tester.pumpAndSettle();
      expect(readTheme().pureBlack, isFalse);
    });
  });

  group('home navigation', () {
    AppSettingProps readSetting() => container.read(appSettingProvider);

    testWidgets('picks a floating or docked bottom bar', (tester) async {
      await pumpThemeView(tester);

      expect(readSetting().floatingNavigationBar, isTrue);

      await tester.tap(find.text('Docked'));
      await tester.pumpAndSettle();
      expect(readSetting().floatingNavigationBar, isFalse);

      await tester.tap(find.text('Floating'));
      await tester.pumpAndSettle();
      expect(readSetting().floatingNavigationBar, isTrue);
    });

    testWidgets('picks a sliding or fading tab switch', (tester) async {
      await pumpThemeView(tester);

      expect(readSetting().tabAnimation, TabAnimation.slide);

      await tester.tap(find.text('Fade'));
      await tester.pumpAndSettle();
      expect(readSetting().tabAnimation, TabAnimation.fade);

      await tester.tap(find.text('Slide'));
      await tester.pumpAndSettle();
      expect(readSetting().tabAnimation, TabAnimation.slide);
    });

    testWidgets('the preview switches to the destination tapped', (
      tester,
    ) async {
      await pumpThemeView(tester);
      Finder inPreview(Type type) => find.descendant(
        of: find.byType(ThemeLivePreview),
        matching: find.byType(type),
      );
      int selected() =>
          tester.widget<MiniScreen>(inPreview(MiniScreen)).selected;

      for (final destination in [2, 1]) {
        await tester.tap(inPreview(GestureDetector).at(destination));
        await tester.pumpAndSettle();
        expect(selected(), destination);
      }
    });
  });

  group('font family', () {
    setUp(() {
      final reader = systemFontFamiliesReader;
      addTearDown(() => systemFontFamiliesReader = reader);
      systemFontFamiliesReader = () async => ['Alpha Sans', 'Beta Serif'];
    });

    Finder inPage(String text) => find.descendant(
      of: find.byType(FontFamilyView),
      matching: find.text(text),
    );

    final fontsIllustration = find.descendant(
      of: find.byType(FontFamilyView),
      matching: find.byKey(const ValueKey(NullStatusIllustration.fonts)),
    );

    String? previewFamilyOf(WidgetTester tester, String text) =>
        DefaultTextStyle.of(tester.element(inPage(text))).style.fontFamily;

    Future<void> pumpSettled(WidgetTester tester) async {
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
    }

    Future<void> openFonts(WidgetTester tester) async {
      await tester.tap(find.text('Aa').first);
      await tester.pumpAndSettle();
      await pumpSettled(tester);
    }

    testWidgets('picks an installed font on its page and back to default', (
      tester,
    ) async {
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Aa'));

      expect(readTheme().fontFamily, isNull);
      expect(find.text('Default'), findsOneWidget);

      await openFonts(tester);
      expect(
        SheetProvider.of(tester.element(find.byType(FontFamilyView)))?.type,
        SheetType.sideSheet,
      );
      expect(inPage('Installed fonts'), findsOneWidget);
      expect(previewFamilyOf(tester, 'Beta Serif'), 'Beta Serif');

      await tester.tap(inPage('Beta Serif'));
      await tester.pumpAndSettle();
      expect(readTheme().fontFamily, 'Beta Serif');
      expect(find.byType(FontFamilyView), findsOneWidget);

      await tester.tap(inPage('Default'));
      await tester.pumpAndSettle();
      expect(readTheme().fontFamily, isNull);
    });

    testWidgets('lists the fonts as the sheet opens and gives the names '
        'their fonts once it has stopped', (tester) async {
      var reads = 0;
      systemFontFamiliesReader = () async {
        reads++;
        return ['Arrival Sans'];
      };
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Aa'));

      await tester.tap(find.text('Aa').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pump(const Duration(milliseconds: 16));
      expect(reads, 1);
      expect(fontsIllustration, findsNothing);
      expect(inPage('Arrival Sans'), findsOneWidget);
      expect(
        isRouteMoving(tester.element(find.byType(FontFamilyView))),
        isTrue,
      );
      expect(previewFamilyOf(tester, 'Arrival Sans'), isNot('Arrival Sans'));

      await pumpSettled(tester);
      expect(previewFamilyOf(tester, 'Arrival Sans'), 'Arrival Sans');
    });

    testWidgets('shows the loading illustration while the fonts take long', (
      tester,
    ) async {
      final families = Completer<List<String>>();
      systemFontFamiliesReader = () => families.future;
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Aa'));

      await tester.tap(find.text('Aa').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(fontsIllustration, findsNothing);

      await tester.pump(const Duration(milliseconds: 300));
      expect(fontsIllustration, findsOneWidget);
      expect(inPage('Default'), findsNothing);

      families.complete(['Alpha Sans']);
      await pumpSettled(tester);
      expect(inPage('Alpha Sans'), findsOneWidget);
      expect(fontsIllustration, findsNothing);
    });

    testWidgets('previews the chosen font on its card', (tester) async {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(fontFamily: 'Beta Serif'));
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Aa'));

      expect(
        tester.widget<Text>(find.text('Beta Serif')).style?.fontFamily,
        'Beta Serif',
      );
      expect(
        tester.widget<Text>(find.text('Aa').first).style?.fontFamily,
        'Beta Serif',
      );
    });

    testWidgets('gives each name its own font one per frame', (tester) async {
      const families = ['Preview One', 'Preview Two', 'Preview Three'];
      var loaded = false;
      systemFontFamiliesReader = () async {
        loaded = true;
        return families;
      };
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Aa'));
      await tester.tap(find.text('Aa').first);
      for (var i = 0; i < 20 && !loaded; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
      await tester.pump(const Duration(seconds: 1));

      int shown() => families
          .where(
            (family) =>
                inPage(family).evaluate().isNotEmpty &&
                previewFamilyOf(tester, family) == family,
          )
          .length;
      final counts = [shown()];
      while (counts.last < families.length && counts.length < 10) {
        await tester.pump();
        counts.add(shown());
      }

      expect(counts.first, lessThan(families.length));
      expect(counts.last, families.length);
      for (var i = 1; i < counts.length; i++) {
        expect(counts[i] - counts[i - 1], lessThanOrEqualTo(1));
      }
    });

    testWidgets('still offers the default when no font can be listed', (
      tester,
    ) async {
      systemFontFamiliesReader = () async => throw const OSError('fc', 1);
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Aa'));

      await openFonts(tester);

      expect(inPage('Default'), findsOneWidget);
      expect(inPage('Installed fonts'), findsNothing);
      expect(fontsIllustration, findsNothing);
    });
  });

  group('text scale', () {
    testWidgets('follows the system until custom is picked', (tester) async {
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Custom'));

      expect(readTheme().textScale.enable, isFalse);
      expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNull);

      await tester.tap(find.text('Custom'));
      await tester.pumpAndSettle();

      expect(readTheme().textScale.enable, isTrue);
      expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNotNull);

      await tester.tap(find.text('Follow system'));
      await tester.pumpAndSettle();

      expect(readTheme().textScale.enable, isFalse);
    });

    testWidgets('writes the scale only when the drag ends', (tester) async {
      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Custom'));
      await tester.tap(find.text('Custom'));
      await tester.pumpAndSettle();
      final before = readTheme().textScale.scale;

      final slider = find.byType(Slider);
      final gesture = await tester.startGesture(tester.getCenter(slider));
      await gesture.moveBy(const Offset(120, 0));
      await tester.pump();
      expect(readTheme().textScale.scale, before);

      await gesture.up();
      await tester.pumpAndSettle();
      expect(readTheme().textScale.scale, isNot(before));
    });

    testWidgets('resets a custom scale to 100%', (tester) async {
      container
          .read(themeSettingProvider.notifier)
          .update(
            (state) => state.copyWith.textScale(enable: true, scale: 1.2),
          );

      await pumpThemeView(tester);
      await revealThemeSetting(tester, find.text('Custom'));

      expect(find.text('120%'), findsOneWidget);

      await tester.tap(find.byTooltip('Reset').last);
      await tester.pumpAndSettle();

      expect(readTheme().textScale.scale, 1);
      expect(find.text('100%'), findsOneWidget);
    });
  });
}
