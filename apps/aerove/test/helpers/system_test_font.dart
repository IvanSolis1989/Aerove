import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

Future<ByteData>? _testFont;

Future<ByteData> loadSdkTestFont() => _testFont ??= _loadTestFont();

Future<ByteData> _loadTestFont() async {
  final bytes = await File(
    'test/fixtures/fonts/Roboto-Regular.ttf',
  ).readAsBytes();
  if (sha256.convert(bytes).toString() !=
      '79e851404657dac2106b3d22ad256d47824a9a5765458edb72c9102a45816d95') {
    throw StateError('Roboto test fixture SHA256 mismatch');
  }
  return ByteData.sublistView(bytes);
}
