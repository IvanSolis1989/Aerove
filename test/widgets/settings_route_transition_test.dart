import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/open_container.dart';
import 'package:fl_clash/views/config/advanced.dart';
import 'package:fl_clash/views/config/general.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/tools.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

const _size = Size(390, 800);
const _progress = [0.0, 0.1, 0.25, 0.5, 0.75, 1.0];
const _grounds = [Color(0xFF3A91AF), Color(0xFFBE7259)];

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    final font = FontLoader('SettingsRouteProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
  });
  for (final brightness in Brightness.values) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      for (final entry in {
        'Theme': ThemeView,
        'General': GeneralView,
        'Advanced configuration': AdvancedConfigView,
      }.entries) {
        testWidgets('settings transition $brightness $quality ${entry.key}', (
          tester,
        ) async {
          tester.view.physicalSize = _size;
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          final samples = <List<List<double>>>[];
          for (var ground = 0; ground < _grounds.length; ground++) {
            final container = ProviderContainer(overrides: [
              profilesProvider.overrideWith(TestProfiles.new),
            ]);
            globalState.container = container;
            container.read(viewSizeProvider.notifier).value = _size;
            final capture = GlobalKey();
            final theme = ThemeData(brightness: brightness,
              platform: TargetPlatform.android, fontFamily: 'SettingsRouteProbe');
            final prefix = 'settings-transition-${brightness.name}-'
                '${quality?.name ?? 'none'}-${entry.key.replaceAll(' ', '-')}-bg$ground';
            try {
              await tester.pumpWidget(UncontrolledProviderScope(
                container: container,
                child: Directionality(textDirection: TextDirection.ltr,
                  child: Theme(data: theme,
                    child: RepaintBoundary(key: capture,
                      child: LiquidGlassScope(enabled: quality != null,
                        quality: quality ?? LiquidGlassQuality.standard,
                        child: LiquidGlassHost(enabled: quality != null,
                          child: Stack(fit: StackFit.expand, children: [
                            ColoredBox(color: _grounds[ground]),
                            TestApp(includeNavigatorKey: false, theme: theme,
                              child: const ToolsView()),
                          ]),
                        ),
                      ),
                    ),
                  ),
                ),
              ));
              await tester.pumpAndSettle();
              final target = find.text(entry.key);
              await tester.scrollUntilVisible(target, 300,
                scrollable: find.byType(Scrollable).first);
              await tester.pumpAndSettle();
              final tile = find.ancestor(of: target,
                matching: find.byType(OpenContainer<dynamic>));
              expect(tile, findsOneWidget);
              final origin = tester.getTopLeft(find.byKey(capture));
              final closed = tester.getRect(tile).shift(-origin);
              final frames = <List<double>>[];
              await tester.tap(target);
              await tester.pump();
              await tester.pump();
              final route = ModalRoute.of(tester.element(find.byType(entry.value)))!;
              expect(route.opaque, isTrue);
              expect(find.byType(LiquidGlassHost), findsOneWidget);
              var previous = 0.0;
              for (final progress in _progress) {
                await tester.pump(Duration(microseconds:
                  ((progress - previous) * 300000).round()));
                expect(route.animation!.value, closeTo(progress, 0.00001));
                final rect = Rect.lerp(closed, Offset.zero & _size,
                  Curves.fastOutSlowIn.transform(progress))!;
                frames.add(await _frame(tester, capture, rect,
                  '$prefix-push-$progress'));
                previous = progress;
              }
              expect(find.byType(entry.value), findsOneWidget);
              Navigator.of(tester.element(find.byType(entry.value))).pop();
              await tester.pump();
              await tester.pump();
              previous = 0;
              for (final progress in _progress) {
                await tester.pump(Duration(microseconds:
                  ((progress - previous) * 300000).round()));
                if (progress < 1) {
                  expect(route.animation!.value, closeTo(1 - progress, 0.00001));
                }
                final rect = Rect.lerp(closed, Offset.zero & _size,
                  Curves.fastOutSlowIn.flipped.transform(1 - progress))!;
                frames.add(await _frame(tester, capture, rect,
                  '$prefix-pop-$progress'));
                previous = progress;
              }
              await tester.pumpAndSettle();
              expect(find.byType(ToolsView), findsOneWidget);
              expect(tester.takeException(), isNull);
              samples.add(frames);
            } finally {
              await tester.pumpWidget(const SizedBox.shrink());
              container.dispose();
            }
          }
          final groundDifference = _rgbDifference(_grounds[0], _grounds[1]);
          final transmission = <double>[];
          for (var frame = 0; frame < samples.first.length; frame++) {
            var difference = 0.0;
            for (var channel = 0; channel < 3; channel++) {
              difference += (samples[0][frame][channel] - samples[1][frame][channel]).abs();
            }
            transmission.add(difference / groundDifference);
          }
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            await tester.runAsync(() => File('$output/settings-transition-'
              '${brightness.name}-${quality?.name ?? 'none'}-'
              '${entry.key.replaceAll(' ', '-')}.json').writeAsString(jsonEncode({
                'shaderFilterSupported': ui.ImageFilter.isShaderFilterSupported,
                'rendering': 'Flutter offscreen, not an Android device GPU claim',
                'progress': _progress,
                'pushTransmission': transmission.take(6).toList(),
                'popTransmission': transmission.skip(6).toList(),
                'groundSamples': samples,
              })));
          }
          for (var frame = 0; frame < transmission.length; frame++) {
            if (quality == null) {
              expect(transmission[frame], closeTo(0, 1 / 255),
                reason: 'None retains its opaque transition at frame $frame.');
            } else {
              expect(transmission[frame], greaterThanOrEqualTo(0.5),
                reason: 'The moving glass surface must transmit both backdrops '
                    'throughout push/pop, frame $frame.');
            }
          }
        });
      }
    }
  }
}

double _rgbDifference(Color a, Color b) =>
    ((a.r - b.r).abs() + (a.g - b.g).abs() + (a.b - b.b).abs()) * 255;

Future<List<double>> _frame(WidgetTester tester, GlobalKey key, Rect rect, String name) async {
  expect(tester.takeException(), isNull);
  final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  expect(boundary.size, _size);
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final pixels = (await image.toByteData())!;
      final channels = List<double>.filled(3, 0);
      var count = 0;
      for (var y = (rect.top + rect.height * 0.25).ceil();
          y < (rect.top + rect.height * 0.75).floor(); y++) {
        for (var x = (rect.left + 4).ceil(); x < (rect.left + 10).floor(); x++) {
          if (x < 0 || x >= image.width || y < 0 || y >= image.height) continue;
          final at = (y * image.width + x) * 4;
          for (var channel = 0; channel < 3; channel++) {
            channels[channel] += pixels.getUint8(at + channel);
          }
          count++;
        }
      }
      expect(count, greaterThan(0));
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        await Directory(output).create(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        await File('$output/$name.png').writeAsBytes(
          png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes));
      }
      return [for (final channel in channels) channel / count];
    } finally {
      image.dispose();
    }
  }))!;
}
