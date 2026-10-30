import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/popup.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _style = LiquidGlassStyle(
  tint: Color(0x330000FF),
  blur: 0,
  refraction: 0,
  bevel: 0,
  rim: 0,
);

Widget _pane(Rect rect) => Positioned.fromRect(
  rect: rect,
  child: const LiquidGlass(
    style: _style,
    clipper: ShapeBorderClipper(shape: AppShape.none),
  ),
);

Widget _app(Widget home, LiquidGlassQuality quality) => MaterialApp(
  builder: (context, child) => wrapAppScopes(
    context,
    LiquidGlassScope(
      enabled: true,
      quality: quality,
      child: LiquidGlassHost(
        enabled: true,
        child: ColoredBox(color: Colors.green, child: child!),
      ),
    ),
  ),
  home: home,
);

const _flatScene = ValueKey('flat-modal-scene');
const _clearGlass = LiquidGlassStyle(
  tint: Colors.transparent,
  blur: 0,
  refraction: 0,
  bevel: 0,
  rim: 0,
);

Future<List<int>> _pixels(WidgetTester tester, String tag) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_flatScene),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/modal-flat-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
      }
      return (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List().toList();
    } finally {
      image.dispose();
    }
  }))!;
}

Widget _flatApp(Widget child, LiquidGlassQuality quality, Color color) =>
    MaterialApp(
      home: RepaintBoundary(
        key: _flatScene,
        child: LiquidGlassScope(
          enabled: true,
          quality: quality,
          child: LiquidGlassHost(
            enabled: true,
            wallpaperOnly: true,
            child: Stack(
              fit: StackFit.expand,
              children: [
                LiquidGlassColorField(
                  child: LiquidGlassColorSource(
                    child: ColoredBox(color: color),
                  ),
                ),
                child,
              ],
            ),
          ),
        ),
      ),
    );

class _MutableOutlineClipper extends CustomClipper<Path> {
  _MutableOutlineClipper(this.path, Listenable reclip) : super(reclip: reclip);

  final Path path;

  @override
  Path getClip(Size size) => path;

  @override
  bool shouldReclip(_MutableOutlineClipper oldClipper) => false;
}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare().timeout(const Duration(seconds: 30));
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });

  testWidgets(
    'opening a real popup marks its surface and keeps menu action live',
    (tester) async {
      late PopupOpen open;
      var selected = false;
      await tester.pumpWidget(
        _app(
          Stack(
            children: [
              Positioned.fill(child: LiquidGlass(style: _style)),
              Center(
                child: CommonPopupBox(
                  targetBuilder: (value) {
                    open = value;
                    return const SizedBox(width: 40, height: 40);
                  },
                  popupBuilder: (_) => CommonPopupMenu(
                    items: [
                      CommonPopupMenuItem(
                        label: 'Choose',
                        onPressed: () => selected = true,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
          LiquidGlassQuality.standard,
        ),
      );
      open();
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugLastCapture!.clippedPanes, greaterThan(0));
      await tester.tap(find.text('Choose', findRichText: true));
      await tester.pumpAndSettle();
      expect(selected, isTrue);
      expect(find.byType(PopupMenuCard), findsNothing);
      expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 0);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );

  for (final quality in LiquidGlassQuality.values) {
    testWidgets(
      'actual modal glass retains underlay ink without stacking glass $quality',
      (tester) async {
        tester.view.physicalSize = const Size(360, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        Future<void> render({
          required bool overlay,
          required bool lowerGlass,
          Color color = Colors.blue,
        }) async {
          await tester.pumpWidget(
            _flatApp(
              Stack(
                fit: StackFit.expand,
                children: [
                  if (lowerGlass)
                    const Positioned.fill(
                      child: LiquidGlass(
                        style: LiquidGlassStyle(
                          tint: Color(0x88FF0000),
                          blur: 0,
                          refraction: 0,
                          bevel: 0,
                          rim: 0,
                        ),
                        clipper: ShapeBorderClipper(shape: AppShape.none),
                      ),
                    ),
                  const Positioned(
                    left: 24,
                    top: 64,
                    width: 140,
                    child: GlassText(
                      'HHHH',
                      inkRole: GlassInkRole.body,
                      style: TextStyle(fontSize: 28, color: Colors.white),
                    ),
                  ),
                  const Positioned(
                    left: 200,
                    top: 150,
                    width: 30,
                    height: 30,
                    child: ColoredBox(color: Colors.yellow),
                  ),
                  if (overlay)
                    const Positioned.fill(
                      child: LiquidGlassOcclusion(
                        shape: AppShape.none,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            LiquidGlass(
                              style: _clearGlass,
                              clipper: ShapeBorderClipper(shape: AppShape.none),
                            ),
                            Positioned(
                              left: 280,
                              top: 40,
                              width: 20,
                              height: 20,
                              child: ColoredBox(color: Colors.purple),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
              quality,
              color,
            ),
          );
          await tester.pump();
          await tester.pump();
        }

        await render(overlay: true, lowerGlass: false);
        final oracle = await _pixels(tester, 'oracle-$quality');
        await render(overlay: false, lowerGlass: true);
        await render(overlay: true, lowerGlass: true);
        final actual = await _pixels(tester, 'overlay-$quality');
        var glyphPixels = 0;
        for (var y = 64; y < 100; y++) {
          for (var x = 24; x < 164; x++) {
            final index = (y * 360 + x) * 4;
            if (oracle[index] > 30) glyphPixels++;
            for (var channel = 0; channel < 4; channel++) {
              expect(
                actual[index + channel],
                closeTo(oracle[index + channel], 2),
                reason: 'glyph $x,$y channel $channel',
              );
            }
          }
        }
        expect(glyphPixels, greaterThan(100));
        final control = (160 * 360 + 210) * 4;
        const controlColor = Colors.yellow;
        expect(actual.sublist(control, control + 4), [
          (controlColor.r * 255).round(),
          (controlColor.g * 255).round(),
          (controlColor.b * 255).round(),
          (controlColor.a * 255).round(),
        ]);
        final own = (50 * 360 + 290) * 4;
        expect(actual.sublist(own, own + 4), [156, 39, 176, 255]);
        final capture = LiquidGlassHost.debugLastCapture!;
        expect(capture.occludedPanes, 1);
        expect(capture.replayedPanes, 0);
        expect(capture.panes, hasLength(1));
        expect(capture.panes.single.level, 0);
        if (ui.ImageFilter.isShaderFilterSupported) {
          expect(LiquidGlassHost.debugLastInkCapture!.frozenMasks, 1);
        }
        expect(LiquidGlassHost.debugLastFlatCapture!.groups, 1);
        expect(
          LiquidGlassHost.debugLastFlatCapture!.pixels,
          lessThanOrEqualTo(21600),
        );
        await render(overlay: true, lowerGlass: true, color: Colors.green);
        expect(LiquidGlassHost.debugLastFlatCapture!.snapshots, 0);
        expect(LiquidGlassHost.debugLastFlatCapture!.kept, 1);
        await render(overlay: false, lowerGlass: true);
        expect(LiquidGlassHost.debugLastFlatCapture!.groups, 0);
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      },
    );

    testWidgets(
      'covered pane keeps its appearance and reveals current field $quality',
      (tester) async {
        tester.view.physicalSize = const Size(360, 240);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        Future<void> render({
          bool cover = false,
          bool partial = false,
          Color color = Colors.blue,
        }) async {
          await tester.pumpWidget(
            _flatApp(
              Stack(
                children: [
                  const Positioned(
                    left: 40,
                    top: 40,
                    width: 160,
                    height: 120,
                    child: LiquidGlass(
                      style: LiquidGlassStyle(
                        tint: Color(0x33FF0000),
                        blur: 0,
                        refraction: 0,
                        bevel: 0,
                        rim: 0.6,
                        rimWidth: 2,
                        hairline: 0.3,
                      ),
                      clipper: ShapeBorderClipper(shape: AppShape.xl),
                    ),
                  ),
                  if (cover)
                    Positioned(
                      left: 0,
                      top: 0,
                      width: partial ? 120 : 360,
                      height: 240,
                      child: const LiquidGlassOcclusion(
                        shape: AppShape.none,
                        child: ColoredBox(color: Colors.transparent),
                      ),
                    ),
                ],
              ),
              quality,
              color,
            ),
          );
          await tester.pump();
          await tester.pump();
        }

        await render();
        final baseline = await _pixels(tester, 'pane-baseline-$quality');
        await render(cover: true);
        final coveredBaseline = await _pixels(tester, 'pane-covered-$quality');
        expect(LiquidGlassHost.debugLastCapture!.panes, isEmpty);
        await render(cover: true, color: Colors.green);
        final frozen = await _pixels(tester, 'pane-frozen-$quality');
        for (var y = 64; y < 136; y++) {
          for (var x = 64; x < 176; x++) {
            final index = (y * 360 + x) * 4;
            for (var channel = 0; channel < 4; channel++) {
              expect(
                frozen[index + channel],
                closeTo(baseline[index + channel], 2),
              );
            }
          }
        }
        for (final point in [const Offset(100, 41), const Offset(41, 100)]) {
          final index = (point.dy.toInt() * 360 + point.dx.toInt()) * 4;
          for (var channel = 0; channel < 4; channel++) {
            expect(
              frozen[index + channel],
              closeTo(coveredBaseline[index + channel], 2),
              reason: 'frozen rim $point',
            );
          }
        }
        await render(cover: true, partial: true, color: Colors.green);
        final partial = await _pixels(tester, 'pane-partial-$quality');
        final hidden = (100 * 360 + 80) * 4;
        final visible = (100 * 360 + 160) * 4;
        expect(
          partial.sublist(hidden, hidden + 4),
          frozen.sublist(hidden, hidden + 4),
        );
        expect(
          partial.sublist(visible, visible + 4),
          isNot(baseline.sublist(visible, visible + 4)),
        );
        expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 1);
        await render(color: Colors.green);
        final revealed = await _pixels(tester, 'pane-revealed-$quality');
        expect(
          revealed.sublist(hidden, hidden + 4),
          isNot(frozen.sublist(hidden, hidden + 4)),
        );
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        await render(cover: true);
        final firstCovered = await _pixels(
          tester,
          'pane-first-covered-$quality',
        );
        expect(
          firstCovered.sublist(hidden, hidden + 4),
          coveredBaseline.sublist(hidden, hidden + 4),
        );
        expect(LiquidGlassHost.debugLastCapture!.panes, isEmpty);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      },
    );

    testWidgets('covered outline identity invalidates frozen edges $quality', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(360, 240);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final reclip = ChangeNotifier();
      addTearDown(reclip.dispose);
      final path = AppShape.xl.getOuterPath(
        const Rect.fromLTWH(0, 0, 160, 120),
      );
      final clipper = _MutableOutlineClipper(path, reclip);
      Future<void> render({bool cover = false, bool partial = false}) async {
        await tester.pumpWidget(
          _flatApp(
            Stack(
              children: [
                Positioned(
                  left: 40,
                  top: 40,
                  width: 160,
                  height: 120,
                  child: LiquidGlass(
                    style: const LiquidGlassStyle(
                      tint: Color(0x88FF0000),
                      blur: 0,
                      refraction: 0,
                      bevel: 0,
                      rim: 0,
                    ),
                    clipper: clipper,
                  ),
                ),
                if (cover)
                  Positioned(
                    left: 0,
                    top: 0,
                    width: partial ? 160 : 360,
                    height: 240,
                    child: const LiquidGlassOcclusion(
                      shape: AppShape.none,
                      child: ColoredBox(color: Colors.transparent),
                    ),
                  ),
              ],
            ),
            quality,
            Colors.blue,
          ),
        );
        await tester.pump();
        await tester.pump();
      }

      await render();
      await render(cover: true);
      final old = await _pixels(tester, 'outline-old-$quality');
      final removed = (100 * 360 + 80) * 4;
      expect(old.sublist(removed, removed + 4), isNot([33, 150, 243, 255]));
      path
        ..reset()
        ..addPath(
          AppShape.xl.getOuterPath(const Rect.fromLTWH(80, 0, 80, 120)),
          Offset.zero,
        );
      reclip.notifyListeners();
      await tester.pump();
      await tester.pump();
      await render(cover: true, partial: true);
      final revealed = await _pixels(tester, 'outline-partial-$quality');
      expect(
        revealed.sublist(removed, removed + 4),
        [33, 150, 243, 255],
        reason: 'the former left outline cannot survive under the cached cover',
      );
      final remaining = (100 * 360 + 180) * 4;
      expect(
        revealed.sublist(remaining, remaining + 4),
        isNot([33, 150, 243, 255]),
      );
      await render();
      final oracle = await _pixels(tester, 'outline-oracle-$quality');
      expect(
        revealed.sublist(removed, removed + 4),
        oracle.sublist(removed, removed + 4),
      );
      expect(
        revealed.sublist(remaining, remaining + 4),
        oracle.sublist(remaining, remaining + 4),
      );
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    });

    testWidgets(
      'menu coverage follows painted card and excludes its children $quality',
      (tester) async {
        tester.view.physicalSize = const Size(400, 500);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        Future<void> mount({
          double grow = 1,
          bool bare = false,
          bool glass = true,
        }) async {
          await tester.pumpWidget(
            _app(
              RepaintBoundary(
                key: const ValueKey('popup-occlusion-scene'),
                child: Stack(
                  children: [
                    _pane(const Rect.fromLTWH(0, 0, 400, 500)),
                    _pane(const Rect.fromLTWH(140, 260, 40, 20)),
                    _pane(const Rect.fromLTWH(100, 100, 2, 2)),
                    Positioned(
                      left: 100,
                      top: 100,
                      width: 200,
                      height: 200,
                      child: PopupMenuCard(
                        grow: grow,
                        origin: (top: 0, height: 40),
                        shadows: const [],
                        fill: Colors.white,
                        dim: Colors.transparent,
                        glass: glass ? _style : null,
                        bare: bare,
                        child: Stack(
                          children: [
                            const Positioned(
                              left: 80,
                              top: 80,
                              width: 20,
                              height: 20,
                              child: ColoredBox(color: Colors.yellow),
                            ),
                            _pane(const Rect.fromLTWH(40, 20, 40, 20)),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              quality,
            ),
          );
          await tester.pump();
        }

        await mount();
        final full = LiquidGlassHost.debugLastCapture!;
        expect(full.occludedPanes, 1);
        expect(full.clippedPanes, 1);
        expect(
          full.panes.any(
            (pane) => pane.pane == const Rect.fromLTWH(140, 120, 40, 20),
          ),
          isTrue,
        );
        expect(
          full.panes.any(
            (pane) => pane.pane == const Rect.fromLTWH(100, 100, 2, 2),
          ),
          isTrue,
        );

        final boundary = tester.renderObject<RenderRepaintBoundary>(
          find.byKey(const ValueKey('popup-occlusion-scene')),
        );
        await tester.runAsync(() async {
          final image = await boundary.toImage();
          try {
            final rgba = (await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            ))!;
            final index = (190 * image.width + 190) * 4;
            const color = Colors.yellow;
            expect(rgba.buffer.asUint8List().sublist(index, index + 4), [
              (color.r * 255).round(),
              (color.g * 255).round(),
              (color.b * 255).round(),
              (color.a * 255).round(),
            ]);
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              Directory(output).createSync(recursive: true);
              final png = (await image.toByteData(
                format: ui.ImageByteFormat.png,
              ))!;
              File(
                '$output/popup-occlusion-$quality.png',
              ).writeAsBytesSync(png.buffer.asUint8List());
            }
          } finally {
            image.dispose();
          }
        });

        await mount(grow: 0.5);
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
        expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 1);

        await mount(bare: true);
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
        expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 0);

        await mount(glass: false);
        expect(LiquidGlassHost.debugLastCapture!.occludedPanes, 0);
        expect(LiquidGlassHost.debugLastCapture!.clippedPanes, 0);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      },
    );
  }
}
