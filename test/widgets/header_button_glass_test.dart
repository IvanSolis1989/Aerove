import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _scene = ValueKey('header-button-scene');

class _Stripes extends CustomPainter {
  const _Stripes();
  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(Rect.fromLTWH(x, 0, 8, size.height), Paint()..color =
          (x ~/ 8).isEven ? const Color(0xFF3050B0) : const Color(0xFFE8D8A0));
    }
  }
  @override
  bool shouldRepaint(_Stripes oldDelegate) => false;
}

Finder get _headerPanes => find.descendant(
  of: find.byType(HeaderButtonGlassScope),
  matching: find.byType(LiquidGlass),
);

Future<void> _shot(WidgetTester tester, String name) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(_scene));
  if (boundary.debugNeedsPaint) {
    tester.binding.scheduleForcedFrame();
    await tester.pump();
  }
  final button = tester.getRect(find.byTooltip('Action A'));
  await tester.runAsync(() async {
    final image = await boundary.toImage();
    final capture = LiquidGlassHost.debugLastCapture;
    final matches = capture?.panes.where((pane) => pane.pane.contains(button.center)).toList();
    matches?.sort((a, b) => (a.pane.width * a.pane.height).compareTo(b.pane.width * b.pane.height));
    final selected = matches == null || matches.isEmpty ? null : matches.first;
    final atlas = selected?.atlas.clone();
    try {
      double? variance;
      if (atlas != null && selected != null) {
        final rgba = (await atlas.toByteData(format: ui.ImageByteFormat.rawRgba))!;
        var sum = 0.0;
        var squared = 0.0;
        var count = 0;
        for (var y = button.top + 8; y < button.bottom - 8; y++) {
          for (var x = button.left + 8; x < button.right - 8; x++) {
            final ax = (selected.place.left + (x - selected.slot.left) * selected.scale).floor();
            final ay = (selected.place.top + (y - selected.slot.top) * selected.scale).floor();
            final index = (ay * atlas.width + ax) * 4;
            final value = rgba.getUint8(index).toDouble();
            sum += value;
            squared += value * value;
            count++;
          }
        }
        variance = squared / count - (sum / count) * (sum / count);
      }
      if (name.endsWith('-under') && ui.ImageFilter.isShaderFilterSupported) {
        if (selected == null) fail('the scrolled header has no captured button pane');
        expect(selected.wallpaper, isTrue);
        expect(selected.level, 0);
        expect(variance, greaterThan(1000), reason: 'header buttons sample sharp contextual stripes, not HeaderBlur output');
      }
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('$output/header-buttons-$name.png').writeAsBytesSync(png.buffer.asUint8List());
        if (atlas != null) {
          final png = (await atlas.toByteData(format: ui.ImageByteFormat.png))!;
          File('$output/header-buttons-$name-atlas.png').writeAsBytesSync(png.buffer.asUint8List());
        }
        File('$output/header-buttons-$name.json').writeAsStringSync(jsonEncode({
          'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
          'shaderReady': LiquidGlass.debugShaderReady,
          'buttonAtlasVariance': variance,
          'glyphTapBox': button.toString(),
          'progress': tester.widget<HeaderButtonGlassScope>(find.byType(HeaderButtonGlassScope)).progress.value,
          'panes': [if (capture != null) for (final pane in capture.panes) {
            'pane': pane.pane.toString(), 'slot': pane.slot.toString(),
            'place': pane.place.toString(), 'scale': pane.scale,
            'wallpaper': pane.wallpaper, 'level': pane.level,
          }],
        }));
      }
    } finally {
      image.dispose();
      atlas?.dispose();
    }
  });
}

Widget _app(ScrollController controller, VoidCallback action, VoidCallback back, {
  bool glass = true, bool contrast = false, bool sheet = false, bool active = true,
  bool disableAnimations = false, bool search = false, Widget? body,
}) => TestApp(
  theme: ThemeData(platform: TargetPlatform.android),
  child: MediaQuery(
    data: MediaQueryData(highContrast: contrast, disableAnimations: disableAnimations),
    child: ViewScope(
      size: const Size(390, 800),
      isMobile: true,
      child: PageActivityScope(
        isActive: active,
        child: LiquidGlassScope(
          enabled: glass && !contrast,
          child: RepaintBoundary(
            key: _scene,
            child: LiquidGlassHost(
              enabled: glass && !contrast,
              wallpaperOnly: true,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  const LiquidGlassColorField(child: LiquidGlassColorSource(
                    child: CustomPaint(painter: _Stripes()),
                  )),
                  SheetProvider(
                    type: sheet ? SheetType.bottomSheet : SheetType.page,
                    onClose: back,
                    child: CommonScaffold(
                      title: 'Header',
                      searchState: search ? AppBarSearchState(onSearch: (_) {}) : null,
                      backgroundColor: Colors.transparent,
                      backAction: back,
                      selectionActions: [
                        IconButtonData(glyph: AppGlyphs.check, tooltip: 'Action A', onPressed: action, isSelected: true),
                        IconButtonData(glyph: AppGlyphs.sync, tooltip: 'Action B', onPressed: action),
                      ],
                      iconActions: [for (var index = 0; index < 4; index++)
                        IconButtonData(glyph: AppGlyphs.openExternal, tooltip: 'Extra $index', onPressed: action),
                      ],
                      body: body ?? ListView.builder(
                        controller: controller,
                        itemCount: 40,
                        itemExtent: 64,
                        itemBuilder: (_, index) => GlassText('Context row $index'),
                      ),
                    ),
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

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag');
      await GlassInk.prepare();
    }
  });
  for (final sheet in [false, true]) {
    testWidgets('header backgrounds fade without moving controls sheet=$sheet', (tester) async {
      tester.view.physicalSize = const Size(390, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ScrollController();
      var taps = 0;
      var backs = 0;
      try {
        await tester.pumpWidget(_app(controller, () => taps++, () => backs++, sheet: sheet));
        await tester.pumpAndSettle();
        expect(_headerPanes, findsNothing);
        final button = find.byTooltip('Action A');
        final rect = tester.getRect(button);
        expect(rect.size, const Size.square(44));
        final selected = tester.widget<IconButton>(find.ancestor(
          of: button, matching: find.byType(IconButton),
        ));
        expect(selected.style!.foregroundColor!.resolve({WidgetState.selected}),
          Theme.of(tester.element(button)).colorScheme.primary);
        await tester.longPress(button);
        await tester.pump();
        expect(find.text('Action A'), findsOneWidget);
        await tester.pump(const Duration(seconds: 2));
        await tester.tap(button);
        expect(taps, 1);
        await _shot(tester, '$sheet-top');
        await tester.drag(find.byType(ListView), const Offset(0, -80));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 80));
        expect(_headerPanes, findsWidgets);
        final fades = tester.widgetList<LiquidGlassFade>(find.descendant(
          of: find.byType(HeaderButtonGlassScope), matching: find.byType(LiquidGlassFade),
        ));
        expect(fades.any((fade) => fade.opacity.value > 0 && fade.opacity.value < 1), isTrue);
        expect(tester.getRect(button), rect);
        await _shot(tester, '$sheet-revealing');
        if (ui.ImageFilter.isShaderFilterSupported) {
          await tester.runAsync(() => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'));
        }
        await tester.pumpAndSettle();
        if (ui.ImageFilter.isShaderFilterSupported) expect(LiquidGlass.debugShaderReady, isTrue);
        expect(find.byType(TonalButtonGroup), findsWidgets);
        await tester.tap(find.byTooltip('Action B'));
        expect(taps, 2);
        expect(tester.getRect(button), rect);
        await _shot(tester, '$sheet-under');
        await tester.tap(find.byTooltip('More'));
        await tester.pumpAndSettle();
        expect(find.text('Extra 3'), findsOneWidget);
        await tester.tap(find.text('Extra 3'));
        await tester.pumpAndSettle();
        expect(taps, 3);
        controller.jumpTo(0);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 80));
        expect(_headerPanes, findsWidgets);
        await tester.pumpAndSettle();
        expect(_headerPanes, findsNothing);
        expect(tester.getRect(button), rect);
        await _shot(tester, '$sheet-restored');
        final pop = sheet ? find.byTooltip('Close') : find.byType(BackButton);
        await tester.tap(pop);
        expect(backs, 1);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        controller.dispose();
      }
    });
  }

  testWidgets('reduced motion reveals the background without a transition', (tester) async {
    final controller = ScrollController();
    try {
      await tester.pumpWidget(_app(controller, () {}, () {}, disableAnimations: true, search: true));
      await tester.pumpAndSettle();
      final rect = tester.getRect(find.byTooltip('Action A'));
      controller.jumpTo(80);
      await tester.pump();
      await tester.pump();
      final fades = tester.widgetList<LiquidGlassFade>(find.descendant(
        of: find.byType(HeaderButtonGlassScope), matching: find.byType(LiquidGlassFade),
      ));
      expect(fades, isNotEmpty);
      expect(fades.every((fade) => fade.opacity.value == 1), isTrue);
      expect(tester.getRect(find.byTooltip('Action A')), rect);
      controller.jumpTo(0);
      await tester.pump();
      await tester.pump();
      expect(_headerPanes, findsNothing);
      await tester.tap(find.byTooltip('Search'));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsOneWidget);
      expect(_headerPanes, findsNothing);
      await tester.enterText(find.byType(TextField), 'query');
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      controller.dispose();
    }
  });

  testWidgets('nested vertical viewports cannot change the outer header background', (tester) async {
    final outer = ScrollController();
    final inner = ScrollController();
    final count = ValueNotifier(30);
    double progress() => tester.widget<HeaderButtonGlassScope>(
      find.byType(HeaderButtonGlassScope),
    ).progress.value;
    try {
      await tester.pumpWidget(_app(outer, () {}, () {}, body: ListView(
        controller: outer,
        children: [
          SizedBox(
            height: 320,
            child: ValueListenableBuilder<int>(
              valueListenable: count,
              builder: (_, value, _) => ListView.builder(
                controller: inner,
                itemCount: value,
                itemExtent: 40,
                itemBuilder: (_, index) => GlassText('Nested row $index'),
              ),
            ),
          ),
          const SizedBox(height: 1200),
        ],
      )));
      await tester.pumpAndSettle();
      inner.jumpTo(80);
      await tester.pumpAndSettle();
      expect(progress(), 0);
      expect(_headerPanes, findsNothing);
      outer.jumpTo(80);
      await tester.pumpAndSettle();
      expect(progress(), 1);
      expect(_headerPanes, findsWidgets);
      inner.jumpTo(0);
      await tester.pumpAndSettle();
      expect(progress(), 1);
      expect(_headerPanes, findsWidgets);
      count.value++;
      await tester.pumpAndSettle();
      expect(progress(), 1);
      expect(_headerPanes, findsWidgets);
      outer.jumpTo(0);
      await tester.pumpAndSettle();
      expect(progress(), 0);
      expect(_headerPanes, findsNothing);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      outer.dispose();
      inner.dispose();
      count.dispose();
    }
  });
  testWidgets('None high contrast and inactive pages do not gain scroll glass', (tester) async {
    for (final mode in [(false, false, true), (true, true, true), (true, false, false)]) {
      final controller = ScrollController();
      try {
        await tester.pumpWidget(_app(controller, () {}, () {}, glass: mode.$1, contrast: mode.$2, active: mode.$3));
        await tester.pumpAndSettle();
        controller.jumpTo(80);
        await tester.pumpAndSettle();
        expect(_headerPanes, findsNothing);
        expect(find.byTooltip('Action A'), findsOneWidget);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        controller.dispose();
      }
    }
  });
}
