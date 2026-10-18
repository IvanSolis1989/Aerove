import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/header_blur.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/navigation_dock.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _size = Size(390, 800);
const _times = [0, 8, 16, 32, 64];
const _tabs = [
  (PageLabel.dashboard, AppGlyphs.dashboard, 'Dashboard'),
  (PageLabel.proxies, AppGlyphs.proxies, 'Proxies'),
  (PageLabel.profiles, AppGlyphs.profiles, 'Profiles'),
  (PageLabel.tools, AppGlyphs.tools, 'Tools'),
];

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    await (FontLoader('FirstFrameProbe')
          ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf')))
        .load();
  });
  for (final animation in [TabAnimation.slide, TabAnimation.fade]) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      for (final wallpaperOnly in [false, true]) {
        testWidgets('home first frame $animation $quality wallpaper=$wallpaperOnly', (
          tester,
        ) async {
          final previousPlatform = debugDefaultTargetPlatformOverride;
          debugDefaultTargetPlatformOverride = TargetPlatform.android;
          tester.view.physicalSize = _size;
          tester.view.devicePixelRatio = 1;
          final container = ProviderContainer(
            overrides: [
              navigationItemsStateProvider.overrideWithValue(
                NavigationItemsState(
                  value: [
                    for (final (label, glyph, _) in _tabs)
                      NavigationItem(
                        glyph: glyph,
                        label: label,
                        builder: (_) => _ProbePage(label),
                      ),
                  ],
                ),
              ),
            ],
          );
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = _size;
          container
              .read(appSettingProvider.notifier)
              .update((state) => state.copyWith(tabAnimation: animation));
          final capture = GlobalKey();
          final frames = <Map<String, Object?>>[];
          final failures = <String>[];
          final name =
              'home-first-${animation.name}-${quality?.name ?? 'none'}-$wallpaperOnly';
          try {
            LiquidGlassHost.debugLastCapture = null;
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: MaterialApp(
                  debugShowCheckedModeBanner: false,
                  theme: ThemeData(
                    platform: TargetPlatform.android,
                    fontFamily: 'FirstFrameProbe',
                    scaffoldBackgroundColor: Colors.transparent,
                  ),
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    AppUiLocalizations.delegate,
                    ...GlobalMaterialLocalizations.delegates,
                  ],
                  supportedLocales: AppLocalizations.delegate.supportedLocales,
                  builder: (context, child) => wrapAppScopes(
                    context,
                    RepaintBoundary(
                      key: capture,
                      child: LiquidGlassScope(
                        enabled: quality != null,
                        quality: quality ?? LiquidGlassQuality.standard,
                        child: LiquidGlassHost(
                          enabled: quality != null,
                          wallpaperOnly: wallpaperOnly,
                          child: Stack(
                            fit: StackFit.expand,
                            children: [
                              const LiquidGlassColorField(
                                child: LiquidGlassColorSource(
                                  child: CustomPaint(painter: _Checker()),
                                ),
                              ),
                              child!,
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                  home: const HomePage(),
                ),
              ),
            );

            Future<void> sample(
              PageLabel label,
              String phase,
              int elapsed,
            ) async {
              final sharedShaderReadyBeforeReadback = LiquidGlass.debugShaderReady;
              final shaderBackendSupportedBeforeReadback =
                  ui.ImageFilter.isShaderFilterSupported;
              final page = find.byKey(ValueKey('page-${label.name}'));
              final pane = find.byKey(ValueKey('pane-${label.name}'));
              final built = page.evaluate().isNotEmpty;
              final pageRect = built ? tester.getRect(page) : Rect.zero;
              final rect = built ? tester.getRect(pane) : Rect.zero;
              final visible = rect.intersect(Offset.zero & _size);
              final fades = built
                  ? find.ancestor(
                      of: page,
                      matching: find.byType(LiquidGlassFade),
                    )
                  : find.byKey(const ValueKey('absent'));
              final ordinaryOpacity = built
                  ? find.ancestor(of: page, matching: find.byType(Opacity))
                  : find.byKey(const ValueKey('absent-opacity'));
              final alpha = fades.evaluate().isNotEmpty
                  ? tester.widget<LiquidGlassFade>(fades.first).opacity.value
                  : ordinaryOpacity.evaluate().isNotEmpty
                  ? tester.widget<Opacity>(ordinaryOpacity.first).opacity
                  : 1.0;
              final state = LiquidGlassHost.debugLastCapture;
              final panes =
                  state?.panes
                      .where(
                        (entry) =>
                            (entry.pane.topLeft - rect.topLeft).distance <
                                0.01 &&
                            (entry.pane.bottomRight - rect.bottomRight)
                                    .distance <
                                0.01,
                      )
                      .toList() ??
                  [];
              final header = built
                  ? find.descendant(of: page, matching: find.byType(HeaderBlur))
                  : find.byKey(const ValueKey('absent-header'));
              var atlasAlpha = 0;
              if (panes.isNotEmpty && visible.width > 2 && visible.height > 2) {
                final entry = panes.single;
                final point = visible.deflate(1).center;
                final texel =
                    entry.place.topLeft +
                    (point - entry.slot.topLeft) * entry.scale;
                final bytes = (await tester.runAsync(entry.atlas.toByteData))!;
                final index =
                    (texel.dy.floor() * entry.atlas.width + texel.dx.floor()) *
                    4;
                atlasAlpha = bytes.getUint8(index + 3);
              }
              final image = await _snapshot(
                tester,
                capture,
                '$name-${label.name}-$phase-$elapsed',
              );
              final headerCrop = Rect.fromLTWH(
                pageRect.left + 2,
                pageRect.top + 4,
                128,
                12,
              ).intersect(Offset.zero & _size);
              final headerVariance = headerCrop.width >= 16
                  ? _variance(image, headerCrop)
                  : null;
              final rawHeaderVariance = headerCrop.width >= 16
                  ? _stripeVariance(headerCrop, pageRect.left)
                  : null;
              double? controlHeaderVariance;
              double? repeatHeaderVariance;
              int? referencePixelNoise;
              int? controlPixelDelta;
              final headerVisible = built && alpha > 1 / 255 &&
                  pageRect.intersect(Offset.zero & _size).width > 0;
              final filterFinder = find.descendant(
                of: header,
                matching: find.byType(BackdropFilter),
              );
              if (quality != null && headerVisible && headerCrop.width >= 16 &&
                  filterFinder.evaluate().length == 1) {
                final filter = tester.renderObject<RenderBackdropFilter>(filterFinder);
                final original = filter.filter;
                final identity = ui.ImageFilter.blur(sigmaX: 0, sigmaY: 0);
                late Uint8List control;
                try {
                  filter.filter = identity;
                  await tester.pump();
                  expect(identical(filter.filter, identity), isTrue);
                  expect(tester.getRect(page), pageRect);
                  control = await _snapshot(
                    tester, capture, '$name-${label.name}-$phase-$elapsed-header-control',
                  );
                } finally {
                  filter.filter = original;
                  await tester.pump();
                  expect(tester.getRect(page), pageRect);
                }
                final repeat = await _snapshot(
                  tester, capture, '$name-${label.name}-$phase-$elapsed-header-repeat',
                );
                controlHeaderVariance = _variance(control, headerCrop);
                repeatHeaderVariance = _variance(repeat, headerCrop);
                referencePixelNoise = _maxDelta(image, repeat, headerCrop);
                controlPixelDelta = _maxDelta(image, control, headerCrop);
              }
              frames.add({
                'page': label.name,
                'phase': phase,
                'virtualFrameElapsedMs': elapsed,
                'built': built,
                'opacity': alpha,
                'paneRect': [rect.left, rect.top, rect.right, rect.bottom],
                'visibleWidth': visible.width,
                'destinationPanes': panes.length,
                'totalPanes': state?.panes.length,
                'atlasPixels': state?.atlasPixels,
                'captureSnapshots': state?.snapshots,
                'replayedPanes': state?.replayedPanes,
                'sampleAlpha': atlasAlpha,
                'headerBlurWidgets': header.evaluate().length,
                'shaderBackendSupportedBeforeReadback':
                    shaderBackendSupportedBeforeReadback,
                'sharedShaderReadyBeforeReadback': sharedShaderReadyBeforeReadback,
                'headerCrop': [
                  headerCrop.left,
                  headerCrop.top,
                  headerCrop.right,
                  headerCrop.bottom,
                ],
                'headerVariance': headerVariance,
                'unblurredStripeVariance': rawHeaderVariance,
                'incomingHeaderVisible': headerVisible,
                'headerPixelControlResolvable': headerCrop.width >= 16,
                'incomingIdentityBlurVariance': controlHeaderVariance,
                'incomingBlurRepeatVariance': repeatHeaderVariance,
                'headerReferencePixelNoise': referencePixelNoise,
                'headerControlPixelDelta': controlPixelDelta,
              });
              if (quality != null &&
                  built &&
                  alpha > 1 / 255 &&
                  visible.width > 2 &&
                  visible.height > 2) {
                if (panes.length != 1) {
                  failures.add(
                    '$label $phase $elapsed: visible pane has ${panes.length} captures.',
                  );
                }
                if (atlasAlpha == 0) {
                  failures.add(
                    '$label $phase $elapsed: backdrop sample is transparent.',
                  );
                }
              }
              if (quality != null && headerVisible) {
                if (header.evaluate().length != 1) {
                  failures.add(
                    '$label $phase $elapsed: floating header blur is absent.',
                  );
                }
                if (headerVariance != null &&
                    rawHeaderVariance != null &&
                    headerVariance >= rawHeaderVariance) {
                  failures.add(
                    '$label $phase $elapsed: header stripe variance $headerVariance did not fall below unblurred $rawHeaderVariance.',
                  );
                }
                if (headerCrop.width >= 16 && (controlHeaderVariance == null ||
                    repeatHeaderVariance == null || referencePixelNoise == null ||
                    controlPixelDelta == null)) {
                  failures.add('$label $phase $elapsed: incoming header control was not captured.');
                } else if (controlHeaderVariance != null &&
                    repeatHeaderVariance != null && headerVariance != null) {
                  final varianceNoise = (headerVariance - repeatHeaderVariance).abs();
                  if (controlHeaderVariance - headerVariance <= varianceNoise ||
                      controlPixelDelta! <= referencePixelNoise!) {
                    failures.add('$label $phase $elapsed: incoming blur did not reduce its own sharp stripes beyond repeated-frame noise.');
                  }
                }
              }
              if (quality == null) expect(header, findsNothing);
              expect(tester.takeException(), isNull);
            }

            await sample(PageLabel.dashboard, 'initial-shared-cache-state', 0);
            for (final phase in ['arrival', 'warm-repeat']) {
              if (phase == 'warm-repeat' &&
                  quality != null &&
                  ui.ImageFilter.isShaderFilterSupported) {
                await tester.runAsync(() async {
                  await ui.FragmentProgram.fromAsset(
                    'shaders/liquid_glass.frag',
                  );
                });
                await tester.pump();
                expect(
                  LiquidGlass.debugShaderReady,
                  isTrue,
                  reason:
                      'A warm repeat requires the actual shared program to be ready.',
                );
              }
              for (final (label, _, caption) in [
                ..._tabs.skip(1),
                _tabs.first,
              ]) {
                final target = _dockTarget(caption);
                expect(target, findsOneWidget);
                await tester.tap(target);
                await tester.pump();
                var previous = 0;
                for (final elapsed in _times) {
                  await tester.pump(Duration(milliseconds: elapsed - previous));
                  await sample(label, phase, elapsed);
                  previous = elapsed;
                }
                await tester.pumpAndSettle();
                await sample(label, '$phase-settled', -1);
                expect(container.read(currentPageLabelProvider), label);
              }
            }
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              await tester.runAsync(() async {
                await File('$output/$name.json').writeAsString(
                  const JsonEncoder.withIndent(
                    '  ',
                  ).convert({
                    'timingEvidence': {
                      'frameClock': 'Flutter test virtual animation clock',
                      'asyncReadbackAndFileIoBetweenFrames': true,
                      'coldShaderWallClockLatencyMeasured': false,
                      'shaderReadySemantics': 'Shared program cache state at sample entry, before asynchronous readback; not per-pane program usage in the already-painted frame.',
                    },
                    'frames': frames,
                    'failures': failures,
                  }),
                );
              });
            }
            expect(failures, isEmpty);
          } finally {
            await tester.pumpWidget(const SizedBox.shrink());
            container.dispose();
            tester.view.reset();
            debugDefaultTargetPlatformOverride = previousPlatform;
          }
        });
      }
    }
  }
}

class _ProbePage extends StatelessWidget {
  const _ProbePage(this.label);
  final PageLabel label;

  @override
  Widget build(BuildContext context) => CommonScaffold(
    key: ValueKey('page-${label.name}'),
    title: 'P${label.index + 1}',
    backgroundColor: Colors.transparent,
    body: Stack(
      children: [
        const Positioned(
          left: 0,
          right: 0,
          top: 0,
          height: 72,
          child: CustomPaint(painter: _HeaderStripes()),
        ),
        Positioned(
          left: 24,
          top: 170,
          width: 280,
          height: 150,
          child: SizedBox(
            key: ValueKey('pane-${label.name}'),
            child: LiquidGlassScope.of(context)
                ? LiquidGlass(
                    style: LiquidGlassStyle.regular(
                      Theme.of(context).colorScheme,
                    ),
                    clipper: const ShapeBorderClipper(shape: AppShape.xl),
                    child: const SizedBox.expand(),
                  )
                : const ColoredBox(color: Color(0xFF909090)),
          ),
        ),
      ],
    ),
  );
}

Finder _dockTarget(String label) => find.descendant(
  of: find.byType(NavigationDock),
  matching: find.byWidgetPredicate((widget) => widget is Semantics &&
      widget.properties.label == label && widget.properties.button == true &&
      widget.properties.onTap != null),
);

class _Checker extends CustomPainter {
  const _Checker();
  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()
          ..color = (x ~/ 8).isEven
              ? const Color(0xFF37678C)
              : const Color(0xFFD1B47F),
      );
    }
  }

  @override
  bool shouldRepaint(_Checker oldDelegate) => false;
}

Future<Uint8List> _snapshot(
  WidgetTester tester,
  GlobalKey key,
  String name,
) async {
  late Uint8List bytes;
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  await tester.runAsync(() async {
    final image = boundary.toImageSync();
    bytes = (await image.toByteData(
      format: ui.ImageByteFormat.rawRgba,
    ))!.buffer.asUint8List();
    final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (output != null) {
      await Directory(output).create(recursive: true);
      final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
      await File('$output/$name.png').writeAsBytes(png.buffer.asUint8List());
    }
    image.dispose();
  });
  return bytes;
}

double _variance(Uint8List bytes, Rect rect) {
  var sum = 0.0;
  var square = 0.0;
  var count = 0;
  for (var y = rect.top.toInt(); y < rect.bottom; y++) {
    for (var x = rect.left.toInt(); x < rect.right; x++) {
      final index = (y * _size.width.toInt() + x) * 4;
      final value =
          (bytes[index] * 0.2126 +
                  bytes[index + 1] * 0.7152 +
                  bytes[index + 2] * 0.0722)
              .roundToDouble();
      sum += value;
      square += value * value;
      count++;
    }
  }
  return square / count - (sum / count) * (sum / count);
}

double _stripeVariance(Rect rect, double pageLeft) {
  var sum = 0.0;
  var square = 0.0;
  var count = 0;
  for (var x = rect.left.toInt(); x < rect.right; x++) {
    final value = ((x + 0.5 - pageLeft) ~/ 8).isEven ? 0.0 : 255.0;
    sum += value;
    square += value * value;
    count++;
  }
  return square / count - (sum / count) * (sum / count);
}

class _HeaderStripes extends CustomPainter {
  const _HeaderStripes();

  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 8, size.height),
        Paint()..color = (x ~/ 8).isEven ? Colors.black : Colors.white,
      );
    }
  }

  @override
  bool shouldRepaint(_HeaderStripes oldDelegate) => false;
}

int _maxDelta(Uint8List a, Uint8List b, Rect rect) {
  var maximum = 0;
  for (var y = rect.top.toInt(); y < rect.bottom; y++) {
    for (var x = rect.left.toInt(); x < rect.right; x++) {
      final index = (y * _size.width.toInt() + x) * 4;
      for (var channel = 0; channel < 4; channel++) {
        final delta = (a[index + channel] - b[index + channel]).abs();
        if (delta > maximum) maximum = delta;
      }
    }
  }
  return maximum;
}
