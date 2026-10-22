import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  for (final onGlass in [false, true]) {
    testWidgets(
      'a chosen card is ${onGlass ? 'a flat primary fill on glass' : 'a tinted pane on the page'}, '
      'and selection draws no border',
      (tester) async {
        await tester.pumpWidget(
          TestApp(
            includeNavigatorKey: false,
            child: Scaffold(
              body: LiquidGlassScope(
                enabled: true,
                child: LiquidGlassHost(
                  enabled: true,
                  child: Center(
                    child: GlassSurfaceScope(
                      enabled: onGlass,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          CommonCard(
                            isSelected: true,
                            fillsSelection: true,
                            onPressed: () {},
                            child: const Text('Chosen'),
                          ),
                          CommonCard(
                            isSelected: true,
                            onPressed: () {},
                            child: const Text('Picked'),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final context = tester.element(find.byType(Row));
        final colors = Theme.of(context).colorScheme;
        ButtonStyle styleOf(String label) => tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, label))
            .style!;
        Finder panesOf(String label) => find.descendant(
          of: find.widgetWithText(FilledButton, label),
          matching: find.byType(LiquidGlass),
        );
        for (final label in ['Chosen', 'Picked']) {
          expect(styleOf(label).side!.resolve({}), BorderSide.none);
          expect(
            styleOf(label).side!.resolve({WidgetState.selected}),
            BorderSide.none,
          );
        }
        if (onGlass) {
          expect(find.byType(LiquidGlass), findsNothing);
          expect(
            styleOf('Chosen').backgroundColor!.resolve({}),
            glassChosenFillOf(colors),
          );
          expect(
            styleOf('Picked').backgroundColor!.resolve({}),
            glassRaisedFillOf(context),
          );
        } else {
          expect(
            tester.widget<LiquidGlass>(panesOf('Chosen')).style,
            LiquidGlassStyle.tinted(colors, colors.primary),
          );
          expect(
            tester.widget<LiquidGlass>(panesOf('Picked')).style,
            LiquidGlassStyle.regular(colors),
          );
          expect(
            styleOf('Chosen').backgroundColor!.resolve({}),
            Colors.transparent,
          );
        }
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final brightness in Brightness.values) {
    testWidgets('active glass rows share one continuous glass ($brightness)', (
      tester,
    ) async {
      final colorScheme = ColorScheme.fromSeed(
        seedColor: Colors.blue,
        brightness: brightness,
      );
      final captureKey = GlobalKey();
      var taps = 0;
      await tester.pumpWidget(
        TestApp(
          includeNavigatorKey: false,
          theme: ThemeData(
            colorScheme: colorScheme,
            filledButtonTheme: FilledButtonThemeData(
              style: LiquidGlass.buttonStyle(
                (_) => LiquidGlassStyle.regular(colorScheme),
              ),
            ),
          ),
          child: Scaffold(
            body: LiquidGlassScope(
              enabled: true,
              child: LiquidGlassHost(
                enabled: true,
                child: RemoteFocusAdapter(
                  enabled: true,
                  child: Center(
                    child: RepaintBoundary(
                      key: captureKey,
                      child: SizedBox(
                        width: 400,
                        child: generateSection(
                          items: [
                            for (var index = 0; index < 3; index++)
                              DecorationListItem(
                                title: Text('Row $index'),
                                isSelected: index == 1,
                                onPressed: () => taps++,
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(LiquidGlassHost), findsOneWidget);
      expect(find.byType(LiquidGlass), findsOneWidget);
      expect(find.byType(CommonCard), findsNWidgets(3));
      for (final button in tester.widgetList<FilledButton>(
        find.byType(FilledButton),
      )) {
        expect(button.style!.side!.resolve({}), BorderSide.none);
        expect(
          button.style!.side!.resolve({WidgetState.focused})!.width,
          greaterThan(0.5),
        );
        expect(button.style!.backgroundColor!.resolve({}), Colors.transparent);
      }
      await tester.tap(find.text('Row 1'));
      await tester.pumpAndSettle();
      expect(taps, 1);
      expect(find.byType(LiquidGlass), findsOneWidget);
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();

      final boundary = tester.renderObject<RenderRepaintBoundary>(
        find.byKey(captureKey),
      );
      final image = boundary.toImageSync();
      try {
        await tester.runAsync(() async {
          final pixels = (await image.toByteData())!;
          int alphaAt(int x, int y) =>
              pixels.getUint8((y * image.width + x) * 4 + 3);
          expect(alphaAt(0, 0), 0);
          expect(alphaAt(image.width - 1, image.height - 1), 0);
          expect(alphaAt(image.width ~/ 2, 1), greaterThan(0));
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            final bytes = (await image.toByteData(
              format: ui.ImageByteFormat.png,
            ))!;
            await Directory(output).create(recursive: true);
            await File(
              '$output/xclash-glass-group-${brightness.name}.png',
            ).writeAsBytes(bytes.buffer.asUint8List());
          }
        });
      } finally {
        image.dispose();
      }
    });
  }
}
