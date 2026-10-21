import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_mode.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/glyph_finders.dart';
import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  Future<void> pump(
    WidgetTester tester,
    Widget child, {
    double width = 360,
    double textScale = 1,
  }) async {
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: Scaffold(
            body: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(
                width: width,
                child: MediaQuery.withClampedTextScaling(
                  minScaleFactor: textScale,
                  maxScaleFactor: textScale,
                  child: child,
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  Finder buttonOf(Mode mode) => find
      .ancestor(of: find.text(mode.label), matching: find.byType(InkWell))
      .first;

  ShapeDecoration decorationOf(WidgetTester tester, Mode mode) {
    final container = find
        .ancestor(of: buttonOf(mode), matching: find.byType(Container))
        .first;
    return tester.widget<Container>(container).decoration! as ShapeDecoration;
  }

  double startRadiusOf(WidgetTester tester, Mode mode) {
    final shape = decorationOf(tester, mode).shape as RoundedSuperellipseBorder;
    return (shape.borderRadius as BorderRadius).topLeft.x;
  }

  testWidgets('the card shows every mode with its own glyph', (tester) async {
    await pump(tester, const OutboundModeCard());

    for (final (mode, glyph) in [
      (Mode.rule, AppGlyphs.rules),
      (Mode.global, AppGlyphs.proxies),
      (Mode.direct, AppGlyphs.target),
    ]) {
      expect(
        find.descendant(of: buttonOf(mode), matching: find.byGlyph(glyph)),
        findsOneWidget,
      );
    }
  });

  testWidgets('tapping a mode selects it and turns it into the pill', (
    tester,
  ) async {
    await pump(tester, const OutboundModeCard());
    final colorScheme = Theme.of(
      tester.element(find.byType(OutboundModeCard)),
    ).colorScheme;
    expect(find.byType(LiquidGlass), findsNothing);
    expect(decorationOf(tester, Mode.rule).color, colorScheme.primary);
    for (final mode in Mode.values) {
      expect(decorationOf(tester, mode).color!.a, 1);
    }
    final innerRadius = startRadiusOf(tester, Mode.direct);

    await tester.tap(buttonOf(Mode.direct));
    await tester.pumpAndSettle();

    expect(container.read(patchClashConfigProvider).mode, Mode.direct);
    expect(decorationOf(tester, Mode.direct).color, colorScheme.primary);
    expect(decorationOf(tester, Mode.rule).color, isNot(colorScheme.primary));
    expect(startRadiusOf(tester, Mode.direct), greaterThan(innerRadius));
  });

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('the modes stand flat on the card\'s clear ${quality.name} '
        'glass and still switch', (tester) async {
      await pump(
        tester,
        LiquidGlassScope(
          enabled: true,
          quality: quality,
          child: const OutboundModeCard(),
        ),
      );
      final colorScheme = Theme.of(
        tester.element(find.byType(OutboundModeCard)),
      ).colorScheme;

      void expectClearModes() {
        expect(find.byType(LiquidGlass), findsOneWidget);
        final selected = container.read(patchClashConfigProvider).mode;
        for (final mode in Mode.values) {
          expect(
            decorationOf(tester, mode).color,
            mode == selected
                ? glassChosenFillOf(colorScheme)
                : Colors.transparent,
          );
        }
        for (final pane in tester.widgetList<LiquidGlass>(
          find.byType(LiquidGlass),
        )) {
          expect(
            pane.style.tint,
            quality == LiquidGlassQuality.standard
                ? glassRaisedFillOf(
                    tester.element(find.byType(OutboundModeCard)),
                  )
                : Colors.transparent,
          );
        }
      }

      expectClearModes();
      await tester.tap(buttonOf(Mode.direct));
      await tester.pumpAndSettle();
      expect(container.read(patchClashConfigProvider).mode, Mode.direct);
      expect(
        tester.widget<Text>(find.text(Mode.direct.label)).style!.color,
        colorScheme.onSurface,
      );
      expectClearModes();
      await tester.tap(buttonOf(Mode.rule));
      await tester.pumpAndSettle();
      expect(container.read(patchClashConfigProvider).mode, Mode.rule);
      expectClearModes();
    });
  }

  for (final quality in LiquidGlassQuality.values) {
    for (final direction in TextDirection.values) {
      testWidgets('${quality.name} mode geometry stays equal in '
          '${direction.name} through selection', (tester) async {
        void expectGeometry() {
          expect(find.byType(LiquidGlass), findsOneWidget);
          for (final mode in Mode.values) {
            final decoration = decorationOf(tester, mode);
            final shape = decoration.shape as RoundedSuperellipseBorder;
            expect(shape.borderRadius, AppShape.full.borderRadius);
            final button = tester.widget<InkWell>(buttonOf(mode));
            expect(button.onTap, isNotNull);
            expect(button.customBorder, shape);
            final size = tester.getSize(buttonOf(mode));
            final bounds = Offset.zero & size;
            final expected = AppShape.full.getOuterPath(bounds);
            final actual = shape.getOuterPath(bounds);
            expect(
              find.ancestor(
                of: buttonOf(mode),
                matching: find.byType(LiquidGlass),
              ),
              findsOneWidget,
            );
            for (var x = 0; x <= 8; x++) {
              for (var y = 0; y <= 8; y++) {
                final point = Offset(size.width * x / 8, size.height * y / 8);
                expect(actual.contains(point), expected.contains(point));
              }
            }
          }
        }

        for (final desktop in [false, true]) {
          final content = desktop
              ? const DashboardDensityScope(
                  density: DashboardDensity.standard,
                  scale: 1,
                  child: SizedBox(height: 64, child: OutboundModeBar()),
                )
              : const OutboundModeCard();
          await pump(
            tester,
            Directionality(
              textDirection: direction,
              child: LiquidGlassScope(
                enabled: true,
                quality: quality,
                child: content,
              ),
            ),
            width: desktop ? 600 : 360,
          );
          await tester.pumpAndSettle();
          expectGeometry();
          await tester.tap(buttonOf(Mode.direct));
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 50));
          expectGeometry();
          await tester.pumpAndSettle();
          expect(container.read(patchClashConfigProvider).mode, Mode.direct);
          expectGeometry();
          await tester.tap(buttonOf(Mode.rule));
          await tester.pumpAndSettle();
          expect(container.read(patchClashConfigProvider).mode, Mode.rule);
          expectGeometry();
        }
      });
    }
  }

  testWidgets('a pressed mode swells alone, over its neighbours', (
    tester,
  ) async {
    await pump(tester, const OutboundModeCard());
    final colorScheme = Theme.of(
      tester.element(find.byType(OutboundModeCard)),
    ).colorScheme;
    double swellOf(Mode mode) {
      final box = tester.renderObject<RenderBox>(buttonOf(mode));
      return ElasticPress.paintBoundsOf(box).width / box.size.width;
    }

    final gesture = await tester.startGesture(
      tester.getCenter(buttonOf(Mode.rule)),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(swellOf(Mode.rule), greaterThan(1));
    expect(swellOf(Mode.global), moreOrLessEquals(1));
    expect(swellOf(Mode.direct), moreOrLessEquals(1));
    final row = find
        .ancestor(
          of: buttonOf(Mode.rule),
          matching: find.byWidgetPredicate((widget) => widget is Flex),
        )
        .first;
    expect(
      tester.renderObject(row),
      paints
        ..rsuperellipse()
        ..rsuperellipse()
        ..rsuperellipse(color: colorScheme.primary),
    );

    await gesture.up();
    await tester.pumpAndSettle();
    expect(swellOf(Mode.rule), moreOrLessEquals(1));
  });

  testWidgets('the desktop bar fills its height and switches the mode', (
    tester,
  ) async {
    await pump(
      tester,
      const DashboardDensityScope(
        density: DashboardDensity.standard,
        scale: 1,
        child: SizedBox(height: 64, child: OutboundModeBar()),
      ),
      width: 600,
    );
    expect(tester.getSize(buttonOf(Mode.rule)).height, 64 - 8 * 2);

    await tester.tap(buttonOf(Mode.global));
    await tester.pumpAndSettle();

    expect(container.read(patchClashConfigProvider).mode, Mode.global);
  });

  testWidgets('large text on a narrow phone still fits', (tester) async {
    await pump(tester, const OutboundModeCard(), width: 300, textScale: 1.4);

    expect(tester.takeException(), isNull);
  });
}
