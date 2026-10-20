import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/header_blur.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart' show SeededAuroraWallpaper;
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _scene = ValueKey('header-button-scene');

String get _backend => Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true'
    ? 'impeller'
    : 'default';

class _Stripes extends CustomPainter {
  const _Stripes({
    this.first = const Color(0xFF3050B0),
    this.second = const Color(0xFFE8D8A0),
  });
  final Color first;
  final Color second;
  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()..color = (x ~/ 8).isEven ? first : second,
      );
    }
  }

  @override
  bool shouldRepaint(_Stripes oldDelegate) =>
      first != oldDelegate.first || second != oldDelegate.second;
}

Finder get _headerPanes => find.descendant(
  of: find.byType(HeaderButtonGlassScope),
  matching: find.byType(LiquidGlass),
);

Future<void> _shot(WidgetTester tester, String name) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_scene),
  );
  if (boundary.debugNeedsPaint) {
    tester.binding.scheduleForcedFrame();
    await tester.pump();
  }
  final button = tester.getRect(find.byTooltip('Action A'));
  final headerContext = tester.element(find.byTooltip('Action A'));
  final lens = LiquidGlassStyle.lens(Theme.of(headerContext).colorScheme);
  for (final pane in tester.widgetList<LiquidGlass>(_headerPanes)) {
    expect(pane.style, lens);
    expect(pane.contentBackdrop, isTrue);
  }
  await tester.runAsync(() async {
    final image = await boundary.toImage();
    final capture = LiquidGlassHost.debugLastCapture;
    final matches = capture?.panes
        .where((pane) => pane.pane.contains(button.center))
        .toList();
    matches?.sort(
      (a, b) => (a.pane.width * a.pane.height).compareTo(
        b.pane.width * b.pane.height,
      ),
    );
    final selected = matches == null || matches.isEmpty ? null : matches.first;
    final atlas = selected?.atlas.clone();
    try {
      double? variance;
      if (atlas != null && selected != null) {
        final rgba = (await atlas.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!;
        var sum = 0.0;
        var squared = 0.0;
        var count = 0;
        for (var y = button.top + 8; y < button.bottom - 8; y++) {
          for (var x = button.left + 8; x < button.right - 8; x++) {
            final ax =
                (selected.place.left +
                        (x - selected.slot.left) * selected.scale)
                    .floor();
            final ay =
                (selected.place.top + (y - selected.slot.top) * selected.scale)
                    .floor();
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
        if (selected == null) {
          fail('the scrolled header has no captured button pane');
        }
        expect(selected.wallpaper, isFalse);
        expect(
          variance,
          greaterThan(1000),
          reason:
              'header buttons sample sharp contextual stripes, not HeaderBlur output',
        );
      }
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/header-buttons-$_backend-$name.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
        if (atlas != null) {
          final png = (await atlas.toByteData(format: ui.ImageByteFormat.png))!;
          File(
            '$output/header-buttons-$_backend-$name-atlas.png',
          ).writeAsBytesSync(png.buffer.asUint8List());
        }
        File('$output/header-buttons-$_backend-$name.json').writeAsStringSync(
          jsonEncode({
            'backendRequested': _backend,
            'brightness': Theme.of(
              tester.element(find.byTooltip('Action A')),
            ).brightness.name,
            'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
            'shaderReady': LiquidGlass.debugShaderReady,
            'buttonAtlasVariance': variance,
            'buttonStyle': {
              'blur': lens.blur,
              'haze': lens.haze,
              'refraction': lens.refraction,
              'bevel': lens.bevel,
              'dispersion': lens.dispersion,
            },
            'glyphTapBox': button.toString(),
            'progress': tester
                .widget<HeaderButtonGlassScope>(
                  find.byType(HeaderButtonGlassScope),
                )
                .progress
                .value,
            'panes': [
              if (capture != null)
                for (final pane in capture.panes)
                  {
                    'pane': pane.pane.toString(),
                    'slot': pane.slot.toString(),
                    'place': pane.place.toString(),
                    'scale': pane.scale,
                    'wallpaper': pane.wallpaper,
                    'level': pane.level,
                  },
            ],
          }),
        );
      }
    } finally {
      image.dispose();
      atlas?.dispose();
    }
  });
}

Widget _app(
  ScrollController controller,
  VoidCallback action,
  VoidCallback back, {
  Brightness brightness = Brightness.light,
  bool glass = true,
  bool contrast = false,
  bool sheet = false,
  bool active = true,
  bool disableAnimations = false,
  bool search = false,
  bool disableSecondaryAction = false,
  LiquidGlassQuality quality = LiquidGlassQuality.ultra,
  Widget? body,
  Widget? backdrop,
}) => TestApp(
  theme: ThemeData(brightness: brightness, platform: TargetPlatform.android),
  child: MediaQuery(
    data: MediaQueryData(
      highContrast: contrast,
      disableAnimations: disableAnimations,
    ),
    child: ViewScope(
      size: const Size(390, 800),
      isMobile: true,
      child: PageActivityScope(
        isActive: active,
        child: LiquidGlassScope(
          enabled: glass && !contrast,
          quality: quality,
          child: RepaintBoundary(
            key: _scene,
            child: LiquidGlassHost(
              enabled: glass && !contrast,
              wallpaperOnly: true,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  backdrop ??
                      const LiquidGlassColorField(
                        child: LiquidGlassColorSource(
                          child: CustomPaint(painter: _Stripes()),
                        ),
                      ),
                  Material(
                    color: Colors.transparent,
                    child: SheetProvider(
                      type: sheet ? SheetType.bottomSheet : SheetType.page,
                      onClose: back,
                      child: CommonScaffold(
                        title: 'Header',
                        searchState: search
                            ? AppBarSearchState(onSearch: (_) {})
                            : null,
                        backgroundColor: glass ? Colors.transparent : null,
                        backAction: back,
                        selectionActions: [
                          IconButtonData(
                            glyph: AppGlyphs.check,
                            tooltip: 'Action A',
                            onPressed: action,
                            isSelected: true,
                          ),
                          IconButtonData(
                            glyph: AppGlyphs.sync,
                            tooltip: 'Action B',
                            onPressed: disableSecondaryAction ? null : action,
                          ),
                        ],
                        iconActions: [
                          for (var index = 0; index < 4; index++)
                            IconButtonData(
                              glyph: AppGlyphs.openExternal,
                              tooltip: 'Extra $index',
                              onPressed: action,
                            ),
                        ],
                        body:
                            body ??
                            ListView.builder(
                              controller: controller,
                              itemCount: 40,
                              itemExtent: 64,
                              itemBuilder: (_, index) =>
                                  GlassText('Context row $index'),
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
  for (final brightness in Brightness.values) {
    for (final gradient in [false, true]) {
      testWidgets('None header reveals a plain surface only after scroll '
          '${brightness.name} gradient=$gradient', (tester) async {
        tester.view.physicalSize = const Size(390, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final controller = ScrollController();
        var taps = 0;
        Finder backgrounds() => find.descendant(
          of: find.byType(HeaderButtonGlassScope),
          matching: find.byWidgetPredicate(
            (widget) =>
                widget is FadeTransition &&
                widget.child is DecoratedBox &&
                (widget.child! as DecoratedBox).decoration is ShapeDecoration,
          ),
        );
        Future<List<int>> pixel(String phase, Offset point) async {
          final image = tester
              .renderObject<RenderRepaintBoundary>(find.byKey(_scene))
              .toImageSync();
          return (await tester.runAsync(() async {
            try {
              final data = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              final index =
                  (point.dy.floor() * image.width + point.dx.floor()) * 4;
              final rgba = [
                for (var channel = 0; channel < 4; channel++)
                  data.getUint8(index + channel),
              ];
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                Directory(output).createSync(recursive: true);
                final prefix =
                    '$output/header-none-$_backend-'
                    '${brightness.name}-$gradient-$phase';
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File('$prefix.png').writeAsBytesSync(png.buffer.asUint8List());
                File('$prefix.json').writeAsStringSync(
                  jsonEncode({
                    'glassEnabled': false,
                    'gradientEnabled': gradient,
                    'sample': rgba,
                    'point': point.toString(),
                  }),
                );
              }
              return rgba;
            } finally {
              image.dispose();
            }
          }))!;
        }

        try {
          await tester.pumpWidget(
            _app(
              controller,
              () => taps++,
              () {},
              glass: false,
              brightness: brightness,
              disableSecondaryAction: true,
              backdrop: gradient
                  ? SeededAuroraWallpaper(
                      colors: const [
                        Color(0xFF65BDD2),
                        Color(0xFFC589CA),
                        Color(0xFF75CCA8),
                      ],
                      seed: 271828,
                      brightness: brightness,
                    )
                  : const ColoredBox(color: Color(0xFF476386)),
            ),
          );
          await tester.pumpAndSettle();
          final action = find.byTooltip('Action A');
          final rect = tester.getRect(action);
          expect(rect.size, const Size.square(44));
          final point = rect.center + const Offset(0, 16);
          expect(backgrounds(), findsNothing);
          expect(find.byType(LiquidGlass), findsNothing);
          final top = await pixel('top', point);
          await tester.tap(action);
          expect(taps, 1);
          await tester.tapAt(rect.center + const Offset(21, 0));
          expect(taps, 2);
          final disabled = tester.widget<IconButton>(
            find.ancestor(
              of: find.byTooltip('Action B'),
              matching: find.byType(IconButton),
            ),
          );
          expect(disabled.onPressed, isNull);
          final buttonTheme = IconButtonTheme.of(tester.element(action));
          final focusSide = buttonTheme.style!.side!.resolve({
            WidgetState.focused,
          });
          expect(focusSide, isNotNull);
          expect(focusSide!.width, greaterThan(0));
          controller.jumpTo(80);
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 80));
          expect(backgrounds(), findsWidgets);
          expect(
            tester
                .widgetList<FadeTransition>(backgrounds())
                .any(
                  (fade) => fade.opacity.value > 0 && fade.opacity.value < 1,
                ),
            isTrue,
          );
          await tester.pumpAndSettle();
          expect(find.byType(LiquidGlass), findsNothing);
          final context = tester.element(action);
          final surface = context.raisedSurfaceLevel.colorOf(
            Theme.of(context).colorScheme,
          );
          for (final fade in tester.widgetList<FadeTransition>(backgrounds())) {
            expect(fade.opacity.value, 1);
            expect(
              ((fade.child! as DecoratedBox).decoration as ShapeDecoration)
                  .color,
              surface,
            );
          }
          final under = await pixel('under', point);
          expect(
            under,
            [
              surface.r,
              surface.g,
              surface.b,
              surface.a,
            ].map((value) => (value * 255).round()).toList(),
          );
          expect(tester.getRect(action), rect);
          await tester.tap(action);
          expect(taps, 3);
          await tester.tap(find.byTooltip('More'));
          await tester.pumpAndSettle();
          await tester.tap(find.text('Extra 3'));
          await tester.pumpAndSettle();
          expect(taps, 4);
          controller.jumpTo(0);
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 80));
          expect(backgrounds(), findsWidgets);
          await tester.pumpAndSettle();
          expect(backgrounds(), findsNothing);
          expect(await pixel('restored', point), top);
          expect(tester.getRect(action), rect);
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
          controller.dispose();
        }
      });
    }
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('real header samples sharp body through HeaderBlur '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(390, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final controller = ScrollController();
        Future<List<int>> capture(String phase) async {
          final button = tester.getRect(find.byTooltip('Action A'));
          final panes =
              LiquidGlassHost.debugLastCapture!.panes
                  .where((pane) => pane.pane.contains(button.center))
                  .toList()
                ..sort(
                  (a, b) => (a.pane.width * a.pane.height).compareTo(
                    b.pane.width * b.pane.height,
                  ),
                );
          expect(panes, isNotEmpty);
          final pane = panes.first;
          expect(pane.wallpaper, isFalse);
          expect(pane.scale, 1);
          final atlas = pane.atlas.clone();
          final scene = tester
              .renderObject<RenderRepaintBoundary>(find.byKey(_scene))
              .toImageSync();
          return (await tester.runAsync(() async {
            try {
              final data = (await atlas.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              final samples = <int>[];
              for (
                var y = button.top.ceil() + 8;
                y < button.bottom.floor() - 8;
                y++
              ) {
                for (
                  var x = button.left.ceil() + 8;
                  x < button.right.floor() - 8;
                  x++
                ) {
                  final ax =
                      (pane.place.left + (x - pane.slot.left) * pane.scale)
                          .floor();
                  final ay = (pane.place.top + (y - pane.slot.top) * pane.scale)
                      .floor();
                  final index = (ay * atlas.width + ax) * 4;
                  samples.addAll([
                    for (var channel = 0; channel < 4; channel++)
                      data.getUint8(index + channel),
                  ]);
                }
              }
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                Directory(output).createSync(recursive: true);
                final prefix =
                    '$output/header-content-$_backend-'
                    '${brightness.name}-${quality.name}-$phase';
                for (final item in [(scene, ''), (atlas, '-atlas')]) {
                  final png = (await item.$1.toByteData(
                    format: ui.ImageByteFormat.png,
                  ))!;
                  File(
                    '$prefix${item.$2}.png',
                  ).writeAsBytesSync(png.buffer.asUint8List());
                }
                File('$prefix.json').writeAsStringSync(
                  jsonEncode({
                    'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
                    'shaderReady': LiquidGlass.debugShaderReady,
                    'wallpaper': pane.wallpaper,
                    'level': pane.level,
                    'scale': pane.scale,
                    'pane': pane.pane.toString(),
                    'slot': pane.slot.toString(),
                    'place': pane.place.toString(),
                    'bodySamples': samples,
                  }),
                );
              }
              return samples;
            } finally {
              atlas.dispose();
              scene.dispose();
            }
          }))!;
        }

        try {
          await tester.pumpWidget(
            _app(
              controller,
              () {},
              () {},
              brightness: brightness,
              quality: quality,
              body: CustomPaint(
                painter: const _Stripes(
                  first: Color(0xFF20B050),
                  second: Color(0xFFD030A0),
                ),
                child: ListView.builder(
                  controller: controller,
                  itemCount: 40,
                  itemExtent: 64,
                  itemBuilder: (_, index) => Align(
                    alignment: Alignment.bottomLeft,
                    child: GlassText('Body card $index'),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          expect(_headerPanes, findsNothing);
          controller.jumpTo(80);
          await tester.pumpAndSettle();
          if (ui.ImageFilter.isShaderFilterSupported) {
            await tester.runAsync(
              () => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
            );
            await tester.pumpAndSettle();
            expect(LiquidGlass.debugShaderReady, isTrue);
            final actual = await capture('blur-on');
            final repeat = await capture('blur-repeat');
            expect(actual, repeat);
            final filter = tester.renderObject<RenderBackdropFilter>(
              find.descendant(
                of: find.byType(HeaderBlur),
                matching: find.byType(BackdropFilter),
              ),
            );
            final original = filter.filter;
            try {
              filter.filter = ui.ImageFilter.blur(sigmaX: 0, sigmaY: 0);
              await tester.pump();
              final clear = await capture('blur-off');
              expect(
                actual,
                clear,
                reason: 'button source excludes the native header blur',
              );
              final pixels = <List<int>>[
                for (var i = 0; i < actual.length; i += 4)
                  actual.sublist(i, i + 4),
              ];
              expect(pixels, contains(equals([32, 176, 80, 255])));
              expect(pixels, contains(equals([208, 48, 160, 255])));
              expect(pixels, isNot(contains(equals([48, 80, 176, 255]))));
            } finally {
              filter.filter = original;
              await tester.pump();
            }
          }
          controller.jumpTo(0);
          await tester.pumpAndSettle();
          expect(_headerPanes, findsNothing);
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
          controller.dispose();
        }
      });
      testWidgets('header optics match the clear Dock lens '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(160, 120);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final progress = ValueNotifier(1.0);
        final scheme = ThemeData(brightness: brightness).colorScheme;
        final lens = LiquidGlassStyle.lens(scheme);
        final unbentLens = LiquidGlassStyle(
          tint: lens.tint,
          contrast: lens.contrast,
          blur: lens.blur,
          haze: lens.haze,
          hazeBlur: lens.hazeBlur,
          refraction: 0,
          bevel: lens.bevel,
          saturation: lens.saturation,
          brightness: lens.brightness,
          rim: lens.rim,
          rimWidth: lens.rimWidth,
          rimTail: lens.rimTail,
          rimFocus: lens.rimFocus,
          rimAdded: lens.rimAdded,
          endShade: lens.endShade,
          dispersion: lens.dispersion,
          rimBlur: lens.rimBlur,
          hairline: lens.hairline,
          hairlineSide: lens.hairlineSide,
        );
        Future<List<int>> render(String variant) async {
          await tester.pumpWidget(
            TestApp(
              theme: ThemeData(colorScheme: scheme),
              child: LiquidGlassScope(
                enabled: true,
                quality: quality,
                child: RepaintBoundary(
                  key: _scene,
                  child: LiquidGlassHost(
                    enabled: true,
                    wallpaperOnly: true,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        const LiquidGlassColorField(
                          child: LiquidGlassColorSource(
                            child: CustomPaint(painter: _Stripes()),
                          ),
                        ),
                        Center(
                          child: variant == 'header'
                              ? HeaderButtonGlassScope(
                                  progress: progress,
                                  child: const TonalButtonGroup(
                                    children: [SizedBox.square(dimension: 44)],
                                  ),
                                )
                              : LiquidGlass(
                                  contentBackdrop: true,
                                  style: variant == 'unbent'
                                      ? unbentLens
                                      : lens,
                                  child: const SizedBox.square(dimension: 44),
                                ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          if (ui.ImageFilter.isShaderFilterSupported &&
              !LiquidGlass.debugShaderReady) {
            await tester.runAsync(
              () => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
            );
            await tester.pumpAndSettle();
            expect(LiquidGlass.debugShaderReady, isTrue);
          }
          final pane = tester.widget<LiquidGlass>(find.byType(LiquidGlass));
          if (variant == 'header') {
            expect(pane.style, lens);
            expect(pane.style.blur, 0);
            expect(pane.style.haze, 0);
            expect(pane.contentBackdrop, isTrue);
          }
          final boundary = tester.renderObject<RenderRepaintBoundary>(
            find.byKey(_scene),
          );
          final image = boundary.toImageSync();
          return (await tester.runAsync(() async {
            try {
              final bytes = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!.buffer.asUint8List().toList();
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                Directory(output).createSync(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '$output/header-optics-$_backend-${brightness.name}-'
                  '${quality.name}-$variant.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return bytes;
            } finally {
              image.dispose();
            }
          }))!;
        }

        int delta(List<int> a, List<int> b) {
          var maximum = 0;
          for (var y = 41; y < 79; y++) {
            for (var x = 61; x < 99; x++) {
              for (var channel = 0; channel < 4; channel++) {
                final index = (y * 160 + x) * 4 + channel;
                final difference = (a[index] - b[index]).abs();
                if (difference > maximum) maximum = difference;
              }
            }
          }
          return maximum;
        }

        try {
          final actual = await render('header');
          final repeat = await render('header');
          final reference = await render('dock-lens');
          final unbent = await render('unbent');
          final noise = delta(actual, repeat);
          final residual = delta(actual, reference);
          final refraction = delta(actual, unbent);
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            File(
              '$output/header-optics-$_backend-${brightness.name}-'
              '${quality.name}.json',
            ).writeAsStringSync(
              jsonEncode({
                'shaderSupported': ui.ImageFilter.isShaderFilterSupported,
                'shaderReady': LiquidGlass.debugShaderReady,
                'repeatNoise': noise,
                'dockLensResidual': residual,
                'refractionDelta': refraction,
                'blur': lens.blur,
                'haze': lens.haze,
                'refraction': lens.refraction,
                'bevel': lens.bevel,
                'dispersion': lens.dispersion,
              }),
            );
          }
          expect(noise, 0);
          expect(residual, 0);
          if (ui.ImageFilter.isShaderFilterSupported) {
            expect(LiquidGlass.debugShaderReady, isTrue);
            expect(refraction, greaterThan(noise));
          }
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
          progress.dispose();
        }
      });
    }
    for (final sheet in [false, true]) {
      testWidgets(
        'header backgrounds fade without moving controls $brightness sheet=$sheet',
        (tester) async {
          tester.view.physicalSize = const Size(390, 800);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final controller = ScrollController();
          var taps = 0;
          var backs = 0;
          try {
            await tester.pumpWidget(
              _app(
                controller,
                () => taps++,
                () => backs++,
                sheet: sheet,
                brightness: brightness,
              ),
            );
            await tester.pumpAndSettle();
            expect(_headerPanes, findsNothing);
            final bodyContext = tester.element(find.text('Context row 0'));
            final bodyStyle = DefaultTextStyle.of(bodyContext).style;
            final materialBody = Theme.of(bodyContext).textTheme.bodyMedium!;
            expect(bodyStyle.fontSize, materialBody.fontSize);
            expect(bodyStyle.color, materialBody.color);
            expect(bodyStyle.decoration, isNot(TextDecoration.underline));
            final prefix = '${brightness.name}-${sheet ? 'sheet' : 'page'}';
            final button = find.byTooltip('Action A');
            final rect = tester.getRect(button);
            expect(rect.size, const Size.square(44));
            final selected = tester.widget<IconButton>(
              find.ancestor(of: button, matching: find.byType(IconButton)),
            );
            expect(
              selected.style!.foregroundColor!.resolve({WidgetState.selected}),
              Theme.of(tester.element(button)).colorScheme.primary,
            );
            await tester.longPress(button);
            await tester.pump();
            expect(find.text('Action A'), findsOneWidget);
            await tester.pump(const Duration(seconds: 2));
            await tester.tap(button);
            expect(taps, 1);
            await _shot(tester, '$prefix-top');
            await tester.drag(find.byType(ListView), const Offset(0, -80));
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 80));
            expect(_headerPanes, findsWidgets);
            final fades = tester.widgetList<LiquidGlassFade>(
              find.descendant(
                of: find.byType(HeaderButtonGlassScope),
                matching: find.byType(LiquidGlassFade),
              ),
            );
            expect(
              fades.any(
                (fade) => fade.opacity.value > 0 && fade.opacity.value < 1,
              ),
              isTrue,
            );
            expect(tester.getRect(button), rect);
            await _shot(tester, '$prefix-revealing');
            if (ui.ImageFilter.isShaderFilterSupported) {
              await tester.runAsync(
                () => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
              );
            }
            await tester.pumpAndSettle();
            if (ui.ImageFilter.isShaderFilterSupported) {
              expect(LiquidGlass.debugShaderReady, isTrue);
            }
            expect(find.byType(TonalButtonGroup), findsWidgets);
            await tester.tap(find.byTooltip('Action B'));
            expect(taps, 2);
            expect(tester.getRect(button), rect);
            await _shot(tester, '$prefix-under');
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
            await _shot(tester, '$prefix-restored');
            final pop = sheet
                ? find.byTooltip('Close')
                : find.byType(BackButton);
            await tester.tap(pop);
            expect(backs, 1);
            expect(tester.takeException(), isNull);
          } finally {
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pump();
            controller.dispose();
          }
        },
      );
    }
  }

  testWidgets('reduced motion reveals the background without a transition', (
    tester,
  ) async {
    final controller = ScrollController();
    try {
      await tester.pumpWidget(
        _app(controller, () {}, () {}, disableAnimations: true, search: true),
      );
      await tester.pumpAndSettle();
      final rect = tester.getRect(find.byTooltip('Action A'));
      controller.jumpTo(80);
      await tester.pump();
      await tester.pump();
      final fades = tester.widgetList<LiquidGlassFade>(
        find.descendant(
          of: find.byType(HeaderButtonGlassScope),
          matching: find.byType(LiquidGlassFade),
        ),
      );
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

  testWidgets(
    'nested vertical viewports cannot change the outer header background',
    (tester) async {
      final outer = ScrollController();
      final inner = ScrollController();
      final count = ValueNotifier(30);
      double progress() => tester
          .widget<HeaderButtonGlassScope>(find.byType(HeaderButtonGlassScope))
          .progress
          .value;
      try {
        await tester.pumpWidget(
          _app(
            outer,
            () {},
            () {},
            body: ListView(
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
            ),
          ),
        );
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
    },
  );
  testWidgets(
    'None high contrast and inactive pages do not gain scroll glass',
    (tester) async {
      for (final mode in [
        (false, false, true),
        (true, true, true),
        (true, false, false),
      ]) {
        final controller = ScrollController();
        try {
          await tester.pumpWidget(
            _app(
              controller,
              () {},
              () {},
              glass: mode.$1,
              contrast: mode.$2,
              active: mode.$3,
            ),
          );
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
    },
  );
}
