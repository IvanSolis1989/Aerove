import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/memory_info.dart';
import 'package:fl_clash/views/dashboard/widgets/profiles.dart';
import 'package:fl_clash/views/dashboard/widgets/proxy_groups.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

const _width = 430;
const _height = 950;
const _dpr = 3;
const _name = 'Science subscription with a long neutral display name';
const _node = 'Long selected neutral node name with trailing details';
const _group = Group(
  type: GroupType.Selector,
  name: 'Route',
  now: _node,
  all: [Proxy(name: _node, type: 'ss')],
);
const _profile = Profile(
  id: 1,
  label: _name,
  type: ProfileType.file,
  autoUpdateDuration: Duration.zero,
  subscriptionInfo: SubscriptionInfo(download: 4 << 30, total: 8 << 30),
);

Color _pixel(List<int> bytes, int x, int y) {
  final i = (y * _width * _dpr + x) * 4;
  return Color.fromARGB(bytes[i + 3], bytes[i], bytes[i + 1], bytes[i + 2]);
}

double _distance(Color a, Color b) =>
    math.max((a.r - b.r).abs(), math.max((a.g - b.g).abs(), (a.b - b.b).abs()));

double _contrast(Color a, Color b) {
  final x = a.computeLuminance(), y = b.computeLuminance();
  return (math.max(x, y) + 0.05) / (math.min(x, y) + 0.05);
}

Future<MemorySnapshot> _memory() async =>
    const MemorySnapshot(app: 32 << 20, coreTotal: 96 << 20);

void main() {
  setUpAll(() async {
    final font = FontLoader('DashboardForeground')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('dashboard data and units take local color at DPR3 '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = Size(
          (_width * _dpr).toDouble(),
          (_height * _dpr).toDouble(),
        );
        tester.view.devicePixelRatio = _dpr.toDouble();
        addTearDown(tester.view.reset);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        final container = ProviderContainer(
          overrides: [
            profilesProvider.overrideWith(() => TestProfiles([_profile])),
            currentProfileProvider.overrideWithValue(_profile),
            selectedProxyNameProvider('Route').overrideWithValue(_node),
            visibleGroupsStateProvider.overrideWithValue(
              const GroupsState(value: [_group]),
            ),
          ],
        );
        globalState.container = container;
        addTearDown(container.dispose);
        final scheme = auroraColorScheme(brightness);
        final fields = brightness == Brightness.light
            ? const [Color(0xFFBDE5EC), Color(0xFFEAD0E8)]
            : const [Color(0xFF173940), Color(0xFF3B203B)];
        final capture = GlobalKey();
        Future<List<int>> render(
          String tag, {
          bool ink = true,
          bool glass = true,
          bool moved = false,
        }) async {
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: TestApp(
                includeNavigatorKey: false,
                locale: const Locale('en'),
                theme: ThemeData(
                  colorScheme: scheme,
                  fontFamily: 'DashboardForeground',
                ).withAppTypography,
                themeMode: ThemeMode.light,
                child: RepaintBoundary(
                  key: capture,
                  child: LiquidGlassScope(
                    enabled: glass,
                    quality: quality,
                    child: LiquidGlassHost(
                      enabled: true,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          Positioned.fill(
                            child: ink
                                ? LiquidGlassColorField(
                                    child: CustomPaint(
                                      painter: _Field(fields, moved),
                                    ),
                                  )
                                : CustomPaint(painter: _Field(fields, moved)),
                          ),
                          Padding(
                            padding: const EdgeInsets.all(16),
                            child: DashboardDensityScope(
                              density: DashboardDensity.mobile,
                              scale: 1,
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                spacing: 12,
                                children: [
                                  const ProfilesCard(),
                                  const ProxyGroupsSummaryCard(),
                                  const MemoryInfo(memoryReader: _memory),
                                  const DashboardDensityScope(
                                    density: DashboardDensity.standard,
                                    scale: 1,
                                    child: SizedBox(
                                      height: 190,
                                      child: ProxyGroupsCard(),
                                    ),
                                  ),
                                  Builder(
                                    builder: (context) => DashboardFitText(
                                      '203.0.113.123',
                                      style: AppTextStyle.title1.of(context),
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
            ),
          );
          await tester.pumpAndSettle();
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          return (await tester.runAsync(() async {
            final image = await boundary.toImage(pixelRatio: _dpr.toDouble());
            try {
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                Directory(output).createSync(recursive: true);
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                File(
                  '$output/dashboard-data-${brightness.name}-'
                  '${quality.name}-$tag.png',
                ).writeAsBytesSync(png.buffer.asUint8List());
              }
              return (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!.buffer.asUint8List();
            } finally {
              image.dispose();
            }
          }))!;
        }

        final plain = await render('plain', ink: false);
        final paragraphs =
            <({String text, Rect bounds, Color source, double contrast})>[];
        for (final widget in tester.widgetList<GlassText>(
          find.byWidgetPredicate(
            (widget) =>
                widget is GlassText && widget.inkRole == GlassInkRole.body,
          ),
        )) {
          final paragraph = find.descendant(
            of: find.byWidget(widget),
            matching: find.byType(RichText),
          );
          expect(paragraph, findsOneWidget);
          final text = widget.data ?? widget.textSpan!.toPlainText();
          final context = tester.element(find.byWidget(widget));
          final style = DefaultTextStyle.of(context).style.merge(widget.style);
          paragraphs.add((
            text: text,
            bounds: tester.getRect(paragraph),
            source: style.color ?? scheme.onSurface,
            contrast: 4.5,
          ));
          final span = widget.textSpan;
          if (span is TextSpan && span.children != null) {
            var offset = span.text?.length ?? 0;
            final render = tester.renderObject<RenderParagraph>(paragraph);
            for (final child in span.children!) {
              final value = child.toPlainText();
              final boxes = render.getBoxesForSelection(
                TextSelection(
                  baseOffset: offset,
                  extentOffset: offset + value.length,
                ),
              );
              if (boxes.isNotEmpty && value.trim().isNotEmpty) {
                final box = boxes
                    .map((box) => box.toRect())
                    .reduce((a, b) => a.expandToInclude(b));
                paragraphs.add((
                  text: '$text [${value.trim()}]',
                  bounds: box.shift(render.localToGlobal(Offset.zero)),
                  source: style.color ?? scheme.onSurface,
                  contrast: 4.5,
                ));
              }
              offset += value.length;
            }
          }
        }
        expect(paragraphs.any((entry) => entry.text == _name), isTrue);
        expect(paragraphs.any((entry) => entry.text == _node), isTrue);
        expect(paragraphs.any((entry) => entry.text.contains('50%')), isTrue);
        expect(paragraphs.any((entry) => entry.text.contains('128')), isTrue);
        expect(paragraphs.any((entry) => entry.text.contains('group')), isTrue);
        final wave = find.descendant(
          of: find.byType(ProfilesCard),
          matching: find.byWidgetPredicate(
            (widget) =>
                widget is SizedBox &&
                widget.height == 12 &&
                widget.child is Stack,
          ),
        );
        expect(wave, findsOneWidget);
        paragraphs.add((
          text: 'subscription-active-wave',
          bounds: tester.getRect(wave),
          source: scheme.onSurface,
          contrast: 3.0,
        ));
        final actual = await render('actual');
        final moved = await render('moved', moved: true);
        final reports = <Map<String, Object?>>[];
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        for (final paragraph in paragraphs) {
          var supported = 0, colored = 0, responding = 0;
          var minimumContrast = double.infinity;
          final bounds = paragraph.bounds;
          for (
            var y = (bounds.top * _dpr).ceil();
            y < (bounds.bottom * _dpr).floor();
            y++
          ) {
            for (
              var x = (bounds.left * _dpr).ceil();
              x < (bounds.right * _dpr).floor();
              x++
            ) {
              final field = Color.lerp(
                fields[0],
                fields[1],
                (x + 0.5) / (_width * _dpr),
              )!;
              final source = Color.alphaBlend(paragraph.source, field);
              if (_distance(_pixel(plain, x, y), source) > 1 / 255) {
                continue;
              }
              supported++;
              final a = _pixel(actual, x, y), b = _pixel(moved, x, y);
              if (_distance(a, source) > 0.05 &&
                  HSVColor.fromColor(a).saturation > 0.08)
                colored++;
              if (_distance(a, b) > 0.01) responding++;
              minimumContrast = math.min(minimumContrast, _contrast(a, field));
              expect(a.a, 1);
            }
          }
          reports.add({
            'text': paragraph.text,
            'opaquePixels': supported,
            'colored': colored,
            'responding': responding,
            'minimumContrast': minimumContrast,
          });
          if (output != null) {
            File(
              '$output/dashboard-data-${brightness.name}-${quality.name}.json',
            ).writeAsStringSync(jsonEncode(reports));
          }
          expect(supported, greaterThan(20), reason: paragraph.text);
          expect(
            colored / supported,
            greaterThan(0.85),
            reason: paragraph.text,
          );
          expect(
            responding / supported,
            greaterThan(0.85),
            reason: paragraph.text,
          );
          expect(
            minimumContrast,
            greaterThanOrEqualTo(paragraph.contrast),
            reason: paragraph.text,
          );
        }
        final none = await render('none', glass: false);
        final noneControl = await render(
          'none-control',
          glass: false,
          ink: false,
        );
        var noneDifference = 0;
        for (var i = 0; i < none.length; i++) {
          noneDifference = math.max(
            noneDifference,
            (none[i] - noneControl[i]).abs(),
          );
        }
        expect(noneDifference, 0);
        expect(find.byType(GlassInk), findsWidgets);
        expect(LiquidGlassHost.debugLastInkCapture!.masks, 0);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
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
    final bounds = Offset.zero & size;
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = LinearGradient(
          colors: moved ? colors.reversed.toList() : colors,
        ).createShader(bounds),
    );
  }

  @override
  bool shouldRepaint(_Field oldDelegate) =>
      oldDelegate.moved != moved || oldDelegate.colors != colors;
}
