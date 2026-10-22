import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/models/models.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'config.dart';
import 'core.dart';
import 'last_exit.dart';
import 'route_state.dart';
import 'routed_probe.dart';

part 'generated/outbound_ip.g.dart';

/// Keyed by the outbound a lookup pins: a node name, `DIRECT`, or
/// [routedOutbound] for whatever the rules pick right now.
@Riverpod(keepAlive: true)
class OutboundIpProbe extends _$OutboundIpProbe
    with RoutedProbe<String, IpInfo> {
  @override
  RoutedProbeState<String, IpInfo> build() {
    listenSelf(_remember);
    final last = ref.read(lastExitProvider);
    return buildProbe(
      provisional: {
        if (last != null &&
            last.profileId == ref.read(currentProfileIdProvider))
          routedOutbound: (value: last.ip, proxied: last.proxied),
      },
    );
  }

  void _remember(
    RoutedProbeState<String, IpInfo>? previous,
    RoutedProbeState<String, IpInfo> next,
  ) {
    final entry = next.entries[routedOutbound];
    final ip = entry?.value;
    final stamp = entry?.stamp;
    final profileId = ref.read(currentProfileIdProvider);
    if (entry == previous?.entries[routedOutbound] ||
        entry?.phase != ProbePhase.fresh ||
        ip == null ||
        stamp == null ||
        profileId == null) {
      return;
    }
    ref
        .read(lastExitProvider.notifier)
        .remember(profileId: profileId, proxied: stamp.proxied, ip: ip);
  }

  @override
  Future<Map<String, ProbeAnswer<IpInfo>>> probe(
    String target,
    RouteState route,
  ) async {
    final proxyName = target == routedOutbound && !route.proxied
        ? directOutbound
        : target;
    final result = await lookupOutboundIp(
      ref.read(coreHandlerProvider),
      proxyName,
    );
    final value = parseOutboundIp(result);
    final chains = result?.chains ?? const <String>[];
    final exit = chains.firstOrNull;
    return {
      target: ProbeAnswer(
        value: value,
        coreEpoch: result?.coreEpoch,
        picksVersion: result?.picksVersion,
        chains: chains,
      ),
      // A node leaves through the same address whatever it is asked for.
      if (value != null && exit != null && exit != target)
        exit: ProbeAnswer(
          value: value,
          coreEpoch: result?.coreEpoch,
          picksVersion: result?.picksVersion,
          chains: [exit],
        ),
    };
  }
}
