import 'package:fl_clash/product.dart';

// Source and content identity: app/src/main/assets/scripts/*.source.json.
final liteProduct = ProductSpec(
  id: 'aerove-lite',
  displayName: 'AEROVE Lite',
  expectedCoreId: 'meow-rs',
  minimumAndroidApi: 33,
  releaseFeed: 'https://ivansolis1989.github.io/Aerove/releases.json',
  releaseTagPrefix: 'lite-v',
  releaseAssetPattern: r'^AEROVE-Lite-android-[\w-]+\.apk$',
  changelogPath: 'docs/aerove-lite/CHANGELOG.md',
  coreSourceUrl: 'https://github.com/meow-rs/meow-rs',
  resources: ProductResources(
    defaultScriptAsset: 'app/src/main/assets/scripts/smart-config-kit-meow.js',
    defaultScriptSha256:
        '96b798d1d01129c0d8552a4a7a39b5108668d9007c6af996cb58b82d0d429586',
    defaultScriptUrl:
        'https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/'
        'refs/heads/main/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/'
        'AeroveLite(meow).js',
    legacyScriptUrls: const {},
    iconAsset: 'app/src/main/res/drawable-nodpi/aerove_lite_icon.png',
  ),
);
