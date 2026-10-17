import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
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
