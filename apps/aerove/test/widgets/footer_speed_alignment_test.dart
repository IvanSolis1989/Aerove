import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/views/dashboard/widgets/status_layout.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/cjk_test_fonts.dart';
import '../helpers/system_test_font.dart';
import '../helpers/test_app.dart';

InlineSpan _whiteSpan(InlineSpan span) {
  final text = span as TextSpan;
  return TextSpan(
    text: text.text,
    style: text.style?.copyWith(color: Colors.white, shadows: const []),
    children: text.children?.map(_whiteSpan).toList(),
  );
}

Future<int> _overlapPixels(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
  RenderObject body,
  RenderObject figures,
) async {
  ui.Image mask(RenderObject root) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder)..scale(3);
    void visit(RenderObject node) {
      if (node is RenderParagraph) {
        final painter =
            TextPainter(
              text: _whiteSpan(node.text),
              textAlign: node.textAlign,
              textDirection: node.textDirection,
              textScaler: node.textScaler,
              maxLines: node.maxLines,
              ellipsis: node.overflow == TextOverflow.ellipsis
                  ? '\u2026'
                  : null,
              locale: node.locale,
              strutStyle: node.strutStyle,
              textWidthBasis: node.textWidthBasis,
              textHeightBehavior: node.textHeightBehavior,
            )..layout(
              minWidth: node.constraints.minWidth,
              maxWidth: node.constraints.maxWidth,
            );
        canvas.save();
        canvas.transform(node.getTransformTo(boundary).storage);
        painter.paint(canvas, Offset.zero);
        canvas.restore();
        painter.dispose();
      } else if (node is RenderCustomPaint) {
        final paint = node.painter;
        if (paint is GlyphPainter || paint is OutboundDropletPainter) {
          canvas.save();
          canvas.transform(node.getTransformTo(boundary).storage);
          if (paint is GlyphPainter) {
            GlyphPainter(
              glyph: paint.glyph,
              fill: paint.fill,
              color: Colors.white,
            ).paint(canvas, node.size);
          } else if (paint is OutboundDropletPainter) {
            canvas.drawPath(
              paint.pathOf(node.size),
              Paint()..color = Colors.white,
            );
          }
          canvas.restore();
        }
      }
      node.visitChildren(visit);
    }

    visit(root);
    final picture = recorder.endRecording();
    final image = picture.toImageSync(
      (boundary.size.width * 3).ceil(),
      (boundary.size.height * 3).ceil(),
    );
    picture.dispose();
    return image;
  }

  final main = mask(body), speed = mask(figures);
  try {
    final bytes = (await tester.runAsync(
      () async => [
        (await main.toByteData(format: ui.ImageByteFormat.rawRgba))!,
        (await speed.toByteData(format: ui.ImageByteFormat.rawRgba))!,
      ],
    ))!;
    var overlap = 0;
    for (var pixel = 3; pixel < bytes[0].lengthInBytes; pixel += 4) {
      if (bytes[0].getUint8(pixel) >= 180 && bytes[1].getUint8(pixel) >= 180) {
        overlap++;
      }
    }
    return overlap;
  } finally {
    main.dispose();
    speed.dispose();
  }
}

class _Ips extends OutboundIpProbe {
  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: '203.0.113.9', countryCode: 'GB'),
    ),
  });
  @override
  void watch(String target) {}
  @override
  void unwatch(String target) {}
}

void main() {
  const sdk = 'FooterRoboto';
  const miSans = 'FooterMiSans';
  final miSansPath = Platform.environment['XCLASH_MISANS_FONT'];
  var fallbacks = <String>[];
  setUpAll(() async {
    await (FontLoader(sdk)..addFont(loadSdkTestFont())).load();
    fallbacks = await loadCjkTestFonts();
    if (miSansPath != null) {
      await (FontLoader(
            miSans,
          )..addFont(File(miSansPath).readAsBytes().then(ByteData.sublistView)))
          .load();
    }
  });
  for (final family in [sdk, if (miSansPath != null) miSans]) {
    for (final locale in const [
      Locale('en'),
      Locale('zh', 'CN'),
      Locale('ja'),
    ]) {
      for (final (label, width, scale, desktop) in const [
        ('normal', 360.0, 1.0, false),
        ('medium', 360.0, 1.3, false),
        ('large', 328.0, 2.0, false),
        ('desktop', 600.0, 0.9, true),
        ('rtl', 360.0, 1.0, false),
      ]) {
        final name = '$family-${locale.toLanguageTag()}-$label';
        testWidgets('footer speed alignment $name', (tester) async {
          tester.view.physicalSize = const Size(3000, 3600);
          tester.view.devicePixelRatio = 3;
          addTearDown(tester.view.reset);
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWithValue(const [
                Profile(id: 1, autoUpdateDuration: Duration.zero),
              ]),
              isStartProvider.overrideWithValue(true),
              suspendProvider.overrideWithValue(false),
              outboundIpProbeProvider.overrideWith(_Ips.new),
              ipQualityProvider.overrideWith(
                (_, _) async => const IpQuality(
                  ip: '203.0.113.9',
                  source: IpQualitySource.ipLocate,
                  type: IpType.residential,
                  organization: 'Example Organization',
                ),
              ),
            ],
          );
          globalState.container = container;
          addTearDown(() async {
            await tester.pumpWidget(const SizedBox.shrink());
            container.dispose();
          });
          final capture = GlobalKey();
          Future<void> mount(bool showTraffic) async {
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: TestApp(
                  locale: locale,
                  homeBuilder: (child) => Directionality(
                    textDirection: label == 'rtl'
                        ? TextDirection.rtl
                        : TextDirection.ltr,
                    child: child,
                  ),
                  theme: ThemeData(
                    fontFamily: family,
                    fontFamilyFallback: fallbacks,
                  ),
                  child: Scaffold(
                    body: Align(
                      alignment: Alignment.topLeft,
                      child: SizedBox(
                        width: width,
                        height: desktop ? 208 : null,
                        child: RepaintBoundary(
                          key: capture,
                          child: MediaQuery(
                            data: MediaQueryData(
                              devicePixelRatio: 3,
                              textScaler: TextScaler.linear(scale),
                              disableAnimations: true,
                            ),
                            child: DashboardDensityScope(
                              density: desktop
                                  ? DashboardDensity.standard
                                  : DashboardDensity.mobile,
                              scale: desktop ? 1 : scale,
                              child: desktop
                                  ? CurrentOutboundCard(
                                      showTraffic: showTraffic,
                                    )
                                  : Column(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        CurrentOutboundCard(
                                          showTraffic: showTraffic,
                                        ),
                                      ],
                                    ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
            await tester.pump();
          }

          List<(String, Rect)> textRects(RenderObject root) {
            final result = <(String, Rect)>[];
            void visit(RenderObject node) {
              if (node is RenderParagraph) {
                result.add((
                  node.text.toPlainText(),
                  node.localToGlobal(Offset.zero) & node.size,
                ));
              }
              node.visitChildren(visit);
            }

            visit(root);
            return result;
          }

          await mount(false);
          final baselineLayout = tester.renderObject<RenderStatusCardLayout>(
            find.byType(StatusCardLayout),
          );
          final baselineBody = baselineLayout.childAfter(
            baselineLayout.firstChild!,
          )!;
          final baselineBodyRect =
              baselineBody.localToGlobal(Offset.zero) & baselineBody.size;
          final baselineTexts = textRects(baselineBody);
          final baselineCardSize = tester.getSize(find.byType(DashboardCard));
          final baselineL10n = AppLocalizations.of(
            tester.element(find.byType(CurrentOutboundCard)),
          );
          final baselineButton = tester.getRect(
            find.byTooltip(baselineL10n.stop),
          );
          expect(find.byType(OutboundTrafficFigures), findsNothing);
          await mount(true);
          container
              .read(trafficsProvider.notifier)
              .addTraffic(const Traffic(up: 1024, down: 2048));
          await tester.pump();
          final context = tester.element(find.byType(CurrentOutboundCard));
          final l10n = AppLocalizations.of(context);
          final layout = tester.renderObject<RenderStatusCardLayout>(
            find.byType(StatusCardLayout),
          );
          final body = layout.childAfter(layout.firstChild!)!;
          expect(body.localToGlobal(Offset.zero) & body.size, baselineBodyRect);
          expect(textRects(body), baselineTexts);
          expect(tester.getRect(find.byTooltip(l10n.stop)), baselineButton);
          final values = find.descendant(
            of: find.byType(OutboundTrafficFigures),
            matching: find.byType(GlassText),
          );
          expect(values, findsNWidgets(2));
          final rowCenters = [
            for (var i = 0; i < 2; i++) tester.getCenter(values.at(i)).dy,
          ];
          final footerCenters = [
            tester.getCenter(find.text(l10n.runTime)).dy,
            tester
                .getCenter(
                  find.text(getTimeText(container.read(runTimeProvider))),
                )
                .dy,
          ];
          final paragraphs = <Map<String, Object?>>[];
          void visit(RenderObject node) {
            if (node is RenderParagraph) {
              final text = node.text.toPlainText();
              paragraphs.add({
                'text': text,
                'rect': (node.localToGlobal(Offset.zero) & node.size)
                    .toString(),
                'lineBoxes': [
                  for (final box in node.getBoxesForSelection(
                    TextSelection(baseOffset: 0, extentOffset: text.length),
                  ))
                    MatrixUtils.transformRect(
                      node.getTransformTo(null),
                      box.toRect(),
                    ).toString(),
                ],
              });
            }
            node.visitChildren(visit);
          }

          visit(layout);
          final facts = <String, Object?>{
            'name': name,
            'width': width,
            'scale': scale,
            'direction': label == 'rtl' ? 'rtl' : 'ltr',
            'overlaid': layout.overlaid,
            'footerSlotAvailable': layout.footerSlotAvailable,
            'baselineCardHeight': baselineCardSize.height,
            'baselineBodyRect': baselineBodyRect.toString(),
            'baselineTextRects': baselineTexts
                .map((item) => '${item.$1}:${item.$2}')
                .toList(),
            'cardHeight': tester.getSize(find.byType(DashboardCard)).height,
            'bodyRect': (body.localToGlobal(Offset.zero) & body.size)
                .toString(),
            'figuresRect': tester
                .getRect(find.byType(OutboundTrafficFigures))
                .toString(),
            'footerCenters': footerCenters,
            'speedCenters': rowCenters,
            'paragraphs': paragraphs,
          };
          final collisionPixels = await _overlapPixels(
            tester,
            capture.currentContext!.findRenderObject()!
                as RenderRepaintBoundary,
            body,
            tester.renderObject(find.byType(OutboundTrafficFigures)),
          );
          facts['collisionPixels'] = collisionPixels;
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            final directory = Directory(output)..createSync(recursive: true);
            File('${directory.path}/$name.json').writeAsStringSync(
              const JsonEncoder.withIndent('  ').convert(facts),
            );
            await tester.runAsync(() async {
              final boundary =
                  capture.currentContext!.findRenderObject()!
                      as RenderRepaintBoundary;
              final image = await boundary.toImage(pixelRatio: 3);
              final bytes = await image.toByteData(
                format: ui.ImageByteFormat.png,
              );
              File(
                '${directory.path}/$name.png',
              ).writeAsBytesSync(bytes!.buffer.asUint8List());
              image.dispose();
            });
          }
          if (!desktop && scale > 1.3 && layout.overlaid == false) {
            expect(layout.footerSlotAvailable, isFalse);
            expect(
              tester.getRect(find.byType(OutboundTrafficFigures)).top,
              closeTo(
                body.localToGlobal(Offset.zero).dy + body.size.height + 8,
                0.01,
              ),
            );
            expect(
              tester.getSize(find.byType(DashboardCard)).height,
              baselineCardSize.height +
                  8 +
                  tester.getSize(find.byType(OutboundTrafficFigures)).height,
            );
          } else {
            expect(layout.overlaid, isTrue, reason: '$facts');
            expect(layout.footerSlotAvailable, isTrue, reason: '$facts');
            expect(
              tester.getSize(find.byType(DashboardCard)),
              baselineCardSize,
            );
            for (var line = 0; line < 2; line++) {
              expect(
                rowCenters[line],
                closeTo(footerCenters[line], 0.01),
                reason: '$facts',
              );
            }
          }
          expect(collisionPixels, 0, reason: '$facts');
          expect(tester.takeException(), isNull);
        });
      }
    }
  }
}
