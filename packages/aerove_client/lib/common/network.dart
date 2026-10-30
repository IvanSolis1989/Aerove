import 'dart:io';

import 'package:flutter/foundation.dart';

typedef NetworkInterfaceLister =
    Future<List<NetworkInterface>> Function({bool includeLoopback});

@visibleForTesting
NetworkInterfaceLister listNetworkInterfaces =
    ({bool includeLoopback = false}) =>
        NetworkInterface.list(includeLoopback: includeLoopback);

extension NetworkInterfaceExt on NetworkInterface {
  bool get isLanCandidate {
    final label = name.toLowerCase();
    return !RegExp(
      r'(^|[\s_-])(tun\d*|utun\d*|tap\d*|wg\d*|wintun|vpn|wireguard|tailscale|zerotier|virtual|virtualbox|vmnet\d*|veth\w*|vethernet|docker\w*|bridge\d*|loopback)([\s_-]|$)',
    ).hasMatch(label);
  }

  bool get isWifi {
    final nameLowCase = name.toLowerCase();
    if (nameLowCase.contains('wlan') ||
        nameLowCase.contains('wi-fi') ||
        nameLowCase == 'en0' ||
        nameLowCase == 'eth0') {
      return true;
    }

    return false;
  }

  bool get includesIPv4 {
    return addresses.any((addr) => addr.isIPv4);
  }
}

extension InternetAddressExt on InternetAddress {
  bool get isIPv4 {
    return type == InternetAddressType.IPv4;
  }
}

bool isUsableLanAddress(String? address) {
  final ip = address == null ? null : InternetAddress.tryParse(address);
  if (ip == null ||
      ip.isLoopback ||
      ip.isMulticast ||
      ip.isLinkLocal ||
      ip.rawAddress.every((byte) => byte == 0)) {
    return false;
  }
  final bytes = ip.rawAddress;
  return ip.type != InternetAddressType.IPv4 ||
      (bytes.first != 0 &&
          !bytes.every((byte) => byte == 255) &&
          !(bytes.first == 198 && (bytes[1] == 18 || bytes[1] == 19)));
}

Future<String?> getLocalIpAddress({String? excludedInterface}) async {
  final List<NetworkInterface> interfaces =
      await listNetworkInterfaces(includeLoopback: false)
        ..sort((a, b) {
          if (a.isWifi && !b.isWifi) return -1;
          if (!a.isWifi && b.isWifi) return 1;
          if (a.includesIPv4 && !b.includesIPv4) return -1;
          if (!a.includesIPv4 && b.includesIPv4) return 1;
          return 0;
        });
  for (final interface in interfaces) {
    if (!interface.isLanCandidate || interface.name == excludedInterface) {
      continue;
    }
    final addresses = interface.addresses
        .where((address) => isUsableLanAddress(address.address))
        .toList();
    if (addresses.isEmpty) {
      continue;
    }
    addresses.sort((a, b) {
      if (a.isIPv4 && !b.isIPv4) return -1;
      if (!a.isIPv4 && b.isIPv4) return 1;
      return 0;
    });
    return addresses.first.address;
  }
  return '';
}
