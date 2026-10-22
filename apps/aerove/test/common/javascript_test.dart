import 'dart:convert';

import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/common/javascript.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late List<({String script, String config})> calls;
  final engine = scriptEvaluator;

  setUp(() {
    calls = [];
  });

  tearDown(() {
    scriptEvaluator = engine;
  });

  void answerWith(String result) {
    scriptEvaluator = ({required String script, required String config}) async {
      calls.add((script: script, config: config));
      return result;
    };
  }

  test(
    'passes the profile text through and returns what the script produced',
    () async {
      final produced = json.encode({'mode': 'global'});
      answerWith(produced);

      final result = await handleEvaluate('main', '{"mode":"rule"}');

      expect(result, produced);
      expect(calls.single.config, '{"mode":"rule"}');
      expect(calls.single.script, 'main');
    },
  );

  test('reports what the engine threw', () async {
    scriptEvaluator = ({required String script, required String config}) async {
      throw Exception('ReferenceError: proxies is not defined');
    };

    await expectLater(
      handleEvaluate('main', '{}'),
      throwsA(
        isA<MessageException>().having(
          (error) => error.message,
          'message',
          contains('ReferenceError'),
        ),
      ),
    );
  });

  test('decodes a configuration object the script returned', () {
    expect(decodeScriptResult('{"mode":"global"}'), {'mode': 'global'});
  });

  test('rejects a result that is not a configuration object', () {
    expect(
      () => decodeScriptResult(json.encode([1, 2, 3])),
      throwsA(isA<MessageException>()),
    );
  });
}
