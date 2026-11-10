// Pure Dart probe: pause the real sibling write before atomic publication.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../packages/aerove_client/lib/common/file.dart';

class PausedFile implements File {
  PausedFile(this.real, this.entered, this.release);
  final File real;
  final Completer<void> entered;
  final Completer<void> release;
  @override
  String get path => real.path;
  @override
  Directory get parent => real.parent;
  @override
  Future<File> writeAsString(
    String contents, {
    FileMode mode = FileMode.write,
    Encoding encoding = utf8,
    bool flush = false,
  }) async {
    await real.writeAsString('{', flush: flush);
    entered.complete();
    await release.future;
    return real.writeAsString(
      contents,
      mode: mode,
      encoding: encoding,
      flush: flush,
    );
  }

  @override
  Future<File> rename(String newPath) => real.rename(newPath);
  @override
  Future<FileSystemEntity> delete({bool recursive = false}) =>
      real.delete(recursive: recursive);
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<void> main() async {
  final home = await Directory.systemTemp.createTemp('owned-marker-');
  try {
    for (final name in ['worker-ready.json', 'ready.json']) {
      final marker = File('${home.path}/$name');
      final sibling = File('${marker.path}.$pid.tmp');
      final entered = Completer<void>(), release = Completer<void>();
      final writing = IOOverrides.runZoned(
        () => marker.writeAsStringAtomically('{"nonce":"test","pid":$pid}'),
        createFile: (path) {
          if (path == marker.path) return marker;
          if (path != sibling.path) throw StateError('Unexpected file $path');
          return PausedFile(sibling, entered, release);
        },
      );
      try {
        await entered.future;
        if (await marker.exists() || await sibling.readAsString() != '{') {
          throw StateError('Partial marker was published');
        }
      } finally {
        release.complete();
      }
      await writing;
      if (jsonDecode(await marker.readAsString())['pid'] != pid ||
          await sibling.exists()) {
        throw StateError('Complete marker was not atomically published');
      }
    }
  } finally {
    await home.delete(recursive: true);
  }
}
