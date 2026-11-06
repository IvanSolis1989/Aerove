import 'package:collection/collection.dart';

class LocalProviderSource {
  LocalProviderSource({
    required this.relativePath,
    required this.sha256,
    required Map<String, dynamic> originalDefinition,
  }) : originalDefinition = Map.unmodifiable(originalDefinition);

  final String relativePath;
  final String sha256;
  final Map<String, dynamic> originalDefinition;

  factory LocalProviderSource.fromJson(Map<String, dynamic> json) {
    if (json.length != 5 ||
        json['schema'] is! int ||
        json['schema'] != 1 ||
        json['kind'] != 'detached' ||
        json['relativePath'] is! String ||
        json['sha256'] is! String ||
        json['originalDefinition'] is! Map) {
      throw const FormatException(
        'localProviderSources: invalid detached metadata',
      );
    }
    return LocalProviderSource(
      relativePath: json['relativePath'] as String,
      sha256: json['sha256'] as String,
      originalDefinition: Map<String, dynamic>.from(
        json['originalDefinition'] as Map,
      ),
    );
  }

  Map<String, dynamic> toJson() => {
    'schema': 1,
    'kind': 'detached',
    'relativePath': relativePath,
    'sha256': sha256,
    'originalDefinition': originalDefinition,
  };

  @override
  bool operator ==(Object other) =>
      other is LocalProviderSource &&
      relativePath == other.relativePath &&
      sha256 == other.sha256 &&
      const DeepCollectionEquality().equals(
        originalDefinition,
        other.originalDefinition,
      );
  @override
  int get hashCode => Object.hash(
    relativePath,
    sha256,
    const DeepCollectionEquality().hash(originalDefinition),
  );
}
