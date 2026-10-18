import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/providers/service_status.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _organization = 'PT TELKOM INDONESIA';
const _ip = '203.0.113.9';
const _targets = [
  ServiceTarget.google,
  ServiceTarget.github,
  ServiceTarget.youtube,
  ServiceTarget.chatgpt,
];

class _Ips extends OutboundIpProbe {
  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: _ip, countryCode: 'ID'),
    ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

class _Services extends ServiceStatus {
  @override
  RoutedProbeState<ServiceTarget, ServiceCheck> build() => RoutedProbeState({
    for (final target in _targets)
      target: const ProbeEntry(
        phase: ProbePhase.fresh,
        value: ServiceCheck(ServiceProbeStatus.available, delay: 42),
      ),
  });

  @override
  void watch(ServiceTarget target) {}

  @override
  void unwatch(ServiceTarget target) {}
}

InlineSpan _paintSpan(InlineSpan span, Color color) {
  final text = span as TextSpan;
  return TextSpan(
    text: text.text,
    style: text.style?.copyWith(color: color, shadows: const []),
    children: text.children?.map((child) => _paintSpan(child, color)).toList(),
  );
}

Finder _paragraphs(String text) => find.byWidgetPredicate(
  (widget) => widget is RichText && widget.text.toPlainText() == text,
);

Future<({ByteData rgba, Uint8List png, int width, int height})> _capture(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
) async => (await tester.runAsync(() async {
  final image = await boundary.toImage();
  try {
    return (
      rgba: (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!,
      png: (await image.toByteData(
        format: ui.ImageByteFormat.png,
      ))!.buffer.asUint8List(),
      width: image.width,
      height: image.height,
    );
  } finally {
    image.dispose();
  }
}))!;

Future<List<Map<String, Object?>>> _measure(
  WidgetTester tester,
  RenderRepaintBoundary boundary,
  List<RenderParagraph> paragraphs,
  String name,
) async {
  final painted = await _capture(tester, boundary);
  final original = [for (final paragraph in paragraphs) paragraph.text];
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  final geometry = <Map<String, Object?>>[];
  final regions = <Rect>[];
  for (final paragraph in paragraphs) {
    final painter = TextPainter(
      text: _paintSpan(paragraph.text, Colors.white),
      textAlign: paragraph.textAlign,
      textDirection: paragraph.textDirection,
      textScaler: paragraph.textScaler,
      maxLines: paragraph.maxLines,
      ellipsis: paragraph.overflow == TextOverflow.ellipsis ? '\u2026' : null,
      locale: paragraph.locale,
      strutStyle: paragraph.strutStyle,
      textWidthBasis: paragraph.textWidthBasis,
      textHeightBehavior: paragraph.textHeightBehavior,
    )..layout(minWidth: paragraph.size.width, maxWidth: paragraph.size.width);
    final origin = paragraph.localToGlobal(Offset.zero, ancestor: boundary);
    painter.paint(canvas, origin);
    regions.add((origin & paragraph.size).inflate(4));
    final clips = <String>[];
    RenderObject child = paragraph;
    while (child.parent != null) {
      final parent = child.parent!;
      final clip = parent.describeApproximatePaintClip(child);
      if (clip != null) {
        final rect = MatrixUtils.transformRect(
          parent.getTransformTo(boundary),
          clip,
        );
        clips.add('${parent.runtimeType}:$rect');
      }
      if (parent == boundary) break;
      child = parent;
    }
    geometry.add({
      'text': paragraph.text.toPlainText(),
      'paragraph': (origin & paragraph.size).toString(),
      'ellipsis': painter.didExceedMaxLines,
      'clips': clips,
    });
    painter.dispose();
  }
  final picture = recorder.endRecording();
  final mask = picture.toImageSync(painted.width, painted.height);
  picture.dispose();
  late ByteData expected;
  Uint8List? maskPng;
  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
  try {
    expected = (await tester.runAsync<ByteData>(
      () async => (await mask.toByteData(format: ui.ImageByteFormat.rawRgba))!,
    ))!;
    if (output != null) {
      maskPng = (await tester.runAsync<ByteData>(
        () async => (await mask.toByteData(format: ui.ImageByteFormat.png))!,
      ))!.buffer.asUint8List();
    }
  } finally {
    mask.dispose();
  }
  late ({ByteData rgba, Uint8List png, int width, int height}) blank;
  try {
    for (var i = 0; i < paragraphs.length; i++) {
      paragraphs[i].text = _paintSpan(original[i], Colors.transparent);
    }
    await tester.pump();
    blank = await _capture(tester, boundary);
  } finally {
    for (var i = 0; i < paragraphs.length; i++) {
      paragraphs[i].text = original[i];
    }
    await tester.pump();
  }
  for (var i = 0; i < geometry.length; i++) {
    final region = regions[i].intersect(
      Rect.fromLTWH(0, 0, painted.width.toDouble(), painted.height.toDouble()),
    );
    final missingRows = <int>[];
    final inkRows = <int>[];
    final visibleRows = <int>[];
    for (var y = region.top.ceil(); y < region.bottom.floor(); y++) {
      var ink = 0;
      var visible = 0;
      for (var x = region.left.ceil(); x < region.right.floor(); x++) {
        final offset = (y * painted.width + x) * 4;
        if (expected.getUint8(offset + 3) < 180) continue;
        ink++;
        var difference = 0;
        for (var channel = 0; channel < 3; channel++) {
          difference +=
              (painted.rgba.getUint8(offset + channel) -
                      blank.rgba.getUint8(offset + channel))
                  .abs();
        }
        if (difference >= 3) visible++;
      }
      if (ink >= 3) {
        inkRows.add(y);
        if (visible >= ink * 0.6) visibleRows.add(y);
        if (visible < ink * 0.6) missingRows.add(y);
      }
    }
    geometry[i].addAll({
      'inkRows': inkRows,
      'visibleRows': visibleRows,
      'missingRows': missingRows,
    });
  }
  if (output != null) {
    final directory = Directory(output)..createSync(recursive: true);
    File(
      '${directory.path}/dashboard-bounds-$name.png',
    ).writeAsBytesSync(painted.png);
    File(
      '${directory.path}/dashboard-bounds-$name-background.png',
    ).writeAsBytesSync(blank.png);
    File(
      '${directory.path}/dashboard-bounds-$name-mask.png',
    ).writeAsBytesSync(maskPng!);
    File(
      '${directory.path}/dashboard-bounds-$name.json',
    ).writeAsStringSync(const JsonEncoder.withIndent('  ').convert(geometry));
  }
  return geometry;
}

Future<GlobalKey> _mountDashboard(
  WidgetTester tester, {
  required Brightness brightness,
  required LiquidGlassMode mode,
  required double scale,
  required double width,
}) async {
  tester.view.physicalSize = Size(width, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(
    overrides: [
      profilesProvider.overrideWithValue(const [
        Profile(id: 1, autoUpdateDuration: Duration.zero),
      ]),
      suspendProvider.overrideWithValue(false),
      outboundIpProbeProvider.overrideWith(_Ips.new),
      serviceStatusProvider.overrideWith(_Services.new),
      enabledServiceTargetsProvider.overrideWithValue(_targets),
      ipQualityProvider.overrideWith(
        (_, _) async => const IpQuality(
          ip: _ip,
          source: IpQualitySource.ipLocate,
          type: IpType.residential,
          organization: _organization,
        ),
      ),
      themeSettingProvider.overrideWithBuild(
        (_, _) => ThemeProps(
          fontFamily: 'DashboardBounds',
          auroraEnabled: mode != LiquidGlassMode.none,
          liquidGlass: mode,
        ),
      ),
      dashboardStateProvider.overrideWithValue(
        const DashboardState(
          dashboardWidgets: [
            DashboardWidget.currentOutbound,
            DashboardWidget.serviceStatus,
          ],
        ),
      ),
      navigationItemsStateProvider.overrideWithValue(
        NavigationItemsState(
          value: [
            NavigationItem(
              glyph: AppGlyphs.dashboard,
              label: PageLabel.dashboard,
              builder: (_) =>
                  const DashboardView(platform: SupportPlatform.Android),
            ),
            NavigationItem(
              glyph: AppGlyphs.tools,
              label: PageLabel.tools,
              builder: (_) => const SizedBox.shrink(),
            ),
          ],
        ),
      ),
    ],
  );
  globalState.container = container;
  addTearDown(container.dispose);
  container.read(viewSizeProvider.notifier).value = Size(width, 1000);
  final capture = GlobalKey();
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: Consumer(
        builder: (_, ref, _) => TestApp(
          includeNavigatorKey: false,
          locale: const Locale('en'),
          theme: watchAppTheme(ref, brightness),
          child: Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(
                textScaler: TextScaler.linear(scale),
                disableAnimations: true,
              ),
              child: RepaintBoundary(
                key: capture,
                child: const AuroraAppSurface(motion: false, child: HomePage()),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return capture;
}

void main() {
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final font = FontLoader('DashboardBounds')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) await GlassInk.prepare();
  });

  testWidgets('glyph detector identifies a vertically clipped real paragraph', (
    tester,
  ) async {
    final capture = GlobalKey();
    await tester.pumpWidget(
      TestApp(
        theme: ThemeData(fontFamily: 'DashboardBounds'),
        homeBuilder: (child) => Scaffold(body: child),
        child: RepaintBoundary(
          key: capture,
          child: const ColoredBox(
            color: Colors.white,
            child: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(
                width: 200,
                height: 12,
                child: ClipRect(
                  child: OverflowBox(
                    alignment: Alignment.topLeft,
                    maxHeight: 80,
                    child: Text(
                      'Google gjy',
                      style: TextStyle(color: Colors.black),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final rows = await _measure(
      tester,
      capture.currentContext!.findRenderObject()! as RenderRepaintBoundary,
      [tester.renderObject<RenderParagraph>(_paragraphs('Google gjy'))],
      'detector-positive-control',
    );
    expect(rows.single['inkRows'], isNotEmpty);
    expect(rows.single['missingRows'], isNotEmpty);
    expect(rows.single['clips'].toString(), contains('RenderClipRect'));
  });

  for (final brightness in Brightness.values) {
    testWidgets('known dashboard horizontal limit matches None at 320dp 2x '
        '${brightness.name}', (tester) async {
      List<Map<String, Object?>>? baselineLayout;
      Map<String, List<double>>? baselineOverflow;
      for (final mode in [LiquidGlassMode.none, LiquidGlassMode.ultra]) {
        final horizontal = <String, Set<double>>{};
        final previousError = FlutterError.onError!;
        FlutterError.onError = (details) {
          final match = RegExp(
            r'A RenderFlex overflowed by ([0-9.]+) pixels on the right\.',
          ).firstMatch(details.exceptionAsString());
          final diagnostics = [
            details.toString(),
            for (final node in details.informationCollector?.call() ??
                <DiagnosticsNode>[])
              node.toStringDeep(minLevel: DiagnosticLevel.hidden),
          ].join('\n');
          final component = diagnostics.contains('DashboardChip')
              ? 'chip'
              : diagnostics.contains('DashboardCardHeader')
              ? 'header'
              : diagnostics.contains('NavigationToolbar')
              ? 'toolbar'
              : null;
          if (match != null && component != null) {
            horizontal
                .putIfAbsent(component, () => <double>{})
                .add(double.parse(match[1]!));
          } else {
            previousError(details);
          }
        };
        try {
          final capture = await _mountDashboard(
            tester,
            brightness: brightness,
            mode: mode,
            scale: 2,
            width: 320,
          );
          final boundary =
              capture.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          final layout = <Map<String, Object?>>[];
          for (final label in [
            _organization,
            ..._targets.map((target) => target.label),
          ]) {
            final paragraph = tester.renderObject<RenderParagraph>(
              _paragraphs(label),
            );
            final origin = paragraph.localToGlobal(
              Offset.zero,
              ancestor: boundary,
            );
            layout.add({
              'text': label,
              'x': origin.dx,
              'y': origin.dy,
              'width': paragraph.size.width,
              'height': paragraph.size.height,
            });
          }
          final overflow = {
            for (final entry in horizontal.entries)
              entry.key: entry.value.toList()..sort(),
          };
          final frame = await _capture(tester, boundary);
          final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
          if (output != null) {
            final directory = Directory(output)..createSync(recursive: true);
            final prefix =
                '${directory.path}/dashboard-known-horizontal-'
                '${brightness.name}-${mode.name}-320-2x';
            File('$prefix.png').writeAsBytesSync(frame.png);
            File('$prefix.json').writeAsStringSync(
              const JsonEncoder.withIndent('  ').convert({
                'knownHorizontalLimit': overflow.isNotEmpty,
                'layout': layout,
                'overflow': overflow,
              }),
            );
          }
          if (baselineLayout == null) {
            baselineLayout = layout;
            baselineOverflow = overflow;
          } else {
            expect(
              layout,
              baselineLayout,
              reason: 'Glass must preserve None layout',
            );
            expect(
              overflow,
              baselineOverflow,
              reason: 'Glass must preserve None overflow',
            );
          }
          expect(tester.takeException(), isNull);
        } finally {
          try {
            await tester.pumpWidget(const SizedBox.shrink());
          } finally {
            FlutterError.onError = previousError;
          }
        }
      }
    }, variant: TargetPlatformVariant.only(TargetPlatform.android));
    for (final mode in LiquidGlassMode.values) {
      for (final scale in [1.0, 1.3]) {
        for (final width in [430.0, 1100.0]) {
          final name = '${brightness.name}-${mode.name}-$scale-$width';
          testWidgets('dashboard glyph rows survive $name', (tester) async {
            final capture = await _mountDashboard(
              tester,
              brightness: brightness,
              mode: mode,
              scale: scale,
              width: width,
            );
            for (final labels in [
              [_organization],
              [for (final target in _targets) target.label],
            ]) {
              expect(_paragraphs(labels.first), findsOneWidget);
              await tester.ensureVisible(_paragraphs(labels.first));
              await tester.pumpAndSettle();
              final paragraphs = [
                for (final label in labels)
                  tester.renderObject<RenderParagraph>(_paragraphs(label)),
                if (labels.first != _organization)
                  for (var i = 0; i < _targets.length; i++)
                    tester.renderObject<RenderParagraph>(
                      _paragraphs('42 ms').at(i),
                    ),
              ];
              final rows = await _measure(
                tester,
                capture.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary,
                paragraphs,
                '$name-${labels.first == _organization ? 'organization' : 'services'}',
              );
              for (final row in rows) {
                if (row['ellipsis'] != true) {
                  expect(row['inkRows'], isNotEmpty, reason: '$row');
                }
                expect(row['missingRows'], isEmpty, reason: '$row');
              }
              expect(tester.takeException(), isNull);
            }
            await tester.pumpWidget(const SizedBox.shrink());
          }, variant: TargetPlatformVariant.only(TargetPlatform.android));
        }
      }
    }
  }
}
