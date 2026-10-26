import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../../helpers/dashboard_host.dart';

void main() {
  setUpAll(() => registerFallbackValue(false));

  testWidgets('a running start bubble repaints itself, not the status card', (
    tester,
  ) async {
    await expectOnlyTheBubbleDrifts(await pumpDashboardHost(tester));
  });

  testWidgets('a desktop status card, standing alone in its grid cell, '
      'repaints only its bubble', (tester) async {
    await expectOnlyTheBubbleDrifts(
      await pumpDashboardHost(tester, density: DashboardDensity.standard),
    );
  });

  testWidgets('a sample and a tick of the run time lay out only their own '
      'lines', (tester) async {
    final host = await pumpDashboardHost(tester);
    LayoutCount.counts.clear();
    for (var second = 0; second < 4; second++) {
      await host.second();
    }
    expect(LayoutCount.counts, isEmpty);
  });

  testWidgets('a tick of the run time repaints its own line and no other '
      'text of the card', (tester) async {
    final host = await pumpDashboardHost(tester);
    final painted = <String>[];
    debugOnProfilePaint = (object) {
      if (object is RenderParagraph) painted.add(object.text.toPlainText());
    };
    try {
      for (var second = 0; second < 3; second++) {
        final runTime = host.container.read(runTimeProvider)!;
        host.container.read(runTimeProvider.notifier).value = runTime + 1000;
        await host.second(traffic: false);
      }
    } finally {
      debugOnProfilePaint = null;
    }
    expect(painted, hasLength(3));
    for (final text in painted) {
      expect(text, matches(RegExp(r'^\d{2}:\d{2}:\d{2}$')));
    }
  });

  testWidgets('a status card scrolled out of view lets go of the run time '
      'and takes it back on return', (tester) async {
    final host = await pumpDashboardHost(tester);
    Set<TrafficNeed> demand() => host.container.read(trafficDemandProvider);
    expect(demand(), {TrafficNeed.traffic, TrafficNeed.runTime});

    await tester.drag(find.byType(CustomScrollView), const Offset(0, -700));
    await host.second(traffic: false);
    expect(demand(), {TrafficNeed.traffic});
    var rebuilds = 0;
    debugOnRebuildDirtyWidget = (element, _) {
      final type = '${element.widget.runtimeType}';
      if (!type.endsWith('UncontrolledProviderScope')) rebuilds++;
    };
    try {
      for (var second = 0; second < 3; second++) {
        await host.second(traffic: false);
      }
    } finally {
      debugOnRebuildDirtyWidget = null;
    }
    expect(rebuilds, 0);

    await tester.drag(find.byType(CustomScrollView), const Offset(0, 700));
    await host.second(traffic: false);
    expect(demand(), {TrafficNeed.traffic, TrafficNeed.runTime});
  });

  testWidgets('a dashboard nobody sees asks the Core for no traffic and '
      'redraws nothing', (tester) async {
    final host = await pumpDashboardHost(tester);
    final action = host.container.read(commonActionProvider.notifier);
    await action.updateTraffic();
    verify(() => host.core.getTraffic(any())).called(1);
    verify(() => host.core.getTotalTraffic(any())).called(1);

    await host.show(1);
    expect(host.container.read(trafficDemandProvider), isEmpty);
    var rebuilds = 0;
    var paints = 0;
    // The provider scope itself rebuilds on every provider change.
    debugOnRebuildDirtyWidget = (element, _) {
      final type = '${element.widget.runtimeType}';
      if (!type.endsWith('UncontrolledProviderScope')) rebuilds++;
    };
    debugOnProfilePaint = (_) => paints++;
    try {
      for (var second = 0; second < 3; second++) {
        await action.updateTraffic();
        await host.second();
      }
    } finally {
      debugOnRebuildDirtyWidget = null;
      debugOnProfilePaint = null;
    }
    verifyNever(() => host.core.getTraffic(any()));
    verifyNever(() => host.core.getTotalTraffic(any()));
    expect(rebuilds, 0);
    expect(paints, 0);

    await host.show(0);
    await action.updateTraffic();
    verify(() => host.core.getTraffic(any())).called(1);
  });

  testWidgets('a page pushed over the dashboard stops the sampling until it '
      'is popped', (tester) async {
    final host = await pumpDashboardHost(tester);
    final action = host.container.read(commonActionProvider.notifier);
    await action.updateTraffic();
    verify(() => host.core.getTraffic(any())).called(1);
    verify(() => host.core.getTotalTraffic(any())).called(1);
    final navigator = Navigator.of(
      tester.element(find.byType(CurrentOutboundCard)),
    );
    navigator.push(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: Text('another page')),
      ),
    );
    await tester.pumpAndSettle();
    expect(host.container.read(trafficDemandProvider), isEmpty);
    for (var second = 0; second < 3; second++) {
      await action.updateTraffic();
      await host.second(traffic: false);
    }
    verifyNever(() => host.core.getTraffic(any()));
    verifyNever(() => host.core.getTotalTraffic(any()));

    navigator.pop();
    await tester.pumpAndSettle();
    expect(
      host.container.read(trafficDemandProvider),
      contains(TrafficNeed.traffic),
    );
    await action.updateTraffic();
    verify(() => host.core.getTraffic(any())).called(1);
  });

  testWidgets('a backdrop scrolled away keeps what its curve was given and '
      'builds nothing new', (tester) async {
    final host = await pumpDashboardHost(tester);
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -700));
    await host.second(traffic: false);
    final charts = tester
        .widgetList<LineChart>(find.byType(LineChart))
        .toList();
    final values = List.of(charts.first.values);
    final scale = charts.first.minScale;
    final figures = tester.widget(
      find.descendant(
        of: find.byType(OutboundTrafficFigures),
        matching: find.byType(TrafficReadings),
      ),
    );
    host.container
        .read(trafficsProvider.notifier)
        .addTraffic(const Traffic(up: 50 << 20, down: 90 << 20));
    for (var second = 0; second < 3; second++) {
      await host.second();
    }
    final held = tester.widgetList<LineChart>(find.byType(LineChart)).toList();
    expect(held.first.values, values);
    expect(held.first.minScale, scale);
    for (var index = 0; index < charts.length; index++) {
      expect(held[index], same(charts[index]), reason: 'chart $index rebuilt');
    }
    expect(
      tester.widget(
        find.descendant(
          of: find.byType(OutboundTrafficFigures),
          matching: find.byType(TrafficReadings),
        ),
      ),
      same(figures),
    );
  });

  testWidgets('the status card keeps continuous curves without local masks', (
    tester,
  ) async {
    final host = await pumpDashboardHost(tester);
    final card = tester.renderObject<RenderRepaintBoundary>(
      find
          .ancestor(
            of: find.byType(CurrentOutboundCard),
            matching: find.byType(RepaintBoundary),
          )
          .first,
    );
    final found = <String>[];
    final masks = <ShaderMaskLayer>[];
    void visit(Layer layer) {
      if (layer is OpacityLayer && (layer.alpha ?? 255) < 255) {
        found.add('opacity ${layer.alpha}');
      }
      if (layer is ShaderMaskLayer) masks.add(layer);
      if (layer is ContainerLayer) {
        for (
          var child = layer.firstChild;
          child != null;
          child = child.nextSibling
        ) {
          visit(child);
        }
      }
    }

    visit(card.debugLayer!);
    expect(found, isEmpty);
    expect(masks, isEmpty);
    for (var second = 0; second < 3; second++) {
      await host.second();
      masks.clear();
      visit(card.debugLayer!);
      expect(masks, isEmpty);
      expect(found, isEmpty);
    }
  });

  testWidgets('pressing a network control works within its own card', (
    tester,
  ) async {
    await pumpDashboardHost(tester, controls: true);
    final net = tester.renderObject(find.byType(FusedNetworkControls));
    bool inside(RenderObject object) {
      for (RenderObject? node = object; node != null; node = node.parent) {
        if (identical(node, net)) return true;
      }
      return false;
    }

    for (final key in ['dashboard-mode', 'dashboard-vpn', 'dashboard-lan']) {
      var outside = 0;
      LayoutCount.counts.clear();
      debugOnProfilePaint = (object) {
        if (object is RenderParagraph && !inside(object)) outside++;
      };
      try {
        final gesture = await tester.startGesture(
          tester.getCenter(find.byKey(ValueKey(key))),
        );
        for (var frame = 0; frame < 12; frame++) {
          await tester.pump(const Duration(microseconds: 16667));
        }
        await gesture.up();
        for (var frame = 0; frame < 40; frame++) {
          await tester.pump(const Duration(microseconds: 16667));
        }
      } finally {
        debugOnProfilePaint = null;
      }
      expect(outside, 0, reason: '$key repainted text outside its card');
      expect(LayoutCount.counts['card'], isNull, reason: key);
    }
    expect(tester.takeException(), isNull);
  });
}
