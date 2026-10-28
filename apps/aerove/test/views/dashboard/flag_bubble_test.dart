import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/service_probe.dart' show routedOutbound;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:fl_clash/ui/native_emoji_cover.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';

const _ip = '203.0.113.9';

const _residential = IpQuality(
  ip: _ip,
  source: IpQualitySource.ipLocate,
  type: IpType.residential,
  organization: 'Example Broadband',
  asn: 64500,
);

Widget _app(Future<IpQuality> quality, DashboardDensity density) => TestApp(
  locale: const Locale('en'),
  overrides: [
    profilesProvider.overrideWithValue(const [
      Profile(id: 1, autoUpdateDuration: Duration.zero),
    ]),
    suspendProvider.overrideWithValue(false),
    outboundIpProbeProvider.overrideWithBuild(
      (_, _) => const RoutedProbeState({
        routedOutbound: ProbeEntry(
          phase: ProbePhase.fresh,
          value: IpInfo(ip: _ip, countryCode: 'DE'),
        ),
      }),
    ),
    ipQualityProvider.overrideWith((_, _) => quality),
  ],
  child: Scaffold(
    body: Align(
      alignment: Alignment.topLeft,
      child: SizedBox(
        width: density.isDesktop ? 600 : 380,
        height: density.isDesktop ? 208 : null,
        child: DashboardDensityScope(
          density: density,
          scale: 1,
          child: const PageActivityScope(
            isActive: true,
            child: CurrentOutboundCard(),
          ),
        ),
      ),
    ),
  ),
);

OutboundDropletPainter _bubble(WidgetTester tester) =>
    tester
            .widget<CustomPaint>(
              find
                  .descendant(
                    of: find.byType(OutboundBubble),
                    matching: find.byWidgetPredicate(
                      (widget) =>
                          widget is CustomPaint &&
                          widget.painter is OutboundDropletPainter,
                    ),
                  )
                  .first,
            )
            .painter!
        as OutboundDropletPainter;

/// Paints the badge's own layer three badges wide on a clear background at a
/// device pixel ratio of [ratio], so paint escaping the badge box shows too,
/// and checks that no flag pixel lies beyond the bubble's skin.
Future<void> _expectFlagInBubble(
  WidgetTester tester,
  DashboardDensity density, {
  required bool settled,
}) async {
  const ratio = 4.0;
  final width = density.isDesktop ? 600.0 : 380.0;
  tester.view.physicalSize = Size(width * ratio, 1600 * ratio);
  tester.view.devicePixelRatio = ratio;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final quality = Completer<IpQuality>();
  await tester.pumpWidget(_app(quality.future, density));
  await tester.pump();
  if (settled) {
    quality.complete(_residential);
    await tester.pumpAndSettle();
  } else {
    await tester.pump(const Duration(milliseconds: 600));
  }
  final painter = _bubble(tester);
  final time = painter.time;
  final wobble = painter.wobble;
  expect(wobble, settled ? 0 : 1);
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find
        .descendant(
          of: find.byType(OutboundBubble),
          matching: find.byType(RepaintBoundary),
        )
        .first,
  );
  final size = boundary.size;
  final layer = boundary.debugLayer! as OffsetLayer;
  late ByteData bytes;
  late int stride;
  await tester.runAsync(() async {
    final image = await layer.toImage(
      Rect.fromLTWH(-size.width, -size.height, size.width * 3, size.height * 3),
      pixelRatio: ratio,
    );
    stride = image.width;
    bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
    image.dispose();
  });
  final skin = OutboundDropletClipper(
    time: time,
    wobble: wobble,
    irregularity: wobble.abs(),
  ).getClip(size);
  final centre = size.center(Offset.zero);
  // One device pixel of antialiasing beyond the skin.
  const reach = 1.5 / ratio;
  final grow = 1 + reach / (size.shortestSide * 0.3);
  final outer = skin.transform(
    (Matrix4.identity()
          ..translateByDouble(centre.dx, centre.dy, 0, 1)
          ..scaleByDouble(grow, grow, 1, 1)
          ..translateByDouble(-centre.dx, -centre.dy, 0, 1))
        .storage,
  );
  var escaped = 0;
  var farthest = 0.0;
  final rows = bytes.lengthInBytes ~/ 4 ~/ stride;
  for (var py = 0; py < rows; py++) {
    for (var px = 0; px < stride; px++) {
      if (bytes.getUint8((py * stride + px) * 4 + 3) == 0) continue;
      final point = Offset(
        (px + 0.5) / ratio - size.width,
        (py + 0.5) / ratio - size.height,
      );
      farthest = math.max(farthest, (point - centre).distance);
      if (!outer.contains(point)) escaped++;
    }
  }
  expect(
    escaped,
    0,
    reason:
        'flag paint reaches ${farthest.toStringAsFixed(2)} from the centre '
        'of a ${size.width} badge',
  );
  // Only the bundled desktop emoji font draws a flag under the test fonts.
  if (!settled || !useDesktopEmojiFont(defaultTargetPlatform)) return;
  // At rest the bubble is a circle of 0.41 of the badge: its middle stays
  // opaque, its rim fades out, and it shows the flag as wide as before.
  int alphaAt(Offset point) {
    final px = ((point.dx + size.width) * ratio).floor();
    final py = ((point.dy + size.height) * ratio).floor();
    return bytes.getUint8((py * stride + px) * 4 + 3);
  }

  final rest = size.shortestSide * 0.41;
  for (var step = 0; step < 16; step++) {
    final angle = step * math.pi / 8;
    final ray = Offset(math.cos(angle), math.sin(angle));
    expect(alphaAt(centre + ray * rest * 0.85), 255, reason: 'ray $step');
    expect(
      alphaAt(centre + ray * (rest - 0.25)),
      lessThan(64),
      reason: 'ray $step',
    );
  }
  expect(farthest, greaterThan(rest - 1));
}

void main() {
  setUpAll(() async {
    final loader = FontLoader(desktopEmojiFontFamily)
      ..addFont(rootBundle.load('assets/fonts/Twemoji.Mozilla.ttf'));
    await loader.load();
  });

  testWidgets('native ink invalidates the resting bubble snapshot', (
    tester,
  ) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    NativeEmojiCover.debugFontFamily = desktopEmojiFontFamily;
    try {
      final key = GlobalKey();
      await tester.pumpWidget(
        RepaintBoundary(
          key: key,
          child: _app(Future.value(_residential), DashboardDensity.mobile),
        ),
      );
      await tester.pumpAndSettle();
      final ink = find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_EmojiInkPainter',
      );
      for (
        var attempt = 0;
        attempt < 500 && ink.evaluate().isEmpty;
        attempt++
      ) {
        await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 20)),
        );
        await tester.pump();
      }
      expect(ink, findsOneWidget);
      await tester.pump();
      await tester.pump();
      final rect = tester.getRect(find.byType(NativeEmojiCover));
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final red = await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final bytes = (await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          ))!;
          var count = 0;
          for (var y = rect.top.ceil(); y < rect.bottom.floor(); y++) {
            for (var x = rect.left.ceil(); x < rect.right.floor(); x++) {
              final offset = (y * image.width + x) * 4;
              if (bytes.getUint8(offset) > 180 &&
                  bytes.getUint8(offset + 1) < 80 &&
                  bytes.getUint8(offset + 2) < 80) {
                count++;
              }
            }
          }
          return count;
        } finally {
          image.dispose();
        }
      });
      expect(
        red,
        greaterThan(10),
        reason:
            'The settled snapshot must contain the measured native color flag, not the initial test-font fallback',
      );
      await tester.pumpWidget(const SizedBox());
      expect(tester.takeException(), isNull);
    } finally {
      debugDefaultTargetPlatformOverride = null;
      NativeEmojiCover.debugFontFamily = null;
    }
  });

  for (final platform in [TargetPlatform.android, TargetPlatform.windows]) {
    for (final density in DashboardDensity.values) {
      for (final settled in [false, true]) {
        testWidgets(
          'the ${platform.name} flag stays inside its ${density.name} bubble '
          '${settled ? 'at rest' : 'while it writhes'}',
          (tester) async {
            debugDefaultTargetPlatformOverride = platform;
            try {
              await _expectFlagInBubble(tester, density, settled: settled);
            } finally {
              debugDefaultTargetPlatformOverride = null;
            }
          },
        );
      }
    }
  }
}
