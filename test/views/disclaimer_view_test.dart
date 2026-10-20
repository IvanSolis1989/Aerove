import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/disclaimer.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

Future<({ProviderContainer container, GlobalKey capture})> _mount(
  WidgetTester tester,
  Brightness brightness,
  LiquidGlassMode mode,
  Color backdrop, {
  bool contrast = false,
}) async {
  final container = ProviderContainer(
    overrides: [
      themeSettingProvider.overrideWithBuild(
        (_, _) => ThemeProps(auroraEnabled: false, liquidGlass: mode),
      ),
    ],
  );
  globalState.container = container;
  container.read(viewSizeProvider.notifier).value = const Size(430, 900);
  final capture = GlobalKey();
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        navigatorKey: globalState.navigatorKey,
        locale: const Locale('en'),
        theme:
            ThemeData(
              brightness: brightness,
              platform: TargetPlatform.android,
              fontFamily: 'DisclaimerProbe',
            ).withAppShapes.withAppSurfaces.copyWith(
              scaffoldBackgroundColor: backdrop,
            ),
        localizationsDelegates: const [
          AppLocalizations.delegate,
          AppUiLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.delegate.supportedLocales,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(highContrast: contrast),
          child: wrapAppScopes(
            context,
            RepaintBoundary(
              key: capture,
              child: AuroraAppSurface(motion: false, child: child!),
            ),
          ),
        ),
        home: const Scaffold(body: SizedBox.shrink()),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return (container: container, capture: capture);
}

Finder _paneAbove(Finder child) =>
    find.ancestor(of: child, matching: find.byType(LiquidGlass));
Finder get _confirm => find.widgetWithText(TextButton, 'Confirm');
Finder get _field => find.byType(TextField);

Future<List<Color>> _shot(
  WidgetTester tester,
  GlobalKey key,
  String name, {
  List<Offset> probes = const [],
}) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(key));
  if (boundary.debugNeedsPaint) {
    tester.binding.scheduleForcedFrame();
    await tester.pump();
  }
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final rgba = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final colors = [
        for (final point in probes)
          Color.fromARGB(
            rgba.getUint8(
              (point.dy.floor() * image.width + point.dx.floor()) * 4 + 3,
            ),
            rgba.getUint8(
              (point.dy.floor() * image.width + point.dx.floor()) * 4,
            ),
            rgba.getUint8(
              (point.dy.floor() * image.width + point.dx.floor()) * 4 + 1,
            ),
            rgba.getUint8(
              (point.dy.floor() * image.width + point.dx.floor()) * 4 + 2,
            ),
          ),
      ];
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final backend =
            Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true'
            ? 'impeller'
            : 'default';
        final prefix = '$output/disclaimer-$backend-$name';
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('$prefix.png').writeAsBytesSync(png.buffer.asUint8List());
        File('$prefix.json').writeAsStringSync(
          jsonEncode({
            'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
            'shaderReady': LiquidGlass.debugShaderReady,
            'hostCount': find.byType(LiquidGlassHost).evaluate().length,
            'probes': [
              for (var i = 0; i < probes.length; i++)
                {'point': probes[i].toString(), 'color': colors[i].toARGB32()},
            ],
          }),
        );
      }
      return colors;
    } finally {
      image.dispose();
    }
  }))!;
}

void main() {
  setUpAll(() async {
    final font = FontLoader('DisclaimerProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
    }
  });

  for (final brightness in Brightness.values) {
    testWidgets('first consent awaits a separate scrollable glass introduction $brightness', (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final host = await _mount(tester, brightness, LiquidGlassMode.ultra, Colors.blue);
      try {
        final settings = host.container.read(themeSettingProvider);
        var completed = false;
        final consent = requestDisclaimerConsent(showGlassIntroduction: true).then((value) {
          completed = true;
          return value;
        });
        await tester.pumpAndSettle();
        final strings = tester.element(find.byType(DisclaimerView)).appLocalizations;
        expect(find.text(strings.liquidGlassUltraIntro), findsNothing);
        await tester.tap(find.widgetWithText(FilledButton, strings.agree));
        await tester.pumpAndSettle();
        expect(find.text(strings.disclaimerRestateTitle), findsOneWidget);
        expect(find.text(strings.liquidGlassUltraIntro), findsNothing);
        await tester.enterText(_field, 'wrong');
        await tester.pump();
        expect(tester.widget<TextButton>(_confirm).onPressed, isNull);
        expect(completed, isFalse);
        await tester.enterText(_field, strings.disclaimerNoServiceStatement);
        await tester.pump();
        await tester.ensureVisible(_confirm);
        await tester.tap(_confirm);
        await tester.pumpAndSettle();
        expect(find.byType(DisclaimerView), findsNothing);
        expect(find.text(strings.liquidGlass), findsOneWidget);
        expect(find.text(strings.ultra), findsOneWidget);
        expect(find.text(strings.standard), findsOneWidget);
        expect(completed, isFalse);
        for (final paragraph in [
          strings.liquidGlassUltraIntro,
          strings.liquidGlassStandardIntro,
          strings.liquidGlassLowPowerTip,
          strings.liquidGlassSettingsTip,
          strings.liquidGlassIntroConfirm,
        ]) {
          expect(find.text(paragraph), findsOneWidget);
        }
        final scroll = find.descendant(of: find.byType(CommonDialog), matching: find.byType(Scrollable));
        expect(scroll, findsOneWidget);
        final position = tester.state<ScrollableState>(scroll).position;
        expect(position.maxScrollExtent, greaterThan(0));
        await tester.scrollUntilVisible(find.text(strings.liquidGlassSettingsTip), 180, scrollable: scroll);
        await tester.pumpAndSettle();
        expect(tester.getRect(find.text(strings.liquidGlassSettingsTip)).overlaps(tester.getRect(scroll)), isTrue);
        expect(host.container.read(themeSettingProvider), settings);
        await _shot(tester, host.capture, '${brightness.name}-intro-small-scrolled');
        if (brightness == Brightness.light) {
          await tester.tap(find.widgetWithText(TextButton, strings.liquidGlassIntroConfirm));
        } else {
          Navigator.of(tester.element(find.byType(CommonDialog))).pop();
        }
        await tester.pumpAndSettle();
        expect(await consent, isTrue);
        expect(completed, isTrue);
        expect(find.byType(CommonDialog), findsNothing);
        expect(host.container.read(themeSettingProvider), settings);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump(Duration.zero);
        host.container.dispose();
      }
    });
  }

  testWidgets('decline back and the default consent API never introduce glass', (tester) async {
    final host = await _mount(tester, Brightness.light, LiquidGlassMode.standard, Colors.blue);
    try {
      final settings = host.container.read(themeSettingProvider);
      final declined = requestDisclaimerConsent(showGlassIntroduction: true);
      await tester.pumpAndSettle();
      final strings = tester.element(find.byType(DisclaimerView)).appLocalizations;
      await tester.tap(find.widgetWithText(OutlinedButton, strings.exit));
      await tester.pumpAndSettle();
      expect(await declined, isFalse);
      expect(find.text(strings.liquidGlassUltraIntro), findsNothing);
      final backed = requestDisclaimerConsent(showGlassIntroduction: true);
      await tester.pumpAndSettle();
      Navigator.of(tester.element(find.byType(DisclaimerView))).pop();
      await tester.pumpAndSettle();
      expect(await backed, isFalse);
      expect(find.text(strings.liquidGlassUltraIntro), findsNothing);
      final accepted = requestDisclaimerConsent();
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, strings.agree));
      await tester.pumpAndSettle();
      await tester.enterText(_field, strings.disclaimerNoServiceStatement);
      await tester.pump();
      await tester.tap(_confirm);
      await tester.pumpAndSettle();
      expect(await accepted, isTrue);
      expect(find.text(strings.liquidGlassUltraIntro), findsNothing);
      expect(host.container.read(themeSettingProvider), settings);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(Duration.zero);
      host.container.dispose();
    }
  });

  for (final brightness in Brightness.values) {
    for (final mode in LiquidGlassMode.values) {
      testWidgets(
        'first consent restatement preserves validation and material $brightness $mode',
        (tester) async {
          tester.view.physicalSize = const Size(430, 900);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final samples = <List<Color>>[];
          final glass = mode != LiquidGlassMode.none;
          for (final backdrop in [
            const Color(0xFF2040C0),
            const Color(0xFFC04020),
          ]) {
            final host = await _mount(tester, brightness, mode, backdrop);
            final prefix =
                '${brightness.name}-${mode.name}-${backdrop.toARGB32()}';
            try {
              final consent = requestDisclaimerConsent();
              await tester.pumpAndSettle();
              final strings = tester
                  .element(find.byType(DisclaimerView))
                  .appLocalizations;
              final statement = strings.disclaimerNoServiceStatement;
              expect(find.text(strings.disclaimerDesc), findsOneWidget);
              final bodyScroll = find
                  .descendant(
                    of: find.byType(DisclaimerView),
                    matching: find.byType(Scrollable),
                  )
                  .first;
              final lastTerm = find.text(strings.disclaimerAcceptTitle);
              await tester.scrollUntilVisible(
                lastTerm,
                400,
                scrollable: bodyScroll,
              );
              await tester.pumpAndSettle();
              expect(lastTerm, findsOneWidget);
              expect(
                tester.getRect(lastTerm).overlaps(tester.getRect(bodyScroll)),
                isTrue,
              );
              tester.state<ScrollableState>(bodyScroll).position.jumpTo(0);
              await tester.pumpAndSettle();
              await _shot(tester, host.capture, '$prefix-page');
              await tester.tap(
                find.widgetWithText(FilledButton, strings.agree),
              );
              await tester.pumpAndSettle();
              expect(tester.widget<TextButton>(_confirm).onPressed, isNull);
              expect(
                tester.widget(find.text(strings.disclaimerRestateTitle)),
                isA<GlassText>(),
              );
              expect(
                tester.widget(find.text(strings.disclaimerRestateTip)),
                isA<GlassText>(),
              );
              final quote = find.byWidgetPredicate(
                (widget) =>
                    widget is SelectableText && widget.data == statement,
              );
              expect(quote, findsOneWidget);
              expect(
                find.ancestor(of: quote, matching: find.byType(GlassInk)),
                findsOneWidget,
              );
              expect(tester.widget<TextField>(_field).minLines, 3);
              expect(tester.widget<TextField>(_field).maxLines, isNull);
              final quotePane = _paneAbove(quote);
              final fieldPane = _paneAbove(_field);
              expect(quotePane, glass ? findsOneWidget : findsNothing);
              expect(fieldPane, glass ? findsOneWidget : findsNothing);
              for (final finder in [quotePane, fieldPane]) {
                if (glass) {
                  final pane = tester.widget<LiquidGlass>(finder);
                  expect(pane.style.tint.a, 0);
                  expect(pane.style.refraction, 0);
                  expect(pane.style.blur, 16);
                }
              }
              if (glass) {
                expect(
                  InputDecorationTheme.of(tester.element(_field)).fillColor!.a,
                  0,
                );
                expect(find.byType(LiquidGlassHost), findsOneWidget);
              }
              final quoteRect = tester.getRect(glass ? quotePane : quote);
              final fieldRect = tester.getRect(glass ? fieldPane : _field);
              final cancelRect = tester.getRect(
                find.widgetWithText(TextButton, strings.cancel),
              );
              samples.add(
                await _shot(
                  tester,
                  host.capture,
                  '$prefix-disabled',
                  probes: [
                    Offset(quoteRect.left + 5, quoteRect.center.dy),
                    Offset(fieldRect.left + 5, fieldRect.center.dy),
                    Offset(cancelRect.center.dx, cancelRect.top + 5),
                  ],
                ),
              );
              for (final label in [strings.cancel, strings.confirm]) {
                final button = find.widgetWithText(TextButton, label);
                final panes = find.descendant(
                  of: button,
                  matching: find.byType(LiquidGlass),
                );
                expect(panes, glass ? findsOneWidget : findsNothing);
                if (glass) {
                  expect(tester.widget<LiquidGlass>(panes).style.refraction, 0);
                }
              }
              await tester.enterText(_field, 'not the statement');
              await tester.pump();
              expect(
                find.text(strings.disclaimerRestateMismatch),
                findsOneWidget,
              );
              expect(tester.widget<TextButton>(_confirm).onPressed, isNull);
              await _shot(tester, host.capture, '$prefix-error');
              await tester.tap(find.widgetWithText(TextButton, strings.cancel));
              await tester.pumpAndSettle();
              expect(find.byType(DisclaimerView), findsOneWidget);
              await tester.tap(
                find.widgetWithText(FilledButton, strings.agree),
              );
              await tester.pumpAndSettle();
              expect(
                tester.widget<TextField>(_field).controller!.text,
                isEmpty,
              );
              await tester.enterText(_field, statement.substring(0, 5));
              await tester.pump();
              expect(tester.widget<TextButton>(_confirm).onPressed, isNull);
              await tester.enterText(_field, statement);
              await tester.pump();
              expect(
                find.text(strings.disclaimerRestateMismatch),
                findsNothing,
              );
              expect(tester.widget<TextButton>(_confirm).onPressed, isNotNull);
              await _shot(tester, host.capture, '$prefix-valid');
              tester.view.viewInsets = const FakeViewPadding(bottom: 280);
              await tester.pumpAndSettle();
              await tester.ensureVisible(_confirm);
              await tester.pumpAndSettle();
              expect(tester.getRect(_confirm).bottom, lessThanOrEqualTo(620));
              expect(
                tester.widget<TextField>(_field).controller!.text,
                statement,
              );
              await _shot(tester, host.capture, '$prefix-valid-keyboard');
              tester.view.resetViewInsets();
              await tester.pumpAndSettle();
              await tester.tap(_confirm);
              await tester.pumpAndSettle();
              expect(await consent, isTrue);
              final declined = requestDisclaimerConsent();
              await tester.pumpAndSettle();
              await tester.tap(
                find.widgetWithText(OutlinedButton, strings.exit),
              );
              await tester.pumpAndSettle();
              expect(await declined, isFalse);
              expect(tester.takeException(), isNull);
            } finally {
              tester.view.resetViewInsets();
              await tester.pumpWidget(const SizedBox.shrink());
              await tester.pump(Duration.zero);
              host.container.dispose();
            }
          }
          if (glass) {
            for (var i = 0; i < samples.first.length; i++) {
              final a = samples[0][i];
              final b = samples[1][i];
              expect(
                (a.r - b.r).abs() + (a.g - b.g).abs() + (a.b - b.b).abs(),
                greaterThan(0.10),
                reason:
                    'quote, input, and actions all transmit distinct backdrops',
              );
            }
          }
        },
      );
    }

    testWidgets('high contrast keeps native restatement fallback $brightness', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(430, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final host = await _mount(
        tester,
        brightness,
        LiquidGlassMode.ultra,
        Colors.blue,
        contrast: true,
      );
      try {
        final consent = requestDisclaimerConsent();
        await tester.pumpAndSettle();
        await tester.tap(find.widgetWithText(FilledButton, 'Agree'));
        await tester.pumpAndSettle();
        expect(_paneAbove(_field), findsNothing);
        expect(_paneAbove(find.byType(SelectableText)), findsNothing);
        expect(InputDecorationTheme.of(tester.element(_field)).fillColor!.a, 1);
        expect(tester.widget<TextButton>(_confirm).onPressed, isNull);
        await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
        await tester.pumpAndSettle();
        await tester.tap(find.widgetWithText(OutlinedButton, 'Exit'));
        await tester.pumpAndSettle();
        expect(await consent, isFalse);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump(Duration.zero);
        host.container.dispose();
      }
    });
  }
}
