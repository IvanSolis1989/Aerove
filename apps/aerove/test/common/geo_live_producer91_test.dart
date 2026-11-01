import 'package:fl_clash/product.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/enum/enum.dart';
import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:drift/native.dart';
import 'package:shared_preferences/shared_preferences.dart';
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
    'real typed global producer and complete Config proof agree on the exact Meow wire projection',
    () async {
      final home = await Directory.systemTemp.createTemp(
        'geo91-live-producer-',
      );
      AppPath.supportDirectory = () async => home;
      AppPath.temporaryDirectory = () async => home;
      AppPath.cacheDirectory = () async => home;
      AppPath.downloadDirectory = () async => home;
      await appPath.rebind();
      SharedPreferences.setMockInitialValues({});
      await preferences.rebind();
      db.database = db.Database(
        NativeDatabase(File('${home.path}/database.sqlite')),
      );
      await Directory('${home.path}/profiles').create();
      await File('${home.path}/profiles/1.yaml').writeAsString('rules: []');
      final container = ProviderContainer();
      final keep = container.listen(configProvider, (_, __) {});
      try {
        container.read(currentProfileIdProvider.notifier).value = 1;
        container
            .read(profilesProvider.notifier)
            .put(
              const Profile(
                id: 1,
                label: 'actual global fixture',
                autoUpdateDuration: Duration.zero,
              ),
            );
        await waitForDatabaseWrites();
        final wire = container.read(updateParamsProvider).toJson();
        expect(
          (wire['geox-url'] as Map).containsKey('geoip'),
          core != 'meow-rs',
        );
        final storage = (await preferences.sharedPreferences)!;
        if (core == 'meow-rs') {
          final proof = await nativeLiteBusinessSource(
            db.database,
            home,
            storage,
            container.read(configProvider),
          );
          expect(proof['liveSha256'], nativeLiteJsonHash(wire));
          final saved = jsonDecode(storage.getString(configKey)!);
          const output = String.fromEnvironment('GEO_LIVE_FIXTURE');
          if (output.isNotEmpty)
            await File(output).writeAsString(
              jsonEncode({'config': saved, 'wire': wire, 'proof': proof}),
            );

          expect(
            saved['patchClashConfig']['geox-url']['geoip'],
            defaultGeoXUrl[GeoResource.GEOIP],
          );
          final state = container.read(patchClashConfigProvider);
          container
              .read(patchClashConfigProvider.notifier)
              .update(
                (_) => state.copyWith(
                  geoXUrl: {
                    ...state.geoXUrl,
                    GeoResource.GEOIP: 'https://custom.invalid/explicit.dat',
                  },
                ),
              );
          expect(
            () => container.read(updateParamsProvider),
            throwsA(
              isA<Object>().having(
                (e) => e.toString(),
                'field',
                contains(r'$.geox-url.geoip'),
              ),
            ),
          );
        } else {
          expect(
            (wire['geox-url'] as Map)['geoip'],
            defaultGeoXUrl[GeoResource.GEOIP],
          );
        }
      } finally {
        await waitForDatabaseWrites();
        keep.close();
        container.dispose();
        await db.database.close();
        await home.delete(recursive: true);
      }
    },
  );
}
