import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const core = String.fromEnvironment('DTO_CORE', defaultValue: 'meow-rs');
  ClientProduct.configure(
    product: ProductSpec(
      id: 'dto-fixture',
      displayName: 'DTO fixture',
      expectedCoreId: core,
      minimumAndroidApi: 33,
      resources: ProductResources(
        defaultScriptAsset: 'fixture',
        defaultScriptSha256: 'fixture',
        defaultScriptUrl: 'https://example.invalid/fixture',
        legacyScriptUrls: {},
        iconAsset: 'fixture',
      ),
      releaseFeed: 'https://example.invalid/fixture',
      releaseTagPrefix: 'fixture',
      releaseAssetPattern: 'fixture',
      changelogPath: 'fixture',
      coreSourceUrl: 'https://example.invalid/fixture',
    ),
    backendCoreId: core,
    backendFactory: () =>
        throw StateError('DTO test must not create a Core backend'),
  );
  test(
    '$core actual VpnOptions provider preserves raw policy and Standard omits Lite fields',
    () async {
      final container = ProviderContainer(
        overrides: [currentProfileProvider.overrideWith((ref) => null)],
      );
      try {
        container
            .read(networkSettingProvider.notifier)
            .update((state) => state.copyWith(bypassPrivateRoute: false));
        container
            .read(patchClashConfigProvider.notifier)
            .update(
              (state) => state.copyWith.tun(
                routeAddress: ['10.0.0.0/8'],
                routeExcludeAddress: ['10.128.0.0/9'],
              ),
            );
        final value = await container.read(vpnOptionsProvider.future);
        expect(value.routeAddress, ['10.0.0.0/9', '::/0']);
        final json = value.toJson();
        const output = String.fromEnvironment('TUN_DTO_OUTPUT');
        if (output.isNotEmpty)
          await File(output).writeAsString(jsonEncode(json));
        if (core == 'meow-rs') {
          expect(value.rawRouteAddress, ['10.0.0.0/8']);
          expect(value.rawRouteExcludeAddress, ['10.128.0.0/9']);
          expect(value.bypassPrivateRoute, false);
          expect(
            VpnOptions.fromJson(
              Map<String, dynamic>.from(jsonDecode(jsonEncode(json)) as Map),
            ),
            value,
          );
        } else {
          expect(json.containsKey('rawRouteAddress'), false);
          expect(json.containsKey('rawRouteExcludeAddress'), false);
          expect(json.containsKey('bypassPrivateRoute'), false);
        }
      } finally {
        container.dispose();
      }
    },
  );
}
