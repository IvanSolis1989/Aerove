import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/sheet_surface.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _glassModes = [LiquidGlassMode.standard, LiquidGlassMode.ultra];

final _controls = [
  find.byType(TextButton),
  find.byType(IconButton),
  find.byType(FilledButton),
  find.byType(TonalButton),
  find.byType(TonalButtonGroup),
  find.byType(GlassSwitch),
  find.byType(GlassSlider),
  find.byType(SegmentedControl<int>),
];

Future<void> _pump(
  WidgetTester tester, {
  required LiquidGlassMode mode,
  required Widget home,
  Brightness brightness = Brightness.light,
  Size size = const Size(400, 1000),
  bool highContrast = false,
  GlobalKey<NavigatorState>? navigator,
  GlobalKey? captureKey,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  if (highContrast) {
    tester.platformDispatcher.accessibilityFeaturesTestValue =
        const FakeAccessibilityFeatures(highContrast: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
  }
  await tester.pumpWidget(
    ProviderScope(
      key: UniqueKey(),
      overrides: [
        viewSizeProvider.overrideWithBuild((_, _) => size),
        themeSettingProvider.overrideWithBuild(
          (_, _) => ThemeProps(liquidGlass: mode),
        ),
      ],
      child: MaterialApp(
        navigatorKey: navigator,
        theme: ThemeData(brightness: brightness).withAppShapes,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          AppUiLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.delegate.supportedLocales,
        builder: (context, child) => wrapAppScopes(
          context,
          RepaintBoundary(
            key: captureKey,
            child: AuroraAppSurface(motion: false, child: child!),
          ),
        ),
        home: home,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

List<Widget> _rows(BuildContext context) => [
  ListItem(
    title: const Text('text'),
    trailing: TextButton(onPressed: () {}, child: const Text('Text')),
  ),
  ListItem(
    title: const Text('icon'),
    trailing: IconButton(
      tooltip: 'Icon',
      onPressed: () {},
      icon: const GlyphIcon(AppGlyphs.eye),
    ),
  ),
  ListItem(
    title: const Text('filled'),
    trailing: FilledButton(onPressed: () {}, child: const Text('Filled')),
  ),
  ListItem(
    title: const Text('tonal'),
    trailing: TonalButton(label: 'Tonal', onPressed: () {}),
  ),
  ListItem(
    title: const Text('group'),
    trailing: TonalButtonGroup(
      children: [
        IconButton(
          tooltip: 'Edit',
          onPressed: () {},
          icon: const GlyphIcon(AppGlyphs.edit),
        ),
        IconButton(
          tooltip: 'Delete',
          onPressed: () {},
          icon: const GlyphIcon(AppGlyphs.delete),
        ),
      ],
    ),
  ),
  ListItem<int>.options(
    title: const Text('options'),
    options: const [1, 2],
    value: 1,
    textBuilder: (value) => 'Option $value',
    onChanged: (_) {},
  ),
  ListItem.toggle(title: const Text('toggle'), value: true, onChanged: (_) {}),
  ListItem(
    title: SliderTheme(
      data: SliderDefaultsM3(context),
      child: GlassSlider(child: Slider(value: 0.5, onChanged: (_) {})),
    ),
  ),
  ListItem(
    title: SegmentedControl<int>(
      segments: const [(0, 'One'), (1, 'Two')],
      value: 0,
      onChanged: (_) {},
    ),
  ),
];

Widget _page({required bool inCard}) => Scaffold(
  body: SafeArea(
    child: Builder(
      builder: (context) => inCard
          ? generateSection(items: _rows(context))
          : Column(children: _rows(context)),
    ),
  ),
);

Finder _panesIn(Finder of) =>
    find.descendant(of: of, matching: find.byType(LiquidGlass));

Future<double> _labelContrast(
  WidgetTester tester,
  GlobalKey captureKey,
  ValueNotifier<bool> visible,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(captureKey),
  );
  final text = tester.renderObject<RenderParagraph>(
    find.descendant(of: find.text('Cancel'), matching: find.byType(RichText)),
  );
  final transform = text.getTransformTo(boundary);
  final bounds = MatrixUtils.transformRect(transform, Offset.zero & text.size);
  final layer = boundary.debugLayer! as OffsetLayer;
  Future<List<int>> bytes(ui.Image image) async {
    try {
      return (await tester.runAsync(
        () async => (await image.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!.buffer.asUint8List().toList(),
      ))!;
    } finally {
      image.dispose();
    }
  }

  final image = layer.toImageSync(bounds, pixelRatio: 3);
  final width = image.width;
  final height = image.height;
  final actual = await bytes(image);
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder)
    ..scale(3)
    ..translate(-bounds.left, -bounds.top)
    ..transform(transform.storage);
  final painter = TextPainter(
    text: text.text,
    textDirection: text.textDirection,
  )..layout(maxWidth: text.size.width);
  painter.paint(canvas, Offset.zero);
  painter.dispose();
  final picture = recorder.endRecording();
  final mask = await bytes(picture.toImageSync(width, height));
  picture.dispose();
  visible.value = false;
  await tester.pump();
  final blank = await bytes(layer.toImageSync(bounds, pixelRatio: 3));
  visible.value = true;
  await tester.pump();
  var minimum = double.infinity;
  var solid = 0;
  for (var at = 0; at < mask.length; at += 4) {
    if (mask[at + 3] != 255) continue;
    solid++;
    double luminance(List<int> data) => Color.fromARGB(
      255,
      data[at],
      data[at + 1],
      data[at + 2],
    ).computeLuminance();
    final a = luminance(actual);
    final b = luminance(blank);
    minimum = math.min(
      minimum,
      (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05),
    );
  }
  expect(solid, greaterThan(0));
  return minimum;
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
    }
  });

  for (final brightness in Brightness.values) {
    for (final mode in _glassModes) {
      final name = '${brightness.name} ${mode.name}';

      testWidgets('controls in a section add no pane to its card $name', (
        tester,
      ) async {
        await _pump(
          tester,
          mode: mode,
          brightness: brightness,
          home: _page(inCard: true),
        );
        final card = find.byType(CardGroup);
        expect(_panesIn(card), findsOneWidget);
        for (final control in _controls) {
          expect(control, findsWidgets);
          expect(_panesIn(control), findsNothing, reason: '$control');
        }
        final panes = LiquidGlassHost.debugLastCapture!.panes;
        expect(panes, hasLength(1));
        expect(panes.single.pane, rectMoreOrLessEquals(tester.getRect(card)));
        final row = tester.element(find.text('Option 1'));
        expect(GlassSurfaceScope.of(row), isTrue);
        final colors = Theme.of(row).colorScheme;
        final tonal = find.descendant(
          of: find.byType(TonalButton),
          matching: find.byWidgetPredicate(
            (widget) =>
                widget is DecoratedBox &&
                (widget.decoration as ShapeDecoration?)?.color ==
                    glassFillOf(colors),
          ),
        );
        expect(tonal, findsOneWidget);
        expect(tester.takeException(), isNull);
      });

      testWidgets('controls on the page take a pane each at most $name', (
        tester,
      ) async {
        await _pump(
          tester,
          mode: mode,
          brightness: brightness,
          home: _page(inCard: false),
        );
        for (final control in _controls) {
          for (final element in control.evaluate()) {
            expect(
              _panesIn(
                find.byElementPredicate((e) => e == element),
              ).evaluate().length,
              lessThanOrEqualTo(1),
              reason: '${element.widget.runtimeType}',
            );
          }
        }
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(9));
        expect(tester.takeException(), isNull);
      });

      for (final (kind, size, show) in [
        ('snap sheet', const Size(400, 800), showSheet),
        ('form sheet', const Size(1200, 800), showFormSheet),
      ]) {
        testWidgets('a section in a $kind paints no pane $name', (
          tester,
        ) async {
          final navigator = GlobalKey<NavigatorState>();
          await _pump(
            tester,
            mode: mode,
            brightness: brightness,
            size: size,
            navigator: navigator,
            home: const Scaffold(body: SizedBox.expand()),
          );
          unawaited(
            show<void>(
              context: navigator.currentContext!,
              builder: (context) => CommonScaffold(
                title: 'Sheet',
                body: SingleChildScrollView(
                  child: generateSection(
                    items: [
                      ListItem.toggle(
                        title: const Text('toggle'),
                        value: false,
                        onChanged: (_) {},
                      ),
                      ListItem(
                        title: const Text('tonal'),
                        trailing: TonalButton(label: 'Bind', onPressed: () {}),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final card = find.byType(CardGroup);
          expect(_panesIn(card), findsNothing);
          final surface = find.byType(SheetSurface);
          expect(
            find.descendant(
              of: surface,
              matching: find.byWidgetPredicate(
                (widget) => widget is LiquidGlass && widget.style.blur == 16,
              ),
            ),
            findsOneWidget,
          );
          final row = tester.element(find.text('toggle'));
          expect(GlassSurfaceScope.of(row), isTrue);
          final fill = tester.widget<DecoratedBox>(
            find
                .descendant(of: card, matching: find.byType(DecoratedBox))
                .first,
          );
          expect(
            (fill.decoration as ShapeDecoration).color,
            glassRaisedFillOf(tester.element(card)),
          );
          expect(tester.takeException(), isNull);
        });
      }

      testWidgets('alert actions are flat fills on its one pane $name', (
        tester,
      ) async {
        final navigator = GlobalKey<NavigatorState>();
        final captureKey = GlobalKey();
        final visible = ValueNotifier(true);
        addTearDown(visible.dispose);
        await _pump(
          tester,
          mode: mode,
          brightness: brightness,
          navigator: navigator,
          captureKey: captureKey,
          home: const Scaffold(body: SizedBox.expand()),
        );
        unawaited(
          showFloatingDialog<void>(
            context: navigator.currentContext!,
            child: CommonDialog(
              title: 'Rename',
              hasFields: true,
              actions: [
                TextButton(
                  onPressed: () {},
                  child: ValueListenableBuilder<bool>(
                    valueListenable: visible,
                    builder: (_, shown, child) =>
                        Opacity(opacity: shown ? 1 : 0, child: child),
                    child: const GlassText('Cancel'),
                  ),
                ),
                TextButton(onPressed: () {}, child: const Text('Save')),
              ],
              child: const TextField(),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(LiquidGlass), findsNothing);
        expect(find.byType(GlassPlatter), findsOneWidget);
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
        final colors = Theme.of(tester.element(find.text('Save'))).colorScheme;
        Color? fillOf(String label) => tester
            .widget<Material>(
              find
                  .descendant(
                    of: find.widgetWithText(TextButton, label),
                    matching: find.byType(Material),
                  )
                  .first,
            )
            .color;
        expect(fillOf('Save'), colors.primary.withValues(alpha: 0.2));
        expect(fillOf('Cancel'), glassFillOf(colors));
        if (ui.ImageFilter.isShaderFilterSupported) {
          expect(
            await _labelContrast(tester, captureKey, visible),
            greaterThanOrEqualTo(4.5),
          );
        }
        expect(
          DefaultTextStyle.of(tester.element(find.text('Save'))).style.color,
          colors.primary,
        );
        expect(tester.takeException(), isNull);
      });

      testWidgets('a dashboard card on a pane paints its fill once $name', (
        tester,
      ) async {
        await _pump(
          tester,
          mode: mode,
          brightness: brightness,
          home: const Scaffold(
            body: Center(
              child: CardGroup(child: DashboardCard(child: Text('Nested'))),
            ),
          ),
        );
        final card = find.byType(DashboardCard);
        final fill = glassRaisedFillOf(tester.element(card));
        expect(
          tester
              .widgetList<DecoratedBox>(
                find.descendant(of: card, matching: find.byType(DecoratedBox)),
              )
              .where(
                (box) =>
                    box.decoration is ShapeDecoration &&
                    (box.decoration as ShapeDecoration).color == fill,
              ),
          hasLength(1),
        );
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
        expect(tester.takeException(), isNull);
      });

      testWidgets('header buttons on glass keep their lens $name', (
        tester,
      ) async {
        final progress = ValueNotifier(1.0);
        addTearDown(progress.dispose);
        await _pump(
          tester,
          mode: mode,
          brightness: brightness,
          home: Scaffold(
            body: Center(
              child: GlassSurfaceScope(
                child: HeaderButtonGlassScope(
                  progress: progress,
                  child: TonalButtonTheme(
                    glass: true,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          tooltip: 'Back',
                          onPressed: () {},
                          icon: const GlyphIcon(AppGlyphs.arrowBack),
                        ),
                        TonalButton(label: 'Done', onPressed: () {}),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        final colors = Theme.of(tester.element(find.text('Done'))).colorScheme;
        final lens = find.byWidgetPredicate(
          (widget) =>
              widget is LiquidGlass &&
              widget.contentBackdrop &&
              widget.style == LiquidGlassStyle.lens(colors),
        );
        for (final button in [
          find.byType(IconButton),
          find.byType(TonalButton),
        ]) {
          expect(find.descendant(of: button, matching: lens), findsOneWidget);
        }
        expect(tester.takeException(), isNull);
      });
    }
  }

  for (final (name, mode, highContrast) in [
    ('plain solid', LiquidGlassMode.none, false),
    ('high contrast', LiquidGlassMode.ultra, true),
  ]) {
    testWidgets('$name keeps the opaque controls', (tester) async {
      await _pump(
        tester,
        mode: mode,
        highContrast: highContrast,
        home: _page(inCard: true),
      );
      expect(find.byType(LiquidGlass), findsNothing);
      final row = tester.element(find.text('Option 1'));
      expect(GlassSurfaceScope.of(row), isFalse);
      expect(Theme.of(row).textButtonTheme.style?.backgroundBuilder, isNull);
      Element? parent;
      tester.element(find.byType(Switch)).visitAncestorElements((element) {
        parent = element;
        return false;
      });
      expect(parent!.widget, isA<GlassSwitch>());
      final control = tester.widget<Container>(
        find
            .descendant(
              of: find.byType(SegmentedControl<int>),
              matching: find.byType(Container),
            )
            .first,
      );
      expect(
        (control.decoration! as ShapeDecoration).color,
        SurfaceLevel.block.colorOf(Theme.of(row).colorScheme),
      );
      expect(tester.takeException(), isNull);
    });
  }
}
