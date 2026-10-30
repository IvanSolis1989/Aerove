import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/ui/snap_sheet.dart';
import 'package:fl_clash/ui/sheet_surface.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _scene = ValueKey('occlusion-scene');
const _sheet = ValueKey('occlusion-sheet');
const _upper = Rect.fromLTWH(25, 45, 120, 80);
const _crossing = Rect.fromLTWH(0, 250, 400, 310);
const _lower = Rect.fromLTWH(30, 500, 120, 80);
const _nested = Rect.fromLTWH(40, 510, 100, 60);
const _glass = LiquidGlassStyle(
  tint: Colors.transparent,
  blur: 0,
  refraction: 0,
  bevel: 0,
  rim: 0,
);
const _redGlass = LiquidGlassStyle(
  tint: Color(0x66FF0000),
  blur: 0,
  refraction: 0,
  bevel: 0,
  rim: 0,
);

Widget _pane(Rect rect, {LiquidGlassStyle style = _redGlass}) =>
    Positioned.fromRect(
      rect: rect,
      child: LiquidGlass(
        style: style,
        clipper: const ShapeBorderClipper(shape: AppShape.none),
      ),
    );

List<Widget> _underlay() => [
  _pane(_upper),
  _pane(_crossing),
  _pane(_lower),
  _pane(_nested),
  const Positioned(
    left: 340,
    top: 610,
    width: 40,
    height: 40,
    child: ColoredBox(color: Colors.green),
  ),
];

Widget _app(
  Widget home,
  LiquidGlassQuality quality, {
  bool enabled = true,
  Brightness brightness = Brightness.light,
}) => MaterialApp(
  theme: ThemeData(
    brightness: brightness,
    platform: TargetPlatform.android,
  ).withAppShapes.withAppSurfaces,
  localizationsDelegates: const [
    AppLocalizations.delegate,
    AppUiLocalizations.delegate,
    ...GlobalMaterialLocalizations.delegates,
  ],
  supportedLocales: AppLocalizations.delegate.supportedLocales,
  builder: (context, child) => wrapAppScopes(
    context,
    RepaintBoundary(
      key: _scene,
      child: LiquidGlassScope(
        enabled: enabled,
        quality: quality,
        child: LiquidGlassHost(
          enabled: enabled,
          child: ColoredBox(color: Colors.blue, child: child!),
        ),
      ),
    ),
  ),
  home: home,
);

Future<({Uint8List bytes, int width})> _shot(
  WidgetTester tester,
  String tag,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_scene),
  );
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File(
          '$output/sheet-occlusion-$tag.png',
        ).writeAsBytesSync(png.buffer.asUint8List());
      }
      return (bytes: data.buffer.asUint8List(), width: image.width);
    } finally {
      image.dispose();
    }
  }))!;
}

Color _pixel(({Uint8List bytes, int width}) shot, int x, int y) {
  final offset = (y * shot.width + x) * 4;
  return Color.fromARGB(
    shot.bytes[offset + 3],
    shot.bytes[offset],
    shot.bytes[offset + 1],
    shot.bytes[offset + 2],
  );
}

Map<String, int> _metrics(LiquidGlassCapture capture) => {
  'panes': capture.panes.length,
  'occluded': capture.occludedPanes,
  'clipped': capture.clippedPanes,
  'replayed': capture.replayedPanes,
  'atlasPixels': capture.atlasPixels,
  'levels': capture.panes.fold(
    0,
    (max, pane) => pane.level >= max ? pane.level + 1 : max,
  ),
};

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      ).timeout(const Duration(seconds: 30));
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      for (final kind in ['bottom', 'snap', 'side', 'form']) {
        testWidgets(
          'sheet $kind has an undimmed dismissible barrier $brightness $quality',
          (tester) async {
            final mobile = kind == 'bottom' || kind == 'snap';
            final size = Size(mobile ? 400 : 800, 700);
            tester.view.physicalSize = size;
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.reset);
            var underlayTaps = 0;
            var actions = 0;
            Widget panel(BuildContext _) => SizedBox(
              height: 220,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('Sheet panel'),
                  TextButton(
                    onPressed: () => actions++,
                    child: const Text('Sheet action'),
                  ),
                ],
              ),
            );
            await tester.pumpWidget(
              _app(
                ViewScope(
                  size: size,
                  isMobile: mobile,
                  child: Navigator(
                    onGenerateRoute: (_) => MaterialPageRoute<void>(
                      builder: (context) => Scaffold(
                        backgroundColor: Colors.transparent,
                        body: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () => underlayTaps++,
                          child: Center(
                            child: TextButton(
                              onPressed: () {
                                switch (kind) {
                                  case 'bottom' || 'side':
                                    unawaited(
                                      showSheet<void>(
                                        context: context,
                                        builder: panel,
                                      ),
                                    );
                                  case 'snap':
                                    unawaited(
                                      showSnapSheet<void>(
                                        context,
                                        builder: (context, _) => panel(context),
                                      ),
                                    );
                                  case 'form':
                                    unawaited(
                                      showFormSheet<void>(
                                        context: context,
                                        builder: panel,
                                      ),
                                    );
                                }
                              },
                              child: const Text('Open sheet'),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                quality ?? LiquidGlassQuality.standard,
                enabled: quality != null,
                brightness: brightness,
              ),
            );
            await tester.pumpAndSettle();
            final before = await _shot(
              tester,
              '$kind-${brightness.name}-${quality?.name ?? 'none'}-barrier-before',
            );
            await tester.tap(find.text('Open sheet'));
            await tester.pumpAndSettle();
            final route = ModalRoute.of(
              tester.element(find.text('Sheet panel')),
            )!;
            expect(route.barrierColor?.a ?? 0, 0);
            expect(route.barrierDismissible, isTrue);
            expect(route.barrierLabel, isNotEmpty);
            final opened = await _shot(
              tester,
              '$kind-${brightness.name}-${quality?.name ?? 'none'}-barrier-open',
            );
            expect(_pixel(opened, 10, 10), _pixel(before, 10, 10));
            await tester.tap(find.text('Sheet action'));
            await tester.pumpAndSettle();
            expect(actions, 1);
            expect(find.text('Sheet panel'), findsOneWidget);
            final taps = underlayTaps;
            await tester.tapAt(const Offset(10, 10));
            await tester.pumpAndSettle();
            expect(find.text('Sheet panel'), findsNothing);
            expect(underlayTaps, taps);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }
  }

  for (final quality in LiquidGlassQuality.values) {
    testWidgets(
      'coverage preserves corners and reduces capture work $quality',
      (tester) async {
        tester.view.physicalSize = const Size(400, 700);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        Future<void> mount(bool covered) async {
          final shape = AppShape.top(AppCorner.xxl);
          final surface = ClipPath(
            clipper: ShapeBorderClipper(shape: shape),
            child: Stack(
              fit: StackFit.expand,
              children: [
                _pane(const Rect.fromLTWH(0, 0, 400, 400), style: _glass),
                _pane(const Rect.fromLTWH(180, 90, 150, 80)),
              ],
            ),
          );
          await tester.pumpWidget(
            _app(
              Scaffold(
                backgroundColor: Colors.transparent,
                body: Stack(
                  children: [
                    ..._underlay(),
                    Positioned(
                      top: 300,
                      left: 0,
                      width: 400,
                      height: 400,
                      child: covered
                          ? LiquidGlassOcclusion(shape: shape, child: surface)
                          : surface,
                    ),
                    _pane(const Rect.fromLTWH(250, 650, 120, 35)),
                  ],
                ),
              ),
              quality,
            ),
          );
          await tester.pump();
        }

        await mount(false);
        final original = await _shot(tester, '${quality.name}-baseline');
        final baseline = LiquidGlassHost.debugLastCapture!;
        await mount(true);
        final optimized = await _shot(tester, '${quality.name}-optimized');
        final reduced = LiquidGlassHost.debugLastCapture!;
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          File(
            '$output/sheet-occlusion-${quality.name}-metrics.json',
          ).writeAsStringSync(
            jsonEncode({
              'baseline': _metrics(baseline),
              'optimized': _metrics(reduced),
            }),
          );
        }
        expect(reduced.occludedPanes, 2);
        expect(reduced.clippedPanes, 1);
        expect(reduced.panes.length, baseline.panes.length - 2);
        expect(reduced.atlasPixels, lessThan(baseline.atlasPixels));
        expect(reduced.replayedPanes, lessThan(baseline.replayedPanes));
        expect(
          reduced.panes
              .map((pane) => pane.level)
              .reduce((a, b) => a > b ? a : b),
          lessThan(
            baseline.panes
                .map((pane) => pane.level)
                .reduce((a, b) => a > b ? a : b),
          ),
        );
        expect(reduced.panes.any((pane) => pane.pane == _upper), isTrue);
        expect(reduced.panes.any((pane) => pane.pane == _crossing), isTrue);
        expect(
          reduced.panes.any(
            (pane) => pane.pane == const Rect.fromLTWH(250, 650, 120, 35),
          ),
          isTrue,
          reason: 'later-painted glass remains above the sheet',
        );
        for (final point in [(70, 70), (2, 301), (397, 301), (200, 280)]) {
          expect(
            _pixel(optimized, point.$1, point.$2),
            _pixel(original, point.$1, point.$2),
            reason: 'uncovered glass at $point',
          );
        }
        expect(
          _pixel(optimized, 360, 630).toARGB32(),
          Colors.green.toARGB32(),
          reason: 'ordinary lower content remains available to sheet sampling',
        );
        expect(
          _pixel(optimized, 80, 535),
          _pixel(original, 80, 535),
          reason: 'the generic host keeps the cached lower glass appearance',
        );
        expect(_pixel(optimized, 80, 535), isNot(Colors.blue));
        await tester.pump();
        final stable = await _shot(tester, '${quality.name}-stable');
        expect(stable.bytes, optimized.bytes);
        expect(LiquidGlassHost.debugLastCapture!.snapshots, 0);
        await mount(false);
        final restored = await _shot(tester, '${quality.name}-restored');
        expect(
          LiquidGlassHost.debugLastCapture!.panes.length,
          baseline.panes.length,
        );
        expect(restored.bytes, original.bytes);
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets('real snap sheet tracks drag keyboard and close $quality', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(400, 700);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final navigator = GlobalKey<NavigatorState>();
      Future<void> open() async {
        unawaited(
          navigator.currentState!.push(
            SnapSheetRoute<void>(
              barrierLabel: 'Dismiss',
              detents: const [0.5, 0.85],
              builder: (_, controller) => ColoredBox(
                key: _sheet,
                color: Colors.transparent,
                child: ListView(
                  controller: controller,
                  children: [
                    const SizedBox(height: 60),
                    SizedBox(
                      height: 100,
                      child: LiquidGlass(
                        style: _redGlass,
                        clipper: const ShapeBorderClipper(shape: AppShape.xl),
                      ),
                    ),
                    const SizedBox(height: 800),
                  ],
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      await tester.pumpWidget(
        _app(
          Navigator(
            key: navigator,
            onGenerateRoute: (_) => MaterialPageRoute<void>(
              builder: (_) => Scaffold(
                backgroundColor: Colors.transparent,
                body: Stack(children: _underlay()),
              ),
            ),
          ),
          quality,
        ),
      );
      final before = await _shot(tester, '${quality.name}-route-before');
      final count = LiquidGlassHost.debugLastCapture!.panes.length;
      await open();
      expect(find.byType(LiquidGlassOcclusion), findsOneWidget);
      final opened = await _shot(tester, '${quality.name}-route-open');
      expect(
        LiquidGlassHost.debugLastCapture!.occludedPanes,
        greaterThanOrEqualTo(2),
      );
      expect(
        LiquidGlassHost.debugLastCapture!.clippedPanes,
        greaterThanOrEqualTo(1),
      );
      expect(
        LiquidGlassHost.debugLastCapture!.panes.any(
          (pane) => pane.pane == _upper,
        ),
        isTrue,
      );
      final top = tester.getTopLeft(find.byType(LiquidGlassOcclusion)).dy;
      for (final point in [
        (70, 70),
        (200, top.floor() - 20),
        (2, top.floor() + 1),
      ]) {
        final expected = _pixel(before, point.$1, point.$2);
        final actual = _pixel(opened, point.$1, point.$2);
        expect(actual.r, closeTo(expected.r, 2 / 255));
        expect(actual.g, closeTo(expected.g, 2 / 255));
        expect(actual.b, closeTo(expected.b, 2 / 255));
      }
      await open();
      await _shot(tester, '${quality.name}-nested');
      expect(find.byType(LiquidGlassOcclusion), findsNWidgets(2));
      navigator.currentState!.pop();
      await tester.pumpAndSettle();
      final unstacked = await _shot(tester, '${quality.name}-unstacked');
      expect(find.byType(LiquidGlassOcclusion), findsOneWidget);
      expect(unstacked.bytes, opened.bytes);
      final gesture = await tester.startGesture(Offset(200, top + 25));
      for (var frame = 0; frame < 4; frame++) {
        await gesture.moveBy(const Offset(0, -45));
        await tester.pump(const Duration(milliseconds: 16));
        await _shot(tester, '${quality.name}-drag-$frame');
        expect(
          LiquidGlassHost.debugLastCapture!.occludedPanes,
          greaterThanOrEqualTo(2),
        );
        expect(
          LiquidGlassHost.debugLastCapture!.panes.any(
            (pane) => pane.pane == _lower,
          ),
          isFalse,
        );
      }
      await gesture.up();
      await tester.pumpAndSettle();
      expect(
        tester.getTopLeft(find.byType(LiquidGlassOcclusion)).dy,
        lessThan(top),
      );
      tester.view.viewInsets = const FakeViewPadding(bottom: 140);
      await tester.pumpAndSettle();
      await _shot(tester, '${quality.name}-keyboard');
      expect(
        LiquidGlassHost.debugLastCapture!.occludedPanes,
        greaterThanOrEqualTo(2),
      );
      tester.view.resetViewInsets();
      await tester.pumpAndSettle();
      navigator.currentState!.pop();
      await tester.pumpAndSettle();
      final after = await _shot(tester, '${quality.name}-route-closed');
      expect(find.byType(LiquidGlassOcclusion), findsNothing);
      expect(LiquidGlassHost.debugLastCapture!.panes.length, count);
      expect(after.bytes, before.bytes);
      await open();
      final reopened = tester.getTopLeft(find.byType(LiquidGlassOcclusion)).dy;
      await tester.flingFrom(
        Offset(200, reopened + 25),
        const Offset(0, 550),
        1400,
      );
      await tester.pumpAndSettle();
      await _shot(tester, '${quality.name}-drag-dismissed');
      expect(find.byType(LiquidGlassOcclusion), findsNothing);
      expect(LiquidGlassHost.debugLastCapture!.panes.length, count);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('a non-glass sheet does not occlude the underlay', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(
        Builder(
          builder: (context) => TextButton(
            onPressed: () => Navigator.of(context).push(
              SnapSheetRoute<void>(
                barrierLabel: 'Dismiss',
                builder: (_, _) => const ColoredBox(color: Colors.orange),
              ),
            ),
            child: const Text('open'),
          ),
        ),
        LiquidGlassQuality.ultra,
        enabled: false,
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.byType(LiquidGlassOcclusion), findsNothing);
    expect(find.byType(SheetSurface), findsOneWidget);
    expect(find.byType(LiquidGlass), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
