import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'package:fl_clash/models/provider_source.dart';
import 'package:flutter/foundation.dart' show compute;
import 'file_provider_source.dart';
import 'native_lite_migration.dart'
    show nativeLiteAtomicFile, nativeLitePrivateDestination, nativeLiteJsonHash;

Map<String, dynamic> admitProviderSourceRegistration(
  Map<String, dynamic> before,
  Map<String, dynamic> after,
  Iterable<String> registeredPaths,
) {
  final beforeHeader = Map<String, dynamic>.from(before)..remove('files');
  final afterHeader = Map<String, dynamic>.from(after)..remove('files');
  if (nativeLiteJsonHash(beforeHeader) != nativeLiteJsonHash(afterHeader)) {
    throw const FormatException(
      'provider source: business state changed during registration',
    );
  }
  Map<String, Map> indexed(Map<String, dynamic> proof) => {
    for (final file in (proof['files'] as List).cast<Map>())
      file['path'] as String: file,
  };
  final previous = indexed(before), next = indexed(after);
  if (previous.length != (before['files'] as List).length ||
      next.length != (after['files'] as List).length) {
    throw const FormatException('provider source: duplicate proof resource');
  }
  for (final entry in previous.entries) {
    if (!next.containsKey(entry.key) ||
        nativeLiteJsonHash(entry.value) !=
            nativeLiteJsonHash(next[entry.key])) {
      throw const FormatException(
        'provider source: existing resource changed during registration',
      );
    }
  }
  final witness = registeredPaths.toSet();
  for (final path in witness) {
    final source = path.endsWith('.source.json')
        ? path.substring(0, path.length - '.source.json'.length)
        : path;
    if (!witness.contains(source) ||
        !witness.contains('$source.source.json') ||
        !next.containsKey(source) ||
        !next.containsKey('$source.source.json')) {
      throw const FormatException(
        'provider source: incomplete source registration witness',
      );
    }
  }
  if (next.keys.any(
    (path) => !previous.containsKey(path) && !witness.contains(path),
  )) {
    throw const FormatException(
      'provider source: unexpected resource appeared during registration',
    );
  }
  return after;
}

/// Immutable recovery evidence for one managed provider source publication.
/// The Native Store decision, never this journal, decides whether to keep it.
class FileProviderSourceJournal {
  FileProviderSourceJournal._(
    this.home,
    this.id,
    this.sourcePlan,
    this.journalSha256,
  );
  final Directory home;
  final String id;
  final Map<String, dynamic> sourcePlan;
  final String journalSha256;
  String get relativePath =>
      'provider-source-transactions/$id/source-journal.json';
  Directory get directory =>
      Directory(p.join(home.path, 'provider-source-transactions', id));
  String get sourcePlanSha256 => nativeLiteJsonHash(sourcePlan);
  Map<String, dynamic> get plan => {
    'ownerSha256': nativeLiteJsonHash({
      'schema': 1,
      'scope': 'flutter-provider-owner',
      'profileId': sourcePlan['profileId'],
    }),
    'namespace': sourcePlan['namespace'],
    'name': sourcePlan['name'],
    'expectedRevision': sourcePlan['expectedRevision'],
    'expected': sourcePlan['expected'],
    'sourcePlanSha256': sourcePlanSha256,
  };
  String get planSha256 => nativeLiteJsonHash(plan);
  Map<String, dynamic> envelope(String operation) => {
    'schemaVersion': 1,
    'operation': operation,
    'transactionId': id,
    'planSha256': planSha256,
  };
  Map<String, dynamic> beginRequest(Map<String, dynamic> proof) => {
    'schemaVersion': 1,
    'operation': 'begin',
    'transactionId': id,
    'plan': plan,
    'sourceJournal': {'relativePath': relativePath, 'sha256': journalSha256},
    'proof': proof,
  };
  void checkReply(Map<String, dynamic> reply) => _checkReply(reply);

  static Future<FileProviderSourceJournal> prepare({
    required Directory home,
    required String id,
    required int profileId,
    required String namespace,
    required String name,
    required String expectedRevision,
    required Map<String, dynamic> expected,
    required Map<String, dynamic> beforeProof,
    required List<int> bytes,
  }) async {
    if (!RegExp(
      r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    ).hasMatch(id)) {
      throw const FormatException(
        'provider source: invalid transaction identity',
      );
    }
    if (bytes.length >
        (namespace == 'proxy-providers' ? 4 : 64) * 1024 * 1024) {
      throw const FormatException(
        'provider source: source byte budget exceeded',
      );
    }
    final root = Directory(await home.resolveSymbolicLinks());
    final relative = fileProviderSourcePath(profileId, namespace, name);
    checkFileProviderSourceRestorePaths(root.path, [
      relative,
      '$relative.source.json',
    ]);
    final source = File(p.join(root.path, relative));
    if (!await source.exists())
      throw const FormatException('provider source: managed source is missing');
    final directory = Directory(
      p.join(root.path, 'provider-source-transactions', id),
    );
    await nativeLitePrivateDestination(
      File(p.join(directory.path, 'source-journal.json')),
      root,
    );
    if (await directory.exists())
      throw const FormatException(
        'provider source: transaction already exists',
      );
    final marker = utf8.encode(
      jsonEncode({
        'schema': 1,
        'kind': 'file-provider-source',
        'profileId': profileId,
        'section': namespace,
        'name': name,
        'relativePath': relative,
      }),
    );
    final existingMarker = File(p.join(root.path, '$relative.source.json'));
    if (await existingMarker.exists() &&
        nativeLiteJsonHash(jsonDecode(await existingMarker.readAsString())) !=
            nativeLiteJsonHash(jsonDecode(utf8.decode(marker)))) {
      throw const FormatException(
        'provider source: conflicting source registration',
      );
    }
    await directory.create(recursive: true);
    try {
      final entries = <Map<String, dynamic>>[];
      for (final item in [
        (path: relative, after: bytes),
        (path: '$relative.source.json', after: marker),
      ]) {
        final file = File(p.join(root.path, item.path));
        if (await file.exists() &&
            await file.length() >
                (item.path.endsWith('.source.json')
                    ? 16384
                    : (namespace == 'proxy-providers' ? 4 : 64) *
                          1024 *
                          1024)) {
          throw const FormatException(
            'provider source: prior source byte budget exceeded',
          );
        }
        final before = await file.exists() ? await file.readAsBytes() : null;
        final index = entries.length;
        if (before != null)
          await nativeLiteAtomicFile(
            File(p.join(directory.path, '$index.before')),
            before,
            boundary: root,
          );
        await nativeLiteAtomicFile(
          File(p.join(directory.path, '$index.after')),
          item.after,
          boundary: root,
        );
        entries.add({
          'relativePath': item.path,
          'beforeSha256': before == null
              ? null
              : sha256.convert(before).toString(),
          'afterSha256': sha256.convert(item.after).toString(),
        });
      }
      final sourcePlan = <String, dynamic>{
        'schema': 1,
        'kind': 'managed-file',
        'profileId': profileId,
        'namespace': namespace,
        'name': name,
        'expectedRevision': expectedRevision,
        'expected': expected,
        'beforeStateSha256': nativeLiteJsonHash(beforeProof),
        'files': entries,
      };
      final encoded = utf8.encode(
        jsonEncode({
          'schema': 1,
          'id': id,
          'sourcePlan': sourcePlan,
          'sourcePlanSha256': nativeLiteJsonHash(sourcePlan),
        }),
      );
      await nativeLiteAtomicFile(
        File(p.join(directory.path, 'source-journal.json')),
        encoded,
        boundary: root,
      );
      return FileProviderSourceJournal._(
        root,
        id,
        sourcePlan,
        sha256.convert(encoded).toString(),
      );
    } catch (_) {
      await _removeOwnedDirectory(directory, root);
      rethrow;
    }
  }

  static Future<void> _removeOwnedDirectory(
    Directory directory,
    Directory home,
  ) async {
    await nativeLitePrivateDestination(
      File(p.join(directory.path, 'source-journal.json')),
      home,
    );
    final files = await directory.list(followLinks: false).toList();
    for (final file in files) {
      if (file is! File ||
          !RegExp(
            r'^(?:[01]\.(?:before|after)|source-journal\.json|source-finalized\.json|acknowledged\.json)$',
          ).hasMatch(p.basename(file.path))) {
        throw const FormatException(
          'provider source: unexpected transaction child',
        );
      }
      await nativeLitePrivateDestination(file, home);
    }
    // The acknowledgement is removed last, so interrupted cleanup cannot be
    // mistaken for an unresolved source publication on next startup.
    files.sort(
      (a, b) => (p.basename(a.path) == 'acknowledged.json' ? 1 : 0).compareTo(
        p.basename(b.path) == 'acknowledged.json' ? 1 : 0,
      ),
    );
    for (final file in files) {
      await file.delete();
    }
    await directory.delete();
  }

  Future<void> _writeState({required bool after}) async {
    final entries = (sourcePlan['files'] as List).cast<Map>();
    // Check every target before changing any. Recovery accepts a mixture left
    // by a crash, but never overwrites an unrelated third value.
    for (final entry in entries) {
      final relative = entry['relativePath'] as String;
      checkFileProviderSourceRestorePaths(home.path, [relative]);
      final file = File(p.join(home.path, relative));
      if (await file.exists() &&
          await file.length() >
              (relative.endsWith('.source.json')
                  ? 16384
                  : (sourcePlan['namespace'] == 'proxy-providers' ? 4 : 64) *
                        1024 *
                        1024)) {
        throw const FormatException(
          'provider source: current source byte budget exceeded',
        );
      }
      final actual = await file.exists()
          ? sha256.convert(await file.readAsBytes()).toString()
          : null;
      if (actual != entry['beforeSha256'] && actual != entry['afterSha256']) {
        throw const FormatException(
          'provider source: publication compare-and-swap failed',
        );
      }
    }
    for (var index = 0; index < entries.length; index++) {
      final entry = entries[index];
      final target = File(p.join(home.path, entry['relativePath'] as String));
      final digest = entry[after ? 'afterSha256' : 'beforeSha256'];
      final actual = await target.exists()
          ? sha256.convert(await target.readAsBytes()).toString()
          : null;
      if (actual == digest) continue;
      if (digest == null) {
        if (await target.exists()) await target.delete();
        continue;
      }
      final blob = File(
        p.join(directory.path, '$index.${after ? 'after' : 'before'}'),
      );
      await nativeLitePrivateDestination(blob, home);
      if (await blob.length() >
          (index == 1
              ? 16384
              : (sourcePlan['namespace'] == 'proxy-providers' ? 4 : 64) *
                    1024 *
                    1024)) {
        throw const FormatException(
          'provider source: recovery blob byte budget exceeded',
        );
      }
      final bytes = await blob.readAsBytes();
      if (sha256.convert(bytes).toString() != digest)
        throw const FormatException('provider source: recovery blob changed');
      await nativeLiteAtomicFile(target, bytes, boundary: home);
    }
  }

  Future<void> publish() => _writeState(after: true);
  Future<void> restore() => _writeState(after: false);

  /// Only the initiating call may use this before it has invoked Native begin.
  /// It removes staging without restoring or publishing any business byte.
  Future<void> discardUnbegun() async {
    for (final entry in (sourcePlan['files'] as List).cast<Map>()) {
      final relative = entry['relativePath'] as String;
      checkFileProviderSourceRestorePaths(home.path, [relative]);
      final target = File(p.join(home.path, relative));
      final actual = await target.exists()
          ? sha256.convert(await target.readAsBytes()).toString()
          : null;
      if (actual != entry['beforeSha256'])
        throw const FormatException(
          'provider source: unbegun staging no longer matches source',
        );
    }
    await _removeOwnedDirectory(directory, home);
  }

  Future<void> finalize(String decision) async {
    if (!{'COMMITTED', 'ABORTED'}.contains(decision))
      throw const FormatException('provider source: unresolved decision');
    await nativeLiteAtomicFile(
      File(p.join(directory.path, 'source-finalized.json')),
      utf8.encode(
        jsonEncode({
          'schema': 1,
          'planSha256': planSha256,
          'decision': decision,
        }),
      ),
      boundary: home,
    );
  }

  static Future<List<FileProviderSourceJournal>> pending(Directory home) async {
    final root = Directory(await home.resolveSymbolicLinks());
    final folder = Directory(p.join(root.path, 'provider-source-transactions'));
    await nativeLitePrivateDestination(
      File(p.join(folder.path, 'probe')),
      root,
    );
    if (!await folder.exists()) return [];
    final result = <FileProviderSourceJournal>[];
    await for (final entry in folder.list(followLinks: false)) {
      final id = p.basename(entry.path);
      if (entry is! Directory ||
          !RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
          ).hasMatch(id)) {
        throw const FormatException(
          'provider source: unexpected recovery entry',
        );
      }
      final file = File(p.join(entry.path, 'source-journal.json'));
      await nativeLitePrivateDestination(file, root);
      final acknowledgement = File(p.join(entry.path, 'acknowledged.json'));
      await nativeLitePrivateDestination(acknowledgement, root);
      if (await acknowledgement.exists()) {
        final ack = jsonDecode(await acknowledgement.readAsString()) as Map;
        if (ack.length != 3 ||
            ack['schema'] != 1 ||
            ack['id'] != id ||
            ack['planSha256'] is! String ||
            !RegExp(r'^[0-9a-f]{64}$').hasMatch(ack['planSha256'] as String)) {
          throw const FormatException(
            'provider source: invalid final acknowledgement',
          );
        }
        await _removeOwnedDirectory(entry, root);
        continue;
      }
      if ((await entry.list(followLinks: false).toList()).isEmpty) {
        await entry.delete();
        continue;
      }
      if (await file.length() > 8 * 1024 * 1024)
        throw const FormatException(
          'provider source: journal byte budget exceeded',
        );
      final bytes = await file.readAsBytes();
      final json = Map<String, dynamic>.from(
        jsonDecode(utf8.decode(bytes)) as Map,
      );
      final plan = Map<String, dynamic>.from(json['sourcePlan'] as Map);
      if (json.length != 4 ||
          json['schema'] != 1 ||
          json['id'] != id ||
          json['sourcePlanSha256'] != nativeLiteJsonHash(plan) ||
          plan['schema'] != 1 ||
          plan['kind'] != 'managed-file') {
        throw const FormatException(
          'provider source: invalid recovery journal',
        );
      }
      final relative = fileProviderSourcePath(
        plan['profileId'] as int,
        plan['namespace'] as String,
        plan['name'] as String,
      );
      final files = plan['files'] as List;
      if (files.length != 2 ||
          (files[0] as Map)['relativePath'] != relative ||
          (files[1] as Map)['relativePath'] != '$relative.source.json') {
        throw const FormatException('provider source: recovery owner mismatch');
      }
      for (final item in files.cast<Map>()) {
        if (item.length != 3 ||
            item['beforeSha256'] != null &&
                !RegExp(
                  r'^[0-9a-f]{64}$',
                ).hasMatch(item['beforeSha256'] as String) ||
            !RegExp(
              r'^[0-9a-f]{64}$',
            ).hasMatch(item['afterSha256'] as String)) {
          throw const FormatException(
            'provider source: invalid recovery digest',
          );
        }
      }
      result.add(
        FileProviderSourceJournal._(
          root,
          id,
          plan,
          sha256.convert(bytes).toString(),
        ),
      );
    }
    return result;
  }

  /// Call only while holding the Dart source gate. A lost Native answer leaves
  /// this immutable evidence in place and blocks the next writer.
  Future<Map<String, dynamic>> recover({
    required Future<Map<String, dynamic>> Function(Map<String, dynamic>) invoke,
    required Future<Map<String, dynamic>> Function() readProof,
  }) async {
    var reply = await invoke(envelope('query'));
    _checkReply(reply);
    final decision = reply['decision'];
    if (decision == 'COMMITTED') {
      await publish();
    } else if (decision == 'UNDECIDED' || decision == 'ABORTED') {
      if (decision == 'UNDECIDED' &&
          reply['state'] != 'PREPARED' &&
          reply['sourceAction'] != 'restore-old') {
        throw const FormatException(
          'provider source: candidate quiescence is unconfirmed',
        );
      }
      await restore();
      final proof = await readProof();
      if (nativeLiteJsonHash(proof) != sourcePlan['beforeStateSha256']) {
        throw const FormatException(
          'provider source: restored business proof changed',
        );
      }
      if (decision == 'UNDECIDED') {
        reply = await invoke({
          ...envelope('abort'),
          'restored': {
            'sourceJournalSha256': journalSha256,
            'beforeStateSha256': nativeLiteJsonHash(proof),
            'proof': proof,
          },
        });
        _checkReply(reply);
      }
    }
    if (!{'COMMITTED', 'ABORTED'}.contains(reply['decision'])) {
      throw const FormatException(
        'provider source: recovery remains unresolved',
      );
    }
    // If commitment raced with abort, the sole durable decision wins.
    if (reply['decision'] == 'COMMITTED') await publish();
    await finalize(reply['decision'] as String);
    final finished = await invoke({
      ...envelope('query'),
      'sourceFinalized': true,
    });
    _checkReply(finished);
    if (finished['decision'] != reply['decision'])
      throw const FormatException('provider source: terminal decision changed');
    await nativeLiteAtomicFile(
      File(p.join(directory.path, 'acknowledged.json')),
      utf8.encode(
        jsonEncode({'schema': 1, 'id': id, 'planSha256': planSha256}),
      ),
      boundary: home,
    );
    await _removeOwnedDirectory(directory, home);
    return finished;
  }

  void _checkReply(Map<String, dynamic> reply) {
    if (reply['schemaVersion'] != 1 ||
        reply['transactionId'] != id ||
        reply['planSha256'] != planSha256 ||
        !{'UNDECIDED', 'COMMITTED', 'ABORTED'}.contains(reply['decision'])) {
      throw const FormatException(
        'provider source: Native decision identity mismatch',
      );
    }
  }
}

Future<Map<String, dynamic>> applyLocalProviderSourcesTask(
  Map<String, dynamic> source, {
  required int? profileId,
  required String homeDirPath,
  required Map<String, LocalProviderSource> bindings,
  String coreId = 'mihomo',
}) => compute(
  (payload) => applyLocalProviderSources(
    payload.source,
    profileId: payload.profileId,
    homeDirPath: payload.home,
    bindings: payload.bindings,
    coreId: payload.coreId,
  ),
  (
    source: source,
    profileId: profileId,
    home: homeDirPath,
    bindings: bindings,
    coreId: coreId,
  ),
);

({String section, String name}) providerSourceKey(String key) {
  for (final section in ['proxy-providers', 'rule-providers']) {
    final prefix = '$section/';
    if (key.startsWith(prefix) && key.length > prefix.length)
      return (section: section, name: key.substring(prefix.length));
  }
  throw const FormatException('localProviderSources: invalid provider key');
}

({LocalProviderSource binding, Uint8List bytes}) prepareDetachedProviderSource({
  required int profileId,
  required String key,
  required List<int> bytes,
  required Map<String, dynamic> originalDefinition,
  Iterable<String> immutableInputs = const [],
}) {
  final selected = providerSourceKey(key);
  final relative = fileProviderSourcePath(
    profileId,
    selected.section,
    selected.name,
    detached: true,
  );
  if (immutableInputs.contains(relative))
    throw const FormatException(
      'localProviderSources: target overlaps immutable input',
    );
  if (bytes.length >
      (selected.section == 'proxy-providers' ? 4 : 64) * 1024 * 1024) {
    throw const FormatException(
      'localProviderSources: source byte budget exceeded',
    );
  }
  if (originalDefinition.keys.any(
    {
      'nativeRecord',
      'receiptSha256',
      'nativeResourceInputs',
      'runtimeRevision',
      'compatibility',
    }.contains,
  )) {
    throw const FormatException(
      'localProviderSources: runtime receipt is not a business definition',
    );
  }
  final content = Uint8List.fromList(bytes);
  return (
    binding: LocalProviderSource(
      relativePath: relative,
      sha256: sha256.convert(content).toString(),
      originalDefinition: Map<String, dynamic>.from(
        jsonDecode(jsonEncode(originalDefinition)) as Map,
      ),
    ),
    bytes: content,
  );
}

Map<String, dynamic> applyLocalProviderSources(
  Map<String, dynamic> source, {
  required int? profileId,
  required String homeDirPath,
  required Map<String, LocalProviderSource> bindings,
  String coreId = 'mihomo',
}) {
  if (bindings.isEmpty) return source;
  if (profileId == null)
    throw const FormatException(
      'localProviderSources: selected profile owner is required',
    );
  final result = Map<String, dynamic>.from(source);
  for (final entry in bindings.entries) {
    final selected = providerSourceKey(entry.key);
    final binding = entry.value;
    final relative = fileProviderSourcePath(
      profileId,
      selected.section,
      selected.name,
      detached: true,
    );
    if (binding.relativePath != relative ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(binding.sha256)) {
      throw const FormatException(
        'localProviderSources: detached owner path or hash is invalid',
      );
    }
    checkFileProviderSourceRestorePaths(homeDirPath, [relative]);
    final path = p.joinAll([homeDirPath, ...p.posix.split(relative)]);
    final file = File(path);
    if (!file.existsSync() ||
        file.lengthSync() >
            (selected.section == 'proxy-providers' ? 4 : 64) * 1024 * 1024 ||
        sha256.convert(file.readAsBytesSync()).toString() != binding.sha256) {
      throw const FormatException(
        'localProviderSources: detached source is missing or changed',
      );
    }
    if (!fileProviderSourceBackupEntries(homeDirPath, [
      profileId,
    ]).contains(relative)) {
      throw const FormatException(
        'localProviderSources: detached source registration is missing',
      );
    }
    final definitions = result[selected.section];
    final definition = {
      ...binding.originalDefinition,
      'type': 'file',
      'path': path,
    };
    if (coreId == 'meow-rs') {
      for (final key in ['url', 'proxy', 'header']) {
        definition.remove(key);
      }
      definition['interval'] = 0;
    }
    result[selected.section] = {
      if (definitions is Map) ...definitions,
      selected.name: definition,
    };
  }
  return result;
}
