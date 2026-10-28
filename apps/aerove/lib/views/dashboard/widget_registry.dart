import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/widgets/widgets.dart';
import 'widgets/fused_controls.dart';
import 'fusion.dart';
import 'package:material_ui/material_ui.dart';

extension DashboardWidgetView on DashboardWidget {
  Widget get card => switch (this) {
    DashboardWidget.currentOutbound => const CurrentOutboundCard(),
    DashboardWidget.outboundMode => const OutboundModeCard(),
    DashboardWidget.vpnButton => const VpnCard(),
    DashboardWidget.networkTakeover => const NetworkTakeoverCard(),
    DashboardWidget.serviceStatus => const ServiceStatusCard(),
    DashboardWidget.profiles => const ProfilesCard(),
    DashboardWidget.proxyGroups => const ProxyGroupsCard(),
    DashboardWidget.connectionStats => const ConnectionStatsCard(),
    DashboardWidget.intranetIp => const IntranetIpCard(),
    DashboardWidget.memoryInfo => const MemoryInfo(),
  };

  Glyph get glyph => switch (this) {
    DashboardWidget.currentOutbound => AppGlyphs.status,
    DashboardWidget.outboundMode => AppGlyphs.split,
    DashboardWidget.vpnButton => AppGlyphs.vpn,
    DashboardWidget.networkTakeover => AppGlyphs.shuffle,
    DashboardWidget.serviceStatus => AppGlyphs.verified,
    DashboardWidget.profiles => AppGlyphs.profiles,
    DashboardWidget.proxyGroups => AppGlyphs.layers,
    DashboardWidget.connectionStats => AppGlyphs.connections,
    DashboardWidget.intranetIp => AppGlyphs.devices,
    DashboardWidget.memoryInfo => AppGlyphs.memory,
  };

  String label(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    return switch (this) {
      DashboardWidget.currentOutbound => appLocalizations.status,
      DashboardWidget.outboundMode => appLocalizations.outboundMode,
      DashboardWidget.vpnButton => 'VPN',
      DashboardWidget.networkTakeover => appLocalizations.networkTakeover,
      DashboardWidget.serviceStatus => appLocalizations.serviceStatus,
      DashboardWidget.profiles => appLocalizations.profile,
      DashboardWidget.proxyGroups => appLocalizations.proxyGroup,
      DashboardWidget.connectionStats => appLocalizations.connectionStats,
      DashboardWidget.intranetIp => appLocalizations.intranetIP,
      DashboardWidget.memoryInfo => appLocalizations.memoryInfo,
    };
  }

  String? description(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    return switch (this) {
      DashboardWidget.currentOutbound => appLocalizations.currentOutboundDesc,
      DashboardWidget.connectionStats => appLocalizations.connectionStatsDesc,
      DashboardWidget.memoryInfo => appLocalizations.memoryInfoDesc,
      _ => null,
    };
  }

  /// It holds the start button, which has nowhere else to go.
  bool get removable => this != DashboardWidget.currentOutbound;
}

extension DashboardFusionView on DashboardFusion {
  Widget preview(
    BuildContext context, {
    required GlobalKey cardKey,
    bool editing = false,
  }) => RepaintBoundary(
    key: cardKey,
    child: Semantics(
      label: editing ? label(context) : null,
      excludeSemantics: editing,
      child: ExcludeFocus(
        excluding: editing,
        child: AbsorbPointer(absorbing: editing, child: card),
      ),
    ),
  );

  Widget get card => switch (identity) {
    DashboardWidget.outboundMode => FusedNetworkControls(members: members),
    DashboardWidget.serviceStatus
        when members.contains(DashboardWidget.serviceStatus) =>
      ServiceStatusCard(
        includeStats: members.contains(DashboardWidget.connectionStats),
      ),
    _ => anchor.card,
  };

  String label(BuildContext context) => switch (identity) {
    DashboardWidget.outboundMode =>
      context.appLocalizations.dashboardNetworkControl,
    DashboardWidget.serviceStatus =>
      context.appLocalizations.dashboardNetworkOverview,
    _ => identity.label(context),
  };

  String? description(BuildContext context) => switch (identity) {
    DashboardWidget.outboundMode || DashboardWidget.serviceStatus => null,
    _ => identity.description(context),
  };

  Glyph get glyph => identity.glyph;
  bool get removable => identity.removable;
}
