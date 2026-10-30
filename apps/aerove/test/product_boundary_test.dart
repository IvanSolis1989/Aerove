import 'dart:io';
import 'dart:isolate';

import 'package:aerove_standard/standard_product.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/lib.dart';
import 'package:fl_clash/product.dart';
import 'package:flutter/foundation.dart' show FlutterError;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

import 'helpers/client_sources.dart';

void main() {
  test('standard tests compose the product with its Mihomo backend', () {
    expect(ClientProduct.current, same(standardProduct));
    expect(ClientProduct.current.id, 'aerove-standard');
    expect(ClientProduct.current.expectedCoreId, 'mihomo');
    expect(ClientProduct.current.minimumAndroidApi, 24);
    expect(ClientProduct.current.releaseTagPrefix, 'v');
  });

  test(
    'composition rejects a different core before creating a backend',
    () async {
      final failure = await Isolate.run(() {
        var created = false;
        try {
          ClientProduct.configure(
            product: standardProduct,
            backendCoreId: 'meow',
            backendFactory: () {
              created = true;
              return CoreLib();
            },
          );
          return 'accepted';
        } on StateError catch (error) {
          return '${error.message}; created=$created';
        }
      });
      expect(
        failure,
        'Product aerove-standard expects mihomo, received meow; created=false',
      );
    },
  );

  test('a claimed Meow factory cannot return the Mihomo adapter', () async {
    final failure = await Isolate.run(() {
      final standard = standardProduct;
      ClientProduct.configure(
        product: ProductSpec(
          id: 'boundary-meow',
          displayName: 'Boundary',
          expectedCoreId: 'meow',
          minimumAndroidApi: 33,
          resources: standard.resources,
          releaseFeed: standard.releaseFeed,
          releaseTagPrefix: 'boundary-',
          releaseAssetPattern: standard.releaseAssetPattern,
          changelogPath: standard.changelogPath,
          coreSourceUrl: standard.coreSourceUrl,
        ),
        backendCoreId: 'meow',
        backendFactory: CoreLib.new,
      );
      try {
        CoreController();
        return 'accepted';
      } on StateError catch (error) {
        return error.message;
      }
    });
    expect(failure, 'Mihomo backend cannot serve boundary-meow');
  });

  testWidgets('shared assets and product payloads use separate bundles', (
    tester,
  ) async {
    await tester.runAsync(() async {
      final shader = await rootBundle.load(ClientAssets.liquidGlassShader);
      expect(shader.lengthInBytes, greaterThan(0));
      final script = await rootBundle.loadString(
        standardProduct.resources.defaultScriptAsset,
      );
      expect(script, contains('Mihomo'));
      await expectLater(
        () => rootBundle.load(
          'packages/fl_clash/${standardProduct.resources.defaultScriptAsset}',
        ),
        throwsA(isA<FlutterError>()),
      );
    });
    final shared =
        loadYaml(
              File(
                '../../packages/aerove_client/pubspec.yaml',
              ).readAsStringSync(),
            )
            as YamlMap;
    final sharedAssets = (shared['flutter'] as YamlMap)['assets'] as YamlList;
    expect(sharedAssets, isNot(contains('assets/data/')));
    expect(sharedAssets, isNot(contains(standardProduct.resources.iconAsset)));
    expect(
      File(
        '../../packages/aerove_client/${standardProduct.resources.defaultScriptAsset}',
      ).existsSync(),
      isFalse,
    );
    expect(clientSourceFile('lib/common/tray.dart').existsSync(), isTrue);
    expect(
      clientSourceFile('lib/common/tray.dart').resolveSymbolicLinksSync(),
      File(
        '../../packages/aerove_client/lib/common/tray.dart',
      ).resolveSymbolicLinksSync(),
    );
    expect(
      clientSourcePath(clientSourceFile('lib/common/tray.dart').path),
      'lib/common/tray.dart',
    );
  });
}
