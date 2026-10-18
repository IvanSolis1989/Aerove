import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/about.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    globalState.packageInfo = PackageInfo(appName: 'Aerove',
      packageName: 'com.follow.clash', version: 'test', buildNumber: '1');
    final font = FontLoader('AuroraColorsFont')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  test('neutral text remapping keeps semantic colors and font metrics', () {
    final old = seededColorScheme(seedColor: const Color(0xFF007AFF), brightness: Brightness.light);
    final next = auroraColorScheme(Brightness.light);
    final source = ThemeData(colorScheme: old).withAppTypography;
    final styled = source.copyWith(textTheme: source.textTheme.copyWith(
      bodyLarge: source.textTheme.bodyLarge!.copyWith(color: old.onSurface.withValues(alpha: 0.38)),
      titleMedium: source.textTheme.titleMedium!.copyWith(color: old.error),
    ));
    final result = styled.withNeutralTextColors(next);
    expect(result.textTheme.bodyLarge!.color!.a, closeTo(0.38, 1 / 255));
    expect(result.textTheme.bodyLarge!.color!.r, next.onSurface.r);
    expect(result.textTheme.titleMedium!.color, old.error);
    expect(result.textTheme.bodyLarge!.fontSize, styled.textTheme.bodyLarge!.fontSize);
    expect(result.textTheme.bodyLarge!.height, styled.textTheme.bodyLarge!.height);
  });

  for (final seed in [0xFF007AFF, 0xFF28A745, 0xFFE91E63]) {
    for (final brightness in Brightness.values) {
      testWidgets('production text colors $seed $brightness', (tester) async {
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        addTearDown(() => debugDefaultTargetPlatformOverride = null);
        tester.view.physicalSize = const Size(430, 900);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final container = ProviderContainer(overrides: [
          themeSettingProvider.overrideWithBuild((_, _) => ThemeProps(
            primaryColor: seed, fontFamily: 'AuroraColorsFont',
            auroraEnabled: false, liquidGlass: LiquidGlassMode.none)),
        ]);
        addTearDown(container.dispose);
        globalState.container = container;
        final capture = GlobalKey();
        final showUI = ValueNotifier(true);
        addTearDown(showUI.dispose);
        ThemeData? input;
        final observed = <String, Object?>{};
        final oldStyles = <String, TextStyle>{};
        await tester.pumpWidget(UncontrolledProviderScope(container: container,
          child: Consumer(builder: (context, ref, _) {
            final theme = watchAppTheme(ref, brightness);
            input = theme;
            return MaterialApp(theme: theme, darkTheme: theme,
              themeMode: brightness == Brightness.dark ? ThemeMode.dark : ThemeMode.light,
              themeAnimationStyle: AnimationStyle.noAnimation,
              localizationsDelegates: const [AppLocalizations.delegate,
                AppUiLocalizations.delegate, ...GlobalMaterialLocalizations.delegates],
              supportedLocales: AppLocalizations.delegate.supportedLocales,
              builder: (context, child) => wrapAppScopes(context,
                RepaintBoundary(key: capture,
                  child: AuroraAppSurface(motion: false, child: child!))),
              home: Builder(builder: (context) {
                final scheme = Theme.of(context).colorScheme;
                final styles = <String, TextStyle>{
                  for (final role in AppTextStyle.values) role.name: role.of(context),
                  'dashboardOrganization': AppTextStyle.subheadline.of(context, emphasized: true),
                  'dashboardService': AppTextStyle.footnote.of(context, emphasized: true),
                  'dashboardLabel': DashboardText.label(context),
                  'profilesName': AppTextStyle.headline.of(context),
                };
                observed.clear();
                observed['onSurface'] = scheme.onSurface.toARGB32();
                observed['slots'] = {for (final entry in styles.entries)
                  entry.key: {'color': entry.value.color?.toARGB32(),
                    'previousColor': oldStyles[entry.key]?.color?.toARGB32(),
                    'previousGateInCurrentScope': GlassForeground.colorsInk(context,
                      oldStyles[entry.key]?.color ?? scheme.onSurface),
                    'gate': GlassForeground.colorsInk(context,
                      entry.value.color ?? scheme.onSurface)}};
                if (!LiquidGlassScope.of(context)) {
                  oldStyles.addAll(styles);
                } else {
                  for (final entry in styles.entries) {
                    expect(GlassForeground.colorsInk(context,
                      entry.value.color ?? scheme.onSurface),
                      (entry.value.color ?? scheme.onSurface).a >= 0.70,
                      reason: '${entry.key} must use an active neutral role');
                    expect(entry.value.fontFamily, oldStyles[entry.key]!.fontFamily);
                    expect(entry.value.fontSize, oldStyles[entry.key]!.fontSize);
                    expect(entry.value.height, oldStyles[entry.key]!.height);
                  }
                }
                expect(GlassForeground.colorsInk(context, scheme.error), isFalse);
                return ValueListenableBuilder<bool>(valueListenable: showUI,
                  builder: (context, visible, child) => Opacity(opacity: visible ? 1 : 0, child: child),
                  child: Stack(children: [
                  const AboutView(),
                  Positioned(left: 24, right: 24, bottom: 24,
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min, children: [
                        GlassText('PT.TELKOMINDONESIA', style: styles['dashboardOrganization']),
                        GlassText('Google  GitHub  YouTube  ChatGPT', style: styles['dashboardService']),
                        GlassText('Profiles subscription', style: styles['profilesName']),
                      ])),
                ]));
              }),
            );
          }),
        ));
        await tester.pumpAndSettle();
        final original = input!;
        final none = Map<String, Object?>.from(observed);
        final noneContext = tester.element(find.byType(AboutView));
        expect(Theme.of(noneContext).colorScheme, original.colorScheme);
        expect(AppTextStyle.title2.of(noneContext).color, original.textTheme.headlineSmall!.color);
        container.read(themeSettingProvider.notifier).update((state) => state.copyWith(
          auroraEnabled: true, liquidGlass: LiquidGlassMode.standard));
        await tester.pumpAndSettle();
        final title = find.byWidgetPredicate((widget) => widget is GlassText && widget.data == displayAppName);
        expect(title, findsOneWidget);
        expect(tester.widget<GlassText>(title).style!.fontSize, 22);
        final actualContext = tester.element(title);
        expect(GlassForeground.colorsInk(actualContext,
          tester.widget<GlassText>(title).style!.color!), isTrue);
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          Directory(output).createSync(recursive: true);
          File('$output/aurora-text-colors-$seed-${brightness.name}.json').writeAsStringSync(jsonEncode({
            'oldOnSurface': original.colorScheme.onSurface.toARGB32(),
            'oldTitle': original.textTheme.headlineSmall!.color?.toARGB32(),
            'none': none, 'active': observed,
          }));
        }
        if (ui.ImageFilter.isShaderFilterSupported) {
          final targets = [title,
            find.text('PT.TELKOMINDONESIA'), find.text('Profiles subscription')];
          final rectangles = targets.map(tester.getRect).toList();
          final boundary = capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          final actual = (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              if (output != null) {
                final png = await image.toByteData(format: ui.ImageByteFormat.png);
                File('$output/aurora-text-colors-$seed-${brightness.name}.png')
                    .writeAsBytesSync(png!.buffer.asUint8List());
              }
              return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
                  .buffer.asUint8List().toList();
            } finally { image.dispose(); }
          }))!;
          showUI.value = false;
          await tester.pump();
          final under = (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
                  .buffer.asUint8List().toList();
            } finally { image.dispose(); }
          }))!;
          final pixels = <Map<String, Object?>>[];
          for (final rectangle in rectangles) {
            var colored = 0;
            var bestContrast = 0.0;
            for (var y = rectangle.top.ceil(); y < rectangle.bottom.floor(); y++) {
              for (var x = rectangle.left.ceil(); x < rectangle.right.floor(); x++) {
                final index = (y * 430 + x) * 4;
                final color = Color.fromARGB(255, actual[index], actual[index + 1], actual[index + 2]);
                final background = Color.fromARGB(255, under[index], under[index + 1], under[index + 2]);
                final a = color.computeLuminance(), b = background.computeLuminance();
                final contrast = (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05);
                if (HSVColor.fromColor(color).saturation > 0.10 && contrast >= 4.5) {
                  colored++;
                  bestContrast = math.max(bestContrast, contrast);
                }
              }
            }
            pixels.add({'rect': [rectangle.left, rectangle.top, rectangle.width, rectangle.height],
              'colored': colored, 'bestContrast': bestContrast});
            expect(colored, greaterThan(3), reason: 'Actual title/body at this location must be colored and readable');
            expect(bestContrast, greaterThanOrEqualTo(4.5));
          }
          if (output != null) File('$output/aurora-text-colors-$seed-${brightness.name}-pixels.json')
              .writeAsStringSync(jsonEncode(pixels));
          showUI.value = true;
          await tester.pump();
        }
        final icon = tester.widgetList<Image>(find.byType(Image)).firstWhere(
          (image) => image.image is AssetImage &&
            (image.image as AssetImage).assetName == 'assets/images/icon.png');
        expect(icon.color, isNull);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
