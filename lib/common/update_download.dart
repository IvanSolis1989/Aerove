class UpdateAsset {
  const UpdateAsset({required this.name, required this.url});

  final String name;
  final Uri url;
}

UpdateAsset? selectAndroidUpdateAsset(
  Map<String, dynamic> release,
  List<String> supportedAbis,
) {
  final assets = release['assets'];
  if (assets is! List) return null;
  const aliases = {
    'arm64-v8a': ['arm64-v8a', 'arm64'],
    'armeabi-v7a': ['armeabi-v7a', 'arm'],
    'x86_64': ['x86_64', 'amd64', 'x64'],
    'x86': ['x86'],
  };
  for (final abi in supportedAbis) {
    for (final alias in aliases[abi] ?? <String>[]) {
      final suffix = '-android-$alias.apk';
      for (final asset in assets) {
        if (asset is! Map) continue;
        final name = asset['name'];
        final rawUrl = asset['browser_download_url'];
        if (name is! String || rawUrl is! String) continue;
        final url = Uri.tryParse(rawUrl);
        if (name.contains('/') || name.contains('\\')) continue;
        if (name.toLowerCase().endsWith(suffix) &&
            url != null &&
            url.scheme == 'https' &&
            url.host == 'github.com' &&
            url.path.contains('/releases/download/') &&
            url.pathSegments.last == name) {
          return UpdateAsset(name: name, url: url);
        }
      }
    }
  }
  return null;
}
