import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'package:flutter/foundation.dart' show compute;

String fileProviderSourcePath(
  int profileId,
  String section,
  String name, {
  bool detached = false,
}) {
  final directory = switch (section) {
    'proxy-providers' => 'proxies',
    'rule-providers' => 'rules',
    _ => throw const FormatException('file provider source: invalid section'),
  };
  if (name.isEmpty)
    throw const FormatException('file provider source: invalid owner');
  final key = md5
      .convert(utf8.encode('${detached ? 'detached/' : ''}$section/$name'))
      .toString();
  return 'profiles/providers/$profileId/$directory/$key';
}

void _checkComponents(String root, String relative) {
  var path = root;
  for (final part in p.posix.split(relative)) {
    if (part.isEmpty || part == '.' || part == '..')
      throw const FormatException('file provider source: unsafe path');
    path = p.join(path, part);
    final type = FileSystemEntity.typeSync(path, followLinks: false);
    if (type == FileSystemEntityType.link ||
        type != FileSystemEntityType.notFound &&
            !p.equals(
              File(path).resolveSymbolicLinksSync(),
              p.normalize(path),
            )) {
      throw const FormatException('file provider source: linked path');
    }
  }
}

List<String> registerFileProviderSource({
  required String homeDirPath,
  required int profileId,
  required String section,
  required String name,
  required String sourcePath,
  bool detached = false,
}) {
  final relative = fileProviderSourcePath(
    profileId,
    section,
    name,
    detached: detached,
  );
  final declared = p
      .relative(p.absolute(sourcePath), from: p.absolute(homeDirPath))
      .replaceAll('\\', '/');
  if (declared != relative)
    throw const FormatException('file provider source: owner path mismatch');
  if (!File(sourcePath).existsSync()) return const [];
  final root = Directory(homeDirPath).resolveSymbolicLinksSync();
  final markerRelative = '$relative.source.json';
  _checkComponents(root, relative);
  _checkComponents(root, markerRelative);
  final file = File(p.joinAll([root, ...p.posix.split(markerRelative)]));
  final value = {
    'schema': 1,
    'kind': 'file-provider-source',
    'profileId': profileId,
    'section': section,
    'name': name,
    'relativePath': relative,
  };
  if (file.existsSync()) {
    final prior = jsonDecode(file.readAsStringSync());
    if (prior is! Map ||
        prior.length != value.length ||
        !value.entries.every((entry) => prior[entry.key] == entry.value)) {
      throw const FormatException('file provider source: conflicting marker');
    }
    return const [];
  }
  file.parent.createSync(recursive: true);
  _checkComponents(root, markerRelative);
  final pending = File(
    '${file.path}.${DateTime.now().microsecondsSinceEpoch}.pending',
  );
  try {
    pending.writeAsStringSync(jsonEncode(value), flush: true);
    pending.renameSync(file.path);
  } finally {
    if (pending.existsSync()) pending.deleteSync();
  }
  return [relative, markerRelative];
}

Future<List<String>> fileProviderSourceBackupEntriesTask(
  String home,
  Iterable<int> profileIds,
) => compute(
  (payload) => fileProviderSourceBackupEntries(payload.home, payload.ids),
  (home: home, ids: profileIds.toList()),
);

void checkFileProviderSourceRestorePaths(
  String home,
  Iterable<String> entries,
) {
  final root = Directory(home).resolveSymbolicLinksSync();
  for (final entry in entries) {
    if (!RegExp(
      r'^profiles/providers/-?[0-9]+/(proxies|rules)/[0-9a-f]{32}(\.source\.json)?$',
    ).hasMatch(entry)) {
      throw const FormatException('file provider source: unsafe restore entry');
    }
    _checkComponents(root, entry);
  }
}

Future<void> checkFileProviderSourceRestorePathsTask(
  String home,
  List<String> entries,
) => compute((payload) {
  checkFileProviderSourceRestorePaths(payload.home, payload.entries);
}, (home: home, entries: entries));

List<String> fileProviderSourceBackupEntries(
  String homeDirPath,
  Iterable<int> profileIds,
) {
  final root = Directory(homeDirPath).resolveSymbolicLinksSync();
  final entries = <String>[];
  for (final id in profileIds.toSet()) {
    for (final kind in ['proxies', 'rules']) {
      final directoryRelative = 'profiles/providers/$id/$kind';
      _checkComponents(root, directoryRelative);
      final directory = Directory(
        p.joinAll([root, ...p.posix.split(directoryRelative)]),
      );
      if (!directory.existsSync()) continue;
      for (final marker in directory.listSync(followLinks: false)) {
        if (!RegExp(
          r'^[0-9a-f]{32}\.source\.json$',
        ).hasMatch(p.basename(marker.path)))
          continue;
        final markerRelative = p
            .relative(marker.path, from: root)
            .replaceAll('\\', '/');
        _checkComponents(root, markerRelative);
        if (FileSystemEntity.typeSync(marker.path, followLinks: false) !=
            FileSystemEntityType.file) {
          throw const FormatException(
            'file provider source: marker is not a regular file',
          );
        }
        final value = jsonDecode(File(marker.path).readAsStringSync());
        if (value is! Map ||
            value.length != 6 ||
            value['schema'] is! int ||
            value['schema'] != 1 ||
            value['profileId'] is! int ||
            value['kind'] != 'file-provider-source' ||
            value['profileId'] != id ||
            value['section'] !=
                (kind == 'proxies' ? 'proxy-providers' : 'rule-providers') ||
            value['name'] is! String) {
          throw const FormatException('file provider source: invalid marker');
        }
        final ordinary = fileProviderSourcePath(
          id,
          value['section'] as String,
          value['name'] as String,
        );
        final detached = fileProviderSourcePath(
          id,
          value['section'] as String,
          value['name'] as String,
          detached: true,
        );
        final relative = value['relativePath'];
        if (relative is! String ||
            relative != ordinary && relative != detached ||
            markerRelative != '$relative.source.json') {
          throw const FormatException(
            'file provider source: marker path mismatch',
          );
        }
        _checkComponents(root, relative);
        final source = File(p.joinAll([root, ...p.posix.split(relative)]));
        if (!source.existsSync()) continue;
        if (FileSystemEntity.typeSync(source.path, followLinks: false) !=
            FileSystemEntityType.file) {
          throw const FormatException(
            'file provider source: source is not a regular file',
          );
        }
        entries.addAll([relative, markerRelative]);
      }
    }
  }
  return entries..sort();
}
