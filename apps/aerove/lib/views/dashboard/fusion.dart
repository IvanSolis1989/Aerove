import 'package:fl_clash/enum/enum.dart';

class DashboardFusion {
  const DashboardFusion(this.anchor, this.members);

  final DashboardWidget anchor;
  final Set<DashboardWidget> members;

  DashboardWidget get identity => _familyOf(anchor).first;
}

const _families = [
  {
    DashboardWidget.outboundMode,
    DashboardWidget.vpnButton,
    DashboardWidget.intranetIp,
  },
  {DashboardWidget.serviceStatus, DashboardWidget.connectionStats},
];

Set<DashboardWidget> _familyOf(DashboardWidget item) => _families.firstWhere(
  (family) => family.contains(item),
  orElse: () => {item},
);

List<DashboardFusion> fuseDashboard(List<DashboardWidget> saved) {
  final used = <DashboardWidget>{};
  final result = <DashboardFusion>[];
  for (final anchor in saved) {
    if (used.contains(anchor)) continue;
    final family = _familyOf(anchor);
    final members = Set<DashboardWidget>.unmodifiable(
      saved.where(family.contains),
    );
    used.addAll(members);
    result.add(DashboardFusion(anchor, members));
  }
  return result;
}

List<DashboardFusion> dashboardFusionsFor(
  List<DashboardWidget> saved,
  SupportPlatform platform,
) => fuseDashboard([
  for (final item in saved)
    if (item.platforms.contains(platform)) item,
]);

List<DashboardFusion> addableDashboardFusions(
  List<DashboardWidget> saved,
  SupportPlatform platform,
) {
  final shown = dashboardFusionsFor(
    saved,
    platform,
  ).map((group) => group.identity).toSet();
  return [
    for (final group in dashboardFusionsFor(DashboardWidget.values, platform))
      if (group.identity != DashboardWidget.currentOutbound &&
          !shown.contains(group.identity))
        group,
  ];
}

List<DashboardWidget> hideDashboardFusion(
  List<DashboardWidget> saved,
  SupportPlatform platform,
  DashboardWidget identity,
) {
  if (identity == DashboardWidget.currentOutbound) return saved;
  final members = _familyOf(identity);
  return [
    for (final item in saved)
      if (!members.contains(item) || !item.platforms.contains(platform)) item,
  ];
}

List<DashboardWidget> restoreDashboardFusion(
  List<DashboardWidget> saved,
  SupportPlatform platform,
  DashboardWidget identity,
) => [
  ...saved,
  for (final item in _familyOf(identity))
    if (item.platforms.contains(platform) && !saved.contains(item)) item,
];

List<DashboardWidget> moveDashboardFusion(
  List<DashboardWidget> saved,
  SupportPlatform platform,
  DashboardWidget identity,
  DashboardWidget? beforeIdentity,
) {
  final groups = dashboardFusionsFor(saved, platform);
  final from = groups.indexWhere((group) => group.identity == identity);
  if (from < 0 || identity == beforeIdentity) return saved;
  final next = from + 1 < groups.length ? groups[from + 1].identity : null;
  if (next == beforeIdentity) return saved;
  final moving = groups[from].members;
  final result = saved.where((item) => !moving.contains(item)).toList();
  final target = beforeIdentity == null
      ? null
      : groups.where((group) => group.identity == beforeIdentity).firstOrNull;
  if (beforeIdentity != null && target == null) return saved;
  final at = target != null
      ? result.indexWhere(target.members.contains)
      : result.lastIndexWhere((item) => item.platforms.contains(platform)) + 1;
  result.insertAll(at, moving);
  return result;
}
