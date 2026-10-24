import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
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
    required this.detail,
  });

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;
  final Widget detail;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final label = GlassText(
        title,
        style: AppTextStyle.callout.of(context, emphasized: true),
      );
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
            label,
            toggle,
          ] else
            Row(
              spacing: 8,
              children: [
                Expanded(child: label),
                toggle,
              ],
            ),
          detail,
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
      detail: GlassText(
        enabled
            ? context.appLocalizations.enabled
            : context.appLocalizations.disabled,
        style: DashboardText.label(context),
      ),
    );
  }
}

class _LanControl extends ConsumerWidget {
  const _LanControl();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.appLocalizations;
    final ip = ref.watch(localIpProvider);
    final enabled = ref.watch(
      patchClashConfigProvider.select((s) => s.allowLan),
    );
    final port = ref.watch(patchClashConfigProvider.select((s) => s.mixedPort));
    final address = ip == null || ip.isEmpty ? null : '$ip:$port';
    return _Control(
      title: l10n.allowLan,
      value: enabled,
      onChanged: (value) => ref
          .read(patchClashConfigProvider.notifier)
          .update((state) => state.copyWith(allowLan: value)),
      detail: Row(
        children: [
          Expanded(
            child: GlassText(
              address ?? (ip == null ? '—' : l10n.noNetwork),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: DashboardText.label(context),
            ),
          ),
          DashboardIconButton(
            glyph: AppGlyphs.copy,
            tooltip: l10n.copy,
            onPressed: address == null
                ? null
                : () => copyToClipboard(context, address),
          ),
        ],
      ),
    );
  }
}
