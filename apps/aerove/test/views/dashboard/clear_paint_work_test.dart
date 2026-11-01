import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/service_probe.dart' show routedOutbound;
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/preferences.dart';
import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

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

bool _emptyDecoration(OutboundDropletPainter painter) =>
    painter.color == null &&
    painter.shadow.a <= 0 &&
    painter.rim == null &&
    painter.ring == null;

void main() {
  setUp(() async {
    await mockPreferences();
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
  });

  tearDown(() {
    debugOnOutboundDropletPaint = null;
    debugOnOutboundDropletPath = null;
  });

  testWidgets('clear dashboard scrolling skips an empty decorative path', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(412, 480);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(
          () => TestProfiles(const [
            Profile(id: 1, autoUpdateDuration: Duration.zero),
          ]),
        ),
        isStartProvider.overrideWith(
          (ref) => ref.watch(runTimeProvider) != null,
        ),
        suspendProvider.overrideWith((ref) => false),
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
    container
      ..listen(patchClashConfigProvider, (_, _) {})
      ..listen(vpnSettingProvider, (_, _) {});
    container.read(viewSizeProvider.notifier).value = const Size(412, 480);
    container.read(runTimeProvider.notifier).value = 754000;
    container
        .read(appSettingProvider.notifier)
        .update(
          (state) => state.copyWith(
            dashboardWidgets: const [
              DashboardWidget.currentOutbound,
              DashboardWidget.outboundMode,
              DashboardWidget.vpnButton,
              DashboardWidget.intranetIp,
              DashboardWidget.profiles,
            ],
          ),
        );
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      container.dispose();
      await tester.pump(const Duration(seconds: 1));
    });
    final scene = GlobalKey();
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: RepaintBoundary(
          key: scene,
          child: TestApp(
            locale: const Locale('en'),
            child: LiquidGlassScope(
              enabled: true,
              foregroundEnabled: true,
              quality: LiquidGlassQuality.ultra,
              child: LiquidGlassHost(
                enabled: true,
                wallpaperOnly: true,
                child: Stack(
                  fit: StackFit.expand,
                  children: const [
                    Positioned.fill(
                      child: LiquidGlassColorField(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              colors: [Color(0xFF1B5E4A), Color(0xFF4A1B5E)],
                            ),
                          ),
                        ),
                      ),
                    ),
                    AuroraMotionActivity(
                      child: DashboardView(platform: SupportPlatform.Android),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
    for (var frame = 0; frame < 60; frame++) {
      await tester.pump(const Duration(microseconds: 16667));
    }
    expect(find.byType(CommonScaffold), findsOneWidget);
    final toggle = find
        .descendant(
          of: find.byType(CurrentOutboundCard),
          matching: find.byWidgetPredicate(
            (widget) =>
                widget is CustomPaint &&
                widget.painter is OutboundDropletPainter &&
                (widget.painter! as OutboundDropletPainter).color == null,
          ),
        )
        .first;
    OutboundDropletPainter decoration() =>
        tester.widget<CustomPaint>(toggle).painter! as OutboundDropletPainter;
    expect(OutboundFinish.of(tester.element(toggle)), OutboundFinish.clear);
    expect(_emptyDecoration(decoration()), isTrue);
    final skin = decoration().skin!;
    final before = skin.time;
    var paints = 0;
    var paths = 0;
    debugOnOutboundDropletPaint = (painter) {
      if (painter.skin == skin && _emptyDecoration(painter)) paints++;
    };
    debugOnOutboundDropletPath = (painter) {
      if (painter.skin == skin && _emptyDecoration(painter)) paths++;
    };

    Future<void> capture(String name) async {
      final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (directory == null) return;
      await tester.runAsync(() async {
        final boundary =
            scene.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        final image = await boundary.toImage(pixelRatio: 1);
        try {
          final rgba = await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          );
          final png = await image.toByteData(format: ui.ImageByteFormat.png);
          final output = Directory('$directory/clear_paint_work');
          await output.create(recursive: true);
          await File('${output.path}/$name.rgba').writeAsBytes(
            rgba!.buffer.asUint8List(rgba.offsetInBytes, rgba.lengthInBytes),
          );
          await File('${output.path}/$name.png').writeAsBytes(
            png!.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
          );
        } finally {
          image.dispose();
        }
      });
    }

    await capture('top');
    final scrollable = find
        .descendant(
          of: find.byType(DashboardView),
          matching: find.byType(Scrollable),
        )
        .first;
    final position = tester.state<ScrollableState>(scrollable).position;
    expect(position.maxScrollExtent, greaterThan(0));
    final offsets = <double>[];
    final gesture = await tester.startGesture(const Offset(200, 400));
    for (var frame = 0; frame < 8; frame++) {
      await gesture.moveBy(const Offset(0, -16));
      await tester.pump(const Duration(microseconds: 16667));
      offsets.add(position.pixels);
    }
    await capture('drag');
    await gesture.cancel();
    for (var frame = 0; frame < 30; frame++) {
      await tester.pump(const Duration(microseconds: 16667));
    }
    position.jumpTo(0);
    await tester.pump();
    for (var frame = 0; frame < 30; frame++) {
      await tester.pump(const Duration(microseconds: 16667));
    }
    await capture('return');
    final runningPaints = paints;
    final runningPaths = paths;
    final after = skin.time;
    expect(offsets.last, greaterThan(offsets.first));
    expect(after, greaterThan(before), reason: 'The real drift must advance');
    expect(runningPaints, greaterThan(0));

    Focus.of(tester.element(toggle)).requestFocus();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(decoration().ring, isNotNull);
    await capture('focus');
    Focus.of(tester.element(toggle)).unfocus();
    await tester.pump();
    container.read(runTimeProvider.notifier).value = null;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(decoration().fullness, isNot(1));
    await capture('stop_morph');
    container.read(runTimeProvider.notifier).value = 754000;
    await tester.pump();
    for (var frame = 0; frame < 60; frame++) {
      await tester.pump(const Duration(microseconds: 16667));
    }
    await capture('restart');

    debugOnOutboundDropletPaint = null;
    debugOnOutboundDropletPath = null;
    final emptyPainter = decoration();
    expect(_emptyDecoration(emptyPainter), isTrue);
    final alpha = await tester.runAsync(() async {
      final recorder = ui.PictureRecorder();
      emptyPainter.paint(Canvas(recorder), tester.getSize(toggle));
      final picture = recorder.endRecording();
      final image = await picture.toImage(128, 128);
      try {
        final bytes = await image.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        );
        final data = bytes!.buffer.asUint8List(
          bytes.offsetInBytes,
          bytes.lengthInBytes,
        );
        return [
          for (var i = 3; i < data.length; i += 4) data[i],
        ].reduce((a, b) => a + b);
      } finally {
        image.dispose();
        picture.dispose();
      }
    });
    expect(alpha, 0, reason: 'The actual decoration produces no pixels');
    final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (directory != null) {
      await tester.runAsync(
        () => File('$directory/clear_paint_work/work.json').writeAsString(
          jsonEncode({
            'scene': 'Real DashboardView/CommonScaffold; not an Android render',
            'size': [412, 480],
            'maxScrollExtent': position.maxScrollExtent,
            'offsets': offsets,
            'runningPaints': runningPaints,
            'runningPaths': runningPaths,
            'allEmptyPaints': paints,
            'allEmptyPaths': paths,
            'before': before,
            'after': after,
            'decorationAlpha': alpha,
          }),
        ),
      );
    }
    expect(tester.takeException(), isNull);
    expect(
      paths,
      0,
      reason:
          'An empty decoration must not build a path; the glass clipper '
          'still owns its visible outline',
    );
  });

  testWidgets('color, transparent highlight, shadow, rim and ring keep paths', (
    tester,
  ) async {
    const cases = <String, OutboundDropletPainter>{
      'color': OutboundDropletPainter(color: Colors.blue),
      'transparent_highlight': OutboundDropletPainter(
        color: Colors.transparent,
      ),
      'shadow': OutboundDropletPainter(color: null, shadow: Colors.black54),
      'rim': OutboundDropletPainter(color: null, rim: Colors.blue),
      'ring': OutboundDropletPainter(
        color: null,
        ring: BorderSide(color: Colors.blue, width: 2),
      ),
    };
    for (final entry in cases.entries) {
      var paths = 0;
      debugOnOutboundDropletPath = (_) => paths++;
      await tester.pumpWidget(
        Center(
          child: SizedBox.square(
            dimension: 96,
            child: CustomPaint(painter: entry.value),
          ),
        ),
      );
      expect(paths, greaterThan(0), reason: entry.key);
      expect(
        tester.renderObject(find.byType(CustomPaint)),
        paints..path(),
        reason: entry.key,
      );
    }
  });
}
