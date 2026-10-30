import 'common/core_capabilities.dart';
import 'core/interface.dart';

export 'common/client_assets.dart';

class ProductResources {
  ProductResources({
    required this.defaultScriptAsset,
    required this.defaultScriptSha256,
    required this.defaultScriptUrl,
    required Set<String> legacyScriptUrls,
    required this.iconAsset,
    Map<String, String> geoAssets = const {},
    this.trustedRootsAsset,
  }) : legacyScriptUrls = Set.unmodifiable(legacyScriptUrls),
       geoAssets = Map.unmodifiable(geoAssets);

  final String defaultScriptAsset;
  final String defaultScriptSha256;
  final String defaultScriptUrl;
  final Set<String> legacyScriptUrls;
  final String iconAsset;
  final Map<String, String> geoAssets;
  final String? trustedRootsAsset;
}

class ProductSpec {
  const ProductSpec({
    required this.id,
    required this.displayName,
    required this.expectedCoreId,
    required this.minimumAndroidApi,
    required this.resources,
    required this.releaseFeed,
    required this.releaseTagPrefix,
    required this.releaseAssetPattern,
    required this.changelogPath,
    required this.coreSourceUrl,
  });

  final String id;
  final String displayName;
  final String expectedCoreId;
  final int minimumAndroidApi;
  final ProductResources resources;
  final String releaseFeed;
  final String releaseTagPrefix;
  final String releaseAssetPattern;
  final String changelogPath;
  final String coreSourceUrl;

  CoreCapabilities get coreCapabilities =>
      CoreCapabilities.forCore(expectedCoreId);
}

typedef CoreBackendFactory = CoreHandlerInterface Function();

class ClientProduct {
  static ProductSpec? _spec;
  static CoreBackendFactory? _backendFactory;

  static ProductSpec get current =>
      _spec ??
      (throw StateError('Product composition has not been configured'));

  static void configure({
    required ProductSpec product,
    required String backendCoreId,
    required CoreBackendFactory backendFactory,
  }) {
    if (_spec != null) throw StateError('Product is already configured');
    if (product.expectedCoreId != backendCoreId) {
      throw StateError(
        'Product ${product.id} expects ${product.expectedCoreId}, '
        'received $backendCoreId',
      );
    }
    _spec = product;
    _backendFactory = backendFactory;
  }

  static CoreHandlerInterface createBackend() =>
      (_backendFactory ??
      (throw StateError('Core backend is not configured')))();
}
