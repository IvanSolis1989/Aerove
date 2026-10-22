import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  testWidgets('all shared button types use one pane and retain their states', (
    tester,
  ) async {
    const error = Color(0xFFB3261E);
    const disabled = Color.fromRGBO(179, 38, 30, 0.38);
    for (final mode in LiquidGlassMode.values) {
      for (final highContrast in [false, true]) {
        await tester.pumpWidget(
          ProviderScope(
            key: UniqueKey(),
            overrides: [
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(auroraEnabled: false, liquidGlass: mode),
              ),
            ],
            child: MaterialApp(
              theme: ThemeData(
                platform: TargetPlatform.android,
                colorScheme: ColorScheme.fromSeed(
                  seedColor: Colors.blue,
                ).copyWith(error: error),
                outlinedButtonTheme: OutlinedButtonThemeData(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: error,
                    disabledForegroundColor: disabled,
                    padding: const EdgeInsets.all(18),
                    shape: AppShape.lg,
                  ),
                ),
              ).withAppShapes.withAppSurfaces,
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(
                  context,
                ).copyWith(highContrast: highContrast),
                child: AuroraAppSurface(motion: false, child: child!),
              ),
              home: Scaffold(
                body: Column(
                  children: [
                    OutlinedButton(onPressed: () {}, child: const Text('Exit')),
                    FilledButton(onPressed: () {}, child: const Text('Agree')),
                    TextButton(onPressed: () {}, child: const Text('Text')),
                    ElevatedButton(
                      onPressed: () {},
                      child: const Text('Raised'),
                    ),
                    IconButton(
                      key: const ValueKey('icon-action'),
                      onPressed: () {},
                      icon: const Text('Icon'),
                    ),
                    const OutlinedButton(
                      onPressed: null,
                      child: Text('Disabled'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final enabled = mode != LiquidGlassMode.none && !highContrast;
        for (final button in [
          find.widgetWithText(OutlinedButton, 'Exit'),
          find.widgetWithText(FilledButton, 'Agree'),
          find.widgetWithText(TextButton, 'Text'),
          find.widgetWithText(ElevatedButton, 'Raised'),
          find.byKey(const ValueKey('icon-action')),
          find.widgetWithText(OutlinedButton, 'Disabled'),
        ]) {
          expect(
            find.descendant(of: button, matching: find.byType(LiquidGlass)),
            enabled ? findsOneWidget : findsNothing,
          );
        }
        expect(find.byType(LiquidGlassHost), findsOneWidget);
        final context = tester.element(find.text('Exit'));
        final color = DefaultTextStyle.of(context).style.color!;
        expect(color.withValues(alpha: 1), error);
        expect(color.a, 1);
        expect(
          DefaultTextStyle.of(
            tester.element(find.text('Disabled')),
          ).style.color,
          disabled,
        );
        final style = OutlinedButtonTheme.of(context).style!;
        expect(style.padding!.resolve({}), const EdgeInsets.all(18));
        if (enabled) {
          expect(style.side!.resolve({}), BorderSide.none);
          expect(
            style.side!.resolve({WidgetState.focused})!.width,
            greaterThan(0),
          );
          final pane = tester.widget<LiquidGlass>(
            find.descendant(
              of: find.widgetWithText(OutlinedButton, 'Exit'),
              matching: find.byType(LiquidGlass),
            ),
          );
          expect((pane.clipper! as ShapeBorderClipper).shape, AppShape.lg);
        } else {
          final material = tester.widget<Material>(
            find.descendant(
              of: find.widgetWithText(OutlinedButton, 'Exit'),
              matching: find.byType(Material),
            ),
          );
          expect(
            (material.shape! as OutlinedBorder).side.width,
            greaterThan(0),
          );
        }
        expect(tester.takeException(), isNull);
      }
    }
  });
}
