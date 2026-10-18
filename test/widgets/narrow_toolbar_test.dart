import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/navigator.dart';
import 'package:fl_clash/ui/typography.dart';
import 'package:fl_clash/views/dashboard/widgets/core_status_button.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    await (FontLoader('NarrowToolbarProbe')
          ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf')))
        .load();
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
  });
  for (final mode in [LiquidGlassMode.none, LiquidGlassMode.ultra]) {
    for (final entry in ['Dashboard', 'Proxies', 'Profiles', 'Tools', 'pushed', 'sheet']) {
      testWidgets('narrow toolbar $entry $mode at 320dp and 2x text', (tester) async {
        final previousPlatform = debugDefaultTargetPlatformOverride;
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        tester.view.physicalSize = const Size(320, 800);
        tester.view.devicePixelRatio = 1;
        final semantics = tester.ensureSemantics();
        final container = ProviderContainer(overrides: [
          themeSettingProvider.overrideWithValue(ThemeProps(
            auroraEnabled: false,
            liquidGlass: mode,
          )),
        ]);
        globalState.container = container;
        container.read(viewSizeProvider.notifier).value = const Size(320, 800);
        container.read(coreStatusProvider.notifier).value = CoreStatus.disconnected;
        final calls = <String>[];
        final status = entry == 'Dashboard' || entry == 'pushed' || entry == 'sheet';
        final title = status ? 'Dashboard' : entry;
        final labels = status
            ? entry == 'Dashboard'
              ? ['Edit dashboard']
              : ['Edit dashboard', 'Update', 'Sort']
            : entry == 'Proxies'
            ? ['Delay test', 'Locate', 'Filter', 'Sort']
            : entry == 'Profiles'
            ? ['Add profile', 'Sync', 'Sort']
            : <String>[];
        Widget bar() => CommonScaffold(
          title: title,
          actions: status ? const [CoreStatusButton()] : null,
          iconActions: [
            for (final label in labels)
              IconButtonData(
                glyph: AppGlyphs.edit,
                tooltip: label,
                onPressed: () => calls.add(label),
              ),
          ],
          body: const SizedBox.expand(),
        );
        final capture = GlobalKey();
        try {
          await tester.pumpWidget(UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              navigatorKey: globalState.navigatorKey,
              theme: ThemeData(
                platform: TargetPlatform.android,
                fontFamily: 'NarrowToolbarProbe',
              ).withAppTypography,
              localizationsDelegates: const [
                AppLocalizations.delegate,
                AppUiLocalizations.delegate,
                ...GlobalMaterialLocalizations.delegates,
              ],
              supportedLocales: AppLocalizations.delegate.supportedLocales,
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context).copyWith(textScaler: const TextScaler.linear(2)),
                child: Builder(builder: (context) => wrapAppScopes(
                  context,
                  RepaintBoundary(
                    key: capture,
                    child: AuroraAppSurface(motion: false, child: child!),
                  ),
                )),
              ),
              home: entry == 'pushed' || entry == 'sheet'
                  ? Builder(builder: (context) => TextButton(
                      onPressed: () {
                        if (entry == 'pushed') {
                          BaseNavigator.push<void>(context, bar());
                        } else {
                          showSheet<void>(context: context, builder: (_) => bar());
                        }
                      },
                      child: const Text('Open toolbar'),
                    ))
                  : bar(),
            ),
          ));
          await tester.pumpAndSettle();
          if (entry == 'pushed' || entry == 'sheet') {
            await tester.tap(find.text('Open toolbar'));
            await tester.pumpAndSettle();
          }
          expect(tester.takeException(), isNull);
          final heading = find.descendant(of: find.byType(AppBar), matching: find.text(title));
          expect(heading, findsOneWidget);
          final titleRect = tester.getRect(heading);
          expect(titleRect.width, greaterThan(0));
          expect(titleRect.left, greaterThanOrEqualTo(0));
          expect(titleRect.right, lessThanOrEqualTo(320));
          await _image(tester, capture, 'toolbar-$entry-${mode.name}');
          for (final label in labels) {
            final direct = find.byTooltip(label).hitTestable();
            if (direct.evaluate().isEmpty) {
              await tester.tap(find.byTooltip('More'));
              await tester.pumpAndSettle();
              expect(find.text(label).hitTestable(), findsOneWidget);
              await tester.tap(find.text(label));
            } else {
              await tester.tap(direct);
            }
            await tester.pumpAndSettle();
            expect(calls.where((call) => call == label), hasLength(1));
            expect(tester.takeException(), isNull);
          }
          if (status) {
            final button = find.descendant(
              of: find.byType(CoreStatusButton), matching: find.byType(IconButton),
            );
            expect(button.hitTestable(), findsOneWidget);
            expect(find.bySemanticsLabel(RegExp('Disconnected')), findsWidgets);
            await tester.tap(button);
            await tester.pumpAndSettle();
            expect(find.byType(CommonDialog), findsOneWidget);
            Navigator.of(tester.element(find.byType(CommonDialog))).pop(false);
            await tester.pumpAndSettle();
          }
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          container.dispose();
          semantics.dispose();
          tester.view.reset();
          debugDefaultTargetPlatformOverride = previousPlatform;
        }
      });
    }
  }
}

Future<void> _image(WidgetTester tester, GlobalKey key, String name) async {
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  if (output == null) return;
  final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  await tester.runAsync(() async {
    final image = boundary.toImageSync();
    final bytes = (await image.toByteData(format: ui.ImageByteFormat.png))!;
    await Directory(output).create(recursive: true);
    await File('$output/$name.png').writeAsBytes(bytes.buffer.asUint8List());
    image.dispose();
  });
}
