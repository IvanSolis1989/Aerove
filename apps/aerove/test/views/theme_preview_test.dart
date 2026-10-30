import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/theme_preview.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

void main() {
  testWidgets('aurora sketches preserve the candidate dark base brightness', (
    tester,
  ) async {
    final capture = GlobalKey();
    for (final pureBlack in [false, true, false]) {
      final colors = auroraColorScheme(Brightness.dark).toPureBlack(pureBlack);
      await tester.pumpWidget(
        MaterialApp(
          home: AuroraAppearance(
            enabled: true,
            child: Center(
              child: RepaintBoundary(
                key: capture,
                child: SizedBox(
                  width: 200,
                  height: 340,
                  child: MiniScreen(colorScheme: colors, floatingBar: true),
                ),
              ),
            ),
          ),
        ),
      );
      final canvas = tester.renderObject(
        find.descendant(
          of: find.byType(MiniScreen),
          matching: find.byType(CustomPaint),
        ),
      );
      expect(canvas, paints..rect(color: colors.surface));
      final boundary = tester.renderObject<RenderRepaintBoundary>(
        find.byKey(capture),
      );
      final image = boundary.toImageSync();
      try {
        final data = (await tester.runAsync(
          () => image.toByteData(format: ui.ImageByteFormat.rawRgba),
        ))!;
        expect(
          Color.fromARGB(
            data.getUint8(3),
            data.getUint8(0),
            data.getUint8(1),
            data.getUint8(2),
          ),
          colors.surface,
        );
      } finally {
        image.dispose();
      }
    }
  });
  for (final brightness in Brightness.values) {
    for (final aurora in [false, true]) {
      for (final floating in [false, true]) {
        for (final mode in LiquidGlassMode.values) {
          testWidgets('preview finish $brightness $mode '
              'aurora=$aurora floating=$floating', (tester) async {
            final colors = ColorScheme.fromSeed(
              seedColor: Colors.blue,
              brightness: brightness,
            );
            await tester.pumpWidget(
              MaterialApp(
                home: AuroraAppearance(
                  enabled: aurora,
                  child: LiquidGlassScope(
                    enabled: true,
                    quality: LiquidGlassQuality.ultra,
                    child: Center(
                      child: SizedBox(
                        width: 200,
                        height: 340,
                        child: MiniScreen(
                          colorScheme: colors,
                          floatingBar: floating,
                          liquidGlass: mode,
                          selected: 1,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
            final canvas = tester.renderObject(
              find.descendant(
                of: find.byType(MiniScreen),
                matching: find.byType(CustomPaint),
              ),
            );
            final opticalRim = paints
              ..something((method, arguments) {
                if (method != #drawPath) return false;
                final paint = arguments[1] as Paint;
                return paint.style == PaintingStyle.stroke &&
                    paint.shader != null;
              });
            expect(
              canvas,
              mode == LiquidGlassMode.ultra ? opticalRim : isNot(opticalRim),
            );
            final frost = paints
              ..something((method, arguments) {
                return method == #saveLayer &&
                    arguments[1] is Paint &&
                    (arguments[1] as Paint).imageFilter != null;
              });
            expect(canvas, mode == LiquidGlassMode.none ? isNot(frost) : frost);
            expect(tester.takeException(), isNull);
          });
        }
      }
    }
  }

  testWidgets('theme previews preserve selected and candidate tiers', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(1400, 2400);
    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(floatingNavigationBar: true));
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: ThemeView()),
      ),
    );
    await tester.pumpAndSettle();
    for (final mode in LiquidGlassMode.values) {
      container
          .read(themeSettingProvider.notifier)
          .update((state) => state.copyWith(liquidGlass: mode));
      await tester.pumpAndSettle();
      final live = tester.widget<MiniScreen>(
        find.descendant(
          of: find.byType(ThemeLivePreview),
          matching: find.byType(MiniScreen),
        ),
      );
      expect(live.liquidGlass, mode);
    }

    final material = find.byType(PreviewChoiceGroup<LiquidGlassMode>);
    await tester.scrollUntilVisible(
      material,
      400,
      scrollable: find
          .descendant(
            of: find.byType(ThemeView),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pumpAndSettle();
    final choices = tester
        .widget<PreviewChoiceGroup<LiquidGlassMode>>(material)
        .choices;
    for (final choice in choices) {
      final thumb = choice.pictogram as MiniScreenThumb;
      expect((thumb.screen as MiniScreen).liquidGlass, choice.value);
    }
    final docked = find.byWidgetPredicate(
      (widget) => widget is MiniScreen && !widget.floatingBar,
    );
    await tester.scrollUntilVisible(
      docked,
      -300,
      scrollable: find
          .descendant(
            of: find.byType(ThemeView),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pumpAndSettle();
    expect(docked, findsOneWidget);
    expect(
      tester.widget<MiniScreen>(docked).liquidGlass,
      LiquidGlassMode.ultra,
    );
    expect(tester.takeException(), isNull);
  });
}
