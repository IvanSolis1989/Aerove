import 'dart:io';
import 'dart:typed_data';

Future<ByteData> loadSdkTestFont() async {
  var directory = File(Platform.resolvedExecutable).parent;
  final root = Platform.environment['FLUTTER_ROOT'];
  final candidates = <File>[
    if (root != null)
      File('$root/bin/cache/artifacts/material_fonts/roboto-regular.ttf'),
  ];
  for (var level = 0; level < 8; level++) {
    candidates.add(
      File('${directory.path}/artifacts/material_fonts/roboto-regular.ttf'),
    );
    directory = directory.parent;
  }
  for (final file in candidates) {
    if (await file.exists()) {
      return ByteData.sublistView(await file.readAsBytes());
    }
  }
  throw StateError('Flutter SDK test font unavailable');
}
