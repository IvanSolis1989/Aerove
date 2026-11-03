part of 'native_lite_mapping.dart';

class NativeLiteResourcePlan {
  NativeLiteResourcePlan(this.inputs, this.options, this.files);
  final Map<String, Map<String, dynamic>> inputs;
  final Map<String, ProxyProviderOptions> options;
  final List<NativeLiteFile> files;
}

Future<NativeLiteResourcePlan> mapNativeLiteResources(
  NativeLiteExport source,
  String uuid,
  int profileId,
  Map<String, dynamic> profile,
  List<int> sourceBytes,
) async {
  Map<String, dynamic> document;
  try {
    document = legacyObject(
      jsonDecode(jsonEncode(loadYaml(utf8.decode(sourceBytes)))),
      'profiles.$uuid.source',
    );
  } catch (error) {
    if (error is NativeLiteMigrationException) rethrow;
    legacyFailure('profiles.$uuid.source', 'invalid original UTF8 YAML');
  }
  final inputs = <String, Map<String, dynamic>>{};
  final options = <String, ProxyProviderOptions>{};
  final files = <NativeLiteFile>[];
  final settings = await source.json('settings/settings.json');
  final geoSettings = settings?['geoResources'] is Map
      ? Map<String, dynamic>.from(settings!['geoResources'] as Map)
      : <String, dynamic>{};
  final bundle = profile['bundle'];
  final records = <String, Map<String, dynamic>>{};
  Map<String, dynamic> runtimeDefinitions = document;
  Map<String, dynamic>? manifest;
  String? generationRoot;
  if (bundle != null) {
    if (bundle is! String || !RegExp(r'^[0-9a-f-]{36}$').hasMatch(bundle))
      legacyFailure('profiles.$uuid.bundle', 'invalid generation identity');
    generationRoot = 'runtime/resources/bundles/$uuid/$bundle';
    manifest = await source.json('$generationRoot/manifest.json');
    if (manifest == null ||
        manifest['schema'] != 1 ||
        manifest['profileId'] != uuid ||
        manifest['generation'] != bundle ||
        manifest['sourceSha256'] != sha256.convert(sourceBytes).toString()) {
      legacyFailure(
        'profiles.$uuid.bundle',
        'generation is not bound to this original profile source',
      );
    }
    final runtimeInput = await source.source('$generationRoot/input.yaml');
    final runtimeBytes = await runtimeInput.readAsBytes();
    if (sha256.convert(runtimeBytes).toString() !=
        manifest['compiledInputSha256'])
      legacyFailure(
        '$generationRoot/input.yaml',
        'generation input checksum mismatch',
      );
    try {
      runtimeDefinitions = legacyObject(
        jsonDecode(jsonEncode(loadYaml(utf8.decode(runtimeBytes)))),
        '$generationRoot/input.yaml',
      );
    } catch (error) {
      if (error is NativeLiteMigrationException) rethrow;
      legacyFailure('$generationRoot/input.yaml', 'invalid generation input');
    }
    for (final raw in legacyList(
      manifest['records'],
      '$generationRoot.records',
    )) {
      final row = legacyObject(raw, '$generationRoot.records');
      final key = legacyText(row['key'], '$generationRoot.records.key');
      if (records.containsKey(key))
        legacyFailure(
          '$generationRoot.records.key',
          'ambiguous resource identity',
        );
      records[key] = row;
      final payload = await source.source(
        '$generationRoot/${legacyText(row['file'], '$generationRoot.records.file')}',
      );
      if (await payload.length() != row['bytes'] ||
          sha256.convert(await payload.readAsBytes()).toString() !=
              row['sha256'])
        legacyFailure(
          '$generationRoot.records.$key',
          'generation payload checksum mismatch',
        );
    }
  }
  Future<File?> libraryOriginal(Map<String, dynamic> record) async {
    final matches = <File>[];
    for (final key in source.records.keys.where(
      (path) => RegExp(
        r'^runtime/resources/library/[0-9a-f]{64}\.json$',
      ).hasMatch(path),
    )) {
      final cached = (await source.json(key))!;
      if (cached['kind'] != record['kind'] ||
          cached['url'] != (record['url'] ?? '') ||
          cached['behavior'] != record['behavior'] ||
          cached['format'] != (record['sourceFormat'] ?? record['format']))
        continue;
      if (cached['sha256'] != record['sha256'] &&
          cached['runtimeSha256'] != record['sha256'])
        continue;
      final file = await source.source(
        'runtime/resources/library/${legacyText(cached['file'], '$key.file')}',
      );
      if (await file.length() != cached['bytes'] ||
          sha256.convert(await file.readAsBytes()).toString() !=
              cached['sha256'])
        legacyFailure(key, 'original library resource checksum mismatch');
      matches.add(file);
    }
    if (matches.length > 1 &&
        matches.map((file) => file.path).toSet().length > 1)
      legacyFailure(
        'profiles.$uuid.bundle.library',
        'ambiguous original library payload',
      );
    return matches.firstOrNull;
  }

  Future<void> capture(
    String key,
    String target, {
    Map<String, dynamic>? definition,
    String? importedPath,
  }) async {
    final record = records[key];
    File? original;
    if (record != null) {
      if (record['kind'] == 'proxy-provider' ||
          record['kind'] == 'sealed-lineage')
        legacyFailure(
          'profiles.$uuid.$key',
          'compiled resource cannot be used as original source',
        );
      if (record['format'] != (record['sourceFormat'] ?? record['format'])) {
        original = await libraryOriginal(record);
        if (original == null)
          legacyFailure(
            'profiles.$uuid.$key.sourceFormat',
            'normalized generation has no matching original library payload',
          );
      } else {
        original = await source.source('$generationRoot/${record['file']}');
      }
    } else if (importedPath != null) {
      if (importedPath.startsWith('.library/')) {
        final matches = <Map<String, dynamic>>[];
        for (final path in source.records.keys.where(
          (path) => RegExp(
            r'^runtime/resources/library/[0-9a-f]{64}\.json$',
          ).hasMatch(path),
        )) {
          final cached = (await source.json(path))!;
          if (cached['localId'] == importedPath.substring(9) &&
              cached['kind'] == 'ruleset')
            matches.add(cached);
        }
        if (matches.length != 1)
          legacyFailure(
            'profiles.$uuid.$key.path',
            'missing or ambiguous original library payload',
          );
        original = await source.source(
          'runtime/resources/library/${matches.single['file']}',
        );
        if (await original.length() != matches.single['bytes'] ||
            sha256.convert(await original.readAsBytes()).toString() !=
                matches.single['sha256'])
          legacyFailure(
            'profiles.$uuid.$key.path',
            'original library payload checksum mismatch',
          );
      } else {
        original = await source.source(
          'runtime/resources/imports/$uuid/$importedPath',
        );
      }
    }
    if (original == null) return;
    final bytes = await original.readAsBytes();
    final receiptFile =
        'profiles/providers/$profileId/rules/${md5.convert(utf8.encode('native-receipt/$key'))}';
    final receipt = <String, dynamic>{
      if (['\$.mmdb', '\$.asn-mmdb', '\$.geosite'].contains(key) &&
          geoSettings['autoUpdate'] == true)
        'cacheMaxAgeMillis':
            legacyInt(
              geoSettings['intervalHours'],
              'settings.geoResources.intervalHours',
            ) *
            3600000,
      'schema': 1,
      'sourceProfileUuid': uuid,
      'key': key,
      'file': target,
      'receiptFile': receiptFile,
      'sha256': sha256.convert(bytes).toString(),
      'bytes': bytes.length,
      'sourcePath': source.records.keys.singleWhere(
        (path) => p.equals(
          p.join(source.directory.path, 'payload', path),
          original!.path,
        ),
      ),
      if (record != null) 'nativeRecord': record,
      if (definition != null) 'sourceDefinition': definition,
      if (definition == null && record?['url'] != null)
        'sourceDefinition': {'type': 'http', 'url': record!['url']},
      if (manifest != null) 'requestPolicy': manifest['requestPolicy'],
      'checkedAt':
          (profile['resourceCheckedAt'] is Map
              ? (profile['resourceCheckedAt'] as Map)[key]
              : null) ??
          record?['updatedAt'] ??
          0,
      'updatedAt': record?['updatedAt'] ?? 0,
      'interval': record?['interval'] ?? definition?['interval'] ?? 0,
      if (record?['subscriptionUserInfo'] != null)
        'subscriptionUserInfo': record!['subscriptionUserInfo'],
    };
    final encoded = Uint8List.fromList(utf8.encode(jsonEncode(receipt)));
    inputs[key] = {
      ...receipt,
      'receiptSha256': sha256.convert(encoded).toString(),
    };
    files.add(NativeLiteFile.copy(target, original));
    files.add(NativeLiteFile.bytes(receiptFile, encoded));
  }

  for (final section in ['proxy-providers', 'rule-providers']) {
    final originals = legacyObject(
      document[section] ?? {},
      'profiles.$uuid.$section',
    );
    final runtime = legacyObject(
      runtimeDefinitions[section] ?? {},
      'profiles.$uuid.runtime.$section',
    );
    final definitions = {...originals, ...runtime};
    for (final entry in definitions.entries) {
      final definition = legacyObject(
        entry.value,
        'profiles.$uuid.$section.${entry.key}',
      );
      final type = definition['type'];
      if (type == 'inline') continue;
      if (type != 'file' && type != 'http')
        legacyFailure(
          'profiles.$uuid.$section.${entry.key}.type',
          'unsupported source provider type',
        );
      if (section == 'proxy-providers' && originals.containsKey(entry.key)) {
        try {
          options[entry.key] =
              ProxyProviderOptions.fromJson(
                _nativeProviderEditableJson(
                  legacyObject(
                    originals[entry.key],
                    'profiles.$uuid.$section.${entry.key}',
                  ),
                ),
              ).copyWith(
                sourceDefinition: legacyObject(
                  originals[entry.key],
                  'profiles.$uuid.$section.${entry.key}',
                ),
                editedFields: {},
              );
        } catch (_) {
          legacyFailure(
            'profiles.$uuid.$section.${entry.key}',
            'provider options cannot be represented losslessly',
          );
        }
      }
      final url = definition['url'];
      final identity = url is String && url.isNotEmpty
          ? '${entry.key}@$url'
          : '$section/${entry.key}';
      final target =
          'profiles/providers/$profileId/${section == 'proxy-providers' ? 'proxies' : 'rules'}/${md5.convert(utf8.encode(identity))}';
      await capture(
        '\$.$section.${entry.key}',
        target,
        definition: definition,
        importedPath: type == 'file'
            ? legacyText(
                definition['path'],
                'profiles.$uuid.$section.${entry.key}.path',
              )
            : null,
      );
    }
  }
  for (final entry in records.entries) {
    final key = entry.key;
    if (inputs.containsKey(key) ||
        entry.value['kind'] == 'proxy-provider' ||
        entry.value['kind'] == 'sealed-lineage' ||
        key.endsWith('.payload'))
      continue;
    if (entry.value['kind'] != 'private-file' &&
        !['\$.mmdb', '\$.asn-mmdb', '\$.geosite'].contains(key))
      continue;
    await capture(
      key,
      'profiles/providers/$profileId/rules/${md5.convert(utf8.encode('native-resource/$key'))}',
    );
  }
  for (final key in ['mmdb', 'asn-mmdb', 'geosite']) {
    if (inputs.containsKey('\$.$key')) continue;
    final local = document[key];
    if (local is String)
      await capture(
        '\$.$key',
        'profiles/providers/$profileId/rules/${md5.convert(utf8.encode('native-resource/\$.$key'))}',
        importedPath: local,
      );
  }
  for (final entry in inputs.entries) {
    final receipt = Map<String, dynamic>.from(entry.value)
      ..remove('receiptSha256');
    receipt['dependencies'] = [
      for (final child in inputs.entries)
        if (child.key.startsWith('${entry.key}.proxies['))
          Map<String, dynamic>.from(child.value)..remove('receiptSha256'),
    ];
    final encoded = Uint8List.fromList(utf8.encode(jsonEncode(receipt)));
    if (encoded.length > 1024 * 1024)
      legacyFailure(
        'profiles.$uuid.resources.${entry.key}.receipt',
        'private receipt exceeds 1 MiB',
      );
    entry.value.addAll({
      'dependencies': receipt['dependencies'],
      'receiptSha256': sha256.convert(encoded).toString(),
    });
    files.removeWhere((file) => file.path == entry.value['receiptFile']);
    files.add(
      NativeLiteFile.bytes(entry.value['receiptFile'] as String, encoded),
    );
  }
  if (inputs.isNotEmpty) {
    final indexPath =
        'profiles/providers/$profileId/rules/${md5.convert(utf8.encode('native-resource-index'))}';
    final indexBytes = Uint8List.fromList(
      utf8.encode(
        jsonEncode({
          'schema': 1,
          'sourceProfileUuid': uuid,
          'inputs': inputs.values.toList(),
        }),
      ),
    );
    if (indexBytes.length > 2 * 1024 * 1024)
      legacyFailure(
        'profiles.$uuid.resources.index',
        'private resource index exceeds 2 MiB',
      );
    final digest = sha256.convert(indexBytes).toString();
    inputs['\$.__migration-input-index'] = {
      'file': indexPath,
      'receiptFile': indexPath,
      'sha256': digest,
      'receiptSha256': digest,
      'bytes': indexBytes.length,
    };
    files.add(NativeLiteFile.bytes(indexPath, indexBytes));
  }
  return NativeLiteResourcePlan(inputs, options, files);
}

Map<String, dynamic> _nativeProviderEditableJson(Map<String, dynamic> source) {
  final result = Map<String, dynamic>.from(source);
  final health = result['health-check'];
  if (health is Map && health['expected-status'] is int) {
    result['health-check'] = {
      ...Map<String, dynamic>.from(health),
      'expected-status': health['expected-status'].toString(),
    };
  }
  return result;
}
