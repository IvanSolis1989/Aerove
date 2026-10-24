import 'package:fl_clash/enum/enum.dart';

class DashboardFusion {
  const DashboardFusion(this.anchor, this.members);

  final DashboardWidget anchor;
  final Set<DashboardWidget> members;
}

const _families = [
  {DashboardWidget.currentOutbound, DashboardWidget.networkSpeed},
  {
    DashboardWidget.outboundMode,
    DashboardWidget.vpnButton,
    DashboardWidget.intranetIp,
  },
  {DashboardWidget.serviceStatus, DashboardWidget.connectionStats},
];

/// Presentation only: the persisted list remains the editable source of truth.
List<DashboardFusion> fuseDashboard(List<DashboardWidget> saved) {
  final used = <DashboardWidget>{};
  final result = <DashboardFusion>[];
  for (final anchor in saved) {
    if (used.contains(anchor)) continue;
    final family = _families.firstWhere(
      (group) => group.contains(anchor),
      orElse: () => {anchor},
    );
    final members = saved.where(family.contains).toSet();
    used.addAll(members);
    result.add(DashboardFusion(anchor, members));
  }
  return result;
}
