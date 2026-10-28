import 'dart:convert';

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/views/dashboard/fusion.dart';
import 'package:flutter_test/flutter_test.dart';

const android = SupportPlatform.Android;

void main() {
  const raw = [
    DashboardWidget.profiles,
    DashboardWidget.intranetIp,
    DashboardWidget.networkTakeover,
    DashboardWidget.currentOutbound,
    DashboardWidget.connectionStats,
    DashboardWidget.outboundMode,
    DashboardWidget.proxyGroups,
  ];

  test('fusion identities stay stable and retain ordered visible subsets', () {
    final groups = dashboardFusionsFor(raw, android);
    expect(groups.map((group) => group.identity), [
      DashboardWidget.profiles,
      DashboardWidget.outboundMode,
      DashboardWidget.currentOutbound,
      DashboardWidget.serviceStatus,
      DashboardWidget.proxyGroups,
    ]);
    expect(groups[1].members.toList(), [
      DashboardWidget.intranetIp,
      DashboardWidget.outboundMode,
    ]);
    expect(groups[3].members, {DashboardWidget.connectionStats});
    expect(
      addableDashboardFusions(raw, android).map((group) => group.identity),
      [DashboardWidget.memoryInfo],
    );
    expect(
      dashboardFusionsFor([
        DashboardWidget.connectionStats,
      ], android).single.identity,
      dashboardFusionsFor([
        DashboardWidget.serviceStatus,
      ], android).single.identity,
    );
  });

  test(
    'no-op moves retain the exact raw list and all interleaved platform members',
    () {
      expect(
        moveDashboardFusion(
          raw,
          android,
          DashboardWidget.outboundMode,
          DashboardWidget.currentOutbound,
        ),
        same(raw),
      );
      expect(
        moveDashboardFusion(raw, android, DashboardWidget.proxyGroups, null),
        same(raw),
      );
      expect(
        moveDashboardFusion(raw, android, DashboardWidget.memoryInfo, null),
        same(raw),
      );
      expect(
        hideDashboardFusion(raw, android, DashboardWidget.currentOutbound),
        same(raw),
      );
    },
  );

  test(
    'moving another group leaves a partial family and every other raw member untouched',
    () {
      final moved = moveDashboardFusion(
        raw,
        android,
        DashboardWidget.proxyGroups,
        DashboardWidget.outboundMode,
      );
      expect(moved, [
        DashboardWidget.profiles,
        DashboardWidget.proxyGroups,
        ...raw.skip(1).take(raw.length - 2),
      ]);
      expect(
        moved.where((item) => item != DashboardWidget.proxyGroups),
        raw.where((item) => item != DashboardWidget.proxyGroups),
      );
      expect(moved, isNot(contains(DashboardWidget.vpnButton)));
      expect(moved, isNot(contains(DashboardWidget.serviceStatus)));
    },
  );

  test(
    'moving an interleaved family extracts only its existing platform members',
    () {
      final moved = moveDashboardFusion(
        raw,
        android,
        DashboardWidget.outboundMode,
        null,
      );
      expect(moved, [
        DashboardWidget.profiles,
        DashboardWidget.networkTakeover,
        DashboardWidget.currentOutbound,
        DashboardWidget.connectionStats,
        DashboardWidget.proxyGroups,
        DashboardWidget.intranetIp,
        DashboardWidget.outboundMode,
      ]);
      expect(moved.toSet(), raw.toSet());
      expect(moved.length, raw.length);
    },
  );

  test(
    'explicit hide removes current visible members and explicit restore adds the platform family',
    () {
      final hidden = hideDashboardFusion(
        raw,
        android,
        DashboardWidget.outboundMode,
      );
      expect(
        hidden,
        raw.where(
          (item) =>
              item != DashboardWidget.intranetIp &&
              item != DashboardWidget.outboundMode,
        ),
      );
      final restored = restoreDashboardFusion(
        hidden,
        android,
        DashboardWidget.outboundMode,
      );
      expect(restored, [
        ...hidden,
        DashboardWidget.outboundMode,
        DashboardWidget.vpnButton,
        DashboardWidget.intranetIp,
      ]);
      final desktop = restoreDashboardFusion(
        hidden,
        SupportPlatform.MacOS,
        DashboardWidget.outboundMode,
      );
      expect(desktop, isNot(contains(DashboardWidget.vpnButton)));
      expect(desktop, contains(DashboardWidget.networkTakeover));
      expect(
        restoreDashboardFusion(restored, android, DashboardWidget.outboundMode),
        restored,
      );
    },
  );

  test(
    'all hidden families get one addable row, and the stored JSON preserves the result',
    () {
      final hidden = hideDashboardFusion(
        hideDashboardFusion(raw, android, DashboardWidget.outboundMode),
        android,
        DashboardWidget.serviceStatus,
      );
      final addable = addableDashboardFusions(hidden, android);
      expect(addable.map((group) => group.identity), [
        DashboardWidget.outboundMode,
        DashboardWidget.serviceStatus,
        DashboardWidget.memoryInfo,
      ]);
      final result = restoreDashboardFusion(
        hidden,
        android,
        DashboardWidget.serviceStatus,
      );
      final props = AppSettingProps(dashboardWidgets: result, locale: 'ja');
      final readback = AppSettingProps.fromJson(
        jsonDecode(jsonEncode(props.toJson())) as Map<String, dynamic>,
      );
      expect(readback.dashboardWidgets, result);
      expect(readback.locale, 'ja');
      expect(
        readback.dashboardWidgets.map((item) => item.name),
        isNot(contains('trafficUsage')),
      );
    },
  );
}
