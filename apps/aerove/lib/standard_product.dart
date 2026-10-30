import 'package:fl_clash/product.dart';

const _smartConfigKitRawRoot =
    'https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/';
const standardDefaultScriptUrl =
    '${_smartConfigKitRawRoot}refs/heads/main/'
    'Mihomo%E5%86%85%E6%A0%B8%E7%B3%BB/FlClash/FlClash%28mihomo%29.js';
const standardLegacyScriptUrls = {
  '${_smartConfigKitRawRoot}main/FlClash/FlClash(mihomo).js',
  '${_smartConfigKitRawRoot}main/FlClash/FlClash%28mihomo%29.js',
  '${_smartConfigKitRawRoot}main/FlClash/FlClash%28mihomo).js',
  '${_smartConfigKitRawRoot}main/FlClash/FlClash(mihomo%29.js',
  '${_smartConfigKitRawRoot}refs/heads/main/FlClash/FlClash(mihomo).js',
  '${_smartConfigKitRawRoot}refs/heads/main/FlClash/FlClash%28mihomo%29.js',
  '${_smartConfigKitRawRoot}refs/heads/main/FlClash/FlClash%28mihomo).js',
  '${_smartConfigKitRawRoot}refs/heads/main/FlClash/FlClash(mihomo%29.js',
};

final standardProduct = ProductSpec(
  id: 'aerove-standard',
  displayName: 'AEROVE',
  expectedCoreId: 'mihomo',
  minimumAndroidApi: 24,
  releaseFeed: 'https://ivansolis1989.github.io/Aerove/releases.json',
  releaseTagPrefix: 'v',
  releaseAssetPattern:
      r'^(?:Aerove|XClash)-(?:android-[\w-]+\.apk|windows-[\w.-]+\.(?:exe|zip|msix)|linux-[\w.-]+\.(?:deb|rpm|AppImage|tar\.gz)|macos-[\w.-]+\.(?:dmg|zip))$',
  changelogPath: 'docs/aerove/CHANGELOG.md',
  coreSourceUrl: 'https://github.com/MetaCubeX/mihomo',
  resources: ProductResources(
    defaultScriptAsset: 'assets/data/smart-config-kit-flclash.js',
    defaultScriptSha256:
        'f33ea7ef24043899d734f3174000b87f4f620cd35c4328af484c32775ea062bc',
    defaultScriptUrl: standardDefaultScriptUrl,
    legacyScriptUrls: standardLegacyScriptUrls,
    iconAsset: 'assets/images/icon.png',
    trustedRootsAsset: 'core/Clash.Meta/component/ca/ca-certificates.crt',
    geoAssets: {
      'GEOIP.metadb': 'assets/data/GEOIP.metadb',
      'ASN.mmdb': 'assets/data/ASN.mmdb',
      'GEOIP.dat': 'assets/data/GEOIP.dat',
      'GEOSITE.dat': 'assets/data/GEOSITE.dat',
    },
  ),
);
