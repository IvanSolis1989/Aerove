import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/core_capabilities.dart';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/common/tun_policy.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fl_clash/common/yaml.dart';

import '../helpers/test_profiles.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'fresh product defaults preserve Standard and select actual Meow values',
    () {
      final standard = Config.realFromJson(null, coreId: 'mihomo');
      final lite = Config.realFromJson(null, coreId: 'meow-rs');
      expect(standard.patchClashConfig.tun.stack, TunStack.mips);
      expect(standard.patchClashConfig.tun.congestionController, 'cubic');
      expect(lite.patchClashConfig.tun.stack, TunStack.lwip);
      expect(lite.patchClashConfig.tun.congestionController, 'builtin');
      expect(lite.patchClashConfig.tun.device, isEmpty);
      final restored = Config.realFromJson(
        jsonDecode(jsonEncode(lite)) as Map<String, Object?>,
        coreId: 'meow-rs',
      );
      expect(restored.patchClashConfig.tun, lite.patchClashConfig.tun);
    },
  );

  test(
    'existing and imported explicit values are not migrated by product selection',
    () {
      final old = Config.realFromJson(null, coreId: 'mihomo');
      final imported = Config.realFromJson(
        jsonDecode(jsonEncode(old)) as Map<String, Object?>,
        coreId: 'meow-rs',
      );
      expect(imported.patchClashConfig.tun.stack, TunStack.mips);
      expect(imported.patchClashConfig.tun.congestionController, 'cubic');
      expect(
        imported.patchClashConfig.tun.device,
        old.patchClashConfig.tun.device,
      );
    },
  );

  test('supported choices never offer a Mihomo stack or algorithm as lwIP', () {
    expect(CoreCapabilities.meow.tunStacks, ['lwip']);
    expect(CoreCapabilities.meow.tunCongestionControllers, ['builtin']);
    expect(CoreCapabilities.mihomo.tunStacks, [
      'gvisor',
      'system',
      'mixed',
      'mips',
    ]);
    expect(CoreCapabilities.mihomo.tunCongestionControllers, [
      'cubic',
      'reno',
      'bbr',
      'bbr3',
    ]);
  });

  test(
    'Meow Update TUN uses two actual fields while Standard wire stays unchanged',
    () {
      Map<String, dynamic> update(String coreId) => newPatchClashConfig(coreId)
          .toUpdateParams(
            authentication: [],
            skipCertVerify: false,
            coreId: coreId,
          )
          .toJson();
      expect(update('meow-rs')['tun'], {'enable': false, 'stack': 'lwip'});
      expect((update('mihomo')['tun'] as Map).keys.toSet(), {
        'enable',
        'device',
        'stack',
        'auto-route',
      });
      expect(
        Tun.fromJson(
          newPatchClashConfig('meow-rs').tun.toJson(),
        ).congestionController,
        'builtin',
      );
    },
  );

  MakeRealProfileState state({
    Map<String, dynamic> raw = const {},
    ProfileOverrides overrides = const ProfileOverrides(),
    String coreId = 'meow-rs',
  }) => MakeRealProfileState(
    profilesPath: Directory.systemTemp.path,
    profileId: 1,
    rawConfig: raw,
    realPatchConfig: newPatchClashConfig(coreId),
    overrides: overrides,
    appendSystemDns: false,
    proxyGroups: [],
    rules: [],
    addedRules: [],
    defaultUA: 'test',
    bypassPrivateRoute: true,
  );

  test('Meow default compiler does not create host-owned TUN YAML', () async {
    final result = await makeRealProfileTask(state(), coreId: 'meow-rs');
    final parsed = loadPlainYaml(result.yaml) as Map;
    expect(parsed.containsKey('tun'), isFalse);
  });

  test('Standard compiler still creates its existing TUN policy', () async {
    final result = await makeRealProfileTask(
      state(coreId: 'mihomo'),
      coreId: 'mihomo',
    );
    final tun = (loadPlainYaml(result.yaml) as Map)['tun'] as Map;
    expect(tun['stack'], 'mips');
    expect(tun['auto-route'], isTrue);
    expect(tun['auto-detect-interface'], isTrue);
    expect(tun['route-exclude-address'], isNotEmpty);
  });

  test(
    'Meow keeps source and selected TUN fields for strict native reconciliation',
    () async {
      final input = state(
        raw: {
          'tun': {
            'enable': false,
            'unknown-policy': {'value': 4},
          },
        },
        overrides: const ProfileOverrides(
          tun: ProfileTun(congestionController: 'builtin'),
          tunOverrideKeys: {TunOverrideKey.congestionController},
        ),
      );
      final result = await makeRealProfileTask(input, coreId: 'meow-rs');
      final tun = (loadPlainYaml(result.yaml) as Map)['tun'] as Map;
      expect(tun['unknown-policy']['value'], 4);
      expect(tun['congestion-controller'], 'builtin');
      expect(tun['enable'], isFalse);
      expect(tun.containsKey('auto-route'), isFalse);
      expect(tun.containsKey('device'), isFalse);
      expect(input.rawConfig['tun'], {
        'enable': false,
        'unknown-policy': {'value': 4},
      });
    },
  );

  test(
    'explicit unsupported source stack is a field error in either product',
    () async {
      for (final (coreId, stack) in [('meow-rs', 'mips'), ('mihomo', 'lwip')]) {
        await expectLater(
          makeRealProfileTask(
            state(
              coreId: coreId,
              raw: {
                'tun': {'stack': stack},
              },
            ),
            coreId: coreId,
          ),
          throwsA(
            isA<FormatException>().having(
              (error) => error.message,
              'field',
              contains(r'$.tun.stack'),
            ),
          ),
        );
      }
    },
  );

  test(
    'explicit old Meow host choices and source algorithms remain incompatible',
    () {
      expect(
        () => validateTunPolicy(
          coreId: 'meow-rs',
          stack: 'mips',
          congestionController: 'cubic',
          device: 'FlClash',
        ),
        throwsFormatException,
      );
      expect(
        () => validateTunPolicy(
          coreId: 'meow-rs',
          stack: 'lwip',
          congestionController: 'builtin',
          device: '',
          sourceTun: {'congestion-controller': 'cubic'},
        ),
        throwsFormatException,
      );
      expect(
        () => validateTunPolicy(
          coreId: 'meow-rs',
          stack: 'lwip',
          congestionController: 'builtin',
          device: 'explicit-device',
        ),
        throwsFormatException,
      );
    },
  );

  test(
    'fresh Lite reaches actual VpnOptions and SharedState provider wire',
    () async {
      await AppLocalizations.load(const Locale('en'));
      final container = ProviderContainer(
        overrides: [
          profilesProvider.overrideWith(TestProfiles.new),
          patchClashConfigProvider.overrideWithBuild(
            (_, _) => newPatchClashConfig('meow-rs'),
          ),
        ],
      );
      addTearDown(container.dispose);
      final vpn = await container.read(vpnOptionsProvider.future);
      expect(vpn.enable, isTrue);
      expect(vpn.stack, 'lwip');
      expect(vpn.congestionController, 'builtin');
      final shared =
          jsonDecode(jsonEncode(container.read(sharedStateProvider))) as Map;
      final wire = shared['vpnOptions'] as Map;
      expect(wire['stack'], 'lwip');
      expect(wire['congestionController'], 'builtin');
    },
  );

  test('live update also rejects a foreign stack before native fallback', () {
    expect(
      () => newPatchClashConfig('meow-rs').toUpdateParams(
        authentication: [],
        skipCertVerify: false,
        coreId: 'mihomo',
      ),
      throwsA(
        isA<FormatException>().having(
          (error) => error.message,
          'field',
          contains(r'$.tun.stack'),
        ),
      ),
    );
  });

  test('setup digest binds identical inputs to the selected core', () {
    const setup = SetupState(
      profileId: 1,
      profileLastUpdateDate: 1,
      profileType: ProfileType.custom,
      extendType: ExtendType.standard,
      rules: [],
      proxyGroups: [],
      addedRules: [],
      script: null,
      overrides: ProfileOverrides(),
    );
    const inputs = (
      setupState: setup,
      setupPatch: PatchClashConfig(),
      appendSystemDns: false,
      bypassPrivateRoute: true,
      testUrl: 'https://example.com',
      skipCertVerify: false,
      safeMode: false,
      build: '1',
    );
    expect(
      setupInputsDigest(inputs, coreId: 'meow-rs'),
      isNot(setupInputsDigest(inputs, coreId: 'mihomo')),
    );
  });
}
