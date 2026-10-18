import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';

Color _pixel(List<int> bytes, Offset point) {
  final index = (point.dy.floor() * 480 + point.dx.floor()) * 4;
  return Color.fromARGB(255, bytes[index], bytes[index + 1], bytes[index + 2]);
}

double _contrast(Color a, Color b) {
  final first = a.computeLuminance();
  final second = b.computeLuminance();
  return first > second
      ? (first + 0.05) / (second + 0.05)
      : (second + 0.05) / (first + 0.05);
}

bool _matches(Color a, Color b) =>
    (a.r - b.r).abs() < 1.5 / 255 &&
    (a.g - b.g).abs() < 1.5 / 255 &&
    (a.b - b.b).abs() < 1.5 / 255;

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    final font = FontLoader('TrafficPreview')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (ui.ImageFilter.isShaderFilterSupported) {
      await GlassInk.prepare();
    }
  });

  testWidgets('zero and unsampled speed retain real zero values', (
    tester,
  ) async {
    await tester.pumpWidget(
      TestApp(
        includeNavigatorKey: false,
        overrides: [trafficsProvider.overrideWithValue(FixedList<Traffic>(30))],
        child: const NetworkSpeedCard(),
      ),
    );
    await tester.pumpAndSettle();
    final figures = tester.widgetList<GlassText>(
      find.byWidgetPredicate(
        (widget) => widget is GlassText && widget.inkRole == GlassInkRole.body,
      ),
    );
    expect(figures.map((text) => text.textSpan!.toPlainText()), [
      '0 B/s',
      '0 B/s',
    ]);
    expect(tester.takeException(), isNull);
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('real speed figures and strokes follow field '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(480, 700);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final capture = GlobalKey();
        final colors = auroraColorScheme(brightness);
        final fields = brightness == Brightness.dark
            ? const [Color(0xFF173940), Color(0xFF3B203B)]
            : const [Color(0xFFBDE5EC), Color(0xFFEAD0E8)];
        final traffic = FixedList<Traffic>(
          30,
          list: List.filled(30, const Traffic(up: 16 * 1024, down: 32 * 1024)),
        );
        Future<List<int>> render(
          String tag, {
          bool glass = true,
          bool ink = true,
          bool moved = false,
        }) async {
          final background = CustomPaint(painter: _Field(fields, moved));
          await tester.pumpWidget(
            TestApp(
              includeNavigatorKey: false,
              locale: const Locale('en'),
              theme: ThemeData(
                colorScheme: colors,
                fontFamily: 'TrafficPreview',
              ),
              darkTheme: ThemeData(
                colorScheme: colors,
                fontFamily: 'TrafficPreview',
              ),
              themeMode: brightness == Brightness.dark
                  ? ThemeMode.dark
                  : ThemeMode.light,
              overrides: [
                trafficsProvider.overrideWithValue(traffic),
                totalTrafficProvider.overrideWithValue(const Traffic()),
              ],
              child: RepaintBoundary(
                key: capture,
                child: LiquidGlassScope(
                  enabled: glass,
                  quality: quality,
                  child: LiquidGlassHost(
                    enabled: glass,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Positioned.fill(
                          child: ink
                              ? LiquidGlassColorField(child: background)
                              : background,
                        ),
                        const Padding(
                          padding: EdgeInsets.all(16),
                          child: DashboardDensityScope(
                            density: DashboardDensity.mobile,
                            scale: 1,
                            child: Column(
                              children: [
                                NetworkSpeedCard(),
                                SizedBox(height: 16),
                                SizedBox(
                                  height: 180,
                                  child: SpeedCard(
                                    direction: TrafficDirection.upload,
                                  ),
                                ),
                              ],
                            ),
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
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final bytes = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!.buffer.asUint8List();
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                Directory(output).createSync(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '$output/speed-ink-${brightness.name}-${quality.name}-$tag.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return bytes;
            } finally {
              image.dispose();
            }
          }))!;
        }

        final none = await render('none', glass: false);
        for (final chart in tester.widgetList<LineChart>(
          find.byType(LineChart),
        )) {
          expect(chart.strokeInk, isNull);
          expect(chart.values.every((value) => value > 0), isTrue);
        }
        final control = await render('neutral-control', ink: false);
        final points = <String, Offset>{};
        final figures = find.byWidgetPredicate(
          (widget) =>
              widget is GlassText && widget.inkRole == GlassInkRole.body,
        );
        expect(figures, findsNWidgets(3));
        final origin = tester.getTopLeft(find.byKey(capture));
        for (var i = 0; i < 3; i++) {
          final rich = find.descendant(
            of: figures.at(i),
            matching: find.byType(RichText),
          );
          final paragraph = tester.renderObject<RenderParagraph>(rich);
          final text = paragraph.text.toPlainText();
          final split = text.indexOf(' ');
          for (final (role, start, end) in [
            ('number', 0, split),
            ('unit', split + 1, text.length),
          ]) {
            final boxes = paragraph.getBoxesForSelection(
              TextSelection(baseOffset: start, extentOffset: end),
            );
            Offset? point;
            for (final box in boxes) {
              final rect = box.toRect().shift(
                paragraph.localToGlobal(Offset.zero) - origin,
              );
              for (
                var y = rect.top.ceil();
                y < rect.bottom && point == null;
                y++
              ) {
                for (var x = rect.left.ceil(); x < rect.right; x++) {
                  final candidate = Offset(x + .5, y + .5);
                  if (_matches(_pixel(control, candidate), colors.onSurface)) {
                    point = candidate;
                    break;
                  }
                }
              }
            }
            expect(point, isNotNull, reason: '$role $i opaque glyph interior');
            points['$role-$i'] = point!;
          }
          final rect = tester
              .getRect(find.byType(LineChart).at(i))
              .shift(-origin);
          final curve = Offset(
            rect.center.dx.floor() + .5,
            rect.top.floor() + 2.5,
          );
          expect(
            _matches(_pixel(control, curve), colors.onSurface),
            isTrue,
            reason: 'curve $i solid stroke interior',
          );
          points['curve-$i'] = curve;
          points['fill-$i'] = rect.center;
        }
        final actual = await render('actual');
        final moved = await render('moved', moved: true);
        final samples = <Map<String, Object?>>[];
        for (final entry in points.entries) {
          final before = _pixel(control, entry.value);
          final after = _pixel(actual, entry.value);
          final changed = _pixel(moved, entry.value);
          samples.add({
            'role': entry.key,
            'point': [entry.value.dx, entry.value.dy],
            'control': before.toARGB32(),
            'actual': after.toARGB32(),
            'moved': changed.toARGB32(),
          });
        }
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null) {
          File(
            '$output/speed-ink-${brightness.name}-${quality.name}-samples.json',
          ).writeAsStringSync(jsonEncode(samples));
        }
        for (final entry in points.entries) {
          final before = _pixel(control, entry.value);
          final after = _pixel(actual, entry.value);
          if (entry.key.startsWith('fill')) {
            expect(after, before, reason: 'area fill is outside the ink mask');
          } else {
            expect(after, isNot(before), reason: entry.key);
            expect(after, isNot(_pixel(moved, entry.value)), reason: entry.key);
            final background = fields[entry.value.dx < 240 ? 0 : 1];
            expect(
              _contrast(after, background),
              greaterThanOrEqualTo(entry.key.startsWith('curve') ? 3 : 4.5),
              reason: entry.key,
            );
          }
        }
        expect(await render('none-restored', glass: false), none);
        expect(tester.takeException(), isNull);
      }, skip: !ui.ImageFilter.isShaderFilterSupported);
    }
  }
}

class _Field extends CustomPainter {
  const _Field(this.colors, this.moved);
  final List<Color> colors;
  final bool moved;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = colors[moved ? 1 : 0]);
    canvas.drawRect(
      Rect.fromLTWH(size.width / 2, 0, size.width / 2, size.height),
      Paint()..color = colors[moved ? 0 : 1],
    );
  }

  @override
  bool shouldRepaint(_Field oldDelegate) => oldDelegate.moved != moved;
}
