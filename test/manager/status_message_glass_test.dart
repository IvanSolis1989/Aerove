import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/status_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/glyph_finders.dart';
import '../helpers/test_app.dart';

const _message = 'VPN settings changed';
const _sourceA = Color(0xFF996622);
const _sourceB = Color(0xFF226699);

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    final font = FontLoader('StatusGlassProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
  });

  for (final brightness in Brightness.values) {
    for (final (mode, enabled, highContrast, quality) in [
      ('none', false, false, LiquidGlassQuality.standard),
      ('high-contrast', true, true, LiquidGlassQuality.ultra),
      ('standard', true, false, LiquidGlassQuality.standard),
      ('ultra', true, false, LiquidGlassQuality.ultra),
    ]) {
      testWidgets(
        'shared warning notification uses its host $brightness $mode',
        (tester) async {
          tester.view.physicalSize = const Size(430, 500);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          tester.platformDispatcher.accessibilityFeaturesTestValue =
              FakeAccessibilityFeatures(highContrast: highContrast);
          addTearDown(
            tester.platformDispatcher.clearAccessibilityFeaturesTestValue,
          );
          final captureKey = GlobalKey();
          final source = ValueNotifier<Color>(_sourceA);
          final completed = Completer<void>();
          var calls = 0;
          var finished = false;
          final glass = enabled && !highContrast;
          try {
            await tester.pumpWidget(
              RepaintBoundary(
                key: captureKey,
                child: LiquidGlassScope(
                  enabled: enabled,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: glass,
                    wallpaperOnly: true,
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
                        TestApp(
                          includeNavigatorKey: false,
                          wrapInProviderScope: true,
                          overrides: [
                            isMobileViewProvider.overrideWithValue(true),
                          ],
                          theme: ThemeData(
                            brightness: brightness,
                            fontFamily: 'StatusGlassProbe',
                          ),
                          child: const StatusManager(child: SizedBox.expand()),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
            final state = tester.state<StatusManagerState>(
              find.byType(StatusManager),
            );
            state.message(
              _message,
              level: MessageLevel.warning,
              actionState: MessageActionState(
                actionText: 'Restart',
                action: () async {
                  calls++;
                  await completed.future;
                  finished = true;
                },
              ),
            );
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 60));
            if (glass && ui.ImageFilter.isShaderFilterSupported) {
              await tester.runAsync(
                () => ui.FragmentProgram.fromAsset(
                  'shaders/liquid_glass.frag',
                ).timeout(const Duration(seconds: 30)),
              );
              await tester.pump();
              expect(LiquidGlass.debugShaderReady, isTrue);
            }
            final name = 'status-message-${brightness.name}-$mode';
            final entering = await _probe(
              tester,
              captureKey,
              glass,
              '$name-entering',
            );
            await tester.pump(const Duration(milliseconds: 450));
            final context = tester.element(find.text(_message));
            final card = find
                .ancestor(
                  of: find.text(_message),
                  matching: find.byType(Material),
                )
                .first;
            final material = tester.widget<Material>(card);
            expect(
              material.color,
              glass
                  ? Colors.transparent
                  : MessageLevel.warning.containerColor(context),
            );
            expect(material.elevation, glass ? 0 : 3);
            expect(material.shape, AppShape.lg);
            expect(find.byGlyph(AppGlyphs.warning), findsOneWidget);
            final glyph = tester.widget<GlyphIcon>(
              find.byGlyph(AppGlyphs.warning),
            );
            expect(glyph.color, MessageLevel.warning.iconColor(context));
            expect(
              tester.widget<Text>(find.text(_message)).style!.color,
              MessageLevel.warning.contentColor(context),
            );
            final panes = find.ancestor(
              of: find.text(_message),
              matching: find.byType(LiquidGlass),
            );
            if (glass) {
              expect(panes, findsOneWidget);
              final pane = tester.widget<LiquidGlass>(panes);
              expect(pane.style.blur, 2.5);
              expect(pane.style.haze, 0);
              expect(pane.style.tint.a, 0);
              expect(pane.contentBackdrop, isFalse);
              expect(
                find.ancestor(
                  of: panes,
                  matching: find.byType(LiquidGlassHost),
                ),
                findsOneWidget,
              );
              expect(
                find.ancestor(
                  of: panes,
                  matching: find.byType(LiquidGlassFade),
                ),
                findsNWidgets(2),
              );
            } else {
              expect(panes, findsNothing);
            }
            final a = await _probe(tester, captureKey, glass, '$name-a');
            final repeat = await _probe(
              tester,
              captureKey,
              glass,
              '$name-repeat',
            );
            source.value = _sourceB;
            await tester.pump();
            final b = await _probe(tester, captureKey, glass, '$name-b');
            final noise = _delta(a.pixel, repeat.pixel);
            final change = _delta(a.pixel, b.pixel);
            expect(_delta(a.outside, b.outside), greaterThan(0));
            if (glass) {
              expect(change, greaterThan(noise));
              if (ui.ImageFilter.isShaderFilterSupported) {
                expect(
                  _delta(a.atlasPixel!, b.atlasPixel!),
                  greaterThan(_delta(a.atlasPixel!, repeat.atlasPixel!)),
                );
              }
            } else {
              expect(change, 0);
            }
            await tester.tap(find.text('Restart'));
            await tester.pumpAndSettle();
            expect(calls, 1);
            expect(finished, isFalse);
            expect(find.text(_message), findsNothing);
            completed.complete();
            await tester.pump();
            expect(finished, isTrue);
            for (final level in MessageLevel.values) {
              final label = 'notice ${level.name}';
              state.message(label, level: level);
              await tester.pump();
              await tester.pump(const Duration(milliseconds: 450));
              final text = find.text(label);
              final context = tester.element(text);
              final card = find
                  .ancestor(of: text, matching: find.byType(Material))
                  .first;
              expect(
                tester.widget<Material>(card).color,
                glass ? Colors.transparent : level.containerColor(context),
              );
              expect(
                tester.widget<Text>(text).style!.color,
                level.contentColor(context),
              );
              if (level.glyph case final glyph?) {
                expect(
                  tester.widget<GlyphIcon>(find.byGlyph(glyph)).color,
                  level.iconColor(context),
                );
              }
              if (glass) {
                final pane = tester.widget<LiquidGlass>(
                  find.ancestor(of: text, matching: find.byType(LiquidGlass)),
                );
                expect(pane.style.haze, 0);
                expect(
                  pane.style.tint.a,
                  level == MessageLevel.error ? 0.16 : 0,
                );
              }
              await tester.drag(card, const Offset(400, 0));
              await tester.pumpAndSettle();
              expect(text, findsNothing);
            }
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null && output.isNotEmpty) {
              File('$output/$name.json').writeAsStringSync(
                jsonEncode({
                  'mode': mode,
                  'brightness': brightness.name,
                  'glass': glass,
                  'sourceA': _sourceA.toARGB32(),
                  'sourceB': _sourceB.toARGB32(),
                  'repeatNoise': noise,
                  'backgroundDelta': change,
                  'entering': entering.metadata,
                  'a': a.metadata,
                  'b': b.metadata,
                }),
              );
            }
            expect(tester.takeException(), isNull);
          } finally {
            if (!completed.isCompleted) completed.complete();
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pump();
            source.dispose();
          }
        },
      );
    }
  }
}

int _delta(List<int> a, List<int> b) {
  var result = 0;
  for (var i = 0; i < a.length; i++) {
    final difference = (a[i] - b[i]).abs();
    if (difference > result) result = difference;
  }
  return result;
}

Future<
  ({
    List<int> pixel,
    List<int> outside,
    List<int>? atlasPixel,
    Map<String, Object?> metadata,
  })
>
_probe(WidgetTester tester, GlobalKey key, bool glass, String name) async {
  final card = find
      .ancestor(of: find.text(_message), matching: find.byType(Material))
      .first;
  final bounds = tester.getRect(card);
  final backend = ui.ImageFilter.isShaderFilterSupported;
  final ready = LiquidGlass.debugShaderReady;
  final metadata = <String, Object?>{
    'shaderSupported': backend,
    'shaderReady': ready,
    'bounds': [bounds.left, bounds.top, bounds.width, bounds.height],
  };
  ui.Image? atlas;
  Offset? atlasPoint;
  if (glass && backend) {
    expect(ready, isTrue);
    final capture = LiquidGlassHost.debugLastCapture!;
    final pane = capture.panes.singleWhere(
      (pane) => pane.pane.contains(bounds.center),
    );
    expect(pane.wallpaper, isTrue);
    expect(pane.scale, greaterThan(0));
    final point = pane.slot.center;
    atlasPoint = pane.place.topLeft + (point - pane.slot.topLeft) * pane.scale;
    atlas = pane.atlas.clone();
    metadata.addAll({
      'pane': [
        pane.pane.left,
        pane.pane.top,
        pane.pane.width,
        pane.pane.height,
      ],
      'slot': [
        pane.slot.left,
        pane.slot.top,
        pane.slot.width,
        pane.slot.height,
      ],
      'place': [
        pane.place.left,
        pane.place.top,
        pane.place.width,
        pane.place.height,
      ],
      'scale': pane.scale,
      'atlasSize': [atlas.width, atlas.height],
      'atlasPoint': [atlasPoint.dx, atlasPoint.dy],
    });
  }
  final atlasImage = atlas;
  final mappedPoint = atlasPoint;
  final scene =
      (key.currentContext!.findRenderObject()! as RenderRepaintBoundary)
          .toImageSync();
  try {
    return (await tester.runAsync(() async {
      final sceneBytes = (await scene.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!.buffer.asUint8List();
      final at =
          ((bounds.top + 5).floor() * scene.width + bounds.center.dx.floor()) *
          4;
      final pixel = sceneBytes.sublist(at, at + 4);
      final outsideAt = (2 * scene.width + 2) * 4;
      final outside = sceneBytes.sublist(outsideAt, outsideAt + 4);
      List<int>? atlasPixel;
      if (atlasImage != null) {
        final bytes = (await atlasImage.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!.buffer.asUint8List();
        final point = mappedPoint!;
        final index =
            (point.dy.floor() * atlasImage.width + point.dx.floor()) * 4;
        atlasPixel = bytes.sublist(index, index + 4);
        expect(atlasPixel.last, 255);
        metadata['atlasPixel'] = atlasPixel;
      }
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && output.isNotEmpty) {
        Directory(output).createSync(recursive: true);
        final png = (await scene.toByteData(format: ui.ImageByteFormat.png))!;
        File('$output/$name.png').writeAsBytesSync(png.buffer.asUint8List());
        if (atlasImage != null) {
          final png = (await atlasImage.toByteData(
            format: ui.ImageByteFormat.png,
          ))!;
          File(
            '$output/$name-atlas.png',
          ).writeAsBytesSync(png.buffer.asUint8List());
        }
      }
      return (
        pixel: pixel.toList(),
        outside: outside.toList(),
        atlasPixel: atlasPixel,
        metadata: metadata,
      );
    }))!;
  } finally {
    scene.dispose();
    atlas?.dispose();
  }
}
