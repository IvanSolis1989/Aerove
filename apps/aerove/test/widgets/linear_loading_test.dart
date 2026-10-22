import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  for (final brightness in Brightness.values) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      for (final highContrast in [false, true]) {
        testWidgets(
          'linear loading follows $brightness $quality high contrast $highContrast',
          (tester) async {
            final key = GlobalKey();
            final source = ValueNotifier(const Color(0xFFBDE5EC));
            addTearDown(source.dispose);
            final semantics = tester.ensureSemantics();
            final colors = ColorScheme.fromSeed(
              seedColor: Colors.blue,
              brightness: brightness,
            );
            try {
              await tester.pumpWidget(
                MaterialApp(
                  theme: ThemeData(colorScheme: colors),
                  home: MediaQuery(
                    data: MediaQueryData(highContrast: highContrast),
                    child: LiquidGlassScope(
                      enabled: quality != null,
                      quality: quality ?? LiquidGlassQuality.ultra,
                      child: LiquidGlassHost(
                        enabled: quality != null && !highContrast,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            Positioned.fill(
                              child: LiquidGlassColorField(
                                child: LiquidGlassColorSource(
                                  child: ValueListenableBuilder<Color>(
                                    valueListenable: source,
                                    builder: (_, color, _) =>
                                        ColoredBox(color: color),
                                  ),
                                ),
                              ),
                            ),
                            Center(
                              child: RepaintBoundary(
                                key: key,
                                child: const SizedBox(
                                  width: 200,
                                  child: CommonLinearLoading(
                                    value: 0.6,
                                    semanticLabel: 'Checking',
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              );
              await tester.pump();
              final glass = quality != null && !highContrast;
              expect(
                find.byType(GlassInk),
                glass ? findsOneWidget : findsNothing,
              );
              expect(find.bySemanticsLabel('Checking'), findsOneWidget);
              final indicator = tester.widget<LinearProgressIndicator>(
                find.byType(LinearProgressIndicator),
              );
              expect(indicator.value, 0.6);
              expect(indicator.color?.a, glass ? lessThan(1) : isNull);
              Future<int> pixel() async {
                await tester.pump();
                final image = tester
                    .renderObject<RenderRepaintBoundary>(find.byKey(key))
                    .toImageSync();
                try {
                  return (await tester.runAsync(
                    () async => (await image.toByteData())!.getUint32(
                      (2 * image.width + 40) * 4,
                    ),
                  ))!;
                } finally {
                  image.dispose();
                }
              }

              final before = await pixel();
              source.value = const Color(0xFFEAD0E8);
              await tester.pump();
              final after = await pixel();
              if (glass && ui.ImageFilter.isShaderFilterSupported) {
                expect(
                  before,
                  isNot(after),
                  reason: 'The active run follows its sampled backdrop',
                );
              } else if (!glass) {
                expect(
                  before,
                  after,
                  reason: 'Opaque progress keeps the theme color',
                );
              }
              await tester.pumpWidget(const SizedBox.shrink());
              expect(tester.takeException(), isNull);
            } finally {
              semantics.dispose();
            }
          },
        );
      }
    }
  }

  testWidgets(
    'the shared page header removes its loading animation when done',
    (tester) async {
      final loading = ValueNotifier(true);
      addTearDown(loading.dispose);
      await tester.pumpWidget(
        TestApp(
          wrapInProviderScope: true,
          child: ValueListenableBuilder<bool>(
            valueListenable: loading,
            builder: (_, value, _) => CommonScaffold(
              title: 'About',
              isLoading: value,
              body: const SizedBox.expand(),
            ),
          ),
        ),
      );
      expect(find.byType(CommonLinearLoading), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 400));
      loading.value = false;
      await tester.pumpAndSettle();
      expect(find.byType(CommonLinearLoading), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
