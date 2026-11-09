import 'dart:convert';
import 'package:fl_clash/common/candidate_build_identity.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  Map<String, Object> identity() => {
    'schema': 1,
    'product': 'aerove-lite',
    'source': 'a' * 40,
    'tree': 'b' * 40,
    'runId': '123',
    'runNumber': '350',
    'attempt': '2',
    'abi': 'arm64-v8a',
  };
  test('candidate label and copied data identify the original build', () {
    final result = CandidateBuildIdentity.parse(jsonEncode(identity()))!;
    expect(result.label, '#350.2 · aaaaaaaa · arm64-v8a');
    expect(
      result.details,
      contains('https://github.com/IvanSolis1989/Aerove/actions/runs/123'),
    );
    expect(result.details, contains('a' * 40));
  });
  test('unidentified and malformed inputs never invent a candidate', () {
    for (final input in [
      '{}',
      'null',
      '[]',
      'bad',
      ' ' * 4097,
      jsonEncode({'schema': 1, 'product': 'aerove-lite', 'identified': false}),
      jsonEncode({...identity(), 'source': '../secret'}),
      jsonEncode({...identity(), 'runId': 123}),
      jsonEncode({...identity(), 'abi': 'wrong'}),
      jsonEncode({...identity(), 'extra': 'ignored'}),
    ]) {
      expect(CandidateBuildIdentity.parse(input), isNull);
    }
  });
  test(
    'reads only the installed platform identity and tolerates old builds',
    () async {
      const channel = MethodChannel('com.follow.clash/app');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'getBuildIdentity');
        expect(call.arguments, isNull);
        return jsonEncode(identity());
      });
      expect(
        (await CandidateBuildIdentity.load())!.label,
        startsWith('#350.2'),
      );
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => throw PlatformException(code: 'missing'),
      );
      expect(await CandidateBuildIdentity.load(), isNull);
    },
  );
}
