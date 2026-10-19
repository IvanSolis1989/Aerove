import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/scaffold.dart';
import 'package:fl_clash/ui/sheet.dart';
import 'package:fl_clash/ui/view_scope.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    final loader = FontLoader('PageTitleProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await loader.load();
  });

  for (final brightness in Brightness.values) {
    for (final enabled in [false, true]) {
      for (final highContrast in [false, true]) {
        testWidgets(
          'page title has no shadow or stroke $brightness glass=$enabled contrast=$highContrast',
          (tester) async {
            await _titleScene(
              tester,
              brightness: brightness,
              enabled: enabled,
              highContrast: highContrast,
            );
            final bar = tester.widget<AppBar>(find.byType(AppBar));
            expect(bar.titleTextStyle!.shadows, isNull);
            expect(bar.titleTextStyle!.foreground?.style, isNot(PaintingStyle.stroke));
            final bodyStyle = DefaultTextStyle.of(
              tester.element(find.text('Body text 0')),
            ).style;
            expect(bodyStyle.shadows, isNull);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }

    testWidgets('all header title paths have no added effects $brightness', (tester) async {
      for (final role in ['main', 'secondary', 'sheet', 'custom']) {
        await tester.pumpWidget(TestApp(
          includeNavigatorKey: false,
          theme: ThemeData(brightness: brightness, platform: TargetPlatform.android),
          child: ViewScope(
            size: const Size(390, 600),
            isMobile: true,
            child: LiquidGlassScope(
              enabled: true,
              child: SheetProvider(
                type: role == 'sheet' ? SheetType.bottomSheet : SheetType.page,
                child: CommonScaffold(
                  title: role == 'custom' ? null : 'Heading $role',
                  titleWidget: role == 'custom' ? const GlassText('Heading custom') : null,
                  backAction: role == 'secondary' ? () {} : null,
                  body: const SizedBox(height: 240),
                ),
              ),
            ),
          ),
        ));
        await tester.pumpAndSettle();
        final text = tester.element(find.text('Heading $role'));
        final style = DefaultTextStyle.of(text).style;
        expect(style.shadows, anyOf(isNull, isEmpty), reason: role);
        expect(style.foreground?.style, isNot(PaintingStyle.stroke), reason: role);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      }
    });

    for (final scroll in [0.0, 128.0]) {
      testWidgets('real title has no halo over $brightness content at $scroll', (
        tester,
      ) async {
        final background = await _titleScene(
          tester,
          brightness: brightness,
          hideTitle: true,
          scroll: scroll,
          name: 'title-${brightness.name}-$scroll-background',
        );
        final flat = scroll == 0
            ? background
            : await _titleScene(
                tester,
                brightness: brightness,
                hideTitle: true,
              );
        final glyphs = await _titleScene(
          tester,
          brightness: brightness,
          glyphMask: true,
          name: 'title-${brightness.name}-$scroll-glyph-mask',
        );
        final baseline = await _titleScene(
          tester,
          brightness: brightness,
          defaultTitle: false,
          scroll: scroll,
          name: 'title-${brightness.name}-$scroll-reference',
        );
        final actual = await _titleScene(
          tester,
          brightness: brightness,
          scroll: scroll,
          name: 'title-${brightness.name}-$scroll-actual',
        );
        final boundary = tester.getRect(
          find.byKey(const ValueKey('title-capture')),
        );
        final title = tester
            .getRect(find.text('Page heading'))
            .shift(-boundary.topLeft);
        final effect = title.inflate(12);
        var changed = 0;
        var outside = 0;
        var ringSamples = 0;
        var overlapSamples = 0;
        var beforeContrast = 0.0;
        var afterContrast = 0.0;
        var overlapBefore = 0.0;
        var overlapAfter = 0.0;
        var foregroundSum = 0.0;
        var foregroundSamples = 0;
        for (var at = 0; at < glyphs.length; at += 4) {
          if (glyphs[at] == 255) {
            foregroundSum += _luminance(actual, at);
            foregroundSamples++;
          }
        }
        expect(foregroundSamples, greaterThan(0));
        final foreground = foregroundSum / foregroundSamples;
        for (var y = 0; y < 600; y++) {
          for (var x = 0; x < 390; x++) {
            final at = (y * 390 + x) * 4;
            var different = false;
            for (var c = 0; c < 4; c++) {
              different |= actual[at + c] != baseline[at + c];
            }
            if (effect.contains(Offset(x + 0.5, y + 0.5)) &&
                glyphs[at] == 0 &&
                _nearGlyph(glyphs, x, y)) {
              final before = _contrast(foreground, _luminance(baseline, at));
              final after = _contrast(foreground, _luminance(actual, at));
              ringSamples++;
              beforeContrast += before;
              afterContrast += after;
              if (_contrast(_luminance(background, at), _luminance(flat, at)) >
                  1.1) {
                overlapSamples++;
                overlapBefore += before;
                overlapAfter += after;
              }
            }
            if (!different) continue;
            changed++;
            if (!effect.contains(Offset(x + 0.5, y + 0.5))) outside++;
          }
        }
        expect(changed, 0);
        expect(ringSamples, greaterThan(0));
        expect(afterContrast, beforeContrast);
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          await tester.runAsync(
            () => File('$output/title-${brightness.name}-$scroll-contrast.json')
                .writeAsString(
                  jsonEncode({
                    'ringSamples': ringSamples,
                    'meanRingContrastBefore': beforeContrast / ringSamples,
                    'meanRingContrastAfter': afterContrast / ringSamples,
                    'overlapSamples': overlapSamples,
                    'meanOverlapContrastBefore': overlapSamples == 0
                        ? null
                        : overlapBefore / overlapSamples,
                    'meanOverlapContrastAfter': overlapSamples == 0
                        ? null
                        : overlapAfter / overlapSamples,
                    'overlapContrastRatio': overlapSamples == 0
                        ? null
                        : overlapAfter / overlapBefore,
                    'outsideChangedPixels': outside,
                  }),
                ),
          );
        }
        if (scroll > 0) {
          expect(overlapSamples, greaterThan(0));
          expect(
            overlapAfter / overlapBefore,
            1,
            reason: 'Titles retain their glyph contrast without an added halo.',
          );
        }
        expect(
          outside,
          0,
          reason:
              'Titles must not add a strip, scrim or body text effect.',
        );
        expect(tester.takeException(), isNull);
      });
    }
  }
}

Future<Uint8List> _titleScene(
  WidgetTester tester, {
  required Brightness brightness,
  bool enabled = true,
  bool highContrast = false,
  bool defaultTitle = true,
  bool glyphMask = false,
  bool hideTitle = false,
  double scroll = 0,
  String? name,
}) async {
  tester.view.physicalSize = const Size(390, 600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final controller = ScrollController();
  addTearDown(controller.dispose);
  final baseTheme = ThemeData(
    brightness: brightness,
    platform: TargetPlatform.android,
    fontFamily: 'PageTitleProbe',
  );
  final theme = baseTheme;
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pumpWidget(
    TestApp(
      includeNavigatorKey: false,
      theme: theme,
      homeBuilder: (child) => MediaQuery(
        data: MediaQueryData(
          size: const Size(390, 600),
          highContrast: highContrast,
        ),
        child: LiquidGlassScope(
          enabled: enabled,
          child: ViewScope(
            size: const Size(390, 600),
            isMobile: true,
            child: RepaintBoundary(
              key: const ValueKey('title-capture'),
              child: ColoredBox(
                color: glyphMask
                    ? Colors.black
                    : brightness == Brightness.light
                    ? const Color(0xFFC1B69C)
                    : const Color(0xFF304757),
                child: child,
              ),
            ),
          ),
        ),
      ),
      child: CommonScaffold(
        title: defaultTitle && !glyphMask && !hideTitle ? 'Page heading' : null,
        titleWidget: glyphMask || hideTitle
            ? Text(
                'Page heading',
                style: TextStyle(
                  shadows: const [],
                  color: glyphMask ? Colors.white : Colors.transparent,
                ),
              )
            : defaultTitle
            ? null
            : const GlassText('Page heading', style: TextStyle(shadows: [])),
        body: DefaultTextStyle(
          style: TextStyle(
            fontFamily: 'PageTitleProbe',
            fontSize: 15,
            color: glyphMask ? Colors.black : theme.textTheme.bodyLarge!.color,
          ),
          child: ListView.builder(
            controller: controller,
            padding: const EdgeInsets.only(top: 128),
            itemCount: 30,
            itemBuilder: (_, index) =>
                SizedBox(height: 32, child: Text('Body text $index')),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  if (scroll > 0) {
    controller.jumpTo(scroll);
    await tester.pumpAndSettle();
  }
  expect(tester.takeException(), isNull);
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(const ValueKey('title-capture')),
  );
  expect(boundary.size, const Size(390, 600));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData())!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && name != null) {
        await Directory(output).create(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        await File('$output/$name.png').writeAsBytes(
          png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
        );
      }
      return Uint8List.fromList(
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
      );
    } finally {
      image.dispose();
    }
  }))!;
}

bool _nearGlyph(Uint8List mask, int x, int y) {
  for (var dy = -2; dy <= 2; dy++) {
    for (var dx = -2; dx <= 2; dx++) {
      final nx = x + dx;
      final ny = y + dy;
      if (nx >= 0 &&
          nx < 390 &&
          ny >= 0 &&
          ny < 600 &&
          mask[(ny * 390 + nx) * 4] > 0)
        return true;
    }
  }
  return false;
}

double _luminance(Uint8List pixels, int at) {
  double linear(int value) {
    final channel = value / 255;
    return channel <= 0.04045
        ? channel / 12.92
        : math.pow((channel + 0.055) / 1.055, 2.4).toDouble();
  }

  return 0.2126 * linear(pixels[at]) +
      0.7152 * linear(pixels[at + 1]) +
      0.0722 * linear(pixels[at + 2]);
}

double _contrast(double a, double b) =>
    (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05);
