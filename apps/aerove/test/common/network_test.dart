import 'dart:io';

import 'package:fl_clash/common/network.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeAddress implements InterfaceAddress {
  _FakeAddress(this.address, this.type);

  @override
  final String address;

  @override
  final InternetAddressType type;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeInterface implements NetworkInterface {
  _FakeInterface(this.name, this.addresses);

  @override
  final String name;

  @override
  final List<InterfaceAddress> addresses;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

InterfaceAddress _v4(String address) =>
    _FakeAddress(address, InternetAddressType.IPv4);

InterfaceAddress _v6(String address) =>
    _FakeAddress(address, InternetAddressType.IPv6);

void main() {
  tearDown(() {
    listNetworkInterfaces = ({bool includeLoopback = false}) =>
        NetworkInterface.list(includeLoopback: includeLoopback);
  });

  void listing(List<NetworkInterface> interfaces) {
    listNetworkInterfaces = ({bool includeLoopback = false}) async =>
        interfaces;
  }

  group('isWifi', () {
    test('recognises the usual wireless interface names', () {
      for (final name in ['wlan0', 'Wi-Fi', 'WLAN1', 'en0', 'eth0', 'ETH0']) {
        expect(_FakeInterface(name, const []).isWifi, isTrue, reason: name);
      }
    });

    test('does not match an unrelated or suffixed interface', () {
      for (final name in ['en1', 'eth1', 'utun3', 'lo0', '']) {
        expect(_FakeInterface(name, const []).isWifi, isFalse, reason: name);
      }
    });
  });

  test('includesIPv4 only counts IPv4 addresses', () {
    expect(_FakeInterface('en1', [_v4('10.0.0.2')]).includesIPv4, isTrue);
    expect(_FakeInterface('en1', [_v6('fe80::1')]).includesIPv4, isFalse);
    expect(_FakeInterface('en1', const []).includesIPv4, isFalse);
  });

  test('isIPv4 reads the address type', () {
    expect(_v4('10.0.0.2').isIPv4, isTrue);
    expect(_v6('fe80::1').isIPv4, isFalse);
  });

  group('getLocalIpAddress', () {
    for (final name in [
      '以太网',
      '이더넷',
      'Réseau local',
      'Office wired',
      'Fortune',
    ]) {
      test(
        'keeps a valid localized or renamed wired interface: $name',
        () async {
          listing([
            _FakeInterface(name, [_v4('192.168.10.23')]),
          ]);
          expect(await getLocalIpAddress(), '192.168.10.23');
        },
      );
    }

    test('prefers a wireless interface over a wired one', () async {
      listing([
        _FakeInterface('utun0', [_v4('10.9.0.1')]),
        _FakeInterface('wlan0', [_v4('192.168.1.20')]),
      ]);

      expect(await getLocalIpAddress(), '192.168.1.20');
    });

    test(
      'prefers an IPv4-carrying interface when neither is wireless',
      () async {
        listing([
          _FakeInterface('en1', [_v6('2001:db8::1')]),
          _FakeInterface('eth1', [_v4('10.9.0.1')]),
        ]);

        expect(await getLocalIpAddress(), '10.9.0.1');
      },
    );

    test('prefers the IPv4 address inside the chosen interface', () async {
      listing([
        _FakeInterface('wlan0', [_v6('fe80::1'), _v4('192.168.1.20')]),
      ]);

      expect(await getLocalIpAddress(), '192.168.1.20');
    });

    test('skips an interface that carries no address at all', () async {
      listing([
        _FakeInterface('wlan0', const []),
        _FakeInterface('eth1', [_v4('10.9.0.1')]),
      ]);

      expect(await getLocalIpAddress(), '10.9.0.1');
    });

    test('returns an empty string when nothing is listed', () async {
      listing([]);

      expect(await getLocalIpAddress(), '');
    });

    test(
      'excludes the configured TUN even when its name resembles LAN',
      () async {
        listing([
          _FakeInterface('eth0', [_v4('10.1.0.1')]),
          _FakeInterface('wlan0', [_v4('192.168.1.20')]),
        ]);
        expect(
          await getLocalIpAddress(excludedInterface: 'eth0'),
          '192.168.1.20',
        );
      },
    );

    test('never returns a tunnel or an unusable LAN address', () async {
      listing([
        _FakeInterface('tun0', [_v4('198.18.0.1')]),
        _FakeInterface('utun2', [_v4('10.9.0.1')]),
        _FakeInterface('Ethernet VPN', [_v4('10.9.0.2')]),
        _FakeInterface('wlan0', [
          _v4('127.0.0.1'),
          _v4('0.0.0.0'),
          _v4('169.254.1.2'),
          _v6('::1'),
          _v6('fe80::1'),
        ]),
      ]);
      expect(await getLocalIpAddress(), '');
    });

    test('returns usable IPv6 when the physical LAN has no IPv4', () async {
      listing([
        _FakeInterface('wlan0', [_v6('fd00::23')]),
      ]);
      expect(await getLocalIpAddress(), 'fd00::23');
    });

    test('never asks for the loopback interface', () async {
      bool? asked;
      listNetworkInterfaces = ({bool includeLoopback = false}) async {
        asked = includeLoopback;
        return [];
      };

      await getLocalIpAddress();

      expect(asked, isFalse);
    });
  });
}
