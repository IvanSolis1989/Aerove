import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/preferences_store.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart';
import 'package:shared_preferences_platform_interface/types.dart';

const _marker = 'flutter.marker';

void main() {
  late Directory dir;
  late String path;
  late AtomicFilePreferencesStore store;

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('preferences_store_skip');
    path = join(dir.path, 'shared_preferences.json');
    store = AtomicFilePreferencesStore(Future.value(path));
  });

  tearDown(() => dir.delete(recursive: true));

  Map<String, Object?> readFile() {
    return (json.decode(File(path).readAsStringSync()) as Map)
        .cast<String, Object?>();
  }

  /// Plants a key only a rewrite of the whole file would drop.
  void markFile() {
    File(path).writeAsStringSync(json.encode({...readFile(), _marker: true}));
  }

  test('setting the stored value again leaves the file alone', () async {
    expect(await store.setValue('String', 'flutter.config', 'a'), isTrue);
    expect(await store.setValue('Int', 'flutter.version', 3), isTrue);
    markFile();

    expect(await store.setValue('String', 'flutter.config', 'a'), isTrue);
    expect(await store.setValue('Int', 'flutter.version', 3), isTrue);
    expect(await store.remove('flutter.missing'), isTrue);
    expect(
      await store.clearWithParameters(
        ClearParameters(filter: PreferencesFilter(prefix: 'other.')),
      ),
      isTrue,
    );

    expect(readFile(), containsPair(_marker, true));
  });

  test('a different value is written', () async {
    await store.setValue('String', 'flutter.config', 'a');
    markFile();

    expect(await store.setValue('String', 'flutter.config', 'b'), isTrue);

    expect(readFile(), {'flutter.config': 'b'});
  });

  test('a number keeps the type it was set with', () async {
    await store.setValue('Int', 'flutter.ratio', 1);

    expect(await store.setValue('Double', 'flutter.ratio', 1.0), isTrue);

    expect(readFile()['flutter.ratio'], isA<double>());
  });

  test('a list changed by its caller is written again', () async {
    final list = ['a'];
    await store.setValue('StringList', 'flutter.list', list);

    list.add('b');
    expect(await store.setValue('StringList', 'flutter.list', list), isTrue);

    expect(readFile()['flutter.list'], ['a', 'b']);
  });

  test('a value cleared away is written again', () async {
    await store.setValue('String', 'flutter.config', 'a');
    expect(await store.clear(), isTrue);
    expect(readFile(), isEmpty);

    expect(await store.setValue('String', 'flutter.config', 'a'), isTrue);

    expect(readFile(), {'flutter.config': 'a'});
  });

  test('a value removed is written again', () async {
    await store.setValue('String', 'flutter.config', 'a');
    expect(await store.remove('flutter.config'), isTrue);

    expect(await store.setValue('String', 'flutter.config', 'a'), isTrue);

    expect(readFile(), {'flutter.config': 'a'});
  });

  test('the same value retries a save that failed', () async {
    final blocker = Directory('$path.$pid.tmp')..createSync();
    expect(await store.setValue('String', 'flutter.config', 'a'), isFalse);

    blocker.deleteSync();
    expect(await store.setValue('String', 'flutter.config', 'a'), isTrue);

    expect(readFile(), {'flutter.config': 'a'});
  });
}
