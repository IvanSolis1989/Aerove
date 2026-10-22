import 'dart:convert';

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/exit_snapshot.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/last_exit.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:riverpod/riverpod.dart';

import '../helpers/preferences.dart';

const _ip = IpInfo(ip: '203.0.113.9', countryCode: 'GB');

const _quality = IpQuality(
  ip: '203.0.113.9',
  source: IpQualitySource.ipLocate,
  type: IpType.residential,
  organization: 'Example Broadband',
  asn: 64500,
  isVpn: true,
);

const _saved = ExitSnapshot(
  profileId: 7,
  proxied: true,
  ip: _ip,
  quality: _quality,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<ProviderContainer> load({
    ExitSnapshot? saved = _saved,
    bool hideIp = false,
  }) async {
    await mockPreferences({
      if (saved != null) exitSnapshotKey: json.encode(saved),
    });
    final container = ProviderContainer(
      overrides: [
        appSettingProvider.overrideWithBuild(
          (_, _) => AppSettingProps(hideIp: hideIp),
        ),
      ],
    );
    addTearDown(container.dispose);
    await container.read(lastExitProvider.notifier).load();
    return container;
  }

  test('loads the exit saved by the last launch', () async {
    final container = await load();

    expect(container.read(lastExitProvider), _saved);
  });

  test('keeps the quality only for the address it was looked up for', () async {
    final container = await load();
    final lastExit = container.read(lastExitProvider.notifier);

    lastExit.remember(profileId: 7, proxied: false, ip: _ip);
    expect(container.read(lastExitProvider)?.quality, _quality);

    const other = IpInfo(ip: '198.51.100.4', countryCode: 'US');
    lastExit.remember(profileId: 7, proxied: true, ip: other);
    lastExit.rememberQuality(_quality);
    expect(
      await preferences.getExitSnapshot(),
      const ExitSnapshot(profileId: 7, proxied: true, ip: other),
    );

    lastExit.rememberQuality(_quality.copyWith(ip: other.ip));
    expect(
      await preferences.getExitSnapshot(),
      ExitSnapshot(
        profileId: 7,
        proxied: true,
        ip: other,
        quality: _quality.copyWith(ip: other.ip),
      ),
    );
  });

  test('keeps nothing on disk while the address is hidden', () async {
    final container = await load(hideIp: true);

    expect(container.read(lastExitProvider), isNull);
    expect(await preferences.getExitSnapshot(), isNull);

    container
        .read(lastExitProvider.notifier)
        .remember(profileId: 7, proxied: true, ip: _ip);
    expect(container.read(lastExitProvider), isNull);
    expect(await preferences.getExitSnapshot(), isNull);
  });

  test('hiding the address forgets the saved exit', () async {
    final container = await load();

    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(hideIp: true));
    await pumpEventQueue();

    expect(container.read(lastExitProvider), isNull);
    expect(await preferences.getExitSnapshot(), isNull);
  });
}
