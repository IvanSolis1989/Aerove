import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/scaffold.dart';
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
          'page title shadow $brightness glass=$enabled contrast=$highContrast',
          (tester) async {
            await _titleScene(
              tester,
              brightness: brightness,
              enabled: enabled,
              highContrast: highContrast,
            );
            final bar = tester.widget<AppBar>(find.byType(AppBar));
            final shadows = bar.titleTextStyle!.shadows;
            if (enabled && !highContrast) {
              expect(shadows, hasLength(1));
              final shadow = shadows!.single;
              expect(
                shadow.color,
                brightness == Brightness.light
                    ? const Color(0x73FFFFFF)
                    : const Color(0x66000000),
              );
              expect(shadow.blurRadius, brightness == Brightness.light ? 3 : 2);
              expect(
                shadow.offset,
                brightness == Brightness.light
                    ? Offset.zero
                    : const Offset(0, 1),
              );
            } else {
              expect(shadows, isNull);
            }
            final bodyStyle = DefaultTextStyle.of(
              tester.element(find.text('Body text 0')),
            ).style;
            expect(bodyStyle.shadows, isNull);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }

    for (final scroll in [0.0, 128.0]) {
      testWidgets(
        'real title halo is local over $brightness content at $scroll',
        (tester) async {
          final baseline = await _titleScene(
            tester,
            brightness: brightness,
            shadow: false,
            scroll: scroll,
            name: 'title-${brightness.name}-$scroll-reference',
          );
          final actual = await _titleScene(
            tester,
            brightness: brightness,
            scroll: scroll,
            name: 'title-${brightness.name}-$scroll-shadow',
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
          for (var y = 0; y < 600; y++) {
            for (var x = 0; x < 390; x++) {
              final at = (y * 390 + x) * 4;
              var different = false;
              for (var c = 0; c < 4; c++) {
                different |= actual[at + c] != baseline[at + c];
              }
              if (!different) continue;
              changed++;
              if (!effect.contains(Offset(x + 0.5, y + 0.5))) outside++;
            }
          }
          expect(changed, greaterThan(0));
          expect(
            outside,
            0,
            reason:
                'Title shadows must not add a strip, scrim or body text effect.',
          );
          expect(tester.takeException(), isNull);
        },
      );
    }
  }
}

Future<Uint8List> _titleScene(
  WidgetTester tester, {
  required Brightness brightness,
  bool enabled = true,
  bool highContrast = false,
  bool shadow = true,
  double scroll = 0,
  String? name,
}) async {
  tester.view.physicalSize = const Size(390, 600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final controller = ScrollController();
  addTearDown(controller.dispose);
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pumpWidget(
    TestApp(
      includeNavigatorKey: false,
      theme: ThemeData(
        brightness: brightness,
        platform: TargetPlatform.android,
        fontFamily: 'PageTitleProbe',
      ),
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
                color: brightness == Brightness.light
                    ? const Color(0xFFC1B69C)
                    : const Color(0xFF304757),
                child: child,
              ),
            ),
          ),
        ),
      ),
      child: CommonScaffold(
        title: shadow ? 'Page heading' : null,
        titleWidget: shadow
            ? null
            : const Text('Page heading', style: TextStyle(shadows: [])),
        body: DefaultTextStyle(
          style: TextStyle(
            fontFamily: 'PageTitleProbe',
            fontSize: 15,
            color: brightness == Brightness.light ? Colors.black : Colors.white,
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
