import 'dart:math' as math;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart';
import 'intranet_ip.dart';
import 'outbound_mode.dart';

class FusedNetworkControls extends StatelessWidget {
  const FusedNetworkControls({super.key, required this.members});

  final Set<DashboardWidget> members;

  @override
  Widget build(BuildContext context) {
    final mode = members.contains(DashboardWidget.outboundMode);
    final vpn = members.contains(DashboardWidget.vpnButton);
    final lan = members.contains(DashboardWidget.intranetIp);
    return DashboardCard(
      padding: lan
          ? dashboardCardPadding(
              DashboardDensityScope.of(context),
            ).copyWith(bottom: 0)
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          DashboardCardHeader(
            glyph: AppGlyphs.vpn,
            title: context.appLocalizations.dashboardNetworkControl,
          ),
          const SizedBox(height: 12),
          if (mode) const OutboundModeCard(embedded: true),
          if (mode && (vpn || lan)) const SizedBox(height: 16),
          if (vpn || lan)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: 16,
              children: [
                if (vpn) const Expanded(child: _VpnControl()),
                if (lan) const Expanded(child: _LanControl()),
              ],
            ),
          if (lan) const _LanAddress(),
        ],
      ),
    );
  }
}

class _Control extends StatelessWidget {
  const _Control({
    required this.title,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final scaler = MediaQuery.textScalerOf(context);
      final titleStyle = AppTextStyle.callout.of(context, emphasized: true);
      final titleHeight =
          scaler.scale(titleStyle.fontSize!) * titleStyle.height!;
      final label = GlassText(title, maxLines: 2, style: titleStyle);
      final toggle = Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: DashboardSwitch(
          value: value,
          semanticLabel: title,
          onChanged: onChanged,
        ),
      );
      final stacked =
          constraints.maxWidth <
          DashboardSwitch.trackWidth +
              72 * MediaQuery.textScalerOf(context).scale(1);
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (stacked) ...[
            SizedBox(
              height: titleHeight * 2,
              child: Align(
                alignment: AlignmentDirectional.topStart,
                child: label,
              ),
            ),
            toggle,
          ] else
            SizedBox(
              height: math.max(CommonSwitch.trackHeight + 16, titleHeight * 2),
              child: Row(
                spacing: 8,
                children: [
                  Expanded(child: label),
                  toggle,
                ],
              ),
            ),
        ],
      );
    },
  );
}

class _VpnControl extends ConsumerWidget {
  const _VpnControl();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final enabled = ref.watch(vpnSettingProvider.select((s) => s.enable));
    return _Control(
      title: 'VPN',
      value: enabled,
      onChanged: (value) => ref
          .read(vpnSettingProvider.notifier)
          .update((state) => state.copyWith(enable: value)),
    );
  }
}

class _LanControl extends ConsumerWidget {
  const _LanControl();

  @override
  Widget build(BuildContext context, WidgetRef ref) => _Control(
    title: context.appLocalizations.allowLan,
    value: ref.watch(patchClashConfigProvider.select((s) => s.allowLan)),
    onChanged: (value) => ref
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(allowLan: value)),
  );
}

class _LanAddress extends ConsumerWidget {
  const _LanAddress();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.appLocalizations;
    final ip = ref.watch(localIpProvider);
    final enabled = ref.watch(patchClashConfigProvider.select((s) => s.allowLan));
    final port = ref.watch(patchClashConfigProvider.select((s) => s.mixedPort));
    final address = _lanAddress(ip, port, enabled);
    final label = address ?? (ip == null ? '—' : l10n.noNetwork);
    final VoidCallback? copy = address == null
        ? null
        : () => copyToClipboard(context, address);
    return Semantics(
      key: const ValueKey('dashboard-lan-copy'),
      button: true,
      enabled: address != null,
      label: l10n.copy,
      value: label,
      excludeSemantics: true,
      onTap: copy,
      child: InkWell(
        onTap: copy,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Center(
              child: GlassText(
                label,
                key: const ValueKey('dashboard-lan-address'),
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: DashboardText.label(context),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

String? _lanAddress(String? ip, int port, bool enabled) {
  if (ip == null || ip.trim().isEmpty) return null;
  if (!enabled) return ip;
  if (port < 1 || port > 65535) return null;
  final host = ip.startsWith('[') && ip.endsWith(']')
      ? ip.substring(1, ip.length - 1)
      : ip;
  return host.contains(':') ? '[$host]:$port' : '$host:$port';
}
